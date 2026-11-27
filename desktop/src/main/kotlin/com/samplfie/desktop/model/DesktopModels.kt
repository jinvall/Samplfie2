package com.samplfie.desktop.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class DesktopStation(val shortLabel: String, val fullTitle: String, val badge: String) {
    BOOTH("BOOTH", "PRODUCTION BOOTH", "HUB"),
    EDITOR("DECK", "SIGNAL & WAV DECK EDITOR", "AUDIO"),
    SAMPLES("SAMPLES", "LOOPER & SAMPLE REFINER", "LOOPS"),
    INSTRUMENTS("INST", "16-STEP SEQ & MIDI", "SYNTH"),
    TRACKS("TRACKS", "SYNCED TRACK TIMELINE", "SYNC"),
    FX_STOMP("FX RACK", "13 FX + 3 FILTERS RACK", "DSP"),
    LIVE("LIVE", "LIVE STOMPBOX & RELEASE", "STAGE"),
    TOOLS("TOOLS", "PRO TOOLS & PLUGINS", "PRO"),
    TUNER("TUNER", "CHROMATIC & STROBE TUNER", "PITCH"),
    VMC("VMC 64", "VIRTUAL MIDI CONTROLLER (64CH)", "MIDI"),
    NET_LINK("NET SYNC", "SMP2 / HANDOFF PEER BRIDGE", "LINK")
}

enum class VisualizerMode(val label: String) {
    WAVE("WAVE"),
    FFT("FFT"),
    SPEC("SPEC"),
    W_SPEC("W+SPEC"),
    DUAL("DUAL")
}

enum class SignalWaveformType(val label: String) {
    SINE("SINE"),
    SQUARE("SQUARE"),
    SAWTOOTH("SAW"),
    TRIANGLE("TRIANGLE"),
    CHIRP("LOG CHIRP"),
    NOISE("WHITE NOISE")
}

enum class NetworkProtocol(val label: String) {
    UDP("UDP"),
    TCP("TCP")
}

enum class DesktopLiveMode(val title: String, val subtitle: String, val accentTag: String) {
    STAGE_DJ("STAGE DJ", "Live club & festival performance console", "LIVE STAGE"),
    LIVE_REC("LIVE REC", "Low-latency field & booth tracking", "LIVE CAPTURE"),
    STUDIO_RELEASE("STUDIO RELEASE", "Final collaborative audio master & bounce", "RELEASE")
}

enum class TuningPreset(val label: String, val notes: List<Pair<String, Double>>) {
    CHROMATIC(
        "Chromatic",
        listOf("C4" to 261.63, "D4" to 293.66, "E4" to 329.63, "F4" to 349.23, "G4" to 392.00, "A4" to 440.00, "B4" to 493.88)
    ),
    GUITAR_STD(
        "Guitar Std",
        listOf("E2" to 82.41, "A2" to 110.00, "D3" to 146.83, "G3" to 196.00, "B3" to 246.94, "E4" to 329.63)
    ),
    BASS_4(
        "Bass 4-Str",
        listOf("E1" to 41.20, "A1" to 55.00, "D2" to 73.42, "G2" to 98.00)
    ),
    DROP_D(
        "Drop D",
        listOf("D2" to 73.42, "A2" to 110.00, "D3" to 146.83, "G3" to 196.00, "B3" to 246.94, "E4" to 329.63)
    ),
    UKULELE(
        "Ukulele GCEA",
        listOf("G4" to 392.00, "C4" to 261.63, "E4" to 329.63, "A4" to 440.00)
    )
}

enum class TunerInputSource(val label: String) {
    LIVE_MIC("LINUX MIC"),
    DECK_BUFFER("EDITOR SIGNAL"),
    SMP2_STREAM("SMP2 STREAM")
}

data class TunerReading(
    val detected: Boolean = false,
    val frequencyHz: Double = 0.0,
    val noteName: String = "--",
    val octave: Int = 0,
    val centsOffset: Double = 0.0,
    val targetFrequencyHz: Double = 0.0,
    val confidence: Float = 0f
) {
    val fullNote: String get() = if (detected) "$noteName$octave" else "--"
    val isInTune: Boolean get() = detected && abs(centsOffset) <= 3.0
}

data class DesktopRigConfig(
    val sampleRate: Int = 44100,
    val channels: Int = 1,
    val bufferSize: Int = 1024,
    val protocol: NetworkProtocol = NetworkProtocol.UDP,
    val bindPort: Int = 9000,
    val txHost: String = "127.0.0.1",
    val txPort: Int = 9001,
    val mp3BitrateKbps: Int = 192,
    val a4ReferenceHz: Double = 440.0
)

data class DesktopWorkspaceConfig(
    val nodeName: String = "LINUX DESKTOP RIG",
    val visibleStations: List<DesktopStation> = DesktopStation.entries.toList(),
    val radialWheelStations: List<DesktopStation> = listOf(
        DesktopStation.EDITOR,
        DesktopStation.SAMPLES,
        DesktopStation.INSTRUMENTS,
        DesktopStation.TUNER,
        DesktopStation.LIVE,
        DesktopStation.TOOLS,
        DesktopStation.TRACKS,
        DesktopStation.FX_STOMP
    ),
    val autoResumeOnHandoff: Boolean = true,
    val autoAcceptIncomingHandoff: Boolean = true,
    val syncWorkspaceOnHandoff: Boolean = true
)

data class DesktopAudioBuffer(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int,
    val name: String = "Signal",
    val sampleFormat: DesktopSampleFormat = DesktopSampleFormat.PCM_16
) {
    val frameCount: Int get() = if (channels <= 0) 0 else samples.size / channels
    val durationSeconds: Float get() = if (sampleRate <= 0) 0f else frameCount.toFloat() / sampleRate

    fun monoMixed(): FloatArray {
        if (channels <= 1) return samples.copyOf()
        val frames = frameCount
        return FloatArray(frames) { f ->
            var sum = 0f
            for (c in 0 until channels) sum += samples[f * channels + c]
            (sum / channels).coerceIn(-1f, 1f)
        }
    }

    fun peakLevel(): Float {
        var peak = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        return peak
    }

    fun rmsLevel(): Float {
        if (samples.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in samples) sumSq += s * s
        return sqrt(sumSq / samples.size).toFloat()
    }

    fun extractSelection(startFrac: Float, endFrac: Float): DesktopAudioBuffer {
        if (frameCount == 0) return this
        val lo = min(startFrac, endFrac).coerceIn(0f, 1f)
        val hi = max(startFrac, endFrac).coerceIn(0f, 1f)
        val startFrame = (lo * frameCount).toInt().coerceIn(0, max(0, frameCount - 1))
        val endFrame = (hi * frameCount).toInt().coerceIn(startFrame + 1, frameCount)
        val sliced = samples.copyOfRange(startFrame * channels, endFrame * channels)
        return copy(samples = sliced)
    }

    fun snapFractionToZeroCrossing(fraction: Float, windowFrames: Int = 256): Float {
        if (frameCount <= 2) return fraction.coerceIn(0f, 1f)
        val target = (fraction.coerceIn(0f, 1f) * (frameCount - 1)).toInt()
        val mono = monoMixed()
        val searchLo = (target - windowFrames).coerceAtLeast(1)
        val searchHi = (target + windowFrames).coerceAtMost(frameCount - 1)
        var bestFrame = target
        var bestDist = Int.MAX_VALUE
        for (f in searchLo..searchHi) {
            val prev = mono[f - 1]
            val curr = mono[f]
            if ((prev <= 0f && curr >= 0f) || (prev >= 0f && curr <= 0f)) {
                val dist = abs(f - target)
                if (dist < bestDist) {
                    bestDist = dist
                    bestFrame = f
                }
            }
        }
        return bestFrame.toFloat() / (frameCount - 1).toFloat()
    }

    fun applyLinearFades(fadeInMs: Float, fadeOutMs: Float): DesktopAudioBuffer {
        if (frameCount == 0) return this
        val out = samples.copyOf()
        val inFrames = ((fadeInMs / 1000f) * sampleRate).toInt().coerceIn(0, frameCount / 2)
        val outFrames = ((fadeOutMs / 1000f) * sampleRate).toInt().coerceIn(0, frameCount / 2)
        for (f in 0 until inFrames) {
            val gain = f.toFloat() / inFrames.coerceAtLeast(1)
            for (c in 0 until channels) out[f * channels + c] *= gain
        }
        for (i in 0 until outFrames) {
            val f = frameCount - 1 - i
            val gain = i.toFloat() / outFrames.coerceAtLeast(1)
            for (c in 0 until channels) out[f * channels + c] *= gain
        }
        return copy(samples = out)
    }

    fun reversed(): DesktopAudioBuffer {
        if (frameCount <= 1) return this
        val out = FloatArray(samples.size)
        for (f in 0 until frameCount) {
            val srcF = frameCount - 1 - f
            for (c in 0 until channels) {
                out[f * channels + c] = samples[srcF * channels + c]
            }
        }
        return copy(samples = out)
    }

    fun removeDcOffset(): DesktopAudioBuffer {
        if (frameCount == 0) return this
        val out = samples.copyOf()
        for (c in 0 until channels) {
            var sum = 0.0
            for (f in 0 until frameCount) sum += out[f * channels + c]
            val mean = (sum / frameCount).toFloat()
            for (f in 0 until frameCount) {
                out[f * channels + c] = (out[f * channels + c] - mean).coerceIn(-1f, 1f)
            }
        }
        return copy(samples = out)
    }

    fun peakNormalized(targetPeak: Float = 0.96f): DesktopAudioBuffer {
        val peak = peakLevel()
        if (peak <= 1e-5f) return this
        val scale = targetPeak.coerceIn(0.1f, 1f) / peak
        val out = FloatArray(samples.size) { i -> (samples[i] * scale).coerceIn(-1f, 1f) }
        return copy(samples = out)
    }

    fun convertChannels(targetChannels: Int): DesktopAudioBuffer {
        val dst = targetChannels.coerceIn(1, 2)
        if (dst == channels) return this
        val frames = frameCount
        return if (channels == 1 && dst == 2) {
            val out = FloatArray(frames * 2)
            for (f in 0 until frames) {
                out[f * 2] = samples[f]
                out[f * 2 + 1] = samples[f]
            }
            copy(samples = out, channels = 2)
        } else {
            copy(samples = monoMixed(), channels = 1)
        }
    }

    fun resample(targetRate: Int): DesktopAudioBuffer {
        if (targetRate == sampleRate || frameCount < 2) return copy(sampleRate = targetRate)
        val ratio = sampleRate.toDouble() / targetRate.toDouble()
        val outFrames = max(1, (frameCount / ratio).toInt())
        val out = FloatArray(outFrames * channels)
        for (of in 0 until outFrames) {
            val pos = of * ratio
            val i0 = pos.toInt().coerceIn(0, frameCount - 1)
            val i1 = (i0 + 1).coerceAtMost(frameCount - 1)
            val frac = (pos - i0).toFloat()
            for (c in 0 until channels) {
                val a = samples[i0 * channels + c]
                val b = samples[i1 * channels + c]
                out[of * channels + c] = (a + (b - a) * frac).coerceIn(-1f, 1f)
            }
        }
        return copy(samples = out, sampleRate = targetRate)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DesktopAudioBuffer) return false
        return sampleRate == other.sampleRate &&
            channels == other.channels &&
            name == other.name &&
            sampleFormat == other.sampleFormat &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var res = samples.contentHashCode()
        res = 31 * res + sampleRate
        res = 31 * res + channels
        res = 31 * res + name.hashCode()
        res = 31 * res + sampleFormat.hashCode()
        return res
    }
}

enum class DesktopWahMode(val label: String) {
    AUTO_ENV("AUTO ENV"),
    LFO_SWEEP("LFO SWEEP"),
    MANUAL_PEDAL("PEDAL")
}

enum class DesktopDelayDivision(val label: String, val quarterMultiplier: Float) {
    FREE_MS("FREE", 1f),
    QUARTER("1/4", 1f),
    DOTTED_EIGHTH("1/8D", 0.75f),
    EIGHTH("1/8", 0.5f),
    SIXTEENTH("1/16", 0.25f)
}

enum class DesktopDistortionCurve(val label: String) {
    SOFT_CLIP("SOFT CLIP"),
    HARD_CLIP("HARD CLIP"),
    TUBE_ASYM("TUBE ASYM"),
    WAVEFOLD("WAVEFOLD"),
    BIT_CRUSH("CRUSH"),
    FUZZ("SILICON FUZZ")
}

enum class DesktopBiquadFilterMode(val label: String) {
    LOW_PASS("LOW PASS"),
    HIGH_PASS("HIGH PASS"),
    BAND_PASS("BAND PASS"),
    NOTCH("NOTCH"),
    PEAKING("PEAK / FORM"),
    FORMANT("VOWEL FORM")
}

data class DesktopFxState(
    val masterBypass: Boolean = false,
    // 1. Noise Gate / Cleanup
    val noiseGateEnabled: Boolean = false,
    val noiseThresholdDb: Float = -46f,
    val noiseReductionDb: Float = 24f,
    val noiseHighPassEnabled: Boolean = true,
    val noiseHighPassHz: Float = 80f,
    val noiseAttackMs: Float = 3f,
    val noiseReleaseMs: Float = 65f,
    val noiseKneeDb: Float = 6f,
    val noiseGateLevel: Float = 1.0f,
    // 2. Pitch & Time
    val pitchEnabled: Boolean = false,
    val pitchSemitones: Float = 0f,
    val pitchFineCents: Float = 0f,
    val speedFactor: Float = 1f,
    val pitchVinylLink: Boolean = false,
    val pitchGrainMs: Float = 32f,
    val pitchLevel: Float = 1.0f,
    // 3. Gain / Comp / Limiter
    val gainEnabled: Boolean = false,
    val gainDb: Float = 0f,
    val normalize: Boolean = false,
    val softLimiter: Boolean = true,
    val limiterCeilingDb: Float = -0.5f,
    val compressionRatio: Float = 1.5f,
    val stereoWidth: Float = 1.0f,
    val gainLevel: Float = 1.0f,
    // 4. Octave (-1 Oct / +1 Oct)
    val octaveEnabled: Boolean = false,
    val octaveSubMix: Float = 0.65f,
    val octaveUpMix: Float = 0.35f,
    val octaveSubToneHz: Float = 900f,
    val octaveDryMix: Float = 0.7f,
    val octaveLevel: Float = 1.0f,
    // 5. Tube Overdrive
    val overdriveEnabled: Boolean = false,
    val driveAmount: Float = 4f,
    val overdriveToneHz: Float = 4200f,
    val overdriveAsymmetry: Float = 0.25f,
    val overdrivePreHpHz: Float = 120f,
    val overdriveMix: Float = 0.65f,
    val overdriveLevel: Float = 1.0f,
    // 6. Auto-Wah Filter
    val wahEnabled: Boolean = false,
    val wahMode: DesktopWahMode = DesktopWahMode.AUTO_ENV,
    val wahRateHz: Float = 1.6f,
    val wahFreqHz: Float = 950f,
    val wahResonanceQ: Float = 3.5f,
    val wahEnvSens: Float = 0.6f,
    val wahPedalPos: Float = 0.5f,
    val wahMix: Float = 0.85f,
    val wahLevel: Float = 1.0f,
    // 7. Multi-Stage Phaser
    val phaserEnabled: Boolean = false,
    val phaserStages: Int = 4,
    val phaserRateHz: Float = 0.8f,
    val phaserDepth: Float = 0.75f,
    val phaserCenterHz: Float = 900f,
    val phaserFeedback: Float = 0.55f,
    val phaserStereoOffset: Float = 0.35f,
    val phaserMix: Float = 0.55f,
    val phaserLevel: Float = 1.0f,
    // 8. Flanger
    val flangerEnabled: Boolean = false,
    val flangerRateHz: Float = 0.6f,
    val flangerDepthMs: Float = 3.0f,
    val flangerManualMs: Float = 0.8f,
    val flangerFeedback: Float = 0.5f,
    val flangerSpread: Float = 0.4f,
    val flangerMix: Float = 0.5f,
    val flangerLevel: Float = 1.0f,
    // 9. Stereo Chorus
    val chorusEnabled: Boolean = false,
    val chorusVoices: Int = 3,
    val chorusRateHz: Float = 1.1f,
    val chorusDepthMs: Float = 6.5f,
    val chorusBaseMs: Float = 14f,
    val chorusHighCutHz: Float = 7500f,
    val chorusMix: Float = 0.45f,
    val chorusLevel: Float = 1.0f,
    // 10. Rotary Leslie Speaker
    val rotaryEnabled: Boolean = false,
    val rotaryRateHz: Float = 3.2f,
    val rotaryDepth: Float = 0.75f,
    val rotaryHornBalance: Float = 0.6f,
    val rotaryFastMode: Boolean = false,
    val rotaryBrake: Boolean = false,
    val rotaryCrossoverHz: Float = 800f,
    val rotaryCabinetDrive: Float = 0.15f,
    val rotaryMix: Float = 0.8f,
    val rotaryLevel: Float = 1.0f,
    // 11. Tape Echo
    val echoEnabled: Boolean = false,
    val echoDelayMs: Float = 240f,
    val echoFeedback: Float = 0.42f,
    val echoDamping: Float = 0.4f,
    val echoWowFlutter: Float = 0.25f,
    val echoSaturation: Float = 0.2f,
    val echoReverseDelay: Boolean = false,
    val echoMix: Float = 0.38f,
    val echoLevel: Float = 1.0f,
    // 12. Digital Delay
    val delayEnabled: Boolean = false,
    val delayTimeMs: Float = 360f,
    val delayBpmSync: Boolean = false,
    val delayDivision: DesktopDelayDivision = DesktopDelayDivision.FREE_MS,
    val delayFeedback: Float = 0.4f,
    val delaySpread: Float = 0.5f,
    val delayHighPassHz: Float = 140f,
    val delayLowPassHz: Float = 6500f,
    val delayReverseDelay: Boolean = false,
    val delayMix: Float = 0.35f,
    val delayLevel: Float = 1.0f,
    // 13. Analog BBD Delay
    val analogDelayEnabled: Boolean = false,
    val analogDelayMs: Float = 310f,
    val analogDelayFeedback: Float = 0.52f,
    val analogDelayWarmthHz: Float = 2400f,
    val analogDelaySaturation: Float = 0.35f,
    val analogDelayModDepth: Float = 0.3f,
    val analogDelayModRateHz: Float = 0.7f,
    val analogDelayReverseDelay: Boolean = false,
    val analogDelayMix: Float = 0.45f,
    val analogDelayLevel: Float = 1.0f,
    // 14. Concert Reverb
    val reverbEnabled: Boolean = false,
    val reverbSize: Float = 0.6f,
    val reverbDamping: Float = 0.35f,
    val reverbPreDelayMs: Float = 18f,
    val reverbEarlyReflections: Float = 0.45f,
    val reverbStereoWidth: Float = 0.75f,
    val reverbLowCutHz: Float = 120f,
    val reverbMix: Float = 0.35f,
    val reverbLevel: Float = 1.0f,
    // 15. Multi-Topology Distortion
    val distortionEnabled: Boolean = false,
    val distortionCurve: DesktopDistortionCurve = DesktopDistortionCurve.HARD_CLIP,
    val distortionDrive: Float = 6.0f,
    val distortionToneHz: Float = 3600f,
    val distortionSymmetry: Float = 0.25f,
    val distortionMix: Float = 0.72f,
    val distortionLevel: Float = 1.0f,
    // 16. Dedicated Resonant Multi-Mode Filter
    val filterEnabled: Boolean = false,
    val filterMode: DesktopBiquadFilterMode = DesktopBiquadFilterMode.LOW_PASS,
    val filterCutoffHz: Float = 1800f,
    val filterResonanceQ: Float = 2.2f,
    val filterDrive: Float = 1.2f,
    val filterLfoRateHz: Float = 0.4f,
    val filterLfoDepth: Float = 0.15f,
    val filterMix: Float = 0.85f,
    val filterLevel: Float = 1.0f
) {
    val fxActiveCount: Int
        get() = listOf(
            noiseGateEnabled, pitchEnabled, gainEnabled, octaveEnabled, overdriveEnabled,
            distortionEnabled, flangerEnabled, chorusEnabled,
            rotaryEnabled, echoEnabled, delayEnabled, analogDelayEnabled, reverbEnabled
        ).count { it }

    val filterActiveCount: Int
        get() = listOf(
            filterEnabled, wahEnabled, phaserEnabled
        ).count { it }

    val activeCount: Int
        get() = fxActiveCount + filterActiveCount

    companion object {
        const val FX_MODULE_COUNT = 13
        const val FILTER_MODULE_COUNT = 3
        const val TOTAL_MODULES = FX_MODULE_COUNT + FILTER_MODULE_COUNT
    }
}

data class DesktopStompState(
    val engaged: Boolean = true,
    val killDrive: Boolean = false,
    val killMod: Boolean = false,
    val killTime: Boolean = false,
    val toneTilt: Float = 0f,
    val dynamicsTrimDb: Float = 0f,
    val masterWetMix: Float = 1f,
    val modScale: Float = 1f,
    val spaceScale: Float = 1f,
    val xyPadX: Float = 0.5f,
    val xyPadY: Float = 0.5f,
    val liveMode: DesktopLiveMode = DesktopLiveMode.STAGE_DJ
)

enum class DesktopPreset(val label: String) {
    BYPASS("BYPASS"),
    CLEAN_VOCAL("CLEAN MIC"),
    ACID_WAH("ACID WAH"),
    DUB_SPACE("DUB SPACE"),
    MASTER_GLUE("MASTER GLUE")
}

object DesktopPresetFactory {
    fun build(preset: DesktopPreset): DesktopFxState = when (preset) {
        DesktopPreset.BYPASS -> DesktopFxState()
        DesktopPreset.CLEAN_VOCAL -> DesktopFxState(
            noiseGateEnabled = true,
            noiseThresholdDb = -44f,
            noiseReductionDb = 28f,
            noiseHighPassHz = 95f,
            gainEnabled = true,
            gainDb = 2.5f,
            normalize = true,
            compressionRatio = 2.2f,
            reverbEnabled = true,
            reverbSize = 0.35f,
            reverbMix = 0.18f
        )
        DesktopPreset.ACID_WAH -> DesktopFxState(
            overdriveEnabled = true,
            driveAmount = 6.5f,
            overdriveAsymmetry = 0.35f,
            wahEnabled = true,
            wahFreqHz = 1100f,
            wahResonanceQ = 5.2f,
            phaserEnabled = true,
            phaserStages = 6,
            phaserMix = 0.55f
        )
        DesktopPreset.DUB_SPACE -> DesktopFxState(
            echoEnabled = true,
            echoDelayMs = 280f,
            echoFeedback = 0.55f,
            echoMix = 0.45f,
            analogDelayEnabled = true,
            analogDelayMs = 375f,
            analogDelayFeedback = 0.58f,
            analogDelayMix = 0.42f,
            reverbEnabled = true,
            reverbSize = 0.8f,
            reverbMix = 0.45f
        )
        DesktopPreset.MASTER_GLUE -> DesktopFxState(
            gainEnabled = true,
            gainDb = 1.8f,
            normalize = true,
            compressionRatio = 2.4f,
            stereoWidth = 1.15f,
            chorusEnabled = true,
            chorusMix = 0.2f,
            reverbEnabled = true,
            reverbSize = 0.25f,
            reverbMix = 0.12f
        )
    }
}
