package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.studio.LooperState
import com.example.studio.SequencerState
import com.example.studio.TimelineState
import com.example.ui.components.HardwareChipToggle
import com.example.ui.components.HardwarePanel
import com.example.ui.components.HardwareParameterSlider
import com.example.ui.theme.AmberHot
import com.example.ui.theme.ElevatedBezel
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

private enum class StudioSubTab(val label: String) {
    LOOPER("LOOPER"),
    SEQUENCER("16-STEP SEQ"),
    TIMELINE("TIMELINE")
}

@Composable
fun StudioPerformanceScreen(
    looperState: LooperState,
    sequencerState: SequencerState,
    timelineState: TimelineState,
    hasEditorAudio: Boolean,
    onSetBpm: (Float) -> Unit,
    onToggleLooperPlay: () -> Unit,
    onAssignEditorToLoop: (Int) -> Unit,
    onToggleLoopSlot: (Int) -> Unit,
    onToggleLoopMute: (Int) -> Unit,
    onClearLoopSlot: (Int) -> Unit,
    onSetLoopBars: (Int, Int) -> Unit,
    onBounceLooperToEditor: () -> Unit,
    onToggleSequencerPlay: () -> Unit,
    onToggleSequencerStep: (Int, Int) -> Unit,
    onToggleSequencerMute: (Int) -> Unit,
    onAssignEditorToSeqLane: (Int) -> Unit,
    onSetSwing: (Float) -> Unit,
    onBounceSequencerToTimeline: () -> Unit,
    onToggleTimelinePlay: () -> Unit,
    onSeekTimeline: (Float) -> Unit,
    onAddEditorClipToTrack: (Int) -> Unit,
    onAddLooperToTimeline: () -> Unit,
    onNudgeClip: (Int, String, Float) -> Unit,
    onRemoveClip: (Int, String) -> Unit,
    onToggleTrackMute: (Int) -> Unit,
    onToggleTrackSolo: (Int) -> Unit,
    onBounceTimelineToEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    var subTab by remember { mutableStateOf(StudioSubTab.LOOPER) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Sub-Mode Switcher + Global Master BPM
        HardwarePanel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StudioSubTab.entries.forEach { tab ->
                    HardwareChipToggle(
                        label = tab.label,
                        selected = subTab == tab,
                        onClick = { subTab = tab },
                        accentColor = NeonCyan,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("studio_subtab_${tab.name.lowercase()}")
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HardwareParameterSlider(
                label = "MASTER TEMPO (BPM)",
                valueText = "${sequencerState.bpm.toInt()} BPM",
                value = sequencerState.bpm,
                valueRange = 60f..180f,
                accentColor = AmberHot,
                onValueChange = onSetBpm,
                testTag = "master_bpm_slider"
            )
        }

        when (subTab) {
            StudioSubTab.LOOPER -> LooperDeckSection(
                looperState = looperState,
                hasEditorAudio = hasEditorAudio,
                onToggleLooperPlay = onToggleLooperPlay,
                onAssignEditorToLoop = onAssignEditorToLoop,
                onToggleLoopSlot = onToggleLoopSlot,
                onToggleLoopMute = onToggleLoopMute,
                onClearLoopSlot = onClearLoopSlot,
                onSetLoopBars = onSetLoopBars,
                onBounceLooperToEditor = onBounceLooperToEditor
            )
            StudioSubTab.SEQUENCER -> SequencerDeckSection(
                sequencerState = sequencerState,
                hasEditorAudio = hasEditorAudio,
                onTogglePlay = onToggleSequencerPlay,
                onToggleStep = onToggleSequencerStep,
                onToggleMute = onToggleSequencerMute,
                onAssignEditorToLane = onAssignEditorToSeqLane,
                onSetSwing = onSetSwing,
                onBounceToTimeline = onBounceSequencerToTimeline
            )
            StudioSubTab.TIMELINE -> TimelineArrangementSection(
                timelineState = timelineState,
                hasEditorAudio = hasEditorAudio,
                onTogglePlay = onToggleTimelinePlay,
                onSeekPlayhead = onSeekTimeline,
                onAddEditorClipToTrack = onAddEditorClipToTrack,
                onAddLooperToTimeline = onAddLooperToTimeline,
                onNudgeClip = onNudgeClip,
                onRemoveClip = onRemoveClip,
                onToggleTrackMute = onToggleTrackMute,
                onToggleTrackSolo = onToggleTrackSolo,
                onBounceToEditor = onBounceTimelineToEditor
            )
        }
    }
}

@Composable
private fun LooperDeckSection(
    looperState: LooperState,
    hasEditorAudio: Boolean,
    onToggleLooperPlay: () -> Unit,
    onAssignEditorToLoop: (Int) -> Unit,
    onToggleLoopSlot: (Int) -> Unit,
    onToggleLoopMute: (Int) -> Unit,
    onClearLoopSlot: (Int) -> Unit,
    onSetLoopBars: (Int, Int) -> Unit,
    onBounceLooperToEditor: () -> Unit
) {
    HardwarePanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("4-SLOT DJ LOOPER", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text("Quantized bar-sync loop decks", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onToggleLooperPlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (looperState.isRunning) TacCoral else EmeraldSignal
                    ),
                    modifier = Modifier.testTag("looper_play_button")
                ) {
                    Icon(
                        imageVector = if (looperState.isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = "Looper Play/Stop",
                        tint = ObsidianChassis
                    )
                    Text(
                        text = if (looperState.isRunning) " STOP" else " RUN",
                        color = ObsidianChassis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                HardwareChipToggle(
                    label = "MIX -> EDIT",
                    selected = false,
                    onClick = onBounceLooperToEditor,
                    accentColor = VioletMod,
                    modifier = Modifier.testTag("looper_bounce_button")
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { looperState.barProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = NeonCyan,
            trackColor = ObsidianChassis
        )
    }

    looperState.slots.forEach { slot ->
        val slotLoaded = slot.buffer != null
        HardwarePanel(
            borderColor = when {
                slot.isActive && !slot.isMuted && looperState.isRunning -> EmeraldSignal
                slot.isActive -> NeonCyan
                else -> HairlineDivider
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val buf = slot.buffer
                    Text(
                        text = "${slot.label} ${if (buf != null) "(${"%.2f".format(buf.durationSeconds)}s)" else "(EMPTY)"}",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (slotLoaded) TextPrimary else TextMuted
                    )
                    Text(
                        text = "${slot.bars} BAR CYCLE",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (hasEditorAudio) {
                        HardwareChipToggle(
                            label = "LOAD",
                            selected = false,
                            onClick = { onAssignEditorToLoop(slot.id) },
                            accentColor = NeonCyan,
                            modifier = Modifier.testTag("loop_load_${slot.id}")
                        )
                    }
                    HardwareChipToggle(
                        label = if (slot.isActive) "ON" else "OFF",
                        selected = slot.isActive,
                        onClick = { onToggleLoopSlot(slot.id) },
                        accentColor = EmeraldSignal,
                        modifier = Modifier.testTag("loop_toggle_${slot.id}")
                    )
                    HardwareChipToggle(
                        label = "M",
                        selected = slot.isMuted,
                        onClick = { onToggleLoopMute(slot.id) },
                        accentColor = TacCoral
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(1, 2, 4).forEach { barCount ->
                    HardwareChipToggle(
                        label = "${barCount}B",
                        selected = slot.bars == barCount,
                        onClick = { onSetLoopBars(slot.id, barCount) },
                        accentColor = AmberHot
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (slotLoaded) {
                    HardwareChipToggle(
                        label = "CLR",
                        selected = false,
                        onClick = { onClearLoopSlot(slot.id) },
                        accentColor = TacCoral
                    )
                }
            }
        }
    }
}

@Composable
private fun SequencerDeckSection(
    sequencerState: SequencerState,
    hasEditorAudio: Boolean,
    onTogglePlay: () -> Unit,
    onToggleStep: (Int, Int) -> Unit,
    onToggleMute: (Int) -> Unit,
    onAssignEditorToLane: (Int) -> Unit,
    onSetSwing: (Float) -> Unit,
    onBounceToTimeline: () -> Unit
) {
    HardwarePanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("16-STEP SEQUENCER", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text("Step ${sequencerState.currentStep + 1}/16 • Live DJ Grid", style = MaterialTheme.typography.labelSmall, color = NeonCyan)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onTogglePlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sequencerState.isPlaying) TacCoral else EmeraldSignal
                    ),
                    modifier = Modifier.testTag("seq_play_button")
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
                    label = "TO TRACK",
                    selected = false,
                    onClick = onBounceToTimeline,
                    accentColor = VioletMod,
                    modifier = Modifier.testTag("seq_bounce_timeline")
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        HardwareParameterSlider(
            label = "GROOVE SWING",
            valueText = "${(sequencerState.swing * 100).toInt()}%",
            value = sequencerState.swing,
            valueRange = 0f..0.45f,
            accentColor = VioletMod,
            onValueChange = onSetSwing
        )
    }

    val laneColors = listOf(NeonCyan, TacCoral, AmberHot, EmeraldSignal)

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
                            label = "USE TAKE",
                            selected = false,
                            onClick = { onAssignEditorToLane(lane.id) },
                            accentColor = NeonCyan
                        )
                    }
                    HardwareChipToggle(
                        label = if (lane.isMuted) "MUTED" else "ACTIVE",
                        selected = !lane.isMuted,
                        onClick = { onToggleMute(lane.id) },
                        accentColor = laneColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Two rows of 8 steps for comfortable 48dp touch targets
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
                                .clickable { onToggleStep(lane.id, stepIdx) }
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
}

@Composable
private fun TimelineArrangementSection(
    timelineState: TimelineState,
    hasEditorAudio: Boolean,
    onTogglePlay: () -> Unit,
    onSeekPlayhead: (Float) -> Unit,
    onAddEditorClipToTrack: (Int) -> Unit,
    onAddLooperToTimeline: () -> Unit,
    onNudgeClip: (Int, String, Float) -> Unit,
    onRemoveClip: (Int, String) -> Unit,
    onToggleTrackMute: (Int) -> Unit,
    onToggleTrackSolo: (Int) -> Unit,
    onBounceToEditor: () -> Unit
) {
    val totalSec = timelineState.totalDurationSec.coerceAtLeast(4f)
    val playheadFraction = (timelineState.playheadSec / totalSec).coerceIn(0f, 1f)

    HardwarePanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("TRACK TIMELINE & PLAYHEAD", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(
                    text = "PLAYHEAD: ${"%.2f".format(timelineState.playheadSec)}s / ${"%.1f".format(totalSec)}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonCyan
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onTogglePlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (timelineState.isPlaying) TacCoral else EmeraldSignal
                    ),
                    modifier = Modifier.testTag("timeline_play_button")
                ) {
                    Icon(
                        imageVector = if (timelineState.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = "Timeline Play/Stop",
                        tint = ObsidianChassis
                    )
                    Text(
                        text = if (timelineState.isPlaying) " STOP" else " PLAY",
                        color = ObsidianChassis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                HardwareChipToggle(
                    label = "MIXDOWN",
                    selected = false,
                    onClick = onBounceToEditor,
                    accentColor = VioletMod,
                    modifier = Modifier.testTag("timeline_mixdown_button")
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Interactive Scrubbing Time Ruler with Playhead
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ObsidianChassis)
                .border(1.dp, ElevatedBezel, RoundedCornerShape(6.dp))
                .pointerInput(totalSec) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeekPlayhead(frac * totalSec)
                    }
                }
                .testTag("timeline_ruler")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val secStep = 1f
                var s = 0f
                while (s <= totalSec) {
                    val x = (s / totalSec) * w
                    drawLine(HairlineDivider, Offset(x, h * 0.4f), Offset(x, h), strokeWidth = 1.5f)
                    s += secStep
                }
                val px = playheadFraction * w
                drawLine(Color.White, Offset(px, 0f), Offset(px, h), strokeWidth = 3f)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0.0s", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text("${"%.1f".format(totalSec / 2f)}s", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text("${"%.1f".format(totalSec)}s", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hasEditorAudio) {
                HardwareChipToggle(
                    label = "+ SAMPLE TO T1",
                    selected = false,
                    onClick = { onAddEditorClipToTrack(0) },
                    accentColor = NeonCyan,
                    modifier = Modifier.testTag("timeline_add_sample_t1")
                )
                HardwareChipToggle(
                    label = "+ FX CLIP TO T4",
                    selected = false,
                    onClick = { onAddEditorClipToTrack(3) },
                    accentColor = MagentaFx
                )
            }
            HardwareChipToggle(
                label = "+ LOOPER TO T2",
                selected = false,
                onClick = onAddLooperToTimeline,
                accentColor = EmeraldSignal
            )
        }
    }

    val trackColors = listOf(NeonCyan, EmeraldSignal, AmberHot, MagentaFx)

    timelineState.tracks.forEachIndexed { idx, track ->
        val tColor = trackColors[idx % trackColors.size]
        HardwarePanel(borderColor = if (track.isMuted) HairlineDivider else tColor.copy(alpha = 0.55f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (track.isMuted) TextMuted else tColor
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (hasEditorAudio) {
                        IconButton(
                            onClick = { onAddEditorClipToTrack(track.id) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add clip to track", tint = tColor)
                        }
                    }
                    HardwareChipToggle(
                        label = "M",
                        selected = track.isMuted,
                        onClick = { onToggleTrackMute(track.id) },
                        accentColor = TacCoral
                    )
                    HardwareChipToggle(
                        label = "S",
                        selected = track.isSolo,
                        onClick = { onToggleTrackSolo(track.id) },
                        accentColor = AmberHot
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Track lane canvas with positioned clips + live playhead line
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ObsidianChassis)
                    .border(1.dp, ElevatedBezel, RoundedCornerShape(6.dp))
            ) {
                val laneWidth = maxWidth
                track.clips.forEach { clip ->
                    val startFrac = (clip.startTimeSec / totalSec).coerceIn(0f, 0.95f)
                    val widthFrac = (clip.buffer.durationSeconds / totalSec).coerceIn(0.08f, 1f - startFrac)
                    Surface(
                        modifier = Modifier
                            .offset(x = laneWidth * startFrac)
                            .width(laneWidth * widthFrac)
                            .fillMaxHeight()
                            .padding(2.dp),
                        color = tColor.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, tColor),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                            Text(
                                text = clip.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary,
                                maxLines = 1
                            )
                        }
                    }
                }
                // Live Playhead line across track lane
                Box(
                    modifier = Modifier
                        .offset(x = laneWidth * playheadFraction)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Color.White)
                )
            }

            if (track.clips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                track.clips.forEach { clip ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${clip.label} @ ${"%.2f".format(clip.startTimeSec)}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            HardwareChipToggle(
                                label = "-0.5s",
                                selected = false,
                                onClick = { onNudgeClip(track.id, clip.id, -0.5f) },
                                accentColor = tColor
                            )
                            HardwareChipToggle(
                                label = "+0.5s",
                                selected = false,
                                onClick = { onNudgeClip(track.id, clip.id, 0.5f) },
                                accentColor = tColor
                            )
                            IconButton(
                                onClick = { onRemoveClip(track.id, clip.id) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove Clip", tint = TacCoral)
                            }
                        }
                    }
                }
            }
        }
    }
}
