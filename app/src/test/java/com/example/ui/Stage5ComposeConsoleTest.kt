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
    fun `circular production booth and all 6 spokes work end-to-end`() {
        composeRule.waitForIdle()

        // 0. Central Production Booth Hub renders circular studio wheel & radial spokes
        composeRule.onNodeWithText("SAMPLFIE2").assertIsDisplayed()
        composeRule.onNodeWithTag("circular_studio_wheel").assertIsDisplayed()
        composeRule.onNodeWithTag("booth_hub_card").assertIsDisplayed()
        composeRule.onNodeWithTag("spoke_deck").assertIsDisplayed()
        composeRule.onNodeWithTag("spoke_samples").assertIsDisplayed()
        composeRule.onNodeWithTag("spoke_instruments").assertIsDisplayed()
        composeRule.onNodeWithTag("spoke_tracks").assertIsDisplayed()
        composeRule.onNodeWithTag("spoke_fx_rack").assertIsDisplayed()
        composeRule.onNodeWithTag("spoke_live").assertIsDisplayed()

        // 1. Navigate to DECK Spoke via radial spoke card
        composeRule.onNodeWithTag("spoke_deck").performClick()
        composeRule.waitForIdle()
        // Master Production Hub stays persistently visible on screen
        composeRule.onNodeWithTag("master_production_hub").assertIsDisplayed()
        composeRule.onNodeWithTag("hub_sync_play_button").assertIsDisplayed()
        composeRule.onNodeWithTag("hub_stomp_bypass_button").assertIsDisplayed()
        composeRule.onNodeWithTag("deck_import_audio_button").assertIsDisplayed()
        composeRule.onNodeWithTag("audio_visualizer_panel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("tac_hold_button").performScrollTo().assertIsDisplayed()

        // Generate reference tone to populate waveform & FFT spectrum
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

        // Switch visualizer modes & extract/undo
        composeRule.onNodeWithTag("vis_mode_spectrum").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("spectrum_canvas").assertIsDisplayed()
        composeRule.onNodeWithTag("vis_mode_dual").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("extract_selection_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("undo_extract_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Expand inline 360-degree Circular Studio Wheel from the persistent Hub while on DECK and jump to FX RACK
        composeRule.onNodeWithTag("hub_expand_wheel_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mini_circular_studio_wheel").assertIsDisplayed()
        composeRule.onNodeWithTag("mini_spoke_fx_rack").performClick()
        composeRule.waitForIdle()

        // 2. FX RACK Spoke: Master Production Hub still visible, apply preset & verify FX count
        composeRule.onNodeWithTag("master_production_hub").assertIsDisplayed()
        composeRule.onNodeWithTag("fx_preset_acid_wah").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("FX 3").assertIsDisplayed()

        // Return to Booth Hub via top BOOTH button
        composeRule.onNodeWithTag("top_booth_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("circular_studio_wheel").assertIsDisplayed()

        // 3. SAMPLES Spoke: Master Production Hub stays visible, load editor audio to Loop A & refine loop sample
        composeRule.onNodeWithTag("nav_tab_samples").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("master_production_hub").assertIsDisplayed()
        composeRule.onNodeWithTag("looper_play_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("loop_load_0").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5000) {
            runCatching {
                composeRule.onNodeWithTag("loop_refine_zerocross").assertExists()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("loop_refine_zerocross").performScrollTo().performClick()
        composeRule.onNodeWithTag("loop_refine_normalize").performScrollTo().performClick()
        composeRule.onNodeWithTag("loop_refine_commit").performScrollTo().performClick()
        composeRule.waitForIdle()

        // 4. INSTRUMENTS Spoke: Master Production Hub stays visible, 16-Step Sequencer & Trigger Pads
        composeRule.onNodeWithTag("nav_tab_instruments").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("master_production_hub").assertIsDisplayed()
        composeRule.onNodeWithTag("inst_pad_0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("seq_step_0_0").performScrollTo().performClick()
        composeRule.waitForIdle()

        // 5. TRACKS Spoke: Master Production Hub stays visible, Synced Playhead & Timeline
        composeRule.onNodeWithTag("nav_tab_tracks").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("master_production_hub").assertIsDisplayed()
        composeRule.onNodeWithTag("tracks_sync_readout").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("timeline_ruler").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("timeline_add_sample_t1").performScrollTo().performClick()
        composeRule.waitForIdle()

        // 6. LIVE Spoke: Master Production Hub stays visible, Stompbox Mini Processor, Live Modes, Network & Engine Config
        composeRule.onNodeWithTag("nav_tab_live").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("master_production_hub").assertIsDisplayed()
        composeRule.onNodeWithTag("stomp_xy_pad").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("stomp_kill_mod").performScrollTo().performClick()
        composeRule.onNodeWithTag("stomp_bypass_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("live_mode_live_rec").performScrollTo().performClick()
        composeRule.onNodeWithTag("live_rec_panel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("live_mode_studio_release").performScrollTo().performClick()
        composeRule.onNodeWithTag("live_release_panel").performScrollTo().assertIsDisplayed()

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
