package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.net.Uri
import com.example.R
import com.example.audio.MicCaptureTelemetry
import com.example.audio.PlaybackState
import com.example.dsp.FxRackState
import com.example.network.NetworkLinkState
import com.example.ui.EditorState
import com.example.ui.SavedTakeItem
import com.example.ui.VisualizerMode
import com.example.ui.components.AudioVisualizerPanel
import com.example.ui.components.HardwareChipToggle
import com.example.ui.components.HardwarePanel
import com.example.ui.components.HardwareParameterSlider
import com.example.ui.components.TacHoldPad
import com.example.ui.components.VuPeakMeterBar
import com.example.ui.theme.AmberHot
import com.example.ui.theme.ElevatedBezel
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianChassis
import com.example.ui.theme.TacCoral
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletMod

@Composable
fun DeckEditorScreen(
    editorState: EditorState,
    micTelemetry: MicCaptureTelemetry,
    playbackState: PlaybackState,
    fxRack: FxRackState,
    networkState: NetworkLinkState,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onTacPressStart: () -> Unit,
    onTacPressRelease: () -> Unit,
    onGenerateOscTone: () -> Unit,
    onImportAudioFile: () -> Unit,
    onVisualizerModeChange: (VisualizerMode) -> Unit,
    onSelectionChange: (Float, Float) -> Unit,
    onSeekPlayhead: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onToggleLoop: () -> Unit,
    onTogglePlaySelectionOnly: () -> Unit,
    onToggleMasterBypass: () -> Unit,
    onExtractSelection: () -> Unit,
    onUndo: () -> Unit,
    onResetToOriginal: () -> Unit,
    onOpenFxRack: () -> Unit,
    onSendToLooperSlot: (Int) -> Unit,
    onSendToSequencer: () -> Unit,
    onAddToTimeline: () -> Unit,
    onStreamTx: () -> Unit,
    onExportMp3Click: () -> Unit,
    onLoadSavedTake: (SavedTakeItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeBuf = editorState.processedBuffer ?: editorState.dryBuffer
    val hasAudio = activeBuf != null && activeBuf.frameCount > 0
    val liveSpectrum = when {
        micTelemetry.isRecording -> micTelemetry.liveSpectrum
        playbackState.isPlaying -> playbackState.liveSpectrum
        else -> editorState.staticSpectrum
    }
    val livePeak = if (micTelemetry.isRecording) micTelemetry.peakLevel else playbackState.livePeak
    val liveRms = if (micTelemetry.isRecording) micTelemetry.rmsLevel else playbackState.liveRms

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Status Banner + Quick OSC generator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = editorState.statusBanner,
                style = MaterialTheme.typography.labelMedium,
                color = if (micTelemetry.isRecording) TacCoral else NeonCyan,
                modifier = Modifier.weight(1f)
            )
            HardwareChipToggle(
                label = "OSC TONE",
                selected = false,
                onClick = onGenerateOscTone,
                accentColor = VioletMod,
                modifier = Modifier.testTag("osc_tone_button")
            )
            HardwareChipToggle(
                label = stringResource(R.string.btn_import_audio),
                selected = false,
                onClick = onImportAudioFile,
                accentColor = NeonCyan,
                modifier = Modifier.testTag("import_audio_button")
            )
        }

        // Real-Time Audio Visualization Component
        AudioVisualizerPanel(
            dryPeaks = editorState.dryPeaks,
            processedPeaks = editorState.processedPeaks,
            liveSpectrum = liveSpectrum,
            liveMicWaveform = micTelemetry.liveWaveform,
            isRecording = micTelemetry.isRecording,
            isPlaying = playbackState.isPlaying,
            playheadFraction = playbackState.playheadFraction,
            selectionStart = editorState.selectionStart,
            selectionEnd = editorState.selectionEnd,
            durationSec = activeBuf?.durationSeconds ?: 0f,
            activeFxCount = fxRack.activeCount,
            isMasterBypass = fxRack.masterBypass,
            visualizerMode = editorState.visualizerMode,
            onModeChange = onVisualizerModeChange,
            onSelectionChange = onSelectionChange,
            onSeekPlayhead = onSeekPlayhead
        )

        // Live VU Meter
        HardwarePanel {
            VuPeakMeterBar(
                peak = livePeak,
                rms = liveRms,
                label = if (micTelemetry.isRecording) "MIC" else "OUT"
            )
        }

        // Precision IN / OUT Selection Controls + Extract / Undo / Reset
        if (hasAudio) {
            HardwarePanel {
                Text(
                    text = "SELECTION EXTRACTOR & TRIM",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        HardwareParameterSlider(
                            label = "IN MARKER",
                            valueText = "${"%.2f".format(editorState.selectionStart * activeBuf.durationSeconds)}s",
                            value = editorState.selectionStart,
                            valueRange = 0f..0.96f,
                            onValueChange = { onSelectionChange(it, editorState.selectionEnd) },
                            accentColor = NeonCyan,
                            testTag = "selection_in_slider"
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        HardwareParameterSlider(
                            label = "OUT MARKER",
                            valueText = "${"%.2f".format(editorState.selectionEnd * activeBuf.durationSeconds)}s",
                            value = editorState.selectionEnd,
                            valueRange = 0.04f..1f,
                            onValueChange = { onSelectionChange(editorState.selectionStart, it) },
                            accentColor = AmberHot,
                            testTag = "selection_out_slider"
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onExtractSelection,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("extract_selection_button")
                    ) {
                        Icon(Icons.Filled.ContentCut, contentDescription = null, tint = ObsidianChassis)
                        Text(
                            text = " ${stringResource(R.string.btn_extract)}",
                            color = ObsidianChassis,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    OutlinedButton(
                        onClick = onUndo,
                        enabled = editorState.canUndo,
                        border = BorderStroke(1.dp, if (editorState.canUndo) TextSecondary else HairlineDivider),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("undo_extract_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo Extract")
                    }
                    OutlinedButton(
                        onClick = onResetToOriginal,
                        border = BorderStroke(1.dp, HairlineDivider),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("reset_wav_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reset Original WAV")
                    }
                }
            }
        }

        // DJ Transport Review Strip (Always Reviewable)
        HardwarePanel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onTogglePlay,
                    enabled = hasAudio,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (playbackState.isPlaying) AmberHot else EmeraldSignal
                    ),
                    modifier = Modifier
                        .weight(1.3f)
                        .minimumInteractiveComponentSize()
                        .testTag("transport_play_button")
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                        tint = ObsidianChassis
                    )
                    Text(
                        text = if (playbackState.isPlaying) " PAUSE" else " PLAY",
                        color = ObsidianChassis,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                OutlinedButton(
                    onClick = onStop,
                    enabled = hasAudio,
                    border = BorderStroke(1.dp, HairlineDivider),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("transport_stop_button")
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }

                HardwareChipToggle(
                    label = "LOOP",
                    selected = playbackState.isLooping,
                    onClick = onToggleLoop,
                    accentColor = NeonCyan,
                    modifier = Modifier.testTag("transport_loop_button")
                )

                HardwareChipToggle(
                    label = "SEL",
                    selected = editorState.playSelectionOnly,
                    onClick = onTogglePlaySelectionOnly,
                    accentColor = AmberHot,
                    modifier = Modifier.testTag("transport_sel_only_button")
                )

                HardwareChipToggle(
                    label = "A/B",
                    selected = fxRack.masterBypass,
                    onClick = onToggleMasterBypass,
                    accentColor = TacCoral,
                    modifier = Modifier.testTag("transport_bypass_button")
                )
            }
        }

        // Tactile Hold-to-Record TAC Pad
        TacHoldPad(
            hasMicPermission = hasMicPermission,
            isRecording = micTelemetry.isRecording,
            elapsedMs = micTelemetry.elapsedMs,
            onRequestMicPermission = onRequestMicPermission,
            onPressStart = onTacPressStart,
            onPressRelease = onTacPressRelease
        )

        // Performance Routing & Quick Actions
        if (hasAudio) {
            HardwarePanel {
                Text(
                    text = "LIVE ROUTING & EXPORT",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HardwareChipToggle(
                        label = "FX RACK (${fxRack.activeCount})",
                        selected = fxRack.activeCount > 0,
                        onClick = onOpenFxRack,
                        accentColor = VioletMod,
                        modifier = Modifier.testTag("route_fx_rack")
                    )
                    HardwareChipToggle(
                        label = "LOOP A",
                        selected = false,
                        onClick = { onSendToLooperSlot(0) },
                        accentColor = NeonCyan
                    )
                    HardwareChipToggle(
                        label = "LOOP B",
                        selected = false,
                        onClick = { onSendToLooperSlot(1) },
                        accentColor = NeonCyan
                    )
                    HardwareChipToggle(
                        label = "TO SEQ",
                        selected = false,
                        onClick = onSendToSequencer,
                        accentColor = AmberHot
                    )
                    HardwareChipToggle(
                        label = "TO TIMELINE",
                        selected = false,
                        onClick = onAddToTimeline,
                        accentColor = EmeraldSignal
                    )
                    HardwareChipToggle(
                        label = if (networkState.isTransmitting) "TX LIVE…" else "STREAM TX",
                        selected = networkState.isTransmitting,
                        onClick = onStreamTx,
                        accentColor = NeonCyan,
                        modifier = Modifier.testTag("route_stream_tx")
                    )
                    HardwareChipToggle(
                        label = "SAVE MP3",
                        selected = false,
                        onClick = onExportMp3Click,
                        accentColor = EmeraldSignal,
                        modifier = Modifier.testTag("route_save_mp3")
                    )
                }
            }
        }

        // Saved .WAV Session Takes Bank
        if (editorState.savedTakes.isNotEmpty()) {
            HardwarePanel {
                Text(
                    text = "CAPTURED .WAV TAKES",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    editorState.savedTakes.forEach { take ->
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onLoadSavedTake(take) },
                            color = ElevatedBezel,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, HairlineDivider)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Filled.Waves, contentDescription = null, tint = NeonCyan)
                                Column {
                                    Text(take.name, style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                                    Text("${"%.2f".format(take.durationSec)}s", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
