package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tuner.TunerInputSource
import com.example.tuner.TuningPreset
import com.example.ui.TunerUiState
import com.example.ui.components.HardwareChipToggle
import com.example.ui.components.HardwareLedIndicator
import com.example.ui.components.HardwarePanel
import com.example.ui.components.HardwareParameterSlider
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * TUNER SPOKE:
 * Precision YIN Chromatic & Strobe Pitch Tuner with A4 calibration (430–450 Hz),
 * source switching (Deck Signal, Live Mic, SMP2 Peer Stream), and reference tone pads.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TunerSpokeScreen(
    tunerState: TunerUiState,
    a4ReferenceHz: Double,
    hasDeckAudio: Boolean,
    onSelectSource: (TunerInputSource) -> Unit,
    onSelectPreset: (TuningPreset) -> Unit,
    onSetA4Reference: (Double) -> Unit,
    onAnalyzeDeckWindow: () -> Unit,
    onPlayReferenceNote: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val reading = tunerState.reading
    val centsClamped = reading.centsOffset.coerceIn(-50.0, 50.0).toFloat()
    val meterColor = when {
        !reading.detected -> TextMuted
        reading.isInTune -> EmeraldSignal
        abs(reading.centsOffset) <= 12.0 -> AmberHot
        else -> TacCoral
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Chromatic Arc Meter & Note Telemetry
        HardwarePanel(
            borderColor = meterColor.copy(alpha = 0.7f),
            modifier = Modifier.testTag("tuner_meter_panel")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CHROMATIC & STROBE YIN TUNER",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "SRC: ${tunerState.source.label} • A4 = ${"%.1f".format(a4ReferenceHz)} Hz",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HardwareLedIndicator(
                        active = reading.isInTune,
                        color = EmeraldSignal,
                        sizeDp = 12
                    )
                    Text(
                        text = if (reading.isInTune) "IN TUNE" else if (reading.detected) "TRACKING" else "IDLE",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (reading.isInTune) EmeraldSignal else TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .testTag("tuner_gauge_canvas")
            ) {
                val center = Offset(size.width / 2f, size.height * 0.86f)
                val radius = minOf(size.width * 0.40f, size.height * 0.74f)
                val arcTopLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2f, radius * 2f)

                drawArc(
                    color = HairlineDivider,
                    startAngle = 195f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                )

                // Center ±3 cents sweet zone
                drawArc(
                    color = EmeraldSignal.copy(alpha = 0.65f),
                    startAngle = 264f,
                    sweepAngle = 12f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                )

                // Calibration tick marks
                for (centsTick in -50..50 step 10) {
                    val deg = 270.0 + (centsTick / 50.0) * 68.0
                    val rad = deg * PI / 180.0
                    val rIn = radius * if (centsTick == 0) 0.78f else 0.86f
                    val rOut = radius * 1.06f
                    drawLine(
                        color = if (centsTick == 0) EmeraldSignal else HairlineDivider,
                        start = Offset(
                            center.x + (rIn * cos(rad)).toFloat(),
                            center.y + (rIn * sin(rad)).toFloat()
                        ),
                        end = Offset(
                            center.x + (rOut * cos(rad)).toFloat(),
                            center.y + (rOut * sin(rad)).toFloat()
                        ),
                        strokeWidth = if (centsTick == 0) 3.dp.toPx() else 1.5.dp.toPx()
                    )
                }

                // Dynamic Needle
                val needleDeg = 270.0 + (centsClamped / 50.0) * 68.0
                val needleRad = needleDeg * PI / 180.0
                val tip = Offset(
                    center.x + (radius * 0.94f * cos(needleRad)).toFloat(),
                    center.y + (radius * 0.94f * sin(needleRad)).toFloat()
                )
                drawLine(
                    color = meterColor,
                    start = center,
                    end = tip,
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(color = meterColor, radius = 7.dp.toPx(), center = center)
            }

            // Big Note & Frequency Telemetry Row
            Surface(
                color = ObsidianChassis,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, meterColor.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = reading.fullNote,
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = meterColor,
                            modifier = Modifier.testTag("tuner_note_readout")
                        )
                        Text(
                            text = if (reading.detected) {
                                "${"%.2f".format(reading.frequencyHz)} Hz (Target ${"%.2f".format(reading.targetFrequencyHz)} Hz)"
                            } else {
                                "Play instrument, sing into mic, or tap RE-ANALYZE DECK"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (reading.detected) {
                                "${if (reading.centsOffset >= 0) "+" else ""}${"%.1f".format(reading.centsOffset)} ¢"
                            } else {
                                "-- ¢"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = meterColor
                        )
                        Text(
                            text = "CONF ${(reading.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
            }
        }

        // 2. Signal Source & A4 Calibration
        HardwarePanel {
            Text("INPUT SOURCE & A4 CALIBRATION", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TunerInputSource.entries.forEach { src ->
                    HardwareChipToggle(
                        label = src.label,
                        selected = tunerState.source == src,
                        onClick = { onSelectSource(src) },
                        accentColor = NeonCyan,
                        modifier = Modifier.testTag("tuner_source_${src.name.lowercase()}")
                    )
                }
                Button(
                    onClick = onAnalyzeDeckWindow,
                    enabled = hasDeckAudio,
                    colors = ButtonDefaults.buttonColors(containerColor = AmberHot),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("tuner_analyze_button")
                ) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = "Analyze Deck Signal", tint = ObsidianChassis)
                    Text(" ANALYZE DECK", color = ObsidianChassis, style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HardwareParameterSlider(
                label = "CONCERT A4 REFERENCE",
                valueText = "${"%.1f".format(a4ReferenceHz)} Hz",
                value = a4ReferenceHz.toFloat(),
                valueRange = 430f..450f,
                accentColor = VioletMod,
                onValueChange = { onSetA4Reference(it.toDouble()) },
                testTag = "tuner_a4_slider"
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HardwareChipToggle(
                    label = "A4 = 432 Hz",
                    selected = abs(a4ReferenceHz - 432.0) < 0.4,
                    onClick = { onSetA4Reference(432.0) },
                    accentColor = VioletMod,
                    modifier = Modifier.testTag("tuner_a4_432")
                )
                HardwareChipToggle(
                    label = "A4 = 440 Hz",
                    selected = abs(a4ReferenceHz - 440.0) < 0.4,
                    onClick = { onSetA4Reference(440.0) },
                    accentColor = EmeraldSignal,
                    modifier = Modifier.testTag("tuner_a4_440")
                )
            }
        }

        // 3. Instrument Presets & Audible Reference Pitch Generator Pads
        HardwarePanel(borderColor = AmberHot.copy(alpha = 0.45f)) {
            Text("TUNING PRESETS & REFERENCE TONE PADS", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TuningPreset.entries.forEach { preset ->
                    HardwareChipToggle(
                        label = preset.label,
                        selected = tunerState.preset == preset,
                        onClick = { onSelectPreset(preset) },
                        accentColor = AmberHot,
                        modifier = Modifier.testTag("tuner_preset_${preset.name.lowercase()}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "TAP STRING / NOTE TO AUDITION REFERENCE PITCH",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tunerState.preset.notes.forEachIndexed { idx, (noteLabel, freqBase) ->
                    val scaledFreq = freqBase * (a4ReferenceHz / 440.0)
                    Button(
                        onClick = { onPlayReferenceNote(scaledFreq) },
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianChassis),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("tuner_ref_note_$idx")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = "Play $noteLabel",
                            tint = NeonCyan
                        )
                        Text(
                            text = " $noteLabel (${"%.1f".format(scaledFreq)})",
                            color = TextPrimary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}
