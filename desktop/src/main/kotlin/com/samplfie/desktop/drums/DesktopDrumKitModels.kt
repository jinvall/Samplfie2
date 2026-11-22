package com.samplfie.desktop.drums

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
    val lastHitSummary: String = "CLICK KIT PIECE OR SWITCH TO ARRANGE MODE TO DRAG LAYOUT",
    val statusError: String? = null
) {
    val mainPieceCount: Int get() = mainPieces.size
    val canAddMainPiece: Boolean get() = mainPieces.size < MAX_MAIN_KIT_PIECES
    val selectedPiece: PlacedKitPiece?
        get() = mainPieces.firstOrNull { it.slotId == selectedSlotId } ?: mainPieces.firstOrNull()
}

object DesktopDrumComponentLibrary {

    val catalog: List<DrumLibraryComponent> = listOf(
        DrumLibraryComponent("kick_ac_maple", "22\" Maple Bass Drum", "AC KICK", DrumKitStyle.ACOUSTIC, DrumPieceCategory.KICK, 36, 0f, 1.0f, 0.50f, 0.72f, "kick_el_808"),
        DrumLibraryComponent("dkick_ac_twin", "Double-Pedal Maple Kick", "DBL KICK", DrumKitStyle.ACOUSTIC, DrumPieceCategory.DOUBLE_PEDAL, 35, 0f, 1.0f, 0.50f, 0.75f, "dkick_el_909"),
        DrumLibraryComponent("snare_ac_maple", "14\" Brass/Maple Snare", "SNARE", DrumKitStyle.ACOUSTIC, DrumPieceCategory.SNARE, 38, 0f, 1.0f, 0.36f, 0.62f, "snare_el_909"),
        DrumLibraryComponent("snare_ac_piccolo", "12\" Piccolo Side Snare", "PICCOLO", DrumKitStyle.ACOUSTIC, DrumPieceCategory.SNARE, 40, 3f, 1.0f, 0.22f, 0.66f, "snare_el_clap"),
        DrumLibraryComponent("hat_ac_bronze", "14\" B20 Bronze Hi-Hat", "HI-HAT", DrumKitStyle.ACOUSTIC, DrumPieceCategory.HIHAT, 42, 0f, 1.0f, 0.20f, 0.48f, "hat_el_crisp"),
        DrumLibraryComponent("tom_hi_ac", "10\" High Rack Tom", "HI TOM", DrumKitStyle.ACOUSTIC, DrumPieceCategory.RACK_TOM, 48, 0f, 1.0f, 0.41f, 0.43f, "tom_hi_el"),
        DrumLibraryComponent("tom_mid_ac", "12\" Mid Rack Tom", "MID TOM", DrumKitStyle.ACOUSTIC, DrumPieceCategory.RACK_TOM, 45, -3f, 1.0f, 0.59f, 0.43f, "tom_mid_el"),
        DrumLibraryComponent("tom_flr_ac", "16\" Deep Floor Tom", "FLR TOM", DrumKitStyle.ACOUSTIC, DrumPieceCategory.FLOOR_TOM, 43, 0f, 1.0f, 0.71f, 0.62f, "tom_flr_el"),
        DrumLibraryComponent("crash_ac_dark", "18\" Dark Bronze Crash", "CRASH", DrumKitStyle.ACOUSTIC, DrumPieceCategory.CRASH, 49, 0f, 1.0f, 0.27f, 0.24f, "crash_el_synth"),
        DrumLibraryComponent("ride_ac_ping", "20\" Heavy Ping Ride", "RIDE", DrumKitStyle.ACOUSTIC, DrumPieceCategory.RIDE, 51, 0f, 1.0f, 0.74f, 0.26f, "ride_el_bell"),
        DrumLibraryComponent("china_ac_trash", "16\" Trash China FX", "CHINA", DrumKitStyle.ACOUSTIC, DrumPieceCategory.FX_CYMBAL, 52, 0f, 1.0f, 0.84f, 0.44f, "china_el_cyber"),

        DrumLibraryComponent("kick_el_808", "808 Sub-Boom Kick", "808 KICK", DrumKitStyle.ELECTRIC, DrumPieceCategory.KICK, 36, 0f, 1.0f, 0.50f, 0.72f, "kick_ac_maple"),
        DrumLibraryComponent("dkick_el_909", "Dual-Trigger E-Pedal Kick", "E-DBL KICK", DrumKitStyle.ELECTRIC, DrumPieceCategory.DOUBLE_PEDAL, 35, 0f, 1.0f, 0.50f, 0.75f, "dkick_ac_twin"),
        DrumLibraryComponent("snare_el_909", "909 Punch E-Snare", "E-SNARE", DrumKitStyle.ELECTRIC, DrumPieceCategory.SNARE, 38, 0f, 1.0f, 0.36f, 0.62f, "snare_ac_maple"),
        DrumLibraryComponent("snare_el_clap", "Layered E-Clap Snare", "E-CLAP", DrumKitStyle.ELECTRIC, DrumPieceCategory.SNARE, 39, 0f, 1.0f, 0.22f, 0.66f, "snare_ac_piccolo"),
        DrumLibraryComponent("hat_el_crisp", "Crisp Synth E-Hat", "E-HAT", DrumKitStyle.ELECTRIC, DrumPieceCategory.HIHAT, 42, 0f, 1.0f, 0.20f, 0.48f, "hat_ac_bronze"),
        DrumLibraryComponent("tom_hi_el", "Simmons Pitch E-Tom Hi", "E-HI TOM", DrumKitStyle.ELECTRIC, DrumPieceCategory.RACK_TOM, 48, 0f, 1.0f, 0.41f, 0.43f, "tom_hi_ac"),
        DrumLibraryComponent("tom_mid_el", "Simmons Pitch E-Tom Mid", "E-MID TOM", DrumKitStyle.ELECTRIC, DrumPieceCategory.RACK_TOM, 45, -3f, 1.0f, 0.59f, 0.43f, "tom_mid_ac"),
        DrumLibraryComponent("tom_flr_el", "Sub E-Floor Tom Pad", "E-FLR TOM", DrumKitStyle.ELECTRIC, DrumPieceCategory.FLOOR_TOM, 43, 0f, 1.0f, 0.71f, 0.62f, "tom_flr_ac"),
        DrumLibraryComponent("crash_el_synth", "Reverse/Noise E-Crash", "E-CRASH", DrumKitStyle.ELECTRIC, DrumPieceCategory.CRASH, 49, 0f, 1.0f, 0.27f, 0.24f, "crash_ac_dark"),
        DrumLibraryComponent("ride_el_bell", "Digital Metallic E-Ride", "E-RIDE", DrumKitStyle.ELECTRIC, DrumPieceCategory.RIDE, 51, 0f, 1.0f, 0.74f, 0.26f, "ride_ac_ping"),
        DrumLibraryComponent("china_el_cyber", "Cyber Zap / E-China", "E-ZAP", DrumKitStyle.ELECTRIC, DrumPieceCategory.FX_CYMBAL, 52, 0f, 1.0f, 0.84f, 0.44f, "china_ac_trash")
    )

    fun byId(id: String): DrumLibraryComponent? = catalog.firstOrNull { it.id == id }

    fun counterpartForStyle(componentId: String, targetStyle: DrumKitStyle): DrumLibraryComponent? {
        val cur = byId(componentId) ?: return null
        if (cur.style == targetStyle) return cur
        return byId(cur.pairedCounterpartId) ?: catalog.firstOrNull {
            it.category == cur.category && it.style == targetStyle
        }
    }

    fun forStyle(style: DrumKitStyle?): List<DrumLibraryComponent> =
        if (style == null) catalog else catalog.filter { it.style == style }
}
