package com.example.stems

import com.example.audio.AudioBuffer
import com.example.audio.WavCodec
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

data class DisbandHttpResponse(
    val statusCode: Int,
    val body: ByteArray
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
            val stream: InputStream? = if (code in 200..399) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return DisbandHttpResponse(code, bytes)
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
    val stemRefs: List<DisbandStemFileRef>
)

class DisbandApiException(val httpCode: Int, message: String) : RuntimeException(message)

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

        val resp = transport.request(
            method = "POST",
            url = url,
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/octet-stream",
                "Accept" to "application/json"
            ),
            body = audioBytes
        )
        return parseJobOrThrow(resp, stems, format, cleanBase)
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
        val headers = mutableMapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json"
        )
        if (waitSeconds > 0) {
            headers["Prefer"] = "wait=$waitSeconds"
        }
        val resp = transport.request(
            method = "GET",
            url = url,
            headers = headers,
            body = null,
            readTimeoutMs = ((waitSeconds + 15) * 1000).coerceAtLeast(25_000)
        )
        return parseJobOrThrow(resp, expectedStems, format, cleanBase)
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
        val resp = transport.request(
            method = "GET",
            url = targetUrl,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = null
        )
        if (resp.statusCode !in 200..299) {
            val errDetail = String(resp.body, Charsets.UTF_8).take(180)
            throw DisbandApiException(resp.statusCode, "Stem download ($stemId) failed (${resp.statusCode}): $errDetail")
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
            throw DisbandApiException(500, job.error ?: "Disband job ${job.id} failed")
        }
        if (!job.status.equals("ready", ignoreCase = true) && !job.status.equals("completed", ignoreCase = true)) {
            throw DisbandApiException(504, "Disband job ${job.id} timed out (${job.status})")
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
            val decoded = WavCodec.readWav(rawStemBytes, name = "${sourceBuffer.name}_${ref.stemId}")
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

    private fun isTerminalStatus(status: String): Boolean {
        val s = status.lowercase()
        return s == "ready" || s == "completed" || s == "failed" || s == "error"
    }

    private fun parseJobOrThrow(
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
            throw DisbandApiException(resp.statusCode, "$hint: $msg")
        }

        val json = MiniJson.parseObject(text)
        val id = (json["id"] as? String).orEmpty()
        val status = (json["status"] as? String).orEmpty().ifBlank { "queued" }
        val charge = (json["charge"] as? Number)?.toInt()
        val duration = (json["duration"] as? Number)?.toFloat()
        val error = json["error"] as? String

        val stemRefs = mutableListOf<DisbandStemFileRef>()
        val stemsObj = json["stems"] as? Map<*, *>
        if (stemsObj != null) {
            stemsObj.forEach { (stemKey, formatMapAny) ->
                val stemId = stemKey?.toString() ?: return@forEach
                val formatMap = formatMapAny as? Map<*, *> ?: return@forEach
                val fmtObj = (formatMap[format] as? Map<*, *>)
                    ?: formatMap.values.firstOrNull() as? Map<*, *>
                val fmtKey = if (formatMap[format] != null) format else formatMap.keys.firstOrNull()?.toString() ?: format
                val url = fmtObj?.get("url")?.toString()
                    ?: "$baseUrl/separations/${urlEncode(id)}/stems/${urlEncode(stemId)}/${urlEncode(fmtKey)}"
                val bytes = (fmtObj?.get("bytes") as? Number)?.toInt() ?: 0
                stemRefs += DisbandStemFileRef(stemId, fmtKey, url, bytes)
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
            stemRefs = stemRefs
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
