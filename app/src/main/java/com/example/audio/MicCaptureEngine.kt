package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
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
    val elapsedMs: Long = 0L,
    val peakLevel: Float = 0f,
    val rmsLevel: Float = 0f,
    val liveWaveform: FloatArray = FloatArray(64),
    val liveSpectrum: FloatArray = FloatArray(32),
    val capturedSampleCount: Int = 0,
    val errorMessage: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MicCaptureTelemetry) return false
        return isRecording == other.isRecording &&
            elapsedMs == other.elapsedMs &&
            peakLevel == other.peakLevel &&
            rmsLevel == other.rmsLevel &&
            capturedSampleCount == other.capturedSampleCount &&
            errorMessage == other.errorMessage &&
            liveWaveform.contentEquals(other.liveWaveform) &&
            liveSpectrum.contentEquals(other.liveSpectrum)
    }

    override fun hashCode(): Int {
        var result = isRecording.hashCode()
        result = 31 * result + elapsedMs.hashCode()
        result = 31 * result + peakLevel.hashCode()
        result = 31 * result + rmsLevel.hashCode()
        result = 31 * result + liveWaveform.contentHashCode()
        result = 31 * result + liveSpectrum.contentHashCode()
        result = 31 * result + capturedSampleCount
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        return result
    }
}

/**
 * Tactile Hold-to-Record AudioRecord engine.
 * Captures mic PCM audio while TAC button is held and flushes to a standard RIFF WAV file on release.
 */
class MicCaptureEngine(
    private val onMonoFrame: (FloatArray, Int) -> Unit = { _, _ -> }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isCapturing = AtomicBoolean(false)
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private val recordedChunks = ArrayList<FloatArray>()
    private val lock = Any()
    private var activeSampleRate = 44100
    private var activeChannels = 1

    private val _telemetry = MutableStateFlow(MicCaptureTelemetry())
    val telemetry: StateFlow<MicCaptureTelemetry> = _telemetry.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startCapture(
        sampleRate: Int,
        channels: Int,
        bufferSizeFrames: Int
    ): Result<Unit> {
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
            _telemetry.value = MicCaptureTelemetry(errorMessage = msg)
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
            _telemetry.value = MicCaptureTelemetry(errorMessage = msg)
            return Result.failure(se)
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to initialize AudioRecord"
            _telemetry.value = MicCaptureTelemetry(errorMessage = msg)
            return Result.failure(e)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            val msg = "Microphone hardware could not initialize"
            _telemetry.value = MicCaptureTelemetry(errorMessage = msg)
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
        _telemetry.value = MicCaptureTelemetry(isRecording = true)

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
                        val mono = if (activeChannels > 1) {
                            val frames = floats.size / activeChannels
                            FloatArray(frames) { f ->
                                var s = 0f
                                for (c in 0 until activeChannels) s += floats[f * activeChannels + c]
                                s / activeChannels
                            }
                        } else {
                            floats
                        }
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
                        _telemetry.value = MicCaptureTelemetry(
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

            _telemetry.value = MicCaptureTelemetry(isRecording = false)

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

    fun release() {
        isCapturing.set(false)
        captureJob?.cancel()
        audioRecord?.release()
        audioRecord = null
    }
}
