package com.example.drums

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
class CustomDrumKitUiTest {

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
    fun `arrangeable electric and acoustic drum kit studio UI controls and limits work end-to-end`() {
        composeRule.waitForIdle()

        // Jump to Instruments Spoke
        composeRule.onNodeWithTag("spoke_instruments").performClick()
        composeRule.waitForIdle()

        // Default 7-Pc Acoustic Double-Pedal kit renders interactive stage & counter
        composeRule.onNodeWithTag("drum_stage_canvas").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("drum_piece_count").performScrollTo().assertTextContains("7/7 MAIN", substring = true)

        // Trigger Left and Right Double Pedal beaters
        composeRule.onNodeWithTag("drum_beater_left").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drum_beater_right").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Switch kit style to Electric (E-Kit)
        composeRule.onNodeWithTag("drum_style_electric").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Load 5-Pc 808/909 Electro preset, confirm count drops to 5/7, then mount Cowbell onto Snare
        composeRule.onNodeWithTag("drum_preset_el_club_5").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drum_piece_count").performScrollTo().assertTextContains("5/7 MAIN", substring = true)

        composeRule.onNodeWithTag("drum_mount_cowbell").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Toggle Arrange Mode and Left-Handed flip
        composeRule.onNodeWithTag("drum_mode_arrange").performScrollTo().performClick()
        composeRule.onNodeWithTag("drum_flip_lr").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Open component library and add a 6th piece from Acoustic library (hybrid custom kit!)
        composeRule.onNodeWithTag("drum_toggle_library").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drum_lib_filter_acoustic").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drum_lib_add_tom_flr_ac").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drum_piece_count").performScrollTo().assertTextContains("6/7 MAIN", substring = true)

        // Verify 16-step sequencer lanes remain intact below the Drum Kit Studio
        composeRule.onNodeWithTag("inst_pad_0").performScrollTo().assertIsDisplayed()
    }
}
