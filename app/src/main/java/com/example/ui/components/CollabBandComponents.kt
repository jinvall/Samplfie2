package com.example.ui.components

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.network.CollabInstrumentStyle
import com.example.network.CollabPeerDevice
import com.example.network.LiveBandRecordingState
import com.example.network.MAX_COLLAB_PEERS
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
import kotlin.math.roundToInt

/**
 * Slim, always-accessible Master Volume + Mute row for the Persistent Production Hub.
 */
@Composable
fun MasterVolumeCompactRow(
    masterVolume: Float,
    isMasterMuted: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pct = if (isMasterMuted) "MUTE" else "${(masterVolume.coerceIn(0f, 1f) * 100f).roundToInt()}%"
    val accent = if (isMasterMuted) TacCoral else EmeraldSignal
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            color = if (isMasterMuted) TacCoral.copy(alpha = 0.22f) else ObsidianChassis,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, if (isMasterMuted) TacCoral else HairlineDivider),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggleMute)
                .testTag("hub_master_mute_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = if (isMasterMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (isMasterMuted) "Unmute Master Volume" else "Mute Master Volume",
                    tint = accent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "VOL",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = accent
                )
            }
        }
        Slider(
            value = masterVolume.coerceIn(0f, 1f),
            onValueChange = onVolumeChange,
            valueRange = 0f..1f,
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
                .testTag("hub_master_volume_slider"),
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = ObsidianChassis
            )
        )
        Text(
            text = pct,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = accent,
            modifier = Modifier
                .width(38.dp)
                .testTag("hub_master_volume_label")
        )
    }
}

/**
 * Obvious connection status banner & 1-tap Live Band Recording launcher shown inside Booth / Live spokes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BandCollaboratorsStrip(
    peers: List<CollabPeerDevice>,
    bandRecState: LiveBandRecordingState,
    onOpenCollabDialog: () -> Unit,
    onToggleBandRec: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connected = peers.filter { it.isConnected }
    val nearbyCount = peers.count { it.isNearby && !it.isConnected }
    val accent = when {
        bandRecState.isRecording -> TacCoral
        connected.isNotEmpty() -> EmeraldSignal
        nearbyCount > 0 -> AmberHot
        else -> NeonCyan
    }

    HardwarePanel(
        borderColor = accent.copy(alpha = 0.65f),
        modifier = modifier.testTag("band_collab_strip")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "4-DEVICE BAND COLLAB (${connected.size}/$MAX_COLLAB_PEERS LINKED)",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = accent
                )
                Text(
                    text = "MY STYLE: ${bandRecState.myStyle.label.uppercase()} -> TRACK ${bandRecState.myTrackSlot + 1} • $nearbyCount NEARBY",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HardwareChipToggle(
                    label = "CONNECT (${connected.size}/4)",
                    selected = connected.isNotEmpty(),
                    onClick = onOpenCollabDialog,
                    accentColor = NeonCyan,
                    modifier = Modifier.testTag("band_open_connect_button")
                )
                Button(
                    onClick = onToggleBandRec,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (bandRecState.isRecording) TacCoral else EmeraldSignal
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("band_strip_rec_button")
                ) {
                    Icon(
                        imageVector = if (bandRecState.isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = "Toggle Live Band Recording",
                        tint = ObsidianChassis,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (bandRecState.isRecording) "STOP REC" else "BAND REC",
                        color = ObsidianChassis,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
        if (connected.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                connected.forEach { p ->
                    Surface(
                        color = ObsidianChassis,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, EmeraldSignal.copy(alpha = 0.7f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            HardwareLedIndicator(
                                active = true,
                                color = if (p.isRecording) TacCoral else EmeraldSignal,
                                sizeDp = 7
                            )
                            Text(
                                text = "${p.name} • ${p.style.shortTag} -> T${p.trackSlot + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }
        if (bandRecState.lastRecordedSummary.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = bandRecState.lastRecordedSummary,
                style = MaterialTheme.typography.labelSmall,
                color = EmeraldSignal
            )
        }
    }
}

/**
 * Interactive 4-Device Live Band Collaborator Connection, Style Picker & Synced Rec Modal.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollabConnectDialog(
    peers: List<CollabPeerDevice>,
    bandRecState: LiveBandRecordingState,
    defaultHost: String,
    defaultPort: Int,
    nodeName: String,
    onRefreshDiscovery: () -> Unit,
    onSelectMyStyle: (CollabInstrumentStyle, Int) -> Unit,
    onConnectPeer: (host: String, port: Int, name: String, platform: String, style: CollabInstrumentStyle?) -> Unit,
    onDisconnectPeer: (peerId: String) -> Unit,
    onRemoveKnownPeer: (peerId: String) -> Unit,
    onUpdatePeerStyleAndTrack: (peerId: String, style: CollabInstrumentStyle, trackSlot: Int) -> Unit,
    onToggleBandRec: () -> Unit,
    onDismiss: () -> Unit
) {
    val connectedCount = peers.count { it.isConnected }
    var hostInput by remember(defaultHost) { mutableStateOf(defaultHost) }
    var portInput by remember(defaultPort) { mutableStateOf(defaultPort.toString()) }
    var nameInput by remember { mutableStateOf("Bandmate Rig") }
    var selectedAddStyle by remember { mutableStateOf(CollabInstrumentStyle.GUITAR) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GunmetalDeck,
        modifier = Modifier.testTag("collab_connect_dialog"),
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
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    bandRecState.isRecording -> TacCoral
                                    connectedCount > 0 -> EmeraldSignal
                                    else -> AmberHot
                                }
                            )
                    )
                    Column {
                        Text(
                            text = "BAND LINK ($connectedCount/$MAX_COLLAB_PEERS DEVICES)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "$nodeName • UDP LAN BEACON + 4-TRACK SYNC REC",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onRefreshDiscovery,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("collab_scan_lan_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Scan LAN for nearby devices",
                        tint = NeonCyan
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Local Device Instrument Style & Track Routing
                Surface(
                    color = ObsidianChassis,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "THIS DEVICE STYLE (${bandRecState.myStyle.label.uppercase()} -> TRACK ${bandRecState.myTrackSlot + 1})",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = NeonCyan
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CollabInstrumentStyle.entries.forEach { style ->
                                HardwareChipToggle(
                                    label = style.shortTag,
                                    selected = bandRecState.myStyle == style,
                                    onClick = { onSelectMyStyle(style, style.defaultTrack) },
                                    accentColor = NeonCyan,
                                    modifier = Modifier.testTag("collab_my_style_${style.name.lowercase()}")
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("TRACK:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            for (slot in 0..3) {
                                HardwareChipToggle(
                                    label = "T${slot + 1}",
                                    selected = bandRecState.myTrackSlot == slot,
                                    onClick = { onSelectMyStyle(bandRecState.myStyle, slot) },
                                    accentColor = EmeraldSignal,
                                    modifier = Modifier.testTag("collab_my_track_$slot")
                                )
                            }
                        }
                    }
                }

                // 2. Nearby Discovered & Known Collaborators
                Surface(
                    color = ObsidianChassis,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, EmeraldSignal.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "NEARBY & KNOWN PEERS (${peers.size})",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = EmeraldSignal
                            )
                            Text(
                                text = "MAX $MAX_COLLAB_PEERS LIVE",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        if (peers.isEmpty()) {
                            Text(
                                text = "Scanning LAN for Samplfie2 peers… or tap a preset / enter IP below.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        } else {
                            peers.forEachIndexed { idx, peer ->
                                Surface(
                                    color = BrushedPanel,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        when {
                                            peer.isConnected -> EmeraldSignal
                                            peer.isNearby -> NeonCyan.copy(alpha = 0.7f)
                                            else -> HairlineDivider
                                        }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("collab_peer_row_$idx")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${peer.name} [${peer.platform}]",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${peer.endpoint} • ${if (peer.isConnected) "CONNECTED" else if (peer.isNearby) "NEARBY" else "KNOWN"}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (peer.isConnected) EmeraldSignal else if (peer.isNearby) NeonCyan else TextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Button(
                                                onClick = {
                                                    if (peer.isConnected) {
                                                        onDisconnectPeer(peer.peerId)
                                                    } else {
                                                        onConnectPeer(peer.host, peer.listenPort, peer.name, peer.platform, peer.style)
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (peer.isConnected) TacCoral else EmeraldSignal
                                                ),
                                                modifier = Modifier
                                                    .minimumInteractiveComponentSize()
                                                    .testTag("collab_peer_toggle_$idx")
                                            ) {
                                                Icon(
                                                    imageVector = if (peer.isConnected) Icons.Filled.LinkOff else Icons.Filled.Link,
                                                    contentDescription = null,
                                                    tint = ObsidianChassis,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (peer.isConnected) "DROP" else "LINK",
                                                    color = ObsidianChassis,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                                )
                                            }
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val styles = CollabInstrumentStyle.entries
                                            val nextStyle = styles[(peer.style.ordinal + 1) % styles.size]
                                            HardwareChipToggle(
                                                label = "STYLE: ${peer.style.label}",
                                                selected = true,
                                                onClick = { onUpdatePeerStyleAndTrack(peer.peerId, nextStyle, peer.trackSlot) },
                                                accentColor = AmberHot,
                                                modifier = Modifier.testTag("collab_peer_style_$idx")
                                            )
                                            HardwareChipToggle(
                                                label = "TRK ${(peer.trackSlot % 4) + 1}",
                                                selected = true,
                                                onClick = { onUpdatePeerStyleAndTrack(peer.peerId, peer.style, (peer.trackSlot + 1) % 4) },
                                                accentColor = MagentaFx,
                                                modifier = Modifier.testTag("collab_peer_track_$idx")
                                            )
                                            if (!peer.isConnected) {
                                                TextButton(onClick = { onRemoveKnownPeer(peer.peerId) }) {
                                                    Text("FORGET", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Quick Manual Peer Add + Local/Desktop Presets
                Surface(
                    color = ObsidianChassis,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, HairlineDivider)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "QUICK CONNECT OR ADD DEVICE",
                            style = MaterialTheme.typography.labelMedium,
                            color = VioletMod
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            HardwareChipToggle(
                                label = "DESKTOP HOST (10.0.2.2:9000)",
                                selected = false,
                                onClick = {
                                    onConnectPeer("10.0.2.2", 9000, "Linux Desktop", "DESKTOP", selectedAddStyle)
                                },
                                accentColor = NeonCyan
                            )
                            HardwareChipToggle(
                                label = "LOCALHOST (127.0.0.1:9000)",
                                selected = false,
                                onClick = {
                                    onConnectPeer("127.0.0.1", 9000, "Local Studio", "DESKTOP", selectedAddStyle)
                                },
                                accentColor = AmberHot
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = hostInput,
                                onValueChange = { hostInput = it },
                                label = { Text("Host / IP") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonCyan,
                                    unfocusedBorderColor = HairlineDivider
                                ),
                                modifier = Modifier
                                    .weight(1.6f)
                                    .testTag("collab_add_host_input")
                            )
                            OutlinedTextField(
                                value = portInput,
                                onValueChange = { portInput = it.filter { c -> c.isDigit() }.take(5) },
                                label = { Text("Port") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonCyan,
                                    unfocusedBorderColor = HairlineDivider
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("collab_add_port_input")
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = { nameInput = it },
                                label = { Text("Device Label") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = VioletMod,
                                    unfocusedBorderColor = HairlineDivider
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("collab_add_name_input")
                            )
                            Button(
                                onClick = {
                                    val p = portInput.toIntOrNull()?.coerceIn(1, 65535) ?: 9000
                                    if (hostInput.isNotBlank()) {
                                        onConnectPeer(hostInput.trim(), p, nameInput.ifBlank { "Studio Peer" }, "PEER", selectedAddStyle)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = VioletMod),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("collab_add_connect_button")
                            ) {
                                Icon(Icons.Filled.GroupAdd, contentDescription = null, tint = ObsidianChassis, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("LINK", color = ObsidianChassis)
                            }
                        }
                    }
                }

                // 4. Synchronized Live Band Recording Action
                Button(
                    onClick = onToggleBandRec,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (bandRecState.isRecording) TacCoral else EmeraldSignal
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("collab_band_rec_button")
                ) {
                    Icon(
                        imageVector = if (bandRecState.isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = null,
                        tint = ObsidianChassis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (bandRecState.isRecording) {
                            "STOP SYNCED BAND RECORDING (${connectedCount + 1} DEVICES)"
                        } else {
                            "START LIVE BAND RECORDING (${connectedCount + 1} DEVICES)"
                        },
                        color = ObsidianChassis,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }

                if (bandRecState.lastRecordedSummary.isNotBlank()) {
                    Text(
                        text = bandRecState.lastRecordedSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldSignal
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = NeonCyan)
            }
        }
    )
}
