package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import de.sciss.jump3r.mp3.MP3Data
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val SAMPLER_MODULE_COUNT = 12

data class Mp3DecodeResult(
    val buffer: AudioBuffer,
    val bitrateKbps: Int,
    val frameCount: Int,
    val modeLabel: String
)

/**
 * Pure-Java/Kotlin MP3 Decoder built on `de.sciss:jump3r` (`MPGLib` / `Interface` / `MP3Data`).
 * Runs identically on Android devices and Linux JVM unit tests with zero native JNI or DCL.
 */
object Mp3Jump3rDecoder {

    private const val CHUNK_BYTES = 4096
    private const val MAX_PCM_SAMPLES_PER_CH = 1152 * 2
    private const val MAX_DECODE_FRAMES = 44100 * 240 // 4 min safety cap

    fun isLikelyMp3(bytes: ByteArray, fileName: String = ""): Boolean {
        if (fileName.endsWith(".mp3", ignoreCase = true)) return true
        if (bytes.size >= 3 &&
            bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()
        ) {
            return true
        }
        val offset = skipId3v2Offset(bytes)
        if (offset + 1 < bytes.size) {
            val b0 = bytes[offset].toInt() and 0xFF
            val b1 = bytes[offset + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return true
        }
        return false
    }

    fun skipId3v2Offset(bytes: ByteArray): Int {
        var pos = 0
        if (bytes.size >= 10 &&
            bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()
        ) {
            val s0 = bytes[6].toInt() and 0x7F
            val s1 = bytes[7].toInt() and 0x7F
            val s2 = bytes[8].toInt() and 0x7F
            val s3 = bytes[9].toInt() and 0x7F
            val tagSize = (s0 shl 21) or (s1 shl 14) or (s2 shl 7) or s3
            pos = (10 + tagSize).coerceIn(0, bytes.size)
        }
        val scanEnd = min(bytes.size - 1, pos + 4096)
        var i = pos
        while (i < scanEnd) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0 && (b1 and 0x06) != 0x00) {
                return i
            }
            i++
        }
        return pos
    }

    fun decodeMp3File(file: File, trackName: String = file.nameWithoutExtension): Result<Mp3DecodeResult> =
        runCatching {
            require(file.exists() && file.isFile) { "MP3 file not found: ${file.name}" }
            decodeMp3Bytes(file.readBytes(), trackName).getOrThrow()
        }

    fun decodeMp3Stream(stream: InputStream, trackName: String = "MP3_STREAM"): Result<Mp3DecodeResult> =
        runCatching {
            val bytes = stream.readBytes()
            decodeMp3Bytes(bytes, trackName).getOrThrow()
        }

    fun decodeMp3Bytes(bytes: ByteArray, trackName: String = "MP3_TRACK"): Result<Mp3DecodeResult> = runCatching {
        require(bytes.size >= 16) { "MP3 payload is empty or too short (${bytes.size} bytes)" }

        val startOffset = skipId3v2Offset(bytes)
        require(startOffset < bytes.size) { "MP3 payload contains only metadata without audio frames" }

        val common = Common()
        val vbrTag = VBRTag()
        val interf = Interface().apply { setModules(vbrTag, common) }
        val mpg = MPGLib().apply { setModules(interf, common) }

        val mp = mpg.hip_decode_init()
            ?: throw IllegalStateException("jump3r MPGLib.hip_decode_init() returned null")

        val pcmL = ShortArray(MAX_PCM_SAMPLES_PER_CH)
        val pcmR = ShortArray(MAX_PCM_SAMPLES_PER_CH)
        val emptyInput = ByteArray(0)
        val mp3Data = MP3Data()

        var detectedSampleRate = 44100
        var detectedChannels = 2
        var detectedBitrate = 192
        var detectedMode = 0
        var headerSeen = false
        var framesDecoded = 0
        var errorCount = 0

        val outSamples = FloatArrayList(min(bytes.size * 6, 44100 * 60))

        try {
            var offset = startOffset
            while (offset < bytes.size && framesDecoded < MAX_DECODE_FRAMES) {
                val chunkLen = min(CHUNK_BYTES, bytes.size - offset)
                val chunk = bytes.copyOfRange(offset, offset + chunkLen)
                offset += chunkLen

                var decoded = mpg.hip_decode1_headers(mp, chunk, chunkLen, pcmL, pcmR, mp3Data)
                while (decoded > 0 && framesDecoded < MAX_DECODE_FRAMES) {
                    if (mp3Data.header_parsed) {
                        headerSeen = true
                        if (mp3Data.samplerate in 8000..192000) detectedSampleRate = mp3Data.samplerate
                        if (mp3Data.stereo in 1..2) detectedChannels = mp3Data.stereo
                        if (mp3Data.bitrate > 0) detectedBitrate = mp3Data.bitrate
                        detectedMode = mp3Data.mode
                    }
                    appendDecodedPcm(outSamples, pcmL, pcmR, decoded, detectedChannels)
                    framesDecoded += decoded
                    decoded = mpg.hip_decode1_headers(mp, emptyInput, 0, pcmL, pcmR, mp3Data)
                }
                if (decoded < 0) {
                    errorCount++
                    if (!headerSeen && errorCount > 6) {
                        throw IllegalArgumentException("Invalid or unsupported MP3 bitstream")
                    }
                }
            }

            // Drain any remaining buffered samples
            var tailGuard = 0
            while (tailGuard < 64 && framesDecoded < MAX_DECODE_FRAMES) {
                val decoded = mpg.hip_decode1_headers(mp, emptyInput, 0, pcmL, pcmR, mp3Data)
                if (decoded <= 0) break
                if (mp3Data.header_parsed) {
                    if (mp3Data.samplerate in 8000..192000) detectedSampleRate = mp3Data.samplerate
                    if (mp3Data.stereo in 1..2) detectedChannels = mp3Data.stereo
                }
                appendDecodedPcm(outSamples, pcmL, pcmR, decoded, detectedChannels)
                framesDecoded += decoded
                tailGuard++
            }
        } finally {
            runCatching { mpg.hip_decode_exit(mp) }
        }

        require(framesDecoded > 0 && outSamples.size > 0) {
            "No MP3 audio frames decoded by jump3r"
        }

        val ch = detectedChannels.coerceIn(1, 2)
        val buffer = AudioBuffer(
            samples = outSamples.toFloatArray(),
            sampleRate = detectedSampleRate.coerceIn(8000, 192000),
            channels = ch,
            name = trackName.ifBlank { "MP3_TRACK" },
            sampleFormat = AudioSampleFormat.PCM_16
        )
        val modeLabel = when (detectedMode) {
            0 -> "STEREO"
            1 -> "JOINT-ST"
            2 -> "DUAL-CH"
            3 -> "MONO"
            else -> "${ch}CH"
        }
        Mp3DecodeResult(
            buffer = buffer,
            bitrateKbps = detectedBitrate,
            frameCount = framesDecoded,
            modeLabel = modeLabel
        )
    }

    private fun appendDecodedPcm(
        out: FloatArrayList,
        pcmL: ShortArray,
        pcmR: ShortArray,
        sampleCount: Int,
        channels: Int
    ) {
        val n = min(sampleCount, min(pcmL.size, pcmR.size))
        if (channels == 1) {
            for (i in 0 until n) {
                out.add((pcmL[i] / 32768f).coerceIn(-1f, 1f))
            }
        } else {
            for (i in 0 until n) {
                out.add((pcmL[i] / 32768f).coerceIn(-1f, 1f))
                out.add((pcmR[i] / 32768f).coerceIn(-1f, 1f))
            }
        }
    }

    private class FloatArrayList(initialCapacity: Int) {
        private var data = FloatArray(max(256, initialCapacity))
        var size = 0
            private set

        fun add(v: Float) {
            if (size >= data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[size++] = v
        }

        fun toFloatArray(): FloatArray = data.copyOf(size)
    }
}

data class SamplerModuleSlot(
    val index: Int,
    val label: String = "MOD ${index + 1}",
    val sampleName: String = "EMPTY",
    val buffer: AudioBuffer? = null,
    val isLocked: Boolean = false,
    val isLooping: Boolean = false,
    val isPlaying: Boolean = false,
    val gain: Float = 0.9f,
    val pan: Float = 0f,
    val pitchSemitones: Float = 0f,
    val sourceFormatTag: String = "--"
) {
    val hasSample: Boolean get() = buffer != null && buffer.frameCount > 0
}

/**
 * 12-Module Hardware Sampler & Pure-Java MP3 Decoding Engine powered by `jump3r`.
 * Each module (`0..11`) holds an isolated sample buffer that can be locked against accidental
 * overwrites, pitched, panned, looped, triggered independently, or mixed down cleanly.
 */
class AudioEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Int, Job>()

    private val _slots = MutableStateFlow(
        List(SAMPLER_MODULE_COUNT) { idx ->
            SamplerModuleSlot(index = idx, label = "M${idx + 1}")
        }
    )
    val slots: StateFlow<List<SamplerModuleSlot>> = _slots.asStateFlow()

    @Volatile
    private var masterGain: Float = 0.85f

    @Volatile
    private var masterMuted: Boolean = false

    fun setMasterVolume(volume: Float, muted: Boolean) {
        masterGain = volume.coerceIn(0f, 1f)
        masterMuted = muted
    }

    fun decodeMp3Bytes(bytes: ByteArray, trackName: String = "MP3_SAMPLE"): Result<AudioBuffer> =
        Mp3Jump3rDecoder.decodeMp3Bytes(bytes, trackName).map { it.buffer }

    fun decodeMp3File(file: File, trackName: String = file.nameWithoutExtension): Result<AudioBuffer> =
        Mp3Jump3rDecoder.decodeMp3File(file, trackName).map { it.buffer }

    fun loadSampleToModule(
        moduleIndex: Int,
        buffer: AudioBuffer,
        lockSlot: Boolean = true,
        sourceTag: String = "WAV/PCM"
    ): Boolean {
        if (moduleIndex !in 0 until SAMPLER_MODULE_COUNT || buffer.frameCount <= 0) return false
        val current = _slots.value[moduleIndex]
        if (current.isLocked && current.hasSample) return false

        stopModule(moduleIndex)
        val isolated = buffer.copy(samples = buffer.samples.copyOf())
        updateSlot(moduleIndex) {
            it.copy(
                sampleName = isolated.name.take(24).ifBlank { "MOD ${moduleIndex + 1}" },
                buffer = isolated,
                isLocked = lockSlot,
                isPlaying = false,
                sourceFormatTag = sourceTag
            )
        }
        return true
    }

    fun forceReplaceModule(
        moduleIndex: Int,
        buffer: AudioBuffer,
        lockSlot: Boolean = true,
        sourceTag: String = "MP3/PCM"
    ): Boolean {
        if (moduleIndex !in 0 until SAMPLER_MODULE_COUNT || buffer.frameCount <= 0) return false
        stopModule(moduleIndex)
        val isolated = buffer.copy(samples = buffer.samples.copyOf())
        updateSlot(moduleIndex) {
            it.copy(
                sampleName = isolated.name.take(24).ifBlank { "MOD ${moduleIndex + 1}" },
                buffer = isolated,
                isLocked = lockSlot,
                isPlaying = false,
                sourceFormatTag = sourceTag
            )
        }
        return true
    }

    fun loadMp3BytesToModule(
        moduleIndex: Int,
        mp3Bytes: ByteArray,
        trackName: String = "MP3_MOD_${moduleIndex + 1}",
        lockSlot: Boolean = true
    ): Result<AudioBuffer> {
        val decoded = Mp3Jump3rDecoder.decodeMp3Bytes(mp3Bytes, trackName)
        return decoded.mapCatching { res ->
            val ok = forceReplaceModule(
                moduleIndex = moduleIndex,
                buffer = res.buffer,
                lockSlot = lockSlot,
                sourceTag = "MP3 ${res.bitrateKbps}k"
            )
            require(ok) { "Unable to bind decoded MP3 to sampler module #${moduleIndex + 1}" }
            res.buffer
        }
    }

    fun toggleModuleLock(moduleIndex: Int) {
        if (moduleIndex !in 0 until SAMPLER_MODULE_COUNT) return
        updateSlot(moduleIndex) { it.copy(isLocked = !it.isLocked) }
    }

    fun toggleModuleLoop(moduleIndex: Int) {
        if (moduleIndex !in 0 until SAMPLER_MODULE_COUNT) return
        updateSlot(moduleIndex) { it.copy(isLooping = !it.isLooping) }
    }

    fun setModuleParams(
        moduleIndex: Int,
        gain: Float? = null,
        pan: Float? = null,
        pitchSemitones: Float? = null
    ) {
        if (moduleIndex !in 0 until SAMPLER_MODULE_COUNT) return
        updateSlot(moduleIndex) { slot ->
            slot.copy(
                gain = (gain ?: slot.gain).coerceIn(0f, 1.5f),
                pan = (pan ?: slot.pan).coerceIn(-1f, 1f),
                pitchSemitones = (pitchSemitones ?: slot.pitchSemitones).coerceIn(-12f, 12f)
            )
        }
    }

    fun clearModule(moduleIndex: Int, force: Boolean = false): Boolean {
        if (moduleIndex !in 0 until SAMPLER_MODULE_COUNT) return false
        val current = _slots.value[moduleIndex]
        if (current.isLocked && !force) return false
        stopModule(moduleIndex)
        updateSlot(moduleIndex) {
            SamplerModuleSlot(index = moduleIndex, label = "M${moduleIndex + 1}")
        }
        return true
    }

    fun triggerModule(moduleIndex: Int) {
        if (moduleIndex !in 0 until SAMPLER_MODULE_COUNT) return
        val slot = _slots.value[moduleIndex]
        val buf = slot.buffer ?: return
        if (buf.frameCount <= 0) return

        stopModule(moduleIndex)
        updateSlot(moduleIndex) { it.copy(isPlaying = true) }

        val job = scope.launch {
            var track: AudioTrack? = null
            try {
                val sr = buf.sampleRate.coerceIn(8000, 192000)
                val outChannels = 2
                track = openStereoAudioTrackOrNull(sr)
                val rendered = renderSlotStereoPcm(slot, buf)
                val chunkFloats = 1024 * outChannels
                val shortBuf = ShortArray(chunkFloats)

                do {
                    var offset = 0
                    while (offset < rendered.size && isActive) {
                        val currentSlot = _slots.value[moduleIndex]
                        if (!currentSlot.isPlaying) break
                        val len = min(chunkFloats, rendered.size - offset)
                        val vol = if (masterMuted) 0f else (masterGain * currentSlot.gain).coerceIn(0f, 1.5f)
                        for (i in 0 until len) {
                            val s = (rendered[offset + i] * vol).coerceIn(-1f, 1f)
                            shortBuf[i] = (s * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        }
                        if (track != null) {
                            track.write(shortBuf, 0, len)
                        } else {
                            val framesWritten = len / outChannels
                            val sleepMs = ((framesWritten * 1000L) / sr).coerceIn(4L, 35L)
                            Thread.sleep(sleepMs)
                        }
                        offset += len
                    }
                } while (isActive && _slots.value[moduleIndex].isLooping && _slots.value[moduleIndex].isPlaying)
            } catch (_: Exception) {
            } finally {
                runCatching {
                    track?.pause()
                    track?.flush()
                    track?.release()
                }
                updateSlot(moduleIndex) { it.copy(isPlaying = false) }
            }
        }
        activeJobs[moduleIndex] = job
    }

    fun stopModule(moduleIndex: Int) {
        if (moduleIndex !in 0 until SAMPLER_MODULE_COUNT) return
        activeJobs.remove(moduleIndex)?.cancel()
        updateSlot(moduleIndex) { it.copy(isPlaying = false) }
    }

    fun stopAllModules() {
        for (i in 0 until SAMPLER_MODULE_COUNT) {
            activeJobs.remove(i)?.cancel()
        }
        _slots.value = _slots.value.map { it.copy(isPlaying = false) }
    }

    /**
     * Renders all active (or loaded) 12-module sampler slots into a single stereo AudioBuffer snapshot.
     */
    fun mixActiveModulesSnapshot(
        targetSampleRate: Int = 44100,
        maxDurationSec: Float = 8f,
        onlyPlaying: Boolean = false
    ): AudioBuffer? {
        val candidates = _slots.value.filter { slot ->
            slot.hasSample && (!onlyPlaying || slot.isPlaying)
        }
        if (candidates.isEmpty()) return null

        val maxFrames = (targetSampleRate * maxDurationSec.coerceIn(0.5f, 30f)).toInt()
        val renderedPairs = candidates.mapNotNull { slot ->
            val raw = slot.buffer ?: return@mapNotNull null
            val resampled = if (raw.sampleRate != targetSampleRate) raw.resample(targetSampleRate) else raw
            renderSlotStereoPcm(slot, resampled)
        }
        if (renderedPairs.isEmpty()) return null

        val outFrames = min(maxFrames, renderedPairs.maxOf { it.size / 2 }).coerceAtLeast(1)
        val mixed = FloatArray(outFrames * 2)
        val norm = 1f / max(1f, kotlin.math.sqrt(renderedPairs.size.toFloat()))
        for (buf in renderedPairs) {
            val frames = min(outFrames, buf.size / 2)
            for (f in 0 until frames) {
                mixed[f * 2] = (mixed[f * 2] + buf[f * 2] * norm).coerceIn(-1f, 1f)
                mixed[f * 2 + 1] = (mixed[f * 2 + 1] + buf[f * 2 + 1] * norm).coerceIn(-1f, 1f)
            }
        }
        return AudioBuffer(
            samples = mixed,
            sampleRate = targetSampleRate,
            channels = 2,
            name = "SAMPLER_12MOD_MIX",
            sampleFormat = AudioSampleFormat.PCM_16
        )
    }

    fun release() {
        stopAllModules()
    }

    private fun renderSlotStereoPcm(slot: SamplerModuleSlot, src: AudioBuffer): FloatArray {
        val pitchRatio = 2.0f.pow(slot.pitchSemitones.coerceIn(-12f, 12f) / 12.0f)
        val inFrames = src.frameCount
        if (inFrames <= 0) return FloatArray(0)
        val outFrames = max(1, (inFrames / pitchRatio).toInt())
        val panAngle = ((slot.pan.coerceIn(-1f, 1f) + 1f) * 0.25f * PI).toFloat()
        val gainL = cos(panAngle) * slot.gain.coerceIn(0f, 1.5f)
        val gainR = sin(panAngle) * slot.gain.coerceIn(0f, 1.5f)
        val out = FloatArray(outFrames * 2)

        for (i in 0 until outFrames) {
            val pos = i * pitchRatio.toDouble()
            val idx0 = pos.toInt().coerceIn(0, inFrames - 1)
            val idx1 = (idx0 + 1).coerceIn(0, inFrames - 1)
            val frac = (pos - idx0).toFloat()
            val l: Float
            val r: Float
            if (src.channels == 1) {
                val a = src.samples[idx0]
                val b = src.samples[idx1]
                val v = a + (b - a) * frac
                l = v
                r = v
            } else {
                val l0 = src.samples[idx0 * 2]
                val l1 = src.samples[idx1 * 2]
                val r0 = src.samples[idx0 * 2 + 1]
                val r1 = src.samples[idx1 * 2 + 1]
                l = l0 + (l1 - l0) * frac
                r = r0 + (r1 - r0) * frac
            }
            out[i * 2] = (l * gainL).coerceIn(-1f, 1f)
            out[i * 2 + 1] = (r * gainR).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun openStereoAudioTrackOrNull(sampleRate: Int): AudioTrack? = runCatching {
        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)
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
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBufBytes, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }.getOrNull()

    private fun updateSlot(index: Int, transform: (SamplerModuleSlot) -> SamplerModuleSlot) {
        val current = _slots.value.toMutableList()
        if (index in current.indices) {
            current[index] = transform(current[index])
            _slots.value = current
        }
    }
}
