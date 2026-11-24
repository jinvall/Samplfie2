package com.example.stems

import com.example.audio.AudioBuffer
import com.example.audio.WavCodec
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DisbandHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
    val headers: Map<String, List<String>> = emptyMap(),
    val statusMessage: String = ""
)

/**
 * Abstracted HTTP transport so production code uses HttpURLConnection and JVM tests can run
 * against either a loopback HttpServer or an in-memory fake transport.
 */
interface DisbandHttpTransport {
    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
        readTimeoutMs: Int = 65_000
    ): DisbandHttpResponse
}

class HttpUrlConnectionDisbandTransport : DisbandHttpTransport {
    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        readTimeoutMs: Int
    ): DisbandHttpResponse {
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
            if (body != null) {
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val statusMsg = runCatching { conn.responseMessage.orEmpty() }.getOrDefault("")
            val respHeaders = linkedMapOf<String, List<String>>()
            runCatching { conn.headerFields }.getOrNull()?.forEach { (k, v) ->
                val key = k ?: "Status-Line"
                respHeaders[key] = v ?: emptyList()
            }
            val stream: InputStream? = if (code in 200..399) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return DisbandHttpResponse(code, bytes, respHeaders, statusMsg)
        } finally {
            conn.disconnect()
        }
    }
}

data class DisbandStemFileRef(
    val stemId: String,
    val format: String,
    val url: String?,
    val bytes: Int
)

data class DisbandJobResponse(
    val id: String,
    val status: String,
    val charge: Int?,
    val durationSec: Float?,
    val error: String?,
    val stemRefs: List<DisbandStemFileRef>,
    val rawDiagnostics: String? = null
)

class DisbandApiException(
    val httpCode: Int,
    message: String,
    val fullDiagnostics: String = message
) : RuntimeException(message)

internal object DisbandDiagnosticsFormatter {
    fun formatHttpExchange(
        stage: String,
        method: String,
        url: String,
        requestHeaders: Map<String, String>,
        requestBody: ByteArray?,
        response: DisbandHttpResponse?,
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
            val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
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
                    appendLine("  Full payload (base64): ${encodeBase64(body)}")
                } else {
                    val tailStart = (body.size - 64).coerceAtLeast(0)
                    val tailBytes = body.copyOfRange(tailStart, body.size)
                    appendLine("  Last ${tailBytes.size} bytes (hex): ${tailBytes.joinToString(" ") { "%02X".format(it) }}")
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
                    appendLine("  Full payload (base64): ${encodeBase64(body)}")
                }
            }.trimEnd()
        }
    }

    private fun encodeBase64(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val hasB1 = i + 1 < data.size
            val hasB2 = i + 2 < data.size
            val b1 = if (hasB1) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (hasB2) data[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            out.append(alphabet[(triple ushr 18) and 0x3F])
            out.append(alphabet[(triple ushr 12) and 0x3F])
            out.append(if (hasB1) alphabet[(triple ushr 6) and 0x3F] else '=')
            out.append(if (hasB2) alphabet[triple and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }
}

/**
 * Pure Kotlin client for Disband Audio Stem Separation API (`https://api.disband.io/v1`).
 *
 * Workflow:
 * 1) `POST /separations?stems=...&formats=wav&name=...` with raw audio payload & Bearer key
 * 2) `GET /separations/{id}` with `Prefer: wait=20` until status == `ready`
 * 3) `GET /separations/{id}/stems/{stem}/{format}` (or explicit `stems[stem][format].url`)
 */
class DisbandApiClient(
    private val transport: DisbandHttpTransport = HttpUrlConnectionDisbandTransport()
) {

    fun submitSeparation(
        baseUrl: String,
        apiKey: String,
        stems: List<String>,
        audioBytes: ByteArray,
        fileName: String,
        format: String = "wav"
    ): DisbandJobResponse {
        require(stems.isNotEmpty()) { "At least one stem ID must be specified" }
        val cleanBase = baseUrl.trim().trimEnd('/')
        val queryParts = mutableListOf<String>()
        stems.forEach { stem ->
            queryParts += "stems=${urlEncode(stem)}"
        }
        queryParts += "formats=${urlEncode(format)}"
        queryParts += "name=${urlEncode(fileName)}"
        val url = "$cleanBase/separations?${queryParts.joinToString("&")}"
        val reqHeaders = linkedMapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/octet-stream",
            "Accept" to "application/json"
        )

        val resp = executeWithDiagnostics(
            stage = "SUBMIT_SEPARATION",
            method = "POST",
            url = url,
            headers = reqHeaders,
            body = audioBytes,
            readTimeoutMs = 65_000
        )
        return parseJobOrThrow(
            stage = "SUBMIT_SEPARATION",
            method = "POST",
            url = url,
            reqHeaders = reqHeaders,
            reqBody = audioBytes,
            resp = resp,
            requestedStems = stems,
            format = format,
            baseUrl = cleanBase
        )
    }

    fun pollSeparationStatus(
        baseUrl: String,
        apiKey: String,
        jobId: String,
        waitSeconds: Int = 20,
        expectedStems: List<String> = emptyList(),
        format: String = "wav"
    ): DisbandJobResponse {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val url = "$cleanBase/separations/${urlEncode(jobId)}"
        val reqHeaders = linkedMapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json"
        )
        if (waitSeconds > 0) {
            reqHeaders["Prefer"] = "wait=$waitSeconds"
        }
        val timeoutMs = ((waitSeconds + 15) * 1000).coerceAtLeast(25_000)
        val resp = executeWithDiagnostics(
            stage = "POLL_STATUS",
            method = "GET",
            url = url,
            headers = reqHeaders,
            body = null,
            readTimeoutMs = timeoutMs
        )
        return parseJobOrThrow(
            stage = "POLL_STATUS",
            method = "GET",
            url = url,
            reqHeaders = reqHeaders,
            reqBody = null,
            resp = resp,
            requestedStems = expectedStems,
            format = format,
            baseUrl = cleanBase
        )
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
        val targetUrl = explicitUrl?.takeIf { it.startsWith("http") }
            ?: "$cleanBase/separations/${urlEncode(jobId)}/stems/${urlEncode(stemId)}/${urlEncode(format)}"
        val reqHeaders = linkedMapOf("Authorization" to "Bearer $apiKey")
        val resp = executeWithDiagnostics(
            stage = "DOWNLOAD_STEM ($stemId)",
            method = "GET",
            url = targetUrl,
            headers = reqHeaders,
            body = null,
            readTimeoutMs = 65_000
        )
        if (resp.statusCode !in 200..299) {
            val fullBody = String(resp.body, Charsets.UTF_8)
            val summary = "Stem download ($stemId) failed (HTTP ${resp.statusCode}): ${fullBody.ifBlank { resp.statusMessage }}"
            val diag = DisbandDiagnosticsFormatter.formatHttpExchange(
                stage = "DOWNLOAD_STEM ($stemId)",
                method = "GET",
                url = targetUrl,
                requestHeaders = reqHeaders,
                requestBody = null,
                response = resp,
                errorSummary = summary
            )
            throw DisbandApiException(resp.statusCode, summary, diag)
        }
        return resp.body
    }

    fun separateAndDownloadStems(
        baseUrl: String,
        apiKey: String,
        stems: List<DisbandStem>,
        sourceBuffer: AudioBuffer,
        maxPollAttempts: Int = 45,
        waitPerPollSec: Int = 20,
        isCancelled: () -> Boolean = { false },
        onStatusUpdate: (jobId: String?, status: String, charge: Int?) -> Unit = { _, _, _ -> }
    ): Pair<DisbandJobResponse, List<SeparatedStemTrack>> {
        if (isCancelled()) throw InterruptedException("Stem separation cancelled")
        val wavBytes = WavCodec.encodeToBytes(sourceBuffer)
        val stemIds = stems.map { it.apiId }
        val cleanName = (sourceBuffer.name.ifBlank { "samplfie2_mix" }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")) + ".wav"

        onStatusUpdate(null, "UPLOADING ${wavBytes.size / 1024}KB", null)
        var job = submitSeparation(
            baseUrl = baseUrl,
            apiKey = apiKey,
            stems = stemIds,
            audioBytes = wavBytes,
            fileName = cleanName,
            format = "wav"
        )
        onStatusUpdate(job.id, job.status.uppercase(), job.charge)

        var polls = 0
        while (!isTerminalStatus(job.status) && polls < maxPollAttempts) {
            if (isCancelled()) throw InterruptedException("Stem separation cancelled")
            polls++
            val t0 = System.currentTimeMillis()
            job = pollSeparationStatus(
                baseUrl = baseUrl,
                apiKey = apiKey,
                jobId = job.id,
                waitSeconds = waitPerPollSec,
                expectedStems = stemIds,
                format = "wav"
            )
            onStatusUpdate(job.id, job.status.uppercase(), job.charge)
            val elapsed = System.currentTimeMillis() - t0
            if (!isTerminalStatus(job.status) && elapsed < 500L) {
                var slept = 0L
                while (slept < 600L) {
                    if (isCancelled()) throw InterruptedException("Stem separation cancelled")
                    Thread.sleep(100L)
                    slept += 100L
                }
            }
        }

        if (isCancelled()) throw InterruptedException("Stem separation cancelled")

        if (job.status.equals("failed", ignoreCase = true) || job.status.equals("error", ignoreCase = true)) {
            val summary = job.error ?: "Disband job ${job.id} failed (${job.status})"
            throw DisbandApiException(500, summary, job.rawDiagnostics ?: summary)
        }
        if (!job.status.equals("ready", ignoreCase = true) && !job.status.equals("completed", ignoreCase = true)) {
            val summary = "Disband job ${job.id} timed out (${job.status})"
            throw DisbandApiException(504, summary, job.rawDiagnostics ?: summary)
        }

        val charge = job.charge ?: 0
        val out = mutableListOf<SeparatedStemTrack>()
        val refs = if (job.stemRefs.isNotEmpty()) {
            job.stemRefs
        } else {
            stemIds.map { id -> DisbandStemFileRef(id, "wav", null, 0) }
        }

        for (ref in refs) {
            if (isCancelled()) throw InterruptedException("Stem separation cancelled")
            onStatusUpdate(job.id, "DOWNLOADING ${ref.stemId.uppercase()}", charge)
            val rawStemBytes = downloadStemAudio(
                baseUrl = baseUrl,
                apiKey = apiKey,
                jobId = job.id,
                stemId = ref.stemId,
                format = ref.format,
                explicitUrl = ref.url
            )
            val decoded = runCatching {
                WavCodec.readWav(rawStemBytes, name = "${sourceBuffer.name}_${ref.stemId}")
            }.getOrElse { decodeErr ->
                val summary = "Decoded WAV error for stem '${ref.stemId}': ${decodeErr.message}"
                val textBody = String(rawStemBytes.copyOfRange(0, minOf(rawStemBytes.size, 8192)), Charsets.UTF_8)
                val diag = buildString {
                    appendLine("=== DISBAND STEM WAV DECODE ERROR ===")
                    appendLine("STEM: ${ref.stemId} (${rawStemBytes.size} bytes)")
                    appendLine("ERROR: ${decodeErr::class.java.name}: ${decodeErr.message}")
                    appendLine("RAW BYTES AS UTF-8 (first ${minOf(rawStemBytes.size, 8192)} bytes):")
                    appendLine(textBody)
                }
                throw DisbandApiException(500, summary, diag)
            }
            val displayLabel = DisbandStem.entries.firstOrNull { it.apiId == ref.stemId }?.label
                ?: ref.stemId.replace('_', ' ').uppercase()
            out += SeparatedStemTrack(
                stemId = ref.stemId,
                label = displayLabel,
                format = ref.format,
                buffer = decoded,
                sizeBytes = rawStemBytes.size,
                creditCharge = charge
            )
        }
        return job to out
    }

    private fun executeWithDiagnostics(
        stage: String,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        readTimeoutMs: Int
    ): DisbandHttpResponse {
        return try {
            transport.request(
                method = method,
                url = url,
                headers = headers,
                body = body,
                readTimeoutMs = readTimeoutMs
            )
        } catch (ie: InterruptedException) {
            throw ie
        } catch (t: Throwable) {
            if (t is DisbandApiException) throw t
            val summary = "Transport error at $stage: ${t.message ?: t::class.java.simpleName}"
            val diag = DisbandDiagnosticsFormatter.formatHttpExchange(
                stage = stage,
                method = method,
                url = url,
                requestHeaders = headers,
                requestBody = body,
                response = null,
                errorSummary = summary,
                cause = t
            )
            throw DisbandApiException(-1, summary, diag)
        }
    }

    private fun isTerminalStatus(status: String): Boolean {
        val s = status.lowercase()
        return s == "ready" || s == "completed" || s == "failed" || s == "error"
    }

    private fun parseJobOrThrow(
        stage: String,
        method: String,
        url: String,
        reqHeaders: Map<String, String>,
        reqBody: ByteArray?,
        resp: DisbandHttpResponse,
        requestedStems: List<String>,
        format: String,
        baseUrl: String
    ): DisbandJobResponse {
        val text = String(resp.body, Charsets.UTF_8)
        if (resp.statusCode !in 200..299) {
            val root = runCatching { MiniJson.parseObject(text) }.getOrNull()
            val msg = (root?.get("error") as? String)
                ?: (root?.get("message") as? String)
                ?: text.ifBlank { "HTTP ${resp.statusCode}" }
            val hint = when (resp.statusCode) {
                401, 403 -> "Unauthorized — check DISBAND_API_KEY in Keys & Secrets"
                402 -> "Insufficient Disband credits"
                else -> "Disband API error (${resp.statusCode})"
            }
            val summary = "$hint: $msg"
            val diag = DisbandDiagnosticsFormatter.formatHttpExchange(
                stage = stage,
                method = method,
                url = url,
                requestHeaders = reqHeaders,
                requestBody = reqBody,
                response = resp,
                errorSummary = summary
            )
            throw DisbandApiException(resp.statusCode, summary, diag)
        }

        val json = runCatching { MiniJson.parseObject(text) }.getOrElse { parseErr ->
            val summary = "Invalid JSON from Disband (${resp.statusCode}): ${parseErr.message}"
            val diag = DisbandDiagnosticsFormatter.formatHttpExchange(
                stage = "$stage (JSON_PARSE)",
                method = method,
                url = url,
                requestHeaders = reqHeaders,
                requestBody = reqBody,
                response = resp,
                errorSummary = summary,
                cause = parseErr
            )
            throw DisbandApiException(resp.statusCode, summary, diag)
        }
        val id = (json["id"] as? String).orEmpty()
        val status = (json["status"] as? String).orEmpty().ifBlank { "queued" }
        val charge = (json["charge"] as? Number)?.toInt()
        val duration = (json["duration"] as? Number)?.toFloat()
        val error = json["error"] as? String

        val rawDiag = DisbandDiagnosticsFormatter.formatHttpExchange(
            stage = "$stage (STATUS=$status)",
            method = method,
            url = url,
            requestHeaders = reqHeaders,
            requestBody = reqBody,
            response = resp,
            errorSummary = error ?: "Job $id status=$status"
        )

        val stemRefs = mutableListOf<DisbandStemFileRef>()
        val stemsObj = json["stems"] as? Map<*, *>
        if (stemsObj != null) {
            stemsObj.forEach { (stemKey, formatMapAny) ->
                val stemId = stemKey?.toString() ?: return@forEach
                val formatMap = formatMapAny as? Map<*, *> ?: return@forEach
                val fmtObj = (formatMap[format] as? Map<*, *>)
                    ?: formatMap.values.firstOrNull() as? Map<*, *>
                val fmtKey = if (formatMap[format] != null) format else formatMap.keys.firstOrNull()?.toString() ?: format
                val urlRef = fmtObj?.get("url")?.toString()
                    ?: "$baseUrl/separations/${urlEncode(id)}/stems/${urlEncode(stemId)}/${urlEncode(fmtKey)}"
                val bytes = (fmtObj?.get("bytes") as? Number)?.toInt() ?: 0
                stemRefs += DisbandStemFileRef(stemId, fmtKey, urlRef, bytes)
            }
        } else if (status.equals("ready", ignoreCase = true) || status.equals("completed", ignoreCase = true)) {
            requestedStems.forEach { stemId ->
                stemRefs += DisbandStemFileRef(
                    stemId = stemId,
                    format = format,
                    url = "$baseUrl/separations/${urlEncode(id)}/stems/${urlEncode(stemId)}/${urlEncode(format)}",
                    bytes = 0
                )
            }
        }

        return DisbandJobResponse(
            id = id,
            status = status,
            charge = charge,
            durationSec = duration,
            error = error,
            stemRefs = stemRefs,
            rawDiagnostics = rawDiag
        )
    }

    private fun urlEncode(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
}

/**
 * Lightweight zero-dependency RFC-8259 JSON parser used identically on Android and Linux Desktop.
 */
object MiniJson {
    fun parseObject(src: String): Map<String, Any?> {
        val p = Parser(src)
        p.skipWs()
        return p.readObject()
    }

    private class Parser(private val s: String) {
        private var idx = 0

        fun skipWs() {
            while (idx < s.length && s[idx].isWhitespace()) idx++
        }

        private fun readValue(): Any? {
            skipWs()
            if (idx >= s.length) return null
            return when (val c = s[idx]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("Unexpected JSON char '$c' at $idx")
            }
        }

        fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            expect('{')
            skipWs()
            if (peek('}')) {
                idx++
                return map
            }
            while (idx < s.length) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                val v = readValue()
                map[key] = v
                skipWs()
                if (peek(',')) {
                    idx++
                } else if (peek('}')) {
                    idx++
                    break
                } else {
                    break
                }
            }
            return map
        }

        private fun readArray(): List<Any?> {
            val list = mutableListOf<Any?>()
            expect('[')
            skipWs()
            if (peek(']')) {
                idx++
                return list
            }
            while (idx < s.length) {
                list += readValue()
                skipWs()
                if (peek(',')) {
                    idx++
                } else if (peek(']')) {
                    idx++
                    break
                } else {
                    break
                }
            }
            return list
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (idx < s.length) {
                val c = s[idx++]
                if (c == '"') return out.toString()
                if (c == '\\' && idx < s.length) {
                    when (val esc = s[idx++]) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (idx + 4 <= s.length) {
                                val code = s.substring(idx, idx + 4).toInt(16)
                                out.append(code.toChar())
                                idx += 4
                            }
                        }
                        else -> out.append(esc)
                    }
                } else {
                    out.append(c)
                }
            }
            return out.toString()
        }

        private fun readNumber(): Number {
            val start = idx
            while (idx < s.length && (s[idx].isDigit() || s[idx] in "-+.eE")) idx++
            val raw = s.substring(start, idx)
            return if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
                raw.toDouble()
            } else {
                raw.toLong()
            }
        }

        private fun readBoolean(): Boolean {
            return if (s.startsWith("true", idx)) {
                idx += 4; true
            } else {
                idx += 5; false
            }
        }

        private fun readNull(): Any? {
            idx += 4
            return null
        }

        private fun peek(c: Char): Boolean = idx < s.length && s[idx] == c

        private fun expect(c: Char) {
            if (idx >= s.length || s[idx] != c) {
                throw IllegalArgumentException("Expected '$c' at $idx")
            }
            idx++
        }
    }
}
