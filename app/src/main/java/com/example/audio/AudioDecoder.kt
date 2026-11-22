package com.example.audio

import android.content.ContentResolver
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any audio file exposed by a content Uri (MP3, AAC, OGG, WAV, FLAC, ...)
 * into an [AudioBuffer] using Android's MediaExtractor + MediaCodec.
 */
object AudioDecoder {

    private const val TIMEOUT_US = 10_000L

    fun decodeUriToBuffer(
        context: Context,
        uri: Uri,
        targetSampleRate: Int = 44100,
        targetChannels: Int = 1,
        name: String = "Imported"
    ): AudioBuffer {
        val cr: ContentResolver = context.contentResolver
        var pfd: ParcelFileDescriptor? = null
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null

        try {
            pfd = cr.openFileDescriptor(uri, "r")
            if (pfd != null) {
                extractor.setDataSource(pfd.fileDescriptor)
            } else {
                extractor.setDataSource(context, uri, null)
            }

            var audioTrackIndex = -1
            var mime: String? = null
            var sourceSampleRate = 44100
            var sourceChannels = 1

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = if (format.containsKey(MediaFormat.KEY_MIME)) {
                    format.getString(MediaFormat.KEY_MIME).orEmpty()
                } else ""
                if (trackMime.startsWith("audio/")) {
                    audioTrackIndex = i
                    mime = trackMime
                    sourceSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(8000, 192000)
                    } else 44100
                    sourceChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
                    } else 1
                    break
                }
            }

            if (audioTrackIndex < 0 || mime.isNullOrBlank()) {
                throw IllegalArgumentException("No audio track found in $uri")
            }

            extractor.selectTrack(audioTrackIndex)
            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(extractor.getTrackFormat(audioTrackIndex), null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            val pcmChunks = mutableListOf<ByteArray>()
            var inputEos = false
            var outputEos = false

            while (!outputEos) {
                if (!inputEos) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = decoder.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sourceSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(8000, 192000)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            sourceChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
                        }
                    }
                    outIndex >= 0 -> {
                        val outBuf = decoder.getOutputBuffer(outIndex)
                        if (info.size > 0 && outBuf != null) {
                            outBuf.position(info.offset)
                            val chunk = ByteArray(info.size)
                            outBuf.get(chunk, 0, info.size)
                            pcmChunks.add(chunk)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEos = true
                        }
                    }
                }
            }

            val totalBytes = pcmChunks.sumOf { it.size }
            if (totalBytes == 0) {
                throw IllegalStateException("Decoded audio is empty: $uri")
            }
            val pcm = ByteArray(totalBytes)
            var dst = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, pcm, dst, chunk.size)
                dst += chunk.size
            }

            val shorts = ShortArray(totalBytes / 2)
            ByteBuffer.wrap(pcm)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(shorts)
            val floatSamples = WavCodec.pcm16ShortsToFloats(shorts)

            var buffer = AudioBuffer(floatSamples, sourceSampleRate, sourceChannels.coerceIn(1, 2), name)
            buffer = buffer.convertChannels(targetChannels.coerceIn(1, 2))
            if (targetSampleRate > 0 && targetSampleRate != sourceSampleRate) {
                buffer = buffer.resample(targetSampleRate)
            }
            return buffer
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            runCatching { pfd?.close() }
        }
    }
}
