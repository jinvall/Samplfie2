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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.PlaybackState
import com.example.dsp.FxPreset
import com.example.dsp.FxRackState
import com.example.ui.components.HardwareChipToggle
import com.example.ui.components.HardwarePanel
import com.example.ui.theme.AmberHot
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.MagentaFx
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianChassis
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletMod

private enum class FxRackGroupFilter(val label: String) {
    ALL("ALL (16)"),
    FILTERS("FILTER LINE (3)"),
    FX_ONLY("FX CHAIN (13)")
}

/**
 * LMMS-Inspired DSP FX Rack & Dedicated Filter Line (13 FX + 3 Filters):
 * Compact fixed-height module strips prevent scrolling jitter on live updates.
 * Tap any FX or Filter module to open its full-scope LMMS parameter editor dialog.
 */
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
    var editingModule by remember { mutableStateOf<FxModuleId?>(null) }
    var activeGroup by remember { mutableStateOf(FxRackGroupFilter.ALL) }

    editingModule?.let { modId ->
        FxModuleEditorDialog(
            moduleId = modId,
            fxRack = fxRack,
            onUpdateFxRack = onUpdateFxRack,
            onDismiss = { editingModule = null }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Master FX Header & Presets Bar
        HardwarePanel(borderColor = if (fxRack.masterBypass) AmberHot else NeonCyan.copy(alpha = 0.55f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "13 FX + 3 FILTERS PRO DSP RACK",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "FX ${fxRack.fxActiveCount}/13 · FLT ${fxRack.filterActiveCount}/3 • ${if (fxRack.masterBypass) "A/B BYPASS" else "LMMS TAP TO EDIT"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fxRack.masterBypass) AmberHot else NeonCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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

            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
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

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FxRackGroupFilter.entries.forEach { grp ->
                    HardwareChipToggle(
                        label = grp.label,
                        selected = activeGroup == grp,
                        onClick = { activeGroup = grp },
                        accentColor = if (grp == FxRackGroupFilter.FILTERS) NeonCyan else MagentaFx,
                        modifier = Modifier.testTag("fx_group_${grp.name.lowercase()}")
                    )
                }
            }
        }

        // DEDICATED FILTER LINE (3 FILTERS: BIQUAD + WAH + PHASER)
        if (activeGroup == FxRackGroupFilter.ALL || activeGroup == FxRackGroupFilter.FILTERS) {
            HardwarePanel(borderColor = NeonCyan.copy(alpha = 0.65f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "FILTER LINE · DEDICATED TONE & SWEEP BUS (${fxRack.filterActiveCount}/3)",
                        style = MaterialTheme.typography.labelLarge,
                        color = NeonCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LmmsFxTileCard(
                        moduleId = FxModuleId.BIQUAD_FILTER,
                        subtitle = "${fxRack.biquadFilter.mode.label} · ${fxRack.biquadFilter.cutoffHz.toInt()} Hz · Q ${"%.1f".format(fxRack.biquadFilter.resonanceQ)}",
                        levelLabel = "LVL ${(fxRack.biquadFilter.level * 100).toInt()}%",
                        enabled = fxRack.biquadFilter.enabled,
                        accentColor = moduleAccentColor(FxModuleId.BIQUAD_FILTER),
                        onToggle = { toggleModuleById(FxModuleId.BIQUAD_FILTER, it, onUpdateFxRack) },
                        onOpenEditor = { editingModule = FxModuleId.BIQUAD_FILTER }
                    )
                    LmmsFxTileCard(
                        moduleId = FxModuleId.WAH,
                        subtitle = "${fxRack.wah.mode.label} · ${fxRack.wah.centerFreqHz.toInt()} Hz · Q ${"%.1f".format(fxRack.wah.resonanceQ)}",
                        levelLabel = "LVL ${(fxRack.wah.level * 100).toInt()}%",
                        enabled = fxRack.wah.enabled,
                        accentColor = moduleAccentColor(FxModuleId.WAH),
                        onToggle = { toggleModuleById(FxModuleId.WAH, it, onUpdateFxRack) },
                        onOpenEditor = { editingModule = FxModuleId.WAH }
                    )
                    LmmsFxTileCard(
                        moduleId = FxModuleId.PHASER,
                        subtitle = "${fxRack.phaser.stages}-Stage · ${"%.2f".format(fxRack.phaser.rateHz)} Hz · FB ${(fxRack.phaser.feedback * 100).toInt()}%",
                        levelLabel = "LVL ${(fxRack.phaser.level * 100).toInt()}%",
                        enabled = fxRack.phaser.enabled,
                        accentColor = moduleAccentColor(FxModuleId.PHASER),
                        onToggle = { toggleModuleById(FxModuleId.PHASER, it, onUpdateFxRack) },
                        onOpenEditor = { editingModule = FxModuleId.PHASER }
                    )
                }
            }
        }

        // 13-MODULE DSP EFFECTS RACK
        if (activeGroup == FxRackGroupFilter.ALL || activeGroup == FxRackGroupFilter.FX_ONLY) {
            Text(
                text = "DSP EFFECTS CHAIN (${fxRack.fxActiveCount}/13 ACTIVE) — TAP ANY MODULE TO OPEN EDITOR",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            val fxRows = listOf(
                Triple(
                    FxModuleId.NOISE_REMOVAL,
                    "Thresh ${fxRack.noiseRemoval.thresholdDb.toInt()} dB · Red ${fxRack.noiseRemoval.reductionDb.toInt()} dB · HPF ${if (fxRack.noiseRemoval.highPass80Hz) "${fxRack.noiseRemoval.highPassFreqHz.toInt()}Hz" else "OFF"}",
                    "LVL ${(fxRack.noiseRemoval.level * 100).toInt()}%"
                ),
                Triple(
                    FxModuleId.PITCH_SPEED,
                    "Pitch ${if (fxRack.pitchSpeed.pitchSemitones >= 0) "+" else ""}${"%.1f".format(fxRack.pitchSpeed.pitchSemitones)} st · Speed ${"%.2f".format(fxRack.pitchSpeed.speedFactor)}x",
                    "LVL ${(fxRack.pitchSpeed.level * 100).toInt()}%"
                ),
                Triple(
                    FxModuleId.GAIN,
                    "Gain ${if (fxRack.gain.gainDb >= 0) "+" else ""}${"%.1f".format(fxRack.gain.gainDb)} dB · Comp ${"%.1f".format(fxRack.gain.compressionRatio)}:1 · Width ${(fxRack.gain.stereoWidth * 100).toInt()}%",
                    "LVL ${(fxRack.gain.level * 100).toInt()}%"
                ),
                Triple(
                    FxModuleId.OCTAVE,
                    "Sub ${(fxRack.octave.subOctaveMix * 100).toInt()}% · +1 Oct ${(fxRack.octave.octaveUpMix * 100).toInt()}% · Dry ${(fxRack.octave.dryMix * 100).toInt()}%",
                    "LVL ${(fxRack.octave.level * 100).toInt()}%"
                ),
                Triple(
                    FxModuleId.OVERDRIVE,
                    "Drive ${"%.1f".format(fxRack.overdrive.drive)}x · Asym ${(fxRack.overdrive.asymmetry * 100).toInt()}% · LPF ${fxRack.overdrive.toneHz.toInt()} Hz",
                    "LVL ${(fxRack.overdrive.level * 100).toInt()}%"
                ),
                Triple(
                    FxModuleId.DISTORTION,
                    "${fxRack.distortion.curve.label} · Drive ${"%.1f".format(fxRack.distortion.drive)}x · Tone ${fxRack.distortion.toneHz.toInt()} Hz",
                    "LVL ${(fxRack.distortion.level * 100).toInt()}%"
                ),
                Triple(
                    FxModuleId.FLANGER,
                    "Rate ${"%.2f".format(fxRack.flanger.rateHz)} Hz · Depth ${"%.1f".format(fxRack.flanger.depthMs)} ms · Mix ${(fxRack.flanger.mix * 100).toInt()}%",
                    "LVL ${(fxRack.flanger.level * 100).toInt()}%"
                ),
                Triple(
                    FxModuleId.CHORUS,
                    "${fxRack.chorus.voices} Voices · ${"%.2f".format(fxRack.chorus.rateHz)} Hz · Mix ${(fxRack.chorus.mix * 100).toInt()}%",
                    "LVL ${(fxRack.chorus.level * 100).toInt()}%"
                ),
                Triple(
                    FxModuleId.ROTARY,
                    "${if (fxRack.rotarySpeaker.brake) "BRAKE" else if (fxRack.rotarySpeaker.fastMode) "FAST" else "SLOW"} · ${"%.1f".format(fxRack.rotarySpeaker.rateHz)} Hz · Xover ${fxRack.rotarySpeaker.crossoverHz.toInt()}Hz",
                    "LVL ${(fxRack.rotarySpeaker.level * 100).toInt()}%"
                ),
                Triple(
                    FxModuleId.ECHO,
                    "${fxRack.echo.delayMs.toInt()} ms · FB ${(fxRack.echo.feedback * 100).toInt()}%${if (fxRack.echo.reverseDelay) " · REV" else ""}",
                    "LVL ${(fxRack.echo.level * 100).toInt()}%"
                ),
                Triple(
                    FxModuleId.DELAY,
                    "${if (fxRack.delay.bpmSync) fxRack.delay.division.label else "${fxRack.delay.delayMs.toInt()}ms"} · FB ${(fxRack.delay.feedback * 100).toInt()}%${if (fxRack.delay.reverseDelay) " · REV" else ""}",
                    "LVL ${(fxRack.delay.level * 100).toInt()}%"
                ),
                Triple(
                    FxModuleId.ANALOG_DELAY,
                    "BBD ${fxRack.analogDelay.delayMs.toInt()} ms · Warmth ${fxRack.analogDelay.warmthHz.toInt()}Hz${if (fxRack.analogDelay.reverseDelay) " · REV" else ""}",
                    "LVL ${(fxRack.analogDelay.level * 100).toInt()}%"
                ),
                Triple(
                    FxModuleId.REVERB,
                    "Size ${(fxRack.reverb.roomSize * 100).toInt()}% · Pre ${fxRack.reverb.preDelayMs.toInt()} ms · Mix ${(fxRack.reverb.mix * 100).toInt()}%",
                    "LVL ${(fxRack.reverb.level * 100).toInt()}%"
                )
            )

            fxRows.forEach { (id, subtitle, lvl) ->
                LmmsFxTileCard(
                    moduleId = id,
                    subtitle = subtitle,
                    levelLabel = lvl,
                    enabled = isModuleEnabled(fxRack, id),
                    accentColor = moduleAccentColor(id),
                    onToggle = { toggleModuleById(id, it, onUpdateFxRack) },
                    onOpenEditor = { editingModule = id }
                )
            }
        }
    }
}
