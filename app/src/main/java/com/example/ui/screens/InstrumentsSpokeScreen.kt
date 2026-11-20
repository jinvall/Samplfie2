package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.midi.MidiState
import com.example.studio.SequencerState
import com.example.ui.components.HardwareChipToggle
import com.example.ui.components.HardwarePanel
import com.example.ui.components.HardwareParameterSlider
import com.example.ui.theme.AmberHot
import com.example.ui.theme.ElevatedBezel
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianChassis
import com.example.ui.theme.TacCoral
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletMod

/**
 * INSTRUMENTS SPOKE:
 * Live Drum/Sample Performance Pads, 16-Step Sequencer, Master BPM/Swing, and Android MIDI Hardware Interface.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstrumentsSpokeScreen(
    sequencerState: SequencerState,
    midiState: MidiState,
    hasEditorAudio: Boolean,
    onSetBpm: (Float) -> Unit,
    onToggleSequencerPlay: () -> Unit,
    onToggleSequencerStep: (Int, Int) -> Unit,
    onToggleSequencerMute: (Int) -> Unit,
    onAssignEditorToSeqLane: (Int) -> Unit,
    onTriggerLanePad: (Int) -> Unit,
    onSetSwing: (Float) -> Unit,
    onBounceSequencerToTimeline: () -> Unit,
    onRefreshMidi: () -> Unit,
    modifier: Modifier = Modifier
) {
    val laneColors = listOf(NeonCyan, TacCoral, AmberHot, EmeraldSignal)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Live Performance Trigger Pads + Sequencer Master Transport
        HardwarePanel(borderColor = AmberHot.copy(alpha = 0.5f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("INSTRUMENTS SPOKE · SEQ & PADS", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(
                        text = "Step ${sequencerState.currentStep + 1}/16 • ${sequencerState.bpm.toInt()} BPM",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberHot
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onToggleSequencerPlay,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sequencerState.isPlaying) TacCoral else EmeraldSignal
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("seq_play_button")
                    ) {
                        Icon(
                            imageVector = if (sequencerState.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = "Sequencer Play/Stop",
                            tint = ObsidianChassis
                        )
                        Text(
                            text = if (sequencerState.isPlaying) " STOP" else " PLAY",
                            color = ObsidianChassis,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    HardwareChipToggle(
                        label = "TO TRACKS",
                        selected = false,
                        onClick = onBounceSequencerToTimeline,
                        accentColor = VioletMod,
                        modifier = Modifier.testTag("seq_bounce_timeline")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("LIVE TRIGGER PADS", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sequencerState.lanes.forEachIndexed { idx, lane ->
                    val padColor = laneColors[idx % laneColors.size]
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onTriggerLanePad(lane.id) }
                            .testTag("inst_pad_${lane.id}"),
                        color = padColor.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.5.dp, padColor)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(4.dp)) {
                            Text(
                                text = lane.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = padColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HardwareParameterSlider(
                label = "TEMPO (BPM)",
                valueText = "${sequencerState.bpm.toInt()} BPM",
                value = sequencerState.bpm,
                valueRange = 60f..180f,
                accentColor = AmberHot,
                onValueChange = onSetBpm
            )
            HardwareParameterSlider(
                label = "GROOVE SWING",
                valueText = "${(sequencerState.swing * 100).toInt()}%",
                value = sequencerState.swing,
                valueRange = 0f..0.45f,
                accentColor = VioletMod,
                onValueChange = onSetSwing
            )
        }

        // 2. 16-Step Sequencer Lanes
        sequencerState.lanes.forEachIndexed { idx, lane ->
            val laneColor = laneColors[idx % laneColors.size]
            HardwarePanel(borderColor = if (lane.isMuted) HairlineDivider else laneColor.copy(alpha = 0.5f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = lane.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (lane.isMuted) TextMuted else laneColor
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (hasEditorAudio) {
                            HardwareChipToggle(
                                label = "USE DECK",
                                selected = false,
                                onClick = { onAssignEditorToSeqLane(lane.id) },
                                accentColor = NeonCyan
                            )
                        }
                        HardwareChipToggle(
                            label = if (lane.isMuted) "MUTED" else "ACTIVE",
                            selected = !lane.isMuted,
                            onClick = { onToggleSequencerMute(lane.id) },
                            accentColor = laneColor
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                for (rowBlock in 0..1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (col in 0..7) {
                            val stepIdx = rowBlock * 8 + col
                            val isOn = lane.steps[stepIdx]
                            val isCursor = sequencerState.isPlaying && sequencerState.currentStep == stepIdx
                            val beatAccent = stepIdx % 4 == 0
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onToggleSequencerStep(lane.id, stepIdx) }
                                    .testTag("seq_step_${lane.id}_$stepIdx"),
                                color = when {
                                    isOn -> laneColor
                                    beatAccent -> ElevatedBezel
                                    else -> ObsidianChassis
                                },
                                border = BorderStroke(
                                    width = if (isCursor) 2.dp else 1.dp,
                                    color = if (isCursor) Color.White else if (isOn) laneColor else HairlineDivider
                                ),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${stepIdx + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isOn) ObsidianChassis else TextSecondary
                                    )
                                }
                            }
                        }
                    }
                    if (rowBlock == 0) Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        // 3. Hardware MIDI Interface Panel
        HardwarePanel(borderColor = if (midiState.connectedDevices.isNotEmpty()) EmeraldSignal else HairlineDivider) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("MIDI CONTROLLER INTERFACE", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(
                        text = if (midiState.isSupported) {
                            "${midiState.connectedDevices.size} DEVICE(S) • ${midiState.syncedBpm?.let { "CLK ${it.toInt()} BPM" } ?: "INTERNAL CLOCK"}"
                        } else {
                            "ANDROID MIDI SERVICE NOT AVAILABLE"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (midiState.connectedDevices.isNotEmpty()) EmeraldSignal else TextSecondary
                    )
                }
                IconButton(
                    onClick = onRefreshMidi,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("midi_refresh_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh MIDI Devices", tint = NeonCyan)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "MAP: N36-39 Loops • N40-43 Drum Pads • N48-72 Chromatic Pitch • CC1/74 Wah • CC71 Drive • CC91 Reverb • CC93 Echo • CC7 Gain",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )

            if (midiState.connectedDevices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                midiState.connectedDevices.forEach { dev ->
                    Surface(
                        color = ObsidianChassis,
                        border = BorderStroke(1.dp, EmeraldSignal.copy(alpha = 0.5f)),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = "${dev.manufacturer} ${dev.name} (IN:${dev.inputPortCount} OUT:${dev.outputPortCount})",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldSignal,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            if (midiState.recentLog.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("LIVE MIDI TELEMETRY", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                midiState.recentLog.take(5).forEach { entry ->
                    Text(entry, style = MaterialTheme.typography.labelSmall, color = NeonCyan)
                }
            }
        }
    }
}
