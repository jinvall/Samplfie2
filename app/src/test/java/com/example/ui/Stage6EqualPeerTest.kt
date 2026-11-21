package com.example.ui

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class Stage6EqualPeerTest {

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
    fun `tuner, 64-ch vmc, and equal peer session handoff customizer render and respond`() {
        composeRule.waitForIdle()

        // Booth renders equal-peer handoff and customizer panels plus radial TUNER & VMC spokes
        composeRule.onNodeWithTag("spoke_tuner").assertIsDisplayed()
        composeRule.onNodeWithTag("spoke_vmc").assertIsDisplayed()
        composeRule.onNodeWithTag("booth_handoff_panel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("booth_customizer_panel").performScrollTo().assertIsDisplayed()

        // Navigate to Chromatic YIN Tuner
        composeRule.onNodeWithTag("booth_open_tuner_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tuner_gauge_canvas").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("tuner_note_readout").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("tuner_a4_432").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tuner_preset_guitar_std").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Return to Booth and jump to 64-Channel VMC
        composeRule.onNodeWithTag("top_booth_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("spoke_vmc").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("vmc_bank_3").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("vmc_active_channel_label").performScrollTo().assertTextContains("BUS 4", substring = true)
        composeRule.onNodeWithTag("vmc_ch_next").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("vmc_pad_loop").performScrollTo().performClick()
        composeRule.onNodeWithTag("vmc_learn_new_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("vmc_status_summary").performScrollTo().assertTextContains("MIDI LEARN", substring = true)
    }
}
