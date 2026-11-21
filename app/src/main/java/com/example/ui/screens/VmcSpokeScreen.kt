package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.midi.VmcEngineState
import com.example.midi.VmcMappingRule
import com.example.midi.VmcMsgType
import com.example.midi.VmcRouteMode
import com.example.midi.VmcTarget
import com.example.ui.components.HardwareChipToggle
import com.example.ui.components.HardwarePanel
import com.example.ui.components.HardwareParameterSlider
import com.example.ui.theme.AmberHot
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

private data class VirtualPadSpec(
    val tag: String,
    val label: String,
    val channel64: Int,
    val note: Int,
    val accent: Color
)

private data class VirtualFaderSpec(
    val key: String,
    val label: String,
    val channel64: Int,
    val ccNumber: Int,
    val accent: Color
)

/**
 * VMC SPOKE:
 * Mappable 64-Channel Virtual MIDI Controller (4 Buses x 16 Channels = CH 1..64).
 * Turns Android into a live tactile VMC companion surface for Desktop (and receives Peer VMC telemetry).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmcSpokeScreen(
    vmcState: VmcEngineState,
    onSelectBank: (Int) -> Unit,
    onSelectChannel64: (Int) -> Unit,
    onSetRouteMode: (VmcRouteMode) -> Unit,
    onEmitControl: (VmcMsgType, Int, Float, Int) -> Unit,
    onArmLearnRule: (String?) -> Unit,
    onArmLearnNew: (VmcTarget) -> Unit,
    onUpsertRule: (VmcMappingRule) -> Unit,
    onRemoveRule: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedNewTarget by remember { mutableStateOf(VmcTarget.FX_REVERB_MIX) }
    var newMsgType by remember { mutableStateOf(VmcMsgType.CC) }
    var newNumber by remember { mutableIntStateOf(74) }

    val faderValues = remember {
        mutableMapOf(
            "wah" to 0.5f,
            "drive" to 0.35f,
            "tilt" to 0.5f,
            "rev" to 0.3f,
            "echo" to 0.25f,
            "wet" to 0.8f,
            "bpm" to 0.53f
        )
    }

    val pads = remember {
        listOf(
            VirtualPadSpec("play", "DECK PLAY\nCH1 N60", 1, 60, EmeraldSignal),
            VirtualPadSpec("loop", "DECK LOOP\nCH1 N61", 1, 61, NeonCyan),
            VirtualPadSpec("sync", "SYNC BOOTH\nCH1 N62", 1, 62, MagentaFx),
            VirtualPadSpec("stomp", "STOMP BYP\nCH2 N64", 2, 64, TacCoral),
            VirtualPadSpec("loop_a", "LOOP A\nCH17 N36", 17, 36, EmeraldSignal),
            VirtualPadSpec("pad_1", "SEQ PAD 1\nCH33 N40", 33, 40, AmberHot),
            VirtualPadSpec("pad_2", "SEQ PAD 2\nCH33 N41", 33, 41, AmberHot),
            VirtualPadSpec("handoff", "PUSH PEER\nCH64 N96", 64, 96, VioletMod)
        )
    }

    val faders = remember {
        listOf(
            VirtualFaderSpec("wah", "WAH FREQ (CH1 CC1)", 1, 1, NeonCyan),
            VirtualFaderSpec("drive", "OVERDRIVE (CH17 CC71)", 17, 71, TacCoral),
            VirtualFaderSpec("tilt", "STOMP TILT (CH33 CC74)", 33, 74, AmberHot),
            VirtualFaderSpec("rev", "REVERB MIX (CH17 CC91)", 17, 91, VioletMod),
            VirtualFaderSpec("echo", "ECHO MIX (CH17 CC93)", 17, 93, NeonCyan),
            VirtualFaderSpec("wet", "STOMP WET (CH33 CC7)", 33, 7, EmeraldSignal),
            VirtualFaderSpec("bpm", "MASTER BPM (CH49 CC14)", 49, 14, MagentaFx)
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. 64-Channel Bank, Channel Stepper & Route Mode
        HardwarePanel(borderColor = VioletMod.copy(alpha = 0.65f)) {
            Text(
                text = "VMC 64-CH VIRTUAL MIDI COMPANION",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                text = vmcState.lastEventSummary,
                style = MaterialTheme.typography.labelSmall,
                color = if (vmcState.learnTargetRuleId != null || vmcState.learnNewTarget != null) AmberHot else NeonCyan,
                modifier = Modifier.testTag("vmc_status_summary")
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val bankLabels = listOf("B1:1-16", "B2:17-32", "B3:33-48", "B4:49-64")
                bankLabels.forEachIndexed { bIdx, label ->
                    HardwareChipToggle(
                        label = label,
                        selected = vmcState.selectedBank == bIdx,
                        onClick = { onSelectBank(bIdx) },
                        accentColor = VioletMod,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("vmc_bank_$bIdx")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HardwareChipToggle(
                    label = "CH -",
                    selected = false,
                    onClick = { onSelectChannel64((vmcState.activeChannel64 - 1).coerceAtLeast(1)) },
                    accentColor = NeonCyan,
                    modifier = Modifier.testTag("vmc_ch_prev")
                )
                Surface(
                    color = ObsidianChassis,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
                    modifier = Modifier.weight(1f)
                ) {
                    val bus = ((vmcState.activeChannel64 - 1) / 16) + 1
                    val ch16 = ((vmcState.activeChannel64 - 1) % 16) + 1
                    Text(
                        text = "ACTIVE: CH ${vmcState.activeChannel64} (BUS $bus : $ch16)",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = NeonCyan,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                            .testTag("vmc_active_channel_label")
                    )
                }
                HardwareChipToggle(
                    label = "CH +",
                    selected = false,
                    onClick = { onSelectChannel64((vmcState.activeChannel64 + 1).coerceAtMost(64)) },
                    accentColor = NeonCyan,
                    modifier = Modifier.testTag("vmc_ch_next")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VmcRouteMode.entries.forEach { mode ->
                    HardwareChipToggle(
                        label = mode.label,
                        selected = vmcState.routeMode == mode,
                        onClick = { onSetRouteMode(mode) },
                        accentColor = EmeraldSignal,
                        modifier = Modifier.testTag("vmc_route_${mode.name.lowercase()}")
                    )
                }
            }
        }

        // 2. Virtual Trigger Pads & Assignable CC Sliders
        HardwarePanel(borderColor = NeonCyan.copy(alpha = 0.45f)) {
            Text("VIRTUAL CONTROL SURFACE (PADS & CC FADERS)", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            for (rowIdx in 0..1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (colIdx in 0..3) {
                        val spec = pads[rowIdx * 4 + colIdx]
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onEmitControl(VmcMsgType.NOTE_ON, spec.note, 1.0f, spec.channel64)
                                }
                                .testTag("vmc_pad_${spec.tag}"),
                            color = spec.accent.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.5.dp, spec.accent)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(4.dp)) {
                                Text(
                                    text = spec.label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = spec.accent
                                )
                            }
                        }
                    }
                }
                if (rowIdx == 0) Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(10.dp))
            faders.forEach { fader ->
                var localVal by remember { mutableFloatStateOf(faderValues[fader.key] ?: 0.5f) }
                HardwareParameterSlider(
                    label = fader.label,
                    valueText = "${(localVal * 127).toInt()}",
                    value = localVal,
                    valueRange = 0f..1f,
                    accentColor = fader.accent,
                    onValueChange = { next ->
                        localVal = next
                        faderValues[fader.key] = next
                        onEmitControl(VmcMsgType.CC, fader.ccNumber, next, fader.channel64)
                    },
                    testTag = "vmc_fader_${fader.key}"
                )
            }
        }

        // 3. New Rule / MIDI Learn Builder
        HardwarePanel(borderColor = AmberHot.copy(alpha = 0.45f)) {
            Text("64-CH MAPPING BUILDER & MIDI LEARN", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            Text("SELECT TARGET FUNCTION", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            val featuredTargets = listOf(
                VmcTarget.DECK_PLAY_PAUSE,
                VmcTarget.DECK_LOOP_TOGGLE,
                VmcTarget.SYNC_ALL_PLAY,
                VmcTarget.STOMP_BYPASS,
                VmcTarget.FX_REVERB_MIX,
                VmcTarget.FX_ECHO_MIX,
                VmcTarget.FX_WAH_FREQ,
                VmcTarget.FX_DRIVE,
                VmcTarget.BPM_TEMPO,
                VmcTarget.SESSION_HANDOFF_PUSH
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                featuredTargets.forEach { target ->
                    HardwareChipToggle(
                        label = target.label,
                        selected = selectedNewTarget == target,
                        onClick = { selectedNewTarget = target },
                        accentColor = AmberHot
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HardwareChipToggle(
                    label = if (newMsgType == VmcMsgType.CC) "TYPE: CC" else "TYPE: NOTE",
                    selected = true,
                    onClick = {
                        newMsgType = if (newMsgType == VmcMsgType.CC) VmcMsgType.NOTE_ON else VmcMsgType.CC
                    },
                    accentColor = NeonCyan
                )
                HardwareChipToggle(
                    label = "#${newNumber} -",
                    selected = false,
                    onClick = { newNumber = (newNumber - 1).coerceAtLeast(0) },
                    accentColor = TextSecondary
                )
                HardwareChipToggle(
                    label = "#${newNumber} +",
                    selected = false,
                    onClick = { newNumber = (newNumber + 1).coerceAtMost(127) },
                    accentColor = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onUpsertRule(
                            VmcMappingRule(
                                id = "vmc_${System.currentTimeMillis()}",
                                channel64 = vmcState.activeChannel64,
                                msgType = newMsgType,
                                number = newNumber,
                                target = selectedNewTarget,
                                routeMode = vmcState.routeMode
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldSignal),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("vmc_add_rule_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add Mapping", tint = ObsidianChassis)
                    Text(" MAP ON CH ${vmcState.activeChannel64}", color = ObsidianChassis, style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = { onArmLearnNew(selectedNewTarget) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (vmcState.learnNewTarget != null) TacCoral else AmberHot
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("vmc_learn_new_button")
                ) {
                    Text(
                        text = if (vmcState.learnNewTarget != null) "WAITING INPUT…" else "MIDI LEARN NEW",
                        color = ObsidianChassis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        // 4. Active 64-Channel Mapping Matrix
        HardwarePanel {
            Text(
                text = "64-CHANNEL ROUTING MATRIX (${vmcState.mappings.size} RULES)",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            vmcState.mappings.forEach { rule ->
                val isLearningThis = vmcState.learnTargetRuleId == rule.id
                Surface(
                    color = ObsidianChassis,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (isLearningThis) AmberHot else HairlineDivider),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .testTag("vmc_rule_row_${rule.id}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rule.target.label,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = "${rule.channelLabel()} • ${rule.msgType.label} #${rule.number}",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonCyan
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            HardwareChipToggle(
                                label = if (isLearningThis) "WAIT" else "LEARN",
                                selected = isLearningThis,
                                onClick = { onArmLearnRule(if (isLearningThis) null else rule.id) },
                                accentColor = AmberHot,
                                modifier = Modifier.testTag("vmc_rule_learn_${rule.id}")
                            )
                            IconButton(
                                onClick = {
                                    onEmitControl(
                                        rule.msgType,
                                        rule.number,
                                        if (rule.target.isContinuous) 0.72f else 1.0f,
                                        rule.channel64.coerceAtLeast(1)
                                    )
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("vmc_rule_trig_${rule.id}")
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Trigger Rule", tint = EmeraldSignal)
                            }
                            IconButton(
                                onClick = { onRemoveRule(rule.id) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("vmc_rule_del_${rule.id}")
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove Rule", tint = TacCoral)
                            }
                        }
                    }
                }
            }

            if (vmcState.recentLog.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("LIVE 64-CH VMC TELEMETRY", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                vmcState.recentLog.take(6).forEach { entry ->
                    Text(entry, style = MaterialTheme.typography.labelSmall, color = EmeraldSignal)
                }
            }
        }
    }
}
