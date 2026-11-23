package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.audio.AudioSampleFormat
import com.example.audio.MicCaptureTelemetry
import com.example.audio.PlaybackState
import com.example.audio.SpectrogramPalette
import com.example.dsp.FxRackState
import com.example.plugins.PluginCatalogEntry
import com.example.tools.ChannelScope
import com.example.tools.MonoFoldMode
import com.example.tools.StereoSplitMode
import com.example.ui.EditorState
import com.example.ui.ToolsAndPluginsState
import com.example.ui.ToolsSectionTab
import com.example.ui.VisualizerMode
import com.example.ui.components.AudioVisualizerPanel
import com.example.ui.components.HardwareChipToggle
import com.example.ui.components.HardwarePanel
import com.example.ui.components.HardwareParameterSlider
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsAndPluginsScreen(
    editorState: EditorState,
    toolsState: ToolsAndPluginsState,
    playbackState: PlaybackState,
    micTelemetry: MicCaptureTelemetry,
    fxRack: FxRackState,
    onSelectTab: (ToolsSectionTab) -> Unit,
    onVisualizerModeChange: (VisualizerMode) -> Unit,
    onSelectionChange: (Float, Float) -> Unit,
    onSeekPlayhead: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onToggleLoop: () -> Unit,
    onUndo: () -> Unit,
    onSelectPalette: (SpectrogramPalette) -> Unit,
    onApplyCustomHexStops: (String) -> Unit,
    onSpecFreqRangeChange: (Float, Float) -> Unit,
    onSetSpecAttenDb: (Float) -> Unit,
    onApplySpectralDelete: () -> Unit,
    onSetInvertScope: (ChannelScope) -> Unit,
    onSetInvertSelectionOnly: (Boolean) -> Unit,
    onApplyInvert: () -> Unit,
    onPickReferenceAudio: () -> Unit,
    onPickSessionReference: (String) -> Unit,
    onComputeDifference: () -> Unit,
    onLoadDiffToDeck: () -> Unit,
    onUpdateMixParams: (Float, Float, Float, Boolean) -> Unit,
    onApplyMix: () -> Unit,
    onSetMonoMode: (MonoFoldMode) -> Unit,
    onApplyMixToMono: () -> Unit,
    onSetStereoParams: (StereoSplitMode, Float) -> Unit,
    onApplySplitToStereo: () -> Unit,
    onSetRateParams: (Int, Boolean) -> Unit,
    onApplyConvertRate: () -> Unit,
    onSetFormatParams: (AudioSampleFormat, Boolean) -> Unit,
    onApplyConvertFormat: () -> Unit,
    onAddPluginToChain: (String) -> Unit,
    onRemovePluginFromChain: (String) -> Unit,
    onMovePluginInChain: (String, Int) -> Unit,
    onTogglePluginBypass: (String) -> Unit,
    onUpdatePluginWetMix: (String, Float) -> Unit,
    onUpdatePluginParam: (String, String, Float) -> Unit,
    onBouncePluginChain: () -> Unit,
    onInstallCatalogPlugin: (PluginCatalogEntry) -> Unit,
    onUninstallCustomPlugin: (String) -> Unit,
    onImportPluginOrIrFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeBuf = editorState.processedBuffer ?: editorState.dryBuffer
    val hasAudio = activeBuf != null && activeBuf.frameCount > 0
    val liveSpectrum = when {
        micTelemetry.isRecording -> micTelemetry.liveSpectrum
        playbackState.isPlaying -> playbackState.liveSpectrum
        else -> editorState.staticSpectrum
    }

    var showCustomGradientDialog by remember { mutableStateOf(false) }
    var hexInputText by remember(toolsState.specPalette) {
        mutableStateOf(toolsState.specPalette.hexListText)
    }

    if (showCustomGradientDialog) {
        AlertDialog(
            onDismissRequest = { showCustomGradientDialog = false },
            containerColor = GunmetalDeck,
            title = {
                Text("10-STOP SPECTROGRAM GRADIENT", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Paste 10 hex color stops (#RRGGBB) from low energy (stop 1) to peak energy (stop 10).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().height(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        toolsState.specPalette.stopsArgb.forEach { argb ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .background(Color(argb), RoundedCornerShape(2.dp))
                            )
                        }
                    }
                    OutlinedTextField(
                        value = hexInputText,
                        onValueChange = { hexInputText = it },
                        label = { Text("10 Hex Stops (comma or space separated)") },
                        modifier = Modifier.fillMaxWidth().testTag("custom_10stop_hex_input"),
                        minLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onApplyCustomHexStops(hexInputText)
                        showCustomGradientDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    modifier = Modifier.testTag("apply_10stop_hex_button")
                ) {
                    Text("APPLY 10 STOPS", color = ObsidianChassis)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomGradientDialog = false }) {
                    Text("CANCEL")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header Status + Quick Transport / Undo Bar
        HardwarePanel(borderColor = MagentaFx.copy(alpha = 0.7f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PRO AUDIO TOOLS & DSP PLUGIN RACK",
                        style = MaterialTheme.typography.labelLarge,
                        color = MagentaFx
                    )
                    Text(
                        text = if (activeBuf != null) {
                            "${activeBuf.name} • ${activeBuf.sampleRate} Hz • ${activeBuf.channels}CH • ${activeBuf.sampleFormat.shortLabel}"
                        } else {
                            "NO DECK AUDIO LOADED — IMPORT OR RECORD IN DECK"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = toolsState.lastToolReport,
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldSignal
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onTogglePlay,
                        enabled = hasAudio,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("tools_play_button")
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = if (playbackState.isPlaying) AmberHot else EmeraldSignal
                        )
                    }
                    IconButton(
                        onClick = onStop,
                        enabled = hasAudio,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("tools_stop_button")
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = TacCoral)
                    }
                    IconButton(
                        onClick = onUndo,
                        enabled = editorState.canUndo,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("tools_undo_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo Tool Edit",
                            tint = if (editorState.canUndo) NeonCyan else TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HardwareChipToggle(
                    label = if (playbackState.isLooping) "LOOP ON (PERSISTENT)" else "LOOP OFF",
                    selected = playbackState.isLooping,
                    onClick = onToggleLoop,
                    accentColor = NeonCyan
                )
                SpectrogramPalette.PRESETS.forEach { pal ->
                    HardwareChipToggle(
                        label = pal.name.substringBefore(" ("),
                        selected = toolsState.specPalette.id == pal.id,
                        onClick = { onSelectPalette(pal) },
                        accentColor = AmberHot
                    )
                }
                HardwareChipToggle(
                    label = "EDIT 10-STOP HEX",
                    selected = toolsState.specPalette.id == "custom10",
                    onClick = { showCustomGradientDialog = true },
                    accentColor = VioletMod,
                    modifier = Modifier.testTag("open_custom_10stop_button")
                )
            }
        }

        // Combined WaveView + 10-Stop Spectrogram Monitor
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
            activeFxCount = fxRack.activeCount + toolsState.pluginChain.count { it.enabled && !it.bypassed },
            isMasterBypass = fxRack.masterBypass,
            visualizerMode = editorState.visualizerMode,
            onModeChange = onVisualizerModeChange,
            onSelectionChange = onSelectionChange,
            onSeekPlayhead = onSeekPlayhead,
            spectrogram = editorState.spectrogram,
            specPalette = toolsState.specPalette,
            specFreqLowHz = toolsState.specFreqLowHz,
            specFreqHighHz = toolsState.specFreqHighHz,
            onSpecFreqRangeChange = onSpecFreqRangeChange
        )

        // Sub-section Switcher Tabs
        HardwarePanel {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ToolsSectionTab.entries.forEach { tab ->
                    HardwareChipToggle(
                        label = tab.label,
                        selected = toolsState.activeTab == tab,
                        onClick = { onSelectTab(tab) },
                        accentColor = if (tab == ToolsSectionTab.PLUGIN_MANAGER || tab == ToolsSectionTab.PLUGIN_INSTALLER) {
                            MagentaFx
                        } else {
                            NeonCyan
                        },
                        modifier = Modifier.testTag("tools_tab_${tab.name.lowercase()}")
                    )
                }
            }
        }

        when (toolsState.activeTab) {
            ToolsSectionTab.SPECTRAL_PHASE -> {
                // 1. SPECTRAL DELETE
                HardwarePanel(borderColor = TacCoral.copy(alpha = 0.65f)) {
                    Text(
                        text = "SPECTRAL DELETE (STFT TIME × FREQ BOX)",
                        style = MaterialTheme.typography.titleSmall,
                        color = TacCoral
                    )
                    Text(
                        text = "Drag directly on the Spectrogram or refine time/freq sliders below to erase hum, buzz, or unwanted frequencies.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val dur = activeBuf?.durationSeconds ?: 0f
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            HardwareParameterSlider(
                                label = "TIME IN",
                                valueText = "${"%.2f".format(editorState.selectionStart * dur)}s",
                                value = editorState.selectionStart,
                                valueRange = 0f..0.95f,
                                onValueChange = { onSelectionChange(it, editorState.selectionEnd) },
                                accentColor = NeonCyan
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            HardwareParameterSlider(
                                label = "TIME OUT",
                                valueText = "${"%.2f".format(editorState.selectionEnd * dur)}s",
                                value = editorState.selectionEnd,
                                valueRange = 0.05f..1f,
                                onValueChange = { onSelectionChange(editorState.selectionStart, it) },
                                accentColor = AmberHot
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            HardwareParameterSlider(
                                label = "LOW FREQ",
                                valueText = "${toolsState.specFreqLowHz.toInt()} Hz",
                                value = toolsState.specFreqLowHz,
                                valueRange = 20f..18000f,
                                onValueChange = { onSpecFreqRangeChange(it, toolsState.specFreqHighHz) },
                                accentColor = VioletMod
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            HardwareParameterSlider(
                                label = "HIGH FREQ",
                                valueText = "${toolsState.specFreqHighHz.toInt()} Hz",
                                value = toolsState.specFreqHighHz,
                                valueRange = 60f..22000f,
                                onValueChange = { onSpecFreqRangeChange(toolsState.specFreqLowHz, it) },
                                accentColor = VioletMod
                            )
                        }
                    }
                    HardwareParameterSlider(
                        label = "ATTENUATION DEPTH",
                        valueText = if (toolsState.specDeleteAttenDb >= 90f) "FULL ERASE (-96dB)" else "-${toolsState.specDeleteAttenDb.toInt()} dB",
                        value = toolsState.specDeleteAttenDb,
                        valueRange = 6f..96f,
                        onValueChange = onSetSpecAttenDb,
                        accentColor = TacCoral
                    )
                    Button(
                        onClick = onApplySpectralDelete,
                        enabled = hasAudio,
                        colors = ButtonDefaults.buttonColors(containerColor = TacCoral),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("apply_spectral_delete_button")
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = ObsidianChassis)
                        Text(" SPECTRAL DELETE REGION", color = ObsidianChassis)
                    }
                }

                // 2. INVERT (POLARITY FLIP)
                HardwarePanel(borderColor = AmberHot.copy(alpha = 0.65f)) {
                    Text(
                        text = "INVERT POLARITY (180° PHASE FLIP)",
                        style = MaterialTheme.typography.titleSmall,
                        color = AmberHot
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ChannelScope.entries.forEach { sc ->
                            HardwareChipToggle(
                                label = sc.label,
                                selected = toolsState.invertScope == sc,
                                onClick = { onSetInvertScope(sc) },
                                accentColor = AmberHot
                            )
                        }
                        HardwareChipToggle(
                            label = if (toolsState.invertSelectionOnly) "SELECTION ONLY" else "FULL BUFFER",
                            selected = toolsState.invertSelectionOnly,
                            onClick = { onSetInvertSelectionOnly(!toolsState.invertSelectionOnly) },
                            accentColor = NeonCyan
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = onApplyInvert,
                        enabled = hasAudio,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberHot),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("apply_invert_button")
                    ) {
                        Icon(Icons.Filled.Flip, contentDescription = null, tint = ObsidianChassis)
                        Text(" INVERT AUDIO POLARITY", color = ObsidianChassis)
                    }
                }

                // 3. AUDIO DIFFERENCE (A - B)
                HardwarePanel(borderColor = EmeraldSignal.copy(alpha = 0.65f)) {
                    Text(
                        text = "AUDIO DIFFERENCE ANALYZER (DECK A − REF B)",
                        style = MaterialTheme.typography.titleSmall,
                        color = EmeraldSignal
                    )
                    Text(
                        text = "Ref B: ${toolsState.referenceLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPickReferenceAudio,
                            border = BorderStroke(1.dp, NeonCyan),
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("pick_ref_audio_button")
                        ) {
                            Icon(Icons.Filled.AudioFile, contentDescription = null, tint = NeonCyan)
                            Text(" LOAD FILE AS REF B", color = NeonCyan)
                        }
                        listOf("RAW", "DRY", "LOOP_A", "LOOP_B").forEach { tag ->
                            HardwareChipToggle(
                                label = "REF: $tag",
                                selected = false,
                                onClick = { onPickSessionReference(tag) },
                                accentColor = EmeraldSignal
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onComputeDifference,
                            enabled = hasAudio,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldSignal),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("compute_audio_diff_button")
                        ) {
                            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = ObsidianChassis)
                            Text(" COMPUTE DIFF", color = ObsidianChassis)
                        }
                        OutlinedButton(
                            onClick = onLoadDiffToDeck,
                            enabled = toolsState.lastDiffResult != null,
                            border = BorderStroke(1.dp, NeonCyan),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("load_diff_to_deck_button")
                        ) {
                            Text("LOAD DIFF -> DECK", color = NeonCyan)
                        }
                    }
                    toolsState.lastDiffResult?.let { diff ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = ElevatedBezel,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, EmeraldSignal.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "Peak Diff: ${"%.5f".format(diff.peakDiff)}   •   RMS Diff: ${"%.5f".format(diff.rmsDiff)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Null Depth: ${"%.2f".format(diff.nullDepthDb)} dB   •   Phase Corr: ${"%.4f".format(diff.correlation)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldSignal
                                )
                            }
                        }
                    }
                }
            }

            ToolsSectionTab.MIX_CHANNELS -> {
                // MIX
                HardwarePanel(borderColor = NeonCyan.copy(alpha = 0.65f)) {
                    Text(
                        text = "MIX (BLEND DECK A + REFERENCE B)",
                        style = MaterialTheme.typography.titleSmall,
                        color = NeonCyan
                    )
                    Text(
                        text = "Ref B: ${toolsState.referenceLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPickReferenceAudio,
                            border = BorderStroke(1.dp, NeonCyan),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text("LOAD REF FILE", color = NeonCyan)
                        }
                        listOf("LOOP_A", "LOOP_B", "RAW").forEach { tag ->
                            HardwareChipToggle(
                                label = tag,
                                selected = false,
                                onClick = { onPickSessionReference(tag) },
                                accentColor = EmeraldSignal
                            )
                        }
                    }
                    HardwareParameterSlider(
                        label = "DECK A GAIN",
                        valueText = "${(toolsState.mixPrimaryGain * 100).toInt()}%",
                        value = toolsState.mixPrimaryGain,
                        valueRange = 0f..2f,
                        onValueChange = {
                            onUpdateMixParams(it, toolsState.mixSecondaryGain, toolsState.mixOffsetSec, toolsState.mixSoftLimit)
                        },
                        accentColor = NeonCyan
                    )
                    HardwareParameterSlider(
                        label = "REF B GAIN",
                        valueText = "${(toolsState.mixSecondaryGain * 100).toInt()}%",
                        value = toolsState.mixSecondaryGain,
                        valueRange = 0f..2f,
                        onValueChange = {
                            onUpdateMixParams(toolsState.mixPrimaryGain, it, toolsState.mixOffsetSec, toolsState.mixSoftLimit)
                        },
                        accentColor = EmeraldSignal
                    )
                    HardwareParameterSlider(
                        label = "REF B TIME OFFSET",
                        valueText = "+${"%.2f".format(toolsState.mixOffsetSec)}s",
                        value = toolsState.mixOffsetSec,
                        valueRange = 0f..10f,
                        onValueChange = {
                            onUpdateMixParams(toolsState.mixPrimaryGain, toolsState.mixSecondaryGain, it, toolsState.mixSoftLimit)
                        },
                        accentColor = AmberHot
                    )
                    HardwareChipToggle(
                        label = if (toolsState.mixSoftLimit) "SOFT-CLIP LIMITER: ON" else "SOFT-CLIP LIMITER: OFF",
                        selected = toolsState.mixSoftLimit,
                        onClick = {
                            onUpdateMixParams(toolsState.mixPrimaryGain, toolsState.mixSecondaryGain, toolsState.mixOffsetSec, !toolsState.mixSoftLimit)
                        },
                        accentColor = VioletMod
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = onApplyMix,
                        enabled = hasAudio && toolsState.referenceBuffer != null,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("apply_mix_button")
                    ) {
                        Text("RENDER MIX TO DECK", color = ObsidianChassis)
                    }
                }

                // MIX TO MONO
                HardwarePanel(borderColor = AmberHot.copy(alpha = 0.65f)) {
                    Text("MIX TO MONO", style = MaterialTheme.typography.titleSmall, color = AmberHot)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MonoFoldMode.entries.forEach { m ->
                            HardwareChipToggle(
                                label = m.label,
                                selected = toolsState.monoFoldMode == m,
                                onClick = { onSetMonoMode(m) },
                                accentColor = AmberHot
                            )
                        }
                    }
                    Button(
                        onClick = onApplyMixToMono,
                        enabled = hasAudio,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberHot),
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("apply_mix_mono_button")
                    ) {
                        Text("CONVERT TO 1-CH MONO", color = ObsidianChassis)
                    }
                }

                // SPLIT TO STEREO
                HardwarePanel(borderColor = VioletMod.copy(alpha = 0.65f)) {
                    Text("SPLIT TO STEREO", style = MaterialTheme.typography.titleSmall, color = VioletMod)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StereoSplitMode.entries.forEach { m ->
                            HardwareChipToggle(
                                label = m.label,
                                selected = toolsState.stereoSplitMode == m,
                                onClick = { onSetStereoParams(m, toolsState.stereoSplitParam) },
                                accentColor = VioletMod
                            )
                        }
                    }
                    val paramLabel = when (toolsState.stereoSplitMode) {
                        StereoSplitMode.DUAL_MONO -> "GAIN MATCH"
                        StereoSplitMode.HAAS_DELAY -> "HAAS DELAY (ms)"
                        StereoSplitMode.PSEUDO_STEREO_COMB -> "COMB SPACING (ms)"
                        StereoSplitMode.MID_SIDE_WIDTH -> "STEREO WIDTH"
                    }
                    val range = if (toolsState.stereoSplitMode == StereoSplitMode.MID_SIDE_WIDTH) 0f..2f else 1f..35f
                    HardwareParameterSlider(
                        label = paramLabel,
                        valueText = if (toolsState.stereoSplitMode == StereoSplitMode.MID_SIDE_WIDTH) {
                            "${"%.2f".format(toolsState.stereoSplitParam.coerceIn(0f, 2f))}x"
                        } else {
                            "${"%.1f".format(toolsState.stereoSplitParam.coerceIn(1f, 35f))} ms"
                        },
                        value = toolsState.stereoSplitParam.coerceIn(range.start, range.endInclusive),
                        valueRange = range,
                        onValueChange = { onSetStereoParams(toolsState.stereoSplitMode, it) },
                        accentColor = VioletMod
                    )
                    Button(
                        onClick = onApplySplitToStereo,
                        enabled = hasAudio,
                        colors = ButtonDefaults.buttonColors(containerColor = VioletMod),
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("apply_split_stereo_button")
                    ) {
                        Text("SPLIT / WIDEN TO 2-CH STEREO", color = ObsidianChassis)
                    }
                }
            }

            ToolsSectionTab.RATE_FORMAT -> {
                // SAMPLE RATE CONVERTER
                HardwarePanel(borderColor = NeonCyan.copy(alpha = 0.65f)) {
                    Text("SAMPLE RATE CONVERTER", style = MaterialTheme.typography.titleSmall, color = NeonCyan)
                    Text(
                        text = "Current: ${activeBuf?.sampleRate ?: 44100} Hz",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AudioSampleFormat.STANDARD_SAMPLE_RATES.forEach { sr ->
                            HardwareChipToggle(
                                label = "$sr Hz",
                                selected = toolsState.targetSampleRate == sr,
                                onClick = { onSetRateParams(sr, toolsState.reinterpretRateOnly) },
                                accentColor = NeonCyan
                            )
                        }
                    }
                    HardwareChipToggle(
                        label = if (toolsState.reinterpretRateOnly) "MODE: REINTERPRET HEADER (VARISPEED)" else "MODE: PITCH-LOCKED RESAMPLE",
                        selected = toolsState.reinterpretRateOnly,
                        onClick = { onSetRateParams(toolsState.targetSampleRate, !toolsState.reinterpretRateOnly) },
                        accentColor = AmberHot
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = onApplyConvertRate,
                        enabled = hasAudio,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("apply_sample_rate_button")
                    ) {
                        Text("APPLY SAMPLE RATE (${toolsState.targetSampleRate} Hz)", color = ObsidianChassis)
                    }
                }

                // FORMAT / BIT DEPTH CONVERTER (PCM 8/16/24/32, FLOAT 32/64)
                HardwarePanel(borderColor = EmeraldSignal.copy(alpha = 0.65f)) {
                    Text(
                        text = "SAMPLE FORMAT (PCM / IEEE FLOAT 16 / 24 / 32 / 64)",
                        style = MaterialTheme.typography.titleSmall,
                        color = EmeraldSignal
                    )
                    Text(
                        text = "Active Buffer Format: ${activeBuf?.sampleFormat?.label ?: toolsState.targetSampleFormat.label} • Used for WAV export",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AudioSampleFormat.entries.forEach { fmt ->
                            HardwareChipToggle(
                                label = fmt.label,
                                selected = toolsState.targetSampleFormat == fmt,
                                onClick = { onSetFormatParams(fmt, toolsState.applyDither) },
                                accentColor = EmeraldSignal,
                                modifier = Modifier.testTag("format_chip_${fmt.name.lowercase()}")
                            )
                        }
                    }
                    HardwareChipToggle(
                        label = if (toolsState.applyDither) "TPDF TRIANGULAR DITHER: ON" else "TPDF DITHER: OFF",
                        selected = toolsState.applyDither,
                        onClick = { onSetFormatParams(toolsState.targetSampleFormat, !toolsState.applyDither) },
                        accentColor = AmberHot
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = onApplyConvertFormat,
                        enabled = hasAudio,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSignal),
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("apply_sample_format_button")
                    ) {
                        Text("CONVERT BUFFER & EXPORT FORMAT (${toolsState.targetSampleFormat.shortLabel})", color = ObsidianChassis)
                    }
                }
            }

            ToolsSectionTab.PLUGIN_MANAGER -> {
                // ACTIVE PLUGIN CHAIN
                HardwarePanel(borderColor = MagentaFx.copy(alpha = 0.7f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PLUGIN MANAGER CHAIN (${toolsState.pluginChain.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MagentaFx
                        )
                        Button(
                            onClick = onBouncePluginChain,
                            enabled = hasAudio && toolsState.pluginChain.any { it.enabled && !it.bypassed },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldSignal),
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("bounce_plugins_button")
                        ) {
                            Text("BOUNCE TO DECK", color = ObsidianChassis)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    if (toolsState.pluginChain.isEmpty()) {
                        Text(
                            text = "Chain is empty — add an installed plugin below or install new DSP modules in INSTALLER.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    } else {
                        toolsState.pluginChain.forEachIndexed { idx, slot ->
                            val def = toolsState.installedPlugins.firstOrNull { it.id == slot.pluginId }
                            Surface(
                                color = ElevatedBezel,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, if (!slot.bypassed) MagentaFx else HairlineDivider),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${idx + 1}. ${slot.pluginName}",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = if (slot.bypassed) TextSecondary else TextPrimary
                                            )
                                            Text(
                                                text = "${slot.category.label} • ${def?.vendor ?: "Samplfie2"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            HardwareChipToggle(
                                                label = if (slot.bypassed) "BYPASSED" else "ACTIVE",
                                                selected = !slot.bypassed,
                                                onClick = { onTogglePluginBypass(slot.instanceId) },
                                                accentColor = EmeraldSignal
                                            )
                                            IconButton(
                                                onClick = { onMovePluginInChain(slot.instanceId, -1) },
                                                enabled = idx > 0,
                                                modifier = Modifier.minimumInteractiveComponentSize()
                                            ) {
                                                Icon(Icons.Filled.ArrowUpward, contentDescription = "Move Up", tint = NeonCyan)
                                            }
                                            IconButton(
                                                onClick = { onMovePluginInChain(slot.instanceId, 1) },
                                                enabled = idx < toolsState.pluginChain.lastIndex,
                                                modifier = Modifier.minimumInteractiveComponentSize()
                                            ) {
                                                Icon(Icons.Filled.ArrowDownward, contentDescription = "Move Down", tint = NeonCyan)
                                            }
                                            IconButton(
                                                onClick = { onRemovePluginFromChain(slot.instanceId) },
                                                modifier = Modifier.minimumInteractiveComponentSize()
                                            ) {
                                                Icon(Icons.Filled.Delete, contentDescription = "Remove Plugin", tint = TacCoral)
                                            }
                                        }
                                    }

                                    HardwareParameterSlider(
                                        label = "WET / DRY MIX",
                                        valueText = "${(slot.wetMix * 100).toInt()}%",
                                        value = slot.wetMix,
                                        valueRange = 0f..1f,
                                        onValueChange = { onUpdatePluginWetMix(slot.instanceId, it) },
                                        accentColor = NeonCyan
                                    )
                                    def?.params?.forEach { spec ->
                                        val curVal = slot.paramValues[spec.key] ?: spec.defaultVal
                                        HardwareParameterSlider(
                                            label = spec.label.uppercase(),
                                            valueText = "${"%.2f".format(curVal)} ${spec.unit}".trim(),
                                            value = curVal,
                                            valueRange = spec.min..spec.max,
                                            onValueChange = { onUpdatePluginParam(slot.instanceId, spec.key, it) },
                                            accentColor = MagentaFx
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ADD FROM INSTALLED PLUGINS
                HardwarePanel {
                    Text(
                        text = "INSTALLED PLUGINS (${toolsState.installedPlugins.size}) — TAP TO ADD TO CHAIN",
                        style = MaterialTheme.typography.titleSmall,
                        color = NeonCyan
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    toolsState.installedPlugins.forEach { def ->
                        Surface(
                            color = ElevatedBezel,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, HairlineDivider),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${def.name} (${def.sourceFormatLabel})", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                                    Text("${def.vendor} v${def.version} • ${def.category.label}", style = MaterialTheme.typography.labelSmall, color = NeonCyan)
                                    Text(def.description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { onAddPluginToChain(def.id) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MagentaFx),
                                    modifier = Modifier.minimumInteractiveComponentSize().testTag("add_plugin_${def.id}")
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, tint = ObsidianChassis)
                                    Text(" ADD", color = ObsidianChassis)
                                }
                            }
                        }
                    }
                }
            }

            ToolsSectionTab.PLUGIN_INSTALLER -> {
                HardwarePanel(borderColor = MagentaFx.copy(alpha = 0.7f)) {
                    Text(
                        text = "PLUGIN INSTALLER (CATALOG + .SMPPLUGIN.JSON / .WAV IR)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MagentaFx
                    )
                    Text(
                        text = "Install DSP plugins from the Samplfie2 catalog or import custom .smpplugin.json bundles & .WAV acoustic Impulse Responses.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onImportPluginOrIrFile,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("import_plugin_file_button")
                    ) {
                        Icon(Icons.Filled.Extension, contentDescription = null, tint = ObsidianChassis)
                        Text(" IMPORT .SMPPLUGIN.JSON OR .WAV IMPULSE RESPONSE", color = ObsidianChassis)
                    }
                }

                val installedIds = toolsState.installedPlugins.map { it.id }.toSet()
                HardwarePanel {
                    Text(
                        text = "AVAILABLE DSP PLUGIN CATALOG (${toolsState.catalogEntries.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = EmeraldSignal
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    toolsState.catalogEntries.forEach { entry ->
                        val isInstalled = installedIds.contains(entry.id)
                        Surface(
                            color = ElevatedBezel,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (isInstalled) EmeraldSignal else HairlineDivider),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.name, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                                    Text(
                                        "${entry.vendor} v${entry.version} • ${entry.category.label} • ${entry.sizeLabel}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AmberHot
                                    )
                                    Text(entry.description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                if (isInstalled) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedButton(
                                            onClick = { onAddPluginToChain(entry.id) },
                                            border = BorderStroke(1.dp, NeonCyan),
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Text("LOAD", color = NeonCyan)
                                        }
                                        OutlinedButton(
                                            onClick = { onUninstallCustomPlugin(entry.id) },
                                            border = BorderStroke(1.dp, TacCoral),
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Text("UNINSTALL", color = TacCoral)
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = { onInstallCatalogPlugin(entry) },
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSignal),
                                        modifier = Modifier.minimumInteractiveComponentSize().testTag("install_catalog_${entry.id}")
                                    ) {
                                        Icon(Icons.Filled.Download, contentDescription = null, tint = ObsidianChassis)
                                        Text(" INSTALL", color = ObsidianChassis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
