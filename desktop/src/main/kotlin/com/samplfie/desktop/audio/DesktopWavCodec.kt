package com.samplfie.desktop.audio

import com.samplfie.desktop.model.DesktopAudioBuffer
import de.sciss.jump3r.Main
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Standard RIFF/WAVE 16-bit PCM reader/writer and pure-Java LAME MP3 exporter for Linux desktop.
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

    fun encodeToBytes(buffer: DesktopAudioBuffer): ByteArray {
        val pcm = floatToPcm16Bytes(buffer.samples)
        val dataLen = pcm.size
        val byteRate = buffer.sampleRate * buffer.channels * 2
        val blockAlign = buffer.channels * 2
        val out = ByteArrayOutputStream(44 + dataLen)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(buffer.channels.toShort())
        header.putInt(buffer.sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLen)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }

    fun writeWav(buffer: DesktopAudioBuffer, file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(encodeToBytes(buffer))
    }

    fun readWav(file: File): DesktopAudioBuffer = readWav(file.readBytes(), file.nameWithoutExtension)

    fun readWav(bytes: ByteArray, name: String = "WAV"): DesktopAudioBuffer {
        require(bytes.size >= 44) { "WAV file too small (${bytes.size} bytes)" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { bb.get(it) }.toString(Charsets.US_ASCII)
        bb.int // chunk size
        val wave = ByteArray(4).also { bb.get(it) }.toString(Charsets.US_ASCII)
        require(riff == "RIFF" && wave == "WAVE") { "Unsupported audio container (expected RIFF/WAVE)" }

        var sampleRate = 44100
        var channels = 1
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (bb.remaining() >= 8) {
            val id = ByteArray(4).also { bb.get(it) }.toString(Charsets.US_ASCII)
            val size = bb.int
            if (size < 0 || size > bb.remaining()) break
            when (id) {
                "fmt " -> {
                    val format = bb.short.toInt() and 0xFFFF
                    require(format == 1) { "Only uncompressed PCM WAV is supported (format=$format)" }
                    channels = (bb.short.toInt() and 0xFFFF).coerceIn(1, 2)
                    sampleRate = bb.int
                    bb.int // byteRate
                    bb.short // blockAlign
                    bitsPerSample = bb.short.toInt() and 0xFFFF
                    val extra = size - 16
                    if (extra > 0) bb.position(bb.position() + extra)
                }
                "data" -> {
                    dataOffset = bb.position()
                    dataSize = size
                    break
                }
                else -> bb.position(bb.position() + size)
            }
        }
        require(dataOffset >= 0 && dataSize >= 2) { "Missing PCM data chunk" }
        require(bitsPerSample == 16) { "Only 16-bit PCM WAV is supported (got $bitsPerSample-bit)" }

        val floats = pcm16BytesToFloats(bytes.copyOfRange(dataOffset, dataOffset + dataSize))
        return DesktopAudioBuffer(floats, sampleRate, channels, name)
    }
}

object DesktopMp3Exporter {
    fun encodeToFile(buffer: DesktopAudioBuffer, targetMp3: File, bitrateKbps: Int = 192): Result<File> {
        return try {
            targetMp3.parentFile?.mkdirs()
            val tempWav = File.createTempFile("samplfie2_dt_", ".wav")
            try {
                DesktopWavCodec.writeWav(buffer, tempWav)
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
