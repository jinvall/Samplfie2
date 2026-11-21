package com.samplfie.desktop.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class DesktopStation(val shortLabel: String, val fullTitle: String) {
    EDITOR("EDITOR", "SIGNAL & AUDIO EDITOR"),
    TUNER("TUNER", "CHROMATIC & STROBE TUNER"),
    FX_STOMP("FX & STOMP", "12-FX & MASTER STOMPBOX"),
    NET_LINK("NET LINK", "SMP2 LINUX AUDIO BRIDGE")
}

enum class VisualizerMode(val label: String) {
    WAVE("WAVE"),
    FFT("FFT"),
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

data class DesktopAudioBuffer(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int,
    val name: String = "Signal"
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
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var res = samples.contentHashCode()
        res = 31 * res + sampleRate
        res = 31 * res + channels
        res = 31 * res + name.hashCode()
        return res
    }
}

data class DesktopFxState(
    val masterBypass: Boolean = false,
    val noiseGateEnabled: Boolean = false,
    val noiseThresholdDb: Float = -46f,
    val pitchEnabled: Boolean = false,
    val pitchSemitones: Float = 0f,
    val speedFactor: Float = 1f,
    val gainEnabled: Boolean = false,
    val gainDb: Float = 0f,
    val normalize: Boolean = false,
    val overdriveEnabled: Boolean = false,
    val driveAmount: Float = 4f,
    val wahEnabled: Boolean = false,
    val wahFreqHz: Float = 950f,
    val phaserEnabled: Boolean = false,
    val phaserMix: Float = 0.5f,
    val flangerEnabled: Boolean = false,
    val flangerMix: Float = 0.5f,
    val chorusEnabled: Boolean = false,
    val chorusMix: Float = 0.45f,
    val rotaryEnabled: Boolean = false,
    val rotaryRateHz: Float = 3.2f,
    val echoEnabled: Boolean = false,
    val echoDelayMs: Float = 240f,
    val echoFeedback: Float = 0.4f,
    val echoMix: Float = 0.35f,
    val delayEnabled: Boolean = false,
    val delayTimeMs: Float = 360f,
    val delayMix: Float = 0.3f,
    val reverbEnabled: Boolean = false,
    val reverbSize: Float = 0.6f,
    val reverbMix: Float = 0.35f
) {
    val activeCount: Int
        get() = listOf(
            noiseGateEnabled, pitchEnabled, gainEnabled, overdriveEnabled,
            wahEnabled, phaserEnabled, flangerEnabled, chorusEnabled,
            rotaryEnabled, echoEnabled, delayEnabled, reverbEnabled
        ).count { it }
}

data class DesktopStompState(
    val engaged: Boolean = true,
    val killDrive: Boolean = false,
    val killMod: Boolean = false,
    val killTime: Boolean = false,
    val toneTilt: Float = 0f,
    val dynamicsTrimDb: Float = 0f,
    val masterWetMix: Float = 1f
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
            gainEnabled = true,
            gainDb = 2.5f,
            normalize = true,
            reverbEnabled = true,
            reverbSize = 0.35f,
            reverbMix = 0.18f
        )
        DesktopPreset.ACID_WAH -> DesktopFxState(
            overdriveEnabled = true,
            driveAmount = 6.5f,
            wahEnabled = true,
            wahFreqHz = 1100f,
            phaserEnabled = true,
            phaserMix = 0.55f
        )
        DesktopPreset.DUB_SPACE -> DesktopFxState(
            echoEnabled = true,
            echoDelayMs = 280f,
            echoFeedback = 0.55f,
            echoMix = 0.45f,
            delayEnabled = true,
            delayTimeMs = 420f,
            delayMix = 0.38f,
            reverbEnabled = true,
            reverbSize = 0.8f,
            reverbMix = 0.45f
        )
        DesktopPreset.MASTER_GLUE -> DesktopFxState(
            gainEnabled = true,
            gainDb = 1.8f,
            normalize = true,
            chorusEnabled = true,
            chorusMix = 0.2f,
            reverbEnabled = true,
            reverbSize = 0.25f,
            reverbMix = 0.12f
        )
    }
}
