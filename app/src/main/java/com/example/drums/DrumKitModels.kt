package com.example.drums

const val MAX_MAIN_KIT_PIECES = 7

enum class DrumKitStyle(val label: String, val shortTag: String) {
    ACOUSTIC("Non-Electric (Acoustic)", "ACOUSTIC"),
    ELECTRIC("Electric (Synth E-Kit)", "ELECTRIC")
}

enum class DrumPieceCategory(
    val label: String,
    val supportsDoubleBeater: Boolean = false
) {
    KICK("Kick"),
    DOUBLE_PEDAL("Double Pedal", supportsDoubleBeater = true),
    SNARE("Snare"),
    HIHAT("Hi-Hat"),
    RACK_TOM("Rack Tom"),
    FLOOR_TOM("Floor Tom"),
    CRASH("Crash"),
    RIDE("Ride"),
    FX_CYMBAL("Splash / China")
}

data class DrumLibraryComponent(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val style: DrumKitStyle,
    val category: DrumPieceCategory,
    val defaultMidiNote: Int,
    val defaultPitchSemis: Float = 0f,
    val defaultDecayScale: Float = 1.0f,
    val defaultXNorm: Float,
    val defaultYNorm: Float,
    val pairedCounterpartId: String
)

enum class DrumAddonType(
    val label: String,
    val shortTag: String,
    val recommendedCategory: DrumPieceCategory
) {
    COWBELL("Cowbell", "COWBELL", DrumPieceCategory.SNARE),
    TAMBOURINE("Tambourine", "TAMB", DrumPieceCategory.HIHAT),
    MARACA("Maraca", "MARACA", DrumPieceCategory.DOUBLE_PEDAL),
    JAM_BLOCK("Jam Block", "BLOCK", DrumPieceCategory.FLOOR_TOM),
    CHIME_SPLASHER("Bar Chime", "CHIME", DrumPieceCategory.CRASH)
}

enum class BeaterSide(val label: String, val shortTag: String) {
    NONE("Shell/Stand", "HOST"),
    LEFT("Left Beater", "L-BTR"),
    RIGHT("Right Beater", "R-BTR")
}

data class PlacedKitPiece(
    val slotId: String,
    val componentId: String,
    val customLabel: String,
    val style: DrumKitStyle,
    val category: DrumPieceCategory,
    val xNorm: Float,
    val yNorm: Float,
    val rotationDeg: Float = 0f,
    val pitchSemitones: Float = 0f,
    val decayScale: Float = 1.0f,
    val volume: Float = 0.9f,
    val isMuted: Boolean = false,
    val midiNote: Int = 36
)

data class MountedAddonPiece(
    val instanceId: String,
    val addonType: DrumAddonType,
    val style: DrumKitStyle,
    val targetSlotId: String,
    val beaterSide: BeaterSide = BeaterSide.NONE,
    val triggerCoupledWithHost: Boolean = true,
    val pitchSemitones: Float = 0f,
    val volume: Float = 0.78f
)

enum class DrumKitPreset(
    val id: String,
    val label: String,
    val style: DrumKitStyle
) {
    ACOUSTIC_CLUB_5("ac_club_5", "5-Pc Acoustic Club", DrumKitStyle.ACOUSTIC),
    ACOUSTIC_ARENA_7("ac_arena_7", "7-Pc Acoustic Dbl-Pedal", DrumKitStyle.ACOUSTIC),
    ELECTRIC_CLUB_5("el_club_5", "5-Pc 808/909 Electro", DrumKitStyle.ELECTRIC),
    ELECTRIC_CYBER_7("el_cyber_7", "7-Pc Cyber Dbl-Trigger", DrumKitStyle.ELECTRIC)
}

data class CustomDrumKitState(
    val kitStyle: DrumKitStyle = DrumKitStyle.ACOUSTIC,
    val activePresetId: String = DrumKitPreset.ACOUSTIC_ARENA_7.id,
    val mainPieces: List<PlacedKitPiece> = emptyList(),
    val mountedAddons: List<MountedAddonPiece> = emptyList(),
    val arrangeMode: Boolean = false,
    val selectedSlotId: String? = null,
    val isLeftHanded: Boolean = false,
    val targetBeaterForMount: BeaterSide = BeaterSide.LEFT,
    val lastHitSlotId: String? = null,
    val lastHitBeater: BeaterSide = BeaterSide.NONE,
    val lastHitAddonId: String? = null,
    val lastHitSummary: String = "TAP ANY KIT PIECE OR ENABLE ARRANGE MODE TO DRAG LAYOUT",
    val statusError: String? = null
) {
    val mainPieceCount: Int get() = mainPieces.size
    val canAddMainPiece: Boolean get() = mainPieces.size < MAX_MAIN_KIT_PIECES
    val selectedPiece: PlacedKitPiece?
        get() = mainPieces.firstOrNull { it.slotId == selectedSlotId } ?: mainPieces.firstOrNull()
}

object DrumComponentLibrary {

    val catalog: List<DrumLibraryComponent> = listOf(
        // Acoustic (Non-Electric)
        DrumLibraryComponent(
            id = "kick_ac_maple",
            displayName = "22\" Maple Bass Drum",
            shortLabel = "AC KICK",
            style = DrumKitStyle.ACOUSTIC,
            category = DrumPieceCategory.KICK,
            defaultMidiNote = 36,
            defaultXNorm = 0.50f,
            defaultYNorm = 0.72f,
            pairedCounterpartId = "kick_el_808"
        ),
        DrumLibraryComponent(
            id = "dkick_ac_twin",
            displayName = "Double-Pedal Maple Kick",
            shortLabel = "DBL KICK",
            style = DrumKitStyle.ACOUSTIC,
            category = DrumPieceCategory.DOUBLE_PEDAL,
            defaultMidiNote = 35,
            defaultXNorm = 0.50f,
            defaultYNorm = 0.75f,
            pairedCounterpartId = "dkick_el_909"
        ),
        DrumLibraryComponent(
            id = "snare_ac_maple",
            displayName = "14\" Brass/Maple Snare",
            shortLabel = "SNARE",
            style = DrumKitStyle.ACOUSTIC,
            category = DrumPieceCategory.SNARE,
            defaultMidiNote = 38,
            defaultXNorm = 0.36f,
            defaultYNorm = 0.62f,
            pairedCounterpartId = "snare_el_909"
        ),
        DrumLibraryComponent(
            id = "snare_ac_piccolo",
            displayName = "12\" Piccolo Side Snare",
            shortLabel = "PICCOLO",
            style = DrumKitStyle.ACOUSTIC,
            category = DrumPieceCategory.SNARE,
            defaultMidiNote = 40,
            defaultPitchSemis = 3f,
            defaultXNorm = 0.22f,
            defaultYNorm = 0.66f,
            pairedCounterpartId = "snare_el_clap"
        ),
        DrumLibraryComponent(
            id = "hat_ac_bronze",
            displayName = "14\" B20 Bronze Hi-Hat",
            shortLabel = "HI-HAT",
            style = DrumKitStyle.ACOUSTIC,
            category = DrumPieceCategory.HIHAT,
            defaultMidiNote = 42,
            defaultXNorm = 0.20f,
            defaultYNorm = 0.48f,
            pairedCounterpartId = "hat_el_crisp"
        ),
        DrumLibraryComponent(
            id = "tom_hi_ac",
            displayName = "10\" High Rack Tom",
            shortLabel = "HI TOM",
            style = DrumKitStyle.ACOUSTIC,
            category = DrumPieceCategory.RACK_TOM,
            defaultMidiNote = 48,
            defaultXNorm = 0.41f,
            defaultYNorm = 0.43f,
            pairedCounterpartId = "tom_hi_el"
        ),
        DrumLibraryComponent(
            id = "tom_mid_ac",
            displayName = "12\" Mid Rack Tom",
            shortLabel = "MID TOM",
            style = DrumKitStyle.ACOUSTIC,
            category = DrumPieceCategory.RACK_TOM,
            defaultMidiNote = 45,
            defaultPitchSemis = -3f,
            defaultXNorm = 0.59f,
            defaultYNorm = 0.43f,
            pairedCounterpartId = "tom_mid_el"
        ),
        DrumLibraryComponent(
            id = "tom_flr_ac",
            displayName = "16\" Deep Floor Tom",
            shortLabel = "FLR TOM",
            style = DrumKitStyle.ACOUSTIC,
            category = DrumPieceCategory.FLOOR_TOM,
            defaultMidiNote = 43,
            defaultXNorm = 0.71f,
            defaultYNorm = 0.62f,
            pairedCounterpartId = "tom_flr_el"
        ),
        DrumLibraryComponent(
            id = "crash_ac_dark",
            displayName = "18\" Dark Bronze Crash",
            shortLabel = "CRASH",
            style = DrumKitStyle.ACOUSTIC,
            category = DrumPieceCategory.CRASH,
            defaultMidiNote = 49,
            defaultXNorm = 0.27f,
            defaultYNorm = 0.24f,
            pairedCounterpartId = "crash_el_synth"
        ),
        DrumLibraryComponent(
            id = "ride_ac_ping",
            displayName = "20\" Heavy Ping Ride",
            shortLabel = "RIDE",
            style = DrumKitStyle.ACOUSTIC,
            category = DrumPieceCategory.RIDE,
            defaultMidiNote = 51,
            defaultXNorm = 0.74f,
            defaultYNorm = 0.26f,
            pairedCounterpartId = "ride_el_bell"
        ),
        DrumLibraryComponent(
            id = "china_ac_trash",
            displayName = "16\" Trash China FX",
            shortLabel = "CHINA",
            style = DrumKitStyle.ACOUSTIC,
            category = DrumPieceCategory.FX_CYMBAL,
            defaultMidiNote = 52,
            defaultXNorm = 0.84f,
            defaultYNorm = 0.44f,
            pairedCounterpartId = "china_el_cyber"
        ),

        // Electric (Electronic / Synth DSP)
        DrumLibraryComponent(
            id = "kick_el_808",
            displayName = "808 Sub-Boom Kick",
            shortLabel = "808 KICK",
            style = DrumKitStyle.ELECTRIC,
            category = DrumPieceCategory.KICK,
            defaultMidiNote = 36,
            defaultXNorm = 0.50f,
            defaultYNorm = 0.72f,
            pairedCounterpartId = "kick_ac_maple"
        ),
        DrumLibraryComponent(
            id = "dkick_el_909",
            displayName = "Dual-Trigger E-Pedal Kick",
            shortLabel = "E-DBL KICK",
            style = DrumKitStyle.ELECTRIC,
            category = DrumPieceCategory.DOUBLE_PEDAL,
            defaultMidiNote = 35,
            defaultXNorm = 0.50f,
            defaultYNorm = 0.75f,
            pairedCounterpartId = "dkick_ac_twin"
        ),
        DrumLibraryComponent(
            id = "snare_el_909",
            displayName = "909 Punch E-Snare",
            shortLabel = "E-SNARE",
            style = DrumKitStyle.ELECTRIC,
            category = DrumPieceCategory.SNARE,
            defaultMidiNote = 38,
            defaultXNorm = 0.36f,
            defaultYNorm = 0.62f,
            pairedCounterpartId = "snare_ac_maple"
        ),
        DrumLibraryComponent(
            id = "snare_el_clap",
            displayName = "Layered E-Clap Snare",
            shortLabel = "E-CLAP",
            style = DrumKitStyle.ELECTRIC,
            category = DrumPieceCategory.SNARE,
            defaultMidiNote = 39,
            defaultXNorm = 0.22f,
            defaultYNorm = 0.66f,
            pairedCounterpartId = "snare_ac_piccolo"
        ),
        DrumLibraryComponent(
            id = "hat_el_crisp",
            displayName = "Crisp Synth E-Hat",
            shortLabel = "E-HAT",
            style = DrumKitStyle.ELECTRIC,
            category = DrumPieceCategory.HIHAT,
            defaultMidiNote = 42,
            defaultXNorm = 0.20f,
            defaultYNorm = 0.48f,
            pairedCounterpartId = "hat_ac_bronze"
        ),
        DrumLibraryComponent(
            id = "tom_hi_el",
            displayName = "Simmons Pitch E-Tom Hi",
            shortLabel = "E-HI TOM",
            style = DrumKitStyle.ELECTRIC,
            category = DrumPieceCategory.RACK_TOM,
            defaultMidiNote = 48,
            defaultXNorm = 0.41f,
            defaultYNorm = 0.43f,
            pairedCounterpartId = "tom_hi_ac"
        ),
        DrumLibraryComponent(
            id = "tom_mid_el",
            displayName = "Simmons Pitch E-Tom Mid",
            shortLabel = "E-MID TOM",
            style = DrumKitStyle.ELECTRIC,
            category = DrumPieceCategory.RACK_TOM,
            defaultMidiNote = 45,
            defaultPitchSemis = -3f,
            defaultXNorm = 0.59f,
            defaultYNorm = 0.43f,
            pairedCounterpartId = "tom_mid_ac"
        ),
        DrumLibraryComponent(
            id = "tom_flr_el",
            displayName = "Sub E-Floor Tom Pad",
            shortLabel = "E-FLR TOM",
            style = DrumKitStyle.ELECTRIC,
            category = DrumPieceCategory.FLOOR_TOM,
            defaultMidiNote = 43,
            defaultXNorm = 0.71f,
            defaultYNorm = 0.62f,
            pairedCounterpartId = "tom_flr_ac"
        ),
        DrumLibraryComponent(
            id = "crash_el_synth",
            displayName = "Reverse/Noise E-Crash",
            shortLabel = "E-CRASH",
            style = DrumKitStyle.ELECTRIC,
            category = DrumPieceCategory.CRASH,
            defaultMidiNote = 49,
            defaultXNorm = 0.27f,
            defaultYNorm = 0.24f,
            pairedCounterpartId = "crash_ac_dark"
        ),
        DrumLibraryComponent(
            id = "ride_el_bell",
            displayName = "Digital Metallic E-Ride",
            shortLabel = "E-RIDE",
            style = DrumKitStyle.ELECTRIC,
            category = DrumPieceCategory.RIDE,
            defaultMidiNote = 51,
            defaultXNorm = 0.74f,
            defaultYNorm = 0.26f,
            pairedCounterpartId = "ride_ac_ping"
        ),
        DrumLibraryComponent(
            id = "china_el_cyber",
            displayName = "Cyber Zap / E-China",
            shortLabel = "E-ZAP",
            style = DrumKitStyle.ELECTRIC,
            category = DrumPieceCategory.FX_CYMBAL,
            defaultMidiNote = 52,
            defaultXNorm = 0.84f,
            defaultYNorm = 0.44f,
            pairedCounterpartId = "china_ac_trash"
        )
    )

    fun byId(id: String): DrumLibraryComponent? = catalog.firstOrNull { it.id == id }

    fun counterpartForStyle(componentId: String, targetStyle: DrumKitStyle): DrumLibraryComponent? {
        val current = byId(componentId) ?: return null
        if (current.style == targetStyle) return current
        return byId(current.pairedCounterpartId) ?: catalog.firstOrNull {
            it.category == current.category && it.style == targetStyle
        }
    }

    fun forStyle(style: DrumKitStyle?): List<DrumLibraryComponent> =
        if (style == null) catalog else catalog.filter { it.style == style }
}
