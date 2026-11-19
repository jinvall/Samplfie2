package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.BrushedPanel
import com.example.ui.theme.ElevatedBezel
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianChassis
import com.example.ui.theme.TacCoral
import com.example.ui.theme.TacCoralGlow
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Tactile Press-and-Hold "TAC" Performance Pad.
 * Pressing down triggers live AudioRecord mic capture; releasing immediately stops and loads the .wav in the editor.
 */
@Composable
fun TacHoldPad(
    hasMicPermission: Boolean,
    isRecording: Boolean,
    elapsedMs: Long,
    onRequestMicPermission: () -> Unit,
    onPressStart: () -> Unit,
    onPressRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed || isRecording) 0.96f else 1.0f,
        animationSpec = spring(stiffness = 600f),
        label = "tacScale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isRecording) TacCoralGlow else if (hasMicPermission) NeonCyan else HairlineDivider,
        label = "tacBorder"
    )
    val infinitePulse = rememberInfiniteTransition(label = "recPulse")
    val pulseAlpha by infinitePulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    if (!hasMicPermission) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = BrushedPanel,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, TacCoral.copy(alpha = 0.6f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mic_permission_required),
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "Grant RECORD_AUDIO to capture live .wav takes with the TAC pad.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                Button(
                    onClick = onRequestMicPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = TacCoral),
                    modifier = Modifier.testTag("grant_mic_button")
                ) {
                    Text(stringResource(R.string.grant_mic_permission))
                }
            }
        }
        return
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .pointerInput(hasMicPermission) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPressStart()
                    waitForUpOrCancellation()
                    isPressed = false
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPressRelease()
                }
            }
            .testTag("tac_hold_button"),
        shape = RoundedCornerShape(18.dp),
        color = ObsidianChassis,
        border = BorderStroke(2.dp, borderColor),
        tonalElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = if (isRecording) {
                        Brush.horizontalGradient(
                            listOf(
                                TacCoral.copy(alpha = 0.28f * pulseAlpha),
                                TacCoralGlow.copy(alpha = 0.16f * pulseAlpha),
                                TacCoral.copy(alpha = 0.28f * pulseAlpha)
                            )
                        )
                    } else {
                        Brush.verticalGradient(listOf(ElevatedBezel, ObsidianChassis))
                    }
                )
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = if (isRecording) TacCoral else TacCoral.copy(alpha = 0.2f),
                        border = BorderStroke(2.dp, if (isRecording) TextPrimary else TacCoral)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isRecording) Icons.Filled.FiberManualRecord else Icons.Filled.Mic,
                                contentDescription = if (isRecording) "Recording Live" else "Hold TAC Pad to Record",
                                tint = TextPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = if (isRecording) stringResource(R.string.tac_recording_label) else stringResource(R.string.tac_hold_label),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isRecording) TacCoralGlow else TextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isRecording) stringResource(R.string.tac_hint_recording) else stringResource(R.string.tac_hint_idle),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                val secs = elapsedMs / 1000.0
                Text(
                    text = if (isRecording) "${"%.2f".format(secs)}s" else "WAV",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isRecording) TacCoralGlow else NeonCyan
                )
            }
        }
    }
}
