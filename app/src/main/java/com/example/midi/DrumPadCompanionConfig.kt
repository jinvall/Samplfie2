package com.example.midi

enum class DrumPadTransportTarget(val label: String, val shortLabel: String) {
    PEER_VMC_AND_USB("PEER VMC + USB MIDI", "PEER+USB"),
    USB_HARDWARE_ONLY("USB / HW MIDI OUT", "USB MIDI"),
    PEER_VMC_ONLY("PEER VMC2 BRIDGE", "PEER VMC"),
    LOCAL_PLUS_MIDI("LOCAL AUDIO + MIDI", "LOCAL+MIDI");

    fun sendsUsbHardwareMidi(): Boolean =
        this == PEER_VMC_AND_USB || this == USB_HARDWARE_ONLY || this == LOCAL_PLUS_MIDI

    fun sendsPeerVmc(): Boolean =
        this == PEER_VMC_AND_USB || this == PEER_VMC_ONLY || this == LOCAL_PLUS_MIDI
}

enum class PadVelocityMode(val label: String, val fixedVel: Int) {
    FULL_127("FIXED 127", 127),
    PUNCHY_110("PUNCH 110", 110),
    GROOVE_95("GROOVE 95", 95),
    GHOST_72("GHOST 72", 72),
    DYNAMIC_ACCENT("ACCENT DYN", 108)
}

enum class PadTriggerMode(val label: String) {
    ONE_SHOT("1-SHOT"),
    GATE_HOLD("GATE HOLD")
}

enum class NoteRepeatDivision(val label: String, val subdivisionsPerQuarter: Int) {
    OFF("OFF", 0),
    EIGHTH("1/8", 2),
    SIXTEENTH("1/16", 4),
    THIRTY_SECOND("1/32", 8)
}

enum class DrumPadBankPreset(val label: String) {
    SAMPLFIE_LIVE_KIT("SAMPLFIE KIT"),
    GM_PERCUSSION_10("GM DRUMS CH10"),
    MPC_BANK_A("MPC BANK A"),
    CHROMATIC_MELODIC("CHROMATIC BASS")
}

enum class PadAccentColor {
    CYAN,
    CORAL,
    AMBER,
    EMERALD,
    VIOLET,
    MAGENTA
}

data class DrumPadSlotConfig(
    val index: Int,
    val label: String,
    val midiNote: Int,
    val channel64Override: Int? = null, // null -> use global VMC/HW channel
    val sequencerLaneId: Int? = 0, // 0..3 -> punches live sequencer lane, null -> MIDI-only
    val accentColor: PadAccentColor = PadAccentColor.CYAN
)

data class DrumPadCompanionConfig(
    val enabled: Boolean = true,
    val transportTarget: DrumPadTransportTarget = DrumPadTransportTarget.LOCAL_PLUS_MIDI,
    val preset: DrumPadBankPreset = DrumPadBankPreset.SAMPLFIE_LIVE_KIT,
    val hardwareMidiChannel16: Int = 10, // GM Percussion standard CH 10
    val vmcChannel64: Int = 33, // Bus 3 Ch 1
    val velocityMode: PadVelocityMode = PadVelocityMode.FULL_127,
    val triggerMode: PadTriggerMode = PadTriggerMode.ONE_SHOT,
    val noteRepeat: NoteRepeatDivision = NoteRepeatDivision.OFF,
    val liveOverdubArmed: Boolean = false,
    val stepRecordWhenStopped: Boolean = true,
    val quantizeToStep: Boolean = true,
    val stepCursor: Int = 0,
    val accentToggle: Boolean = false,
    val sendMidiClock: Boolean = false,
    val localAuditionEnabled: Boolean = true,
    val selectedPadIndex: Int = 0,
    val pads: List<DrumPadSlotConfig> = buildPadsForPreset(DrumPadBankPreset.SAMPLFIE_LIVE_KIT)
) {
    fun resolveHardwareChannel16(pad: DrumPadSlotConfig): Int {
        val ov = pad.channel64Override
        return if (ov != null) ((ov - 1).coerceIn(0, 63) % 16) + 1 else hardwareMidiChannel16.coerceIn(1, 16)
    }

    fun resolveVmcChannel64(pad: DrumPadSlotConfig): Int {
        return (pad.channel64Override ?: vmcChannel64).coerceIn(1, 64)
    }

    fun resolveVelocity(pad: DrumPadSlotConfig, pressure: Float = 1f): Int {
        return when (velocityMode) {
            PadVelocityMode.DYNAMIC_ACCENT -> {
                val base = if (accentToggle || pad.index % 4 == 0) 124 else 88
                (base * pressure.coerceIn(0.3f, 1.15f)).toInt().coerceIn(24, 127)
            }
            else -> velocityMode.fixedVel
        }
    }

    companion object {
        private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun formatMidiNote(note: Int): String {
            val n = note.coerceIn(0, 127)
            val name = NOTE_NAMES[n % 12]
            val oct = (n / 12) - 1
            return "$name$oct"
        }

        fun buildPadsForPreset(preset: DrumPadBankPreset): List<DrumPadSlotConfig> {
            return when (preset) {
                DrumPadBankPreset.SAMPLFIE_LIVE_KIT -> listOf(
                    DrumPadSlotConfig(0, "KICK 808", 36, null, 1, PadAccentColor.CORAL),
                    DrumPadSlotConfig(1, "SNARE 909", 38, null, 2, PadAccentColor.AMBER),
                    DrumPadSlotConfig(2, "CL HAT", 42, null, 3, PadAccentColor.EMERALD),
                    DrumPadSlotConfig(3, "SAMPLE", 40, null, 0, PadAccentColor.CYAN),
                    DrumPadSlotConfig(4, "CLAP 808", 39, null, 2, PadAccentColor.AMBER),
                    DrumPadSlotConfig(5, "OPEN HAT", 46, null, 3, PadAccentColor.EMERALD),
                    DrumPadSlotConfig(6, "LOW TOM", 41, null, 1, PadAccentColor.CORAL),
                    DrumPadSlotConfig(7, "PERC ZAP", 44, null, 0, PadAccentColor.CYAN),
                    DrumPadSlotConfig(8, "RIM SHOT", 37, null, 2, PadAccentColor.VIOLET),
                    DrumPadSlotConfig(9, "MID TOM", 45, null, 1, PadAccentColor.CORAL),
                    DrumPadSlotConfig(10, "CRASH CYM", 49, null, 3, PadAccentColor.MAGENTA),
                    DrumPadSlotConfig(11, "CHOP -3", 48, null, 0, PadAccentColor.VIOLET),
                    DrumPadSlotConfig(12, "SUB BOOM", 35, null, 1, PadAccentColor.CORAL),
                    DrumPadSlotConfig(13, "COWBELL", 56, null, 3, PadAccentColor.AMBER),
                    DrumPadSlotConfig(14, "CHOP +5", 52, null, 0, PadAccentColor.CYAN),
                    DrumPadSlotConfig(15, "CHOP +7", 55, null, 0, PadAccentColor.MAGENTA)
                )

                DrumPadBankPreset.GM_PERCUSSION_10 -> {
                    val gmNames = listOf(
                        36 to "BD KICK", 37 to "SIDESTK", 38 to "SD ACOUST", 39 to "HAND CLAP",
                        40 to "SD ELEC", 41 to "LO TOM 2", 42 to "CL HI-HAT", 43 to "LO TOM 1",
                        44 to "PD HI-HAT", 45 to "MID TOM 2", 46 to "OP HI-HAT", 47 to "MID TOM 1",
                        48 to "HI TOM 2", 49 to "CRASH 1", 50 to "HI TOM 1", 51 to "RIDE CYM"
                    )
                    val lanes = listOf(1, 2, 2, 2, 2, 1, 3, 1, 3, 1, 3, 1, 0, 3, 0, 3)
                    val colors = listOf(
                        PadAccentColor.CORAL, PadAccentColor.AMBER, PadAccentColor.AMBER, PadAccentColor.MAGENTA,
                        PadAccentColor.AMBER, PadAccentColor.CORAL, PadAccentColor.EMERALD, PadAccentColor.CORAL,
                        PadAccentColor.EMERALD, PadAccentColor.VIOLET, PadAccentColor.EMERALD, PadAccentColor.VIOLET,
                        PadAccentColor.CYAN, PadAccentColor.MAGENTA, PadAccentColor.CYAN, PadAccentColor.EMERALD
                    )
                    gmNames.mapIndexed { idx, (note, title) ->
                        DrumPadSlotConfig(idx, title, note, null, lanes[idx], colors[idx])
                    }
                }

                DrumPadBankPreset.MPC_BANK_A -> (0 until 16).map { idx ->
                    val note = 36 + idx
                    val lane = when (idx % 4) {
                        0 -> 1
                        1 -> 2
                        2 -> 3
                        else -> 0
                    }
                    val color = PadAccentColor.entries[idx % PadAccentColor.entries.size]
                    DrumPadSlotConfig(idx, "MPC A${idx + 1}", note, null, lane, color)
                }

                DrumPadBankPreset.CHROMATIC_MELODIC -> (0 until 16).map { idx ->
                    val note = 48 + idx
                    val color = if (note % 12 in setOf(1, 3, 6, 8, 10)) PadAccentColor.VIOLET else PadAccentColor.CYAN
                    DrumPadSlotConfig(idx, "SYNTH ${formatMidiNote(note)}", note, null, 0, color)
                }
            }
        }
    }
}
