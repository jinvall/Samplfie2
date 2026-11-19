package com.example.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.audio.AudioBuffer
import com.example.audio.WavCodec
import de.sciss.jump3r.Main as LameMain
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExportedMp3Result(
    val fileName: String,
    val displayLocation: String,
    val byteSize: Long,
    val uri: Uri? = null
)

/**
 * Pure-Java LAME MP3 encoder and Android MediaStore / Music directory exporter.
 */
object Mp3Exporter {

    suspend fun encodeBufferToMp3File(
        buffer: AudioBuffer,
        targetMp3File: File,
        bitrateKbps: Int = 192
    ): Result<File> = withContext(Dispatchers.IO) {
        if (buffer.frameCount == 0) {
            return@withContext Result.failure(IllegalStateException("Audio buffer is empty"))
        }

        val tempWav = File(
            targetMp3File.parentFile ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp"),
            "tmp_lame_${System.nanoTime()}.wav"
        )
        try {
            WavCodec.writeWav(buffer, tempWav)
            targetMp3File.parentFile?.mkdirs()
            val safeBitrate = bitrateKbps.coerceIn(64, 320)
            val exitCode = LameMain().run(
                arrayOf(
                    "--silent",
                    "-b",
                    safeBitrate.toString(),
                    "-q",
                    "2",
                    tempWav.absolutePath,
                    targetMp3File.absolutePath
                )
            )
            if (exitCode != 0 || !targetMp3File.exists() || targetMp3File.length() == 0L) {
                return@withContext Result.failure(
                    IllegalStateException("MP3 encoding failed with code $exitCode")
                )
            }
            Result.success(targetMp3File)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempWav.delete()
        }
    }

    suspend fun saveMp3ToDevice(
        context: Context,
        buffer: AudioBuffer,
        fileNameBase: String,
        bitrateKbps: Int = 192
    ): Result<ExportedMp3Result> = withContext(Dispatchers.IO) {
        val sanitizedBase = fileNameBase
            .trim()
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "samplfie2_mix_${System.currentTimeMillis()}" }
        val finalName = if (sanitizedBase.endsWith(".mp3", ignoreCase = true)) {
            sanitizedBase
        } else {
            "$sanitizedBase.mp3"
        }

        val tempMp3 = File(context.cacheDir, "export_${System.nanoTime()}.mp3")
        try {
            val encodeRes = encodeBufferToMp3File(buffer, tempMp3, bitrateKbps)
            if (encodeRes.isFailure) {
                return@withContext Result.failure(encodeRes.exceptionOrNull()!!)
            }
            val mp3Bytes = tempMp3.readBytes()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, finalName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Samplfie2")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext Result.failure(IllegalStateException("MediaStore insert failed"))
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(mp3Bytes)
                } ?: return@withContext Result.failure(IllegalStateException("Cannot open output stream"))

                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                Result.success(
                    ExportedMp3Result(
                        fileName = finalName,
                        displayLocation = "Music/Samplfie2/$finalName",
                        byteSize = mp3Bytes.size.toLong(),
                        uri = uri
                    )
                )
            } else {
                val musicDir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
                    "Samplfie2"
                ).apply { mkdirs() }
                val outFile = File(musicDir, finalName)
                outFile.writeBytes(mp3Bytes)
                Result.success(
                    ExportedMp3Result(
                        fileName = finalName,
                        displayLocation = outFile.absolutePath,
                        byteSize = mp3Bytes.size.toLong(),
                        uri = Uri.fromFile(outFile)
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempMp3.delete()
        }
    }
}
