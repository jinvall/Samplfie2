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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.config.NetworkProtocol
import com.example.config.RigConfig
import com.example.export.ExportedMp3Result
import com.example.midi.MidiState
import com.example.network.NetworkLinkState
import com.example.ui.components.HardwareChipToggle
import com.example.ui.components.HardwareLedIndicator
import com.example.ui.components.HardwarePanel
import com.example.ui.theme.AmberHot
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianChassis
import com.example.ui.theme.TacCoral
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletMod

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RigLinkMidiScreen(
    rigConfig: RigConfig,
    networkState: NetworkLinkState,
    midiState: MidiState,
    hasAudio: Boolean,
    isExportingMp3: Boolean,
    lastExport: ExportedMp3Result?,
    onUpdateConfig: (RigConfig.() -> RigConfig) -> Unit,
    onStreamTx: () -> Unit,
    onStopStream: () -> Unit,
    onExportMp3: (String) -> Unit,
    onRefreshMidi: () -> Unit,
    modifier: Modifier = Modifier
) {
    var ipText by remember(rigConfig.ipAddress) { mutableStateOf(rigConfig.ipAddress) }
    var portText by remember(rigConfig.port) { mutableStateOf(rigConfig.port.toString()) }
    var mp3FileName by remember { mutableStateOf("samplfie2_take") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Network Link Configuration (IP, Port, TCP/UDP)
        HardwarePanel(borderColor = if (networkState.isTransmitting) NeonCyan else HairlineDivider) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("NETWORK AUDIO LINK", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(networkState.statusMessage, style = MaterialTheme.typography.labelSmall, color = NeonCyan)
                }
                HardwareLedIndicator(
                    active = networkState.isTransmitting || networkState.packetsSent > 0,
                    color = if (networkState.errorMessage != null) TacCoral else EmeraldSignal,
                    sizeDp = 12
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

            Spacer(modifier = Modifier.height(10.dp))
            Text("TRANSPORT PROTOCOL", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = if (networkState.isTransmitting) onStopStream else onStreamTx,
                    enabled = hasAudio || networkState.isTransmitting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (networkState.isTransmitting) TacCoral else NeonCyan
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("rig_stream_tx_button")
                ) {
                    Icon(
                        imageVector = if (networkState.isTransmitting) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Stream Audio",
                        tint = ObsidianChassis
                    )
                    Text(
                        text = if (networkState.isTransmitting) " STOP TX" else " TRANSMIT ${rigConfig.protocol.label} PACKETS",
                        color = ObsidianChassis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (networkState.packetsSent > 0 || networkState.errorMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = networkState.errorMessage ?: "SENT ${networkState.packetsSent} PKTS (${networkState.bytesSent} BYTES)",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (networkState.errorMessage != null) TacCoral else EmeraldSignal
                )
            }
        }

        // 2. Audio Format Configuration (Sample Rate, Channels, Buffer Size)
        HardwarePanel {
            Text("AUDIO ENGINE FORMAT", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))

            Text("SAMPLE RATE", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(modifier = Modifier.height(6.dp))
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

            Spacer(modifier = Modifier.height(10.dp))
            Text("CHANNELS", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

            Spacer(modifier = Modifier.height(10.dp))
            Text("BUFFER SIZE (FRAMES)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(modifier = Modifier.height(6.dp))
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

        // 3. Device MP3 Export Card
        HardwarePanel(borderColor = EmeraldSignal.copy(alpha = 0.45f)) {
            Text("EXPORT ON DEVICE AS .MP3", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = mp3FileName,
                onValueChange = { mp3FileName = it },
                label = { Text("MP3 FILENAME") },
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
            Spacer(modifier = Modifier.height(6.dp))
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
            Button(
                onClick = { onExportMp3(mp3FileName) },
                enabled = hasAudio && !isExportingMp3,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldSignal),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("rig_export_mp3_button")
            ) {
                Icon(Icons.Filled.Download, contentDescription = "Save MP3", tint = ObsidianChassis)
                Text(
                    text = if (isExportingMp3) " ENCODING MP3…" else " SAVE TO MUSIC/SAMPLFIE2 (.MP3)",
                    color = ObsidianChassis,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            if (lastExport != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "LAST EXPORT: ${lastExport.displayLocation} (${lastExport.byteSize / 1024} KB)",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldSignal
                )
            }
        }

        // 4. MIDI Hardware Controller & Live Monitor
        HardwarePanel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("MIDI HARDWARE INTERFACE", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(
                        text = if (midiState.isSupported) {
                            "${midiState.connectedDevices.size} DEVICE(S) • ${midiState.syncedBpm?.let { "CLK ${it.toInt()} BPM" } ?: "NO CLOCK"}"
                        } else {
                            "ANDROID MIDI NOT PRESENT ON THIS DEVICE"
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
                text = "MAP: Notes 36-39 Loop Pads • Notes 40-43 Drum Lanes • Notes 48-72 Chromatic Pitch • CC1/74 Wah • CC71 Drive • CC91 Reverb • CC93 Echo • CC7 Gain",
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
                Text("LIVE MIDI LOG", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                midiState.recentLog.take(5).forEach { entry ->
                    Text(entry, style = MaterialTheme.typography.labelSmall, color = NeonCyan)
                }
            }
        }
    }
}
