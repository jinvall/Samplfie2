package com.samplfie.desktop

import com.samplfie.desktop.audio.DesktopWavCodec
import com.samplfie.desktop.audio.SignalAnalyzer
import com.samplfie.desktop.model.SignalWaveformType
import com.samplfie.desktop.stems.DesktopDisbandStem
import com.samplfie.desktop.stems.DesktopSecretsVault
import com.samplfie.desktop.stems.DesktopStemPreset
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopStemsAndSecretsTest {

    @Test
    fun desktopSecretsVault_persistsMaskedSecretsToDiskAndReloads() {
        val tempFile = File.createTempFile("samplfie_vault_", ".properties").apply { delete() }
        try {
            val vault = DesktopSecretsVault(tempFile)
            vault.putSecret(DesktopSecretsVault.KEY_DISBAND_API_KEY, "disband_live_secret_9876")
            vault.putSecret("custom_token", "tok_abcdef1234")

            assertTrue(vault.state.value.hasDisbandKey)
            assertEquals("disband_live_secret_9876", vault.resolveDisbandApiKey())
            val entry = vault.state.value.entries.first { it.keyName == DesktopSecretsVault.KEY_DISBAND_API_KEY }
            assertFalse(entry.maskedValue.contains("secret_9876"))
            assertTrue(entry.maskedValue.endsWith("9876"))

            val reloaded = DesktopSecretsVault(tempFile)
            assertEquals("disband_live_secret_9876", reloaded.resolveDisbandApiKey())
            assertEquals("tok_abcdef1234", reloaded.getSecret("CUSTOM_TOKEN"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun desktopController_allStemsConfirmationAndLoopbackHttpSeparation() {
        val sampleWav = DesktopWavCodec.encodeToBytes(
            SignalAnalyzer.generateTestSignal(SignalWaveformType.SINE, 330.0, 0.25f, 44100, 1)
        )
        val recordedQueries = CopyOnWriteArrayList<String>()
        val authHeaders = CopyOnWriteArrayList<String>()
        var pollCount = 0

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/v1/separations") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
            authHeaders += auth
            val path = ex.requestURI.path
            val query = ex.requestURI.rawQuery.orEmpty()
            when {
                ex.requestMethod == "POST" && path == "/v1/separations" -> {
                    ex.requestBody.readBytes()
                    recordedQueries += query
                    val json = """{"id":"sep_test_1","status":"queued","charge":12}"""
                    val b = json.toByteArray()
                    ex.sendResponseHeaders(202, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                }
                ex.requestMethod == "GET" && path == "/v1/separations/sep_test_1" -> {
                    pollCount++
                    val stemsList = queryOrLastStems(recordedQueries.lastOrNull().orEmpty())
                    val stemsJson = stemsList.joinToString(",") { stem ->
                        """"$stem":{"wav":{"url":"http://127.0.0.1:$port/v1/separations/sep_test_1/stems/$stem/wav","bytes":${sampleWav.size}}}"""
                    }
                    val json = """{"id":"sep_test_1","status":"ready","charge":${stemsList.size * 10},"duration":0.25,"stems":{$stemsJson}}"""
                    val b = json.toByteArray()
                    ex.sendResponseHeaders(200, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                }
                ex.requestMethod == "GET" && path.contains("/stems/") -> {
                    ex.sendResponseHeaders(200, sampleWav.size.toLong())
                    ex.responseBody.use { it.write(sampleWav) }
                }
                else -> {
                    ex.sendResponseHeaders(404, 0)
                    ex.responseBody.close()
                }
            }
        }
        server.start()

        val controller = DesktopStudioController()
        try {
            controller.saveSecretKey(DesktopSecretsVault.KEY_DISBAND_API_KEY, "test_disband_key_1234")
            controller.saveSecretKey(DesktopSecretsVault.KEY_DISBAND_BASE_URL, "http://127.0.0.1:$port/v1")

            // 1) Unspecified stem set -> must NOT call API directly; must raise All-Stems confirmation + toast
            controller.applyStemPreset(DesktopStemPreset.UNSPECIFIED_ALL)
            val startedImmediately = controller.requestStemSeparation()
            assertFalse(startedImmediately)
            assertTrue(controller.stemState.value.showAllStemsConfirmation)
            assertTrue(recordedQueries.isEmpty())
            assertNotNull(controller.stemState.value.lastToastMessage)

            // Confirming All Stems runs all 6 core stems over HTTP
            controller.confirmSeparateAllStems()
            val deadline1 = System.currentTimeMillis() + 5000
            while (controller.stemState.value.isRunning && System.currentTimeMillis() < deadline1) {
                Thread.sleep(25)
            }
            assertFalse(controller.stemState.value.isRunning)
            assertEquals("READY", controller.stemState.value.jobStatusLabel)
            assertEquals(6, controller.stemState.value.separatedStems.size)
            val firstQuery = recordedQueries.first()
            DesktopDisbandStem.ALL_STEMS_DEFAULT.forEach { stem ->
                assertTrue("Expected ${stem.apiId} in query: $firstQuery", firstQuery.contains("stems=${stem.apiId}"))
            }
            assertTrue(authHeaders.all { it == "Bearer test_disband_key_1234" })

            // 2) Specific stem selection (Vocals + Drums) bypasses confirmation box
            controller.applyStemPreset(DesktopStemPreset.VOCALS_DRUMS)
            val startedSpecific = controller.requestStemSeparation()
            assertTrue(startedSpecific)
            assertFalse(controller.stemState.value.showAllStemsConfirmation)
            val deadline2 = System.currentTimeMillis() + 5000
            while (controller.stemState.value.isRunning && System.currentTimeMillis() < deadline2) {
                Thread.sleep(25)
            }
            assertEquals(2, controller.stemState.value.separatedStems.size)
            val secondQuery = recordedQueries.last()
            assertTrue(secondQuery.contains("stems=vocals"))
            assertTrue(secondQuery.contains("stems=drums"))
            assertFalse(secondQuery.contains("stems=guitar"))

            // 3) Loading separated stem to deck while loop playback is active keeps looping
            controller.soundEngine.setLooping(true)
            controller.soundEngine.startPlayback()
            assertTrue(controller.playbackState.value.isPlaying)
            controller.loadSeparatedStemToDeck("vocals")
            assertTrue(controller.playbackState.value.isPlaying)
            assertTrue(controller.playbackState.value.isLooping)

            // 4) Routing all separated stems to tracks
            controller.sendAllSeparatedStemsToTimeline()
            assertTrue(controller.timelineState.value.tracks[0].clips.isNotEmpty())
            assertTrue(controller.timelineState.value.tracks[1].clips.isNotEmpty())
        } finally {
            controller.shutdown()
            server.stop(0)
        }
    }

    @Test
    fun desktopController_errorCapturesFullRequestAndResponsePayloadWithHeaders() {
        val tailMarker = "END_OF_LONG_DESKTOP_ERROR_PAYLOAD_777"
        val longBody = """{"error":"Rejected wav payload","reason":"quota_or_format","pad":"${"y".repeat(450)}","tail":"$tailMarker"}"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/separations") { ex ->
            ex.requestBody.readBytes()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.responseHeaders.add("X-Disband-Debug-Id", "dbg_desktop_500")
            ex.responseHeaders.add("X-Upstream-Node", "worker-09")
            val b = longBody.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(400, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
        val port = server.address.port
        val controller = DesktopStudioController()
        try {
            controller.saveSecretKey(DesktopSecretsVault.KEY_DISBAND_API_KEY, "desk_err_key_555")
            controller.saveSecretKey(DesktopSecretsVault.KEY_DISBAND_BASE_URL, "http://127.0.0.1:$port/v1")
            controller.applyStemPreset(DesktopStemPreset.VOCALS_ONLY)
            assertTrue(controller.requestStemSeparation())

            val deadline = System.currentTimeMillis() + 5000
            while (controller.stemState.value.isRunning && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
            }
            assertFalse(controller.stemState.value.isRunning)
            assertEquals("ERROR", controller.stemState.value.jobStatusLabel)
            val detail = controller.stemState.value.errorPayloadDetail
            assertNotNull("Expected full errorPayloadDetail on DesktopStemState", detail)
            detail!!
            assertTrue(detail.contains("POST http://127.0.0.1:$port/v1/separations?"))
            assertTrue(detail.contains("Authorization: Bearer desk_err_key_555"))
            assertTrue(detail.contains("Content-Type: application/octet-stream"))
            assertTrue(detail.contains("Binary WAV/RIFF"))
            assertTrue(detail.contains("HTTP 400"))
            assertTrue(detail.contains("dbg_desktop_500"))
            assertTrue(detail.contains("worker-09"))
            assertTrue("Must contain entire error body", detail.contains(longBody))
            assertTrue(detail.contains(tailMarker))
        } finally {
            controller.shutdown()
            server.stop(0)
        }
    }

    @Test
    fun desktopDisbandClient_compactsLargeWavToMp3AndRecoversFromHttp413ViaReservedUpload() {
        val sr = 44100
        val seconds = 20 // Stereo PCM16 WAV ~ 3.53 MB (> 2.5 MB threshold)
        val largeSamples = FloatArray(sr * 2 * seconds) { idx ->
            (0.35f * kotlin.math.sin(2.0 * kotlin.math.PI * 220.0 * (idx / 2) / sr)).toFloat()
        }
        val largeMix = com.samplfie.desktop.model.DesktopAudioBuffer(largeSamples, sr, 2, "desktop_long_mix")
        val client = com.samplfie.desktop.stems.DesktopDisbandClient()
        val prepared = client.prepareUploadPayload(largeMix)
        assertTrue("Expected MP3 compaction, got ${prepared.fileName}", prepared.fileName.endsWith(".mp3"))
        assertEquals("audio/mpeg", prepared.contentType)
        assertTrue(prepared.bytes.size < largeSamples.size)

        // Verify DesktopStudioController automatically falls back from HTTP 413 to Reserved Upload
        val sampleWav = DesktopWavCodec.encodeToBytes(
            SignalAnalyzer.generateTestSignal(SignalWaveformType.SINE, 440.0, 0.25f, 44100, 1)
        )
        var directPostCount = 0
        var reserveCount = 0
        var putCount = 0
        var putHadAuth = false
        var putContentType = ""

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/v1/separations") { ex ->
            val path = ex.requestURI.path
            val query = ex.requestURI.rawQuery.orEmpty()
            ex.requestBody.readBytes()
            when {
                ex.requestMethod == "POST" && path == "/v1/separations" && !query.contains("bytes=") -> {
                    directPostCount++
                    val err = """{"error":"Request entity too large"}""".toByteArray()
                    ex.sendResponseHeaders(413, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                }
                ex.requestMethod == "POST" && path == "/v1/separations" && query.contains("bytes=") -> {
                    reserveCount++
                    val json = """{"id":"desk_413_ok","status":"uploading","upload_url":"http://127.0.0.1:$port/upload/desk_413_ok"}"""
                    val b = json.toByteArray()
                    ex.sendResponseHeaders(202, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                }
                ex.requestMethod == "GET" && path == "/v1/separations/desk_413_ok" -> {
                    val json = """{"id":"desk_413_ok","status":"ready","charge":10,"duration":0.25,"stems":{"vocals":{"wav":{"url":"http://127.0.0.1:$port/v1/separations/desk_413_ok/stems/vocals/wav","bytes":${sampleWav.size}}}}}"""
                    val b = json.toByteArray()
                    ex.sendResponseHeaders(200, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                }
                ex.requestMethod == "GET" && path.endsWith("/stems/vocals/wav") -> {
                    ex.sendResponseHeaders(200, sampleWav.size.toLong())
                    ex.responseBody.use { it.write(sampleWav) }
                }
                else -> {
                    ex.sendResponseHeaders(404, 0)
                    ex.responseBody.close()
                }
            }
        }
        server.createContext("/upload/desk_413_ok") { ex ->
            putCount++
            putHadAuth = ex.requestHeaders.containsKey("Authorization")
            putContentType = ex.requestHeaders.getFirst("Content-Type").orEmpty()
            ex.requestBody.readBytes()
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        val controller = DesktopStudioController()
        try {
            controller.saveSecretKey(DesktopSecretsVault.KEY_DISBAND_API_KEY, "desk_413_key")
            controller.saveSecretKey(DesktopSecretsVault.KEY_DISBAND_BASE_URL, "http://127.0.0.1:$port/v1")
            controller.applyStemPreset(DesktopStemPreset.VOCALS_ONLY)
            assertTrue(controller.requestStemSeparation())

            val deadline = System.currentTimeMillis() + 8000
            while (controller.stemState.value.isRunning && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
            }
            assertFalse(controller.stemState.value.isRunning)
            assertEquals("READY", controller.stemState.value.jobStatusLabel)
            assertEquals(1, directPostCount)
            assertEquals(1, reserveCount)
            assertEquals(1, putCount)
            assertFalse("Reserved signed PUT must omit Authorization", putHadAuth)
            assertEquals("audio/mpeg", putContentType)
            assertEquals(1, controller.stemState.value.separatedStems.size)
            assertEquals("vocals", controller.stemState.value.separatedStems.first().stemId)
        } finally {
            controller.shutdown()
            server.stop(0)
        }
    }

    private fun queryOrLastStems(query: String): List<String> {
        val stems = query.split('&')
            .filter { it.startsWith("stems=") }
            .map { it.removePrefix("stems=") }
        return stems.ifEmpty { listOf("vocals") }
    }
}

