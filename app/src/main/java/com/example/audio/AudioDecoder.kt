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
 *
 * The decoded PCM is normalized to [-1f, 1f], optionally channel-converted and
 * resampled to [targetSampleRate] / [targetChannels] so it lands in the editor
 * at the project's configured format. No storage permissions are required because
 * the content Uri is opened via [ContentResolver.openFileDescriptor].
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
        val pfd: ParcelFileDescriptor =
            cr.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("Cannot open audio Uri: $uri")

        val extractor = MediaExtractor().apply {
            setDataSource(pfd.fileDescriptor)
        }

        // Locate the first audio track
        var audioTrackIndex = -1
        var mime: String? = null
        var sourceSampleRate = 44100
        var sourceChannels = 1

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val trackMime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (trackMime.startsWith("audio/")) {
                audioTrackIndex = i
                mime = trackMime
                sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 44100
                sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
                break
            }
        }

        if (audioTrackIndex < 0 || mime.isNullOrBlank()) {
            pfd.close()
            extractor.release()
            throw IllegalArgumentException("No audio track found in $uri")
        }

        extractor.selectTrack(audioTrackIndex)

        val decoder = MediaCodec.createDecoderByType(mime).apply {
            configure(extractor.getTrackFormat(audioTrackIndex), null, null, 0)
            start()
        }

        val info = MediaCodec.BufferInfo()
        val pcmChunks = mutableListOf<ByteArray>()
        var inputEos = false
        var outputEos = false

        while (!outputEos) {
            // ---- feed encoded input to the decoder ----
            if (!inputEos) {
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inBuf = decoder.getInputBuffer(inIndex) ?: continue
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        // Signal end-of-stream to the decoder
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    } else {
                        decoder.queueInputBuffer(
                            inIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime,
                            0
                        )
                        extractor.advance()
                    }
                }
            }

            // ---- drain decoded PCM output ----
            val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    // Handled via getOutputBuffer(index) below; nothing to do here
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Format may change mid-stream (e.g. HE-AAC); we ignore it
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // No output available yet
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

        decoder.stop()
        decoder.release()
        extractor.release()
        pfd.close()

        // ---- assemble raw PCM ----
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

        // ---- convert 16-bit little-endian PCM -> float samples [-1f, 1f] ----
        val shorts = ShortArray(totalBytes / 2)
        ByteBuffer.wrap(pcm)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        val floatSamples = WavCodec.pcm16ShortsToFloats(shorts)

        // ---- build at source format, then convert channels & resample ----
        var buffer = AudioBuffer(floatSamples, sourceSampleRate, sourceChannels, name)
        buffer = buffer.convertChannels(targetChannels.coerceIn(1, 2))
        if (targetSampleRate > 0 && targetSampleRate != sourceSampleRate) {
            buffer = buffer.resample(targetSampleRate)
        }

        return buffer
    }
}
