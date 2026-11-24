package com.example.stems

import com.example.audio.AudioBuffer
import kotlin.math.ceil
import kotlin.math.max

enum class StemCategory {
    PRIMARY,
    PRO_SUB,
    DRUM_KIT
}

/**
 * Supported stem identifiers in the Disband API catalog (`https://api.disband.io/v1`).
 */
enum class DisbandStem(
    val apiId: String,
    val label: String,
    val shortTag: String,
    val isCoreAllStem: Boolean,
    val category: StemCategory
) {
    VOCALS("vocals", "Vocals", "VOX", true, StemCategory.PRIMARY),
    INSTRUMENTAL("instrumental", "Instrumental", "INST", false, StemCategory.PRIMARY),
    DRUMS("drums", "Drums", "DRM", true, StemCategory.PRIMARY),
    BASS("bass", "Bass", "BAS", true, StemCategory.PRIMARY),
    GUITAR("guitar", "Guitar", "GTR", true, StemCategory.PRIMARY),
    PIANO("piano", "Piano", "PNO", true, StemCategory.PRIMARY),
    OTHER("other", "Other", "OTH", true, StemCategory.PRIMARY),
    LEAD_VOCALS("lead_vocals", "Lead Vox", "LVOX", false, StemCategory.PRO_SUB),
    BACKING_VOCALS("backing_vocals", "Backing Vox", "BVOX", false, StemCategory.PRO_SUB),
    KICK("kick", "Kick", "KCK", false, StemCategory.DRUM_KIT),
    SNARE("snare", "Snare", "SNR", false, StemCategory.DRUM_KIT),
    TOMS("toms", "Toms", "TOM", false, StemCategory.DRUM_KIT),
    HIHAT("hihat", "Hi-Hat", "HAT", false, StemCategory.DRUM_KIT);

    companion object {
        val ALL_STEMS_DEFAULT: List<DisbandStem> = entries.filter { it.isCoreAllStem }
    }
}

enum class StemPreset(val label: String, val stems: Set<DisbandStem>) {
    VOCALS_ONLY("Vocals Only", setOf(DisbandStem.VOCALS)),
    VOCALS_DRUMS("Vocals + Drums", setOf(DisbandStem.VOCALS, DisbandStem.DRUMS)),
    VOCALS_INST("Vocals + Inst", setOf(DisbandStem.VOCALS, DisbandStem.INSTRUMENTAL)),
    DRUMS_BASS("Drums + Bass", setOf(DisbandStem.DRUMS, DisbandStem.BASS)),
    FOUR_STEM_BAND("Band (Vox/Drm/Bas/Gtr)", setOf(DisbandStem.VOCALS, DisbandStem.DRUMS, DisbandStem.BASS, DisbandStem.GUITAR)),
    UNSPECIFIED_ALL("All Stems (Clear)", emptySet())
}

data class SeparatedStemTrack(
    val stemId: String,
    val label: String,
    val format: String,
    val buffer: AudioBuffer,
    val sizeBytes: Int,
    val creditCharge: Int = 0
)

data class StemSeparationUiState(
    val selectedStems: Set<DisbandStem> = emptySet(),
    val useSelectionOnly: Boolean = false,
    val showAllStemsConfirmation: Boolean = false,
    val pendingAllStemsEstimatedCredits: Int = 0,
    val pendingAudioDurationSec: Float = 0f,
    val isRunning: Boolean = false,
    val activeJobId: String? = null,
    val jobStatusLabel: String = "IDLE",
    val lastCreditCharge: Int? = null,
    val separatedStems: List<SeparatedStemTrack> = emptyList(),
    val errorMessage: String? = null,
    val errorPayloadDetail: String? = null,
    val lastToastMessage: String? = null,
    val statusMessage: String = "PICK SPECIFIC STEMS TO SEPARATE, OR LEAVE EMPTY FOR ALL-STEMS CONFIRMATION"
) {
    companion object {
        fun estimateAllStemsCredits(durationSec: Float): Int {
            val mins = max(1, ceil(durationSec / 60.0).toInt())
            return mins * 60
        }
    }
}
