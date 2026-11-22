package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.audio.MicCaptureTelemetry
import com.example.audio.PlaybackState
import com.example.config.NetworkProtocol
import com.example.config.RigConfig
import com.example.dsp.FxRackState
import com.example.dsp.LiveSessionMode
import com.example.dsp.StompboxMasterState
import com.example.export.ExportedAudioResult
import com.example.network.CollabInstrumentStyle
import com.example.network.CollabPeerDevice
import com.example.network.LiveBandRecordingState
import com.example.network.NetworkLinkState
import com.example.studio.LooperState
import com.example.ui.components.BandCollaboratorsStrip
import com.example.ui.components.HardwareChipToggle
import com.example.ui.components.HardwareLedIndicator
import com.example.ui.components.HardwarePanel
import com.example.ui.components.StompboxMiniProcessor
import com.example.ui.components.TacHoldPad
import com.example.ui.components.VuPeakMeterBar
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

/**
 * LIVE SPOKE:
 * Incorporates the Mini Stompbox Master Multi-FX Processor + tailored workflow controls for:
 * 1) Live Stage DJ Performance
 * 2) Live Recording
 * 3) Final Collaborative Audio Studio Release
 * plus complete Rig / Network (IP, Port, TCP/UDP, Sample Rate, Channels, Buffer) & Local/Network Export controls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveSpokeScreen(
    stompbox: StompboxMasterState,
    fxRack: FxRackState,
    rigConfig: RigConfig,
    micTelemetry: MicCaptureTelemetry,
    playbackState: PlaybackState,
    looperState: LooperState,
    networkState: NetworkLinkState,
    hasMicPermission: Boolean,
    hasAudio: Boolean,
    isExporting: Boolean,
    lastLocalExport: ExportedAudioResult?,
    bpm: Float,
    onSelectLiveMode: (LiveSessionMode) -> Unit,
    onUpdateStompbox: (StompboxMasterState.() -> StompboxMasterState) -> Unit,
    onToggleStompBypass: () -> Unit,
    onXyDrag: (Float, Float) -> Unit,
    onTapTempo: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleSyncedPlay: () -> Unit,
    onToggleLoopSlot: (Int) -> Unit,
    onRequestMicPermission: () -> Unit,
    onTacPressStart: () -> Unit,
    onTacPressRelease: () -> Unit,
    onBounceTimelineToEditor: () -> Unit,
    onUpdateConfig: (RigConfig.() -> RigConfig) -> Unit,
    onStreamTx: () -> Unit,
    onStopStream: () -> Unit,
    onToggleListener: () -> Unit = {},
    onCaptureRxToDeck: () -> Unit = {},
    onPushHandoff: () -> Unit = {},
    onGrabHandoff: () -> Unit = {},
    collabPeers: List<CollabPeerDevice> = emptyList(),
    bandRecState: LiveBandRecordingState = LiveBandRecordingState(),
    onOpenCollabDialog: () -> Unit = {},
    onToggleBandRec: () -> Unit = {},
    onSelectMyStyle: (CollabInstrumentStyle) -> Unit = {},
    onExportWav: (String) -> Unit,
    onExportMp3: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var ipText by remember(rigConfig.ipAddress) { mutableStateOf(rigConfig.ipAddress) }
    var portText by remember(rigConfig.port) { mutableStateOf(rigConfig.port.toString()) }
    var bindPortText by remember(rigConfig.bindPort) { mutableStateOf(rigConfig.bindPort.toString()) }
    var nodeNameText by remember(rigConfig.nodeName) { mutableStateOf(rigConfig.nodeName) }
    var releaseName by remember { mutableStateOf("samplfie2_release") }

    val activeAccent = when (stompbox.liveMode) {
        LiveSessionMode.STAGE_DJ -> NeonCyan
        LiveSessionMode.LIVE_REC -> TacCoral
        LiveSessionMode.STUDIO_RELEASE -> EmeraldSignal
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Live Workflow Mode Selector (Stage DJ / Live Rec / Final Collaborative Studio Release)
        HardwarePanel(borderColor = activeAccent.copy(alpha = 0.7f)) {
            Text("LIVE SPOKE · PERFORMANCE & RELEASE HUB", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(stompbox.liveMode.subtitle, style = MaterialTheme.typography.labelSmall, color = activeAccent)
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
                        accentColor = when (mode) {
                            LiveSessionMode.STAGE_DJ -> NeonCyan
                            LiveSessionMode.LIVE_REC -> TacCoral
                            LiveSessionMode.STUDIO_RELEASE -> EmeraldSignal
                        },
                        modifier = Modifier.testTag("live_mode_${mode.name.lowercase()}")
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("MY COLLABORATOR STYLE", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CollabInstrumentStyle.entries.forEach { st ->
                    HardwareChipToggle(
                        label = st.shortTag,
                        selected = bandRecState.myStyle == st,
                        onClick = { onSelectMyStyle(st) },
                        accentColor = NeonCyan
                    )
                }
            }
        }

        // 1b. 4-Device Live Band Collaborators & Sync Recording Strip
        BandCollaboratorsStrip(
            peers = collabPeers,
            bandRecState = bandRecState,
            onOpenCollabDialog = onOpenCollabDialog,
            onToggleBandRec = onToggleBandRec
        )

        // 2. Mini Stompbox Master Multi-FX Processor
        StompboxMiniProcessor(
            stompbox = stompbox,
            bpm = bpm,
            onUpdateStompbox = onUpdateStompbox,
            onToggleBypass = onToggleStompBypass,
            onXyDrag = onXyDrag,
            onTapTempo = onTapTempo
        )

        // 3. Mode-Specific Console Components
        when (stompbox.liveMode) {
            LiveSessionMode.STAGE_DJ -> {
                HardwarePanel(borderColor = NeonCyan.copy(alpha = 0.5f), modifier = Modifier.testTag("live_stage_panel")) {
                    Text("STAGE DJ DECK & LOOPER LAUNCHER", style = MaterialTheme.typography.titleSmall, color = NeonCyan)
                    Spacer(modifier = Modifier.height(6.dp))
                    VuPeakMeterBar(peak = playbackState.livePeak, rms = playbackState.liveRms, label = "STAGE")
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HardwareChipToggle(
                            label = if (playbackState.isPlaying) "PAUSE DECK" else "CUE PLAY",
                            selected = playbackState.isPlaying,
                            onClick = onTogglePlay,
                            accentColor = EmeraldSignal
                        )
                        HardwareChipToggle(
                            label = "SYNC BOOTH",
                            selected = looperState.isRunning,
                            onClick = onToggleSyncedPlay,
                            accentColor = MagentaFx
                        )
                        looperState.slots.forEach { slot ->
                            HardwareChipToggle(
                                label = "${slot.label} ${if (slot.isActive) "ON" else "OFF"}",
                                selected = slot.isActive,
                                onClick = { onToggleLoopSlot(slot.id) },
                                accentColor = NeonCyan
                            )
                        }
                    }
                }
            }

            LiveSessionMode.LIVE_REC -> {
                HardwarePanel(borderColor = TacCoral.copy(alpha = 0.5f), modifier = Modifier.testTag("live_rec_panel")) {
                    Text("LIVE RECORDING CAPTURE & METERING", style = MaterialTheme.typography.titleSmall, color = TacCoral)
                    Spacer(modifier = Modifier.height(6.dp))
                    VuPeakMeterBar(peak = micTelemetry.peakLevel, rms = micTelemetry.rmsLevel, label = "MIC")
                    Spacer(modifier = Modifier.height(8.dp))
                    TacHoldPad(
                        hasMicPermission = hasMicPermission,
                        isRecording = micTelemetry.isRecording,
                        elapsedMs = micTelemetry.elapsedMs,
                        onRequestMicPermission = onRequestMicPermission,
                        onPressStart = onTacPressStart,
                        onPressRelease = onTacPressRelease
                    )
                }
            }

            LiveSessionMode.STUDIO_RELEASE -> {
                HardwarePanel(borderColor = EmeraldSignal.copy(alpha = 0.5f), modifier = Modifier.testTag("live_release_panel")) {
                    Text("COLLABORATIVE STUDIO RELEASE SUITE", style = MaterialTheme.typography.titleSmall, color = EmeraldSignal)
                    Text(
                        text = "FX Rack ${fxRack.activeCount}/12 • Bounce timeline mixdown & publish locally or over IP",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HardwareChipToggle(
                            label = "BOUNCE TIMELINE",
                            selected = false,
                            onClick = onBounceTimelineToEditor,
                            accentColor = VioletMod
                        )
                        HardwareChipToggle(
                            label = if (playbackState.isPlaying) "STOP PREVIEW" else "MASTER AUDITION",
                            selected = playbackState.isPlaying,
                            onClick = onTogglePlay,
                            accentColor = EmeraldSignal
                        )
                    }
                }
            }
        }

        // 4. Local Release Export (.WAV & .MP3)
        HardwarePanel(borderColor = EmeraldSignal.copy(alpha = 0.45f)) {
            Text("LOCAL EXPORT (.WAV & .MP3 TO MUSIC/SAMPLFIE2)", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = releaseName,
                onValueChange = { releaseName = it },
                label = { Text("RELEASE FILENAME") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EmeraldSignal,
                    unfocusedBorderColor = HairlineDivider,
                    focusedLabelColor = EmeraldSignal,
                    unfocusedLabelColor = TextSecondary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("mp3_filename_input")
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text("MP3 BITRATE", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RigConfig.SUPPORTED_MP3_BITRATES.forEach { br ->
                    HardwareChipToggle(
                        label = "$br kbps",
                        selected = rigConfig.mp3BitrateKbps == br,
                        onClick = { onUpdateConfig { copy(mp3BitrateKbps = br) } },
                        accentColor = EmeraldSignal
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onExportWav(releaseName) },
                    enabled = hasAudio && !isExporting,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("rig_export_wav_button")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = "Save WAV", tint = ObsidianChassis)
                    Text(" SAVE .WAV", color = ObsidianChassis, style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = { onExportMp3(releaseName) },
                    enabled = hasAudio && !isExporting,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldSignal),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("rig_export_mp3_button")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = "Save MP3", tint = ObsidianChassis)
                    Text(
                        text = if (isExporting) " WORKING…" else " SAVE .MP3",
                        color = ObsidianChassis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (lastLocalExport != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "LAST EXPORT (${lastLocalExport.formatLabel}): ${lastLocalExport.displayLocation} (${lastLocalExport.byteSize / 1024} KB)",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldSignal
                )
            }
        }

        // 5. Network Collaboration Stream & Audio Engine Format (IP, Port, TCP/UDP, Sample Rate, Channels, Buffer)
        HardwarePanel(borderColor = if (networkState.isTransmitting) NeonCyan else HairlineDivider) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("NETWORK COLLAB TX & ENGINE FORMAT", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                    Text(networkState.statusMessage, style = MaterialTheme.typography.labelSmall, color = NeonCyan)
                }
                HardwareLedIndicator(
                    active = networkState.isTransmitting || networkState.packetsSent > 0,
                    color = if (networkState.errorMessage != null) TacCoral else EmeraldSignal,
                    sizeDp = 12
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { next ->
                        ipText = next
                        onUpdateConfig { copy(ipAddress = next) }
                    },
                    label = { Text("IP ADDRESS") },
                    singleLine = true,
                    isError = !rigConfig.isValidIpOrHost(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = HairlineDivider,
                        focusedLabelColor = NeonCyan,
                        unfocusedLabelColor = TextSecondary
                    ),
                    modifier = Modifier
                        .weight(1.7f)
                        .testTag("config_ip_input")
                )

                OutlinedTextField(
                    value = portText,
                    onValueChange = { next ->
                        portText = next.filter { it.isDigit() }.take(5)
                        val parsed = portText.toIntOrNull()
                        if (parsed != null && parsed in 1..65535) {
                            onUpdateConfig { copy(port = parsed) }
                        }
                    },
                    label = { Text("PORT") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = portText.toIntOrNull()?.let { it !in 1..65535 } ?: true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = HairlineDivider,
                        focusedLabelColor = NeonCyan,
                        unfocusedLabelColor = TextSecondary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("config_port_input")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetworkProtocol.entries.forEach { proto ->
                    HardwareChipToggle(
                        label = proto.label,
                        selected = rigConfig.protocol == proto,
                        onClick = { onUpdateConfig { copy(protocol = proto) } },
                        accentColor = NeonCyan,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("config_protocol_${proto.name.lowercase()}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = if (networkState.isTransmitting) onStopStream else onStreamTx,
                enabled = hasAudio || networkState.isTransmitting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (networkState.isTransmitting) TacCoral else NeonCyan
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("rig_stream_tx_button")
            ) {
                Icon(
                    imageVector = if (networkState.isTransmitting) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Transmit Network Stream",
                    tint = ObsidianChassis
                )
                Text(
                    text = if (networkState.isTransmitting) " STOP NETWORK TX" else " EXPORT OVER ${rigConfig.protocol.label} STREAM",
                    color = ObsidianChassis,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text("EQUAL PEER RX LISTENER & SESSION HANDOFF", style = MaterialTheme.typography.labelMedium, color = VioletMod)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nodeNameText,
                    onValueChange = { next ->
                        nodeNameText = next
                        onUpdateConfig { copy(nodeName = next) }
                    },
                    label = { Text("NODE NAME") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VioletMod,
                        unfocusedBorderColor = HairlineDivider,
                        focusedLabelColor = VioletMod,
                        unfocusedLabelColor = TextSecondary
                    ),
                    modifier = Modifier
                        .weight(1.7f)
                        .testTag("config_node_name_input")
                )
                OutlinedTextField(
                    value = bindPortText,
                    onValueChange = { next ->
                        bindPortText = next.filter { it.isDigit() }.take(5)
                        val parsed = bindPortText.toIntOrNull()
                        if (parsed != null && parsed in 1..65535) {
                            onUpdateConfig { copy(bindPort = parsed) }
                        }
                    },
                    label = { Text("RX BIND") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VioletMod,
                        unfocusedBorderColor = HairlineDivider,
                        focusedLabelColor = VioletMod,
                        unfocusedLabelColor = TextSecondary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("config_bind_port_input")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HardwareChipToggle(
                    label = if (networkState.isListening) "RX ON :${rigConfig.bindPort}" else "START RX :${rigConfig.bindPort}",
                    selected = networkState.isListening,
                    onClick = onToggleListener,
                    accentColor = EmeraldSignal,
                    modifier = Modifier.testTag("rig_listener_button")
                )
                HardwareChipToggle(
                    label = "LOAD RX -> DECK",
                    selected = networkState.rxPackets > 0L,
                    onClick = onCaptureRxToDeck,
                    accentColor = NeonCyan,
                    modifier = Modifier.testTag("rig_capture_rx_button")
                )
                HardwareChipToggle(
                    label = "PUSH HANDOFF",
                    selected = false,
                    onClick = onPushHandoff,
                    accentColor = VioletMod,
                    modifier = Modifier.testTag("rig_push_handoff_button")
                )
                HardwareChipToggle(
                    label = "GRAB HANDOFF",
                    selected = false,
                    onClick = onGrabHandoff,
                    accentColor = AmberHot,
                    modifier = Modifier.testTag("rig_grab_handoff_button")
                )
                HardwareChipToggle(
                    label = if (rigConfig.autoAcceptIncomingHandoff) "AUTO-ACCEPT ON" else "AUTO-ACCEPT OFF",
                    selected = rigConfig.autoAcceptIncomingHandoff,
                    onClick = { onUpdateConfig { copy(autoAcceptIncomingHandoff = !autoAcceptIncomingHandoff) } },
                    accentColor = EmeraldSignal
                )
                HardwareChipToggle(
                    label = if (rigConfig.autoResumeOnHandoff) "AUTO-PLAY ON" else "AUTO-PLAY OFF",
                    selected = rigConfig.autoResumeOnHandoff,
                    onClick = { onUpdateConfig { copy(autoResumeOnHandoff = !autoResumeOnHandoff) } },
                    accentColor = AmberHot
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text("SAMPLE RATE", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RigConfig.SUPPORTED_SAMPLE_RATES.forEach { sr ->
                    HardwareChipToggle(
                        label = "${sr / 1000.0} kHz".replace(".0", ""),
                        selected = rigConfig.sampleRate == sr,
                        onClick = { onUpdateConfig { copy(sampleRate = sr) } },
                        accentColor = AmberHot,
                        modifier = Modifier.testTag("config_sr_$sr")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("CHANNELS", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RigConfig.SUPPORTED_CHANNELS.forEach { ch ->
                    HardwareChipToggle(
                        label = if (ch == 1) "MONO (1 CH)" else "STEREO (2 CH)",
                        selected = rigConfig.channels == ch,
                        onClick = { onUpdateConfig { copy(channels = ch) } },
                        accentColor = EmeraldSignal,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("config_ch_$ch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("BUFFER SIZE (FRAMES)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RigConfig.SUPPORTED_BUFFERS.forEach { buf ->
                    HardwareChipToggle(
                        label = "$buf",
                        selected = rigConfig.bufferSize == buf,
                        onClick = { onUpdateConfig { copy(bufferSize = buf) } },
                        accentColor = VioletMod,
                        modifier = Modifier.testTag("config_buf_$buf")
                    )
                }
            }
        }
    }
}
