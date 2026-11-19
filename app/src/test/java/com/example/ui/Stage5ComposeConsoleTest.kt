package com.example.ui

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Stage5ComposeConsoleTest {

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
    fun `full live DJ workstation CUJ across deck, visualizer, 12 FX, looper-seq-timeline, and rig config`() {
        composeRule.waitForIdle()

        // 1. Deck renders with TAC pad & Visualizer
        composeRule.onNodeWithText("SAMPLFIE2").assertIsDisplayed()
        composeRule.onNodeWithTag("audio_visualizer_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("tac_hold_button").performScrollTo().assertIsDisplayed()

        // Generate reference tone to populate editor waveform & FFT spectrum
        composeRule.onNodeWithTag("osc_tone_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5000) {
            runCatching {
                composeRule.onNodeWithTag("extract_selection_button").assertExists()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("waveform_canvas").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("spectrum_canvas").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("transport_play_button").performScrollTo().assertIsDisplayed()

        // Switch visualizer modes
        composeRule.onNodeWithTag("vis_mode_spectrum").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("spectrum_canvas").assertIsDisplayed()
        composeRule.onNodeWithTag("vis_mode_dual").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Extract selection & undo
        composeRule.onNodeWithTag("extract_selection_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("undo_extract_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        // 2. FX Rack tab: apply preset & verify FX count
        composeRule.onNodeWithTag("nav_tab_fx_rack").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("fx_preset_acid_wah").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("FX 3").assertIsDisplayed()

        // 3. Studio tab: Looper, 16-Step Sequencer, Timeline
        composeRule.onNodeWithTag("nav_tab_studio").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("looper_play_button").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("studio_subtab_sequencer").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("seq_step_0_0").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("studio_subtab_timeline").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("timeline_ruler").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("timeline_add_sample_t1").performScrollTo().performClick()
        composeRule.waitForIdle()

        // 4. Rig & MIDI tab: configure IP, Port, TCP/UDP, Sample Rate, Channels, Buffer
        composeRule.onNodeWithTag("nav_tab_rig_link").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("config_ip_input").performScrollTo().performTextReplacement("192.168.1.55")
        composeRule.onNodeWithTag("config_port_input").performScrollTo().performTextReplacement("9123")
        composeRule.onNodeWithTag("config_protocol_tcp").performScrollTo().performClick()
        composeRule.onNodeWithTag("config_sr_48000").performScrollTo().performClick()
        composeRule.onNodeWithTag("config_ch_2").performScrollTo().performClick()
        composeRule.onNodeWithTag("config_buf_2048").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("top_rig_summary").assertTextEquals("48kHz • 2CH • B2048 • TCP")
    }
}
