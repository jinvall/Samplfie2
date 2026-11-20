package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.config.RigConfig
import com.example.ui.StudioSpoke
import com.example.ui.theme.AmberHot
import com.example.ui.theme.BrushedPanel
import com.example.ui.theme.ElevatedBezel
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.GunmetalDeck
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianChassis
import com.example.ui.theme.TacCoral
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun HardwareTopRack(
    rigConfig: RigConfig,
    selectedSpoke: StudioSpoke,
    activeFxCount: Int,
    isMasterBypass: Boolean,
    isRecording: Boolean,
    hasAudio: Boolean,
    bpm: Float,
    onReturnToBooth: () -> Unit,
    onExportMp3Click: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = GunmetalDeck,
        border = BorderStroke(1.dp, HairlineDivider),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (selectedSpoke != StudioSpoke.BOOTH) {
                    Surface(
                        color = NeonCyan.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, NeonCyan),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onReturnToBooth)
                            .testTag("top_booth_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Hub,
                                contentDescription = "Return to Production Booth Hub",
                                tint = NeonCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Text("BOOTH", style = MaterialTheme.typography.labelSmall, color = NeonCyan)
                        }
                    }
                } else {
                    HardwareLedIndicator(
                        active = true,
                        color = if (isRecording) TacCoral else NeonCyan,
                        sizeDp = 10
                    )
                }

                Column {
                    Text(
                        text = if (selectedSpoke == StudioSpoke.BOOTH) "SAMPLFIE2" else "SAMPLFIE2 · ${selectedSpoke.shortLabel}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${rigConfig.sampleRate / 1000}kHz • ${rigConfig.channels}CH • B${rigConfig.bufferSize} • ${rigConfig.protocol.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("top_rig_summary")
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = BrushedPanel,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, HairlineDivider)
                ) {
                    Text(
                        text = "${bpm.toInt()} BPM",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberHot,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    color = if (isMasterBypass) ElevatedBezel else if (activeFxCount > 0) NeonCyan.copy(alpha = 0.16f) else BrushedPanel,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isMasterBypass) AmberHot else if (activeFxCount > 0) NeonCyan else HairlineDivider
                    )
                ) {
                    Text(
                        text = if (isMasterBypass) "BYPASS" else "FX $activeFxCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isMasterBypass) AmberHot else if (activeFxCount > 0) NeonCyan else TextMuted,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }

                IconButton(
                    onClick = onExportMp3Click,
                    enabled = hasAudio,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("top_export_mp3_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = "Export MP3",
                        tint = if (hasAudio) EmeraldSignal else TextMuted
                    )
                }
            }
        }
    }
}

@Composable
fun HardwareLedIndicator(
    active: Boolean,
    color: Color,
    sizeDp: Int = 8,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(if (active) color else TextMuted.copy(alpha = 0.35f))
            .border(1.dp, if (active) color.copy(alpha = 0.8f) else HairlineDivider, CircleShape)
    )
}

@Composable
fun HardwarePanel(
    modifier: Modifier = Modifier,
    borderColor: Color = HairlineDivider,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = BrushedPanel,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            content = content
        )
    }
}

@Composable
fun HardwareParameterSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    accentColor: Color = NeonCyan,
    enabled: Boolean = true,
    testTag: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) TextSecondary else TextMuted
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) accentColor else TextMuted
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = ObsidianChassis,
                disabledThumbColor = TextMuted,
                disabledActiveTrackColor = TextMuted.copy(alpha = 0.4f)
            )
        )
    }
}

@Composable
fun VuPeakMeterBar(
    peak: Float,
    rms: Float,
    label: String = "OUT",
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.width(34.dp)
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(ObsidianChassis)
        ) {
            val segments = 24
            val gap = 2.dp.toPx()
            val segW = (size.width - gap * (segments - 1)) / segments
            val litPeak = (peak.coerceIn(0f, 1f) * segments).toInt()
            val litRms = (rms.coerceIn(0f, 1f) * segments).toInt()

            for (i in 0 until segments) {
                val segColor = when {
                    i >= segments - 3 -> TacCoral
                    i >= segments - 7 -> AmberHot
                    else -> EmeraldSignal
                }
                val alpha = when {
                    i < litRms -> 1f
                    i < litPeak -> 0.72f
                    else -> 0.14f
                }
                drawRoundRect(
                    color = segColor.copy(alpha = alpha),
                    topLeft = Offset(i * (segW + gap), 2f),
                    size = Size(segW, size.height - 4f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }
        }
        val dbLabel = if (peak > 0.001f) {
            "${"%.1f".format(20f * kotlin.math.log10(peak.toDouble()))}dB"
        } else {
            "-∞dB"
        }
        Text(
            text = dbLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (peak > 0.92f) TacCoral else TextSecondary,
            modifier = Modifier.width(48.dp)
        )
    }
}

@Composable
fun HardwareChipToggle(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accentColor: Color = NeonCyan,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = if (selected) accentColor.copy(alpha = 0.2f) else ObsidianChassis,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (selected) accentColor else HairlineDivider)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) accentColor else TextSecondary,
                maxLines = 1
            )
        }
    }
}
