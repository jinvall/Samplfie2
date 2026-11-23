package com.example.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure Kotlin RIFF/WAVE encoder and decoder supporting 8/16/24/32-bit PCM and 32/64-bit IEEE float WAVs.
 */
object WavCodec {

    fun encodeToBytes(
        buffer: AudioBuffer,
        targetFormat: AudioSampleFormat = buffer.sampleFormat,
        dither: Boolean = false
    ): ByteArray {
        val shapedSamples = if (dither) {
            AudioSampleFormat.quantizeSamples(buffer.samples, targetFormat, dither = true)
        } else {
            buffer.samples
        }
        val payload = encodeSamples(shapedSamples, targetFormat)
        val bytesPerSample = targetFormat.bitsPerSample / 8
        val byteRate = buffer.sampleRate * buffer.channels * bytesPerSample
        val blockAlign = buffer.channels * bytesPerSample
        val dataSize = payload.size
        val riffChunkSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(intToLittleEndian(riffChunkSize))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt subchunk
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(intToLittleEndian(16))
        out.write(shortToLittleEndian(targetFormat.wavFormatCode))
        out.write(shortToLittleEndian(buffer.channels))
        out.write(intToLittleEndian(buffer.sampleRate))
        out.write(intToLittleEndian(byteRate))
        out.write(shortToLittleEndian(blockAlign))
        out.write(shortToLittleEndian(targetFormat.bitsPerSample))

        // data subchunk
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(intToLittleEndian(dataSize))
        out.write(payload)
        return out.toByteArray()
    }

    fun writeWav(
        buffer: AudioBuffer,
        file: File,
        targetFormat: AudioSampleFormat = buffer.sampleFormat,
        dither: Boolean = false
    ): File {
        file.parentFile?.mkdirs()
        file.writeBytes(encodeToBytes(buffer, targetFormat, dither))
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
        val format = AudioSampleFormat.fromWavHeader(audioFormat, bitsPerSample)
        return AudioBuffer(
            samples = samples,
            sampleRate = sampleRate,
            channels = channels,
            name = name,
            sampleFormat = format
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

    private fun encodeSamples(samples: FloatArray, format: AudioSampleFormat): ByteArray = when (format) {
        AudioSampleFormat.PCM_8 -> ByteArray(samples.size) { i ->
            val v = ((samples[i].coerceIn(-1f, 1f) * 127f) + 128f).toInt().coerceIn(0, 255)
            v.toByte()
        }
        AudioSampleFormat.PCM_16 -> floatToPcm16Bytes(samples)
        AudioSampleFormat.PCM_24 -> ByteArray(samples.size * 3).also { out ->
            for (i in samples.indices) {
                val s = (samples[i].coerceIn(-1f, 1f) * 8388607.0).toInt().coerceIn(-8388608, 8388607)
                val base = i * 3
                out[base] = (s and 0xFF).toByte()
                out[base + 1] = ((s shr 8) and 0xFF).toByte()
                out[base + 2] = ((s shr 16) and 0xFF).toByte()
            }
        }
        AudioSampleFormat.PCM_32 -> ByteArray(samples.size * 4).also { out ->
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                val v = (s.toDouble().coerceIn(-1.0, 1.0) * 2147483647.0).toInt()
                bb.putInt(v)
            }
        }
        AudioSampleFormat.FLOAT_32 -> ByteArray(samples.size * 4).also { out ->
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) bb.putFloat(s.coerceIn(-1f, 1f))
        }
        AudioSampleFormat.FLOAT_64 -> ByteArray(samples.size * 8).also { out ->
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) bb.putDouble(s.toDouble().coerceIn(-1.0, 1.0))
        }
    }

    fun pcm16ShortsToFloats(shorts: ShortArray, count: Int = shorts.size): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) {
            out[i] = (shorts[i].toFloat() / 32768f).coerceIn(-1f, 1f)
        }
        return out
    }

    fun pcm16BytesToFloats(bytes: ByteArray): FloatArray {
        val count = bytes.size / 2
        val out = FloatArray(count)
        for (i in 0 until count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort()
            out[i] = (sample.toFloat() / 32768f).coerceIn(-1f, 1f)
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
            audioFormat == 3 && bitsPerSample == 64 -> {
                val count = size / 8
                FloatArray(count) { bb.double.toFloat().coerceIn(-1f, 1f) }
            }
            audioFormat == 3 && bitsPerSample == 32 -> {
                val count = size / 4
                FloatArray(count) { bb.float.coerceIn(-1f, 1f) }
            }
            audioFormat == 1 && bitsPerSample == 32 -> {
                val count = size / 4
                FloatArray(count) { (bb.int.toDouble() / 2147483648.0).toFloat().coerceIn(-1f, 1f) }
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
