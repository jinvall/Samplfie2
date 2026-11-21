package com.samplfie.desktop.stems

import com.samplfie.desktop.audio.DesktopWavCodec
import com.samplfie.desktop.model.DesktopAudioBuffer
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
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

data class DesktopHttpResponse(val statusCode: Int, val body: ByteArray)

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
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
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
            val stream: InputStream? = if (code in 200..399) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return DesktopHttpResponse(code, bytes)
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
    val stemRefs: List<DesktopDisbandStemRef>
)

class DesktopDisbandClient(
    private val transport: DesktopDisbandHttpTransport = DesktopHttpUrlTransport()
) {
    fun submitSeparation(
        baseUrl: String,
        apiKey: String,
        stems: List<String>,
        audioBytes: ByteArray,
        fileName: String,
        format: String = "wav"
    ): DesktopDisbandJobResponse {
        require(stems.isNotEmpty()) { "At least one stem ID must be specified" }
        val cleanBase = baseUrl.trim().trimEnd('/')
        val query = (stems.map { "stems=${urlEncode(it)}" } +
            listOf("formats=${urlEncode(format)}", "name=${urlEncode(fileName)}"))
            .joinToString("&")
        val resp = transport.request(
            method = "POST",
            url = "$cleanBase/separations?$query",
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
    ): DesktopDisbandJobResponse {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val headers = linkedMapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json"
        )
        if (waitSeconds > 0) headers["Prefer"] = "wait=$waitSeconds"
        val resp = transport.request(
            method = "GET",
            url = "$cleanBase/separations/${urlEncode(jobId)}",
            headers = headers,
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
        val target = explicitUrl?.takeIf { it.startsWith("http") }
            ?: "$cleanBase/separations/${urlEncode(jobId)}/stems/${urlEncode(stemId)}/${urlEncode(format)}"
        val resp = transport.request("GET", target, mapOf("Authorization" to "Bearer $apiKey"))
        if (resp.statusCode !in 200..299) {
            throw RuntimeException("Stem download ($stemId) failed (${resp.statusCode})")
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
        onStatus: (jobId: String?, status: String, charge: Int?) -> Unit = { _, _, _ -> }
    ): Pair<DesktopDisbandJobResponse, List<DesktopSeparatedStem>> {
        val wavBytes = DesktopWavCodec.encodeToBytes(sourceBuffer)
        val stemIds = stems.map { it.apiId }
        val cleanName = (sourceBuffer.name.ifBlank { "samplfie2_mix" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")) + ".wav"

        onStatus(null, "UPLOADING ${wavBytes.size / 1024}KB", null)
        var job = submitSeparation(baseUrl, apiKey, stemIds, wavBytes, cleanName, "wav")
        onStatus(job.id, job.status.uppercase(), job.charge)

        var polls = 0
        while (!isTerminal(job.status) && polls < maxPollAttempts) {
            polls++
            job = pollSeparationStatus(baseUrl, apiKey, job.id, waitPerPollSec, stemIds, "wav")
            onStatus(job.id, job.status.uppercase(), job.charge)
        }
        if (job.status.equals("failed", true) || job.status.equals("error", true)) {
            throw RuntimeException(job.error ?: "Disband job ${job.id} failed")
        }
        if (!job.status.equals("ready", true) && !job.status.equals("completed", true)) {
            throw RuntimeException("Disband job ${job.id} timed out (${job.status})")
        }

        val charge = job.charge ?: 0
        val refs = if (job.stemRefs.isNotEmpty()) job.stemRefs else stemIds.map {
            DesktopDisbandStemRef(it, "wav", null, 0)
        }
        val out = mutableListOf<DesktopSeparatedStem>()
        for (ref in refs) {
            onStatus(job.id, "DOWNLOADING ${ref.stemId.uppercase()}", charge)
            val bytes = downloadStemAudio(baseUrl, apiKey, job.id, ref.stemId, ref.format, ref.url)
            val buf = DesktopWavCodec.readWav(bytes, name = "${sourceBuffer.name}_${ref.stemId}")
            val displayLabel = DesktopDisbandStem.entries.firstOrNull { it.apiId == ref.stemId }?.label
                ?: ref.stemId.replace('_', ' ').uppercase()
            out += DesktopSeparatedStem(ref.stemId, displayLabel, ref.format, buf, bytes.size, charge)
        }
        return job to out
    }

    private fun isTerminal(s: String): Boolean {
        val l = s.lowercase()
        return l == "ready" || l == "completed" || l == "failed" || l == "error"
    }

    private fun parseJobOrThrow(
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
                else -> "Disband API error (${resp.statusCode})"
            }
            throw RuntimeException("$hint: $msg")
        }
        val json = DesktopMiniJson.parseObject(text)
        val id = (json["id"] as? String).orEmpty()
        val status = (json["status"] as? String).orEmpty().ifBlank { "queued" }
        val charge = (json["charge"] as? Number)?.toInt()
        val duration = (json["duration"] as? Number)?.toFloat()
        val error = json["error"] as? String

        val refs = mutableListOf<DesktopDisbandStemRef>()
        val stemsObj = json["stems"] as? Map<*, *>
        if (stemsObj != null) {
            stemsObj.forEach { (stemKey, fmtAny) ->
                val stemId = stemKey?.toString() ?: return@forEach
                val fmtMap = fmtAny as? Map<*, *> ?: return@forEach
                val fmtObj = (fmtMap[format] as? Map<*, *>) ?: fmtMap.values.firstOrNull() as? Map<*, *>
                val fmtKey = if (fmtMap[format] != null) format else fmtMap.keys.firstOrNull()?.toString() ?: format
                val url = fmtObj?.get("url")?.toString()
                    ?: "$baseUrl/separations/${urlEncode(id)}/stems/${urlEncode(stemId)}/${urlEncode(fmtKey)}"
                val bytes = (fmtObj?.get("bytes") as? Number)?.toInt() ?: 0
                refs += DesktopDisbandStemRef(stemId, fmtKey, url, bytes)
            }
        } else if (status.equals("ready", true) || status.equals("completed", true)) {
            requestedStems.forEach { stemId ->
                refs += DesktopDisbandStemRef(stemId, format, "$baseUrl/separations/${urlEncode(id)}/stems/${urlEncode(stemId)}/${urlEncode(format)}", 0)
            }
        }
        return DesktopDisbandJobResponse(id, status, charge, duration, error, refs)
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
