package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.MicCaptureTelemetry
import com.example.audio.PlaybackState
import com.example.dsp.FxRackState
import com.example.dsp.StompboxMasterState
import com.example.midi.MidiState
import com.example.network.NetworkLinkState
import com.example.studio.LooperState
import com.example.studio.SequencerState
import com.example.studio.SyncedPlayheadState
import com.example.studio.TimelineState
import com.example.ui.EditorState
import com.example.ui.StudioSpoke
import com.example.ui.WorkspaceConfig
import com.example.ui.theme.AmberHot
import com.example.ui.theme.BrushedPanel
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.GunmetalDeck
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianChassis
import com.example.ui.theme.TacCoral
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletMod
import kotlin.math.cos
import kotlin.math.sin

/**
 * Persistent Master Production Control Hub for Portrait/Compact screens:
 * Remains docked on screen across every station spoke so the musician always has
 * Synced Transport, Beat/VU Circular Dial, Deck Audition, Tap Tempo, Stompbox Bypass,
 * Universal Import, Network TX, and an inline 360° Studio Spoke Wheel toggle.
 */
@Composable
fun DockedCircularProductionHub(
    selectedSpoke: StudioSpoke,
    editorState: EditorState,
    playbackState: PlaybackState,
    micTelemetry: MicCaptureTelemetry,
    fxRack: FxRackState,
    stompbox: StompboxMasterState,
    looperState: LooperState,
    sequencerState: SequencerState,
    timelineState: TimelineState,
    syncedPlayhead: SyncedPlayheadState,
    networkState: NetworkLinkState,
    midiState: MidiState,
    hasAudio: Boolean,
    workspaceConfig: WorkspaceConfig = WorkspaceConfig(),
    onSelectSpoke: (StudioSpoke) -> Unit,
    onToggleSyncedPlay: () -> Unit,
    onToggleDeckPlay: () -> Unit,
    onTapTempo: () -> Unit,
    onToggleStompBypass: () -> Unit,
    onImportAudioClick: () -> Unit,
    onStreamTxClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var wheelExpanded by remember { mutableStateOf(false) }
    val activeAccent = spokeAccent(selectedSpoke)
    val livePeak = if (micTelemetry.isRecording) micTelemetry.peakLevel else playbackState.livePeak
    val liveRms = if (micTelemetry.isRecording) micTelemetry.rmsLevel else playbackState.liveRms
    val spokes = buildStudioSpokes(
        editorState = editorState,
        looperState = looperState,
        sequencerState = sequencerState,
        timelineState = timelineState,
        fxRack = fxRack,
        stompbox = stompbox,
        midiState = midiState,
        wheelSpokes = workspaceConfig.radialWheelSpokes
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("master_production_hub"),
        color = GunmetalDeck,
        border = BorderStroke(1.dp, activeAccent.copy(alpha = 0.6f)),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Left: Compact Circular Hub Radar & Master Sync Transport Dial
                Box(
                    modifier = Modifier.size(72.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val rimR = size.minDimension * 0.44f
                        val arcR = size.minDimension * 0.34f

                        drawCircle(
                            color = ObsidianChassis,
                            radius = size.minDimension * 0.48f,
                            center = center
                        )

                        // Radial dots & active beam connecting center Production Hub to active Spoke
                        spokes.forEach { desc ->
                            val isActive = desc.spoke == selectedSpoke
                            val rad = Math.toRadians(desc.angleDeg)
                            val pt = Offset(
                                center.x + (rimR * cos(rad)).toFloat(),
                                center.y + (rimR * sin(rad)).toFloat()
                            )
                            if (isActive) {
                                drawLine(
                                    color = desc.accent.copy(alpha = 0.9f),
                                    start = center,
                                    end = pt,
                                    strokeWidth = 2.5.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }
                            drawCircle(
                                color = if (isActive) desc.accent else desc.accent.copy(alpha = 0.35f),
                                radius = if (isActive) 4.dp.toPx() else 2.2.dp.toPx(),
                                center = pt
                            )
                        }

                        // Synced Beat Phase Arc
                        val arcTopLeft = Offset(center.x - arcR, center.y - arcR)
                        val arcSize = Size(arcR * 2f, arcR * 2f)
                        drawArc(
                            color = HairlineDivider,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        )
                        val sweep = if (syncedPlayhead.isPlaying || playbackState.isPlaying) {
                            (syncedPlayhead.phaseFraction * 360f).coerceAtLeast(20f)
                        } else {
                            90f
                        }
                        drawArc(
                            color = if (syncedPlayhead.isPlaying) EmeraldSignal else activeAccent,
                            startAngle = -90f,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    // Center 48dp Sync Play/Stop Button
                    Surface(
                        color = if (syncedPlayhead.isPlaying) TacCoral.copy(alpha = 0.22f) else ObsidianChassis.copy(alpha = 0.85f),
                        shape = CircleShape,
                        border = BorderStroke(
                            1.5.dp,
                            if (syncedPlayhead.isPlaying) EmeraldSignal else activeAccent
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onToggleSyncedPlay)
                            .testTag("hub_sync_play_button")
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (syncedPlayhead.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "Master Sync Play or Pause",
                                tint = if (syncedPlayhead.isPlaying) EmeraldSignal else NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "${syncedPlayhead.bar}:${syncedPlayhead.beat}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = TextPrimary,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Right: Master Production Control Matrix
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Header row: Active Hub->Station Link + 360° Wheel Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "HUB ➔ ${selectedSpoke.shortLabel} • ${syncedPlayhead.bpm.toInt()} BPM",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = activeAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = if (wheelExpanded) NeonCyan.copy(alpha = 0.22f) else BrushedPanel,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (wheelExpanded) NeonCyan else HairlineDivider),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { wheelExpanded = !wheelExpanded }
                                .testTag("hub_expand_wheel_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Hub,
                                    contentDescription = "Toggle 360 Circular Studio Wheel",
                                    tint = if (wheelExpanded) NeonCyan else TextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (wheelExpanded) "HIDE 360°" else "360° RING",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = if (wheelExpanded) NeonCyan else TextPrimary
                                )
                            }
                        }
                    }

                    // 5 Master Production Pads (Equal weight, fits cleanly on 320dp+)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        HubControlPad(
                            label = if (playbackState.isPlaying) "PAUSE" else "DECK",
                            icon = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            active = playbackState.isPlaying,
                            enabled = hasAudio,
                            accent = EmeraldSignal,
                            onClick = onToggleDeckPlay,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hub_deck_play_button")
                        )
                        HubControlPad(
                            label = "TAP",
                            icon = Icons.Filled.Speed,
                            active = false,
                            enabled = true,
                            accent = AmberHot,
                            onClick = onTapTempo,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hub_tap_bpm_button")
                        )
                        HubControlPad(
                            label = if (stompbox.engaged && !fxRack.masterBypass) "STOMP" else "BYP",
                            icon = Icons.Filled.ElectricBolt,
                            active = stompbox.engaged && !fxRack.masterBypass,
                            enabled = true,
                            accent = TacCoral,
                            onClick = onToggleStompBypass,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hub_stomp_bypass_button")
                        )
                        HubControlPad(
                            label = "IMPORT",
                            icon = Icons.Filled.AudioFile,
                            active = editorState.isImportingAudio,
                            enabled = !editorState.isImportingAudio,
                            accent = NeonCyan,
                            onClick = onImportAudioClick,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hub_import_button")
                        )
                        HubControlPad(
                            label = if (networkState.isTransmitting) "LIVE" else "TX",
                            icon = Icons.AutoMirrored.Filled.Send,
                            active = networkState.isTransmitting,
                            enabled = true,
                            accent = VioletMod,
                            onClick = onStreamTxClick,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hub_tx_button")
                        )
                    }
                }
            }

            // Live Master Output VU Bar visible on every station
            VuPeakMeterBar(
                peak = livePeak,
                rms = liveRms,
                label = "MST",
                modifier = Modifier.testTag("hub_vu_meter")
            )

            // Expandable Inline 360° Circular Studio Floorplan Overlay
            AnimatedVisibility(
                visible = wheelExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Surface(
                    color = ObsidianChassis,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    CircularStudioWheel(
                        selectedSpoke = selectedSpoke,
                        editorState = editorState,
                        playbackState = playbackState,
                        micTelemetry = micTelemetry,
                        fxRack = fxRack,
                        stompbox = stompbox,
                        looperState = looperState,
                        sequencerState = sequencerState,
                        timelineState = timelineState,
                        syncedPlayhead = syncedPlayhead,
                        midiState = midiState,
                        wheelSpokes = workspaceConfig.radialWheelSpokes,
                        onSelectSpoke = { spoke ->
                            wheelExpanded = false
                            onSelectSpoke(spoke)
                        },
                        onToggleSyncedPlay = onToggleSyncedPlay,
                        maxWheelDp = 310f,
                        tagPrefix = "mini_",
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HubControlPad(
    label: String,
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = if (active) accent.copy(alpha = 0.22f) else ObsidianChassis,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            when {
                !enabled -> HairlineDivider.copy(alpha = 0.45f)
                active -> accent
                else -> HairlineDivider
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    !enabled -> TextMuted
                    active -> accent
                    else -> TextSecondary
                },
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = when {
                    !enabled -> TextMuted
                    active -> accent
                    else -> TextPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

/**
 * Persistent Left-Side Circular Studio Hub & Radial Spokes Console for Tablet/Landscape screens:
 * Keeps the entire 360° Production Control Hub and 6 radial station spokes permanently on screen
 * while any selected station operates in the right workspace pane.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WideCircularProductionSidebar(
    selectedSpoke: StudioSpoke,
    editorState: EditorState,
    playbackState: PlaybackState,
    micTelemetry: MicCaptureTelemetry,
    fxRack: FxRackState,
    stompbox: StompboxMasterState,
    looperState: LooperState,
    sequencerState: SequencerState,
    timelineState: TimelineState,
    syncedPlayhead: SyncedPlayheadState,
    networkState: NetworkLinkState,
    midiState: MidiState,
    hasAudio: Boolean,
    workspaceConfig: WorkspaceConfig = WorkspaceConfig(),
    onSelectSpoke: (StudioSpoke) -> Unit,
    onToggleSyncedPlay: () -> Unit,
    onToggleDeckPlay: () -> Unit,
    onTapTempo: () -> Unit,
    onToggleStompBypass: () -> Unit,
    onImportAudioClick: () -> Unit,
    onExportMp3Click: () -> Unit,
    onStreamTxClick: () -> Unit,
    onPushHandoffClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val livePeak = if (micTelemetry.isRecording) micTelemetry.peakLevel else playbackState.livePeak
    val liveRms = if (micTelemetry.isRecording) micTelemetry.rmsLevel else playbackState.liveRms

    Surface(
        modifier = modifier
            .width(340.dp)
            .fillMaxHeight(),
        color = GunmetalDeck,
        border = BorderStroke(1.dp, HairlineDivider),
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CIRCULAR STUDIO HUB",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = NeonCyan
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HardwareChipToggle(
                        label = "HANDOFF",
                        selected = false,
                        onClick = onPushHandoffClick,
                        accentColor = EmeraldSignal
                    )
                    HardwareChipToggle(
                        label = "BOOTH",
                        selected = selectedSpoke == StudioSpoke.BOOTH,
                        onClick = { onSelectSpoke(StudioSpoke.BOOTH) },
                        accentColor = NeonCyan,
                        modifier = Modifier.testTag("wide_booth_hub_button")
                    )
                }
            }

            CircularStudioWheel(
                selectedSpoke = selectedSpoke,
                editorState = editorState,
                playbackState = playbackState,
                micTelemetry = micTelemetry,
                fxRack = fxRack,
                stompbox = stompbox,
                looperState = looperState,
                sequencerState = sequencerState,
                timelineState = timelineState,
                syncedPlayhead = syncedPlayhead,
                midiState = midiState,
                wheelSpokes = workspaceConfig.radialWheelSpokes,
                onSelectSpoke = onSelectSpoke,
                onToggleSyncedPlay = onToggleSyncedPlay,
                maxWheelDp = 316f
            )

            VuPeakMeterBar(
                peak = livePeak,
                rms = liveRms,
                label = "MST"
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HardwareChipToggle(
                    label = if (playbackState.isPlaying) "PAUSE DECK" else "PLAY DECK",
                    selected = playbackState.isPlaying,
                    onClick = onToggleDeckPlay,
                    accentColor = if (hasAudio) EmeraldSignal else TextSecondary,
                    modifier = Modifier.testTag("wide_deck_play_button")
                )
                HardwareChipToggle(
                    label = "TAP ${syncedPlayhead.bpm.toInt()}",
                    selected = false,
                    onClick = onTapTempo,
                    accentColor = AmberHot
                )
                HardwareChipToggle(
                    label = if (stompbox.engaged) "STOMP ON" else "BYPASS",
                    selected = stompbox.engaged,
                    onClick = onToggleStompBypass,
                    accentColor = TacCoral
                )
                HardwareChipToggle(
                    label = "IMPORT",
                    selected = false,
                    onClick = onImportAudioClick,
                    accentColor = NeonCyan
                )
                HardwareChipToggle(
                    label = "EXPORT",
                    selected = false,
                    onClick = onExportMp3Click,
                    accentColor = EmeraldSignal
                )
                HardwareChipToggle(
                    label = if (networkState.isTransmitting) "TX LIVE" else "NET TX",
                    selected = networkState.isTransmitting,
                    onClick = onStreamTxClick,
                    accentColor = VioletMod
                )
            }

            Text(
                text = editorState.statusBanner,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
