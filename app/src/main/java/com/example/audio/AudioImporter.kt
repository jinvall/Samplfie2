package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Universal Audio Importer supporting WAV, MP3, AAC/M4A, OGG, FLAC, Opus, and any Android MediaCodec audio stream.
 * Decodes imported audio into a canonical normalized AudioBuffer and writes an editable .wav take on import.
 */
object AudioImporter {

    suspend fun importUriAsWav(
        context: Context,
        uri: Uri,
        takesDir: File,
        targetSampleRate: Int? = null
    ): Result<Pair<AudioBuffer, File>> = withContext(Dispatchers.IO) {
        try {
            val displayName = resolveDisplayName(context, uri)
                .substringBeforeLast('.')
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .ifBlank { "IMPORT_${System.currentTimeMillis()}" }

            // Fast path: check if source is a standard RIFF WAV file
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                val head = ByteArray(12)
                val read = stream.read(head)
                if (read == 12 &&
                    String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
                ) {
                    val rest = stream.readBytes()
                    head + rest
                } else {
                    null
                }
            }

            val decodedBuffer = if (rawBytes != null) {
                WavCodec.readWav(rawBytes, displayName)
            } else {
                decodeWithMediaCodec(context, uri, displayName)
            }

            val finalBuffer = if (targetSampleRate != null && decodedBuffer.sampleRate != targetSampleRate) {
                decodedBuffer.resample(targetSampleRate)
            } else {
                decodedBuffer
            }

            takesDir.mkdirs()
            val wavFile = File(takesDir, "$displayName.wav")
            WavCodec.writeWav(finalBuffer, wavFile)
            Result.success(finalBuffer to wavFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun decodeWithMediaCodec(context: Context, uri: Uri, name: String): AudioBuffer {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            require(audioTrackIndex >= 0 && trackFormat != null) {
                "No playable audio track found in selected file"
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val pcmOut = ByteArrayOutputStream()
            var inputDone = false
            var outputDone = false
            val maxBytes = sampleRate * channels * 2 * 180 // Cap at 180s for memory safety

            while (!outputDone && pcmOut.size() < maxBytes) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.get(chunk)
                            pcmOut.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val pcmBytes = pcmOut.toByteArray()
            require(pcmBytes.size >= 4) { "Decoded audio stream was empty" }
            val bb = java.nio.ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            val sampleCount = pcmBytes.size / 2
            val floats = FloatArray(sampleCount) {
                (bb.short.toFloat() / 32768f).coerceIn(-1f, 1f)
            }
            return AudioBuffer(
                samples = floats,
                sampleRate = sampleRate,
                channels = channels,
                name = name
            )
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val value = cursor.getString(idx)
                        if (!value.isNullOrBlank()) return value
                    }
                }
            }
        return uri.lastPathSegment ?: "imported_audio"
    }
}
