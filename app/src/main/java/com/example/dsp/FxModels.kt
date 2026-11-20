package com.example.dsp

enum class WahMode(val label: String) {
    AUTO_ENV("AUTO ENV"),
    LFO_SWEEP("LFO SWEEP"),
    MANUAL_PEDAL("PEDAL")
}

enum class DelayNoteDivision(val label: String, val quarterMultiplier: Float) {
    FREE_MS("FREE", 1f),
    QUARTER("1/4", 1f),
    DOTTED_EIGHTH("1/8D", 0.75f),
    EIGHTH("1/8", 0.5f),
    SIXTEENTH("1/16", 0.25f)
}

enum class LiveSessionMode(
    val title: String,
    val subtitle: String,
    val accentTag: String
) {
    STAGE_DJ(
        title = "STAGE DJ",
        subtitle = "Live Club Performance · Stompbox FX + Looper + UDP/TCP Stage Feed",
        accentTag = "STAGE"
    ),
    LIVE_REC(
        title = "LIVE REC",
        subtitle = "Live Field / Booth Capture · TAC Mic + Gate/Limiter + Clean .WAV",
        accentTag = "RECORD"
    ),
    STUDIO_RELEASE(
        title = "STUDIO RELEASE",
        subtitle = "Collab Master Release · Timeline Mixdown + Mastering + MP3/WAV + Net TX",
        accentTag = "RELEASE"
    )
}

data class StompboxMasterState(
    val engaged: Boolean = true,
    val dynamicsTrimDb: Float = 0f,
    val toneTilt: Float = 0f,
    val modScale: Float = 1.0f,
    val spaceScale: Float = 1.0f,
    val masterWetMix: Float = 1.0f,
    val killDrive: Boolean = false,
    val killMod: Boolean = false,
    val killTime: Boolean = false,
    val xyPadX: Float = 0.5f,
    val xyPadY: Float = 0.5f,
    val liveMode: LiveSessionMode = LiveSessionMode.STAGE_DJ
)

data class NoiseRemovalParams(
    val enabled: Boolean = false,
    val thresholdDb: Float = -38f,
    val reductionDb: Float = 24f,
    val highPass80Hz: Boolean = true,
    val highPassFreqHz: Float = 80f,
    val attackMs: Float = 3f,
    val releaseMs: Float = 65f,
    val kneeWidthDb: Float = 6f
)

data class PitchSpeedParams(
    val enabled: Boolean = false,
    val pitchSemitones: Float = 0f,
    val speedFactor: Float = 1.0f,
    val vinylLink: Boolean = false,
    val fineCents: Float = 0f,
    val grainSizeMs: Float = 32f
)

data class GainParams(
    val enabled: Boolean = false,
    val gainDb: Float = 0f,
    val normalize: Boolean = false,
    val softLimiter: Boolean = true,
    val limiterCeilingDb: Float = -0.5f,
    val compressionRatio: Float = 1.5f,
    val stereoWidth: Float = 1.0f
)

data class OverdriveParams(
    val enabled: Boolean = false,
    val drive: Float = 4.0f,
    val toneHz: Float = 4200f,
    val mix: Float = 0.65f,
    val asymmetry: Float = 0.25f,
    val preHighPassHz: Float = 120f
)

data class WahParams(
    val enabled: Boolean = false,
    val rateHz: Float = 1.6f,
    val centerFreqHz: Float = 950f,
    val resonanceQ: Float = 3.5f,
    val envSensitivity: Float = 0.6f,
    val mix: Float = 0.85f,
    val mode: WahMode = WahMode.AUTO_ENV,
    val manualPedalPos: Float = 0.5f
)

data class PhaserParams(
    val enabled: Boolean = false,
    val rateHz: Float = 0.8f,
    val depth: Float = 0.75f,
    val feedback: Float = 0.55f,
    val mix: Float = 0.6f,
    val stages: Int = 4,
    val centerFreqHz: Float = 900f,
    val stereoPhaseOffset: Float = 0.35f
)

data class FlangerParams(
    val enabled: Boolean = false,
    val rateHz: Float = 0.6f,
    val depthMs: Float = 3.0f,
    val feedback: Float = 0.5f,
    val mix: Float = 0.55f,
    val manualDelayMs: Float = 0.8f,
    val stereoSpread: Float = 0.4f
)

data class ChorusParams(
    val enabled: Boolean = false,
    val rateHz: Float = 1.1f,
    val depthMs: Float = 6.5f,
    val mix: Float = 0.5f,
    val voices: Int = 3,
    val baseDelayMs: Float = 14f,
    val highCutHz: Float = 7500f
)

data class RotaryParams(
    val enabled: Boolean = false,
    val rateHz: Float = 3.8f,
    val depth: Float = 0.75f,
    val hornBalance: Float = 0.6f,
    val mix: Float = 0.8f,
    val fastMode: Boolean = false,
    val brake: Boolean = false,
    val crossoverHz: Float = 800f,
    val cabinetDrive: Float = 0.15f
)

data class EchoParams(
    val enabled: Boolean = false,
    val delayMs: Float = 320f,
    val feedback: Float = 0.45f,
    val damping: Float = 0.4f,
    val mix: Float = 0.45f,
    val wowFlutter: Float = 0.25f,
    val saturation: Float = 0.2f
)

data class DelayParams(
    val enabled: Boolean = false,
    val delayMs: Float = 240f,
    val feedback: Float = 0.4f,
    val stereoSpread: Float = 0.5f,
    val mix: Float = 0.4f,
    val bpmSync: Boolean = false,
    val division: DelayNoteDivision = DelayNoteDivision.FREE_MS,
    val highPassHz: Float = 140f,
    val lowPassHz: Float = 6500f
)

data class ReverbParams(
    val enabled: Boolean = false,
    val roomSize: Float = 0.65f,
    val damping: Float = 0.35f,
    val preDelayMs: Float = 18f,
    val mix: Float = 0.35f,
    val earlyReflections: Float = 0.45f,
    val stereoWidth: Float = 0.75f,
    val lowCutHz: Float = 120f
)

data class FxRackState(
    val masterBypass: Boolean = false,
    val noiseRemoval: NoiseRemovalParams = NoiseRemovalParams(),
    val pitchSpeed: PitchSpeedParams = PitchSpeedParams(),
    val gain: GainParams = GainParams(),
    val overdrive: OverdriveParams = OverdriveParams(),
    val wah: WahParams = WahParams(),
    val phaser: PhaserParams = PhaserParams(),
    val flanger: FlangerParams = FlangerParams(),
    val chorus: ChorusParams = ChorusParams(),
    val rotarySpeaker: RotaryParams = RotaryParams(),
    val echo: EchoParams = EchoParams(),
    val delay: DelayParams = DelayParams(),
    val reverb: ReverbParams = ReverbParams()
) {
    val activeCount: Int
        get() = listOf(
            noiseRemoval.enabled,
            pitchSpeed.enabled,
            gain.enabled,
            overdrive.enabled,
            wah.enabled,
            phaser.enabled,
            flanger.enabled,
            chorus.enabled,
            rotarySpeaker.enabled,
            echo.enabled,
            delay.enabled,
            reverb.enabled
        ).count { it }
}

enum class FxPreset(val label: String) {
    BYPASS_ALL("BYPASS"),
    CLEAN_VOCAL("CLEAN MIC"),
    DUB_ECHO("DUB SIREN"),
    ACID_WAH("ACID WAH"),
    LESLIE_CLUB("ROTARY CLUB"),
    VINYL_CRUNCH("LO-FI VINYL"),
    STUDIO_MASTER("MASTER GLUE")
}

object FxPresets {
    fun build(preset: FxPreset): FxRackState = when (preset) {
        FxPreset.BYPASS_ALL -> FxRackState()
        FxPreset.CLEAN_VOCAL -> FxRackState(
            noiseRemoval = NoiseRemovalParams(
                enabled = true,
                thresholdDb = -36f,
                reductionDb = 28f,
                highPass80Hz = true,
                highPassFreqHz = 95f,
                attackMs = 2.5f,
                releaseMs = 70f
            ),
            gain = GainParams(
                enabled = true,
                gainDb = 3f,
                normalize = true,
                softLimiter = true,
                limiterCeilingDb = -0.5f,
                compressionRatio = 2.2f
            ),
            reverb = ReverbParams(enabled = true, roomSize = 0.42f, damping = 0.45f, preDelayMs = 15f, mix = 0.22f)
        )
        FxPreset.DUB_ECHO -> FxRackState(
            noiseRemoval = NoiseRemovalParams(enabled = true, thresholdDb = -42f, reductionDb = 20f, highPass80Hz = true),
            echo = EchoParams(enabled = true, delayMs = 375f, feedback = 0.62f, damping = 0.55f, mix = 0.55f, wowFlutter = 0.35f, saturation = 0.3f),
            reverb = ReverbParams(enabled = true, roomSize = 0.78f, damping = 0.35f, preDelayMs = 24f, mix = 0.38f, earlyReflections = 0.6f)
        )
        FxPreset.ACID_WAH -> FxRackState(
            overdrive = OverdriveParams(enabled = true, drive = 6.5f, toneHz = 3800f, mix = 0.6f, asymmetry = 0.35f),
            wah = WahParams(enabled = true, rateHz = 2.4f, centerFreqHz = 1050f, resonanceQ = 5.2f, envSensitivity = 0.75f, mix = 0.9f),
            delay = DelayParams(enabled = true, delayMs = 190f, feedback = 0.42f, stereoSpread = 0.65f, mix = 0.35f)
        )
        FxPreset.LESLIE_CLUB -> FxRackState(
            chorus = ChorusParams(enabled = true, rateHz = 1.2f, depthMs = 7.0f, mix = 0.45f, voices = 4),
            rotarySpeaker = RotaryParams(enabled = true, rateHz = 5.2f, depth = 0.85f, hornBalance = 0.65f, mix = 0.85f, fastMode = true),
            reverb = ReverbParams(enabled = true, roomSize = 0.68f, damping = 0.3f, preDelayMs = 18f, mix = 0.32f)
        )
        FxPreset.VINYL_CRUNCH -> FxRackState(
            pitchSpeed = PitchSpeedParams(enabled = true, pitchSemitones = -2f, speedFactor = 0.92f, vinylLink = true),
            overdrive = OverdriveParams(enabled = true, drive = 3.8f, toneHz = 2600f, mix = 0.55f, asymmetry = 0.45f),
            flanger = FlangerParams(enabled = true, rateHz = 0.45f, depthMs = 2.5f, feedback = 0.35f, mix = 0.35f)
        )
        FxPreset.STUDIO_MASTER -> FxRackState(
            noiseRemoval = NoiseRemovalParams(enabled = true, thresholdDb = -48f, reductionDb = 14f, highPass80Hz = true, highPassFreqHz = 38f),
            gain = GainParams(enabled = true, gainDb = 2.5f, normalize = true, softLimiter = true, limiterCeilingDb = -0.3f, compressionRatio = 2.4f, stereoWidth = 1.18f),
            reverb = ReverbParams(enabled = true, roomSize = 0.36f, damping = 0.55f, preDelayMs = 12f, mix = 0.14f, earlyReflections = 0.35f)
        )
    }
}
