package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.audio.SpectrogramData
import com.example.audio.SpectrogramPalette
import com.example.audio.WaveformPeaks
import com.example.ui.VisualizerMode
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time responsive Audio Visualizer for Samplfie2 Editor:
 * - WAVEFORM: Dual-layer dry ghost + glowing FX-processed waveform, draggable IN/OUT selection markers,
 *   glowing playhead scrub, and live mic capture pulse.
 * - FFT SPECTRUM: 32-band logarithmic real-time spectrum analyzer with gradient bars & peak caps.
 * - SPECTROGRAM: Time-frequency STFT heatmap rendered with the 10-stop gradient & 2D box select.
 * - WAVE + SPEC / DUAL: Simultaneous Waveform + Spectrogram or Waveform + FFT stacked view.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioVisualizerPanel(
    dryPeaks: WaveformPeaks,
    processedPeaks: WaveformPeaks,
    liveSpectrum: FloatArray,
    liveMicWaveform: FloatArray,
    isRecording: Boolean,
    isPlaying: Boolean,
    playheadFraction: Float,
    selectionStart: Float,
    selectionEnd: Float,
    durationSec: Float,
    activeFxCount: Int,
    isMasterBypass: Boolean,
    visualizerMode: VisualizerMode,
    onModeChange: (VisualizerMode) -> Unit,
    onSelectionChange: (Float, Float) -> Unit,
    onSeekPlayhead: (Float) -> Unit,
    spectrogram: SpectrogramData = SpectrogramData.EMPTY,
    specPalette: SpectrogramPalette = SpectrogramPalette.SAMPLFIE_NEON,
    specFreqLowHz: Float = 220f,
    specFreqHighHz: Float = 3800f,
    onSpecFreqRangeChange: (Float, Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    HardwarePanel(
        modifier = modifier.testTag("audio_visualizer_panel"),
        borderColor = when {
            isRecording -> TacCoral
            isPlaying -> NeonCyan
            else -> HairlineDivider
        }
    ) {
        // Header row: Visualizer mode tabs + selection & FX cues
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                VisualizerMode.entries.forEach { mode ->
                    val selected = mode == visualizerMode
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onModeChange(mode) }
                            .testTag("vis_mode_${mode.name.lowercase()}"),
                        color = if (selected) NeonCyan.copy(alpha = 0.2f) else ObsidianChassis,
                        border = BorderStroke(1.dp, if (selected) NeonCyan else HairlineDivider),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) NeonCyan else TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 6.dp)
            ) {
                val selDur = ((selectionEnd - selectionStart) * durationSec).coerceAtLeast(0f)
                Text(
                    text = if (isRecording) "REC IN" else "${"%.2f".format(selDur)}s/${"%.2f".format(durationSec)}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isRecording) TacCoral else TextSecondary
                )
                if (activeFxCount > 0) {
                    Surface(
                        color = if (isMasterBypass) AmberHot.copy(alpha = 0.15f) else VioletMod.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, if (isMasterBypass) AmberHot else VioletMod)
                    ) {
                        Text(
                            text = if (isMasterBypass) "DRY A/B" else "DSP ON",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isMasterBypass) AmberHot else VioletMod,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (visualizerMode) {
            VisualizerMode.WAVEFORM -> {
                WaveformCanvas(
                    dryPeaks = dryPeaks,
                    processedPeaks = processedPeaks,
                    liveMicWaveform = liveMicWaveform,
                    isRecording = isRecording,
                    isPlaying = isPlaying,
                    playheadFraction = playheadFraction,
                    selectionStart = selectionStart,
                    selectionEnd = selectionEnd,
                    activeFxCount = activeFxCount,
                    isMasterBypass = isMasterBypass,
                    onSelectionChange = onSelectionChange,
                    onSeekPlayhead = onSeekPlayhead,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(144.dp)
                )
            }
            VisualizerMode.SPECTRUM -> {
                SpectrumAnalyzerCanvas(
                    bands = liveSpectrum,
                    isRecording = isRecording,
                    isPlaying = isPlaying,
                    activeFxCount = activeFxCount,
                    isMasterBypass = isMasterBypass,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(144.dp)
                )
            }
            VisualizerMode.SPECTROGRAM -> {
                SpectrogramCanvas(
                    spectrogram = spectrogram,
                    palette = specPalette,
                    playheadFraction = playheadFraction,
                    isPlaying = isPlaying,
                    selectionStart = selectionStart,
                    selectionEnd = selectionEnd,
                    freqLowHz = specFreqLowHz,
                    freqHighHz = specFreqHighHz,
                    onSelectionChange = onSelectionChange,
                    onFreqRangeChange = onSpecFreqRangeChange,
                    onSeekPlayhead = onSeekPlayhead,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                )
            }
            VisualizerMode.WAVE_SPEC -> {
                WaveformCanvas(
                    dryPeaks = dryPeaks,
                    processedPeaks = processedPeaks,
                    liveMicWaveform = liveMicWaveform,
                    isRecording = isRecording,
                    isPlaying = isPlaying,
                    playheadFraction = playheadFraction,
                    selectionStart = selectionStart,
                    selectionEnd = selectionEnd,
                    activeFxCount = activeFxCount,
                    isMasterBypass = isMasterBypass,
                    onSelectionChange = onSelectionChange,
                    onSeekPlayhead = onSeekPlayhead,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                SpectrogramCanvas(
                    spectrogram = spectrogram,
                    palette = specPalette,
                    playheadFraction = playheadFraction,
                    isPlaying = isPlaying,
                    selectionStart = selectionStart,
                    selectionEnd = selectionEnd,
                    freqLowHz = specFreqLowHz,
                    freqHighHz = specFreqHighHz,
                    onSelectionChange = onSelectionChange,
                    onFreqRangeChange = onSpecFreqRangeChange,
                    onSeekPlayhead = onSeekPlayhead,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(114.dp)
                )
            }
            VisualizerMode.DUAL -> {
                WaveformCanvas(
                    dryPeaks = dryPeaks,
                    processedPeaks = processedPeaks,
                    liveMicWaveform = liveMicWaveform,
                    isRecording = isRecording,
                    isPlaying = isPlaying,
                    playheadFraction = playheadFraction,
                    selectionStart = selectionStart,
                    selectionEnd = selectionEnd,
                    activeFxCount = activeFxCount,
                    isMasterBypass = isMasterBypass,
                    onSelectionChange = onSelectionChange,
                    onSeekPlayhead = onSeekPlayhead,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(124.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                SpectrumAnalyzerCanvas(
                    bands = liveSpectrum,
                    isRecording = isRecording,
                    isPlaying = isPlaying,
                    activeFxCount = activeFxCount,
                    isMasterBypass = isMasterBypass,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(74.dp)
                )
            }
        }
    }
}

@Composable
private fun SpectrogramCanvas(
    spectrogram: SpectrogramData,
    palette: SpectrogramPalette,
    playheadFraction: Float,
    isPlaying: Boolean,
    selectionStart: Float,
    selectionEnd: Float,
    freqLowHz: Float,
    freqHighHz: Float,
    onSelectionChange: (Float, Float) -> Unit,
    onFreqRangeChange: (Float, Float) -> Unit,
    onSeekPlayhead: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(spectrogram, palette) {
        if (spectrogram.isEmpty) {
            null
        } else {
            val lut = SpectrogramPalette.buildLut256(palette.stopsArgb)
            val cols = spectrogram.frames
            val rows = spectrogram.bins
            val pixels = IntArray(cols * rows)
            for (b in 0 until rows) {
                val y = rows - 1 - b
                val rowOff = y * cols
                for (f in 0 until cols) {
                    val v = spectrogram.valueAt(f, b)
                    val lutIdx = (v * 255f).toInt().coerceIn(0, 255)
                    pixels[rowOff + f] = lut[lutIdx]
                }
            }
            Bitmap.createBitmap(pixels, cols, rows, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

    var dragAnchorX by remember { mutableStateOf(0f) }
    var dragAnchorY by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ObsidianChassis)
            .border(1.dp, ElevatedBezel, RoundedCornerShape(8.dp))
            .pointerInput(spectrogram) {
                detectTapGestures { offset ->
                    val frac = (offset.x / max(1f, size.width.toFloat())).coerceIn(0f, 1f)
                    onSeekPlayhead(frac)
                }
            }
            .pointerInput(spectrogram) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragAnchorX = (offset.x / max(1f, size.width.toFloat())).coerceIn(0f, 1f)
                        dragAnchorY = (1f - offset.y / max(1f, size.height.toFloat())).coerceIn(0f, 1f)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val curX = (change.position.x / max(1f, size.width.toFloat())).coerceIn(0f, 1f)
                        val curY = (1f - change.position.y / max(1f, size.height.toFloat())).coerceIn(0f, 1f)
                        if (abs(curX - dragAnchorX) > 0.02f) {
                            onSelectionChange(min(dragAnchorX, curX), max(dragAnchorX, curX))
                        }
                        if (abs(curY - dragAnchorY) > 0.03f && !spectrogram.isEmpty) {
                            val f0 = spectrogram.normYToFreqHz(min(dragAnchorY, curY))
                            val f1 = spectrogram.normYToFreqHz(max(dragAnchorY, curY))
                            onFreqRangeChange(f0, f1)
                        }
                    }
                )
            }
            .testTag("spectrogram_canvas")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (imageBitmap != null) {
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Medium
                )
            }

            // 10-Stop Gradient Legend strip along bottom 5dp
            val stops = palette.stopsArgb
            val segW = w / stops.size.coerceAtLeast(1)
            stops.forEachIndexed { idx, argb ->
                drawRect(
                    color = Color(argb),
                    topLeft = Offset(idx * segW, h - 5.dp.toPx()),
                    size = Size(segW + 1f, 5.dp.toPx())
                )
            }

            // 2D Time + Frequency Spectral Target Box
            if (!spectrogram.isEmpty) {
                val x0 = selectionStart.coerceIn(0f, 1f) * w
                val x1 = selectionEnd.coerceIn(0f, 1f) * w
                val yNormLo = spectrogram.freqHzToNormY(min(freqLowHz, freqHighHz))
                val yNormHi = spectrogram.freqHzToNormY(max(freqLowHz, freqHighHz))
                val topY = (1f - yNormHi) * (h - 6.dp.toPx())
                val botY = (1f - yNormLo) * (h - 6.dp.toPx())
                val boxW = (x1 - x0).coerceAtLeast(4f)
                val boxH = (botY - topY).coerceAtLeast(4f)
                drawRect(
                    color = TacCoral.copy(alpha = 0.16f),
                    topLeft = Offset(x0, topY),
                    size = Size(boxW, boxH)
                )
                drawRect(
                    color = AmberHot,
                    topLeft = Offset(x0, topY),
                    size = Size(boxW, boxH),
                    style = Stroke(width = 1.6.dp.toPx())
                )
            }

            // Playhead line
            val px = (playheadFraction.coerceIn(0f, 1f) * w)
            drawLine(
                color = if (isPlaying) Color.White else EmeraldSignal.copy(alpha = 0.8f),
                start = Offset(px, 0f),
                end = Offset(px, h),
                strokeWidth = if (isPlaying) 2.5f else 1.5f
            )
        }

        if (spectrogram.isEmpty) {
            Text(
                text = "NO SPECTROGRAM DATA • RECORD OR LOAD AUDIO",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${spectrogram.maxFreqHz.toInt()}Hz",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary.copy(alpha = 0.85f)
                )
                Text(
                    text = "BOX ${min(freqLowHz, freqHighHz).toInt()}–${max(freqLowHz, freqHighHz).toInt()}Hz (${palette.name.take(14)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberHot
                )
                Text(
                    text = "${spectrogram.minFreqHz.toInt()}Hz↓",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun WaveformCanvas(
    dryPeaks: WaveformPeaks,
    processedPeaks: WaveformPeaks,
    liveMicWaveform: FloatArray,
    isRecording: Boolean,
    isPlaying: Boolean,
    playheadFraction: Float,
    selectionStart: Float,
    selectionEnd: Float,
    activeFxCount: Int,
    isMasterBypass: Boolean,
    onSelectionChange: (Float, Float) -> Unit,
    onSeekPlayhead: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragTarget by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ObsidianChassis)
            .border(1.dp, ElevatedBezel, RoundedCornerShape(8.dp))
            .pointerInput(selectionStart, selectionEnd, isRecording) {
                if (!isRecording) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeekPlayhead(frac)
                    }
                }
            }
            .pointerInput(selectionStart, selectionEnd, isRecording) {
                if (!isRecording) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            val distStart = abs(frac - selectionStart)
                            val distEnd = abs(frac - selectionEnd)
                            dragTarget = when {
                                distStart < 0.08f && distStart <= distEnd -> "START"
                                distEnd < 0.08f -> "END"
                                else -> "PLAYHEAD"
                            }
                        },
                        onDragEnd = { dragTarget = null },
                        onDragCancel = { dragTarget = null },
                        onDrag = { change, _ ->
                            change.consume()
                            val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            when (dragTarget) {
                                "START" -> onSelectionChange(frac.coerceAtMost(selectionEnd - 0.03f), selectionEnd)
                                "END" -> onSelectionChange(selectionStart, frac.coerceAtLeast(selectionStart + 0.03f))
                                "PLAYHEAD" -> onSeekPlayhead(frac)
                            }
                        }
                    )
                }
            }
            .testTag("waveform_canvas")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val midY = h / 2f

            // Background gridlines
            val gridColor = HairlineDivider.copy(alpha = 0.45f)
            drawLine(gridColor, Offset(0f, midY), Offset(w, midY), strokeWidth = 1f)
            drawLine(gridColor.copy(alpha = 0.25f), Offset(0f, h * 0.25f), Offset(w, h * 0.25f), strokeWidth = 1f)
            drawLine(gridColor.copy(alpha = 0.25f), Offset(0f, h * 0.75f), Offset(w, h * 0.75f), strokeWidth = 1f)
            for (i in 1..7) {
                val gx = w * (i / 8f)
                drawLine(gridColor.copy(alpha = 0.2f), Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
            }

            if (isRecording) {
                // Real-time live mic waveform bars
                val count = liveMicWaveform.size.coerceAtLeast(1)
                val barW = w / count
                for (i in 0 until count) {
                    val amp = liveMicWaveform[i].coerceIn(0.03f, 1f)
                    val barH = amp * h * 0.85f
                    drawRoundRect(
                        color = TacCoral.copy(alpha = 0.45f + 0.55f * amp),
                        topLeft = Offset(i * barW + 1f, midY - barH / 2f),
                        size = Size((barW - 2f).coerceAtLeast(2f), barH)
                    )
                }
                return@Canvas
            }

            val procCount = processedPeaks.maxPeaks.size
            if (procCount == 0) {
                return@Canvas
            }

            // Selection region highlight
            val selX0 = selectionStart * w
            val selX1 = selectionEnd * w
            drawRect(
                color = NeonCyan.copy(alpha = 0.11f),
                topLeft = Offset(selX0, 0f),
                size = Size((selX1 - selX0).coerceAtLeast(2f), h)
            )

            // Dry ghost waveform (visible whenever FX are active for instant visual cue)
            if (activeFxCount > 0 && !isMasterBypass && dryPeaks.maxPeaks.size == procCount) {
                val step = w / procCount
                for (i in 0 until procCount) {
                    val x = i * step + step * 0.5f
                    val yTop = midY - (dryPeaks.maxPeaks[i].coerceIn(0f, 1f) * midY * 0.88f)
                    val yBot = midY - (dryPeaks.minPeaks[i].coerceIn(-1f, 0f) * midY * 0.88f)
                    drawLine(
                        color = TextMuted.copy(alpha = 0.35f),
                        start = Offset(x, yTop),
                        end = Offset(x, yBot),
                        strokeWidth = (step * 0.65f).coerceAtLeast(1.5f)
                    )
                }
            }

            // Active processed waveform
            val activeColor = if (activeFxCount > 0 && !isMasterBypass) NeonCyan else EmeraldSignal
            val step = w / procCount
            for (i in 0 until procCount) {
                val x = i * step + step * 0.5f
                val frac = i.toFloat() / procCount.toFloat()
                val inSelection = frac in selectionStart..selectionEnd
                val played = frac <= playheadFraction && isPlaying
                val barColor = when {
                    played -> EmeraldSignal
                    inSelection -> activeColor
                    else -> activeColor.copy(alpha = 0.36f)
                }
                val yTop = midY - (processedPeaks.maxPeaks[i].coerceIn(0f, 1f) * midY * 0.9f)
                val yBot = midY - (processedPeaks.minPeaks[i].coerceIn(-1f, 0f) * midY * 0.9f)
                drawLine(
                    color = barColor,
                    start = Offset(x, yTop),
                    end = Offset(x, yBot.coerceAtLeast(yTop + 2f)),
                    strokeWidth = (step * 0.72f).coerceAtLeast(2f)
                )
            }

            // Selection IN / OUT handle lines and flags
            drawLine(NeonCyan, Offset(selX0, 0f), Offset(selX0, h), strokeWidth = 2.5f)
            drawRect(NeonCyan, Offset(selX0, 0f), Size(20f, 16f))

            drawLine(AmberHot, Offset(selX1, 0f), Offset(selX1, h), strokeWidth = 2.5f)
            drawRect(AmberHot, Offset((selX1 - 20f).coerceAtLeast(0f), h - 16f), Size(20f, 16f))

            // Playhead line + top diamond marker
            val px = (playheadFraction * w).coerceIn(0f, w)
            drawLine(
                color = Color.White,
                start = Offset(px, 0f),
                end = Offset(px, h),
                strokeWidth = if (isPlaying) 3f else 2f
            )
            val diamond = Path().apply {
                moveTo(px, 0f)
                lineTo(px + 6f, 8f)
                lineTo(px, 16f)
                lineTo(px - 6f, 8f)
                close()
            }
            drawPath(diamond, color = Color.White)
        }

        if (processedPeaks.maxPeaks.isEmpty() && !isRecording) {
            Text(
                text = "NO WAVEFORM LOADED • HOLD TAC PAD OR TAP OSC",
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun SpectrumAnalyzerCanvas(
    bands: FloatArray,
    isRecording: Boolean,
    isPlaying: Boolean,
    activeFxCount: Int,
    isMasterBypass: Boolean,
    modifier: Modifier = Modifier
) {
    val peakCaps = remember { FloatArray(32) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ObsidianChassis)
            .border(1.dp, ElevatedBezel, RoundedCornerShape(8.dp))
            .testTag("spectrum_canvas")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val count = bands.size.coerceAtLeast(1)
            val gap = 2.dp.toPx()
            val barW = ((w - gap * (count - 1)) / count).coerceAtLeast(2f)

            // Reference dB lines
            drawLine(HairlineDivider.copy(alpha = 0.3f), Offset(0f, h * 0.25f), Offset(w, h * 0.25f), 1f)
            drawLine(HairlineDivider.copy(alpha = 0.3f), Offset(0f, h * 0.55f), Offset(w, h * 0.55f), 1f)

            val activeBrush = when {
                isRecording -> Brush.verticalGradient(listOf(TacCoral, AmberHot, NeonCyan))
                activeFxCount > 0 && !isMasterBypass -> Brush.verticalGradient(listOf(MagentaFx, VioletMod, NeonCyan))
                else -> Brush.verticalGradient(listOf(AmberHot, EmeraldSignal, NeonCyan))
            }

            for (i in 0 until count) {
                val level = bands[i].coerceIn(0f, 1f)
                if (i < peakCaps.size) {
                    peakCaps[i] = if (level >= peakCaps[i]) level else (peakCaps[i] - 0.025f).coerceAtLeast(level)
                }
                val barH = level * (h - 10f)
                val x = i * (barW + gap)
                if (barH > 1f) {
                    drawRect(
                        brush = activeBrush,
                        topLeft = Offset(x, h - barH),
                        size = Size(barW, barH)
                    )
                }
                val capY = h - (if (i < peakCaps.size) peakCaps[i] else level) * (h - 10f)
                if (capY < h - 3f) {
                    drawRect(
                        color = TextPrimary.copy(alpha = if (isPlaying || isRecording) 0.9f else 0.45f),
                        topLeft = Offset(x, capY - 2f),
                        size = Size(barW, 2.5f)
                    )
                }
            }
        }

        // Frequency band axis overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("60Hz", "250Hz", "1kHz", "4kHz", "16kHz").forEach { freq ->
                Text(
                    text = freq,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted.copy(alpha = 0.7f)
                )
            }
        }
    }
}
