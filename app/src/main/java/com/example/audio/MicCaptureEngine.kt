package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
import kotlinx.coroutines.withContext

data class MicCaptureTelemetry(
    val isRecording: Boolean = false,
    val isLiveMonitoring: Boolean = false,
    val monitorPassthrough: Boolean = false,
    val monitorGain: Float = 0.7f,
    val monitorWindowSec: Int = 12,
    val liveSyncToDeck: Boolean = true,
    val elapsedMs: Long = 0L,
    val peakLevel: Float = 0f,
    val rmsLevel: Float = 0f,
    val liveWaveform: FloatArray = FloatArray(64),
    val liveSpectrum: FloatArray = FloatArray(32),
    val capturedSampleCount: Int = 0,
    val monitorBufferedSamples: Int = 0,
    val errorMessage: String? = null
) {
    val isActiveCapture: Boolean get() = isRecording || isLiveMonitoring

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MicCaptureTelemetry) return false
        return isRecording == other.isRecording &&
            isLiveMonitoring == other.isLiveMonitoring &&
            monitorPassthrough == other.monitorPassthrough &&
            monitorGain == other.monitorGain &&
            monitorWindowSec == other.monitorWindowSec &&
            liveSyncToDeck == other.liveSyncToDeck &&
            elapsedMs == other.elapsedMs &&
            peakLevel == other.peakLevel &&
            rmsLevel == other.rmsLevel &&
            capturedSampleCount == other.capturedSampleCount &&
            monitorBufferedSamples == other.monitorBufferedSamples &&
            errorMessage == other.errorMessage &&
            liveWaveform.contentEquals(other.liveWaveform) &&
            liveSpectrum.contentEquals(other.liveSpectrum)
    }

    override fun hashCode(): Int {
        var result = isRecording.hashCode()
        result = 31 * result + isLiveMonitoring.hashCode()
        result = 31 * result + monitorPassthrough.hashCode()
        result = 31 * result + monitorGain.hashCode()
        result = 31 * result + monitorWindowSec
        result = 31 * result + liveSyncToDeck.hashCode()
        result = 31 * result + elapsedMs.hashCode()
        result = 31 * result + peakLevel.hashCode()
        result = 31 * result + rmsLevel.hashCode()
        result = 31 * result + liveWaveform.contentHashCode()
        result = 31 * result + liveSpectrum.contentHashCode()
        result = 31 * result + capturedSampleCount
        result = 31 * result + monitorBufferedSamples
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        return result
    }
}

/**
 * Tactile Hold-to-Record + Continuous Live Monitor AudioRecord/AudioTrack engine.
 * - Hold mode (`startCapture`): captures mic PCM while TAC pad is held and flushes to WAV on release.
 * - Live Monitor mode (`startLiveMonitor`): continuously captures into a rolling window, optionally monitors
 *   live passthrough via AudioTrack, and streams rolling snapshots directly into the Deck.
 */
class MicCaptureEngine(
    private val onMonoFrame: (FloatArray, Int) -> Unit = { _, _ -> },
    private val onMonitorDeckSnapshot: (AudioBuffer) -> Unit = { _ -> }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isCapturing = AtomicBoolean(false)
    private val isMonitoring = AtomicBoolean(false)
    private var captureJob: Job? = null
    private var monitorJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private val recordedChunks = ArrayList<FloatArray>()
    private val lock = Any()
    private val monitorLock = Any()

    @Volatile
    private var activeSampleRate = 44100

    @Volatile
    private var activeChannels = 1

    @Volatile
    private var monitorSampleRate = 44100

    @Volatile
    private var monitorChannels = 1

    @Volatile
    private var monitorPassthroughEnabled = false

    @Volatile
    private var monitorGain = 0.7f

    @Volatile
    private var masterVolume = 0.85f

    @Volatile
    private var masterMuted = false

    @Volatile
    private var monitorWindowSec = 12

    @Volatile
    private var liveSyncToDeck = true

    private var monitorRingBuf = FloatArray(44100 * 12)
    private var monitorWritePos = 0
    private var monitorCount = 0
    private val monitorRollingWave = FloatArray(64)
    private var monitorWaveHead = 0
    private var lastMonitorSyncMs = 0L

    private val _telemetry = MutableStateFlow(MicCaptureTelemetry())
    val telemetry: StateFlow<MicCaptureTelemetry> = _telemetry.asStateFlow()

    fun setMasterVolume(volume: Float, muted: Boolean) {
        masterVolume = volume.coerceIn(0f, 1f)
        masterMuted = muted
    }

    fun setMonitorPassthrough(enabled: Boolean) {
        monitorPassthroughEnabled = enabled
        _telemetry.value = _telemetry.value.copy(monitorPassthrough = enabled)
    }

    fun setMonitorGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        monitorGain = clamped
        _telemetry.value = _telemetry.value.copy(monitorGain = clamped)
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
        _telemetry.value = _telemetry.value.copy(
            monitorWindowSec = clamped,
            monitorBufferedSamples = synchronized(monitorLock) { monitorCount }
        )
    }

    fun setLiveSyncToDeck(enabled: Boolean) {
        liveSyncToDeck = enabled
        _telemetry.value = _telemetry.value.copy(liveSyncToDeck = enabled)
    }

    @SuppressLint("MissingPermission")
    fun startCapture(
        sampleRate: Int,
        channels: Int,
        bufferSizeFrames: Int
    ): Result<Unit> {
        if (isMonitoring.get()) {
            stopLiveMonitorSyncInternal()
        }
        if (isCapturing.get()) return Result.success(Unit)

        val channelConfig = if (channels == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes == AudioRecord.ERROR || minBufBytes == AudioRecord.ERROR_BAD_VALUE) {
            val msg = "Unsupported mic format ($sampleRate Hz, $channels ch)"
            _telemetry.value = _telemetry.value.copy(errorMessage = msg)
            return Result.failure(IllegalArgumentException(msg))
        }

        val requestedBytes = bufferSizeFrames * channels * 2
        val actualBufferBytes = max(minBufBytes * 2, requestedBytes)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                actualBufferBytes
            )
        } catch (se: SecurityException) {
            val msg = "Microphone permission denied"
            _telemetry.value = _telemetry.value.copy(errorMessage = msg)
            return Result.failure(se)
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to initialize AudioRecord"
            _telemetry.value = _telemetry.value.copy(errorMessage = msg)
            return Result.failure(e)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            val msg = "Microphone hardware could not initialize"
            _telemetry.value = _telemetry.value.copy(errorMessage = msg)
            return Result.failure(IllegalStateException(msg))
        }

        synchronized(lock) {
            recordedChunks.clear()
        }
        activeSampleRate = sampleRate
        activeChannels = channels
        audioRecord = recorder
        isCapturing.set(true)

        val startTimeMs = System.currentTimeMillis()
        _telemetry.value = _telemetry.value.copy(
            isRecording = true,
            isLiveMonitoring = false,
            elapsedMs = 0L,
            capturedSampleCount = 0,
            errorMessage = null
        )

        captureJob = scope.launch {
            val readShorts = ShortArray(max(256, bufferSizeFrames) * channels)
            val rollingWaveform = FloatArray(64)
            var waveHead = 0

            try {
                recorder.startRecording()
                while (isCapturing.get()) {
                    val readCount = recorder.read(readShorts, 0, readShorts.size)
                    if (readCount > 0) {
                        val floats = WavCodec.pcm16ShortsToFloats(readShorts, readCount)
                        synchronized(lock) {
                            recordedChunks.add(floats)
                        }
                        val mono = mixToMono(floats, activeChannels)
                        onMonoFrame(mono, activeSampleRate)
                        var peak = 0f
                        var sumSq = 0.0
                        for (s in floats) {
                            val a = abs(s)
                            if (a > peak) peak = a
                            sumSq += s * s
                        }
                        val rms = sqrt(sumSq / floats.size).toFloat()
                        rollingWaveform[waveHead] = peak
                        waveHead = (waveHead + 1) % rollingWaveform.size

                        val orderedWave = FloatArray(rollingWaveform.size) { idx ->
                            rollingWaveform[(waveHead + idx) % rollingWaveform.size]
                        }
                        val spectrum = AudioAnalyzer.computeSpectrumFromSamples(
                            samples = floats,
                            sampleRate = activeSampleRate,
                            fftSize = 512,
                            bandCount = 32
                        )
                        val totalSamples = synchronized(lock) { recordedChunks.sumOf { it.size } }
                        _telemetry.value = _telemetry.value.copy(
                            isRecording = true,
                            elapsedMs = System.currentTimeMillis() - startTimeMs,
                            peakLevel = peak,
                            rmsLevel = rms,
                            liveWaveform = orderedWave,
                            liveSpectrum = spectrum,
                            capturedSampleCount = totalSamples
                        )
                    }
                }
            } catch (e: Exception) {
                _telemetry.value = _telemetry.value.copy(
                    isRecording = false,
                    errorMessage = e.message ?: "Mic capture error"
                )
            } finally {
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (_: Exception) {
                }
                recorder.release()
                if (audioRecord === recorder) {
                    audioRecord = null
                }
            }
        }

        return Result.success(Unit)
    }

    suspend fun stopCaptureAndSaveWav(outputFile: File, takeName: String): Result<AudioBuffer> {
        isCapturing.set(false)
        captureJob?.join()
        captureJob = null

        return withContext(Dispatchers.IO) {
            val combined = synchronized(lock) {
                val total = recordedChunks.sumOf { it.size }
                val merged = FloatArray(total)
                var offset = 0
                for (chunk in recordedChunks) {
                    System.arraycopy(chunk, 0, merged, offset, chunk.size)
                    offset += chunk.size
                }
                recordedChunks.clear()
                merged
            }

            _telemetry.value = _telemetry.value.copy(
                isRecording = false,
                peakLevel = 0f,
                rmsLevel = 0f,
                elapsedMs = 0L,
                capturedSampleCount = 0
            )

            if (combined.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Hold the TAC pad longer to capture audio")
                )
            }

            val buffer = AudioBuffer(
                samples = combined,
                sampleRate = activeSampleRate,
                channels = activeChannels,
                name = takeName
            )
            WavCodec.writeWav(buffer, outputFile)
            Result.success(buffer)
        }
    }

    @SuppressLint("MissingPermission")
    fun startLiveMonitor(
        sampleRate: Int,
        channels: Int,
        bufferSizeFrames: Int,
        windowSeconds: Int = monitorWindowSec,
        passthroughEnabled: Boolean = monitorPassthroughEnabled,
        gain: Float = monitorGain,
        syncToDeck: Boolean = liveSyncToDeck
    ): Result<Unit> {
        if (isMonitoring.get()) return Result.success(Unit)
        if (isCapturing.get()) {
            isCapturing.set(false)
            captureJob?.cancel()
            captureJob = null
        }

        monitorSampleRate = sampleRate
        monitorChannels = channels.coerceIn(1, 2)
        monitorWindowSec = windowSeconds.coerceIn(2, 60)
        monitorPassthroughEnabled = passthroughEnabled
        monitorGain = gain.coerceIn(0f, 1f)
        liveSyncToDeck = syncToDeck
        lastMonitorSyncMs = 0L

        synchronized(monitorLock) {
            val cap = (monitorWindowSec * monitorSampleRate * monitorChannels).coerceAtLeast(2048)
            monitorRingBuf = FloatArray(cap)
            monitorWritePos = 0
            monitorCount = 0
            monitorRollingWave.fill(0f)
            monitorWaveHead = 0
        }

        val channelConfig = if (monitorChannels == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes == AudioRecord.ERROR || minBufBytes == AudioRecord.ERROR_BAD_VALUE) {
            val msg = "Unsupported monitor format ($sampleRate Hz, $monitorChannels ch)"
            _telemetry.value = _telemetry.value.copy(errorMessage = msg)
            return Result.failure(IllegalArgumentException(msg))
        }

        val requestedBytes = bufferSizeFrames * monitorChannels * 2
        val actualBufferBytes = max(minBufBytes * 2, requestedBytes)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                actualBufferBytes
            )
        } catch (se: SecurityException) {
            val msg = "Microphone permission denied"
            _telemetry.value = _telemetry.value.copy(errorMessage = msg)
            return Result.failure(se)
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to initialize live monitor AudioRecord"
            _telemetry.value = _telemetry.value.copy(errorMessage = msg)
            return Result.failure(e)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            val msg = "Microphone hardware could not initialize for Live Monitor"
            _telemetry.value = _telemetry.value.copy(errorMessage = msg)
            return Result.failure(IllegalStateException(msg))
        }

        audioRecord = recorder
        isMonitoring.set(true)
        val startTimeMs = System.currentTimeMillis()
        _telemetry.value = _telemetry.value.copy(
            isRecording = false,
            isLiveMonitoring = true,
            monitorPassthrough = monitorPassthroughEnabled,
            monitorGain = monitorGain,
            monitorWindowSec = monitorWindowSec,
            liveSyncToDeck = liveSyncToDeck,
            elapsedMs = 0L,
            monitorBufferedSamples = 0,
            errorMessage = null
        )

        monitorJob = scope.launch {
            val readShorts = ShortArray(max(256, bufferSizeFrames) * monitorChannels)
            var outTrack: AudioTrack? = null
            try {
                recorder.startRecording()
                while (isMonitoring.get()) {
                    val readCount = recorder.read(readShorts, 0, readShorts.size)
                    if (readCount > 0) {
                        val floats = WavCodec.pcm16ShortsToFloats(readShorts, readCount)
                        val now = System.currentTimeMillis()
                        ingestLiveMonitorChunkInternal(
                            floats = floats,
                            sampleRate = monitorSampleRate,
                            channels = monitorChannels,
                            elapsedMs = now - startTimeMs,
                            nowMs = now,
                            forceSnapshot = false
                        )

                        if (monitorPassthroughEnabled) {
                            if (outTrack == null) {
                                outTrack = openMonitorOutputTrackOrNull(
                                    monitorSampleRate,
                                    monitorChannels,
                                    bufferSizeFrames
                                )
                            }
                            outTrack?.let { tr ->
                                val gainFactor = if (masterMuted) 0f else (monitorGain * masterVolume).coerceIn(0f, 1f)
                                val outShorts = ShortArray(readCount) { i ->
                                    ((floats[i] * gainFactor).coerceIn(-1f, 1f) * 32767f)
                                        .toInt()
                                        .coerceIn(-32768, 32767)
                                        .toShort()
                                }
                                tr.write(outShorts, 0, outShorts.size)
                            }
                        } else if (outTrack != null) {
                            runCatching {
                                outTrack?.pause()
                                outTrack?.flush()
                                outTrack?.release()
                            }
                            outTrack = null
                        }
                    }
                }
            } catch (e: Exception) {
                _telemetry.value = _telemetry.value.copy(
                    isLiveMonitoring = false,
                    errorMessage = e.message ?: "Live Monitor error"
                )
            } finally {
                runCatching {
                    outTrack?.pause()
                    outTrack?.flush()
                    outTrack?.release()
                }
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (_: Exception) {
                }
                recorder.release()
                if (audioRecord === recorder) {
                    audioRecord = null
                }
            }
        }

        return Result.success(Unit)
    }

    /**
     * Pure-Kotlin deterministic entry point for unit testing live monitor rolling buffering,
     * telemetry metering, and auto-load Deck snapshot callbacks without Android AudioRecord hardware.
     */
    fun ingestLiveMonitorSamplesForTest(
        samples: FloatArray,
        sampleRate: Int = monitorSampleRate,
        channels: Int = monitorChannels,
        windowSeconds: Int = monitorWindowSec,
        syncToDeck: Boolean = liveSyncToDeck,
        forceSnapshot: Boolean = true
    ): AudioBuffer? {
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
        if (!_telemetry.value.isLiveMonitoring) {
            _telemetry.value = _telemetry.value.copy(
                isLiveMonitoring = true,
                monitorWindowSec = monitorWindowSec,
                liveSyncToDeck = liveSyncToDeck
            )
        }
        return ingestLiveMonitorChunkInternal(
            floats = samples,
            sampleRate = monitorSampleRate,
            channels = monitorChannels,
            elapsedMs = _telemetry.value.elapsedMs + ((samples.size.toLong() * 1000L) / (monitorSampleRate * monitorChannels).coerceAtLeast(1)),
            nowMs = System.currentTimeMillis(),
            forceSnapshot = forceSnapshot
        )
    }

    private fun ingestLiveMonitorChunkInternal(
        floats: FloatArray,
        sampleRate: Int,
        channels: Int,
        elapsedMs: Long,
        nowMs: Long,
        forceSnapshot: Boolean
    ): AudioBuffer? {
        if (floats.isEmpty()) return null
        val totalBuffered: Int
        synchronized(monitorLock) {
            appendMonitorSamplesLocked(floats)
            totalBuffered = monitorCount
        }
        val mono = mixToMono(floats, channels)
        onMonoFrame(mono, sampleRate)

        var peak = 0f
        var sumSq = 0.0
        for (s in floats) {
            val a = abs(s)
            if (a > peak) peak = a
            sumSq += s * s
        }
        val rms = sqrt(sumSq / floats.size).toFloat()
        monitorRollingWave[monitorWaveHead] = peak
        monitorWaveHead = (monitorWaveHead + 1) % monitorRollingWave.size
        val orderedWave = FloatArray(monitorRollingWave.size) { idx ->
            monitorRollingWave[(monitorWaveHead + idx) % monitorRollingWave.size]
        }
        val spectrum = AudioAnalyzer.computeSpectrumFromSamples(
            samples = floats,
            sampleRate = sampleRate,
            fftSize = 512,
            bandCount = 32
        )

        _telemetry.value = _telemetry.value.copy(
            isLiveMonitoring = true,
            monitorPassthrough = monitorPassthroughEnabled,
            monitorGain = monitorGain,
            monitorWindowSec = monitorWindowSec,
            liveSyncToDeck = liveSyncToDeck,
            elapsedMs = elapsedMs,
            peakLevel = peak,
            rmsLevel = rms,
            liveWaveform = orderedWave,
            liveSpectrum = spectrum,
            monitorBufferedSamples = totalBuffered,
            errorMessage = null
        )

        val shouldSnapshot = forceSnapshot || (liveSyncToDeck && totalBuffered >= 256 * channels && (nowMs - lastMonitorSyncMs) >= 220L)
        if (!shouldSnapshot) return null
        lastMonitorSyncMs = nowMs
        val snap = snapshotLiveMonitorBuffer("LIVE_MONITOR") ?: return null
        onMonitorDeckSnapshot(snap)
        return snap
    }

    fun snapshotLiveMonitorBuffer(name: String = "LIVE_MONITOR"): AudioBuffer? {
        val samples = synchronized(monitorLock) {
            if (monitorCount <= 0) FloatArray(0) else readMonitorSamplesLocked()
        }
        if (samples.isEmpty()) return null
        return AudioBuffer(
            samples = samples,
            sampleRate = monitorSampleRate,
            channels = monitorChannels,
            name = name
        )
    }

    suspend fun stopLiveMonitorAndCollect(
        saveWavFile: File? = null,
        takeName: String = "LIVE_MONITOR"
    ): Result<AudioBuffer> {
        isMonitoring.set(false)
        monitorJob?.join()
        monitorJob = null

        return withContext(Dispatchers.IO) {
            val buf = snapshotLiveMonitorBuffer(takeName)
            _telemetry.value = _telemetry.value.copy(
                isLiveMonitoring = false,
                peakLevel = 0f,
                rmsLevel = 0f,
                elapsedMs = 0L
            )
            if (buf == null || buf.frameCount < 128) {
                return@withContext Result.failure(
                    IllegalStateException("Live Monitor stopped (capture buffer empty)")
                )
            }
            if (saveWavFile != null) {
                WavCodec.writeWav(buf, saveWavFile)
            }
            Result.success(buf)
        }
    }

    private fun stopLiveMonitorSyncInternal() {
        isMonitoring.set(false)
        monitorJob?.cancel()
        monitorJob = null
        _telemetry.value = _telemetry.value.copy(isLiveMonitoring = false)
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

    private fun openMonitorOutputTrackOrNull(
        sampleRate: Int,
        channels: Int,
        bufferSizeFrames: Int
    ): AudioTrack? = runCatching {
        val outMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, outMask, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(1024)
        val bufBytes = max(minBytes, bufferSizeFrames * channels * 2)
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
                    .setChannelMask(outMask)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }.getOrNull()

    fun release() {
        isCapturing.set(false)
        isMonitoring.set(false)
        captureJob?.cancel()
        monitorJob?.cancel()
        audioRecord?.release()
        audioRecord = null
    }
}
