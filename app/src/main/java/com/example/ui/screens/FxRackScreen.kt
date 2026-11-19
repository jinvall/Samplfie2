package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.audio.PlaybackState
import com.example.dsp.FxPreset
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
import com.example.ui.theme.TacCoral
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletMod

@Composable
fun FxRackScreen(
    fxRack: FxRackState,
    playbackState: PlaybackState,
    hasAudio: Boolean,
    onTogglePlay: () -> Unit,
    onToggleMasterBypass: () -> Unit,
    onApplyPreset: (FxPreset) -> Unit,
    onUpdateFxRack: (FxRackState.() -> FxRackState) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Master FX Bar & Live Audition Review Button
        HardwarePanel(borderColor = if (fxRack.masterBypass) AmberHot else NeonCyan.copy(alpha = 0.5f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "12-MODULE LIVE DSP RACK",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "${fxRack.activeCount}/12 ACTIVE • ${if (fxRack.masterBypass) "A/B DRY BYPASS" else "FX ENGAGED"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fxRack.masterBypass) AmberHot else NeonCyan
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HardwareChipToggle(
                        label = "BYPASS",
                        selected = fxRack.masterBypass,
                        onClick = onToggleMasterBypass,
                        accentColor = AmberHot,
                        modifier = Modifier.testTag("fx_master_bypass")
                    )
                    Button(
                        onClick = onTogglePlay,
                        enabled = hasAudio,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (playbackState.isPlaying) AmberHot else EmeraldSignal
                        ),
                        modifier = Modifier.testTag("fx_audition_play")
                    ) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Audition FX",
                            tint = ObsidianChassis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FxPreset.entries.forEach { preset ->
                    HardwareChipToggle(
                        label = preset.label,
                        selected = false,
                        onClick = { onApplyPreset(preset) },
                        accentColor = VioletMod,
                        modifier = Modifier.testTag("fx_preset_${preset.name.lowercase()}")
                    )
                }
            }
        }

        // 1. Noise Removal
        FxModuleCard(
            title = "1. NOISE REMOVAL",
            subtitle = "80Hz Rumble HPF + Soft-Knee Gate",
            enabled = fxRack.noiseRemoval.enabled,
            accentColor = EmeraldSignal,
            testTag = "fx_toggle_noise_removal",
            onToggle = { onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = it)) } }
        ) {
            HardwareParameterSlider(
                label = "THRESHOLD",
                valueText = "${fxRack.noiseRemoval.thresholdDb.toInt()} dB",
                value = fxRack.noiseRemoval.thresholdDb,
                valueRange = -60f..-10f,
                accentColor = EmeraldSignal,
                onValueChange = { v -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, thresholdDb = v)) } }
            )
            HardwareParameterSlider(
                label = "REDUCTION",
                valueText = "${fxRack.noiseRemoval.reductionDb.toInt()} dB",
                value = fxRack.noiseRemoval.reductionDb,
                valueRange = 6f..48f,
                accentColor = EmeraldSignal,
                onValueChange = { v -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, reductionDb = v)) } }
            )
            HardwareChipToggle(
                label = "80Hz HIGH-PASS RUMBLE FILTER",
                selected = fxRack.noiseRemoval.highPass80Hz,
                onClick = { onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, highPass80Hz = !noiseRemoval.highPass80Hz)) } },
                accentColor = EmeraldSignal
            )
        }

        // 2. Pitch & Speed
        FxModuleCard(
            title = "2. PITCH & SPEED",
            subtitle = "WSOLA Time-Stretch + Vinyl Link",
            enabled = fxRack.pitchSpeed.enabled,
            accentColor = NeonCyan,
            testTag = "fx_toggle_pitch_speed",
            onToggle = { onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = it)) } }
        ) {
            HardwareParameterSlider(
                label = "PITCH SHIFT",
                valueText = "${if (fxRack.pitchSpeed.pitchSemitones >= 0) "+" else ""}${"%.1f".format(fxRack.pitchSpeed.pitchSemitones)} st",
                value = fxRack.pitchSpeed.pitchSemitones,
                valueRange = -12f..12f,
                enabled = !fxRack.pitchSpeed.vinylLink,
                accentColor = NeonCyan,
                onValueChange = { v -> onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, pitchSemitones = v)) } }
            )
            HardwareParameterSlider(
                label = "TEMPO / SPEED",
                valueText = "${"%.2f".format(fxRack.pitchSpeed.speedFactor)}x",
                value = fxRack.pitchSpeed.speedFactor,
                valueRange = 0.5f..2.0f,
                accentColor = NeonCyan,
                onValueChange = { v -> onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, speedFactor = v)) } }
            )
            HardwareChipToggle(
                label = "VINYL TURNTABLE LINK (PITCH = SPEED)",
                selected = fxRack.pitchSpeed.vinylLink,
                onClick = { onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, vinylLink = !pitchSpeed.vinylLink)) } },
                accentColor = NeonCyan
            )
        }

        // 3. Gain & Limiter
        FxModuleCard(
            title = "3. GAIN & DYNAMICS",
            subtitle = "Boost + Peak Normalize + Soft Limiter",
            enabled = fxRack.gain.enabled,
            accentColor = EmeraldSignal,
            testTag = "fx_toggle_gain",
            onToggle = { onUpdateFxRack { copy(gain = gain.copy(enabled = it)) } }
        ) {
            HardwareParameterSlider(
                label = "GAIN",
                valueText = "${if (fxRack.gain.gainDb >= 0) "+" else ""}${"%.1f".format(fxRack.gain.gainDb)} dB",
                value = fxRack.gain.gainDb,
                valueRange = -24f..24f,
                accentColor = EmeraldSignal,
                onValueChange = { v -> onUpdateFxRack { copy(gain = gain.copy(enabled = true, gainDb = v)) } }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HardwareChipToggle(
                    label = "NORMALIZE -0.3dB",
                    selected = fxRack.gain.normalize,
                    onClick = { onUpdateFxRack { copy(gain = gain.copy(enabled = true, normalize = !gain.normalize)) } },
                    accentColor = EmeraldSignal
                )
                HardwareChipToggle(
                    label = "SOFT LIMITER",
                    selected = fxRack.gain.softLimiter,
                    onClick = { onUpdateFxRack { copy(gain = gain.copy(enabled = true, softLimiter = !gain.softLimiter)) } },
                    accentColor = AmberHot
                )
            }
        }

        // 4. Overdrive
        FxModuleCard(
            title = "4. OVERDRIVE",
            subtitle = "Asymmetric Tube Waveshaper",
            enabled = fxRack.overdrive.enabled,
            accentColor = TacCoral,
            testTag = "fx_toggle_overdrive",
            onToggle = { onUpdateFxRack { copy(overdrive = overdrive.copy(enabled = it)) } }
        ) {
            HardwareParameterSlider(
                label = "DRIVE",
                valueText = "${"%.1f".format(fxRack.overdrive.drive)}x",
                value = fxRack.overdrive.drive,
                valueRange = 1f..20f,
                accentColor = TacCoral,
                onValueChange = { v -> onUpdateFxRack { copy(overdrive = overdrive.copy(enabled = true, drive = v)) } }
            )
            HardwareParameterSlider(
                label = "TONE LPF",
                valueText = "${fxRack.overdrive.toneHz.toInt()} Hz",
                value = fxRack.overdrive.toneHz,
                valueRange = 800f..12000f,
                accentColor = TacCoral,
                onValueChange = { v -> onUpdateFxRack { copy(overdrive = overdrive.copy(enabled = true, toneHz = v)) } }
            )
            HardwareParameterSlider(
                label = "MIX",
                valueText = "${(fxRack.overdrive.mix * 100).toInt()}%",
                value = fxRack.overdrive.mix,
                valueRange = 0f..1f,
                accentColor = TacCoral,
                onValueChange = { v -> onUpdateFxRack { copy(overdrive = overdrive.copy(enabled = true, mix = v)) } }
            )
        }

        // 5. Wah
        FxModuleCard(
            title = "5. AUTO / LFO WAH",
            subtitle = "Resonant State-Variable Bandpass",
            enabled = fxRack.wah.enabled,
            accentColor = AmberHot,
            testTag = "fx_toggle_wah",
            onToggle = { onUpdateFxRack { copy(wah = wah.copy(enabled = it)) } }
        ) {
            HardwareParameterSlider(
                label = "CENTER FREQ",
                valueText = "${fxRack.wah.centerFreqHz.toInt()} Hz",
                value = fxRack.wah.centerFreqHz,
                valueRange = 350f..2200f,
                accentColor = AmberHot,
                onValueChange = { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, centerFreqHz = v)) } }
            )
            HardwareParameterSlider(
                label = "RESONANCE Q",
                valueText = "${"%.1f".format(fxRack.wah.resonanceQ)}",
                value = fxRack.wah.resonanceQ,
                valueRange = 1.5f..8f,
                accentColor = AmberHot,
                onValueChange = { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, resonanceQ = v)) } }
            )
            HardwareParameterSlider(
                label = "LFO RATE",
                valueText = "${"%.1f".format(fxRack.wah.rateHz)} Hz",
                value = fxRack.wah.rateHz,
                valueRange = 0.2f..6f,
                accentColor = AmberHot,
                onValueChange = { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, rateHz = v)) } }
            )
            HardwareParameterSlider(
                label = "ENV SENSITIVITY",
                valueText = "${(fxRack.wah.envSensitivity * 100).toInt()}%",
                value = fxRack.wah.envSensitivity,
                valueRange = 0f..1f,
                accentColor = AmberHot,
                onValueChange = { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, envSensitivity = v)) } }
            )
        }

        // 6. Phaser
        FxModuleCard(
            title = "6. PHASER",
            subtitle = "4-Stage Cascaded All-Pass Network",
            enabled = fxRack.phaser.enabled,
            accentColor = VioletMod,
            testTag = "fx_toggle_phaser",
            onToggle = { onUpdateFxRack { copy(phaser = phaser.copy(enabled = it)) } }
        ) {
            HardwareParameterSlider(
                label = "RATE",
                valueText = "${"%.2f".format(fxRack.phaser.rateHz)} Hz",
                value = fxRack.phaser.rateHz,
                valueRange = 0.1f..5f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, rateHz = v)) } }
            )
            HardwareParameterSlider(
                label = "FEEDBACK",
                valueText = "${(fxRack.phaser.feedback * 100).toInt()}%",
                value = fxRack.phaser.feedback,
                valueRange = 0f..0.85f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, feedback = v)) } }
            )
            HardwareParameterSlider(
                label = "MIX",
                valueText = "${(fxRack.phaser.mix * 100).toInt()}%",
                value = fxRack.phaser.mix,
                valueRange = 0f..1f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, mix = v)) } }
            )
        }

        // 7. Flanger
        FxModuleCard(
            title = "7. FLANGER",
            subtitle = "Jet-Sweep Comb Delay",
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
                valueRange = -0.8f..0.8f,
                accentColor = MagentaFx,
                onValueChange = { v -> onUpdateFxRack { copy(flanger = flanger.copy(enabled = true, feedback = v)) } }
            )
        }

        // 8. Chorus
        FxModuleCard(
            title = "8. CHORUS",
            subtitle = "3-Voice Modulated Ensemble",
            enabled = fxRack.chorus.enabled,
            accentColor = NeonCyan,
            testTag = "fx_toggle_chorus",
            onToggle = { onUpdateFxRack { copy(chorus = chorus.copy(enabled = it)) } }
        ) {
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
            subtitle = "Dual-Band Leslie Horn + Drum",
            enabled = fxRack.rotarySpeaker.enabled,
            accentColor = AmberHot,
            testTag = "fx_toggle_rotary",
            onToggle = { onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = it)) } }
        ) {
            HardwareParameterSlider(
                label = "ROTOR SPEED",
                valueText = "${"%.1f".format(fxRack.rotarySpeaker.rateHz)} Hz",
                value = fxRack.rotarySpeaker.rateHz,
                valueRange = 0.5f..9f,
                accentColor = AmberHot,
                onValueChange = { v -> onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, rateHz = v)) } }
            )
            HardwareParameterSlider(
                label = "DEPTH",
                valueText = "${(fxRack.rotarySpeaker.depth * 100).toInt()}%",
                value = fxRack.rotarySpeaker.depth,
                valueRange = 0f..1f,
                accentColor = AmberHot,
                onValueChange = { v -> onUpdateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, depth = v)) } }
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
            subtitle = "Warm HF-Damped Feedback Echo",
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
                label = "TAPE DAMPING",
                valueText = "${(fxRack.echo.damping * 100).toInt()}%",
                value = fxRack.echo.damping,
                valueRange = 0f..0.9f,
                accentColor = NeonCyan,
                onValueChange = { v -> onUpdateFxRack { copy(echo = echo.copy(enabled = true, damping = v)) } }
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
            subtitle = "Rhythmic Cross-Tap Delay",
            enabled = fxRack.delay.enabled,
            accentColor = VioletMod,
            testTag = "fx_toggle_delay",
            onToggle = { onUpdateFxRack { copy(delay = delay.copy(enabled = it)) } }
        ) {
            HardwareParameterSlider(
                label = "DELAY TIME",
                valueText = "${fxRack.delay.delayMs.toInt()} ms",
                value = fxRack.delay.delayMs,
                valueRange = 20f..1200f,
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
            subtitle = "Schroeder 4-Comb + 2-AllPass",
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
                label = "DAMPING",
                valueText = "${(fxRack.reverb.damping * 100).toInt()}%",
                value = fxRack.reverb.damping,
                valueRange = 0f..0.9f,
                accentColor = EmeraldSignal,
                onValueChange = { v -> onUpdateFxRack { copy(reverb = reverb.copy(enabled = true, damping = v)) } }
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
}

@Composable
private fun FxModuleCard(
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
