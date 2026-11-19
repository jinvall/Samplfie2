package com.example.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure Kotlin RIFF/WAVE PCM encoder and decoder supporting 8/16/24-bit PCM and 32-bit float WAVs.
 */
object WavCodec {

    fun encodeToBytes(buffer: AudioBuffer): ByteArray {
        val pcm16 = floatToPcm16Bytes(buffer.samples)
        val out = ByteArrayOutputStream(44 + pcm16.size)
        val byteRate = buffer.sampleRate * buffer.channels * 2
        val blockAlign = buffer.channels * 2
        val dataSize = pcm16.size
        val riffChunkSize = 36 + dataSize

        // RIFF header
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(intToLittleEndian(riffChunkSize))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt subchunk
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(intToLittleEndian(16)) // Subchunk1Size for PCM
        out.write(shortToLittleEndian(1)) // AudioFormat 1 = PCM
        out.write(shortToLittleEndian(buffer.channels))
        out.write(intToLittleEndian(buffer.sampleRate))
        out.write(intToLittleEndian(byteRate))
        out.write(shortToLittleEndian(blockAlign))
        out.write(shortToLittleEndian(16)) // BitsPerSample

        // data subchunk
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(intToLittleEndian(dataSize))
        out.write(pcm16)
        return out.toByteArray()
    }

    fun writeWav(buffer: AudioBuffer, file: File): File {
        file.parentFile?.mkdirs()
        file.writeBytes(encodeToBytes(buffer))
        return file
    }

    fun readWav(file: File): AudioBuffer {
        return file.inputStream().use { readWav(it, file.nameWithoutExtension) }
    }

    fun readWav(bytes: ByteArray, name: String = "WAV"): AudioBuffer {
        return ByteArrayInputStream(bytes).use { readWav(it, name) }
    }

    fun readWav(inputStream: InputStream, name: String = "WAV"): AudioBuffer {
        val allBytes = inputStream.readBytes()
        require(allBytes.size >= 44) { "Invalid WAV data: file too small (${allBytes.size} bytes)" }

        val bb = ByteBuffer.wrap(allBytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { bb.get(it) }
        require(String(riff, Charsets.US_ASCII) == "RIFF") { "Invalid WAV: missing RIFF header" }
        bb.int // file size
        val wave = ByteArray(4).also { bb.get(it) }
        require(String(wave, Charsets.US_ASCII) == "WAVE") { "Invalid WAV: missing WAVE header" }

        var audioFormat = 1
        var channels = 1
        var sampleRate = 44100
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (bb.remaining() >= 8) {
            val chunkIdBytes = ByteArray(4).also { bb.get(it) }
            val chunkId = String(chunkIdBytes, Charsets.US_ASCII)
            val chunkSize = bb.int.coerceAtLeast(0)
            val chunkStart = bb.position()

            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16 && bb.remaining() >= 16) { "Invalid WAV: malformed fmt chunk" }
                    audioFormat = bb.short.toInt() and 0xFFFF
                    channels = (bb.short.toInt() and 0xFFFF).coerceIn(1, 2)
                    sampleRate = bb.int.coerceAtLeast(8000)
                    bb.int // byteRate
                    bb.short // blockAlign
                    bitsPerSample = bb.short.toInt() and 0xFFFF
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataSize = chunkSize.coerceAtMost(allBytes.size - chunkStart)
                    break
                }
            }
            val nextPos = (chunkStart + chunkSize + (chunkSize % 2)).coerceAtMost(allBytes.size)
            bb.position(nextPos)
        }

        require(dataOffset >= 0 && dataSize >= 0) { "Invalid WAV: missing data chunk" }
        val samples = decodeSamples(allBytes, dataOffset, dataSize, audioFormat, bitsPerSample)
        return AudioBuffer(
            samples = samples,
            sampleRate = sampleRate,
            channels = channels,
            name = name
        )
    }

    fun floatToPcm16Bytes(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            val s = (clamped * 32767f).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    fun pcm16ShortsToFloats(shorts: ShortArray, count: Int = shorts.size): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) {
            out[i] = (shorts[i].toFloat() / 32768f).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun decodeSamples(
        bytes: ByteArray,
        offset: Int,
        size: Int,
        audioFormat: Int,
        bitsPerSample: Int
    ): FloatArray {
        val bb = ByteBuffer.wrap(bytes, offset, size).order(ByteOrder.LITTLE_ENDIAN)
        return when {
            audioFormat == 3 && bitsPerSample == 32 -> {
                val count = size / 4
                FloatArray(count) { bb.float.coerceIn(-1f, 1f) }
            }
            bitsPerSample == 8 -> {
                FloatArray(size) {
                    val unsigned = bytes[offset + it].toInt() and 0xFF
                    ((unsigned - 128) / 128f).coerceIn(-1f, 1f)
                }
            }
            bitsPerSample == 24 -> {
                val count = size / 3
                FloatArray(count) { i ->
                    val base = offset + i * 3
                    val b0 = bytes[base].toInt() and 0xFF
                    val b1 = bytes[base + 1].toInt() and 0xFF
                    val b2 = bytes[base + 2].toInt() // signed MSB
                    val raw = (b2 shl 16) or (b1 shl 8) or b0
                    (raw / 8388608f).coerceIn(-1f, 1f)
                }
            }
            else -> {
                val count = size / 2
                FloatArray(count) { (bb.short.toFloat() / 32768f).coerceIn(-1f, 1f) }
            }
        }
    }

    private fun intToLittleEndian(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    private fun shortToLittleEndian(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte()
    )
}
