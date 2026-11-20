package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.studio.SyncedPlayheadState
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

/**
 * TRACKS SPOKE:
 * Multi-track timeline arrangement with globally synced playhead across tracks, looper, and sequencer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TracksSpokeScreen(
    timelineState: TimelineState,
    syncedPlayhead: SyncedPlayheadState,
    hasEditorAudio: Boolean,
    onSetBpm: (Float) -> Unit,
    onToggleSyncedPlay: () -> Unit,
    onToggleTimelinePlay: () -> Unit,
    onSeekSyncedPlayhead: (Float) -> Unit,
    onAddEditorClipToTrack: (Int) -> Unit,
    onAddLooperToTimeline: () -> Unit,
    onBounceSequencerToTimeline: () -> Unit,
    onNudgeClip: (Int, String, Float) -> Unit,
    onRemoveClip: (Int, String) -> Unit,
    onToggleTrackMute: (Int) -> Unit,
    onToggleTrackSolo: (Int) -> Unit,
    onBounceTimelineToEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalSec = timelineState.totalDurationSec.coerceAtLeast(4f)
    val effectivePlayhead = if (syncedPlayhead.isPlaying) syncedPlayhead.playheadSec else timelineState.playheadSec
    val playheadFraction = (effectivePlayhead / totalSec).coerceIn(0f, 1f)
    val trackColors = listOf(NeonCyan, EmeraldSignal, AmberHot, MagentaFx)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Master Sync Playhead Controller
        HardwarePanel(borderColor = MagentaFx.copy(alpha = 0.5f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Sync, contentDescription = null, tint = MagentaFx, modifier = Modifier.size(18.dp))
                        Text("TRACKS SPOKE · SYNCED PLAYHEAD", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    }
                    Text(
                        text = "BAR ${syncedPlayhead.bar}:${syncedPlayhead.beat} • ${"%.2f".format(effectivePlayhead)}s / ${"%.1f".format(totalSec)}s @ ${syncedPlayhead.bpm.toInt()} BPM",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan,
                        modifier = Modifier.testTag("tracks_sync_readout")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleSyncedPlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (syncedPlayhead.isPlaying) TacCoral else EmeraldSignal
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("tracks_master_sync_play")
                ) {
                    Icon(
                        imageVector = if (syncedPlayhead.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = "Master Sync Play/Stop",
                        tint = ObsidianChassis
                    )
                    Text(
                        text = if (syncedPlayhead.isPlaying) " STOP ALL SYNC" else " SYNC ALL PLAY",
                        color = ObsidianChassis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Button(
                    onClick = onToggleTimelinePlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (timelineState.isPlaying) TacCoral else NeonCyan
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("timeline_play_button")
                ) {
                    Text(
                        text = if (timelineState.isPlaying) "STOP MIX" else "PLAY TRACKS",
                        color = ObsidianChassis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                HardwareChipToggle(
                    label = "MIXDOWN -> DECK",
                    selected = false,
                    onClick = onBounceTimelineToEditor,
                    accentColor = VioletMod,
                    modifier = Modifier.testTag("timeline_mixdown_button")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HardwareParameterSlider(
                label = "SYNC CLOCK TEMPO",
                valueText = "${syncedPlayhead.bpm.toInt()} BPM",
                value = syncedPlayhead.bpm,
                valueRange = 60f..180f,
                accentColor = AmberHot,
                onValueChange = onSetBpm,
                testTag = "master_bpm_slider"
            )

            // Interactive Scrubbing Beat/Time Ruler with Synced Playhead
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ObsidianChassis)
                    .border(1.dp, ElevatedBezel, RoundedCornerShape(6.dp))
                    .pointerInput(totalSec) {
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onSeekSyncedPlayhead(frac * totalSec)
                        }
                    }
                    .testTag("timeline_ruler")
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val beatSec = 60f / syncedPlayhead.bpm.coerceIn(60f, 180f)
                    var t = 0f
                    var beatIdx = 0
                    while (t <= totalSec) {
                        val x = (t / totalSec) * w
                        val isBar = beatIdx % 4 == 0
                        drawLine(
                            color = if (isBar) NeonCyan.copy(alpha = 0.5f) else HairlineDivider,
                            start = Offset(x, if (isBar) 0f else h * 0.45f),
                            end = Offset(x, h),
                            strokeWidth = if (isBar) 2f else 1f
                        )
                        t += beatSec
                        beatIdx++
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
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasEditorAudio) {
                    HardwareChipToggle(
                        label = "+ DECK TO T1",
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
                HardwareChipToggle(
                    label = "+ SEQ BEAT TO T3",
                    selected = false,
                    onClick = onBounceSequencerToTimeline,
                    accentColor = AmberHot
                )
            }
        }

        // Track Lanes
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
                    // Live Synced Playhead Line
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
                                    modifier = Modifier.minimumInteractiveComponentSize()
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
}
