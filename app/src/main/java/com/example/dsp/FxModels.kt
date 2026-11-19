package com.example.dsp

data class NoiseRemovalParams(
    val enabled: Boolean = false,
    val thresholdDb: Float = -38f,
    val reductionDb: Float = 24f,
    val highPass80Hz: Boolean = true
)

data class PitchSpeedParams(
    val enabled: Boolean = false,
    val pitchSemitones: Float = 0f,
    val speedFactor: Float = 1.0f,
    val vinylLink: Boolean = false
)

data class GainParams(
    val enabled: Boolean = false,
    val gainDb: Float = 0f,
    val normalize: Boolean = false,
    val softLimiter: Boolean = true
)

data class OverdriveParams(
    val enabled: Boolean = false,
    val drive: Float = 4.0f,
    val toneHz: Float = 4200f,
    val mix: Float = 0.65f
)

data class WahParams(
    val enabled: Boolean = false,
    val rateHz: Float = 1.6f,
    val centerFreqHz: Float = 950f,
    val resonanceQ: Float = 3.5f,
    val envSensitivity: Float = 0.6f,
    val mix: Float = 0.85f
)

data class PhaserParams(
    val enabled: Boolean = false,
    val rateHz: Float = 0.8f,
    val depth: Float = 0.75f,
    val feedback: Float = 0.55f,
    val mix: Float = 0.6f
)

data class FlangerParams(
    val enabled: Boolean = false,
    val rateHz: Float = 0.6f,
    val depthMs: Float = 3.0f,
    val feedback: Float = 0.5f,
    val mix: Float = 0.55f
)

data class ChorusParams(
    val enabled: Boolean = false,
    val rateHz: Float = 1.1f,
    val depthMs: Float = 6.5f,
    val mix: Float = 0.5f
)

data class RotaryParams(
    val enabled: Boolean = false,
    val rateHz: Float = 3.8f,
    val depth: Float = 0.75f,
    val hornBalance: Float = 0.6f,
    val mix: Float = 0.8f
)

data class EchoParams(
    val enabled: Boolean = false,
    val delayMs: Float = 320f,
    val feedback: Float = 0.45f,
    val damping: Float = 0.4f,
    val mix: Float = 0.45f
)

data class DelayParams(
    val enabled: Boolean = false,
    val delayMs: Float = 240f,
    val feedback: Float = 0.4f,
    val stereoSpread: Float = 0.5f,
    val mix: Float = 0.4f
)

data class ReverbParams(
    val enabled: Boolean = false,
    val roomSize: Float = 0.65f,
    val damping: Float = 0.35f,
    val preDelayMs: Float = 18f,
    val mix: Float = 0.35f
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
    VINYL_CRUNCH("LO-FI VINYL")
}

object FxPresets {
    fun build(preset: FxPreset): FxRackState = when (preset) {
        FxPreset.BYPASS_ALL -> FxRackState()
        FxPreset.CLEAN_VOCAL -> FxRackState(
            noiseRemoval = NoiseRemovalParams(enabled = true, thresholdDb = -36f, reductionDb = 28f, highPass80Hz = true),
            gain = GainParams(enabled = true, gainDb = 3f, normalize = true, softLimiter = true),
            reverb = ReverbParams(enabled = true, roomSize = 0.42f, damping = 0.45f, preDelayMs = 15f, mix = 0.22f)
        )
        FxPreset.DUB_ECHO -> FxRackState(
            noiseRemoval = NoiseRemovalParams(enabled = true, thresholdDb = -42f, reductionDb = 20f, highPass80Hz = true),
            echo = EchoParams(enabled = true, delayMs = 375f, feedback = 0.62f, damping = 0.55f, mix = 0.55f),
            reverb = ReverbParams(enabled = true, roomSize = 0.78f, damping = 0.35f, preDelayMs = 24f, mix = 0.38f)
        )
        FxPreset.ACID_WAH -> FxRackState(
            overdrive = OverdriveParams(enabled = true, drive = 6.5f, toneHz = 3800f, mix = 0.6f),
            wah = WahParams(enabled = true, rateHz = 2.4f, centerFreqHz = 1050f, resonanceQ = 5.2f, envSensitivity = 0.75f, mix = 0.9f),
            delay = DelayParams(enabled = true, delayMs = 190f, feedback = 0.42f, stereoSpread = 0.65f, mix = 0.35f)
        )
        FxPreset.LESLIE_CLUB -> FxRackState(
            chorus = ChorusParams(enabled = true, rateHz = 1.2f, depthMs = 7.0f, mix = 0.45f),
            rotarySpeaker = RotaryParams(enabled = true, rateHz = 5.2f, depth = 0.85f, hornBalance = 0.65f, mix = 0.85f),
            reverb = ReverbParams(enabled = true, roomSize = 0.68f, damping = 0.3f, preDelayMs = 18f, mix = 0.32f)
        )
        FxPreset.VINYL_CRUNCH -> FxRackState(
            pitchSpeed = PitchSpeedParams(enabled = true, pitchSemitones = -2f, speedFactor = 0.92f, vinylLink = true),
            overdrive = OverdriveParams(enabled = true, drive = 3.8f, toneHz = 2600f, mix = 0.55f),
            flanger = FlangerParams(enabled = true, rateHz = 0.45f, depthMs = 2.5f, feedback = 0.35f, mix = 0.35f)
        )
    }
}
