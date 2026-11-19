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
 * selection-bounded looping, playhead scrubbing, and live FFT telemetry.
 */
class AudioPlaybackEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeBufferRef = AtomicReference<AudioBuffer?>(null)
    private val isPlayingFlag = AtomicBoolean(false)
    private val isLoopingFlag = AtomicBoolean(false)

    @Volatile
    private var selectionStartFraction = 0f

    @Volatile
    private var selectionEndFraction = 1f

    @Volatile
    private var currentFrameCursor = 0

    @Volatile
    private var chunkSizeFrames = 1024

    private var playJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun setBuffer(buffer: AudioBuffer?, bufferSizeFrames: Int = chunkSizeFrames) {
        chunkSizeFrames = bufferSizeFrames.coerceIn(256, 4096)
        val old = activeBufferRef.getAndSet(buffer)
        val total = buffer?.frameCount ?: 0
        if (old?.sampleRate != buffer?.sampleRate || old?.channels != buffer?.channels) {
            currentFrameCursor = 0
        } else if (total > 0) {
            currentFrameCursor = currentFrameCursor.coerceIn(0, total - 1)
        } else {
            currentFrameCursor = 0
        }
        _state.value = _state.value.copy(
            totalFrames = total,
            currentFrame = currentFrameCursor,
            playheadFraction = if (total > 0) currentFrameCursor.toFloat() / total else 0f
        )
    }

    fun setSelectionBounds(startFraction: Float, endFraction: Float) {
        selectionStartFraction = min(startFraction, endFraction).coerceIn(0f, 1f)
        selectionEndFraction = max(startFraction, endFraction).coerceIn(0f, 1f)
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

    fun togglePlay(onlySelection: Boolean = false) {
        if (isPlayingFlag.get()) {
            pause()
        } else {
            play(onlySelection)
        }
    }

    fun play(onlySelection: Boolean = false) {
        val initialBuffer = activeBufferRef.get() ?: return
        if (initialBuffer.frameCount == 0) return

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
            val channelMask = if (initialBuffer.channels == 2) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }
            val minBufBytes = AudioTrack.getMinBufferSize(
                initialBuffer.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1024)
            val trackBufBytes = max(minBufBytes, chunkSizeFrames * initialBuffer.channels * 2)

            var track: AudioTrack? = null
            try {
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(initialBuffer.sampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(trackBufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.play()

                while (isPlayingFlag.get()) {
                    val buf = activeBufferRef.get() ?: break
                    if (buf.frameCount == 0) break

                    val activeStart = if (onlySelection) {
                        (selectionStartFraction * buf.frameCount).toInt()
                            .coerceIn(0, max(0, buf.frameCount - 1))
                    } else {
                        0
                    }
                    val activeEnd = if (onlySelection) {
                        (selectionEndFraction * buf.frameCount).toInt()
                            .coerceIn(activeStart + 1, buf.frameCount)
                    } else {
                        buf.frameCount
                    }

                    if (currentFrameCursor >= activeEnd) {
                        if (isLoopingFlag.get()) {
                            currentFrameCursor = activeStart
                        } else {
                            currentFrameCursor = activeStart
                            isPlayingFlag.set(false)
                            break
                        }
                    }

                    val framesToWrite = min(chunkSizeFrames, activeEnd - currentFrameCursor)
                    if (framesToWrite <= 0) break

                    val sampleStart = currentFrameCursor * buf.channels
                    val sampleEnd = (sampleStart + framesToWrite * buf.channels).coerceAtMost(buf.samples.size)
                    val slice = buf.samples.copyOfRange(sampleStart, sampleEnd)

                    val shorts = ShortArray(slice.size) { i ->
                        (slice[i].coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    track.write(shorts, 0, shorts.size)

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
                try {
                    track?.pause()
                    track?.flush()
                    track?.release()
                } catch (_: Exception) {
                }
                _state.value = _state.value.copy(
                    isPlaying = false,
                    livePeak = 0f,
                    liveRms = 0f,
                    playheadFraction = activeBufferRef.get()?.let { b ->
                        if (b.frameCount > 0) (currentFrameCursor.toFloat() / b.frameCount).coerceIn(0f, 1f) else 0f
                    } ?: 0f
                )
            }
        }
    }

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
