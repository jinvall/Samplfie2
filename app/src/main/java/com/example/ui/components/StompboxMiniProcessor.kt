package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.dsp.StompboxMasterState
import com.example.ui.theme.AmberHot
import com.example.ui.theme.ElevatedBezel
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

/**
 * Die-cast Live Stompbox Mini Multi-FX Processor:
 * Controls overall FX groups (Dynamics/Drive, Tone Tilt EQ, Modulation Scale, Space/Time Scale, Master Wet Mix),
 * 3 group Kill switches, a live X/Y touch pad, Tap Tempo, and illuminated Bypass Footswitch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StompboxMiniProcessor(
    stompbox: StompboxMasterState,
    bpm: Float,
    onUpdateStompbox: (StompboxMasterState.() -> StompboxMasterState) -> Unit,
    onToggleBypass: () -> Unit,
    onXyDrag: (Float, Float) -> Unit,
    onTapTempo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeAccent = if (stompbox.engaged) NeonCyan else HairlineDivider

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = GunmetalDeck,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(2.dp, activeAccent)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Stompbox Chassis Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HardwareLedIndicator(
                        active = stompbox.engaged,
                        color = TacCoral,
                        sizeDp = 12
                    )
                    Column {
                        Text(
                            text = "STOMPBOX MASTER FX",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = if (stompbox.engaged) "ACTIVE • ${stompbox.liveMode.title}" else "TRUE BYPASS",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (stompbox.engaged) EmeraldSignal else TextMuted,
                            modifier = Modifier.testTag("stomp_status_text")
                        )
                    }
                }
                HardwareChipToggle(
                    label = "TAP ${bpm.toInt()}",
                    selected = false,
                    onClick = onTapTempo,
                    accentColor = AmberHot,
                    modifier = Modifier.testTag("stomp_tap_tempo")
                )
            }

            // Interactive X/Y Stomp Pad (X = Tilt/Mod, Y = Space/Echo)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(ObsidianChassis, ElevatedBezel, ObsidianChassis)
                        )
                    )
                    .border(1.dp, activeAccent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            onXyDrag(offset.x / size.width.toFloat(), 1f - (offset.y / size.height.toFloat()))
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            onXyDrag(
                                change.position.x / size.width.toFloat(),
                                1f - (change.position.y / size.height.toFloat())
                            )
                        }
                    }
                    .testTag("stomp_xy_pad")
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val px = stompbox.xyPadX.coerceIn(0f, 1f) * size.width
                    val py = (1f - stompbox.xyPadY.coerceIn(0f, 1f)) * size.height

                    // Grid crosshairs
                    drawLine(
                        color = HairlineDivider,
                        start = Offset(size.width / 2f, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = HairlineDivider,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 1.dp.toPx()
                    )

                    // Target reticle
                    drawLine(NeonCyan.copy(alpha = 0.45f), Offset(px, 0f), Offset(px, size.height), 1.5.dp.toPx())
                    drawLine(MagentaFx.copy(alpha = 0.45f), Offset(0f, py), Offset(size.width, py), 1.5.dp.toPx())
                    drawCircle(
                        color = if (stompbox.engaged) AmberHot else TextMuted,
                        radius = 14.dp.toPx(),
                        center = Offset(px, py),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = if (stompbox.engaged) NeonCyan else TextMuted,
                        radius = 6.dp.toPx(),
                        center = Offset(px, py)
                    )
                }
                Text(
                    text = "X/Y LIVE PAD · X: TILT/MOD (${(stompbox.xyPadX * 100).toInt()}%) • Y: SPACE (${(stompbox.xyPadY * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                )
            }

            // 3 FX Group Kill Switches
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HardwareChipToggle(
                    label = if (stompbox.killDrive) "DRIVE KILLED" else "DRIVE ON",
                    selected = stompbox.killDrive,
                    onClick = { onUpdateStompbox { copy(killDrive = !killDrive) } },
                    accentColor = TacCoral,
                    modifier = Modifier.testTag("stomp_kill_drive")
                )
                HardwareChipToggle(
                    label = if (stompbox.killMod) "MOD KILLED" else "MOD ON",
                    selected = stompbox.killMod,
                    onClick = { onUpdateStompbox { copy(killMod = !killMod) } },
                    accentColor = VioletMod,
                    modifier = Modifier.testTag("stomp_kill_mod")
                )
                HardwareChipToggle(
                    label = if (stompbox.killTime) "SPACE KILLED" else "SPACE ON",
                    selected = stompbox.killTime,
                    onClick = { onUpdateStompbox { copy(killTime = !killTime) } },
                    accentColor = AmberHot,
                    modifier = Modifier.testTag("stomp_kill_time")
                )
            }

            // Stompbox Master Group Scalars
            HardwareParameterSlider(
                label = "DYNAMICS / OUTPUT TRIM",
                valueText = "${if (stompbox.dynamicsTrimDb >= 0) "+" else ""}${"%.1f".format(stompbox.dynamicsTrimDb)} dB",
                value = stompbox.dynamicsTrimDb,
                valueRange = -6f..6f,
                accentColor = EmeraldSignal,
                onValueChange = { v -> onUpdateStompbox { copy(engaged = true, dynamicsTrimDb = v) } },
                testTag = "stomp_dynamics_slider"
            )
            HardwareParameterSlider(
                label = "MASTER DJ TONE TILT",
                valueText = if (stompbox.toneTilt < -0.05f) "BASS ${(-stompbox.toneTilt * 100).toInt()}%" else if (stompbox.toneTilt > 0.05f) "AIR +${(stompbox.toneTilt * 100).toInt()}%" else "FLAT",
                value = stompbox.toneTilt,
                valueRange = -1f..1f,
                accentColor = NeonCyan,
                onValueChange = { v -> onUpdateStompbox { copy(engaged = true, toneTilt = v) } },
                testTag = "stomp_tilt_slider"
            )
            HardwareParameterSlider(
                label = "MOD GROUP SCALE",
                valueText = "${(stompbox.modScale * 100).toInt()}%",
                value = stompbox.modScale,
                valueRange = 0f..1.8f,
                accentColor = VioletMod,
                onValueChange = { v -> onUpdateStompbox { copy(engaged = true, modScale = v) } }
            )
            HardwareParameterSlider(
                label = "SPACE / DELAY SCALE",
                valueText = "${(stompbox.spaceScale * 100).toInt()}%",
                value = stompbox.spaceScale,
                valueRange = 0f..1.8f,
                accentColor = AmberHot,
                onValueChange = { v -> onUpdateStompbox { copy(engaged = true, spaceScale = v) } }
            )
            HardwareParameterSlider(
                label = "STOMP MASTER WET/DRY MIX",
                valueText = "${(stompbox.masterWetMix * 100).toInt()}%",
                value = stompbox.masterWetMix,
                valueRange = 0f..1f,
                accentColor = MagentaFx,
                onValueChange = { v -> onUpdateStompbox { copy(engaged = true, masterWetMix = v) } },
                testTag = "stomp_wet_mix_slider"
            )

            // Die-Cast Stomp Bypass Footswitch
            Button(
                onClick = onToggleBypass,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (stompbox.engaged) TacCoral else ElevatedBezel
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("stomp_bypass_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = "Stompbox Bypass Footswitch",
                    tint = if (stompbox.engaged) ObsidianChassis else TextPrimary
                )
                Text(
                    text = if (stompbox.engaged) " STOMP ENGAGED (TAP TO BYPASS)" else " BYPASSED (TAP TO ENGAGE)",
                    color = if (stompbox.engaged) ObsidianChassis else TextPrimary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
