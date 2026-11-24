package com.samplfie.desktop.audio

import com.samplfie.desktop.model.DesktopAudioBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DesktopPlaybackState(
    val isPlaying: Boolean = false,
    val isLooping: Boolean = false,
    val playheadFraction: Float = 0f,
    val livePeak: Float = 0f,
    val liveRms: Float = 0f,
    val liveSpectrum: FloatArray = FloatArray(48)
)

data class DesktopMicState(
    val isRecording: Boolean = false,
    val isTunerMonitorOn: Boolean = false,
    val isLiveMonitoring: Boolean = false,
    val monitorPassthrough: Boolean = false,
    val monitorGain: Float = 0.7f,
    val monitorWindowSec: Int = 12,
    val liveSyncToDeck: Boolean = true,
    val bufferedSamples: Int = 0,
    val capturedSeconds: Float = 0f,
    val peakLevel: Float = 0f,
    val statusNote: String = "LINUX AUDIO READY"
)

/**
 * Native Linux ALSA / PulseAudio / PipeWire Java Sound engine supporting:
 * - Hot-swappable DesktopAudioBuffer playback that persists uninterrupted across edits & extractions when looping
 * - Live microphone capture into editable WAV buffers
 * - Continuous Live Monitor Mode with rolling capture window, optional SourceDataLine passthrough, and live Deck loading
 * - Live microphone streaming into the Chromatic Tuner
 * - Reference tuning tones & one-shot pad auditioning without stopping deck loop playback
 */
class LinuxSoundEngine(
    private val onLiveSamplesForTuner: (FloatArray, Int) -> Unit = { _, _ -> },
    private val onLiveRecordedAudio: (FloatArray, Int) -> Unit = { _, _ -> },
    private val onLiveMonitorDeckSnapshot: (DesktopAudioBuffer) -> Unit = { _ -> }
) {
    private val _playbackState = MutableStateFlow(DesktopPlaybackState())
    val playbackState: StateFlow<DesktopPlaybackState> = _playbackState.asStateFlow()

    private val _micState = MutableStateFlow(DesktopMicState())
    val micState: StateFlow<DesktopMicState> = _micState.asStateFlow()

    private val bufferRef = AtomicReference<DesktopAudioBuffer?>(null)

    @Volatile
    private var selectionStart = 0f

    @Volatile
    private var selectionEnd = 1f

    @Volatile
    private var playSelectionOnly = false

    @Volatile
    private var cursorFrame = 0

    @Volatile
    private var masterGain = 0.85f

    @Volatile
    private var masterMuted = false

    fun setMasterVolume(volume: Float, muted: Boolean) {
        masterGain = volume.coerceIn(0f, 1f)
        masterMuted = muted
    }

    fun effectiveMasterGain(): Float = if (masterMuted) 0f else masterGain

    private val playingFlag = AtomicBoolean(false)
    private val loopingFlag = AtomicBoolean(false)
    private var playThread: Thread? = null

    private val micRunning = AtomicBoolean(false)
    private var micThread: Thread? = null
    private val recordedSamples = mutableListOf<Float>()
    private var recordedSampleRate = 44100

    private val monitorRunning = AtomicBoolean(false)
    private var monitorThread: Thread? = null
    private val monitorLock = Any()

    @Volatile
    private var monitorSampleRate = 44100

    @Volatile
    private var monitorChannels = 1

    @Volatile
    private var monitorPassthrough = false

    @Volatile
    private var monitorGain = 0.7f

    @Volatile
    private var monitorWindowSec = 12

    @Volatile
    private var liveSyncToDeck = true

    private var monitorRingBuf = FloatArray(44100 * 12)
    private var monitorWritePos = 0
    private var monitorCount = 0
    private var lastMonitorSyncMs = 0L

    /**
     * Updates the playback buffer. When [preserveLoopPlayback] is true and a loop is currently playing,
     * the buffer is hot-swapped seamlessly so loop playback survives edits, FX changes, and extractions until STOP is pressed.
     */
    fun updateBufferHot(newBuf: DesktopAudioBuffer?, preserveLoopPlayback: Boolean = false) {
        val oldBuf = bufferRef.getAndSet(newBuf)
        val wasLoopingAndPlaying = preserveLoopPlayback && playingFlag.get() && loopingFlag.get()
        if (!wasLoopingAndPlaying) {
            stopPlayback()
            cursorFrame = 0
            _playbackState.value = _playbackState.value.copy(
                playheadFraction = 0f,
                livePeak = 0f,
                liveRms = 0f,
                liveSpectrum = SignalAnalyzer.computeSpectrumBands(newBuf, 0, 1024, 48)
            )
            return
        }

        if (newBuf == null || newBuf.frameCount == 0) {
            stopPlayback()
            return
        }

        val total = newBuf.frameCount
        cursorFrame = cursorFrame.coerceIn(0, total - 1)
        val formatChanged = oldBuf?.sampleRate != newBuf.sampleRate || oldBuf.channels != newBuf.channels
        if (formatChanged) {
            // Restart stream smoothly on new audio format while preserving loop & playing state
            playingFlag.set(false)
            runCatching { playThread?.join(120) }
            startPlayback()
        } else {
            _playbackState.value = _playbackState.value.copy(
                playheadFraction = cursorFrame.toFloat() / total.coerceAtLeast(1),
                liveSpectrum = SignalAnalyzer.computeSpectrumBands(newBuf, cursorFrame, 1024, 48)
            )
        }
    }

    fun setBuffer(newBuf: DesktopAudioBuffer?) = updateBufferHot(newBuf, preserveLoopPlayback = false)

    fun setSelection(start: Float, end: Float, onlySelection: Boolean) {
        selectionStart = min(start, end).coerceIn(0f, 1f)
        selectionEnd = max(start, end).coerceIn(0f, 1f)
        playSelectionOnly = onlySelection
    }

    fun setLooping(loop: Boolean) {
        loopingFlag.set(loop)
        _playbackState.value = _playbackState.value.copy(isLooping = loop)
    }

    fun seekTo(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        val b = bufferRef.get()
        val frame = ((b?.frameCount ?: 0) * clamped).toInt().coerceIn(0, max(0, (b?.frameCount ?: 1) - 1))
        cursorFrame = frame
        _playbackState.value = _playbackState.value.copy(
            playheadFraction = clamped,
            liveSpectrum = SignalAnalyzer.computeSpectrumBands(b, frame, 1024, 48)
        )
    }

    fun togglePlay() {
        if (playingFlag.get()) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    fun startPlayback() {
        val initial = bufferRef.get() ?: return
        if (initial.frameCount == 0) return
        if (playingFlag.getAndSet(true)) return

        _playbackState.value = _playbackState.value.copy(isPlaying = true)
        playThread = thread(isDaemon = true, name = "SamplfieDesktopPlay") {
            var line: SourceDataLine? = null
            var openedRate = initial.sampleRate
            var openedChannels = initial.channels
            try {
                line = openOutputLine(openedRate, openedChannels)

                val chunkFrames = 1024
                while (playingFlag.get()) {
                    val b = bufferRef.get() ?: break
                    if (b.frameCount == 0) break

                    if (b.sampleRate != openedRate || b.channels != openedChannels) {
                        runCatching { line?.drain(); line?.stop(); line?.close() }
                        openedRate = b.sampleRate
                        openedChannels = b.channels
                        line = openOutputLine(openedRate, openedChannels)
                    }

                    val startFrame = if (playSelectionOnly) {
                        (selectionStart * b.frameCount).toInt().coerceIn(0, b.frameCount - 1)
                    } else {
                        0
                    }
                    val endFrame = if (playSelectionOnly) {
                        (selectionEnd * b.frameCount).toInt().coerceIn(startFrame + 1, b.frameCount)
                    } else {
                        b.frameCount
                    }

                    if (cursorFrame < startFrame) {
                        cursorFrame = startFrame
                    }
                    if (cursorFrame >= endFrame) {
                        if (loopingFlag.get()) {
                            cursorFrame = startFrame
                        } else {
                            break
                        }
                    }

                    val curPos = cursorFrame.coerceIn(startFrame, max(startFrame, endFrame - 1))
                    val count = min(chunkFrames, endFrame - curPos)
                    if (count <= 0) {
                        if (loopingFlag.get()) {
                            cursorFrame = startFrame
                            continue
                        } else {
                            break
                        }
                    }
                    val sampleLo = (curPos * b.channels).coerceIn(0, b.samples.size)
                    val sampleHi = ((curPos + count) * b.channels).coerceIn(sampleLo, b.samples.size)
                    val slice = b.samples.copyOfRange(sampleLo, sampleHi)
                    val outGain = effectiveMasterGain()
                    val scaledSlice = if (outGain >= 0.999f) slice else FloatArray(slice.size) { i -> (slice[i] * outGain).coerceIn(-1f, 1f) }
                    val pcm = DesktopWavCodec.floatToPcm16Bytes(scaledSlice)
                    val frameMs = (count * 1000L) / b.sampleRate.coerceAtLeast(8000)

                    if (line != null) {
                        line.write(pcm, 0, pcm.size)
                    } else {
                        Thread.sleep(frameMs.coerceAtLeast(10L))
                    }

                    var peak = 0f
                    var sq = 0.0
                    for (s in slice) {
                        val a = abs(s)
                        if (a > peak) peak = a
                        sq += s * s
                    }
                    val rms = sqrt(sq / max(1, slice.size)).toFloat()
                    val monoChunk = if (b.channels > 1) {
                        val monoLen = slice.size / b.channels
                        FloatArray(monoLen) { i -> 0.5f * (slice[i * 2] + slice[i * 2 + 1]) }
                    } else {
                        slice
                    }
                    if (monoChunk.isNotEmpty()) {
                        onLiveSamplesForTuner(monoChunk, b.sampleRate)
                    }

                    val nextCursor = curPos + count
                    cursorFrame = if (nextCursor >= endFrame && loopingFlag.get()) startFrame else nextCursor
                    val frac = cursorFrame.toFloat() / b.frameCount.coerceAtLeast(1)
                    _playbackState.value = _playbackState.value.copy(
                        playheadFraction = frac.coerceIn(0f, 1f),
                        livePeak = peak,
                        liveRms = rms,
                        liveSpectrum = SignalAnalyzer.computeBandsFromMonoWindow(monoChunk, 48)
                    )
                }
            } catch (_: Exception) {
            } finally {
                playingFlag.set(false)
                runCatching { line?.drain(); line?.stop(); line?.close() }
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    livePeak = 0f,
                    liveRms = 0f
                )
            }
        }
    }

    private fun openOutputLine(sampleRate: Int, channels: Int, bufferBytes: Int = 4096): SourceDataLine? {
        return runCatching {
            val fmt = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
            val info = DataLine.Info(SourceDataLine::class.java, fmt)
            if (!AudioSystem.isLineSupported(info)) return null
            (AudioSystem.getLine(info) as SourceDataLine).apply {
                open(fmt, bufferBytes)
                start()
            }
        }.getOrNull()
    }

    fun pausePlayback() {
        playingFlag.set(false)
        _playbackState.value = _playbackState.value.copy(isPlaying = false)
    }

    fun stopPlayback() {
        playingFlag.set(false)
        runCatching { playThread?.join(180) }
        cursorFrame = 0
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            playheadFraction = 0f,
            livePeak = 0f,
            liveRms = 0f
        )
    }

    fun playOneShot(buf: DesktopAudioBuffer) {
        if (buf.frameCount == 0) return
        thread(isDaemon = true, name = "SamplfieDesktopOneShot") {
            val outGain = effectiveMasterGain()
            val scaled = if (outGain >= 0.999f) buf.samples else FloatArray(buf.samples.size) { i -> (buf.samples[i] * outGain).coerceIn(-1f, 1f) }
            val pcm = DesktopWavCodec.floatToPcm16Bytes(scaled)
            try {
                val fmt = AudioFormat(buf.sampleRate.toFloat(), 16, buf.channels, true, false)
                val info = DataLine.Info(SourceDataLine::class.java, fmt)
                if (AudioSystem.isLineSupported(info)) {
                    (AudioSystem.getLine(info) as SourceDataLine).use { line ->
                        line.open(fmt, 4096)
                        line.start()
                        line.write(pcm, 0, pcm.size)
                        line.drain()
                        line.stop()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun playReferenceTone(freqHz: Double, durationMs: Int = 700, sampleRate: Int = 44100) {
        thread(isDaemon = true, name = "SamplfieRefTone") {
            val frames = (sampleRate * (durationMs / 1000.0)).toInt()
            val outGain = effectiveMasterGain()
            val floats = FloatArray(frames) { i ->
                val t = i.toDouble() / sampleRate
                val env = min(1f, i / 400f) * min(1f, (frames - 1 - i) / 600f)
                (sin(2.0 * PI * freqHz * t) * 0.45 * env * outGain).toFloat()
            }
            val pcm = DesktopWavCodec.floatToPcm16Bytes(floats)
            try {
                val fmt = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
                val info = DataLine.Info(SourceDataLine::class.java, fmt)
                if (AudioSystem.isLineSupported(info)) {
                    (AudioSystem.getLine(info) as SourceDataLine).use { line ->
                        line.open(fmt, 4096)
                        line.start()
                        line.write(pcm, 0, pcm.size)
                        line.drain()
                        line.stop()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun startMicCapture(sampleRate: Int = 44100, tunerOnly: Boolean = false) {
        if (monitorRunning.get()) {
            stopLiveMonitorAndCollectBuffer()
        }
        if (micRunning.getAndSet(true)) return
        recordedSampleRate = sampleRate
        synchronized(recordedSamples) { recordedSamples.clear() }
        _micState.value = _micState.value.copy(
            isRecording = !tunerOnly,
            isTunerMonitorOn = tunerOnly,
            isLiveMonitoring = false,
            statusNote = if (tunerOnly) "TUNER LIVE MIC ACTIVE" else "RECORDING LINUX MIC…"
        )

        micThread = thread(isDaemon = true, name = "SamplfieDesktopMic") {
            var targetLine: TargetDataLine? = null
            try {
                val fmt = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
                val info = DataLine.Info(TargetDataLine::class.java, fmt)
                if (!AudioSystem.isLineSupported(info)) {
                    _micState.value = _micState.value.copy(
                        isRecording = false,
                        isTunerMonitorOn = false,
                        statusNote = "NO LINUX MIC DEVICE FOUND"
                    )
                    micRunning.set(false)
                    return@thread
                }
                targetLine = (AudioSystem.getLine(info) as TargetDataLine).apply {
                    open(fmt, 4096)
                    start()
                }
                val byteBuf = ByteArray(4096)
                val rollingTuner = FloatArray(4096)
                var rollingFill = 0

                while (micRunning.get()) {
                    val n = targetLine.read(byteBuf, 0, byteBuf.size)
                    if (n > 1) {
                        val floats = DesktopWavCodec.pcm16BytesToFloats(byteBuf, n)
                        if (!tunerOnly) {
                            synchronized(recordedSamples) {
                                for (s in floats) recordedSamples.add(s)
                            }
                            onLiveRecordedAudio(floats, sampleRate)
                        }
                        var peak = 0f
                        for (s in floats) peak = max(peak, abs(s))

                        for (s in floats) {
                            if (rollingFill < rollingTuner.size) rollingTuner[rollingFill++] = s
                        }
                        if (rollingFill >= 2048) {
                            onLiveSamplesForTuner(rollingTuner.copyOfRange(0, rollingFill), sampleRate)
                            rollingFill = 0
                        }

                        val sec = if (tunerOnly) 0f else synchronized(recordedSamples) {
                            recordedSamples.size.toFloat() / sampleRate
                        }
                        _micState.value = _micState.value.copy(capturedSeconds = sec, peakLevel = peak)
                    }
                }
            } catch (e: Exception) {
                _micState.value = _micState.value.copy(
                    isRecording = false,
                    isTunerMonitorOn = false,
                    statusNote = "MIC ERROR: ${e.message ?: "Unavailable"}"
                )
            } finally {
                micRunning.set(false)
                runCatching { targetLine?.stop(); targetLine?.close() }
            }
        }
    }

    fun stopMicAndCollectBuffer(name: String = "LINUX_MIC_TAKE"): DesktopAudioBuffer? {
        val wasRecording = _micState.value.isRecording
        micRunning.set(false)
        runCatching { micThread?.join(250) }
        _micState.value = _micState.value.copy(
            isRecording = false,
            isTunerMonitorOn = false,
            peakLevel = 0f,
            statusNote = "LINUX AUDIO READY"
        )
        if (!wasRecording) return null
        val samples = synchronized(recordedSamples) { recordedSamples.toFloatArray() }
        if (samples.size < 256) return null
        return DesktopAudioBuffer(samples, recordedSampleRate, 1, name)
    }

    // --- CONTINUOUS LIVE MONITOR MODE -> LOAD INTO DECK ---

    fun setMonitorPassthrough(enabled: Boolean) {
        monitorPassthrough = enabled
        _micState.value = _micState.value.copy(monitorPassthrough = enabled)
    }

    fun setMonitorGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        monitorGain = clamped
        _micState.value = _micState.value.copy(monitorGain = clamped)
    }

    fun setMonitorWindowSec(seconds: Int) {
        val clamped = seconds.coerceIn(2, 60)
        monitorWindowSec = clamped
        synchronized(monitorLock) {
            val newCap = (clamped * monitorSampleRate.coerceAtLeast(8000) * monitorChannels.coerceAtLeast(1)).coerceAtLeast(1024)
            if (newCap != monitorRingBuf.size) {
                val recent = readMonitorSamplesLocked(limit = newCap)
                val nextBuf = FloatArray(newCap)
                System.arraycopy(recent, 0, nextBuf, 0, recent.size)
                monitorRingBuf = nextBuf
                monitorCount = recent.size
                monitorWritePos = recent.size % newCap
            }
        }
        val buffered = synchronized(monitorLock) { monitorCount }
        _micState.value = _micState.value.copy(
            monitorWindowSec = clamped,
            bufferedSamples = buffered,
            capturedSeconds = buffered.toFloat() / (monitorSampleRate * monitorChannels).coerceAtLeast(1)
        )
    }

    fun setLiveSyncToDeck(enabled: Boolean) {
        liveSyncToDeck = enabled
        _micState.value = _micState.value.copy(liveSyncToDeck = enabled)
    }

    fun startLiveMonitor(
        sampleRate: Int = 44100,
        channels: Int = 1,
        windowSeconds: Int = monitorWindowSec,
        passthrough: Boolean = monitorPassthrough,
        gain: Float = monitorGain,
        syncToDeck: Boolean = liveSyncToDeck
    ) {
        if (micRunning.get()) {
            stopMicAndCollectBuffer()
        }
        if (monitorRunning.getAndSet(true)) return

        monitorSampleRate = sampleRate.coerceAtLeast(8000)
        monitorChannels = channels.coerceIn(1, 2)
        monitorWindowSec = windowSeconds.coerceIn(2, 60)
        monitorPassthrough = passthrough
        monitorGain = gain.coerceIn(0f, 1f)
        liveSyncToDeck = syncToDeck
        lastMonitorSyncMs = 0L

        synchronized(monitorLock) {
            val cap = (monitorWindowSec * monitorSampleRate * monitorChannels).coerceAtLeast(2048)
            monitorRingBuf = FloatArray(cap)
            monitorWritePos = 0
            monitorCount = 0
        }

        _micState.value = _micState.value.copy(
            isRecording = false,
            isTunerMonitorOn = false,
            isLiveMonitoring = true,
            monitorPassthrough = monitorPassthrough,
            monitorGain = monitorGain,
            monitorWindowSec = monitorWindowSec,
            liveSyncToDeck = liveSyncToDeck,
            bufferedSamples = 0,
            capturedSeconds = 0f,
            statusNote = "LIVE MONITOR ACTIVE (${monitorWindowSec}s RING)"
        )

        monitorThread = thread(isDaemon = true, name = "SamplfieDesktopLiveMonitor") {
            var targetLine: TargetDataLine? = null
            var outLine: SourceDataLine? = null
            try {
                val fmt = AudioFormat(monitorSampleRate.toFloat(), 16, monitorChannels, true, false)
                val info = DataLine.Info(TargetDataLine::class.java, fmt)
                if (!AudioSystem.isLineSupported(info)) {
                    _micState.value = _micState.value.copy(
                        isLiveMonitoring = false,
                        statusNote = "NO LINUX INPUT DEVICE FOR LIVE MONITOR"
                    )
                    monitorRunning.set(false)
                    return@thread
                }
                targetLine = (AudioSystem.getLine(info) as TargetDataLine).apply {
                    open(fmt, 4096)
                    start()
                }
                val byteBuf = ByteArray(2048 * monitorChannels)
                val rollingTuner = FloatArray(4096)
                var rollingFill = 0

                while (monitorRunning.get()) {
                    val n = targetLine.read(byteBuf, 0, byteBuf.size)
                    if (n > 1) {
                        val floats = DesktopWavCodec.pcm16BytesToFloats(byteBuf, n)
                        ingestLiveMonitorChunkInternal(floats, monitorSampleRate, monitorChannels, System.currentTimeMillis(), forceSnapshot = false)

                        val mono = mixToMono(floats, monitorChannels)
                        for (s in mono) {
                            if (rollingFill < rollingTuner.size) rollingTuner[rollingFill++] = s
                        }
                        if (rollingFill >= 2048) {
                            onLiveSamplesForTuner(rollingTuner.copyOfRange(0, rollingFill), monitorSampleRate)
                            rollingFill = 0
                        }

                        if (monitorPassthrough) {
                            if (outLine == null) {
                                outLine = openOutputLine(monitorSampleRate, monitorChannels, 4096)
                            }
                            outLine?.let { out ->
                                val outGain = (monitorGain * effectiveMasterGain()).coerceIn(0f, 1f)
                                val scaled = FloatArray(floats.size) { i -> (floats[i] * outGain).coerceIn(-1f, 1f) }
                                val pcmOut = DesktopWavCodec.floatToPcm16Bytes(scaled)
                                out.write(pcmOut, 0, pcmOut.size)
                            }
                        } else if (outLine != null) {
                            runCatching { outLine?.drain(); outLine?.stop(); outLine?.close() }
                            outLine = null
                        }
                    }
                }
            } catch (e: Exception) {
                _micState.value = _micState.value.copy(
                    isLiveMonitoring = false,
                    statusNote = "MONITOR ERROR: ${e.message ?: "Unavailable"}"
                )
            } finally {
                monitorRunning.set(false)
                runCatching { outLine?.drain(); outLine?.stop(); outLine?.close() }
                runCatching { targetLine?.stop(); targetLine?.close() }
            }
        }
    }

    /**
     * Pure-Kotlin deterministic helper to ingest PCM samples into the Live Monitor rolling buffer
     * and trigger live Deck snapshot loading during headless/unit tests.
     */
    fun ingestLiveMonitorSamplesForTest(
        samples: FloatArray,
        sampleRate: Int = monitorSampleRate,
        channels: Int = monitorChannels,
        windowSeconds: Int = monitorWindowSec,
        syncToDeck: Boolean = liveSyncToDeck,
        forceSnapshot: Boolean = true
    ): DesktopAudioBuffer? {
        if (sampleRate != monitorSampleRate || channels != monitorChannels || windowSeconds != monitorWindowSec) {
            monitorSampleRate = sampleRate.coerceAtLeast(8000)
            monitorChannels = channels.coerceIn(1, 2)
            monitorWindowSec = windowSeconds.coerceIn(2, 60)
            synchronized(monitorLock) {
                val cap = (monitorWindowSec * monitorSampleRate * monitorChannels).coerceAtLeast(256)
                if (monitorRingBuf.size != cap) {
                    monitorRingBuf = FloatArray(cap)
                    monitorWritePos = 0
                    monitorCount = 0
                }
            }
        }
        liveSyncToDeck = syncToDeck
        if (!_micState.value.isLiveMonitoring) {
            _micState.value = _micState.value.copy(
                isLiveMonitoring = true,
                monitorWindowSec = monitorWindowSec,
                liveSyncToDeck = liveSyncToDeck,
                statusNote = "LIVE MONITOR ACTIVE (${monitorWindowSec}s RING)"
            )
        }
        return ingestLiveMonitorChunkInternal(
            floats = samples,
            sampleRate = monitorSampleRate,
            channels = monitorChannels,
            nowMs = System.currentTimeMillis(),
            forceSnapshot = forceSnapshot
        )
    }

    private fun ingestLiveMonitorChunkInternal(
        floats: FloatArray,
        sampleRate: Int,
        channels: Int,
        nowMs: Long,
        forceSnapshot: Boolean
    ): DesktopAudioBuffer? {
        if (floats.isEmpty()) return null
        val totalBuffered: Int
        synchronized(monitorLock) {
            appendMonitorSamplesLocked(floats)
            totalBuffered = monitorCount
        }
        var peak = 0f
        for (s in floats) peak = max(peak, abs(s))
        val sec = totalBuffered.toFloat() / (sampleRate * channels).coerceAtLeast(1)
        _micState.value = _micState.value.copy(
            isLiveMonitoring = true,
            monitorPassthrough = monitorPassthrough,
            monitorGain = monitorGain,
            monitorWindowSec = monitorWindowSec,
            liveSyncToDeck = liveSyncToDeck,
            bufferedSamples = totalBuffered,
            capturedSeconds = sec,
            peakLevel = peak
        )
        val shouldSnapshot = forceSnapshot || (liveSyncToDeck && totalBuffered >= 256 * channels && (nowMs - lastMonitorSyncMs) >= 220L)
        if (!shouldSnapshot) return null
        lastMonitorSyncMs = nowMs
        val snap = snapshotLiveMonitorBuffer("LIVE_MONITOR") ?: return null
        onLiveMonitorDeckSnapshot(snap)
        return snap
    }

    fun snapshotLiveMonitorBuffer(name: String = "LIVE_MONITOR"): DesktopAudioBuffer? {
        val samples = synchronized(monitorLock) {
            if (monitorCount <= 0) FloatArray(0) else readMonitorSamplesLocked()
        }
        if (samples.size < 64) return null
        return DesktopAudioBuffer(samples, monitorSampleRate, monitorChannels, name)
    }

    fun stopLiveMonitorAndCollectBuffer(name: String = "LIVE_MONITOR"): DesktopAudioBuffer? {
        monitorRunning.set(false)
        runCatching { monitorThread?.join(250) }
        val snap = snapshotLiveMonitorBuffer(name)
        _micState.value = _micState.value.copy(
            isLiveMonitoring = false,
            peakLevel = 0f,
            statusNote = "LINUX AUDIO READY"
        )
        return snap
    }

    private fun appendMonitorSamplesLocked(samples: FloatArray) {
        val cap = monitorRingBuf.size.coerceAtLeast(1)
        if (samples.size >= cap) {
            System.arraycopy(samples, samples.size - cap, monitorRingBuf, 0, cap)
            monitorWritePos = 0
            monitorCount = cap
            return
        }
        val firstPart = min(samples.size, cap - monitorWritePos)
        System.arraycopy(samples, 0, monitorRingBuf, monitorWritePos, firstPart)
        val secondPart = samples.size - firstPart
        if (secondPart > 0) {
            System.arraycopy(samples, firstPart, monitorRingBuf, 0, secondPart)
        }
        monitorWritePos = (monitorWritePos + samples.size) % cap
        monitorCount = min(cap, monitorCount + samples.size)
    }

    private fun readMonitorSamplesLocked(limit: Int = Int.MAX_VALUE): FloatArray {
        val cap = monitorRingBuf.size.coerceAtLeast(1)
        val take = min(monitorCount, limit).coerceAtLeast(0)
        if (take == 0) return FloatArray(0)
        val out = FloatArray(take)
        val start = ((monitorWritePos - take) % cap + cap) % cap
        val firstPart = min(take, cap - start)
        System.arraycopy(monitorRingBuf, start, out, 0, firstPart)
        val secondPart = take - firstPart
        if (secondPart > 0) {
            System.arraycopy(monitorRingBuf, 0, out, firstPart, secondPart)
        }
        return out
    }

    private fun mixToMono(floats: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return floats
        val frames = floats.size / channels
        return FloatArray(frames) { f ->
            var s = 0f
            for (c in 0 until channels) s += floats[f * channels + c]
            s / channels
        }
    }
}
