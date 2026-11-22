package com.example.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Universal Audio Importer supporting WAV, MP3, AAC/M4A, OGG, FLAC, Opus, and any Android MediaCodec audio stream.
 * Decodes imported audio into a canonical normalized AudioBuffer and writes an editable .wav take on import.
 * Handles SAF Document URIs, MediaStore URIs, file:// URIs, multi-channel sources, Float/16-bit/8-bit PCM,
 * and temp-file fallback if MediaExtractor cannot read directly from a stream descriptor.
 */
object AudioImporter {

    private const val MAX_WAV_FASTPATH_BYTES = 64 * 1024 * 1024 // 64 MB cap to avoid OOM
    private const val MAX_TEMP_COPY_BYTES = 96 * 1024 * 1024

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

            // Fast path: check if source is a standard RIFF WAV file without loading huge streams blindly
            val wavHeadBytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    readWavIfHeaderMatches(stream)
                }
            }.getOrNull()

            val decodedBuffer = if (wavHeadBytes != null) {
                WavCodec.readWav(wavHeadBytes, displayName)
            } else {
                decodeWithMediaCodec(context, uri, displayName)
            }

            require(decodedBuffer.frameCount > 0) { "Imported file contains no playable audio frames" }

            val finalBuffer = if (targetSampleRate != null && decodedBuffer.sampleRate != targetSampleRate) {
                decodedBuffer.resample(targetSampleRate)
            } else {
                decodedBuffer
            }

            takesDir.mkdirs()
            val wavFile = File(takesDir, "$displayName.wav")
            WavCodec.writeWav(finalBuffer, wavFile)
            Result.success(finalBuffer to wavFile)
        } catch (ce: CancellationException) {
            throw ce
        } catch (se: SecurityException) {
            Result.failure(
                SecurityException("Storage/audio file permission required to open this file: ${se.message}", se)
            )
        } catch (t: Throwable) {
            Result.failure(
                if (t is Exception) t else RuntimeException(t.message ?: "Failed to import audio", t)
            )
        }
    }

    private fun readWavIfHeaderMatches(stream: InputStream): ByteArray? {
        val head = ByteArray(12)
        var headRead = 0
        while (headRead < 12) {
            val r = stream.read(head, headRead, 12 - headRead)
            if (r < 0) break
            headRead += r
        }
        if (headRead != 12) return null
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(head, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) {
            return null
        }

        val baos = ByteArrayOutputStream(minOf(65536, MAX_WAV_FASTPATH_BYTES))
        baos.write(head, 0, 12)
        val buf = ByteArray(16 * 1024)
        while (baos.size() < MAX_WAV_FASTPATH_BYTES) {
            val r = stream.read(buf)
            if (r <= 0) break
            val allowed = minOf(r, MAX_WAV_FASTPATH_BYTES - baos.size())
            baos.write(buf, 0, allowed)
        }
        return baos.toByteArray()
    }

    private fun decodeWithMediaCodec(context: Context, uri: Uri, name: String): AudioBuffer {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var tempFile: File? = null
        try {
            val attached = attachExtractorDataSource(context, extractor, uri) { tmp ->
                tempFile = tmp
            }
            require(attached) { "Unable to open audio stream for $uri (grant Audio/Storage permission or pick via Files)" }

            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = if (fmt.containsKey(MediaFormat.KEY_MIME)) fmt.getString(MediaFormat.KEY_MIME) else null
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            require(audioTrackIndex >= 0 && trackFormat != null) {
                "No playable audio track found in selected file"
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Audio track missing MIME type")
            var sampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(8000, 192000)
            } else 44100
            var rawChannels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else 1
            var pcmEncoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
            ) {
                trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val pcmOut = ByteArrayOutputStream()
            var inputDone = false
            var outputDone = false
            val bytesPerSample = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else if (pcmEncoding == AudioFormat.ENCODING_PCM_8BIT) 1 else 2
            val maxBytes = sampleRate * rawChannels.coerceAtMost(8) * bytesPerSample * 180 // Cap ~180s

            while (!outputDone && pcmOut.size() < maxBytes) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
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
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(8000, 192000)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            rawChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                            outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ) {
                            pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
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
            val floats = decodeRawPcmBytes(pcmBytes, pcmEncoding)
            require(floats.isNotEmpty()) { "Decoded audio stream was empty" }

            // Downmix >2 channels cleanly to stereo, otherwise preserve mono/stereo
            val targetOutChannels = if (rawChannels >= 2) 2 else 1
            val normalizedFloats = if (rawChannels > 2) {
                downmixMultiChannelToStereo(floats, rawChannels)
            } else {
                floats
            }
            return AudioBuffer(
                samples = normalizedFloats,
                sampleRate = sampleRate,
                channels = targetOutChannels,
                name = name
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { tempFile?.delete() }
        }
    }

    private fun attachExtractorDataSource(
        context: Context,
        extractor: MediaExtractor,
        uri: Uri,
        onTempFile: (File) -> Unit
    ): Boolean {
        // Strategy 1: ParcelFileDescriptor (works reliably for SAF/Downloads/MediaStore when readable)
        val pfdOk = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
                true
            } ?: false
        }.getOrDefault(false)
        if (pfdOk) return true

        // Strategy 2: Context+Uri setDataSource
        val uriOk = runCatching {
            extractor.setDataSource(context, uri, null)
            true
        }.getOrDefault(false)
        if (uriOk) return true

        // Strategy 3: Stream copy into cache temp file (works even for non-seekable pipes/virtual docs)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val tmp = File.createTempFile("samplfie2_imp_", ".bin", context.cacheDir)
                onTempFile(tmp)
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var copied = 0
                    while (copied < MAX_TEMP_COPY_BYTES) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        val allowed = minOf(r, MAX_TEMP_COPY_BYTES - copied)
                        out.write(buf, 0, allowed)
                        copied += allowed
                    }
                }
                if (tmp.length() > 0) {
                    extractor.setDataSource(tmp.absolutePath)
                    true
                } else {
                    false
                }
            } ?: false
        }.getOrDefault(false)
    }

    private fun decodeRawPcmBytes(pcmBytes: ByteArray, pcmEncoding: Int): FloatArray {
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val count = pcmBytes.size / 4
                val fb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                FloatArray(count) { fb.get().coerceIn(-1f, 1f) }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                FloatArray(pcmBytes.size) { i ->
                    val unsigned = pcmBytes[i].toInt() and 0xFF
                    ((unsigned - 128) / 128f).coerceIn(-1f, 1f)
                }
            }
            else -> {
                val count = pcmBytes.size / 2
                val sb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                FloatArray(count) { (sb.get().toFloat() / 32768f).coerceIn(-1f, 1f) }
            }
        }
    }

    private fun downmixMultiChannelToStereo(samples: FloatArray, rawChannels: Int): FloatArray {
        val frames = samples.size / rawChannels
        if (frames <= 0) return FloatArray(0)
        val out = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val base = f * rawChannels
            var left = samples[base]
            var right = samples[base + 1]
            for (c in 2 until rawChannels) {
                val extra = samples[base + c] * 0.5f
                if (c % 2 == 0) left += extra else right += extra
            }
            val norm = 1f / (1f + (rawChannels - 2) * 0.25f)
            out[f * 2] = (left * norm).coerceIn(-1f, 1f)
            out[f * 2 + 1] = (right * norm).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        runCatching {
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
        }
        return uri.lastPathSegment ?: "imported_audio"
    }
}
