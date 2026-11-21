package com.samplfie.desktop.midi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VmcMsgType(val label: String, val code: Int) {
    NOTE_ON("NOTE ON", 0),
    NOTE_OFF("NOTE OFF", 1),
    CC("CC", 2),
    PITCH_BEND("PITCH", 3),
    TRANSPORT_START("START", 4),
    TRANSPORT_STOP("STOP", 5);

    companion object {
        fun fromCode(code: Int): VmcMsgType = entries.firstOrNull { it.code == code } ?: CC
    }
}

enum class VmcRouteMode(val label: String) {
    LOCAL_AND_PEER("LOCAL + PEER"),
    LOCAL_ONLY("LOCAL ONLY"),
    PEER_VMC_ONLY("PEER VMC TX")
}

enum class VmcTarget(val label: String, val isContinuous: Boolean) {
    DECK_PLAY_PAUSE("Deck Play/Pause", false),
    DECK_STOP("Deck Stop", false),
    DECK_LOOP_TOGGLE("Deck Loop On/Off", false),
    SYNC_ALL_PLAY("Master Sync Play/Stop", false),
    BPM_TEMPO("Master BPM (60-180)", true),
    STOMP_BYPASS("Stompbox Engage/Bypass", false),
    STOMP_WET_MIX("Stomp Wet Mix", true),
    STOMP_TONE_TILT("Stomp DJ Tone Tilt", true),
    STOMP_DYNAMICS("Stomp Dynamics Trim", true),
    STOMP_MOD_SCALE("Stomp Mod Scale", true),
    STOMP_SPACE_SCALE("Stomp Space Scale", true),
    FX_DRIVE("Overdrive Amount", true),
    FX_WAH_FREQ("Wah Center Freq", true),
    FX_PHASER_MIX("Phaser Mix", true),
    FX_FLANGER_MIX("Flanger Mix", true),
    FX_CHORUS_MIX("Chorus Mix", true),
    FX_ROTARY_RATE("Rotary Rate Hz", true),
    FX_ECHO_MIX("Echo Mix", true),
    FX_DELAY_MIX("Delay Mix", true),
    FX_REVERB_MIX("Reverb Mix", true),
    FX_PITCH_SEMI("Pitch Semitones", true),
    LOOPER_SLOT_A("Looper Slot A Toggle", false),
    LOOPER_SLOT_B("Looper Slot B Toggle", false),
    LOOPER_SLOT_C("Looper Slot C Toggle", false),
    LOOPER_SLOT_D("Looper Slot D Toggle", false),
    SEQ_PAD_1("Seq Pad 1 (Sample)", false),
    SEQ_PAD_2("Seq Pad 2 (Kick 808)", false),
    SEQ_PAD_3("Seq Pad 3 (Snare 909)", false),
    SEQ_PAD_4("Seq Pad 4 (Hi-Hat)", false),
    SEQ_PLAY_TOGGLE("Sequencer Play/Stop", false),
    TIMELINE_PLAY_TOGGLE("Timeline Play/Stop", false),
    SESSION_HANDOFF_PUSH("Push Session Handoff", false)
}

/**
 * A 64-Channel Virtual MIDI Controller mapping rule (Channels 1..64 across 4 MIDI buses x 16ch, or 0 = ANY).
 */
data class VmcMappingRule(
    val id: String,
    val channel64: Int, // 0 = ANY, 1..64 = Bus (1..4) * 16 + Ch (1..16)
    val msgType: VmcMsgType,
    val number: Int, // 0..127 (CC# or Note#)
    val target: VmcTarget,
    val routeMode: VmcRouteMode = VmcRouteMode.LOCAL_AND_PEER
) {
    fun busNumber(): Int = if (channel64 <= 0) 0 else ((channel64 - 1) / 16) + 1
    fun busChannel16(): Int = if (channel64 <= 0) 0 else ((channel64 - 1) % 16) + 1
    fun channelLabel(): String = if (channel64 <= 0) "ANY" else "CH $channel64 (B${busNumber()}:${busChannel16()})"
}

data class VmcEventPacket(
    val channel64: Int, // 1..64
    val msgType: VmcMsgType,
    val data1: Int,
    val data2: Int,
    val normalized: Float
)

data class VmcEngineState(
    val selectedBank: Int = 0, // 0 -> CH 1-16, 1 -> CH 17-32, 2 -> CH 33-48, 3 -> CH 49-64
    val activeChannel64: Int = 1,
    val routeMode: VmcRouteMode = VmcRouteMode.LOCAL_AND_PEER,
    val learnTargetRuleId: String? = null,
    val learnNewTarget: VmcTarget? = null,
    val mappings: List<VmcMappingRule> = default64ChMappings(),
    val lastEventSummary: String = "VMC 64-CH ENGINE READY",
    val recentLog: List<String> = emptyList(),
    val hardwareInputs: List<String> = emptyList(),
    val hardwareOutputs: List<String> = emptyList()
) {
    companion object {
        fun default64ChMappings(): List<VmcMappingRule> = listOf(
            VmcMappingRule("r_play", 1, VmcMsgType.NOTE_ON, 60, VmcTarget.DECK_PLAY_PAUSE),
            VmcMappingRule("r_loop", 1, VmcMsgType.NOTE_ON, 61, VmcTarget.DECK_LOOP_TOGGLE),
            VmcMappingRule("r_sync", 1, VmcMsgType.NOTE_ON, 62, VmcTarget.SYNC_ALL_PLAY),
            VmcMappingRule("r_stomp", 2, VmcMsgType.NOTE_ON, 64, VmcTarget.STOMP_BYPASS),
            VmcMappingRule("r_wah", 1, VmcMsgType.CC, 1, VmcTarget.FX_WAH_FREQ),
            VmcMappingRule("r_drive", 17, VmcMsgType.CC, 71, VmcTarget.FX_DRIVE),
            VmcMappingRule("r_reverb", 17, VmcMsgType.CC, 91, VmcTarget.FX_REVERB_MIX),
            VmcMappingRule("r_echo", 17, VmcMsgType.CC, 93, VmcTarget.FX_ECHO_MIX),
            VmcMappingRule("r_tilt", 33, VmcMsgType.CC, 74, VmcTarget.STOMP_TONE_TILT),
            VmcMappingRule("r_wet", 33, VmcMsgType.CC, 7, VmcTarget.STOMP_WET_MIX),
            VmcMappingRule("r_bpm", 49, VmcMsgType.CC, 14, VmcTarget.BPM_TEMPO),
            VmcMappingRule("r_handoff", 64, VmcMsgType.NOTE_ON, 96, VmcTarget.SESSION_HANDOFF_PUSH),
            VmcMappingRule("r_loop_a", 17, VmcMsgType.NOTE_ON, 36, VmcTarget.LOOPER_SLOT_A),
            VmcMappingRule("r_loop_b", 17, VmcMsgType.NOTE_ON, 37, VmcTarget.LOOPER_SLOT_B),
            VmcMappingRule("r_pad_1", 33, VmcMsgType.NOTE_ON, 40, VmcTarget.SEQ_PAD_1),
            VmcMappingRule("r_pad_2", 33, VmcMsgType.NOTE_ON, 41, VmcTarget.SEQ_PAD_2)
        )
    }
}

/**
 * 64-Channel Mappable Virtual MIDI Controller Engine for Samplfie2 Desktop.
 * Supports Buses 1-4 (Channels 1-64), MIDI Learn, Bidirectional Peer VMC2 companion control,
 * and local hardware MIDI bridging.
 */
class DesktopVmcEngine(
    private val onExecuteTarget: (VmcTarget, Float) -> Unit,
    private val onTransmitPeerVmc: (VmcEventPacket) -> Unit
) {
    private val _state = MutableStateFlow(VmcEngineState())
    val state: StateFlow<VmcEngineState> = _state.asStateFlow()

    fun selectBank(bankIndex: Int) {
        val b = bankIndex.coerceIn(0, 3)
        val cur = _state.value
        val chOffset = (cur.activeChannel64 - 1) % 16
        _state.value = cur.copy(
            selectedBank = b,
            activeChannel64 = (b * 16 + chOffset + 1).coerceIn(1, 64)
        )
    }

    fun selectChannel64(ch64: Int) {
        val c = ch64.coerceIn(1, 64)
        _state.value = _state.value.copy(
            activeChannel64 = c,
            selectedBank = (c - 1) / 16
        )
    }

    fun setRouteMode(mode: VmcRouteMode) {
        _state.value = _state.value.copy(routeMode = mode)
    }

    fun armLearnForRule(ruleId: String?) {
        _state.value = _state.value.copy(
            learnTargetRuleId = ruleId,
            learnNewTarget = null,
            lastEventSummary = if (ruleId != null) "MIDI LEARN ARMED — MOVE ANY CONTROL OR PAD" else "MIDI LEARN CANCELLED"
        )
    }

    fun armLearnNewMapping(target: VmcTarget) {
        _state.value = _state.value.copy(
            learnTargetRuleId = null,
            learnNewTarget = target,
            lastEventSummary = "MIDI LEARN NEW RULE -> ${target.label}"
        )
    }

    fun upsertMapping(rule: VmcMappingRule) {
        val existing = _state.value.mappings.toMutableList()
        val idx = existing.indexOfFirst { it.id == rule.id }
        if (idx >= 0) existing[idx] = rule else existing.add(rule)
        _state.value = _state.value.copy(mappings = existing)
    }

    fun removeMapping(ruleId: String) {
        _state.value = _state.value.copy(
            mappings = _state.value.mappings.filterNot { it.id == ruleId }
        )
    }

    fun replaceMappings(rules: List<VmcMappingRule>) {
        if (rules.isNotEmpty()) {
            _state.value = _state.value.copy(mappings = rules)
        }
    }

    fun updateHardwareLists(inputs: List<String>, outputs: List<String>) {
        _state.value = _state.value.copy(hardwareInputs = inputs, hardwareOutputs = outputs)
    }

    /**
     * Triggered from the interactive VMC surface (faders, pads, transport buttons).
     * Routes to Peer VMC (`VMC2`), Local Engine, or Both according to active RouteMode.
     */
    fun emitVirtualControl(
        msgType: VmcMsgType,
        number: Int,
        normalized: Float,
        channel64: Int = _state.value.activeChannel64
    ) {
        val ch = channel64.coerceIn(1, 64)
        val norm = normalized.coerceIn(0f, 1f)
        val d2 = (norm * 127f).toInt().coerceIn(0, 127)
        val pkt = VmcEventPacket(ch, msgType, number.coerceIn(0, 127), d2, norm)
        val mode = _state.value.routeMode
        if (mode != VmcRouteMode.LOCAL_ONLY) {
            onTransmitPeerVmc(pkt)
        }
        if (mode != VmcRouteMode.PEER_VMC_ONLY) {
            processIncomingEvent(pkt, fromPeer = false)
        } else {
            appendLog("TX PEER CH$ch ${msgType.label} #$number = $d2")
        }
    }

    /**
     * Handles incoming MIDI from local OS hardware OR remote Peer VMC (`VMC2`).
     */
    fun processIncomingEvent(pkt: VmcEventPacket, fromPeer: Boolean) {
        val cur = _state.value

        // Check MIDI Learn first
        if (cur.learnTargetRuleId != null) {
            val updated = cur.mappings.map { r ->
                if (r.id == cur.learnTargetRuleId) {
                    r.copy(channel64 = pkt.channel64, msgType = pkt.msgType, number = pkt.data1)
                } else {
                    r
                }
            }
            _state.value = cur.copy(
                mappings = updated,
                learnTargetRuleId = null,
                lastEventSummary = "LEARNED CH${pkt.channel64} ${pkt.msgType.label} #${pkt.data1}"
            )
            appendLog("LEARNED CH${pkt.channel64} ${pkt.msgType.label} #${pkt.data1}")
            return
        }

        if (cur.learnNewTarget != null) {
            val newRule = VmcMappingRule(
                id = "vmc_${System.nanoTime()}",
                channel64 = pkt.channel64,
                msgType = pkt.msgType,
                number = pkt.data1,
                target = cur.learnNewTarget
            )
            _state.value = cur.copy(
                mappings = cur.mappings + newRule,
                learnNewTarget = null,
                lastEventSummary = "MAPPED CH${pkt.channel64} #${pkt.data1} -> ${cur.learnNewTarget.label}"
            )
            appendLog("NEW MAP CH${pkt.channel64} #${pkt.data1} -> ${cur.learnNewTarget.label}")
            return
        }

        val matching = cur.mappings.filter { rule ->
            (rule.channel64 == 0 || rule.channel64 == pkt.channel64) &&
                rule.msgType == pkt.msgType &&
                (pkt.msgType == VmcMsgType.PITCH_BEND ||
                    pkt.msgType == VmcMsgType.TRANSPORT_START ||
                    pkt.msgType == VmcMsgType.TRANSPORT_STOP ||
                    rule.number == pkt.data1)
        }

        val prefix = if (fromPeer) "PEER RX" else "VMC"
        if (matching.isEmpty()) {
            appendLog("$prefix CH${pkt.channel64} ${pkt.msgType.label} #${pkt.data1} (${pkt.data2}) [UNMAPPED]")
        } else {
            matching.forEach { rule ->
                if (rule.routeMode != VmcRouteMode.PEER_VMC_ONLY) {
                    onExecuteTarget(rule.target, pkt.normalized)
                }
                appendLog("$prefix CH${pkt.channel64} #${pkt.data1} -> ${rule.target.label}")
            }
        }
    }

    private fun appendLog(entry: String) {
        val nextLog = (listOf(entry) + _state.value.recentLog).take(14)
        _state.value = _state.value.copy(
            lastEventSummary = entry,
            recentLog = nextLog
        )
    }
}
