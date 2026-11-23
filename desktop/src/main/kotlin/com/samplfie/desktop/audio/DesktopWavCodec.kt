package com.samplfie.desktop.audio

import com.samplfie.desktop.model.DesktopAudioBuffer
import com.samplfie.desktop.model.DesktopSampleFormat
import de.sciss.jump3r.Main
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Multi-format RIFF/WAVE PCM (8/16/24/32) & IEEE Float (32/64) reader/writer + LAME MP3 exporter.
 */
object DesktopWavCodec {

    fun floatToPcm16Bytes(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val clamped = s.coerceIn(-1f, 1f)
            val v = (clamped * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            bb.putShort(v)
        }
        return out
    }

    fun pcm16BytesToFloats(bytes: ByteArray, length: Int = bytes.size): FloatArray {
        val count = length / 2
        val bb = ByteBuffer.wrap(bytes, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { (bb.short.toFloat() / 32768f).coerceIn(-1f, 1f) }
    }

    fun encodeSamplesToPayload(
        samples: FloatArray,
        targetFormat: DesktopSampleFormat,
        dither: Boolean = false
    ): ByteArray {
        val src = if (dither && !targetFormat.isFloat) {
            DesktopSampleFormat.quantizeSamples(samples, targetFormat, dither = true)
        } else {
            samples
        }
        return when (targetFormat) {
            DesktopSampleFormat.PCM_8 -> ByteArray(src.size) { i ->
                ((src[i].coerceIn(-1f, 1f) * 127f).roundToInt() + 128).coerceIn(0, 255).toByte()
            }
            DesktopSampleFormat.PCM_16 -> floatToPcm16Bytes(src)
            DesktopSampleFormat.PCM_24 -> {
                val out = ByteArray(src.size * 3)
                for (i in src.indices) {
                    val v = (src[i].coerceIn(-1f, 1f).toDouble() * 8388607.0).roundToInt().coerceIn(-8388608, 8388607)
                    val base = i * 3
                    out[base] = (v and 0xFF).toByte()
                    out[base + 1] = ((v ushr 8) and 0xFF).toByte()
                    out[base + 2] = ((v ushr 16) and 0xFF).toByte()
                }
                out
            }
            DesktopSampleFormat.PCM_32 -> {
                val out = ByteArray(src.size * 4)
                val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
                for (s in src) {
                    val v = (s.coerceIn(-1f, 1f).toDouble() * 2147483647.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                    bb.putInt(v)
                }
                out
            }
            DesktopSampleFormat.FLOAT_32 -> {
                val out = ByteArray(src.size * 4)
                val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
                for (s in src) bb.putFloat(s.coerceIn(-1f, 1f))
                out
            }
            DesktopSampleFormat.FLOAT_64 -> {
                val out = ByteArray(src.size * 8)
                val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
                for (s in src) bb.putDouble(s.coerceIn(-1f, 1f).toDouble())
                out
            }
        }
    }

    fun decodePayloadToFloats(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        format: DesktopSampleFormat
    ): FloatArray {
        val safeLen = length.coerceIn(0, bytes.size - offset)
        val bb = ByteBuffer.wrap(bytes, offset, safeLen).order(ByteOrder.LITTLE_ENDIAN)
        return when (format) {
            DesktopSampleFormat.PCM_8 -> FloatArray(safeLen) { i ->
                val unsigned = bytes[offset + i].toInt() and 0xFF
                ((unsigned - 128) / 128f).coerceIn(-1f, 1f)
            }
            DesktopSampleFormat.PCM_16 -> FloatArray(safeLen / 2) {
                (bb.short.toFloat() / 32768f).coerceIn(-1f, 1f)
            }
            DesktopSampleFormat.PCM_24 -> {
                val count = safeLen / 3
                FloatArray(count) { i ->
                    val b = offset + i * 3
                    var v = (bytes[b].toInt() and 0xFF) or
                        ((bytes[b + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[b + 2].toInt() and 0xFF) shl 16)
                    if ((v and 0x800000) != 0) v = v or -0x1000000
                    (v.toDouble() / 8388608.0).toFloat().coerceIn(-1f, 1f)
                }
            }
            DesktopSampleFormat.PCM_32 -> FloatArray(safeLen / 4) {
                (bb.int.toDouble() / 2147483648.0).toFloat().coerceIn(-1f, 1f)
            }
            DesktopSampleFormat.FLOAT_32 -> FloatArray(safeLen / 4) {
                val f = bb.float
                if (f.isFinite()) f.coerceIn(-1f, 1f) else 0f
            }
            DesktopSampleFormat.FLOAT_64 -> FloatArray(safeLen / 8) {
                val d = bb.double
                if (d.isFinite()) d.toFloat().coerceIn(-1f, 1f) else 0f
            }
        }
    }

    fun encodeToBytes(
        buffer: DesktopAudioBuffer,
        targetFormat: DesktopSampleFormat = buffer.sampleFormat,
        dither: Boolean = false
    ): ByteArray {
        val payload = encodeSamplesToPayload(buffer.samples, targetFormat, dither)
        val dataLen = payload.size
        val bps = targetFormat.bytesPerSample
        val byteRate = buffer.sampleRate * buffer.channels * bps
        val blockAlign = buffer.channels * bps
        val out = ByteArrayOutputStream(44 + dataLen)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(targetFormat.wavFormatCode.toShort())
        header.putShort(buffer.channels.toShort())
        header.putInt(buffer.sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(targetFormat.bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLen)
        out.write(header.array())
        out.write(payload)
        return out.toByteArray()
    }

    fun writeWav(
        buffer: DesktopAudioBuffer,
        file: File,
        targetFormat: DesktopSampleFormat = buffer.sampleFormat,
        dither: Boolean = false
    ) {
        file.parentFile?.mkdirs()
        file.writeBytes(encodeToBytes(buffer, targetFormat, dither))
    }

    fun readWav(file: File): DesktopAudioBuffer = readWav(file.readBytes(), file.nameWithoutExtension)

    fun readWav(bytes: ByteArray, name: String = "WAV"): DesktopAudioBuffer {
        require(bytes.size >= 44) { "WAV file too small (${bytes.size} bytes)" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { bb.get(it) }.toString(Charsets.US_ASCII)
        bb.int // chunk size
        val wave = ByteArray(4).also { bb.get(it) }.toString(Charsets.US_ASCII)
        require(riff == "RIFF" && wave == "WAVE") { "Unsupported audio container (expected RIFF/WAVE)" }

        var formatTag = 1
        var sampleRate = 44100
        var channels = 1
        var bitsPerSample = 16
        var validBits = 0
        var subFormatCode = 0
        var dataOffset = -1
        var dataSize = 0

        while (bb.remaining() >= 8) {
            val id = ByteArray(4).also { bb.get(it) }.toString(Charsets.US_ASCII)
            val size = bb.int
            if (size < 0 || size > bb.remaining()) break
            when (id) {
                "fmt " -> {
                    val startPos = bb.position()
                    formatTag = bb.short.toInt() and 0xFFFF
                    channels = (bb.short.toInt() and 0xFFFF).coerceIn(1, 2)
                    sampleRate = bb.int
                    bb.int // byteRate
                    bb.short // blockAlign
                    bitsPerSample = bb.short.toInt() and 0xFFFF
                    if (formatTag == 0xFFFE && size >= 40) {
                        bb.short // cbSize
                        validBits = bb.short.toInt() and 0xFFFF
                        bb.int // channelMask
                        subFormatCode = bb.short.toInt() and 0xFFFF
                    }
                    bb.position(startPos + size)
                }
                "data" -> {
                    dataOffset = bb.position()
                    dataSize = size
                    break
                }
                else -> bb.position(bb.position() + size + (size and 1))
            }
        }
        val effectiveTag = if (formatTag == 0xFFFE && subFormatCode != 0) subFormatCode else formatTag
        val effectiveBits = if (validBits > 0) validBits else bitsPerSample
        val sampleFormat = DesktopSampleFormat.fromWavHeader(effectiveTag, effectiveBits)
            ?: throw IllegalArgumentException("Unsupported WAV format (tag=$effectiveTag, bits=$effectiveBits)")
        require(dataOffset >= 0 && dataSize >= sampleFormat.bytesPerSample) { "Missing WAV audio data chunk" }

        val floats = decodePayloadToFloats(bytes, dataOffset, dataSize, sampleFormat)
        return DesktopAudioBuffer(floats, sampleRate, channels, name, sampleFormat)
    }
}

object DesktopMp3Exporter {
    fun encodeToFile(buffer: DesktopAudioBuffer, targetMp3: File, bitrateKbps: Int = 192): Result<File> {
        return try {
            targetMp3.parentFile?.mkdirs()
            val tempWav = File.createTempFile("samplfie2_dt_", ".wav")
            try {
                DesktopWavCodec.writeWav(buffer, tempWav, DesktopSampleFormat.PCM_16, dither = false)
                val main = Main()
                val args = arrayOf(
                    "-b", bitrateKbps.coerceIn(128, 320).toString(),
                    "--cbr",
                    "-q", "2",
                    tempWav.absolutePath,
                    targetMp3.absolutePath
                )
                val code = main.run(args)
                require(code == 0 && targetMp3.exists() && targetMp3.length() > 0L) {
                    "MP3 encoder exited with code $code"
                }
                Result.success(targetMp3)
            } finally {
                tempWav.delete()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
