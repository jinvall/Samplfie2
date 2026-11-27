package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.SAMPLER_MODULE_COUNT
import com.example.audio.SamplerModuleSlot
import com.example.studio.LooperState
import com.example.ui.LoopEditorState
import com.example.ui.SavedTakeItem
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
 * SAMPLES SPOKE:
 * 4-Slot DJ Looper + Loop Sample Refinement Editor (in-spoke trim, zero-crossing snap, fades, reverse,
 * DC removal, peak normalize, pitch/speed tune, continuous loop audition, commit back to slot/deck) + Saved .WAV Bank.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SamplesSpokeScreen(
    looperState: LooperState,
    loopEditorState: LoopEditorState,
    savedTakes: List<SavedTakeItem>,
    hasEditorAudio: Boolean,
    onSetBpm: (Float) -> Unit,
    onToggleLooperPlay: () -> Unit,
    onAssignEditorToLoop: (Int) -> Unit,
    onToggleLoopSlot: (Int) -> Unit,
    onToggleLoopMute: (Int) -> Unit,
    onClearLoopSlot: (Int) -> Unit,
    onSetLoopBars: (Int, Int) -> Unit,
    onBounceLooperToEditor: () -> Unit,
    onImportAudioClick: () -> Unit,
    onLoadSavedTake: (SavedTakeItem) -> Unit,
    onLoadSlotIntoRefiner: (Int) -> Unit,
    onUpdateLoopSelection: (Float, Float) -> Unit,
    onSnapZeroCrossing: () -> Unit,
    onTrimLoop: () -> Unit,
    onSetLoopFades: (Float, Float) -> Unit,
    onApplyFades: () -> Unit,
    onReverseLoop: () -> Unit,
    onNormalizeLoop: () -> Unit,
    onRemoveDcLoop: () -> Unit,
    onTuneLoopPitchSpeed: (Float, Float) -> Unit,
    onResetLoopRefiner: () -> Unit,
    onAuditionLoopRefiner: () -> Unit,
    onCommitRefinerToSlot: () -> Unit,
    onSendRefinerToDeck: () -> Unit,
    onToggleLoopSlotLock: (Int) -> Unit = {},
    samplerSlots: List<SamplerModuleSlot> = List(SAMPLER_MODULE_COUNT) { idx ->
        SamplerModuleSlot(index = idx, label = "M${idx + 1}")
    },
    onTriggerSamplerModule: (Int) -> Unit = {},
    onStopSamplerModule: (Int) -> Unit = {},
    onStopAllSamplerModules: () -> Unit = {},
    onLoadDeckToSamplerModule: (Int) -> Unit = {},
    onImportFileToSamplerModule: (Int) -> Unit = {},
    onToggleSamplerLock: (Int) -> Unit = {},
    onToggleSamplerLoop: (Int) -> Unit = {},
    onUpdateSamplerParams: (Int, Float?, Float?, Float?) -> Unit = { _, _, _, _ -> },
    onClearSamplerModule: (Int) -> Unit = {},
    onBounceSamplerToDeck: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedSamplerIdx by remember { mutableIntStateOf(0) }
    val normalizedSamplerSlots = if (samplerSlots.size == SAMPLER_MODULE_COUNT) {
        samplerSlots
    } else {
        List(SAMPLER_MODULE_COUNT) { idx ->
            samplerSlots.getOrNull(idx) ?: SamplerModuleSlot(index = idx, label = "M${idx + 1}")
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Looper Master Bar
        HardwarePanel(borderColor = EmeraldSignal.copy(alpha = 0.5f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SAMPLES SPOKE · 4-SLOT LOOPER", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text("Load loops, click REFINE to edit seamlessly", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onToggleLooperPlay,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (looperState.isRunning) TacCoral else EmeraldSignal
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("looper_play_button")
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
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { looperState.barProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = EmeraldSignal,
                trackColor = ObsidianChassis
            )

            Spacer(modifier = Modifier.height(8.dp))
            HardwareParameterSlider(
                label = "LOOPER TEMPO",
                valueText = "${looperState.bpm.toInt()} BPM",
                value = looperState.bpm,
                valueRange = 60f..180f,
                accentColor = AmberHot,
                onValueChange = onSetBpm
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HardwareChipToggle(
                    label = "MIX -> DECK",
                    selected = false,
                    onClick = onBounceLooperToEditor,
                    accentColor = VioletMod,
                    modifier = Modifier.testTag("looper_bounce_button")
                )
                HardwareChipToggle(
                    label = "IMPORT AUDIO",
                    selected = false,
                    onClick = onImportAudioClick,
                    accentColor = NeonCyan,
                    modifier = Modifier.testTag("samples_import_button")
                )
            }
        }

        // 2. Looper Slots A-D with "REFINE" button
        looperState.slots.forEach { slot ->
            val slotLoaded = slot.buffer != null
            val isRefiningThis = loopEditorState.selectedSlotId == slot.id && loopEditorState.workingBuffer != null
            HardwarePanel(
                borderColor = when {
                    isRefiningThis -> AmberHot
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (hasEditorAudio) {
                            HardwareChipToggle(
                                label = if (slot.isSampleLocked) "REBIND" else "BIND",
                                selected = false,
                                onClick = { onAssignEditorToLoop(slot.id) },
                                accentColor = NeonCyan,
                                modifier = Modifier.testTag("loop_load_${slot.id}")
                            )
                        }
                        HardwareChipToggle(
                            label = "REFINE",
                            selected = isRefiningThis,
                            onClick = { onLoadSlotIntoRefiner(slot.id) },
                            accentColor = AmberHot,
                            modifier = Modifier.testTag("loop_refine_${slot.id}")
                        )
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
                    if (slotLoaded) {
                        HardwareChipToggle(
                            label = if (slot.isSampleLocked) "LOCKED" else "OPEN",
                            selected = slot.isSampleLocked,
                            onClick = { onToggleLoopSlotLock(slot.id) },
                            accentColor = AmberHot,
                            modifier = Modifier.testTag("loop_lock_${slot.id}")
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

        // 3. Dedicated Loop Sample Refinement Editor (on Samples Spoke)
        val working = loopEditorState.workingBuffer
        HardwarePanel(
            borderColor = if (working != null) AmberHot.copy(alpha = 0.7f) else HairlineDivider,
            modifier = Modifier.testTag("loop_refiner_panel")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = null, tint = AmberHot)
                        Text(
                            text = "LOOP REFINEMENT EDITOR (SLOT ${'A' + loopEditorState.selectedSlotId})",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = loopEditorState.statusNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberHot,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.heightIn(min = 32.dp)
                    )
                }
            }

            if (working != null) {
                Spacer(modifier = Modifier.height(8.dp))
                // Mini Loop Waveform Preview with IN/OUT Highlight
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ObsidianChassis)
                        .border(1.dp, ElevatedBezel, RoundedCornerShape(8.dp))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val midY = h / 2f
                        val sX = loopEditorState.loopStartFrac * w
                        val eX = loopEditorState.loopEndFrac * w

                        drawRect(
                            color = AmberHot.copy(alpha = 0.14f),
                            topLeft = Offset(sX, 0f),
                            size = Size((eX - sX).coerceAtLeast(2f), h)
                        )
                        val maxPeaks = loopEditorState.peaks.maxPeaks
                        val minPeaks = loopEditorState.peaks.minPeaks
                        if (maxPeaks.isNotEmpty()) {
                            val colW = w / maxPeaks.size
                            for (i in maxPeaks.indices) {
                                val x = i * colW + colW * 0.5f
                                val topY = midY - maxPeaks[i] * (midY * 0.85f)
                                val botY = midY - minPeaks[i] * (midY * 0.85f)
                                val inRange = (i.toFloat() / maxPeaks.size) in loopEditorState.loopStartFrac..loopEditorState.loopEndFrac
                                drawLine(
                                    color = if (inRange) NeonCyan else TextMuted,
                                    start = Offset(x, topY),
                                    end = Offset(x, botY),
                                    strokeWidth = (colW * 0.72f).coerceAtLeast(1.5f)
                                )
                            }
                        }
                        drawLine(NeonCyan, Offset(sX, 0f), Offset(sX, h), strokeWidth = 2.dp.toPx())
                        drawLine(AmberHot, Offset(eX, 0f), Offset(eX, h), strokeWidth = 2.dp.toPx())
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        HardwareParameterSlider(
                            label = "LOOP IN",
                            valueText = "${"%.2f".format(loopEditorState.loopStartFrac * working.durationSeconds)}s",
                            value = loopEditorState.loopStartFrac,
                            valueRange = 0f..0.95f,
                            accentColor = NeonCyan,
                            onValueChange = { onUpdateLoopSelection(it, loopEditorState.loopEndFrac) }
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        HardwareParameterSlider(
                            label = "LOOP OUT",
                            valueText = "${"%.2f".format(loopEditorState.loopEndFrac * working.durationSeconds)}s",
                            value = loopEditorState.loopEndFrac,
                            valueRange = 0.05f..1f,
                            accentColor = AmberHot,
                            onValueChange = { onUpdateLoopSelection(loopEditorState.loopStartFrac, it) }
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        HardwareParameterSlider(
                            label = "FADE IN",
                            valueText = "${loopEditorState.fadeInMs.toInt()} ms",
                            value = loopEditorState.fadeInMs,
                            valueRange = 0f..2000f,
                            accentColor = EmeraldSignal,
                            onValueChange = { onSetLoopFades(it, loopEditorState.fadeOutMs) }
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        HardwareParameterSlider(
                            label = "FADE OUT",
                            valueText = "${loopEditorState.fadeOutMs.toInt()} ms",
                            value = loopEditorState.fadeOutMs,
                            valueRange = 0f..2000f,
                            accentColor = EmeraldSignal,
                            onValueChange = { onSetLoopFades(loopEditorState.fadeInMs, it) }
                        )
                    }
                }

                HardwareParameterSlider(
                    label = "LOOP FINE PITCH",
                    valueText = "${if (loopEditorState.pitchSemitones >= 0) "+" else ""}${"%.1f".format(loopEditorState.pitchSemitones)} st",
                    value = loopEditorState.pitchSemitones,
                    valueRange = -12f..12f,
                    accentColor = VioletMod,
                    onValueChange = { onTuneLoopPitchSpeed(it, loopEditorState.speedFactor) }
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HardwareChipToggle(
                        label = "ZERO-CROSS",
                        selected = false,
                        onClick = onSnapZeroCrossing,
                        accentColor = NeonCyan,
                        modifier = Modifier.testTag("loop_refine_zerocross")
                    )
                    HardwareChipToggle(
                        label = "TRIM LOOP",
                        selected = false,
                        onClick = onTrimLoop,
                        accentColor = AmberHot,
                        modifier = Modifier.testTag("loop_refine_trim")
                    )
                    HardwareChipToggle(
                        label = "APPLY FADES",
                        selected = false,
                        onClick = onApplyFades,
                        accentColor = EmeraldSignal,
                        modifier = Modifier.testTag("loop_refine_fade")
                    )
                    HardwareChipToggle(
                        label = "REVERSE",
                        selected = loopEditorState.isReversed,
                        onClick = onReverseLoop,
                        accentColor = MagentaFx,
                        modifier = Modifier.testTag("loop_refine_reverse")
                    )
                    HardwareChipToggle(
                        label = "NORMALIZE",
                        selected = false,
                        onClick = onNormalizeLoop,
                        accentColor = EmeraldSignal,
                        modifier = Modifier.testTag("loop_refine_normalize")
                    )
                    HardwareChipToggle(
                        label = "DC FIX",
                        selected = false,
                        onClick = onRemoveDcLoop,
                        accentColor = VioletMod
                    )
                    HardwareChipToggle(
                        label = "RESET",
                        selected = false,
                        onClick = onResetLoopRefiner,
                        accentColor = TacCoral
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onAuditionLoopRefiner,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSignal),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("loop_refine_audition")
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = ObsidianChassis)
                        Text(" AUDITION LOOP", color = ObsidianChassis, style = MaterialTheme.typography.labelMedium)
                    }
                    Button(
                        onClick = onCommitRefinerToSlot,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberHot),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("loop_refine_commit")
                    ) {
                        Icon(Icons.Filled.ContentCut, contentDescription = null, tint = ObsidianChassis)
                        Text(" COMMIT SLOT ${'A' + loopEditorState.selectedSlotId}", color = ObsidianChassis, style = MaterialTheme.typography.labelMedium)
                    }
                    HardwareChipToggle(
                        label = "SEND -> DECK",
                        selected = false,
                        onClick = onSendRefinerToDeck,
                        accentColor = NeonCyan,
                        modifier = Modifier.testTag("loop_refine_to_deck")
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(6.dp))
                HardwareChipToggle(
                    label = "LOAD ACTIVE AUDIO TO SLOT A REFINER",
                    selected = false,
                    onClick = { onLoadSlotIntoRefiner(0) },
                    accentColor = AmberHot,
                    modifier = Modifier.testTag("loop_refine_quickstart")
                )
            }
        }

        // 3B. 12-Module Hardware Sampler (AudioEngine + jump3r MP3)
        val activeModule = normalizedSamplerSlots[selectedSamplerIdx.coerceIn(0, SAMPLER_MODULE_COUNT - 1)]
        HardwarePanel(
            borderColor = NeonCyan.copy(alpha = 0.6f),
            modifier = Modifier.testTag("hardware_sampler_12_panel")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "12-MODULE HARDWARE SAMPLER (AUDIOENGINE)",
                        style = MaterialTheme.typography.titleSmall,
                        color = NeonCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val loadedCount = normalizedSamplerSlots.count { it.hasSample }
                    val playingCount = normalizedSamplerSlots.count { it.isPlaying }
                    Text(
                        text = "$loadedCount/12 LOADED · $playingCount ACTIVE · MP3 (JUMP3R) & WAV",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HardwareChipToggle(
                        label = "STOP ALL",
                        selected = false,
                        onClick = onStopAllSamplerModules,
                        accentColor = TacCoral,
                        modifier = Modifier.testTag("sampler_stop_all")
                    )
                    HardwareChipToggle(
                        label = "BOUNCE -> DECK",
                        selected = false,
                        onClick = onBounceSamplerToDeck,
                        accentColor = VioletMod,
                        modifier = Modifier.testTag("sampler_bounce_deck")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Fixed-height 3x4 (or 4x3 on wide screens) hardware trigger pad grid
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= 580.dp) 4 else 3
                val rows = normalizedSamplerSlots.chunked(columns)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rows.forEach { rowSlots ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowSlots.forEach { modSlot ->
                                val isSel = modSlot.index == selectedSamplerIdx
                                val borderColor = when {
                                    modSlot.isPlaying -> EmeraldSignal
                                    isSel -> NeonCyan
                                    modSlot.hasSample -> AmberHot.copy(alpha = 0.65f)
                                    else -> HairlineDivider
                                }
                                val bgColor = when {
                                    modSlot.isPlaying -> EmeraldSignal.copy(alpha = 0.18f)
                                    isSel -> NeonCyan.copy(alpha = 0.14f)
                                    else -> ElevatedBezel
                                }
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(84.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            selectedSamplerIdx = modSlot.index
                                            if (modSlot.hasSample) {
                                                if (modSlot.isPlaying) {
                                                    onStopSamplerModule(modSlot.index)
                                                } else {
                                                    onTriggerSamplerModule(modSlot.index)
                                                }
                                            }
                                        }
                                        .testTag("sampler_pad_${modSlot.index}"),
                                    color = bgColor,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(if (isSel || modSlot.isPlaying) 1.5.dp else 1.dp, borderColor)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = modSlot.label,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = if (modSlot.isPlaying) EmeraldSignal else if (isSel) NeonCyan else TextPrimary
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (modSlot.isLocked) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Lock,
                                                        contentDescription = "Locked",
                                                        tint = AmberHot,
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(
                                                            when {
                                                                modSlot.isPlaying -> EmeraldSignal
                                                                modSlot.hasSample -> AmberHot
                                                                else -> HairlineDivider
                                                            }
                                                        )
                                                )
                                            }
                                        }
                                        Text(
                                            text = modSlot.sampleName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (modSlot.hasSample) TextPrimary else TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (modSlot.hasSample) {
                                                "${modSlot.sourceFormatTag} · ${"%.1f".format(modSlot.buffer?.durationSeconds ?: 0f)}s"
                                            } else {
                                                "TAP TO SELECT"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (modSlot.isPlaying) EmeraldSignal else TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            if (rowSlots.size < columns) {
                                repeat(columns - rowSlots.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Selected Module Detail & Controls
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ObsidianChassis,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, HairlineDivider)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MODULE ${activeModule.label}: ${activeModule.sampleName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (activeModule.hasSample) activeModule.sourceFormatTag else "NO SAMPLE",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberHot,
                            maxLines = 1
                        )
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                if (activeModule.isPlaying) {
                                    onStopSamplerModule(activeModule.index)
                                } else {
                                    onTriggerSamplerModule(activeModule.index)
                                }
                            },
                            enabled = activeModule.hasSample,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (activeModule.isPlaying) TacCoral else EmeraldSignal
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("sampler_trigger_selected")
                        ) {
                            Icon(
                                imageVector = if (activeModule.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = "Trigger or stop module",
                                tint = ObsidianChassis
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (activeModule.isPlaying) "STOP" else "TRIGGER",
                                color = ObsidianChassis,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        if (hasEditorAudio) {
                            HardwareChipToggle(
                                label = "DECK -> ${activeModule.label}",
                                selected = false,
                                onClick = { onLoadDeckToSamplerModule(activeModule.index) },
                                accentColor = NeonCyan,
                                modifier = Modifier.testTag("sampler_bind_deck")
                            )
                        }
                        HardwareChipToggle(
                            label = "IMPORT MP3/WAV",
                            selected = false,
                            onClick = { onImportFileToSamplerModule(activeModule.index) },
                            accentColor = AmberHot,
                            modifier = Modifier.testTag("sampler_import_file")
                        )
                        HardwareChipToggle(
                            label = if (activeModule.isLocked) "LOCKED" else "OPEN",
                            selected = activeModule.isLocked,
                            onClick = { onToggleSamplerLock(activeModule.index) },
                            accentColor = AmberHot,
                            modifier = Modifier.testTag("sampler_toggle_lock")
                        )
                        HardwareChipToggle(
                            label = if (activeModule.isLooping) "LOOP ON" else "LOOP OFF",
                            selected = activeModule.isLooping,
                            onClick = { onToggleSamplerLoop(activeModule.index) },
                            accentColor = EmeraldSignal,
                            modifier = Modifier.testTag("sampler_toggle_loop")
                        )
                        if (activeModule.hasSample) {
                            HardwareChipToggle(
                                label = "CLR",
                                selected = false,
                                onClick = { onClearSamplerModule(activeModule.index) },
                                accentColor = TacCoral,
                                modifier = Modifier.testTag("sampler_clear_selected")
                            )
                        }
                    }

                    HardwareParameterSlider(
                        label = "MODULE GAIN",
                        valueText = "${(activeModule.gain * 100).toInt()}%",
                        value = activeModule.gain,
                        valueRange = 0f..1.5f,
                        accentColor = EmeraldSignal,
                        onValueChange = { onUpdateSamplerParams(activeModule.index, it, null, null) }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            HardwareParameterSlider(
                                label = "PAN L/R",
                                valueText = "${(activeModule.pan * 100).toInt()}%",
                                value = activeModule.pan,
                                valueRange = -1f..1f,
                                accentColor = NeonCyan,
                                onValueChange = { onUpdateSamplerParams(activeModule.index, null, it, null) }
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            HardwareParameterSlider(
                                label = "PITCH",
                                valueText = "${if (activeModule.pitchSemitones >= 0f) "+" else ""}${"%.1f".format(activeModule.pitchSemitones)} st",
                                value = activeModule.pitchSemitones,
                                valueRange = -12f..12f,
                                accentColor = VioletMod,
                                onValueChange = { onUpdateSamplerParams(activeModule.index, null, null, it) }
                            )
                        }
                    }
                }
            }
        }

        // 4. Saved .WAV Takes Bank
        if (savedTakes.isNotEmpty()) {
            HardwarePanel {
                Text("LOCAL .WAV SAMPLE BANK", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    savedTakes.forEach { take ->
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
