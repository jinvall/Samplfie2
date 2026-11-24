package com.example.stems

import com.example.audio.AudioBuffer
import com.example.audio.AudioSampleFormat
import com.example.audio.WavCodec
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DisbandErrorPayloadTest {

    private fun testTone(): AudioBuffer {
        val sr = 44100
        val n = sr / 10
        return AudioBuffer(FloatArray(n) { i -> (0.4f * sin(2.0 * PI * 220.0 * i / sr)).toFloat() }, sr, 1, "err_test_mix")
    }

    @Test
    fun `submitSeparation error preserves full request headers, WAV payload header, response headers, and untruncated response body`() {
        val tailMarker = "END_OF_LONG_DISBAND_VALIDATION_PAYLOAD_MARKER_999"
        val longBody = """{"error":"Invalid stem parameters","code":"ERR_STEM_SPEC","details":{"padding":"${"x".repeat(450)}"},"marker":"$tailMarker"}"""

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/separations") { ex ->
            ex.requestBody.readBytes()
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.responseHeaders.add("X-Disband-Trace", "trace_android_422_abc")
            ex.responseHeaders.add("X-RateLimit-Remaining", "17")
            val b = longBody.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(422, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val port = server.address.port
            val client = DisbandApiClient()
            try {
                client.separateAndDownloadStems(
                    baseUrl = "http://127.0.0.1:$port/v1",
                    apiKey = "secret_debug_key_xyz",
                    stems = listOf(DisbandStem.VOCALS, DisbandStem.DRUMS),
                    sourceBuffer = testTone()
                )
                fail("Expected DisbandApiException")
            } catch (ex: DisbandApiException) {
                assertEquals(422, ex.httpCode)
                val diag = ex.fullDiagnostics
                // Request details + headers + WAV payload header
                assertTrue(diag.contains("POST http://127.0.0.1:$port/v1/separations?"))
                assertTrue(diag.contains("stems=vocals"))
                assertTrue(diag.contains("stems=drums"))
                assertTrue(diag.contains("Authorization: Bearer secret_debug_key_xyz"))
                assertTrue(diag.contains("Content-Type: application/octet-stream"))
                assertTrue(diag.contains("Accept: application/json"))
                assertTrue(diag.contains("Binary WAV/RIFF"))
                assertTrue(diag.contains("sampleRate=44100Hz"))
                // Response headers + ENTIRE response body without truncation
                assertTrue(diag.contains("HTTP 422"))
                assertTrue(diag.contains("X-Disband-Trace: trace_android_422_abc") || diag.contains("X-disband-trace: trace_android_422_abc"))
                assertTrue(diag.contains("X-RateLimit-Remaining: 17") || diag.contains("X-ratelimit-remaining: 17"))
                assertTrue("Full response payload must not be truncated", diag.contains(longBody))
                assertTrue(diag.contains(tailMarker))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `downloadStemAudio error preserves full untruncated payload beyond 180 chars and response headers`() {
        val longDownloadErr = "DOWNLOAD_DENIED_" + "Z".repeat(320) + "_FINAL_BYTE_MARKER"
        val client = DisbandApiClient(object : DisbandHttpTransport {
            override fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: ByteArray?,
                readTimeoutMs: Int
            ): DisbandHttpResponse {
                return DisbandHttpResponse(
                    statusCode = 403,
                    body = longDownloadErr.toByteArray(Charsets.UTF_8),
                    headers = mapOf(
                        "X-Storage-Edge" to listOf("us-west-2b"),
                        "Retry-After" to listOf("30")
                    ),
                    statusMessage = "Forbidden"
                )
            }
        })
        try {
            client.downloadStemAudio("https://api.disband.io/v1", "k_dl_99", "job_1", "vocals")
            fail("Expected DisbandApiException")
        } catch (ex: DisbandApiException) {
            assertEquals(403, ex.httpCode)
            assertTrue(ex.fullDiagnostics.contains("Authorization: Bearer k_dl_99"))
            assertTrue(ex.fullDiagnostics.contains("X-Storage-Edge: us-west-2b"))
            assertTrue(ex.fullDiagnostics.contains("Retry-After: 30"))
            assertTrue(ex.fullDiagnostics.contains("_FINAL_BYTE_MARKER"))
        }
    }

    @Test
    fun `prepareUploadPayload compacts large stereo PCM16 mix to smaller MP3 payload`() {
        val sr = 44100
        val seconds = 20 // Stereo 44.1kHz PCM16 WAV ~ 3.53 MB (> 2.5 MB threshold)
        val totalSamples = sr * 2 * seconds
        val samples = FloatArray(totalSamples) { idx ->
            (0.35f * sin(2.0 * PI * 220.0 * (idx / 2) / sr)).toFloat()
        }
        val largeMix = AudioBuffer(samples, sr, 2, "long_studio_mix")
        val client = DisbandApiClient()

        val prepared = client.prepareUploadPayload(largeMix)
        assertTrue("Expected MP3 extension, got ${prepared.fileName}", prepared.fileName.endsWith(".mp3"))
        assertEquals("audio/mpeg", prepared.contentType)
        assertTrue(
            "Expected MP3 (${prepared.bytes.size} bytes) smaller than raw WAV (${totalSamples * 2} bytes)",
            prepared.bytes.size < totalSamples
        )
    }

    @Test
    fun `separateAndDownloadStems automatically recovers from HTTP 413 via 128kbps MP3 and Reserved Upload`() {
        val sr = 44100
        val toneSamples = FloatArray(sr / 4) { i -> (0.4f * sin(2.0 * PI * 330.0 * i / sr)).toFloat() }
        val source = AudioBuffer(toneSamples, sr, 1, "clip_413_test")
        val stemWav = WavCodec.encodeToBytes(source, AudioSampleFormat.PCM_16, dither = false)

        var directPostCount = 0
        var reservePostCount = 0
        var reservedPutCount = 0
        var reservedPutHadAuthHeader = false
        var reservedPutContentType = ""
        var reservedBytesQuery = 0

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/v1/separations") { ex ->
            val path = ex.requestURI.path
            val query = ex.requestURI.rawQuery.orEmpty()
            val reqBytes = ex.requestBody.readBytes()
            when {
                ex.requestMethod == "POST" && path == "/v1/separations" && !query.contains("bytes=") -> {
                    directPostCount++
                    val err = """{"error":"Payload Too Large for direct POST"}""".toByteArray(Charsets.UTF_8)
                    ex.sendResponseHeaders(413, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                }
                ex.requestMethod == "POST" && path == "/v1/separations" && query.contains("bytes=") -> {
                    reservePostCount++
                    reservedBytesQuery = Regex("bytes=(\\d+)").find(query)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val json = """{"id":"sep_413_rec","status":"uploading","upload_url":"http://127.0.0.1:$port/upload/sep_413_rec"}"""
                    val b = json.toByteArray(Charsets.UTF_8)
                    ex.sendResponseHeaders(202, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                }
                ex.requestMethod == "GET" && path == "/v1/separations/sep_413_rec" -> {
                    val json = """{"id":"sep_413_rec","status":"ready","charge":8,"duration":0.25,"stems":{"vocals":{"wav":{"url":"http://127.0.0.1:$port/v1/separations/sep_413_rec/stems/vocals/wav","bytes":${stemWav.size}}}}}"""
                    val b = json.toByteArray(Charsets.UTF_8)
                    ex.sendResponseHeaders(200, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                }
                ex.requestMethod == "GET" && path.endsWith("/stems/vocals/wav") -> {
                    ex.sendResponseHeaders(200, stemWav.size.toLong())
                    ex.responseBody.use { it.write(stemWav) }
                }
                else -> {
                    ex.sendResponseHeaders(404, 0)
                    ex.responseBody.close()
                }
            }
        }
        server.createContext("/upload/sep_413_rec") { ex ->
            reservedPutCount++
            reservedPutHadAuthHeader = ex.requestHeaders.containsKey("Authorization")
            reservedPutContentType = ex.requestHeaders.getFirst("Content-Type").orEmpty()
            ex.requestBody.readBytes()
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        try {
            val client = DisbandApiClient()
            val (job, tracks) = client.separateAndDownloadStems(
                baseUrl = "http://127.0.0.1:$port/v1",
                apiKey = "key_413_recovery",
                stems = listOf(DisbandStem.VOCALS),
                sourceBuffer = source
            )
            assertEquals("sep_413_rec", job.id)
            assertEquals(1, directPostCount)
            assertEquals(1, reservePostCount)
            assertEquals(1, reservedPutCount)
            assertTrue("Reserved upload must reserve > 0 bytes", reservedBytesQuery > 0)
            assertEquals("audio/mpeg", reservedPutContentType)
            assertTrue("Reserved signed PUT must NOT send Authorization header", !reservedPutHadAuthHeader)
            assertEquals(1, tracks.size)
            assertEquals("vocals", tracks.first().stemId)
        } finally {
            server.stop(0)
        }
    }
}

