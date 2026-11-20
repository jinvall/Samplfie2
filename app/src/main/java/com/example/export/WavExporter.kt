package com.example.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.audio.AudioBuffer
import com.example.audio.WavCodec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExportedAudioResult(
    val formatLabel: String,
    val fileName: String,
    val displayLocation: String,
    val byteSize: Long,
    val uri: Uri? = null
)

/**
 * Local device .WAV exporter writing standard RIFF/WAVE PCM files to Music/Samplfie2/.
 */
object WavExporter {

    suspend fun saveWavToDevice(
        context: Context,
        buffer: AudioBuffer,
        fileNameBase: String
    ): Result<ExportedAudioResult> = withContext(Dispatchers.IO) {
        if (buffer.frameCount == 0) {
            return@withContext Result.failure(IllegalStateException("Audio buffer is empty"))
        }
        val sanitizedBase = fileNameBase
            .trim()
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "samplfie2_take_${System.currentTimeMillis()}" }
        val finalName = if (sanitizedBase.endsWith(".wav", ignoreCase = true)) {
            sanitizedBase
        } else {
            "$sanitizedBase.wav"
        }

        try {
            val wavBytes = WavCodec.encodeToBytes(buffer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, finalName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Samplfie2")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = runCatching {
                    resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                }.getOrNull()
                if (uri != null) {
                    val wrote = runCatching {
                        resolver.openOutputStream(uri)?.use { it.write(wavBytes) } != null
                    }.getOrDefault(false)
                    if (wrote) {
                        values.clear()
                        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                        runCatching { resolver.update(uri, values, null, null) }
                        return@withContext Result.success(
                            ExportedAudioResult(
                                formatLabel = "WAV",
                                fileName = finalName,
                                displayLocation = "Music/Samplfie2/$finalName",
                                byteSize = wavBytes.size.toLong(),
                                uri = uri
                            )
                        )
                    }
                }
            }

            val musicDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
                "Samplfie2"
            ).apply { mkdirs() }
            val outFile = File(musicDir, finalName)
            outFile.writeBytes(wavBytes)
            Result.success(
                ExportedAudioResult(
                    formatLabel = "WAV",
                    fileName = finalName,
                    displayLocation = "Samplfie2/$finalName",
                    byteSize = wavBytes.size.toLong(),
                    uri = Uri.fromFile(outFile)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
