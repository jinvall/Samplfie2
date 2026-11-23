package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.audio.PlaybackState
import com.example.dsp.FxPreset
import com.example.dsp.FxRackState
import com.example.dsp.WahMode
import com.example.ui.components.HardwareChipToggle
import com.example.ui.components.HardwarePanel
import com.example.ui.components.HardwareParameterSlider
import com.example.ui.theme.AmberHot
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianChassis
import com.example.ui.theme.TacCoral
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletMod

@OptIn(ExperimentalLayoutApi::class)
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "14-MODULE PRO DSP RACK",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "${fxRack.activeCount}/14 ACTIVE • ${if (fxRack.masterBypass) "A/B DRY BYPASS" else "FX ENGAGED"}",
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

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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

        // 1. Noise Removal (Expanded)
        FxModuleCard(
            title = "1. NOISE REMOVAL",
            subtitle = "Variable HPF + Attack/Release/Knee Gate + Level",
            enabled = fxRack.noiseRemoval.enabled,
            accentColor = EmeraldSignal,
            testTag = "fx_toggle_noise_removal",
            onToggle = { onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = it)) } }
        ) {
            HardwareChipToggle(
                label = "HIGH-PASS FILTER",
                selected = fxRack.noiseRemoval.highPass80Hz,
                onClick = { onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, highPass80Hz = !noiseRemoval.highPass80Hz)) } },
                accentColor = EmeraldSignal
            )
            Spacer(modifier = Modifier.height(4.dp))
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
            HardwareParameterSlider(
                label = "HPF CUTOFF",
                valueText = "${fxRack.noiseRemoval.highPassFreqHz.toInt()} Hz",
                value = fxRack.noiseRemoval.highPassFreqHz,
                valueRange = 30f..250f,
                enabled = fxRack.noiseRemoval.highPass80Hz,
                accentColor = EmeraldSignal,
                onValueChange = { v -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, highPass80Hz = true, highPassFreqHz = v)) } }
            )
            HardwareParameterSlider(
                label = "ATTACK",
                valueText = "${"%.1f".format(fxRack.noiseRemoval.attackMs)} ms",
                value = fxRack.noiseRemoval.attackMs,
                valueRange = 0.5f..25f,
                accentColor = EmeraldSignal,
                onValueChange = { v -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, attackMs = v)) } }
            )
            HardwareParameterSlider(
                label = "RELEASE",
                valueText = "${fxRack.noiseRemoval.releaseMs.toInt()} ms",
                value = fxRack.noiseRemoval.releaseMs,
                valueRange = 15f..350f,
                accentColor = EmeraldSignal,
                onValueChange = { v -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, releaseMs = v)) } }
            )
            HardwareParameterSlider(
                label = "KNEE WIDTH",
                valueText = "${fxRack.noiseRemoval.kneeWidthDb.toInt()} dB",
                value = fxRack.noiseRemoval.kneeWidthDb,
                valueRange = 0f..18f,
                accentColor = EmeraldSignal,
                onValueChange = { v -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, kneeWidthDb = v)) } }
            )
            HardwareParameterSlider(
                label = "LEVEL GAIN",
                valueText = "${(fxRack.noiseRemoval.level * 100).toInt()}%",
                value = fxRack.noiseRemoval.level,
                valueRange = 0f..2f,
                accentColor = EmeraldSignal,
                onValueChange = { v -> onUpdateFxRack { copy(noiseRemoval = noiseRemoval.copy(enabled = true, level = v)) } }
            )
        }

        // 2. Pitch & Speed (Expanded)
        FxModuleCard(
            title = "2. PITCH & SPEED",
            subtitle = "WSOLA Grain + Fine Cents + Vinyl Link + Level",
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
                label = "FINE DETUNE",
                valueText = "${fxRack.pitchSpeed.fineCents.toInt()} ct",
                value = fxRack.pitchSpeed.fineCents,
                valueRange = -50f..50f,
                enabled = !fxRack.pitchSpeed.vinylLink,
                accentColor = NeonCyan,
                onValueChange = { v -> onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, fineCents = v)) } }
            )
            HardwareParameterSlider(
                label = "TEMPO / SPEED",
                valueText = "${"%.2f".format(fxRack.pitchSpeed.speedFactor)}x",
                value = fxRack.pitchSpeed.speedFactor,
                valueRange = 0.5f..2.0f,
                accentColor = NeonCyan,
                onValueChange = { v -> onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, speedFactor = v)) } }
            )
            HardwareParameterSlider(
                label = "GRAIN SIZE",
                valueText = "${fxRack.pitchSpeed.grainSizeMs.toInt()} ms",
                value = fxRack.pitchSpeed.grainSizeMs,
                valueRange = 12f..80f,
                enabled = !fxRack.pitchSpeed.vinylLink,
                accentColor = NeonCyan,
                onValueChange = { v -> onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, grainSizeMs = v)) } }
            )
            HardwareChipToggle(
                label = "VINYL TURNTABLE LINK (PITCH = SPEED)",
                selected = fxRack.pitchSpeed.vinylLink,
                onClick = { onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, vinylLink = !pitchSpeed.vinylLink)) } },
                accentColor = NeonCyan
            )
            Spacer(modifier = Modifier.height(4.dp))
            HardwareParameterSlider(
                label = "LEVEL GAIN",
                valueText = "${(fxRack.pitchSpeed.level * 100).toInt()}%",
                value = fxRack.pitchSpeed.level,
                valueRange = 0f..2f,
                accentColor = NeonCyan,
                onValueChange = { v -> onUpdateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, level = v)) } }
            )
        }

        // 3. Gain & Dynamics (Expanded)
        FxModuleCard(
            title = "3. GAIN & DYNAMICS",
            subtitle = "Compressor + Limiter Ceiling + Stereo Width + Level",
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
            HardwareParameterSlider(
                label = "COMP RATIO",
                valueText = "${"%.1f".format(fxRack.gain.compressionRatio)}:1",
                value = fxRack.gain.compressionRatio,
                valueRange = 1f..6f,
                accentColor = EmeraldSignal,
                onValueChange = { v -> onUpdateFxRack { copy(gain = gain.copy(enabled = true, compressionRatio = v)) } }
            )
            HardwareParameterSlider(
                label = "LIMITER CEILING",
                valueText = "${"%.1f".format(fxRack.gain.limiterCeilingDb)} dB",
                value = fxRack.gain.limiterCeilingDb,
                valueRange = -6f..0f,
                enabled = fxRack.gain.softLimiter,
                accentColor = AmberHot,
                onValueChange = { v -> onUpdateFxRack { copy(gain = gain.copy(enabled = true, softLimiter = true, limiterCeilingDb = v)) } }
            )
            HardwareParameterSlider(
                label = "STEREO WIDTH",
                valueText = "${(fxRack.gain.stereoWidth * 100).toInt()}%",
                value = fxRack.gain.stereoWidth,
                valueRange = 0f..1.8f,
                accentColor = EmeraldSignal,
                onValueChange = { v -> onUpdateFxRack { copy(gain = gain.copy(enabled = true, stereoWidth = v)) } }
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HardwareChipToggle(
                    label = "NORMALIZE",
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
            Spacer(modifier = Modifier.height(4.dp))
            HardwareParameterSlider(
                label = "LEVEL GAIN",
                valueText = "${(fxRack.gain.level * 100).toInt()}%",
                value = fxRack.gain.level,
                valueRange = 0f..2f,
                accentColor = EmeraldSignal,
                onValueChange = { v -> onUpdateFxRack { copy(gain = gain.copy(enabled = true, level = v)) } }
            )
        }

        // 4. Octave (-1 Oct / +1 Oct)
        FxModuleCard(
            title = "4. OCTAVE (-1 / +1 OCT)",
            subtitle = "Warm Sub Divider (-1 Oct) + Upper Octave Harmonic + Level",
            enabled = fxRack.octave.enabled,
            accentColor = VioletMod,
            testTag = "fx_toggle_octave",
            onToggle = { onUpdateFxRack { copy(octave = octave.copy(enabled = it)) } }
        ) {
            HardwareParameterSlider(
                label = "SUB (-1 OCTAVE)",
                valueText = "${(fxRack.octave.subOctaveMix * 100).toInt()}%",
                value = fxRack.octave.subOctaveMix,
                valueRange = 0f..1f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateFxRack { copy(octave = octave.copy(enabled = true, subOctaveMix = v)) } }
            )
            HardwareParameterSlider(
                label = "UPPER (+1 OCTAVE)",
                valueText = "${(fxRack.octave.octaveUpMix * 100).toInt()}%",
                value = fxRack.octave.octaveUpMix,
                valueRange = 0f..1f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateFxRack { copy(octave = octave.copy(enabled = true, octaveUpMix = v)) } }
            )
            HardwareParameterSlider(
                label = "SUB LPF TONE",
                valueText = "${fxRack.octave.subToneHz.toInt()} Hz",
                value = fxRack.octave.subToneHz,
                valueRange = 250f..2400f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateFxRack { copy(octave = octave.copy(enabled = true, subToneHz = v)) } }
            )
            HardwareParameterSlider(
                label = "DRY BLEND",
                valueText = "${(fxRack.octave.dryMix * 100).toInt()}%",
                value = fxRack.octave.dryMix,
                valueRange = 0f..1f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateFxRack { copy(octave = octave.copy(enabled = true, dryMix = v)) } }
            )
            HardwareParameterSlider(
                label = "LEVEL GAIN",
                valueText = "${(fxRack.octave.level * 100).toInt()}%",
                value = fxRack.octave.level,
                valueRange = 0f..2f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateFxRack { copy(octave = octave.copy(enabled = true, level = v)) } }
            )
        }

        // 5. Overdrive (Expanded)
        FxModuleCard(
            title = "5. OVERDRIVE",
            subtitle = "Pre-HPF + Asymmetric Tube Bias + Tone LPF + Level",
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
                label = "TUBE ASYMMETRY",
                valueText = "${(fxRack.overdrive.asymmetry * 100).toInt()}%",
                value = fxRack.overdrive.asymmetry,
                valueRange = 0f..0.8f,
                accentColor = TacCoral,
                onValueChange = { v -> onUpdateFxRack { copy(overdrive = overdrive.copy(enabled = true, asymmetry = v)) } }
            )
            HardwareParameterSlider(
                label = "PRE-HPF CUTOFF",
                valueText = "${fxRack.overdrive.preHighPassHz.toInt()} Hz",
                value = fxRack.overdrive.preHighPassHz,
                valueRange = 30f..450f,
                accentColor = TacCoral,
                onValueChange = { v -> onUpdateFxRack { copy(overdrive = overdrive.copy(enabled = true, preHighPassHz = v)) } }
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
            HardwareParameterSlider(
                label = "LEVEL GAIN",
                valueText = "${(fxRack.overdrive.level * 100).toInt()}%",
                value = fxRack.overdrive.level,
                valueRange = 0f..2f,
                accentColor = TacCoral,
                onValueChange = { v -> onUpdateFxRack { copy(overdrive = overdrive.copy(enabled = true, level = v)) } }
            )
        }

        // 6. Wah (Expanded with Mode Switch + Manual Pedal + Level)
        FxModuleCard(
            title = "6. AUTO / LFO / PEDAL WAH",
            subtitle = "State-Variable Bandpass · 3 Sweep Modes + Level",
            enabled = fxRack.wah.enabled,
            accentColor = AmberHot,
            testTag = "fx_toggle_wah",
            onToggle = { onUpdateFxRack { copy(wah = wah.copy(enabled = it)) } }
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WahMode.entries.forEach { m ->
                    HardwareChipToggle(
                        label = m.label,
                        selected = fxRack.wah.mode == m,
                        onClick = { onUpdateFxRack { copy(wah = wah.copy(enabled = true, mode = m)) } },
                        accentColor = AmberHot
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            HardwareParameterSlider(
                label = "CENTER FREQ",
                valueText = "${fxRack.wah.centerFreqHz.toInt()} Hz",
                value = fxRack.wah.centerFreqHz,
                valueRange = 350f..2200f,
                accentColor = AmberHot,
                onValueChange = { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, centerFreqHz = v)) } }
            )
            if (fxRack.wah.mode == WahMode.MANUAL_PEDAL) {
                HardwareParameterSlider(
                    label = "PEDAL SWEEP",
                    valueText = "${(fxRack.wah.manualPedalPos * 100).toInt()}%",
                    value = fxRack.wah.manualPedalPos,
                    valueRange = 0f..1f,
                    accentColor = AmberHot,
                    onValueChange = { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, manualPedalPos = v)) } }
                )
            }
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
            HardwareParameterSlider(
                label = "MIX",
                valueText = "${(fxRack.wah.mix * 100).toInt()}%",
                value = fxRack.wah.mix,
                valueRange = 0f..1f,
                accentColor = AmberHot,
                onValueChange = { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, mix = v)) } }
            )
            HardwareParameterSlider(
                label = "LEVEL GAIN",
                valueText = "${(fxRack.wah.level * 100).toInt()}%",
                value = fxRack.wah.level,
                valueRange = 0f..2f,
                accentColor = AmberHot,
                onValueChange = { v -> onUpdateFxRack { copy(wah = wah.copy(enabled = true, level = v)) } }
            )
        }

        // 7. Phaser (Expanded with 4/6/8 Stages + Center Freq + Stereo Offset + Level)
        FxModuleCard(
            title = "7. PHASER",
            subtitle = "4/6/8-Stage Stereo All-Pass Network + Level",
            enabled = fxRack.phaser.enabled,
            accentColor = VioletMod,
            testTag = "fx_toggle_phaser",
            onToggle = { onUpdateFxRack { copy(phaser = phaser.copy(enabled = it)) } }
        ) {
            Text("ALL-PASS STAGES", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 6, 8).forEach { st ->
                    HardwareChipToggle(
                        label = "$st-STAGE",
                        selected = fxRack.phaser.stages == st,
                        onClick = { onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, stages = st)) } },
                        accentColor = VioletMod
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            HardwareParameterSlider(
                label = "RATE",
                valueText = "${"%.2f".format(fxRack.phaser.rateHz)} Hz",
                value = fxRack.phaser.rateHz,
                valueRange = 0.1f..5f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, rateHz = v)) } }
            )
            HardwareParameterSlider(
                label = "DEPTH",
                valueText = "${(fxRack.phaser.depth * 100).toInt()}%",
                value = fxRack.phaser.depth,
                valueRange = 0f..1f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, depth = v)) } }
            )
            HardwareParameterSlider(
                label = "CENTER FREQ",
                valueText = "${fxRack.phaser.centerFreqHz.toInt()} Hz",
                value = fxRack.phaser.centerFreqHz,
                valueRange = 300f..2500f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, centerFreqHz = v)) } }
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
                label = "STEREO PHASE OFFSET",
                valueText = "${(fxRack.phaser.stereoPhaseOffset * 100).toInt()}%",
                value = fxRack.phaser.stereoPhaseOffset,
                valueRange = 0f..1f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, stereoPhaseOffset = v)) } }
            )
            HardwareParameterSlider(
                label = "MIX",
                valueText = "${(fxRack.phaser.mix * 100).toInt()}%",
                value = fxRack.phaser.mix,
                valueRange = 0f..1f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, mix = v)) } }
            )
            HardwareParameterSlider(
                label = "LEVEL GAIN",
                valueText = "${(fxRack.phaser.level * 100).toInt()}%",
                value = fxRack.phaser.level,
                valueRange = 0f..2f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateFxRack { copy(phaser = phaser.copy(enabled = true, level = v)) } }
            )
        }

        // 8–14. Spatial, Delay (Echo, Digital, Analog BBD + Reverse Delay) & Modulation Cards
        FxRackSpatialAndModCards(fxRack = fxRack, onUpdateFxRack = onUpdateFxRack)
    }
}
