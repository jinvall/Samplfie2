package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaybackState(
    val isPlaying: Boolean = false,
    val isLooping: Boolean = false,
    val playheadFraction: Float = 0f,
    val currentFrame: Int = 0,
    val totalFrames: Int = 0,
    val livePeak: Float = 0f,
    val liveRms: Float = 0f,
    val liveSpectrum: FloatArray = FloatArray(32)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlaybackState) return false
        return isPlaying == other.isPlaying &&
            isLooping == other.isLooping &&
            playheadFraction == other.playheadFraction &&
            currentFrame == other.currentFrame &&
            totalFrames == other.totalFrames &&
            livePeak == other.livePeak &&
            liveRms == other.liveRms &&
            liveSpectrum.contentEquals(other.liveSpectrum)
    }

    override fun hashCode(): Int {
        var result = isPlaying.hashCode()
        result = 31 * result + isLooping.hashCode()
        result = 31 * result + playheadFraction.hashCode()
        result = 31 * result + currentFrame
        result = 31 * result + totalFrames
        result = 31 * result + livePeak.hashCode()
        result = 31 * result + liveRms.hashCode()
        result = 31 * result + liveSpectrum.contentHashCode()
        return result
    }
}

/**
 * Low-latency AudioTrack streaming player supporting real-time FX buffer hot-swapping,
 * persistent loop playback across edits & extractions, playhead scrubbing, and live FFT telemetry.
 */
class AudioPlaybackEngine(
    private val onLiveChunkForTuner: (FloatArray, Int) -> Unit = { _, _ -> }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeBufferRef = AtomicReference<AudioBuffer?>(null)
    private val isPlayingFlag = AtomicBoolean(false)
    private val isLoopingFlag = AtomicBoolean(false)
    private val playSelectionOnlyFlag = AtomicBoolean(false)

    @Volatile
    private var selectionStartFraction = 0f

    @Volatile
    private var selectionEndFraction = 1f

    @Volatile
    private var currentFrameCursor = 0

    @Volatile
    private var chunkSizeFrames = 1024

    @Volatile
    private var masterGain = 0.85f

    @Volatile
    private var masterMuted = false

    private var playJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun setMasterVolume(volume: Float, muted: Boolean) {
        masterGain = volume.coerceIn(0f, 1f)
        masterMuted = muted
    }

    fun effectiveMasterGain(): Float = if (masterMuted) 0f else masterGain

    fun setBuffer(buffer: AudioBuffer?, bufferSizeFrames: Int = chunkSizeFrames) {
        updateBufferHot(buffer, bufferSizeFrames, preserveLoopPlayback = false)
    }

    fun updateBufferHot(
        buffer: AudioBuffer?,
        bufferSizeFrames: Int = chunkSizeFrames,
        preserveLoopPlayback: Boolean = false
    ) {
        chunkSizeFrames = bufferSizeFrames.coerceIn(256, 4096)
        val old = activeBufferRef.getAndSet(buffer)
        val total = buffer?.frameCount ?: 0
        val wasPlayingLoop = preserveLoopPlayback && isPlayingFlag.get() && isLoopingFlag.get()

        if (total <= 0) {
            currentFrameCursor = 0
        } else if (old != null && old.frameCount > 0 && currentFrameCursor > 0) {
            val prevFrac = (currentFrameCursor.toFloat() / old.frameCount.toFloat()).coerceIn(0f, 1f)
            currentFrameCursor = (prevFrac * total).toInt().coerceIn(0, total - 1)
        } else {
            currentFrameCursor = currentFrameCursor.coerceIn(0, max(0, total - 1))
        }

        _state.value = _state.value.copy(
            totalFrames = total,
            currentFrame = currentFrameCursor,
            playheadFraction = if (total > 0) currentFrameCursor.toFloat() / total else 0f
        )

        if (wasPlayingLoop && buffer != null && buffer.frameCount > 0) {
            if (playJob == null || playJob?.isActive != true) {
                play(onlySelection = playSelectionOnlyFlag.get())
            }
        }
    }

    fun setSelectionBounds(startFraction: Float, endFraction: Float, onlySelection: Boolean? = null) {
        selectionStartFraction = min(startFraction, endFraction).coerceIn(0f, 1f)
        selectionEndFraction = max(startFraction, endFraction).coerceIn(0f, 1f)
        if (onlySelection != null) {
            playSelectionOnlyFlag.set(onlySelection)
        }
    }

    fun setPlaySelectionOnly(onlySelection: Boolean) {
        playSelectionOnlyFlag.set(onlySelection)
    }

    fun setLooping(loop: Boolean) {
        isLoopingFlag.set(loop)
        _state.value = _state.value.copy(isLooping = loop)
    }

    fun seekToFraction(fraction: Float) {
        val buf = activeBufferRef.get() ?: return
        val clamped = fraction.coerceIn(0f, 1f)
        val frame = (clamped * buf.frameCount).toInt().coerceIn(0, max(0, buf.frameCount - 1))
        currentFrameCursor = frame
        val spectrum = AudioAnalyzer.computeSpectrumBands(buf, frame, 512, 32)
        _state.value = _state.value.copy(
            currentFrame = frame,
            playheadFraction = clamped,
            liveSpectrum = spectrum
        )
    }

    fun togglePlay(onlySelection: Boolean = playSelectionOnlyFlag.get()) {
        if (isPlayingFlag.get()) {
            pause()
        } else {
            play(onlySelection)
        }
    }

    fun play(onlySelection: Boolean = false) {
        val initialBuffer = activeBufferRef.get() ?: return
        if (initialBuffer.frameCount == 0) return
        playSelectionOnlyFlag.set(onlySelection)

        if (isPlayingFlag.get()) {
            pause()
        }

        val startFrameBound = if (onlySelection) {
            (selectionStartFraction * initialBuffer.frameCount).toInt()
                .coerceIn(0, max(0, initialBuffer.frameCount - 1))
        } else {
            0
        }
        val endFrameBound = if (onlySelection) {
            (selectionEndFraction * initialBuffer.frameCount).toInt()
                .coerceIn(startFrameBound + 1, initialBuffer.frameCount)
        } else {
            initialBuffer.frameCount
        }

        if (currentFrameCursor < startFrameBound || currentFrameCursor >= endFrameBound) {
            currentFrameCursor = startFrameBound
        }

        isPlayingFlag.set(true)
        _state.value = _state.value.copy(isPlaying = true)

        playJob = scope.launch {
            var track: AudioTrack? = null
            var openSampleRate = -1
            var openChannels = -1
            try {
                while (isPlayingFlag.get()) {
                    val buf = activeBufferRef.get() ?: break
                    if (buf.frameCount == 0) break

                    if (buf.sampleRate != openSampleRate || buf.channels != openChannels) {
                        runCatching {
                            track?.pause()
                            track?.flush()
                            track?.release()
                        }
                        track = openAudioTrackOrNull(buf.sampleRate, buf.channels)
                        openSampleRate = buf.sampleRate
                        openChannels = buf.channels
                    }

                    val selMode = playSelectionOnlyFlag.get()
                    val activeStart = if (selMode) {
                        (selectionStartFraction * buf.frameCount).toInt()
                            .coerceIn(0, max(0, buf.frameCount - 1))
                    } else {
                        0
                    }
                    val activeEnd = if (selMode) {
                        (selectionEndFraction * buf.frameCount).toInt()
                            .coerceIn(activeStart + 1, buf.frameCount)
                    } else {
                        buf.frameCount
                    }

                    if (currentFrameCursor >= activeEnd || currentFrameCursor < activeStart) {
                        if (isLoopingFlag.get()) {
                            currentFrameCursor = activeStart
                        } else {
                            currentFrameCursor = activeStart
                            isPlayingFlag.set(false)
                            break
                        }
                    }

                    val framesToWrite = min(chunkSizeFrames, activeEnd - currentFrameCursor)
                    if (framesToWrite <= 0) {
                        if (isLoopingFlag.get()) {
                            currentFrameCursor = activeStart
                            continue
                        } else {
                            break
                        }
                    }

                    val sampleStart = currentFrameCursor * buf.channels
                    val sampleEnd = (sampleStart + framesToWrite * buf.channels).coerceAtMost(buf.samples.size)
                    if (sampleStart >= buf.samples.size) {
                        currentFrameCursor = activeStart
                        continue
                    }
                    val slice = buf.samples.copyOfRange(sampleStart, sampleEnd)
                    val outGain = effectiveMasterGain()
                    val shorts = ShortArray(slice.size) { i ->
                        ((slice[i] * outGain).coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    if (track != null) {
                        track.write(shorts, 0, shorts.size)
                    } else {
                        val ms = ((framesToWrite * 1000L) / buf.sampleRate.coerceAtLeast(8000)).coerceIn(5L, 45L)
                        Thread.sleep(ms)
                    }

                    val monoChunk = if (buf.channels > 1) {
                        FloatArray(framesToWrite) { f ->
                            var s = 0f
                            for (c in 0 until buf.channels) {
                                val idx = f * buf.channels + c
                                if (idx < slice.size) s += slice[idx]
                            }
                            s / buf.channels
                        }
                    } else {
                        slice
                    }
                    onLiveChunkForTuner(monoChunk, buf.sampleRate)

                    var peak = 0f
                    var sumSq = 0.0
                    for (s in slice) {
                        val a = abs(s)
                        if (a > peak) peak = a
                        sumSq += s * s
                    }
                    val rms = if (slice.isNotEmpty()) sqrt(sumSq / slice.size).toFloat() else 0f
                    val spectrum = AudioAnalyzer.computeSpectrumBands(buf, currentFrameCursor, 512, 32)

                    currentFrameCursor += framesToWrite
                    if (currentFrameCursor >= activeEnd && isLoopingFlag.get()) {
                        currentFrameCursor = activeStart
                    }
                    val frac = currentFrameCursor.toFloat() / buf.frameCount.toFloat()

                    _state.value = _state.value.copy(
                        isPlaying = isPlayingFlag.get(),
                        currentFrame = currentFrameCursor,
                        totalFrames = buf.frameCount,
                        playheadFraction = frac.coerceIn(0f, 1f),
                        livePeak = peak,
                        liveRms = rms,
                        liveSpectrum = spectrum
                    )
                }
            } catch (_: Exception) {
                isPlayingFlag.set(false)
            } finally {
                runCatching {
                    track?.pause()
                    track?.flush()
                    track?.release()
                }
                _state.value = _state.value.copy(
                    isPlaying = isPlayingFlag.get(),
                    livePeak = 0f,
                    liveRms = 0f,
                    playheadFraction = activeBufferRef.get()?.let { b ->
                        if (b.frameCount > 0) (currentFrameCursor.toFloat() / b.frameCount).coerceIn(0f, 1f) else 0f
                    } ?: 0f
                )
            }
        }
    }

    private fun openAudioTrackOrNull(sampleRate: Int, channels: Int): AudioTrack? = runCatching {
        val channelMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(1024)
        val trackBufBytes = max(minBufBytes, chunkSizeFrames * channels * 2)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(trackBufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }.getOrNull()

    fun pause() {
        isPlayingFlag.set(false)
        playJob?.cancel()
        playJob = null
        _state.value = _state.value.copy(isPlaying = false, livePeak = 0f, liveRms = 0f)
    }

    fun stop() {
        isPlayingFlag.set(false)
        playJob?.cancel()
        playJob = null
        val buf = activeBufferRef.get()
        val resetFrame = if (buf != null && buf.frameCount > 0) {
            (selectionStartFraction * buf.frameCount).toInt().coerceIn(0, buf.frameCount - 1)
        } else {
            0
        }
        currentFrameCursor = resetFrame
        _state.value = _state.value.copy(
            isPlaying = false,
            currentFrame = resetFrame,
            playheadFraction = if (buf != null && buf.frameCount > 0) {
                resetFrame.toFloat() / buf.frameCount
            } else {
                0f
            },
            livePeak = 0f,
            liveRms = 0f
        )
    }

    fun release() {
        stop()
    }
}
