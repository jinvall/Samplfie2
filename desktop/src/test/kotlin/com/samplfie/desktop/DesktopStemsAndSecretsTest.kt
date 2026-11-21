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

    private fun queryOrLastStems(query: String): List<String> {
        val stems = query.split('&')
            .filter { it.startsWith("stems=") }
            .map { it.removePrefix("stems=") }
        return stems.ifEmpty { listOf("vocals") }
    }
}
