package com.example.stems

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.audio.AudioBuffer
import com.example.audio.WavCodec
import com.example.config.SecretsVaultRepository
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DisbandStemsAndSecretsTest {

    private val grantPermissionRule = TestRule { base, _: Description ->
        object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<Application>()
                shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
                base.evaluate()
            }
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(grantPermissionRule).around(composeRule)

    @Test
    fun secretsVaultRepository_storesMasksAndResolvesKeys() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val repo = SecretsVaultRepository(ctx)
        repo.putSecret(SecretsVaultRepository.KEY_DISBAND_API_KEY, "dbnd_live_abcdef_5678")
        repo.putSecret("webhook_secret", "wh_tok_9999")

        assertEquals("dbnd_live_abcdef_5678", repo.resolveDisbandApiKey())
        assertEquals("wh_tok_9999", repo.getSecret("WEBHOOK_SECRET"))
        val masked = repo.state.value.entries.first { it.keyName == SecretsVaultRepository.KEY_DISBAND_API_KEY }.maskedValue
        assertFalse(masked.contains("abcdef"))
        assertTrue(masked.endsWith("5678"))

        repo.removeSecret("webhook_secret")
        assertEquals(null, repo.getSecret("WEBHOOK_SECRET"))
    }

    @Test
    fun disbandApiClient_realLoopbackHttpSeparatesSpecifiedStemsAndDecodesWav() {
        val wavBytes = WavCodec.encodeToBytes(makeSineBuffer("mix", 220f, 0.25f))
        val queries = CopyOnWriteArrayList<String>()
        val server = createFakeDisbandServer(wavBytes, queries)
        server.start()
        try {
            val port = server.address.port
            val client = DisbandApiClient()
            val (job, tracks) = client.separateAndDownloadStems(
                baseUrl = "http://127.0.0.1:$port/v1",
                apiKey = "disband_android_key",
                stems = listOf(DisbandStem.VOCALS, DisbandStem.DRUMS, DisbandStem.GUITAR),
                sourceBuffer = makeSineBuffer("song", 440f, 0.25f),
                maxPollAttempts = 5,
                waitPerPollSec = 1
            )
            assertEquals("ready", job.status)
            assertEquals(3, tracks.size)
            assertEquals(listOf("vocals", "drums", "guitar"), tracks.map { it.stemId })
            val q = queries.single()
            assertTrue(q.contains("stems=vocals"))
            assertTrue(q.contains("stems=drums"))
            assertTrue(q.contains("stems=guitar"))
            assertTrue(tracks.all { it.buffer.frameCount > 0 })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun ui_topBarSecretsVault_unspecifiedAllStemsToastAndConfirmation_andSpecificPresetFlow() {
        val wavBytes = WavCodec.encodeToBytes(makeSineBuffer("stem", 330f, 0.2f))
        val queries = CopyOnWriteArrayList<String>()
        val server = createFakeDisbandServer(wavBytes, queries)
        server.start()
        try {
            val port = server.address.port
            composeRule.waitForIdle()

            // 1) Open Secrets & Keys Vault from Top Toolbar
            composeRule.onNodeWithTag("top_secrets_button").performClick()
            composeRule.onNodeWithTag("secrets_vault_dialog").assertIsDisplayed()
            composeRule.onNodeWithTag("secrets_disband_input").performTextInput("db_secret_ui_4321")
            composeRule.onNodeWithTag("secrets_save_disband_button").performClick()
            composeRule.onNodeWithTag("secrets_disband_status").assertTextContains("SET")

            // Point Disband Base URL at loopback server via dialog or ViewModel helper
            composeRule.activityRule.scenario.onActivity { act ->
                val vmField = MainActivity::class.java.getDeclaredField("viewModel\$delegate")
                vmField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val vm = (vmField.get(act) as Lazy<com.example.ui.SamplfieViewModel>).value
                vm.saveSecretKey(SecretsVaultRepository.KEY_DISBAND_BASE_URL, "http://127.0.0.1:$port/v1")
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("secrets_vault_dialog").assertIsDisplayed()
            composeRule.onNodeWithTag("secrets_disband_input").assertIsDisplayed()
            // Close Secrets Vault dialog
            composeRule.activityRule.scenario.onActivity { act ->
                val vmField = MainActivity::class.java.getDeclaredField("viewModel\$delegate")
                vmField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val vm = (vmField.get(act) as Lazy<com.example.ui.SamplfieViewModel>).value
                vm.setShowSecretsVault(false)
            }
            composeRule.waitForIdle()

            // 2) Load test oscillator audio in Booth so Separate button is enabled
            composeRule.onNodeWithTag("booth_osc_tone_button").performScrollTo().performClick()
            composeRule.waitForIdle()

            // 3) No specific stems selected -> tapping Get Stems MUST toast AND show confirmation dialog without hitting API
            composeRule.onNodeWithTag("disband_separate_button").performScrollTo().performClick()
            composeRule.waitForIdle()

            val toastText = ShadowToast.getTextOfLatestToast()
            assertNotNull("Expected toast warning when no stems specified", toastText)
            assertTrue(toastText.contains("All Stems", ignoreCase = true) || toastText.contains("credits", ignoreCase = true))
            composeRule.onNodeWithTag("all_stems_confirm_dialog").assertIsDisplayed()
            assertTrue("API must not fire before user confirms All Stems", queries.isEmpty())

            // Dismiss confirmation -> pick specific preset (Vocals Only) -> executes directly
            composeRule.onNodeWithTag("cancel_all_stems_button").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("stem_preset_vocals_only").performScrollTo().performClick()
            composeRule.onNodeWithTag("disband_separate_button").performScrollTo().performClick()

            composeRule.waitUntil(timeoutMillis = 5000) {
                composeRule.onAllNodesWithTag("separated_stem_row_vocals").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, queries.size)
            assertTrue(queries[0].contains("stems=vocals"))
            assertFalse(queries[0].contains("stems=drums"))

            // 4) Clear selection (unspecified -> All Stems) and CONFIRM in modal -> separates all 6 core stems
            composeRule.onNodeWithTag("stem_preset_unspecified_all").performScrollTo().performClick()
            composeRule.onNodeWithTag("disband_separate_button").performScrollTo().performClick()
            composeRule.onNodeWithTag("all_stems_confirm_dialog").assertIsDisplayed()
            composeRule.onNodeWithTag("confirm_all_stems_button").performClick()

            composeRule.waitUntil(timeoutMillis = 5000) {
                composeRule.onAllNodesWithTag("separated_stem_row_guitar").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(2, queries.size)
            assertTrue(queries[1].contains("stems=vocals"))
            assertTrue(queries[1].contains("stems=drums"))
            assertTrue(queries[1].contains("stems=bass"))
            assertTrue(queries[1].contains("stems=guitar"))
            assertTrue(queries[1].contains("stems=piano"))
            assertTrue(queries[1].contains("stems=other"))
        } finally {
            server.stop(0)
        }
    }

    private fun createFakeDisbandServer(
        wavBytes: ByteArray,
        recordedQueries: CopyOnWriteArrayList<String>
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/v1/separations") { ex ->
            val path = ex.requestURI.path
            val query = ex.requestURI.rawQuery.orEmpty()
            when {
                ex.requestMethod == "POST" && path == "/v1/separations" -> {
                    ex.requestBody.readBytes()
                    recordedQueries += query
                    val b = """{"id":"sep_droid_1","status":"queued","charge":18}""".toByteArray()
                    ex.sendResponseHeaders(202, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                }
                ex.requestMethod == "GET" && path == "/v1/separations/sep_droid_1" -> {
                    val stems = recordedQueries.lastOrNull().orEmpty()
                        .split('&')
                        .filter { it.startsWith("stems=") }
                        .map { it.removePrefix("stems=") }
                        .ifEmpty { listOf("vocals") }
                    val stemsJson = stems.joinToString(",") { stem ->
                        """"$stem":{"wav":{"url":"http://127.0.0.1:$port/v1/separations/sep_droid_1/stems/$stem/wav","bytes":${wavBytes.size}}}"""
                    }
                    val b = """{"id":"sep_droid_1","status":"ready","charge":${stems.size * 10},"duration":0.25,"stems":{$stemsJson}}""".toByteArray()
                    ex.sendResponseHeaders(200, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                }
                ex.requestMethod == "GET" && path.contains("/stems/") -> {
                    ex.sendResponseHeaders(200, wavBytes.size.toLong())
                    ex.responseBody.use { it.write(wavBytes) }
                }
                else -> {
                    ex.sendResponseHeaders(404, 0)
                    ex.responseBody.close()
                }
            }
        }
        return server
    }

    private fun makeSineBuffer(name: String, hz: Float, sec: Float): AudioBuffer {
        val sr = 44100
        val n = (sr * sec).toInt()
        val samples = FloatArray(n) { i -> (0.5f * sin(2.0 * PI * hz * i / sr)).toFloat() }
        return AudioBuffer(samples, sr, 1, name)
    }
}
