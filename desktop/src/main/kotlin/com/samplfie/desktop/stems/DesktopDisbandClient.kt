package com.samplfie.desktop.stems

import com.samplfie.desktop.audio.DesktopMp3Exporter
import com.samplfie.desktop.audio.DesktopWavCodec
import com.samplfie.desktop.model.DesktopAudioBuffer
import com.samplfie.desktop.model.DesktopSampleFormat
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.math.ceil
import kotlin.math.max

enum class DesktopDisbandStem(
    val apiId: String,
    val label: String,
    val shortTag: String,
    val isCoreAllStem: Boolean,
    val isPrimary: Boolean
) {
    VOCALS("vocals", "Vocals", "VOX", true, true),
    INSTRUMENTAL("instrumental", "Instrumental", "INST", false, true),
    DRUMS("drums", "Drums", "DRM", true, true),
    BASS("bass", "Bass", "BAS", true, true),
    GUITAR("guitar", "Guitar", "GTR", true, true),
    PIANO("piano", "Piano", "PNO", true, true),
    OTHER("other", "Other", "OTH", true, true),
    LEAD_VOCALS("lead_vocals", "Lead Vox", "LVOX", false, false),
    BACKING_VOCALS("backing_vocals", "Backing Vox", "BVOX", false, false),
    KICK("kick", "Kick", "KCK", false, false),
    SNARE("snare", "Snare", "SNR", false, false),
    TOMS("toms", "Toms", "TOM", false, false),
    HIHAT("hihat", "Hi-Hat", "HAT", false, false);

    companion object {
        val ALL_STEMS_DEFAULT: List<DesktopDisbandStem> = entries.filter { it.isCoreAllStem }
    }
}

enum class DesktopStemPreset(val label: String, val stems: Set<DesktopDisbandStem>) {
    VOCALS_ONLY("Vocals Only", setOf(DesktopDisbandStem.VOCALS)),
    VOCALS_DRUMS("Vocals + Drums", setOf(DesktopDisbandStem.VOCALS, DesktopDisbandStem.DRUMS)),
    VOCALS_INST("Vocals + Inst", setOf(DesktopDisbandStem.VOCALS, DesktopDisbandStem.INSTRUMENTAL)),
    DRUMS_BASS("Drums + Bass", setOf(DesktopDisbandStem.DRUMS, DesktopDisbandStem.BASS)),
    FOUR_STEM_BAND("Band (Vox/Drm/Bas/Gtr)", setOf(DesktopDisbandStem.VOCALS, DesktopDisbandStem.DRUMS, DesktopDisbandStem.BASS, DesktopDisbandStem.GUITAR)),
    UNSPECIFIED_ALL("All Stems (Clear)", emptySet())
}

data class DesktopSeparatedStem(
    val stemId: String,
    val label: String,
    val format: String,
    val buffer: DesktopAudioBuffer,
    val sizeBytes: Int,
    val creditCharge: Int
)

data class DesktopStemState(
    val selectedStems: Set<DesktopDisbandStem> = emptySet(),
    val useSelectionOnly: Boolean = false,
    val showAllStemsConfirmation: Boolean = false,
    val pendingAllStemsEstimatedCredits: Int = 0,
    val pendingAudioDurationSec: Float = 0f,
    val isRunning: Boolean = false,
    val activeJobId: String? = null,
    val jobStatusLabel: String = "IDLE",
    val lastCreditCharge: Int? = null,
    val separatedStems: List<DesktopSeparatedStem> = emptyList(),
    val errorMessage: String? = null,
    val errorPayloadDetail: String? = null,
    val lastToastMessage: String? = null,
    val statusMessage: String = "PICK SPECIFIC STEMS, OR LEAVE EMPTY TO PROMPT FOR ALL-STEMS SEPARATION"
) {
    companion object {
        fun estimateAllStemsCredits(durationSec: Float): Int {
            val mins = max(1, ceil(durationSec / 60.0).toInt())
            return mins * 60
        }
    }
}

data class DesktopHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
    val headers: Map<String, List<String>> = emptyMap(),
    val statusMessage: String = ""
)

interface DesktopDisbandHttpTransport {
    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
        readTimeoutMs: Int = 65_000
    ): DesktopHttpResponse
}

class DesktopHttpUrlTransport : DesktopDisbandHttpTransport {
    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        readTimeoutMs: Int
    ): DesktopHttpResponse {
        val conn = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = readTimeoutMs
            useCaches = false
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val statusMsg = runCatching { conn.responseMessage.orEmpty() }.getOrDefault("")
            val respHeaders = linkedMapOf<String, List<String>>()
            runCatching { conn.headerFields }.getOrNull()?.forEach { (k, v) ->
                val key = k ?: "Status-Line"
                respHeaders[key] = v ?: emptyList()
            }
            val stream: InputStream? = if (code in 200..399) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return DesktopHttpResponse(code, bytes, respHeaders, statusMsg)
        } finally {
            conn.disconnect()
        }
    }
}

data class DesktopDisbandStemRef(val stemId: String, val format: String, val url: String?, val bytes: Int)

data class DesktopDisbandJobResponse(
    val id: String,
    val status: String,
    val charge: Int?,
    val durationSec: Float?,
    val error: String?,
    val stemRefs: List<DesktopDisbandStemRef>,
    val uploadUrl: String? = null,
    val rawDiagnostics: String? = null
)

data class DesktopDisbandPreparedUpload(
    val bytes: ByteArray,
    val fileName: String,
    val contentType: String
)

class DesktopDisbandApiException(
    val httpCode: Int,
    message: String,
    val fullDiagnostics: String = message
) : RuntimeException(message)

internal object DesktopDisbandDiagnosticsFormatter {
    fun formatHttpExchange(
        stage: String,
        method: String,
        url: String,
        requestHeaders: Map<String, String>,
        requestBody: ByteArray?,
        response: DesktopHttpResponse?,
        errorSummary: String,
        cause: Throwable? = null
    ): String = buildString {
        appendLine("=== DISBAND ERROR DIAGNOSTICS ===")
        appendLine("STAGE: $stage")
        appendLine("SUMMARY: $errorSummary")
        appendLine()
        appendLine("--- HTTP REQUEST ---")
        appendLine("$method $url")
        appendLine("Request Headers:")
        if (requestHeaders.isEmpty()) {
            appendLine("  (none)")
        } else {
            requestHeaders.forEach { (k, v) ->
                appendLine("  $k: $v")
            }
        }
        if (requestBody != null && !requestHeaders.keys.any { it.equals("Content-Length", ignoreCase = true) }) {
            appendLine("  Content-Length: ${requestBody.size}")
        }
        appendLine("Request Payload:")
        appendLine(formatRequestBody(requestBody))
        appendLine()
        appendLine("--- HTTP RESPONSE ---")
        if (response == null) {
            appendLine("(No HTTP response received — transport/client failure before response)")
        } else {
            val statusSuffix = if (response.statusMessage.isNotBlank()) " ${response.statusMessage}" else ""
            appendLine("HTTP ${response.statusCode}$statusSuffix")
            appendLine("Response Headers:")
            if (response.headers.isEmpty()) {
                appendLine("  (none reported by transport)")
            } else {
                response.headers.forEach { (k, vals) ->
                    appendLine("  $k: ${vals.joinToString(", ")}")
                }
            }
            appendLine("Response Body (${response.body.size} bytes):")
            val bodyText = String(response.body, Charsets.UTF_8)
            appendLine(bodyText.ifEmpty { "(empty response body)" })
        }
        if (cause != null) {
            appendLine()
            appendLine("--- EXCEPTION TRACE ---")
            appendLine("${cause::class.java.name}: ${cause.message}")
            cause.stackTrace.take(12).forEach { frame ->
                appendLine("  at $frame")
            }
        }
    }.trimEnd()

    private fun formatRequestBody(body: ByteArray?): String {
        if (body == null) return "  (empty — no request body)"
        if (body.isEmpty()) return "  (0 bytes)"
        val sha256 = runCatching {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(body)
                .joinToString("") { "%02x".format(it) }
        }.getOrDefault("unavailable")

        val isWav = body.size >= 44 &&
            body[0] == 'R'.code.toByte() && body[1] == 'I'.code.toByte() &&
            body[2] == 'F'.code.toByte() && body[3] == 'F'.code.toByte() &&
            body[8] == 'W'.code.toByte() && body[9] == 'A'.code.toByte() &&
            body[10] == 'V'.code.toByte() && body[11] == 'E'.code.toByte()

        if (isWav) {
            val bb = java.nio.ByteBuffer.wrap(body).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val riffSize = bb.getInt(4)
            val fmtSize = bb.getInt(16)
            val audioFmt = bb.getShort(20).toInt() and 0xFFFF
            val channels = bb.getShort(22).toInt() and 0xFFFF
            val sampleRate = bb.getInt(24)
            val byteRate = bb.getInt(28)
            val blockAlign = bb.getShort(32).toInt() and 0xFFFF
            val bitsPerSample = bb.getShort(34).toInt() and 0xFFFF
            val dataTag = String(body, 36, 4, Charsets.US_ASCII)
            val dataSize = bb.getInt(40)
            return buildString {
                appendLine("  Type: Binary WAV/RIFF (${body.size} bytes, SHA-256=$sha256)")
                appendLine("  WAV Header: RIFF size=$riffSize, fmtSize=$fmtSize, audioFormat=$audioFmt, channels=$channels, sampleRate=${sampleRate}Hz, byteRate=$byteRate, blockAlign=$blockAlign, bitsPerSample=$bitsPerSample, chunk4='$dataTag', dataBytes=$dataSize")
                val headBytes = body.copyOfRange(0, minOf(body.size, 128))
                appendLine("  First ${headBytes.size} bytes (hex): ${headBytes.joinToString(" ") { "%02X".format(it) }}")
                if (body.size <= 4096) {
                    appendLine("  Full payload (base64): ${java.util.Base64.getEncoder().encodeToString(body)}")
                } else {
                    val tailStart = (body.size - 64).coerceAtLeast(0)
                    val tailBytes = body.copyOfRange(tailStart, body.size)
                    appendLine("  Last ${tailBytes.size} bytes (hex): ${tailBytes.joinToString(" ") { "%02X".format(it) }}")
                }
            }.trimEnd()
        }

        val isMp3 = body.size >= 3 && (
            (body[0] == 'I'.code.toByte() && body[1] == 'D'.code.toByte() && body[2] == '3'.code.toByte()) ||
                ((body[0].toInt() and 0xFF) == 0xFF && (body[1].toInt() and 0xE0) == 0xE0)
            )
        if (isMp3) {
            val headBytes = body.copyOfRange(0, minOf(body.size, 128))
            return buildString {
                appendLine("  Type: Binary MP3 audio/mpeg (${body.size} bytes, SHA-256=$sha256)")
                appendLine("  First ${headBytes.size} bytes (hex): ${headBytes.joinToString(" ") { "%02X".format(it) }}")
                if (body.size <= 4096) {
                    appendLine("  Full payload (base64): ${java.util.Base64.getEncoder().encodeToString(body)}")
                }
            }.trimEnd()
        }

        val textCandidate = String(body, Charsets.UTF_8)
        val controlChars = textCandidate.count { it < ' ' && it != '\n' && it != '\r' && it != '\t' }
        return if (controlChars == 0) {
            "  Type: Text (${body.size} bytes, SHA-256=$sha256)\n$textCandidate"
        } else {
            val headBytes = body.copyOfRange(0, minOf(body.size, 256))
            buildString {
                appendLine("  Type: Binary (${body.size} bytes, SHA-256=$sha256)")
                appendLine("  First ${headBytes.size} bytes (hex): ${headBytes.joinToString(" ") { "%02X".format(it) }}")
                if (body.size <= 4096) {
                    appendLine("  Full payload (base64): ${java.util.Base64.getEncoder().encodeToString(body)}")
                }
            }.trimEnd()
        }
    }
}

class DesktopDisbandClient(
    private val transport: DesktopDisbandHttpTransport = DesktopHttpUrlTransport()
) {
    companion object {
        internal const val MP3_SWITCH_WAV_BYTES = 2_500_000
        internal const val DIRECT_POST_LIMIT_BYTES = 12_000_000
    }

    fun prepareUploadPayload(
        sourceBuffer: DesktopAudioBuffer,
        preferCompressedMp3: Boolean = false,
        mp3BitrateKbps: Int = 256
    ): DesktopDisbandPreparedUpload {
        val baseName = sourceBuffer.name.ifBlank { "samplfie2_mix" }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val wavBytes = DesktopWavCodec.encodeToBytes(sourceBuffer, DesktopSampleFormat.PCM_16, dither = false)
        if (!preferCompressedMp3 && wavBytes.size <= MP3_SWITCH_WAV_BYTES) {
            return DesktopDisbandPreparedUpload(wavBytes, "$baseName.wav", "application/octet-stream")
        }
        val tmpMp3 = File.createTempFile("samplfie2_dbnd_", ".mp3")
        return try {
            val enc = DesktopMp3Exporter.encodeToFile(sourceBuffer, tmpMp3, mp3BitrateKbps.coerceIn(128, 320))
            val mp3Bytes = if (enc.isSuccess && tmpMp3.exists()) tmpMp3.readBytes() else null
            if (mp3Bytes != null && mp3Bytes.isNotEmpty() && mp3Bytes.size < wavBytes.size) {
                DesktopDisbandPreparedUpload(mp3Bytes, "$baseName.mp3", "audio/mpeg")
            } else {
                DesktopDisbandPreparedUpload(wavBytes, "$baseName.wav", "application/octet-stream")
            }
        } finally {
            tmpMp3.delete()
        }
    }

    fun submitSeparation(
        baseUrl: String,
        apiKey: String,
        stems: List<String>,
        audioBytes: ByteArray,
        fileName: String,
        format: String = "wav",
        contentType: String = "application/octet-stream"
    ): DesktopDisbandJobResponse {
        require(stems.isNotEmpty()) { "At least one stem ID must be specified" }
        val cleanBase = baseUrl.trim().trimEnd('/')
        val query = (stems.map { "stems=${urlEncode(it)}" } +
            listOf("formats=${urlEncode(format)}", "name=${urlEncode(fileName)}"))
            .joinToString("&")
        val url = "$cleanBase/separations?$query"
        val reqHeaders = linkedMapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to contentType,
            "Accept" to "application/json"
        )
        val resp = executeWithDiagnostics(
            stage = "SUBMIT_SEPARATION",
            method = "POST",
            url = url,
            headers = reqHeaders,
            body = audioBytes,
            readTimeoutMs = 90_000
        )
        return parseJobOrThrow("SUBMIT_SEPARATION", "POST", url, reqHeaders, audioBytes, resp, stems, format, cleanBase)
    }

    fun reserveSeparationUpload(
        baseUrl: String,
        apiKey: String,
        stems: List<String>,
        byteSize: Int,
        fileName: String,
        format: String = "wav"
    ): DesktopDisbandJobResponse {
        require(stems.isNotEmpty()) { "At least one stem ID must be specified" }
        val cleanBase = baseUrl.trim().trimEnd('/')
        val query = (stems.map { "stems=${urlEncode(it)}" } +
            listOf("formats=${urlEncode(format)}", "name=${urlEncode(fileName)}", "bytes=$byteSize"))
            .joinToString("&")
        val url = "$cleanBase/separations?$query"
        val reqHeaders = linkedMapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json"
        )
        val resp = executeWithDiagnostics(
            stage = "RESERVE_UPLOAD",
            method = "POST",
            url = url,
            headers = reqHeaders,
            body = null,
            readTimeoutMs = 45_000
        )
        return parseJobOrThrow("RESERVE_UPLOAD", "POST", url, reqHeaders, null, resp, stems, format, cleanBase)
    }

    fun uploadToReservedUrl(
        uploadUrl: String,
        audioBytes: ByteArray,
        contentType: String = "application/octet-stream"
    ) {
        val reqHeaders = linkedMapOf("Content-Type" to contentType)
        val resp = executeWithDiagnostics(
            stage = "PUT_RESERVED_UPLOAD",
            method = "PUT",
            url = uploadUrl,
            headers = reqHeaders,
            body = audioBytes,
            readTimeoutMs = 120_000
        )
        if (resp.statusCode !in 200..299) {
            val fullBody = String(resp.body, Charsets.UTF_8)
            val summary = "Reserved upload PUT failed (HTTP ${resp.statusCode}): ${fullBody.ifBlank { resp.statusMessage }}"
            val diag = DesktopDisbandDiagnosticsFormatter.formatHttpExchange(
                stage = "PUT_RESERVED_UPLOAD",
                method = "PUT",
                url = uploadUrl,
                requestHeaders = reqHeaders,
                requestBody = audioBytes,
                response = resp,
                errorSummary = summary
            )
            throw DesktopDisbandApiException(resp.statusCode, summary, diag)
        }
    }

    fun pollSeparationStatus(
        baseUrl: String,
        apiKey: String,
        jobId: String,
        waitSeconds: Int = 20,
        expectedStems: List<String> = emptyList(),
        format: String = "wav"
    ): DesktopDisbandJobResponse {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val url = "$cleanBase/separations/${urlEncode(jobId)}"
        val reqHeaders = linkedMapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json"
        )
        if (waitSeconds > 0) reqHeaders["Prefer"] = "wait=$waitSeconds"
        val resp = executeWithDiagnostics(
            stage = "POLL_STATUS",
            method = "GET",
            url = url,
            headers = reqHeaders,
            body = null,
            readTimeoutMs = ((waitSeconds + 15) * 1000).coerceAtLeast(25_000)
        )
        return parseJobOrThrow("POLL_STATUS", "GET", url, reqHeaders, null, resp, expectedStems, format, cleanBase)
    }

    fun downloadStemAudio(
        baseUrl: String,
        apiKey: String,
        jobId: String,
        stemId: String,
        format: String = "wav",
        explicitUrl: String? = null
    ): ByteArray {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val target = explicitUrl?.takeIf { it.startsWith("http") }
            ?: "$cleanBase/separations/${urlEncode(jobId)}/stems/${urlEncode(stemId)}/${urlEncode(format)}"
        val reqHeaders = linkedMapOf("Authorization" to "Bearer $apiKey")
        val resp = executeWithDiagnostics(
            stage = "DOWNLOAD_STEM ($stemId)",
            method = "GET",
            url = target,
            headers = reqHeaders,
            body = null,
            readTimeoutMs = 65_000
        )
        if (resp.statusCode !in 200..299) {
            val fullBody = String(resp.body, Charsets.UTF_8)
            val summary = "Stem download ($stemId) failed (HTTP ${resp.statusCode}): ${fullBody.ifBlank { resp.statusMessage }}"
            val diag = DesktopDisbandDiagnosticsFormatter.formatHttpExchange(
                stage = "DOWNLOAD_STEM ($stemId)",
                method = "GET",
                url = target,
                requestHeaders = reqHeaders,
                requestBody = null,
                response = resp,
                errorSummary = summary
            )
            throw DesktopDisbandApiException(resp.statusCode, summary, diag)
        }
        return resp.body
    }

    fun separateAndDownloadStems(
        baseUrl: String,
        apiKey: String,
        stems: List<DesktopDisbandStem>,
        sourceBuffer: DesktopAudioBuffer,
        maxPollAttempts: Int = 45,
        waitPerPollSec: Int = 20,
        isCancelled: () -> Boolean = { false },
        onStatus: (jobId: String?, status: String, charge: Int?) -> Unit = { _, _, _ -> }
    ): Pair<DesktopDisbandJobResponse, List<DesktopSeparatedStem>> {
        if (isCancelled()) throw InterruptedException("Stem separation cancelled")
        val prepared = prepareUploadPayload(sourceBuffer)
        val stemIds = stems.map { it.apiId }

        var job = if (prepared.bytes.size > DIRECT_POST_LIMIT_BYTES) {
            submitViaReservedUpload(baseUrl, apiKey, stemIds, prepared, onStatus)
        } else {
            onStatus(null, "UPLOADING ${prepared.bytes.size / 1024}KB (${prepared.fileName.substringAfterLast('.').uppercase()})", null)
            try {
                submitSeparation(baseUrl, apiKey, stemIds, prepared.bytes, prepared.fileName, "wav", prepared.contentType)
            } catch (ex: DesktopDisbandApiException) {
                if (ex.httpCode != 413) throw ex
                val compact = if (!prepared.fileName.endsWith(".mp3", ignoreCase = true) || prepared.bytes.size > 4_000_000) {
                    prepareUploadPayload(sourceBuffer, preferCompressedMp3 = true, mp3BitrateKbps = 128)
                } else {
                    prepared
                }
                submitViaReservedUpload(baseUrl, apiKey, stemIds, compact, onStatus, prior413Exception = ex)
            }
        }
        onStatus(job.id, job.status.uppercase(), job.charge)

        var polls = 0
        while (!isTerminal(job.status) && polls < maxPollAttempts) {
            if (isCancelled()) throw InterruptedException("Stem separation cancelled")
            polls++
            val t0 = System.currentTimeMillis()
            job = pollSeparationStatus(baseUrl, apiKey, job.id, waitPerPollSec, stemIds, "wav")
            onStatus(job.id, job.status.uppercase(), job.charge)
            val elapsed = System.currentTimeMillis() - t0
            if (!isTerminal(job.status) && elapsed < 500L) {
                var slept = 0L
                while (slept < 600L) {
                    if (isCancelled()) throw InterruptedException("Stem separation cancelled")
                    Thread.sleep(100L)
                    slept += 100L
                }
            }
        }
        if (isCancelled()) throw InterruptedException("Stem separation cancelled")
        if (job.status.equals("failed", true) || job.status.equals("error", true)) {
            val summary = job.error ?: "Disband job ${job.id} failed (${job.status})"
            throw DesktopDisbandApiException(500, summary, job.rawDiagnostics ?: summary)
        }
        if (!job.status.equals("ready", true) && !job.status.equals("completed", true)) {
            val summary = "Disband job ${job.id} timed out (${job.status})"
            throw DesktopDisbandApiException(504, summary, job.rawDiagnostics ?: summary)
        }

        val charge = job.charge ?: 0
        val refs = if (job.stemRefs.isNotEmpty()) job.stemRefs else stemIds.map {
            DesktopDisbandStemRef(it, "wav", null, 0)
        }
        val out = mutableListOf<DesktopSeparatedStem>()
        for (ref in refs) {
            if (isCancelled()) throw InterruptedException("Stem separation cancelled")
            onStatus(job.id, "DOWNLOADING ${ref.stemId.uppercase()}", charge)
            val bytes = downloadStemAudio(baseUrl, apiKey, job.id, ref.stemId, ref.format, ref.url)
            val buf = runCatching {
                DesktopWavCodec.readWav(bytes, name = "${sourceBuffer.name}_${ref.stemId}")
            }.getOrElse { decodeErr ->
                val summary = "Decoded WAV error for stem '${ref.stemId}': ${decodeErr.message}"
                val textBody = String(bytes.copyOfRange(0, minOf(bytes.size, 8192)), Charsets.UTF_8)
                val diag = buildString {
                    appendLine("=== DISBAND STEM WAV DECODE ERROR ===")
                    appendLine("STEM: ${ref.stemId} (${bytes.size} bytes)")
                    appendLine("ERROR: ${decodeErr::class.java.name}: ${decodeErr.message}")
                    appendLine("RAW BYTES AS UTF-8 (first ${minOf(bytes.size, 8192)} bytes):")
                    appendLine(textBody)
                }
                throw DesktopDisbandApiException(500, summary, diag)
            }
            val displayLabel = DesktopDisbandStem.entries.firstOrNull { it.apiId == ref.stemId }?.label
                ?: ref.stemId.replace('_', ' ').uppercase()
            out += DesktopSeparatedStem(ref.stemId, displayLabel, ref.format, buf, bytes.size, charge)
        }
        return job to out
    }

    private fun submitViaReservedUpload(
        baseUrl: String,
        apiKey: String,
        stemIds: List<String>,
        payload: DesktopDisbandPreparedUpload,
        onStatus: (jobId: String?, status: String, charge: Int?) -> Unit,
        prior413Exception: DesktopDisbandApiException? = null
    ): DesktopDisbandJobResponse {
        onStatus(null, "RESERVING UPLOAD (${payload.bytes.size / 1024}KB)", null)
        val reserved = try {
            reserveSeparationUpload(baseUrl, apiKey, stemIds, payload.bytes.size, payload.fileName, "wav")
        } catch (reserveErr: DesktopDisbandApiException) {
            if (prior413Exception != null) {
                val combined = buildString {
                    appendLine(prior413Exception.fullDiagnostics)
                    appendLine()
                    appendLine("=== RESERVED UPLOAD FALLBACK ALSO FAILED ===")
                    appendLine(reserveErr.fullDiagnostics)
                }
                throw DesktopDisbandApiException(prior413Exception.httpCode, prior413Exception.message ?: "HTTP 413 Request Too Large", combined)
            }
            throw reserveErr
        }
        val uploadUrl = reserved.uploadUrl?.takeIf { it.startsWith("http") }
        if (uploadUrl == null) {
            if (reserved.id.isNotBlank() && reserved.status.isNotBlank() && !reserved.status.equals("uploading", ignoreCase = true)) {
                return reserved
            }
            if (prior413Exception != null) throw prior413Exception
            val msg = "Disband reserve response missing upload_url for ${payload.bytes.size} bytes"
            throw DesktopDisbandApiException(500, msg, reserved.rawDiagnostics ?: msg)
        }
        onStatus(reserved.id, "PUT ${payload.bytes.size / 1024}KB -> UPLOAD URL", reserved.charge)
        uploadToReservedUrl(uploadUrl, payload.bytes, payload.contentType)
        return reserved
    }

    private fun executeWithDiagnostics(
        stage: String,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        readTimeoutMs: Int
    ): DesktopHttpResponse {
        return try {
            transport.request(method, url, headers, body, readTimeoutMs)
        } catch (ie: InterruptedException) {
            throw ie
        } catch (t: Throwable) {
            if (t is DesktopDisbandApiException) throw t
            val summary = "Transport error at $stage: ${t.message ?: t::class.java.simpleName}"
            val diag = DesktopDisbandDiagnosticsFormatter.formatHttpExchange(
                stage = stage,
                method = method,
                url = url,
                requestHeaders = headers,
                requestBody = body,
                response = null,
                errorSummary = summary,
                cause = t
            )
            throw DesktopDisbandApiException(-1, summary, diag)
        }
    }

    private fun isTerminal(s: String): Boolean {
        val l = s.lowercase()
        return l == "ready" || l == "completed" || l == "failed" || l == "error"
    }

    private fun parseJobOrThrow(
        stage: String,
        method: String,
        url: String,
        reqHeaders: Map<String, String>,
        reqBody: ByteArray?,
        resp: DesktopHttpResponse,
        requestedStems: List<String>,
        format: String,
        baseUrl: String
    ): DesktopDisbandJobResponse {
        val text = String(resp.body, Charsets.UTF_8)
        if (resp.statusCode !in 200..299) {
            val map = runCatching { DesktopMiniJson.parseObject(text) }.getOrNull()
            val msg = (map?.get("error") as? String)
                ?: (map?.get("message") as? String)
                ?: text.ifBlank { "HTTP ${resp.statusCode}" }
            val hint = when (resp.statusCode) {
                401, 403 -> "Unauthorized — check DISBAND_API_KEY in Keys & Secrets"
                402 -> "Insufficient Disband credits"
                413 -> "Request Too Large (${reqBody?.size?.div(1024) ?: 0}KB) — enable Selection Only or check Disband tier limit"
                else -> "Disband API error (${resp.statusCode})"
            }
            val summary = "$hint: $msg"
            val diag = DesktopDisbandDiagnosticsFormatter.formatHttpExchange(
                stage = stage,
                method = method,
                url = url,
                requestHeaders = reqHeaders,
                requestBody = reqBody,
                response = resp,
                errorSummary = summary
            )
            throw DesktopDisbandApiException(resp.statusCode, summary, diag)
        }
        val json = runCatching { DesktopMiniJson.parseObject(text) }.getOrElse { parseErr ->
            val summary = "Invalid JSON from Disband (${resp.statusCode}): ${parseErr.message}"
            val diag = DesktopDisbandDiagnosticsFormatter.formatHttpExchange(
                stage = "$stage (JSON_PARSE)",
                method = method,
                url = url,
                requestHeaders = reqHeaders,
                requestBody = reqBody,
                response = resp,
                errorSummary = summary,
                cause = parseErr
            )
            throw DesktopDisbandApiException(resp.statusCode, summary, diag)
        }
        val id = (json["id"] as? String).orEmpty()
        val status = (json["status"] as? String).orEmpty().ifBlank { "queued" }
        val charge = (json["charge"] as? Number)?.toInt()
        val duration = (json["duration"] as? Number)?.toFloat()
        val error = json["error"] as? String
        val uploadUrl = json["upload_url"] as? String

        val rawDiag = DesktopDisbandDiagnosticsFormatter.formatHttpExchange(
            stage = "$stage (STATUS=$status)",
            method = method,
            url = url,
            requestHeaders = reqHeaders,
            requestBody = reqBody,
            response = resp,
            errorSummary = error ?: "Job $id status=$status"
        )

        val refs = mutableListOf<DesktopDisbandStemRef>()
        val stemsObj = json["stems"] as? Map<*, *>
        if (stemsObj != null) {
            stemsObj.forEach { (stemKey, fmtAny) ->
                val stemId = stemKey?.toString() ?: return@forEach
                val fmtMap = fmtAny as? Map<*, *> ?: return@forEach
                val fmtObj = (fmtMap[format] as? Map<*, *>) ?: fmtMap.values.firstOrNull() as? Map<*, *>
                val fmtKey = if (fmtMap[format] != null) format else fmtMap.keys.firstOrNull()?.toString() ?: format
                val urlRef = fmtObj?.get("url")?.toString()
                    ?: "$baseUrl/separations/${urlEncode(id)}/stems/${urlEncode(stemId)}/${urlEncode(fmtKey)}"
                val bytes = (fmtObj?.get("bytes") as? Number)?.toInt() ?: 0
                refs += DesktopDisbandStemRef(stemId, fmtKey, urlRef, bytes)
            }
        } else if (status.equals("ready", true) || status.equals("completed", true)) {
            requestedStems.forEach { stemId ->
                refs += DesktopDisbandStemRef(stemId, format, "$baseUrl/separations/${urlEncode(id)}/stems/${urlEncode(stemId)}/${urlEncode(format)}", 0)
            }
        }
        return DesktopDisbandJobResponse(id, status, charge, duration, error, refs, uploadUrl, rawDiag)
    }

    private fun urlEncode(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
}

private object DesktopMiniJson {
    fun parseObject(src: String): Map<String, Any?> {
        val p = Parser(src)
        p.skipWs()
        return p.readObject()
    }

    private class Parser(private val s: String) {
        private var idx = 0
        fun skipWs() { while (idx < s.length && s[idx].isWhitespace()) idx++ }

        private fun readValue(): Any? {
            skipWs()
            if (idx >= s.length) return null
            return when (val c = s[idx]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> if (s.startsWith("true", idx)) { idx += 4; true } else { idx += 5; false }
                'n' -> { idx += 4; null }
                '-', in '0'..'9' -> {
                    val st = idx
                    while (idx < s.length && (s[idx].isDigit() || s[idx] in "-+.eE")) idx++
                    val raw = s.substring(st, idx)
                    if (raw.contains('.') || raw.contains('e') || raw.contains('E')) raw.toDouble() else raw.toLong()
                }
                else -> throw IllegalArgumentException("Unexpected char '$c' at $idx")
            }
        }

        fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            expect('{'); skipWs()
            if (peek('}')) { idx++; return map }
            while (idx < s.length) {
                skipWs(); val k = readString(); skipWs(); expect(':'); map[k] = readValue(); skipWs()
                if (peek(',')) idx++ else if (peek('}')) { idx++; break } else break
            }
            return map
        }

        private fun readArray(): List<Any?> {
            val list = mutableListOf<Any?>()
            expect('['); skipWs()
            if (peek(']')) { idx++; return list }
            while (idx < s.length) {
                list += readValue(); skipWs()
                if (peek(',')) idx++ else if (peek(']')) { idx++; break } else break
            }
            return list
        }

        private fun readString(): String {
            expect('"'); val out = StringBuilder()
            while (idx < s.length) {
                val c = s[idx++]
                if (c == '"') return out.toString()
                if (c == '\\' && idx < s.length) {
                    when (val esc = s[idx++]) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b'); 'f' -> out.append('\u000C')
                        'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                        'u' -> if (idx + 4 <= s.length) { out.append(s.substring(idx, idx + 4).toInt(16).toChar()); idx += 4 }
                        else -> out.append(esc)
                    }
                } else out.append(c)
            }
            return out.toString()
        }

        private fun peek(c: Char): Boolean = idx < s.length && s[idx] == c
        private fun expect(c: Char) { if (idx >= s.length || s[idx] != c) throw IllegalArgumentException("Expected '$c' at $idx"); idx++ }
    }
}
