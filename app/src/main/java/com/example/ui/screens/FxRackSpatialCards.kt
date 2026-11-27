package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.dsp.AnalogDelayParams
import com.example.dsp.BiquadFilterMode
import com.example.dsp.BiquadFilterParams
import com.example.dsp.ChorusParams
import com.example.dsp.DelayNoteDivision
import com.example.dsp.DelayParams
import com.example.dsp.DistortionCurve
import com.example.dsp.DistortionParams
import com.example.dsp.EchoParams
import com.example.dsp.FlangerParams
import com.example.dsp.FxRackState
import com.example.dsp.GainParams
import com.example.dsp.NoiseRemovalParams
import com.example.dsp.OctaveParams
import com.example.dsp.OverdriveParams
import com.example.dsp.PhaserParams
import com.example.dsp.PitchSpeedParams
import com.example.dsp.ReverbParams
import com.example.dsp.RotaryParams
import com.example.dsp.WahMode
import com.example.dsp.WahParams
import com.example.ui.components.HardwareChipToggle
import com.example.ui.components.HardwareLedIndicator
import com.example.ui.components.HardwareParameterSlider
import com.example.ui.theme.AmberHot
import com.example.ui.theme.BrushedPanel
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.GunmetalDeck
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.MagentaFx
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianChassis
import com.example.ui.theme.TacCoral
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletMod

enum class FxModuleId(
    val rackTitle: String,
    val isFilterLine: Boolean,
    val testTagKey: String
) {
    // Dedicated Filter Line (3 Filters)
    BIQUAD_FILTER("F1. BIQUAD FILTER", true, "biquad_filter"),
    WAH("F2. AUTO / PEDAL WAH", true, "wah"),
    PHASER("F3. STEREO PHASER", true, "phaser"),

    // 13-Module DSP FX Chain
    NOISE_REMOVAL("1. NOISE REMOVAL", false, "noise_removal"),
    PITCH_SPEED("2. PITCH & SPEED", false, "pitch_speed"),
    GAIN("3. GAIN & DYNAMICS", false, "gain"),
    OCTAVE("4. OCTAVE (-1/+1)", false, "octave"),
    OVERDRIVE("5. OVERDRIVE", false, "overdrive"),
    DISTORTION("6. DISTORTION", false, "distortion"),
    FLANGER("7. FLANGER", false, "flanger"),
    CHORUS("8. CHORUS", false, "chorus"),
    ROTARY("9. ROTARY SPEAKER", false, "rotary"),
    ECHO("10. TAPE ECHO", false, "echo"),
    DELAY("11. DIGITAL DELAY", false, "delay"),
    ANALOG_DELAY("12. ANALOG BBD DELAY", false, "analog_delay"),
    REVERB("13. HALL REVERB", false, "reverb")
}

/**
 * Compact LMMS-style FX / Filter rack slot card:
 * Fixed height prevents scrolling layout jumps when live audio metrics update.
 * Tapping the module card opens the full LMMS parameter editor window.
 */
@Composable
internal fun LmmsFxTileCard(
    moduleId: FxModuleId,
    subtitle: String,
    levelLabel: String,
    enabled: Boolean,
    accentColor: Color,
    onToggle: (Boolean) -> Unit,
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpenEditor)
            .testTag("fx_tile_${moduleId.testTagKey}"),
        color = if (enabled) BrushedPanel else GunmetalDeck,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (enabled) 1.5.dp else 1.dp,
            color = if (enabled) accentColor.copy(alpha = 0.85f) else HairlineDivider
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                HardwareLedIndicator(active = enabled, color = accentColor, sizeDp = 10)
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = moduleId.rackTitle,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (enabled) TextPrimary else TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Box(
                            modifier = Modifier
                                .background(accentColor.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = levelLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = accentColor,
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (enabled) TextSecondary else TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = accentColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onOpenEditor)
                        .testTag("fx_edit_${moduleId.testTagKey}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = "Edit ${moduleId.rackTitle}",
                            tint = accentColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "EDIT",
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("fx_toggle_${moduleId.testTagKey}"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ObsidianChassis,
                        checkedTrackColor = accentColor,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = ObsidianChassis
                    )
                )
            }
        }
    }
}

/**
 * LMMS-style click-to-open parameter editor modal for any of the 16 FX & Filter modules.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FxModuleEditorDialog(
    moduleId: FxModuleId,
    fxRack: FxRackState,
    onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit,
    onDismiss: () -> Unit
) {
    val accent = moduleAccentColor(moduleId)
    val enabled = isModuleEnabled(fxRack, moduleId)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GunmetalDeck,
        title = {
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
                    HardwareLedIndicator(active = enabled, color = accent, sizeDp = 12)
                    Text(
                        text = moduleId.rackTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { on -> toggleModuleById(moduleId, on, onUpdateFxRack) },
                    modifier = Modifier.testTag("fx_dialog_toggle_${moduleId.testTagKey}"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ObsidianChassis,
                        checkedTrackColor = accent,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = ObsidianChassis
                    )
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (moduleId) {
                    FxModuleId.BIQUAD_FILTER -> BiquadFilterEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.WAH -> WahEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.PHASER -> PhaserEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.NOISE_REMOVAL -> NoiseRemovalEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.PITCH_SPEED -> PitchSpeedEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.GAIN -> GainEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.OCTAVE -> OctaveEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.OVERDRIVE -> OverdriveEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.DISTORTION -> DistortionEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.FLANGER -> FlangerEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.CHORUS -> ChorusEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.ROTARY -> RotaryEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.ECHO -> EchoEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.DELAY -> DelayEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.ANALOG_DELAY -> AnalogDelayEditorBody(fxRack, accent, onUpdateFxRack)
                    FxModuleId.REVERB -> ReverbEditorBody(fxRack, accent, onUpdateFxRack)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                modifier = Modifier.testTag("fx_dialog_done")
            ) {
                Text("DONE", color = ObsidianChassis)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { resetModuleById(moduleId, onUpdateFxRack) },
                border = BorderStroke(1.dp, TacCoral),
                modifier = Modifier.testTag("fx_dialog_reset")
            ) {
                Text("RESET", color = TacCoral)
            }
        }
    )
}

internal fun moduleAccentColor(id: FxModuleId): Color = when (id) {
    FxModuleId.BIQUAD_FILTER -> NeonCyan
    FxModuleId.WAH -> AmberHot
    FxModuleId.PHASER -> VioletMod
    FxModuleId.NOISE_REMOVAL -> EmeraldSignal
    FxModuleId.PITCH_SPEED -> NeonCyan
    FxModuleId.GAIN -> EmeraldSignal
    FxModuleId.OCTAVE -> VioletMod
    FxModuleId.OVERDRIVE -> TacCoral
    FxModuleId.DISTORTION -> MagentaFx
    FxModuleId.FLANGER -> MagentaFx
    FxModuleId.CHORUS -> NeonCyan
    FxModuleId.ROTARY -> AmberHot
    FxModuleId.ECHO -> NeonCyan
    FxModuleId.DELAY -> VioletMod
    FxModuleId.ANALOG_DELAY -> AmberHot
    FxModuleId.REVERB -> EmeraldSignal
}

internal fun isModuleEnabled(fx: FxRackState, id: FxModuleId): Boolean = when (id) {
    FxModuleId.BIQUAD_FILTER -> fx.biquadFilter.enabled
    FxModuleId.WAH -> fx.wah.enabled
    FxModuleId.PHASER -> fx.phaser.enabled
    FxModuleId.NOISE_REMOVAL -> fx.noiseRemoval.enabled
    FxModuleId.PITCH_SPEED -> fx.pitchSpeed.enabled
    FxModuleId.GAIN -> fx.gain.enabled
    FxModuleId.OCTAVE -> fx.octave.enabled
    FxModuleId.OVERDRIVE -> fx.overdrive.enabled
    FxModuleId.DISTORTION -> fx.distortion.enabled
    FxModuleId.FLANGER -> fx.flanger.enabled
    FxModuleId.CHORUS -> fx.chorus.enabled
    FxModuleId.ROTARY -> fx.rotarySpeaker.enabled
    FxModuleId.ECHO -> fx.echo.enabled
    FxModuleId.DELAY -> fx.delay.enabled
    FxModuleId.ANALOG_DELAY -> fx.analogDelay.enabled
    FxModuleId.REVERB -> fx.reverb.enabled
}

internal fun toggleModuleById(
    id: FxModuleId,
    enabled: Boolean,
    onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit
) {
    when (id) {
        FxModuleId.BIQUAD_FILTER -> onUpdateFxRack { copy(biquadFilter = biquadFilter.copy(enabled = enabled)) }
        FxModuleId.WAH -> onUpdateFxRack { copy(wah = wah.copy(enabled = enabled)) }
        FxModuleId.PHASER -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = enabled)) }
        FxModuleId.NOISE_REMOVAL -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = enabled)) }
        FxModuleId.PITCH_SPEED -> onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = enabled)) }
        FxModuleId.GAIN -> onUpdateFxRack { copy(gain = gain.copy(enabled = enabled)) }
        FxModuleId.OCTAVE -> onUpdateFxRack { copy(octave = octave.copy(enabled = enabled)) }
        FxModuleId.OVERDRIVE -> onUpdateFxRack { copy(overdrive = overdrive.copy(enabled = enabled)) }
        FxModuleId.DISTORTION -> onUpdateFxRack { copy(distortion = distortion.copy(enabled = enabled)) }
        FxModuleId.FLANGER -> onUpdateFxRack { copy(flanger = flanger.copy(enabled = enabled)) }
        FxModuleId.CHORUS -> onUpdateFxRack { copy(chorus = chorus.copy(enabled = enabled)) }
        FxModuleId.ROTARY -> onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = enabled)) }
        FxModuleId.ECHO -> onUpdateFxRack { copy(echo = echo.copy(enabled = enabled)) }
        FxModuleId.DELAY -> onUpdateFxRack { copy(delay = delay.copy(enabled = enabled)) }
        FxModuleId.ANALOG_DELAY -> onUpdateFxRack { copy(analogDelay = analogDelay.copy(enabled = enabled)) }
        FxModuleId.REVERB -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = enabled)) }
    }
}

private fun resetModuleById(
    id: FxModuleId,
    onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit
) {
    when (id) {
        FxModuleId.BIQUAD_FILTER -> onUpdateFxRack { copy(biquadFilter = BiquadFilterParams()) }
        FxModuleId.WAH -> onUpdateFxRack { copy(wah = WahParams()) }
        FxModuleId.PHASER -> onUpdateFxRack { copy(phaser = PhaserParams()) }
        FxModuleId.NOISE_REMOVAL -> onUpdateFxRack { copy(noiseRemoval = NoiseRemovalParams()) }
        FxModuleId.PITCH_SPEED -> onUpdateFxRack { copy(pitchSpeed = PitchSpeedParams()) }
        FxModuleId.GAIN -> onUpdateFxRack { copy(gain = GainParams()) }
        FxModuleId.OCTAVE -> onUpdateFxRack { copy(octave = OctaveParams()) }
        FxModuleId.OVERDRIVE -> onUpdateFxRack { copy(overdrive = OverdriveParams()) }
        FxModuleId.DISTORTION -> onUpdateFxRack { copy(distortion = DistortionParams()) }
        FxModuleId.FLANGER -> onUpdateFxRack { copy(flanger = FlangerParams()) }
        FxModuleId.CHORUS -> onUpdateFxRack { copy(chorus = ChorusParams()) }
        FxModuleId.ROTARY -> onUpdateFxRack { copy(rotarySpeaker = RotaryParams()) }
        FxModuleId.ECHO -> onUpdateFxRack { copy(echo = EchoParams()) }
        FxModuleId.DELAY -> onUpdateFxRack { copy(delay = DelayParams()) }
        FxModuleId.ANALOG_DELAY -> onUpdateFxRack { copy(analogDelay = AnalogDelayParams()) }
        FxModuleId.REVERB -> onUpdateFxRack { copy(reverb = ReverbParams()) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BiquadFilterEditorBody(
    fx: FxRackState,
    accent: Color,
    onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit
) {
    Text("FILTER TOPOLOGY", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BiquadFilterMode.entries.forEach { m ->
            HardwareChipToggle(
                label = m.label,
                selected = fx.biquadFilter.mode == m,
                onClick = { onUpdateFxRack { copy(biquadFilter = biquadFilter.copy(enabled = true, mode = m)) } },
                accentColor = accent,
                modifier = Modifier.testTag("filter_mode_${m.name.lowercase()}")
            )
        }
    }
    HardwareParameterSlider("CUTOFF FREQ", "${fx.biquadFilter.cutoffHz.toInt()} Hz", fx.biquadFilter.cutoffHz, 60f..14000f, { v ->
        onUpdateFxRack { copy(biquadFilter = biquadFilter.copy(enabled = true, cutoffHz = v)) }
    }, accent)
    HardwareParameterSlider("RESONANCE Q", "${"%.2f".format(fx.biquadFilter.resonanceQ)}", fx.biquadFilter.resonanceQ, 0.5f..12f, { v ->
        onUpdateFxRack { copy(biquadFilter = biquadFilter.copy(enabled = true, resonanceQ = v)) }
    }, accent)
    HardwareParameterSlider("FILTER DRIVE", "${"%.1f".format(fx.biquadFilter.drive)}x", fx.biquadFilter.drive, 0.5f..4f, { v ->
        onUpdateFxRack { copy(biquadFilter = biquadFilter.copy(enabled = true, drive = v)) }
    }, accent)
    HardwareParameterSlider("LFO RATE", "${"%.2f".format(fx.biquadFilter.lfoRateHz)} Hz", fx.biquadFilter.lfoRateHz, 0.05f..8f, { v ->
        onUpdateFxRack { copy(biquadFilter = biquadFilter.copy(enabled = true, lfoRateHz = v)) }
    }, accent)
    HardwareParameterSlider("LFO DEPTH", "${(fx.biquadFilter.lfoDepth * 100).toInt()}%", fx.biquadFilter.lfoDepth, 0f..1f, { v ->
        onUpdateFxRack { copy(biquadFilter = biquadFilter.copy(enabled = true, lfoDepth = v)) }
    }, accent)
    HardwareParameterSlider("MIX", "${(fx.biquadFilter.mix * 100).toInt()}%", fx.biquadFilter.mix, 0f..1f, { v ->
        onUpdateFxRack { copy(biquadFilter = biquadFilter.copy(enabled = true, mix = v)) }
    }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.biquadFilter.level * 100).toInt()}%", fx.biquadFilter.level, 0f..2f, { v ->
        onUpdateFxRack { copy(biquadFilter = biquadFilter.copy(enabled = true, level = v)) }
    }, accent)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DistortionEditorBody(
    fx: FxRackState,
    accent: Color,
    onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit
) {
    Text("DISTORTION WAVESHAPER CURVE", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DistortionCurve.entries.forEach { c ->
            HardwareChipToggle(
                label = c.label,
                selected = fx.distortion.curve == c,
                onClick = { onUpdateFxRack { copy(distortion = distortion.copy(enabled = true, curve = c)) } },
                accentColor = accent,
                modifier = Modifier.testTag("dist_curve_${c.name.lowercase()}")
            )
        }
    }
    HardwareParameterSlider("DRIVE", "${"%.1f".format(fx.distortion.drive)}x", fx.distortion.drive, 1f..24f, { v ->
        onUpdateFxRack { copy(distortion = distortion.copy(enabled = true, drive = v)) }
    }, accent)
    HardwareParameterSlider("TONE LPF", "${fx.distortion.toneHz.toInt()} Hz", fx.distortion.toneHz, 600f..12000f, { v ->
        onUpdateFxRack { copy(distortion = distortion.copy(enabled = true, toneHz = v)) }
    }, accent)
    HardwareParameterSlider("SYMMETRY / BIAS", "${(fx.distortion.symmetry * 100).toInt()}%", fx.distortion.symmetry, 0f..1f, { v ->
        onUpdateFxRack { copy(distortion = distortion.copy(enabled = true, symmetry = v)) }
    }, accent)
    HardwareParameterSlider("MIX", "${(fx.distortion.mix * 100).toInt()}%", fx.distortion.mix, 0f..1f, { v ->
        onUpdateFxRack { copy(distortion = distortion.copy(enabled = true, mix = v)) }
    }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.distortion.level * 100).toInt()}%", fx.distortion.level, 0f..2f, { v ->
        onUpdateFxRack { copy(distortion = distortion.copy(enabled = true, level = v)) }
    }, accent)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WahEditorBody(fx: FxRackState, accent: Color, onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        WahMode.entries.forEach { m ->
            HardwareChipToggle(m.label, fx.wah.mode == m, { onUpdateFxRack { copy(wah = wah.copy(enabled = true, mode = m)) } }, accent)
        }
    }
    HardwareParameterSlider("CENTER FREQ", "${fx.wah.centerFreqHz.toInt()} Hz", fx.wah.centerFreqHz, 350f..2200f, { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, centerFreqHz = v)) } }, accent)
    if (fx.wah.mode == WahMode.MANUAL_PEDAL) {
        HardwareParameterSlider("PEDAL SWEEP", "${(fx.wah.manualPedalPos * 100).toInt()}%", fx.wah.manualPedalPos, 0f..1f, { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, manualPedalPos = v)) } }, accent)
    }
    HardwareParameterSlider("RESONANCE Q", "${"%.1f".format(fx.wah.resonanceQ)}", fx.wah.resonanceQ, 1.5f..8f, { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, resonanceQ = v)) } }, accent)
    HardwareParameterSlider("LFO RATE", "${"%.1f".format(fx.wah.rateHz)} Hz", fx.wah.rateHz, 0.2f..6f, { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, rateHz = v)) } }, accent)
    HardwareParameterSlider("ENV SENS", "${(fx.wah.envSensitivity * 100).toInt()}%", fx.wah.envSensitivity, 0f..1f, { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, envSensitivity = v)) } }, accent)
    HardwareParameterSlider("MIX", "${(fx.wah.mix * 100).toInt()}%", fx.wah.mix, 0f..1f, { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, mix = v)) } }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.wah.level * 100).toInt()}%", fx.wah.level, 0f..2f, { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, level = v)) } }, accent)
}

@Composable
private fun PhaserEditorBody(fx: FxRackState, accent: Color, onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(4, 6, 8).forEach { st ->
            HardwareChipToggle("$st-STAGE", fx.phaser.stages == st, { onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, stages = st)) } }, accent)
        }
    }
    HardwareParameterSlider("RATE", "${"%.2f".format(fx.phaser.rateHz)} Hz", fx.phaser.rateHz, 0.1f..5f, { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, rateHz = v)) } }, accent)
    HardwareParameterSlider("DEPTH", "${(fx.phaser.depth * 100).toInt()}%", fx.phaser.depth, 0f..1f, { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, depth = v)) } }, accent)
    HardwareParameterSlider("CENTER FREQ", "${fx.phaser.centerFreqHz.toInt()} Hz", fx.phaser.centerFreqHz, 300f..2500f, { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, centerFreqHz = v)) } }, accent)
    HardwareParameterSlider("FEEDBACK", "${(fx.phaser.feedback * 100).toInt()}%", fx.phaser.feedback, 0f..0.85f, { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, feedback = v)) } }, accent)
    HardwareParameterSlider("STEREO OFFSET", "${(fx.phaser.stereoPhaseOffset * 100).toInt()}%", fx.phaser.stereoPhaseOffset, 0f..1f, { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, stereoPhaseOffset = v)) } }, accent)
    HardwareParameterSlider("MIX", "${(fx.phaser.mix * 100).toInt()}%", fx.phaser.mix, 0f..1f, { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, mix = v)) } }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.phaser.level * 100).toInt()}%", fx.phaser.level, 0f..2f, { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, level = v)) } }, accent)
}

@Composable
private fun NoiseRemovalEditorBody(fx: FxRackState, accent: Color, onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit) {
    HardwareChipToggle("HIGH-PASS FILTER", fx.noiseRemoval.highPass80Hz, { onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, highPass80Hz = !noiseRemoval.highPass80Hz)) } }, accent)
    HardwareParameterSlider("THRESHOLD", "${fx.noiseRemoval.thresholdDb.toInt()} dB", fx.noiseRemoval.thresholdDb, -60f..-10f, { v -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, thresholdDb = v)) } }, accent)
    HardwareParameterSlider("REDUCTION", "${fx.noiseRemoval.reductionDb.toInt()} dB", fx.noiseRemoval.reductionDb, 6f..48f, { v -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, reductionDb = v)) } }, accent)
    HardwareParameterSlider("HPF CUTOFF", "${fx.noiseRemoval.highPassFreqHz.toInt()} Hz", fx.noiseRemoval.highPassFreqHz, 30f..250f, { v -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, highPass80Hz = true, highPassFreqHz = v)) } }, accent, enabled = fx.noiseRemoval.highPass80Hz)
    HardwareParameterSlider("ATTACK", "${"%.1f".format(fx.noiseRemoval.attackMs)} ms", fx.noiseRemoval.attackMs, 0.5f..25f, { v -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, attackMs = v)) } }, accent)
    HardwareParameterSlider("RELEASE", "${fx.noiseRemoval.releaseMs.toInt()} ms", fx.noiseRemoval.releaseMs, 15f..350f, { v -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, releaseMs = v)) } }, accent)
    HardwareParameterSlider("KNEE WIDTH", "${fx.noiseRemoval.kneeWidthDb.toInt()} dB", fx.noiseRemoval.kneeWidthDb, 0f..18f, { v -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, kneeWidthDb = v)) } }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.noiseRemoval.level * 100).toInt()}%", fx.noiseRemoval.level, 0f..2f, { v -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, level = v)) } }, accent)
}

@Composable
private fun PitchSpeedEditorBody(fx: FxRackState, accent: Color, onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit) {
    HardwareChipToggle("VINYL LINK (PITCH = SPEED)", fx.pitchSpeed.vinylLink, { onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, vinylLink = !pitchSpeed.vinylLink)) } }, accent)
    HardwareParameterSlider("PITCH SHIFT", "${if (fx.pitchSpeed.pitchSemitones >= 0) "+" else ""}${"%.1f".format(fx.pitchSpeed.pitchSemitones)} st", fx.pitchSpeed.pitchSemitones, -12f..12f, { v -> onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, pitchSemitones = v)) } }, accent, enabled = !fx.pitchSpeed.vinylLink)
    HardwareParameterSlider("FINE DETUNE", "${fx.pitchSpeed.fineCents.toInt()} ct", fx.pitchSpeed.fineCents, -50f..50f, { v -> onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, fineCents = v)) } }, accent, enabled = !fx.pitchSpeed.vinylLink)
    HardwareParameterSlider("TEMPO / SPEED", "${"%.2f".format(fx.pitchSpeed.speedFactor)}x", fx.pitchSpeed.speedFactor, 0.5f..2f, { v -> onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, speedFactor = v)) } }, accent)
    HardwareParameterSlider("GRAIN SIZE", "${fx.pitchSpeed.grainSizeMs.toInt()} ms", fx.pitchSpeed.grainSizeMs, 12f..80f, { v -> onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, grainSizeMs = v)) } }, accent, enabled = !fx.pitchSpeed.vinylLink)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.pitchSpeed.level * 100).toInt()}%", fx.pitchSpeed.level, 0f..2f, { v -> onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, level = v)) } }, accent)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GainEditorBody(fx: FxRackState, accent: Color, onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HardwareChipToggle("NORMALIZE", fx.gain.normalize, { onUpdateFxRack { copy(gain = gain.copy(enabled = true, normalize = !gain.normalize)) } }, accent)
        HardwareChipToggle("SOFT LIMITER", fx.gain.softLimiter, { onUpdateFxRack { copy(gain = gain.copy(enabled = true, softLimiter = !gain.softLimiter)) } }, AmberHot)
    }
    HardwareParameterSlider("GAIN", "${if (fx.gain.gainDb >= 0) "+" else ""}${"%.1f".format(fx.gain.gainDb)} dB", fx.gain.gainDb, -24f..24f, { v -> onUpdateFxRack { copy(gain = gain.copy(enabled = true, gainDb = v)) } }, accent)
    HardwareParameterSlider("COMP RATIO", "${"%.1f".format(fx.gain.compressionRatio)}:1", fx.gain.compressionRatio, 1f..6f, { v -> onUpdateFxRack { copy(gain = gain.copy(enabled = true, compressionRatio = v)) } }, accent)
    HardwareParameterSlider("LIMIT CEILING", "${"%.1f".format(fx.gain.limiterCeilingDb)} dB", fx.gain.limiterCeilingDb, -6f..0f, { v -> onUpdateFxRack { copy(gain = gain.copy(enabled = true, softLimiter = true, limiterCeilingDb = v)) } }, AmberHot, enabled = fx.gain.softLimiter)
    HardwareParameterSlider("STEREO WIDTH", "${(fx.gain.stereoWidth * 100).toInt()}%", fx.gain.stereoWidth, 0f..1.8f, { v -> onUpdateFxRack { copy(gain = gain.copy(enabled = true, stereoWidth = v)) } }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.gain.level * 100).toInt()}%", fx.gain.level, 0f..2f, { v -> onUpdateFxRack { copy(gain = gain.copy(enabled = true, level = v)) } }, accent)
}

@Composable
private fun OctaveEditorBody(fx: FxRackState, accent: Color, onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit) {
    HardwareParameterSlider("SUB (-1 OCTAVE)", "${(fx.octave.subOctaveMix * 100).toInt()}%", fx.octave.subOctaveMix, 0f..1f, { v -> onUpdateFxRack { copy(octave = octave.copy(enabled = true, subOctaveMix = v)) } }, accent)
    HardwareParameterSlider("UPPER (+1 OCTAVE)", "${(fx.octave.octaveUpMix * 100).toInt()}%", fx.octave.octaveUpMix, 0f..1f, { v -> onUpdateFxRack { copy(octave = octave.copy(enabled = true, octaveUpMix = v)) } }, accent)
    HardwareParameterSlider("SUB LPF TONE", "${fx.octave.subToneHz.toInt()} Hz", fx.octave.subToneHz, 250f..2400f, { v -> onUpdateFxRack { copy(octave = octave.copy(enabled = true, subToneHz = v)) } }, accent)
    HardwareParameterSlider("DRY BLEND", "${(fx.octave.dryMix * 100).toInt()}%", fx.octave.dryMix, 0f..1f, { v -> onUpdateFxRack { copy(octave = octave.copy(enabled = true, dryMix = v)) } }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.octave.level * 100).toInt()}%", fx.octave.level, 0f..2f, { v -> onUpdateFxRack { copy(octave = octave.copy(enabled = true, level = v)) } }, accent)
}

@Composable
private fun OverdriveEditorBody(fx: FxRackState, accent: Color, onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit) {
    HardwareParameterSlider("DRIVE", "${"%.1f".format(fx.overdrive.drive)}x", fx.overdrive.drive, 1f..20f, { v -> onUpdateFxRack { copy(overdrive = overdrive.copy(enabled = true, drive = v)) } }, accent)
    HardwareParameterSlider("TUBE ASYMMETRY", "${(fx.overdrive.asymmetry * 100).toInt()}%", fx.overdrive.asymmetry, 0f..0.8f, { v -> onUpdateFxRack { copy(overdrive = overdrive.copy(enabled = true, asymmetry = v)) } }, accent)
    HardwareParameterSlider("PRE-HPF CUTOFF", "${fx.overdrive.preHighPassHz.toInt()} Hz", fx.overdrive.preHighPassHz, 30f..450f, { v -> onUpdateFxRack { copy(overdrive = overdrive.copy(enabled = true, preHighPassHz = v)) } }, accent)
    HardwareParameterSlider("TONE LPF", "${fx.overdrive.toneHz.toInt()} Hz", fx.overdrive.toneHz, 800f..12000f, { v -> onUpdateFxRack { copy(overdrive = overdrive.copy(enabled = true, toneHz = v)) } }, accent)
    HardwareParameterSlider("MIX", "${(fx.overdrive.mix * 100).toInt()}%", fx.overdrive.mix, 0f..1f, { v -> onUpdateFxRack { copy(overdrive = overdrive.copy(enabled = true, mix = v)) } }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.overdrive.level * 100).toInt()}%", fx.overdrive.level, 0f..2f, { v -> onUpdateFxRack { copy(overdrive = overdrive.copy(enabled = true, level = v)) } }, accent)
}

@Composable
private fun FlangerEditorBody(fx: FxRackState, accent: Color, onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit) {
    HardwareParameterSlider("RATE", "${"%.2f".format(fx.flanger.rateHz)} Hz", fx.flanger.rateHz, 0.1f..4f, { v -> onUpdateFxRack { copy(flanger = flanger.copy(enabled = true, rateHz = v)) } }, accent)
    HardwareParameterSlider("MANUAL DELAY", "${"%.1f".format(fx.flanger.manualDelayMs)} ms", fx.flanger.manualDelayMs, 0.2f..4f, { v -> onUpdateFxRack { copy(flanger = flanger.copy(enabled = true, manualDelayMs = v)) } }, accent)
    HardwareParameterSlider("DEPTH", "${"%.1f".format(fx.flanger.depthMs)} ms", fx.flanger.depthMs, 0.3f..6f, { v -> onUpdateFxRack { copy(flanger = flanger.copy(enabled = true, depthMs = v)) } }, accent)
    HardwareParameterSlider("FEEDBACK", "${(fx.flanger.feedback * 100).toInt()}%", fx.flanger.feedback, -0.85f..0.85f, { v -> onUpdateFxRack { copy(flanger = flanger.copy(enabled = true, feedback = v)) } }, accent)
    HardwareParameterSlider("STEREO SPREAD", "${(fx.flanger.stereoSpread * 100).toInt()}%", fx.flanger.stereoSpread, 0f..1f, { v -> onUpdateFxRack { copy(flanger = flanger.copy(enabled = true, stereoSpread = v)) } }, accent)
    HardwareParameterSlider("MIX", "${(fx.flanger.mix * 100).toInt()}%", fx.flanger.mix, 0f..1f, { v -> onUpdateFxRack { copy(flanger = flanger.copy(enabled = true, mix = v)) } }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.flanger.level * 100).toInt()}%", fx.flanger.level, 0f..2f, { v -> onUpdateFxRack { copy(flanger = flanger.copy(enabled = true, level = v)) } }, accent)
}

@Composable
private fun ChorusEditorBody(fx: FxRackState, accent: Color, onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(2, 3, 4).forEach { v ->
            HardwareChipToggle("$v VOICES", fx.chorus.voices == v, { onUpdateFxRack { copy(chorus = chorus.copy(enabled = true, voices = v)) } }, accent)
        }
    }
    HardwareParameterSlider("RATE", "${"%.2f".format(fx.chorus.rateHz)} Hz", fx.chorus.rateHz, 0.2f..4f, { v -> onUpdateFxRack { copy(chorus = chorus.copy(enabled = true, rateHz = v)) } }, accent)
    HardwareParameterSlider("BASE DELAY", "${fx.chorus.baseDelayMs.toInt()} ms", fx.chorus.baseDelayMs, 5f..30f, { v -> onUpdateFxRack { copy(chorus = chorus.copy(enabled = true, baseDelayMs = v)) } }, accent)
    HardwareParameterSlider("DEPTH", "${"%.1f".format(fx.chorus.depthMs)} ms", fx.chorus.depthMs, 2f..15f, { v -> onUpdateFxRack { copy(chorus = chorus.copy(enabled = true, depthMs = v)) } }, accent)
    HardwareParameterSlider("HIGH CUT", "${fx.chorus.highCutHz.toInt()} Hz", fx.chorus.highCutHz, 2000f..14000f, { v -> onUpdateFxRack { copy(chorus = chorus.copy(enabled = true, highCutHz = v)) } }, accent)
    HardwareParameterSlider("MIX", "${(fx.chorus.mix * 100).toInt()}%", fx.chorus.mix, 0f..1f, { v -> onUpdateFxRack { copy(chorus = chorus.copy(enabled = true, mix = v)) } }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.chorus.level * 100).toInt()}%", fx.chorus.level, 0f..2f, { v -> onUpdateFxRack { copy(chorus = chorus.copy(enabled = true, level = v)) } }, accent)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RotaryEditorBody(fx: FxRackState, accent: Color, onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HardwareChipToggle("FAST ROTOR", fx.rotarySpeaker.fastMode, { onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, fastMode = !rotarySpeaker.fastMode, brake = false)) } }, accent)
        HardwareChipToggle("BRAKE", fx.rotarySpeaker.brake, { onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, brake = !rotarySpeaker.brake)) } }, TacCoral)
    }
    HardwareParameterSlider("ROTOR SPEED", "${"%.1f".format(fx.rotarySpeaker.rateHz)} Hz", fx.rotarySpeaker.rateHz, 0.5f..9f, { v -> onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, rateHz = v)) } }, accent)
    HardwareParameterSlider("DOPPLER DEPTH", "${(fx.rotarySpeaker.depth * 100).toInt()}%", fx.rotarySpeaker.depth, 0f..1f, { v -> onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, depth = v)) } }, accent)
    HardwareParameterSlider("CROSSOVER", "${fx.rotarySpeaker.crossoverHz.toInt()} Hz", fx.rotarySpeaker.crossoverHz, 400f..1600f, { v -> onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, crossoverHz = v)) } }, accent)
    HardwareParameterSlider("HORN BALANCE", "${(fx.rotarySpeaker.hornBalance * 100).toInt()}%", fx.rotarySpeaker.hornBalance, 0f..1f, { v -> onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, hornBalance = v)) } }, accent)
    HardwareParameterSlider("CAB DRIVE", "${(fx.rotarySpeaker.cabinetDrive * 100).toInt()}%", fx.rotarySpeaker.cabinetDrive, 0f..1f, { v -> onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, cabinetDrive = v)) } }, accent)
    HardwareParameterSlider("MIX", "${(fx.rotarySpeaker.mix * 100).toInt()}%", fx.rotarySpeaker.mix, 0f..1f, { v -> onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, mix = v)) } }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.rotarySpeaker.level * 100).toInt()}%", fx.rotarySpeaker.level, 0f..2f, { v -> onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, level = v)) } }, accent)
}

@Composable
private fun EchoEditorBody(fx: FxRackState, accent: Color, onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit) {
    HardwareChipToggle("REVERSE DELAY", fx.echo.reverseDelay, { onUpdateFxRack { copy(echo = echo.copy(enabled = true, reverseDelay = !echo.reverseDelay)) } }, MagentaFx, Modifier.testTag("fx_echo_reverse"))
    HardwareParameterSlider("ECHO TIME", "${fx.echo.delayMs.toInt()} ms", fx.echo.delayMs, 50f..1000f, { v -> onUpdateFxRack { copy(echo = echo.copy(enabled = true, delayMs = v)) } }, accent)
    HardwareParameterSlider("FEEDBACK", "${(fx.echo.feedback * 100).toInt()}%", fx.echo.feedback, 0f..0.88f, { v -> onUpdateFxRack { copy(echo = echo.copy(enabled = true, feedback = v)) } }, accent)
    HardwareParameterSlider("HF DAMPING", "${(fx.echo.damping * 100).toInt()}%", fx.echo.damping, 0f..0.95f, { v -> onUpdateFxRack { copy(echo = echo.copy(enabled = true, damping = v)) } }, accent)
    HardwareParameterSlider("WOW & FLUTTER", "${(fx.echo.wowFlutter * 100).toInt()}%", fx.echo.wowFlutter, 0f..1f, { v -> onUpdateFxRack { copy(echo = echo.copy(enabled = true, wowFlutter = v)) } }, accent)
    HardwareParameterSlider("TAPE SAT", "${(fx.echo.saturation * 100).toInt()}%", fx.echo.saturation, 0f..1f, { v -> onUpdateFxRack { copy(echo = echo.copy(enabled = true, saturation = v)) } }, accent)
    HardwareParameterSlider("MIX", "${(fx.echo.mix * 100).toInt()}%", fx.echo.mix, 0f..1f, { v -> onUpdateFxRack { copy(echo = echo.copy(enabled = true, mix = v)) } }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.echo.level * 100).toInt()}%", fx.echo.level, 0f..2f, { v -> onUpdateFxRack { copy(echo = echo.copy(enabled = true, level = v)) } }, accent)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DelayEditorBody(fx: FxRackState, accent: Color, onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HardwareChipToggle("REVERSE DELAY", fx.delay.reverseDelay, { onUpdateFxRack { copy(delay = delay.copy(enabled = true, reverseDelay = !delay.reverseDelay)) } }, MagentaFx, Modifier.testTag("fx_delay_reverse"))
        HardwareChipToggle("BPM SYNC", fx.delay.bpmSync, { onUpdateFxRack { copy(delay = delay.copy(enabled = true, bpmSync = !delay.bpmSync)) } }, accent)
        DelayNoteDivision.entries.forEach { div ->
            HardwareChipToggle(div.label, fx.delay.division == div, { onUpdateFxRack { copy(delay = delay.copy(enabled = true, bpmSync = div != DelayNoteDivision.FREE_MS, division = div)) } }, accent)
        }
    }
    HardwareParameterSlider("DELAY TIME", "${fx.delay.delayMs.toInt()} ms", fx.delay.delayMs, 20f..1200f, { v -> onUpdateFxRack { copy(delay = delay.copy(enabled = true, delayMs = v)) } }, accent, enabled = !fx.delay.bpmSync)
    HardwareParameterSlider("FEEDBACK", "${(fx.delay.feedback * 100).toInt()}%", fx.delay.feedback, 0f..0.9f, { v -> onUpdateFxRack { copy(delay = delay.copy(enabled = true, feedback = v)) } }, accent)
    HardwareParameterSlider("HIGH-PASS CUT", "${fx.delay.highPassHz.toInt()} Hz", fx.delay.highPassHz, 40f..1000f, { v -> onUpdateFxRack { copy(delay = delay.copy(enabled = true, highPassHz = v)) } }, accent)
    HardwareParameterSlider("LOW-PASS CUT", "${fx.delay.lowPassHz.toInt()} Hz", fx.delay.lowPassHz, 1200f..14000f, { v -> onUpdateFxRack { copy(delay = delay.copy(enabled = true, lowPassHz = v)) } }, accent)
    HardwareParameterSlider("TAP SPREAD", "${(fx.delay.stereoSpread * 100).toInt()}%", fx.delay.stereoSpread, 0f..1f, { v -> onUpdateFxRack { copy(delay = delay.copy(enabled = true, stereoSpread = v)) } }, accent)
    HardwareParameterSlider("MIX", "${(fx.delay.mix * 100).toInt()}%", fx.delay.mix, 0f..1f, { v -> onUpdateFxRack { copy(delay = delay.copy(enabled = true, mix = v)) } }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.delay.level * 100).toInt()}%", fx.delay.level, 0f..2f, { v -> onUpdateFxRack { copy(delay = delay.copy(enabled = true, level = v)) } }, accent)
}

@Composable
private fun AnalogDelayEditorBody(fx: FxRackState, accent: Color, onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit) {
    HardwareChipToggle("REVERSE DELAY", fx.analogDelay.reverseDelay, { onUpdateFxRack { copy(analogDelay = analogDelay.copy(enabled = true, reverseDelay = !analogDelay.reverseDelay)) } }, MagentaFx, Modifier.testTag("fx_analog_delay_reverse"))
    HardwareParameterSlider("BBD TIME", "${fx.analogDelay.delayMs.toInt()} ms", fx.analogDelay.delayMs, 30f..1000f, { v -> onUpdateFxRack { copy(analogDelay = analogDelay.copy(enabled = true, delayMs = v)) } }, accent)
    HardwareParameterSlider("FEEDBACK", "${(fx.analogDelay.feedback * 100).toInt()}%", fx.analogDelay.feedback, 0f..0.92f, { v -> onUpdateFxRack { copy(analogDelay = analogDelay.copy(enabled = true, feedback = v)) } }, accent)
    HardwareParameterSlider("WARMTH LPF", "${fx.analogDelay.warmthHz.toInt()} Hz", fx.analogDelay.warmthHz, 600f..6500f, { v -> onUpdateFxRack { copy(analogDelay = analogDelay.copy(enabled = true, warmthHz = v)) } }, accent)
    HardwareParameterSlider("COMPANDER SAT", "${(fx.analogDelay.saturation * 100).toInt()}%", fx.analogDelay.saturation, 0f..1f, { v -> onUpdateFxRack { copy(analogDelay = analogDelay.copy(enabled = true, saturation = v)) } }, accent)
    HardwareParameterSlider("CLOCK MOD", "${(fx.analogDelay.modDepth * 100).toInt()}%", fx.analogDelay.modDepth, 0f..1f, { v -> onUpdateFxRack { copy(analogDelay = analogDelay.copy(enabled = true, modDepth = v)) } }, accent)
    HardwareParameterSlider("MOD RATE", "${"%.2f".format(fx.analogDelay.modRateHz)} Hz", fx.analogDelay.modRateHz, 0.1f..4f, { v -> onUpdateFxRack { copy(analogDelay = analogDelay.copy(enabled = true, modRateHz = v)) } }, accent)
    HardwareParameterSlider("MIX", "${(fx.analogDelay.mix * 100).toInt()}%", fx.analogDelay.mix, 0f..1f, { v -> onUpdateFxRack { copy(analogDelay = analogDelay.copy(enabled = true, mix = v)) } }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.analogDelay.level * 100).toInt()}%", fx.analogDelay.level, 0f..2f, { v -> onUpdateFxRack { copy(analogDelay = analogDelay.copy(enabled = true, level = v)) } }, accent)
}

@Composable
private fun ReverbEditorBody(fx: FxRackState, accent: Color, onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit) {
    HardwareParameterSlider("ROOM SIZE", "${(fx.reverb.roomSize * 100).toInt()}%", fx.reverb.roomSize, 0.1f..0.98f, { v -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = true, roomSize = v)) } }, accent)
    HardwareParameterSlider("PRE-DELAY", "${fx.reverb.preDelayMs.toInt()} ms", fx.reverb.preDelayMs, 0f..80f, { v -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = true, preDelayMs = v)) } }, accent)
    HardwareParameterSlider("EARLY REFL", "${(fx.reverb.earlyReflections * 100).toInt()}%", fx.reverb.earlyReflections, 0f..1f, { v -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = true, earlyReflections = v)) } }, accent)
    HardwareParameterSlider("HF DAMPING", "${(fx.reverb.damping * 100).toInt()}%", fx.reverb.damping, 0f..0.95f, { v -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = true, damping = v)) } }, accent)
    HardwareParameterSlider("LOW-CUT HPF", "${fx.reverb.lowCutHz.toInt()} Hz", fx.reverb.lowCutHz, 20f..500f, { v -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = true, lowCutHz = v)) } }, accent)
    HardwareParameterSlider("STEREO WIDTH", "${(fx.reverb.stereoWidth * 100).toInt()}%", fx.reverb.stereoWidth, 0f..1.4f, { v -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = true, stereoWidth = v)) } }, accent)
    HardwareParameterSlider("MIX", "${(fx.reverb.mix * 100).toInt()}%", fx.reverb.mix, 0f..1f, { v -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = true, mix = v)) } }, accent)
    HardwareParameterSlider("LEVEL GAIN", "${(fx.reverb.level * 100).toInt()}%", fx.reverb.level, 0f..2f, { v -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = true, level = v)) } }, accent)
}
