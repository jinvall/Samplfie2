package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.audio.MicCaptureTelemetry
import com.example.audio.PlaybackState
import com.example.dsp.FxRackState
import com.example.dsp.LiveSessionMode
import com.example.dsp.StompboxMasterState
import com.example.midi.MidiState
import com.example.network.NetworkLinkState
import com.example.studio.LooperState
import com.example.studio.SequencerState
import com.example.studio.SyncedPlayheadState
import com.example.studio.TimelineState
import com.example.ui.EditorState
import com.example.ui.StudioSpoke
import com.example.ui.theme.AmberHot
import com.example.ui.theme.ElevatedBezel
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.GunmetalDeck
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.MagentaFx
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianChassis
import com.example.ui.theme.TacCoral
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletMod
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal data class SpokeDescriptor(
    val spoke: StudioSpoke,
    val title: String,
    val subtitle: String,
    val angleDeg: Double,
    val accent: Color,
    val icon: ImageVector
)

internal fun spokeAccent(spoke: StudioSpoke): Color = when (spoke) {
    StudioSpoke.BOOTH -> NeonCyan
    StudioSpoke.DECK -> NeonCyan
    StudioSpoke.SAMPLES -> EmeraldSignal
    StudioSpoke.INSTRUMENTS -> AmberHot
    StudioSpoke.LIVE -> TacCoral
    StudioSpoke.TRACKS -> MagentaFx
    StudioSpoke.FX_RACK -> VioletMod
}

internal fun buildStudioSpokes(
    editorState: EditorState,
    looperState: LooperState,
    sequencerState: SequencerState,
    timelineState: TimelineState,
    fxRack: FxRackState,
    stompbox: StompboxMasterState,
    midiState: MidiState
): List<SpokeDescriptor> {
    val activeBuf = editorState.processedBuffer ?: editorState.dryBuffer
    val activeLoops = looperState.slots.count { it.isActive && it.buffer != null }
    val activeTracks = timelineState.tracks.count { it.clips.isNotEmpty() }

    return listOf(
        SpokeDescriptor(
            spoke = StudioSpoke.DECK,
            title = "DECK",
            subtitle = if (activeBuf != null && activeBuf.frameCount > 0) "${"%.1f".format(activeBuf.durationSeconds)}s WAV" else "MIC / IMPORT",
            angleDeg = -90.0,
            accent = NeonCyan,
            icon = Icons.Filled.GraphicEq
        ),
        SpokeDescriptor(
            spoke = StudioSpoke.SAMPLES,
            title = "SAMPLES",
            subtitle = "$activeLoops/4 LOOPS",
            angleDeg = -30.0,
            accent = EmeraldSignal,
            icon = Icons.Filled.Loop
        ),
        SpokeDescriptor(
            spoke = StudioSpoke.INSTRUMENTS,
            title = "INST",
            subtitle = "${sequencerState.bpm.toInt()} BPM • MIDI ${midiState.connectedDevices.size}",
            angleDeg = 30.0,
            accent = AmberHot,
            icon = Icons.Filled.Piano
        ),
        SpokeDescriptor(
            spoke = StudioSpoke.LIVE,
            title = "LIVE",
            subtitle = stompbox.liveMode.accentTag,
            angleDeg = 90.0,
            accent = TacCoral,
            icon = Icons.Filled.ElectricBolt
        ),
        SpokeDescriptor(
            spoke = StudioSpoke.TRACKS,
            title = "TRACKS",
            subtitle = "$activeTracks TRK SYNC",
            angleDeg = 150.0,
            accent = MagentaFx,
            icon = Icons.Filled.ViewTimeline
        ),
        SpokeDescriptor(
            spoke = StudioSpoke.FX_RACK,
            title = "FX RACK",
            subtitle = "${fxRack.activeCount}/12 ACTIVE",
            angleDeg = 210.0,
            accent = VioletMod,
            icon = Icons.Filled.Tune
        )
    )
}

/**
 * Reusable 360-degree Circular Studio Wheel:
 * Musician's center-seat view where Master Production Control sits in the center hub
 * and the 6 studio stations radiate around it on illuminated signal spokes.
 */
@Composable
fun CircularStudioWheel(
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
    midiState: MidiState,
    onSelectSpoke: (StudioSpoke) -> Unit,
    onToggleSyncedPlay: () -> Unit,
    maxWheelDp: Float = 420f,
    tagPrefix: String = "",
    modifier: Modifier = Modifier
) {
    val spokes = buildStudioSpokes(
        editorState = editorState,
        looperState = looperState,
        sequencerState = sequencerState,
        timelineState = timelineState,
        fxRack = fxRack,
        stompbox = stompbox,
        midiState = midiState
    )
    val livePeak = if (micTelemetry.isRecording) micTelemetry.peakLevel else playbackState.livePeak

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag("${tagPrefix}circular_studio_wheel")
    ) {
        val cappedWheel = if (maxWidth < 360.dp) min(maxWheelDp, 252f) else maxWheelDp
        val wheelSize: Dp = min(maxWidth.value, cappedWheel).dp
        val nodeSize: Dp = when {
            wheelSize < 280.dp -> 68.dp
            wheelSize < 340.dp -> 80.dp
            else -> 92.dp
        }
        val hubSize: Dp = when {
            wheelSize < 280.dp -> 88.dp
            wheelSize < 340.dp -> 114.dp
            else -> 128.dp
        }
        val orbitRadiusDp = (wheelSize - nodeSize) * 0.45f

        Box(
            modifier = Modifier
                .size(wheelSize)
                .align(Alignment.Center)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerR = size.minDimension * 0.36f
                val innerR = size.minDimension * 0.19f

                // Circular studio room ambiance
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NeonCyan.copy(alpha = 0.16f),
                            GunmetalDeck.copy(alpha = 0.55f),
                            ObsidianChassis
                        ),
                        center = center,
                        radius = size.minDimension * 0.5f
                    ),
                    radius = size.minDimension * 0.49f,
                    center = center
                )

                // Outer console track ring & calibration ticks
                drawCircle(
                    color = HairlineDivider,
                    radius = outerR,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
                for (deg in 0 until 360 step 15) {
                    val rad = Math.toRadians(deg.toDouble())
                    val tickIn = outerR * 0.94f
                    val tickOut = outerR * 1.02f
                    drawLine(
                        color = HairlineDivider.copy(alpha = if (deg % 60 == 0) 0.85f else 0.35f),
                        start = Offset(
                            center.x + (tickIn * cos(rad)).toFloat(),
                            center.y + (tickIn * sin(rad)).toFloat()
                        ),
                        end = Offset(
                            center.x + (tickOut * cos(rad)).toFloat(),
                            center.y + (tickOut * sin(rad)).toFloat()
                        ),
                        strokeWidth = if (deg % 60 == 0) 2.dp.toPx() else 1.dp.toPx()
                    )
                }

                // Radial signal cables from Production Hub to each station spoke
                spokes.forEach { desc ->
                    val isActiveSpoke = desc.spoke == selectedSpoke
                    val rad = Math.toRadians(desc.angleDeg)
                    val end = Offset(
                        center.x + (outerR * cos(rad)).toFloat(),
                        center.y + (outerR * sin(rad)).toFloat()
                    )
                    val start = Offset(
                        center.x + (innerR * cos(rad)).toFloat(),
                        center.y + (innerR * sin(rad)).toFloat()
                    )
                    if (isActiveSpoke) {
                        drawLine(
                            color = desc.accent.copy(alpha = 0.28f),
                            start = start,
                            end = end,
                            strokeWidth = 10.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                    drawLine(
                        color = desc.accent.copy(alpha = if (isActiveSpoke) 1f else 0.55f),
                        start = start,
                        end = end,
                        strokeWidth = if (isActiveSpoke) 4.dp.toPx() else 2.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = desc.accent,
                        radius = if (isActiveSpoke) 6.dp.toPx() else 4.dp.toPx(),
                        center = end
                    )
                }

                // Outer Live VU Halo around Hub
                val vuTopLeft = Offset(center.x - innerR * 1.28f, center.y - innerR * 1.28f)
                val vuSize = Size(innerR * 2.56f, innerR * 2.56f)
                drawArc(
                    color = HairlineDivider.copy(alpha = 0.5f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = vuTopLeft,
                    size = vuSize,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
                if (livePeak > 0.01f) {
                    drawArc(
                        color = if (livePeak > 0.85f) TacCoral else AmberHot,
                        startAngle = 135f,
                        sweepAngle = 270f * livePeak.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = vuTopLeft,
                        size = vuSize,
                        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Synced Master Playhead Beat Arc around Production Hub
                val arcTopLeft = Offset(center.x - innerR * 1.13f, center.y - innerR * 1.13f)
                val arcSize = Size(innerR * 2.26f, innerR * 2.26f)
                drawArc(
                    color = HairlineDivider,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
                val sweep = if (syncedPlayhead.isPlaying || playbackState.isPlaying) {
                    (syncedPlayhead.phaseFraction * 360f).coerceAtLeast(18f)
                } else {
                    90f
                }
                drawArc(
                    color = if (syncedPlayhead.isPlaying) EmeraldSignal else NeonCyan,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // 6 Radial Station Spokes
            spokes.forEach { desc ->
                val isActive = desc.spoke == selectedSpoke
                val rad = Math.toRadians(desc.angleDeg)
                val centerDp = wheelSize / 2
                val xOffset = centerDp + orbitRadiusDp * cos(rad).toFloat() - nodeSize / 2
                val yOffset = centerDp + orbitRadiusDp * sin(rad).toFloat() - nodeSize / 2

                Surface(
                    modifier = Modifier
                        .offset(x = xOffset, y = yOffset)
                        .size(width = nodeSize, height = nodeSize * 0.82f)
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectSpoke(desc.spoke) }
                        .testTag("${tagPrefix}spoke_${desc.spoke.name.lowercase()}"),
                    color = if (isActive) desc.accent.copy(alpha = 0.22f) else GunmetalDeck,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        width = if (isActive) 2.5.dp else 1.5.dp,
                        color = desc.accent.copy(alpha = if (isActive) 1f else 0.78f)
                    ),
                    shadowElevation = if (isActive) 8.dp else 4.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = desc.icon,
                            contentDescription = desc.title,
                            tint = desc.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = desc.title,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = desc.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = desc.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Central Production Control Hub
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(hubSize)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleSyncedPlay)
                    .testTag("${tagPrefix}booth_hub_card"),
                color = ObsidianChassis,
                shape = CircleShape,
                border = BorderStroke(
                    2.dp,
                    if (syncedPlayhead.isPlaying) EmeraldSignal else NeonCyan
                ),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (selectedSpoke == StudioSpoke.BOOTH) "BOOTH HUB" else "HUB ➔ ${selectedSpoke.shortLabel}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = NeonCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${syncedPlayhead.bar}:${syncedPlayhead.beat} • ${syncedPlayhead.bpm.toInt()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = if (syncedPlayhead.isPlaying) TacCoral else EmeraldSignal,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("${tagPrefix}booth_sync_play_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (syncedPlayhead.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "Master Sync Play/Stop",
                                tint = ObsidianChassis,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Production Booth Screen (shown on BOOTH spoke; on wide screens showWheel=false because
 * the Circular Studio Wheel is permanently docked in the left console column across all spokes).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CircularStudioHub(
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
    onSelectSpoke: (StudioSpoke) -> Unit,
    onToggleSyncedPlay: () -> Unit,
    onToggleDeckPlay: () -> Unit,
    onTapTempo: () -> Unit,
    onToggleStompBypass: () -> Unit,
    onSelectLiveMode: (LiveSessionMode) -> Unit,
    onImportAudioClick: () -> Unit,
    onGenerateOscTone: () -> Unit,
    onExportWavClick: () -> Unit,
    onExportMp3Click: () -> Unit,
    onStreamTxClick: () -> Unit,
    showWheel: Boolean = true,
    modifier: Modifier = Modifier
) {
    val activeBuf = editorState.processedBuffer ?: editorState.dryBuffer
    val hasAudio = activeBuf != null && activeBuf.frameCount > 0
    val activeLoops = looperState.slots.count { it.isActive && it.buffer != null }
    val activeTracks = timelineState.tracks.count { it.clips.isNotEmpty() }
    val livePeak = if (micTelemetry.isRecording) micTelemetry.peakLevel else playbackState.livePeak
    val liveRms = if (micTelemetry.isRecording) micTelemetry.rmsLevel else playbackState.liveRms

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Status & Master Booth Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = GunmetalDeck,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CIRCULAR PRODUCTION BOOTH",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = editorState.statusBanner,
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HardwareChipToggle(
                    label = stompbox.liveMode.title,
                    selected = true,
                    onClick = { onSelectSpoke(StudioSpoke.LIVE) },
                    accentColor = TacCoral,
                    modifier = Modifier.testTag("booth_live_mode_badge")
                )
            }
        }

        if (showWheel) {
            // Radial 360-degree Circular Studio Diagram (Musician's View)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = GunmetalDeck.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ElevatedBezel)
            ) {
                CircularStudioWheel(
                    selectedSpoke = StudioSpoke.BOOTH,
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
                    onSelectSpoke = onSelectSpoke,
                    onToggleSyncedPlay = onToggleSyncedPlay,
                    modifier = Modifier.padding(6.dp)
                )
            }
        }

        // Master Production Control Strip (Transport, Stomp, Mode, I/O)
        HardwarePanel(borderColor = NeonCyan.copy(alpha = 0.35f)) {
            Text(
                text = "MASTER PRODUCTION CONTROL & BOOTH I/O",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            VuPeakMeterBar(
                peak = livePeak,
                rms = liveRms,
                label = "MST"
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HardwareChipToggle(
                    label = if (playbackState.isPlaying) "PAUSE DECK" else "PLAY DECK",
                    selected = playbackState.isPlaying,
                    onClick = onToggleDeckPlay,
                    accentColor = if (hasAudio) EmeraldSignal else TextSecondary,
                    modifier = Modifier.testTag("booth_deck_play_button")
                )
                HardwareChipToggle(
                    label = "TAP ${syncedPlayhead.bpm.toInt()}",
                    selected = false,
                    onClick = onTapTempo,
                    accentColor = AmberHot,
                    modifier = Modifier.testTag("booth_tap_bpm_button")
                )
                HardwareChipToggle(
                    label = if (stompbox.engaged) "STOMP ON" else "STOMP BYP",
                    selected = stompbox.engaged,
                    onClick = onToggleStompBypass,
                    accentColor = TacCoral,
                    modifier = Modifier.testTag("booth_stomp_toggle_button")
                )
                Button(
                    onClick = onImportAudioClick,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("booth_import_audio_button")
                ) {
                    Icon(Icons.Filled.AudioFile, contentDescription = "Import Audio", tint = ObsidianChassis)
                    Text(" IMPORT -> WAV", color = ObsidianChassis, style = MaterialTheme.typography.labelMedium)
                }
                HardwareChipToggle(
                    label = "OSC TONE",
                    selected = false,
                    onClick = onGenerateOscTone,
                    accentColor = VioletMod,
                    modifier = Modifier.testTag("booth_osc_tone_button")
                )
                HardwareChipToggle(
                    label = "SAVE .WAV",
                    selected = false,
                    onClick = onExportWavClick,
                    accentColor = EmeraldSignal,
                    modifier = Modifier.testTag("booth_export_wav_button")
                )
                HardwareChipToggle(
                    label = "SAVE .MP3",
                    selected = false,
                    onClick = onExportMp3Click,
                    accentColor = EmeraldSignal,
                    modifier = Modifier.testTag("booth_export_mp3_button")
                )
                HardwareChipToggle(
                    label = if (networkState.isTransmitting) "NET TX LIVE" else "NET STREAM",
                    selected = networkState.isTransmitting,
                    onClick = onStreamTxClick,
                    accentColor = AmberHot,
                    modifier = Modifier.testTag("booth_network_tx_button")
                )
            }
        }

        // Studio Session Mode Quick Switcher
        HardwarePanel {
            Text(
                text = "STUDIO WORKFLOW PROFILE",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LiveSessionMode.entries.forEach { mode ->
                    HardwareChipToggle(
                        label = mode.title,
                        selected = stompbox.liveMode == mode,
                        onClick = { onSelectLiveMode(mode) },
                        accentColor = if (mode == LiveSessionMode.STAGE_DJ) TacCoral else if (mode == LiveSessionMode.LIVE_REC) NeonCyan else EmeraldSignal
                    )
                }
            }
        }

        // Booth Telemetry Matrix
        HardwarePanel {
            Text(
                text = "STUDIO TELEMETRY",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "DECK: ${activeBuf?.name ?: "Empty"} • FX: ${fxRack.activeCount}/12 • STOMP: ${if (stompbox.engaged) "ON (${stompbox.liveMode.title})" else "BYPASS"}",
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary
            )
            Text(
                text = "LOOPER: $activeLoops/4 Active • TIMELINE: $activeTracks/4 Tracks (${"%.1f".format(syncedPlayhead.playheadSec)}s) • NET: ${networkState.statusMessage}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            if (editorState.lastLocalExport != null) {
                Text(
                    text = "LAST EXPORT (${editorState.lastLocalExport.formatLabel}): ${editorState.lastLocalExport.displayLocation}",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldSignal
                )
            }
        }
    }
}
