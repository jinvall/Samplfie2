package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.dsp.DelayNoteDivision
import com.example.dsp.FxRackState
import com.example.ui.components.HardwareChipToggle
import com.example.ui.components.HardwareLedIndicator
import com.example.ui.components.HardwarePanel
import com.example.ui.components.HardwareParameterSlider
import com.example.ui.theme.AmberHot
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.MagentaFx
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianChassis
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletMod

@Composable
internal fun FxModuleCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    accentColor: Color,
    testTag: String,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    HardwarePanel(
        borderColor = if (enabled) accentColor.copy(alpha = 0.75f) else HairlineDivider
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                HardwareLedIndicator(active = enabled, color = accentColor, sizeDp = 10)
                Column {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) TextPrimary else TextSecondary)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag(testTag),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ObsidianChassis,
                    checkedTrackColor = accentColor,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = ObsidianChassis
                )
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FxRackSpatialAndModCards(
    fxRack: FxRackState,
    onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit
) {
    // 7. Flanger
    FxModuleCard(
        title = "7. FLANGER",
        subtitle = "Jet-Sweep Comb + Manual Sweep & Stereo Spread",
        enabled = fxRack.flanger.enabled,
        accentColor = MagentaFx,
        testTag = "fx_toggle_flanger",
        onToggle = { onUpdateFxRack { copy(flanger = flanger.copy(enabled = it)) } }
    ) {
        HardwareParameterSlider(
            label = "RATE",
            valueText = "${"%.2f".format(fxRack.flanger.rateHz)} Hz",
            value = fxRack.flanger.rateHz,
            valueRange = 0.1f..4f,
            accentColor = MagentaFx,
            onValueChange = { v -> onUpdateFxRack { copy(flanger = flanger.copy(enabled = true, rateHz = v)) } }
        )
        HardwareParameterSlider(
            label = "MANUAL DELAY",
            valueText = "${"%.1f".format(fxRack.flanger.manualDelayMs)} ms",
            value = fxRack.flanger.manualDelayMs,
            valueRange = 0.2f..4f,
            accentColor = MagentaFx,
            onValueChange = { v -> onUpdateFxRack { copy(flanger = flanger.copy(enabled = true, manualDelayMs = v)) } }
        )
        HardwareParameterSlider(
            label = "DEPTH",
            valueText = "${"%.1f".format(fxRack.flanger.depthMs)} ms",
            value = fxRack.flanger.depthMs,
            valueRange = 0.3f..6f,
            accentColor = MagentaFx,
            onValueChange = { v -> onUpdateFxRack { copy(flanger = flanger.copy(enabled = true, depthMs = v)) } }
        )
        HardwareParameterSlider(
            label = "FEEDBACK",
            valueText = "${(fxRack.flanger.feedback * 100).toInt()}%",
            value = fxRack.flanger.feedback,
            valueRange = -0.85f..0.85f,
            accentColor = MagentaFx,
            onValueChange = { v -> onUpdateFxRack { copy(flanger = flanger.copy(enabled = true, feedback = v)) } }
        )
        HardwareParameterSlider(
            label = "STEREO SPREAD",
            valueText = "${(fxRack.flanger.stereoSpread * 100).toInt()}%",
            value = fxRack.flanger.stereoSpread,
            valueRange = 0f..1f,
            accentColor = MagentaFx,
            onValueChange = { v -> onUpdateFxRack { copy(flanger = flanger.copy(enabled = true, stereoSpread = v)) } }
        )
    }

    // 8. Chorus
    FxModuleCard(
        title = "8. CHORUS",
        subtitle = "2–4 Voice Ensemble + High-Cut Filter",
        enabled = fxRack.chorus.enabled,
        accentColor = NeonCyan,
        testTag = "fx_toggle_chorus",
        onToggle = { onUpdateFxRack { copy(chorus = chorus.copy(enabled = it)) } }
    ) {
        Text("VOICES", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 3, 4).forEach { v ->
                HardwareChipToggle(
                    label = "$v VOICES",
                    selected = fxRack.chorus.voices == v,
                    onClick = { onUpdateFxRack { copy(chorus = chorus.copy(enabled = true, voices = v)) } },
                    accentColor = NeonCyan
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        HardwareParameterSlider(
            label = "RATE",
            valueText = "${"%.2f".format(fxRack.chorus.rateHz)} Hz",
            value = fxRack.chorus.rateHz,
            valueRange = 0.2f..4f,
            accentColor = NeonCyan,
            onValueChange = { v -> onUpdateFxRack { copy(chorus = chorus.copy(enabled = true, rateHz = v)) } }
        )
        HardwareParameterSlider(
            label = "DEPTH",
            valueText = "${"%.1f".format(fxRack.chorus.depthMs)} ms",
            value = fxRack.chorus.depthMs,
            valueRange = 2f..15f,
            accentColor = NeonCyan,
            onValueChange = { v -> onUpdateFxRack { copy(chorus = chorus.copy(enabled = true, depthMs = v)) } }
        )
        HardwareParameterSlider(
            label = "HIGH CUT",
            valueText = "${fxRack.chorus.highCutHz.toInt()} Hz",
            value = fxRack.chorus.highCutHz,
            valueRange = 2000f..14000f,
            accentColor = NeonCyan,
            onValueChange = { v -> onUpdateFxRack { copy(chorus = chorus.copy(enabled = true, highCutHz = v)) } }
        )
        HardwareParameterSlider(
            label = "MIX",
            valueText = "${(fxRack.chorus.mix * 100).toInt()}%",
            value = fxRack.chorus.mix,
            valueRange = 0f..1f,
            accentColor = NeonCyan,
            onValueChange = { v -> onUpdateFxRack { copy(chorus = chorus.copy(enabled = true, mix = v)) } }
        )
    }

    // 9. Rotary Speaker
    FxModuleCard(
        title = "9. ROTARY SPEAKER",
        subtitle = "Leslie Horn + Drum · Fast/Brake + Drive",
        enabled = fxRack.rotarySpeaker.enabled,
        accentColor = AmberHot,
        testTag = "fx_toggle_rotary",
        onToggle = { onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = it)) } }
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HardwareChipToggle(
                label = "FAST ROTOR",
                selected = fxRack.rotarySpeaker.fastMode,
                onClick = { onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, fastMode = !rotarySpeaker.fastMode, brake = false)) } },
                accentColor = AmberHot
            )
            HardwareChipToggle(
                label = "BRAKE",
                selected = fxRack.rotarySpeaker.brake,
                onClick = { onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, brake = !rotarySpeaker.brake)) } },
                accentColor = AmberHot
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        HardwareParameterSlider(
            label = "ROTOR SPEED",
            valueText = "${"%.1f".format(fxRack.rotarySpeaker.rateHz)} Hz",
            value = fxRack.rotarySpeaker.rateHz,
            valueRange = 0.5f..9f,
            accentColor = AmberHot,
            onValueChange = { v -> onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, rateHz = v)) } }
        )
        HardwareParameterSlider(
            label = "CROSSOVER",
            valueText = "${fxRack.rotarySpeaker.crossoverHz.toInt()} Hz",
            value = fxRack.rotarySpeaker.crossoverHz,
            valueRange = 400f..1600f,
            accentColor = AmberHot,
            onValueChange = { v -> onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, crossoverHz = v)) } }
        )
        HardwareParameterSlider(
            label = "CABINET DRIVE",
            valueText = "${(fxRack.rotarySpeaker.cabinetDrive * 100).toInt()}%",
            value = fxRack.rotarySpeaker.cabinetDrive,
            valueRange = 0f..1f,
            accentColor = AmberHot,
            onValueChange = { v -> onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, cabinetDrive = v)) } }
        )
        HardwareParameterSlider(
            label = "MIX",
            valueText = "${(fxRack.rotarySpeaker.mix * 100).toInt()}%",
            value = fxRack.rotarySpeaker.mix,
            valueRange = 0f..1f,
            accentColor = AmberHot,
            onValueChange = { v -> onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, mix = v)) } }
        )
    }

    // 10. Echo
    FxModuleCard(
        title = "10. TAPE ECHO",
        subtitle = "Wow/Flutter + Tape Saturation + Damping",
        enabled = fxRack.echo.enabled,
        accentColor = NeonCyan,
        testTag = "fx_toggle_echo",
        onToggle = { onUpdateFxRack { copy(echo = echo.copy(enabled = it)) } }
    ) {
        HardwareParameterSlider(
            label = "ECHO TIME",
            valueText = "${fxRack.echo.delayMs.toInt()} ms",
            value = fxRack.echo.delayMs,
            valueRange = 50f..1000f,
            accentColor = NeonCyan,
            onValueChange = { v -> onUpdateFxRack { copy(echo = echo.copy(enabled = true, delayMs = v)) } }
        )
        HardwareParameterSlider(
            label = "FEEDBACK",
            valueText = "${(fxRack.echo.feedback * 100).toInt()}%",
            value = fxRack.echo.feedback,
            valueRange = 0f..0.88f,
            accentColor = NeonCyan,
            onValueChange = { v -> onUpdateFxRack { copy(echo = echo.copy(enabled = true, feedback = v)) } }
        )
        HardwareParameterSlider(
            label = "WOW & FLUTTER",
            valueText = "${(fxRack.echo.wowFlutter * 100).toInt()}%",
            value = fxRack.echo.wowFlutter,
            valueRange = 0f..1f,
            accentColor = NeonCyan,
            onValueChange = { v -> onUpdateFxRack { copy(echo = echo.copy(enabled = true, wowFlutter = v)) } }
        )
        HardwareParameterSlider(
            label = "TAPE SATURATION",
            valueText = "${(fxRack.echo.saturation * 100).toInt()}%",
            value = fxRack.echo.saturation,
            valueRange = 0f..1f,
            accentColor = NeonCyan,
            onValueChange = { v -> onUpdateFxRack { copy(echo = echo.copy(enabled = true, saturation = v)) } }
        )
        HardwareParameterSlider(
            label = "MIX",
            valueText = "${(fxRack.echo.mix * 100).toInt()}%",
            value = fxRack.echo.mix,
            valueRange = 0f..1f,
            accentColor = NeonCyan,
            onValueChange = { v -> onUpdateFxRack { copy(echo = echo.copy(enabled = true, mix = v)) } }
        )
    }

    // 11. Delay
    FxModuleCard(
        title = "11. DIGITAL DELAY",
        subtitle = "BPM Sync Divisions + HP/LP Filters + Tap Spread",
        enabled = fxRack.delay.enabled,
        accentColor = VioletMod,
        testTag = "fx_toggle_delay",
        onToggle = { onUpdateFxRack { copy(delay = delay.copy(enabled = it)) } }
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HardwareChipToggle(
                label = "BPM SYNC",
                selected = fxRack.delay.bpmSync,
                onClick = { onUpdateFxRack { copy(delay = delay.copy(enabled = true, bpmSync = !delay.bpmSync)) } },
                accentColor = VioletMod
            )
            DelayNoteDivision.entries.forEach { div ->
                HardwareChipToggle(
                    label = div.label,
                    selected = fxRack.delay.division == div,
                    onClick = { onUpdateFxRack { copy(delay = delay.copy(enabled = true, bpmSync = div != DelayNoteDivision.FREE_MS, division = div)) } },
                    accentColor = VioletMod
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        HardwareParameterSlider(
            label = "DELAY TIME",
            valueText = "${fxRack.delay.delayMs.toInt()} ms",
            value = fxRack.delay.delayMs,
            valueRange = 20f..1200f,
            enabled = !fxRack.delay.bpmSync,
            accentColor = VioletMod,
            onValueChange = { v -> onUpdateFxRack { copy(delay = delay.copy(enabled = true, delayMs = v)) } }
        )
        HardwareParameterSlider(
            label = "FEEDBACK",
            valueText = "${(fxRack.delay.feedback * 100).toInt()}%",
            value = fxRack.delay.feedback,
            valueRange = 0f..0.9f,
            accentColor = VioletMod,
            onValueChange = { v -> onUpdateFxRack { copy(delay = delay.copy(enabled = true, feedback = v)) } }
        )
        HardwareParameterSlider(
            label = "HIGH-PASS CUT",
            valueText = "${fxRack.delay.highPassHz.toInt()} Hz",
            value = fxRack.delay.highPassHz,
            valueRange = 40f..1000f,
            accentColor = VioletMod,
            onValueChange = { v -> onUpdateFxRack { copy(delay = delay.copy(enabled = true, highPassHz = v)) } }
        )
        HardwareParameterSlider(
            label = "TAP SPREAD",
            valueText = "${(fxRack.delay.stereoSpread * 100).toInt()}%",
            value = fxRack.delay.stereoSpread,
            valueRange = 0f..1f,
            accentColor = VioletMod,
            onValueChange = { v -> onUpdateFxRack { copy(delay = delay.copy(enabled = true, stereoSpread = v)) } }
        )
        HardwareParameterSlider(
            label = "MIX",
            valueText = "${(fxRack.delay.mix * 100).toInt()}%",
            value = fxRack.delay.mix,
            valueRange = 0f..1f,
            accentColor = VioletMod,
            onValueChange = { v -> onUpdateFxRack { copy(delay = delay.copy(enabled = true, mix = v)) } }
        )
    }

    // 12. Reverb
    FxModuleCard(
        title = "12. HALL REVERB",
        subtitle = "Early Reflections + Stereo Width + Low-Cut",
        enabled = fxRack.reverb.enabled,
        accentColor = EmeraldSignal,
        testTag = "fx_toggle_reverb",
        onToggle = { onUpdateFxRack { copy(reverb = reverb.copy(enabled = it)) } }
    ) {
        HardwareParameterSlider(
            label = "ROOM SIZE",
            valueText = "${(fxRack.reverb.roomSize * 100).toInt()}%",
            value = fxRack.reverb.roomSize,
            valueRange = 0.1f..0.98f,
            accentColor = EmeraldSignal,
            onValueChange = { v -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = true, roomSize = v)) } }
        )
        HardwareParameterSlider(
            label = "EARLY REFLECTIONS",
            valueText = "${(fxRack.reverb.earlyReflections * 100).toInt()}%",
            value = fxRack.reverb.earlyReflections,
            valueRange = 0f..1f,
            accentColor = EmeraldSignal,
            onValueChange = { v -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = true, earlyReflections = v)) } }
        )
        HardwareParameterSlider(
            label = "STEREO WIDTH",
            valueText = "${(fxRack.reverb.stereoWidth * 100).toInt()}%",
            value = fxRack.reverb.stereoWidth,
            valueRange = 0f..1.4f,
            accentColor = EmeraldSignal,
            onValueChange = { v -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = true, stereoWidth = v)) } }
        )
        HardwareParameterSlider(
            label = "PRE-DELAY",
            valueText = "${fxRack.reverb.preDelayMs.toInt()} ms",
            value = fxRack.reverb.preDelayMs,
            valueRange = 0f..80f,
            accentColor = EmeraldSignal,
            onValueChange = { v -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = true, preDelayMs = v)) } }
        )
        HardwareParameterSlider(
            label = "MIX",
            valueText = "${(fxRack.reverb.mix * 100).toInt()}%",
            value = fxRack.reverb.mix,
            valueRange = 0f..1f,
            accentColor = EmeraldSignal,
            onValueChange = { v -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = true, mix = v)) } }
        )
    }
}
