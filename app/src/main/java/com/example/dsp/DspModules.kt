package com.example.dsp

import com.example.audio.AudioBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Collection of 12 pure-Kotlin floating-point audio DSP processors + Stompbox master Tilt EQ.
 */
object DspModules {

    // 1. Noise Removal (Variable HPF + attack/release/soft-knee envelope noise gate)
    fun applyNoiseRemoval(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: NoiseRemovalParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val frames = samples.size / channels
        val out = FloatArray(samples.size)

        val hpfHz = params.highPassFreqHz.coerceIn(25f, 300f)
        val rc = 1.0f / (2.0f * PI.toFloat() * hpfHz)
        val dt = 1.0f / sampleRate.toFloat()
        val hpAlpha = rc / (rc + dt)
        val prevIn = FloatArray(channels)
        val prevHp = FloatArray(channels)

        val attackSec = (params.attackMs.coerceIn(0.5f, 50f) / 1000f)
        val releaseSec = (params.releaseMs.coerceIn(10f, 500f) / 1000f)
        val attackCoeff = exp(-1.0f / (attackSec * sampleRate))
        val releaseCoeff = exp(-1.0f / (releaseSec * sampleRate))
        val thresholdLin = 10f.pow(params.thresholdDb / 20f)
        val kneeRatio = 10f.pow(-params.kneeWidthDb.coerceIn(0f, 18f) / 20f)
        val floorGain = 10f.pow(-params.reductionDb / 20f)
        var envelope = 0f

        for (f in 0 until frames) {
            var framePower = 0f
            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                val hp = if (params.highPass80Hz) {
                    val y = hpAlpha * (prevHp[c] + x - prevIn[c])
                    prevIn[c] = x
                    prevHp[c] = y
                    y
                } else {
                    x
                }
                out[idx] = hp
                framePower += hp * hp
            }
            val rms = sqrt(framePower / channels)
            envelope = if (rms > envelope) {
                attackCoeff * envelope + (1f - attackCoeff) * rms
            } else {
                releaseCoeff * envelope + (1f - releaseCoeff) * rms
            }

            val lowerKnee = thresholdLin * kneeRatio
            val gain = when {
                envelope >= thresholdLin -> 1f
                envelope <= lowerKnee -> {
                    val ratio = (envelope / max(lowerKnee, 1e-6f)).coerceIn(0f, 1f)
                    floorGain + (1f - floorGain) * ratio * ratio * 0.1f
                }
                else -> {
                    val t = ((envelope - lowerKnee) / max(thresholdLin - lowerKnee, 1e-6f)).coerceIn(0f, 1f)
                    val shaped = 0.1f + 0.9f * t * t
                    floorGain + (1f - floorGain) * shaped
                }
            }

            for (c in 0 until channels) {
                out[f * channels + c] *= gain
            }
        }
        return out
    }

    // 2. Pitch & Speed (WSOLA Time Stretch + Fine Cents + Cubic Hermite Resampling + Vinyl Link mode)
    fun applyPitchAndSpeed(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: PitchSpeedParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val speed = params.speedFactor.coerceIn(0.5f, 2.0f)
        val totalSemis = (params.pitchSemitones + params.fineCents.coerceIn(-50f, 50f) / 100f)
            .coerceIn(-12.5f, 12.5f)
        val pitchRatio = if (params.vinylLink) {
            speed
        } else {
            2.0f.pow(totalSemis / 12.0f)
        }

        if (abs(speed - 1f) < 0.005f && abs(pitchRatio - 1f) < 0.005f) {
            return samples
        }

        val timeStretchFactor = if (params.vinylLink) {
            1.0f
        } else {
            (pitchRatio / speed).coerceIn(0.25f, 4.0f)
        }

        val stretched = if (abs(timeStretchFactor - 1f) > 0.005f) {
            wsolaStretch(samples, sampleRate, channels, timeStretchFactor, params.grainSizeMs)
        } else {
            samples
        }

        return if (abs(pitchRatio - 1f) > 0.005f) {
            resampleByRatio(stretched, channels, pitchRatio)
        } else {
            stretched
        }
    }

    private fun wsolaStretch(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        stretchFactor: Float,
        grainSizeMs: Float
    ): FloatArray {
        val inFrames = samples.size / channels
        if (inFrames < 64) return samples

        val grainSec = grainSizeMs.coerceIn(14f, 64f) / 1000f
        val windowSize = (sampleRate * grainSec).toInt().coerceIn(256, max(256, inFrames / 2))
        val hopOut = windowSize / 2
        val hopIn = max(1, (hopOut / stretchFactor).toInt())
        val searchRadius = (sampleRate * 0.006f).toInt().coerceIn(8, windowSize / 3)
        val outFrames = max(windowSize, (inFrames * stretchFactor).toInt())
        val out = FloatArray(outFrames * channels)
        val norm = FloatArray(outFrames)

        val hann = FloatArray(windowSize) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (windowSize - 1)))).toFloat()
        }

        var inPos = 0
        var outPos = 0

        while (outPos + windowSize <= outFrames && inPos < inFrames) {
            var bestOffset = 0
            if (outPos > 0 && inPos > searchRadius && inPos + windowSize + searchRadius < inFrames) {
                var bestCorr = Float.NEGATIVE_INFINITY
                val step = max(1, searchRadius / 12)
                for (delta in -searchRadius..searchRadius step step) {
                    var corr = 0f
                    val cand = inPos + delta
                    for (k in 0 until hopOut step 4) {
                        val a = out[(outPos + k) * channels]
                        val b = samples[(cand + k) * channels]
                        corr += a * b
                    }
                    if (corr > bestCorr) {
                        bestCorr = corr
                        bestOffset = delta
                    }
                }
            }

            val alignedIn = (inPos + bestOffset).coerceIn(0, max(0, inFrames - windowSize))
            for (k in 0 until windowSize) {
                val w = hann[k]
                norm[outPos + k] += w
                val srcFrame = min(inFrames - 1, alignedIn + k)
                for (c in 0 until channels) {
                    out[(outPos + k) * channels + c] += samples[srcFrame * channels + c] * w
                }
            }

            inPos += hopIn
            outPos += hopOut
        }

        for (f in 0 until outFrames) {
            val n = norm[f]
            if (n > 1e-4f) {
                val inv = 1f / n
                for (c in 0 until channels) {
                    out[f * channels + c] = (out[f * channels + c] * inv).coerceIn(-1f, 1f)
                }
            }
        }
        return out
    }

    private fun resampleByRatio(samples: FloatArray, channels: Int, ratio: Float): FloatArray {
        val inFrames = samples.size / channels
        if (inFrames < 4) return samples
        val outFrames = max(1, (inFrames / ratio).toInt())
        val out = FloatArray(outFrames * channels)
        for (c in 0 until channels) {
            for (i in 0 until outFrames) {
                val pos = i * ratio.toDouble()
                val idx = pos.toInt()
                val t = (pos - idx).toFloat()
                val xm1 = samples[(idx - 1).coerceIn(0, inFrames - 1) * channels + c]
                val x0 = samples[idx.coerceIn(0, inFrames - 1) * channels + c]
                val x1 = samples[(idx + 1).coerceIn(0, inFrames - 1) * channels + c]
                val x2 = samples[(idx + 2).coerceIn(0, inFrames - 1) * channels + c]
                out[i * channels + c] = AudioBuffer.hermite(t, xm1, x0, x1, x2).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    // 3. Gain + Compression Ratio + Stereo Width + Peak Normalize + Variable Ceiling Soft Limiter
    fun applyGain(
        samples: FloatArray,
        params: GainParams,
        channels: Int = 1
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        val ceilingLin = 10f.pow(params.limiterCeilingDb.coerceIn(-6f, -0.1f) / 20f)
        var normScale = 1f
        if (params.normalize) {
            var peak = 0f
            for (s in samples) {
                val a = abs(s)
                if (a > peak) peak = a
            }
            if (peak > 1e-5f) {
                normScale = ceilingLin / peak
            }
        }
        val gainLin = 10f.pow(params.gainDb.coerceIn(-24f, 24f) / 20f) * normScale
        val compRatio = params.compressionRatio.coerceIn(1f, 8f)
        val compThresh = 0.5f

        for (i in samples.indices) {
            var y = samples[i] * gainLin
            if (compRatio > 1.05f && abs(y) > compThresh) {
                val sign = if (y >= 0f) 1f else -1f
                val over = abs(y) - compThresh
                y = sign * (compThresh + over / compRatio)
            }
            if (params.softLimiter && abs(y) > ceilingLin * 0.9f) {
                val sign = if (y >= 0f) 1f else -1f
                val knee = ceilingLin * 0.9f
                val excess = abs(y) - knee
                val headroom = (ceilingLin - knee).coerceAtLeast(0.02f)
                y = sign * (knee + headroom * tanh(excess / headroom))
            }
            out[i] = y.coerceIn(-1f, 1f)
        }

        if (channels == 2 && abs(params.stereoWidth - 1f) > 0.01f) {
            val width = params.stereoWidth.coerceIn(0f, 2f)
            val frames = out.size / 2
            for (f in 0 until frames) {
                val l = out[f * 2]
                val r = out[f * 2 + 1]
                val mid = (l + r) * 0.5f
                val side = (l - r) * 0.5f * width
                out[f * 2] = (mid + side).coerceIn(-1f, 1f)
                out[f * 2 + 1] = (mid - side).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    // 4. Overdrive (Pre-HPF + Asymmetric tube waveshaper + 1-pole tone LPF)
    fun applyOverdrive(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: OverdriveParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        val drive = params.drive.coerceIn(1f, 20f)
        val mix = params.mix.coerceIn(0f, 1f)
        val dry = 1f - mix
        val bias = params.asymmetry.coerceIn(0f, 0.8f) * 0.4f
        val norm = 1f / max(0.35f, tanh(drive * 0.7f))

        val fc = params.toneHz.coerceIn(400f, sampleRate * 0.45f)
        val lpAlpha = (1f - exp(-2f * PI.toFloat() * fc / sampleRate)).coerceIn(0.01f, 1f)
        val lpState = FloatArray(channels)

        val preHpHz = params.preHighPassHz.coerceIn(20f, 800f)
        val rc = 1f / (2f * PI.toFloat() * preHpHz)
        val hpAlpha = rc / (rc + 1f / sampleRate)
        val hpPrevIn = FloatArray(channels)
        val hpPrevOut = FloatArray(channels)
        val frames = samples.size / channels

        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                val pre = hpAlpha * (hpPrevOut[c] + x - hpPrevIn[c])
                hpPrevIn[c] = x
                hpPrevOut[c] = pre
                val shaped = (tanh(pre * drive + bias) - tanh(bias)) * norm
                lpState[c] += lpAlpha * (shaped - lpState[c])
                out[idx] = (x * dry + lpState[c] * mix).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    // 5. Wah (Auto-Env / LFO / Manual Pedal swept State-Variable Bandpass Filter)
    fun applyWah(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: WahParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        val frames = samples.size / channels
        val mix = params.mix.coerceIn(0f, 1f)
        val dry = 1f - mix
        val qInv = 1f / params.resonanceQ.coerceIn(1.2f, 10f)
        val low = FloatArray(channels)
        val band = FloatArray(channels)

        val attack = exp(-1f / (0.004f * sampleRate))
        val release = exp(-1f / (0.08f * sampleRate))
        var env = 0f

        for (f in 0 until frames) {
            var monoAbs = 0f
            for (c in 0 until channels) monoAbs += abs(samples[f * channels + c])
            monoAbs /= channels
            env = if (monoAbs > env) attack * env + (1f - attack) * monoAbs else release * env + (1f - release) * monoAbs

            val lfo = 0.5f + 0.5f * sin(2.0 * PI * params.rateHz * f / sampleRate).toFloat()
            val modFreq = when (params.mode) {
                WahMode.AUTO_ENV -> params.centerFreqHz * (0.45f + 1.15f * lfo + env * params.envSensitivity * 2.4f)
                WahMode.LFO_SWEEP -> params.centerFreqHz * (0.4f + 1.6f * lfo)
                WahMode.MANUAL_PEDAL -> params.centerFreqHz * (0.35f + 1.85f * params.manualPedalPos.coerceIn(0f, 1f))
            }.coerceIn(180f, sampleRate * 0.36f)
            val fCoeff = (2f * sin(PI.toFloat() * modFreq / sampleRate)).coerceIn(0.001f, 0.95f)

            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                repeat(2) {
                    low[c] += fCoeff * 0.5f * band[c]
                    val high = x - low[c] - qInv * band[c]
                    band[c] += fCoeff * 0.5f * high
                }
                val wet = (band[c] * 1.4f).coerceIn(-1f, 1f)
                out[idx] = (x * dry + wet * mix).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    // Master DJ Stompbox Tilt EQ + Dynamics Trim
    fun applyTiltEqAndTrim(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        toneTilt: Float,
        trimDb: Float
    ): FloatArray {
        if (samples.isEmpty() || (abs(toneTilt) < 0.02f && abs(trimDb) < 0.05f)) return samples
        val out = FloatArray(samples.size)
        val frames = samples.size / channels
        val trimLin = 10f.pow(trimDb.coerceIn(-12f, 12f) / 20f)
        val tilt = toneTilt.coerceIn(-1f, 1f)

        val splitHz = 950f
        val lpAlpha = (1f - exp(-2f * PI.toFloat() * splitHz / sampleRate)).coerceIn(0.01f, 1f)
        val lpState = FloatArray(channels)
        val lowGain = 1f - tilt * 0.45f
        val highGain = 1f + tilt * 0.55f

        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                lpState[c] += lpAlpha * (x - lpState[c])
                val low = lpState[c]
                val high = x - low
                val tilted = low * lowGain + high * highGain
                out[idx] = (tilted * trimLin).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    fun applyPhaser(samples: FloatArray, sampleRate: Int, channels: Int, params: PhaserParams): FloatArray =
        DspSpatialModModules.applyPhaser(samples, sampleRate, channels, params)

    fun applyFlanger(samples: FloatArray, sampleRate: Int, channels: Int, params: FlangerParams): FloatArray =
        DspSpatialModModules.applyFlanger(samples, sampleRate, channels, params)

    fun applyChorus(samples: FloatArray, sampleRate: Int, channels: Int, params: ChorusParams): FloatArray =
        DspSpatialModModules.applyChorus(samples, sampleRate, channels, params)

    fun applyRotarySpeaker(samples: FloatArray, sampleRate: Int, channels: Int, params: RotaryParams): FloatArray =
        DspSpatialModModules.applyRotarySpeaker(samples, sampleRate, channels, params)

    fun applyEcho(samples: FloatArray, sampleRate: Int, channels: Int, params: EchoParams): FloatArray =
        DspSpatialModModules.applyEcho(samples, sampleRate, channels, params)

    fun applyDelay(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: DelayParams,
        bpm: Float = 124f
    ): FloatArray = DspSpatialModModules.applyDelay(samples, sampleRate, channels, params, bpm)

    fun applyReverb(samples: FloatArray, sampleRate: Int, channels: Int, params: ReverbParams): FloatArray =
        DspSpatialModModules.applyReverb(samples, sampleRate, channels, params)
}
