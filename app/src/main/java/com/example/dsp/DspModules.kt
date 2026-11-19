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
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Collection of 12 pure-Kotlin floating-point audio DSP processors.
 */
object DspModules {

    // 1. Noise Removal (80 Hz rumble HPF + RMS envelope soft-knee noise gate)
    fun applyNoiseRemoval(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: NoiseRemovalParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val frames = samples.size / channels
        val out = FloatArray(samples.size)

        val rc = 1.0f / (2.0f * PI.toFloat() * 80f)
        val dt = 1.0f / sampleRate.toFloat()
        val hpAlpha = rc / (rc + dt)
        val prevIn = FloatArray(channels)
        val prevHp = FloatArray(channels)

        val attackCoeff = exp(-1.0f / (0.003f * sampleRate))
        val releaseCoeff = exp(-1.0f / (0.065f * sampleRate))
        val thresholdLin = 10f.pow(params.thresholdDb / 20f)
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

            // Soft knee expander around thresholdLin
            val gain = if (envelope >= thresholdLin) {
                1f
            } else {
                val ratio = (envelope / max(thresholdLin, 1e-6f)).coerceIn(0f, 1f)
                val shaped = ratio * ratio
                floorGain + (1f - floorGain) * shaped
            }

            for (c in 0 until channels) {
                out[f * channels + c] *= gain
            }
        }
        return out
    }

    // 2. Pitch & Speed (WSOLA Time Stretch + Cubic Hermite Resampling + Vinyl Link mode)
    fun applyPitchAndSpeed(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: PitchSpeedParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val speed = params.speedFactor.coerceIn(0.5f, 2.0f)
        val pitchRatio = if (params.vinylLink) {
            speed
        } else {
            2.0f.pow(params.pitchSemitones.coerceIn(-12f, 12f) / 12.0f)
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
            wsolaStretch(samples, sampleRate, channels, timeStretchFactor)
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
        stretchFactor: Float
    ): FloatArray {
        val inFrames = samples.size / channels
        if (inFrames < 64) return samples

        val windowSize = (sampleRate * 0.032f).toInt().coerceIn(256, max(256, inFrames / 2))
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
            // Find best correlation alignment around inPos
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

    // 3. Gain + Optional Peak Normalize + Soft-Knee Limiter
    fun applyGain(samples: FloatArray, params: GainParams): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        var normScale = 1f
        if (params.normalize) {
            var peak = 0f
            for (s in samples) {
                val a = abs(s)
                if (a > peak) peak = a
            }
            if (peak > 1e-5f) {
                normScale = 0.966f / peak
            }
        }
        val gainLin = 10f.pow(params.gainDb.coerceIn(-24f, 24f) / 20f) * normScale
        for (i in samples.indices) {
            var y = samples[i] * gainLin
            if (params.softLimiter && abs(y) > 0.9f) {
                val sign = if (y >= 0f) 1f else -1f
                val excess = abs(y) - 0.9f
                y = sign * (0.9f + 0.1f * tanh(excess * 10f))
            }
            out[i] = y.coerceIn(-1f, 1f)
        }
        return out
    }

    // 4. Overdrive (Asymmetric tube waveshaper + 1-pole tone LPF)
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
        val bias = 0.12f
        val norm = 1f / max(0.35f, tanh(drive * 0.7f))

        val fc = params.toneHz.coerceIn(400f, sampleRate * 0.45f)
        val lpAlpha = (1f - exp(-2f * PI.toFloat() * fc / sampleRate)).coerceIn(0.01f, 1f)
        val lpState = FloatArray(channels)
        val frames = samples.size / channels

        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                val shaped = (tanh(x * drive + bias) - tanh(bias)) * norm
                lpState[c] += lpAlpha * (shaped - lpState[c])
                out[idx] = (x * dry + lpState[c] * mix).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    // 5. Wah (Envelope-follower + LFO swept State-Variable Bandpass Filter)
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
            val modFreq = (params.centerFreqHz * (0.45f + 1.15f * lfo + env * params.envSensitivity * 2.4f))
                .coerceIn(180f, sampleRate * 0.36f)
            val fCoeff = (2f * sin(PI.toFloat() * modFreq / sampleRate)).coerceIn(0.001f, 0.95f)

            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                // 2-step oversampled SVF update for crisp high-Q stability
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

    // 6. Phaser (4-stage cascaded swept 1st-order all-pass filters with resonance feedback)
    fun applyPhaser(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: PhaserParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        val frames = samples.size / channels
        val mix = params.mix.coerceIn(0f, 1f)
        val dry = 1f - mix
        val fb = params.feedback.coerceIn(0f, 0.88f)
        val stages = 4
        val apState = Array(channels) { FloatArray(stages) }
        val lastOut = FloatArray(channels)

        for (f in 0 until frames) {
            val lfo = 0.5f + 0.5f * sin(2.0 * PI * params.rateHz * f / sampleRate).toFloat()
            val sweepHz = 220f + (lfo * params.depth) * 2600f
            val tanVal = tan(PI.toFloat() * sweepHz.coerceIn(100f, sampleRate * 0.4f) / sampleRate)
            val coeff = (1f - tanVal) / (1f + tanVal)

            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                var signal = x + lastOut[c] * fb
                for (s in 0 until stages) {
                    val y = -coeff * signal + apState[c][s]
                    apState[c][s] = signal + coeff * y
                    signal = y
                }
                lastOut[c] = signal.coerceIn(-1f, 1f)
                out[idx] = (x * dry + lastOut[c] * mix).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    // 7. Flanger (Short modulated fractional delay line with bipolar feedback)
    fun applyFlanger(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: FlangerParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        val frames = samples.size / channels
        val maxDelaySamples = ((0.008f + params.depthMs / 1000f) * sampleRate).toInt().coerceAtLeast(32)
        val delayBuf = Array(channels) { FloatArray(maxDelaySamples + 4) }
        var writePtr = 0
        val mix = params.mix.coerceIn(0f, 1f)
        val dry = 1f - mix
        val fb = params.feedback.coerceIn(-0.85f, 0.85f)

        for (f in 0 until frames) {
            val lfo = 0.5f + 0.5f * sin(2.0 * PI * params.rateHz * f / sampleRate).toFloat()
            val delaySamples = (0.0004f + (params.depthMs / 1000f) * lfo) * sampleRate

            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                val buf = delayBuf[c]
                val delayed = readFractional(buf, writePtr, delaySamples)
                buf[writePtr] = (x + delayed * fb).coerceIn(-1f, 1f)
                out[idx] = (x * dry + delayed * mix).coerceIn(-1f, 1f)
            }
            writePtr = (writePtr + 1) % delayBuf[0].size
        }
        return out
    }

    // 8. Chorus (3-voice detuned LFO modulated delay)
    fun applyChorus(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: ChorusParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        val frames = samples.size / channels
        val maxDelaySamples = ((0.045f + params.depthMs / 1000f) * sampleRate).toInt().coerceAtLeast(64)
        val delayBuf = Array(channels) { FloatArray(maxDelaySamples + 4) }
        var writePtr = 0
        val mix = params.mix.coerceIn(0f, 1f)
        val dry = 1f - mix
        val baseDelaysMs = floatArrayOf(14f, 21f, 28f)
        val phaseOffsets = floatArrayOf(0f, (2.0 * PI / 3.0).toFloat(), (4.0 * PI / 3.0).toFloat())

        for (f in 0 until frames) {
            val basePhase = (2.0 * PI * params.rateHz * f / sampleRate).toFloat()
            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                val buf = delayBuf[c]
                buf[writePtr] = x

                var wet = 0f
                for (v in 0 until 3) {
                    val lfo = 0.5f + 0.5f * sin(basePhase + phaseOffsets[v] + c * 0.6f)
                    val dSamples = ((baseDelaysMs[v] + params.depthMs * lfo) / 1000f) * sampleRate
                    wet += readFractional(buf, writePtr, dSamples)
                }
                wet /= 3f
                out[idx] = (x * dry + wet * mix).coerceIn(-1f, 1f)
            }
            writePtr = (writePtr + 1) % delayBuf[0].size
        }
        return out
    }

    // 9. Rotary Speaker (Dual-band Leslie cabinet: crossover + Doppler pitch vibrato + AM swirl)
    fun applyRotarySpeaker(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: RotaryParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        val frames = samples.size / channels
        val mix = params.mix.coerceIn(0f, 1f)
        val dry = 1f - mix
        val lpAlpha = (1f - exp(-2f * PI.toFloat() * 800f / sampleRate)).coerceIn(0.01f, 1f)
        val lpState = FloatArray(channels)

        val maxDopplerSamples = (0.004f * sampleRate).toInt().coerceAtLeast(32)
        val hornBuf = Array(channels) { FloatArray(maxDopplerSamples + 4) }
        var writePtr = 0

        for (f in 0 until frames) {
            val hornPhase = (2.0 * PI * params.rateHz * f / sampleRate).toFloat()
            val drumPhase = (2.0 * PI * (params.rateHz * 0.72f) * f / sampleRate).toFloat()

            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                lpState[c] += lpAlpha * (x - lpState[c])
                val lowDrum = lpState[c]
                val highHorn = x - lowDrum

                val chOffset = if (channels == 2 && c == 1) (PI / 2.0).toFloat() else 0f
                val hornAm = 1f - params.depth * 0.42f * (0.5f + 0.5f * cos(hornPhase + chOffset))
                val drumAm = 1f - params.depth * 0.30f * (0.5f + 0.5f * sin(drumPhase + chOffset))

                hornBuf[c][writePtr] = highHorn
                val dopplerDelay = (0.0014f + 0.0012f * params.depth * sin(hornPhase + chOffset)) * sampleRate
                val dopplerHorn = readFractional(hornBuf[c], writePtr, dopplerDelay.coerceAtLeast(1f))

                val wet = dopplerHorn * hornAm * params.hornBalance * 1.4f +
                    lowDrum * drumAm * (1f - params.hornBalance) * 1.4f
                out[idx] = (x * dry + wet * mix).coerceIn(-1f, 1f)
            }
            writePtr = (writePtr + 1) % hornBuf[0].size
        }
        return out
    }

    // 10. Echo (Tape-style delay with HF low-pass damping in feedback loop)
    fun applyEcho(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: EchoParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        val frames = samples.size / channels
        val delaySamples = ((params.delayMs.coerceIn(20f, 1500f) / 1000f) * sampleRate).toInt().coerceAtLeast(1)
        val delayBuf = Array(channels) { FloatArray(delaySamples) }
        var ptr = 0
        val fb = params.feedback.coerceIn(0f, 0.9f)
        val damp = (1f - params.damping.coerceIn(0f, 0.92f)).coerceIn(0.05f, 1f)
        val dampState = FloatArray(channels)
        val mix = params.mix.coerceIn(0f, 1f)
        val dry = 1f - mix

        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                val delayed = delayBuf[c][ptr]
                dampState[c] += damp * (delayed - dampState[c])
                delayBuf[c][ptr] = (x + dampState[c] * fb).coerceIn(-1f, 1f)
                out[idx] = (x * dry + delayed * mix).coerceIn(-1f, 1f)
            }
            ptr = (ptr + 1) % delaySamples
        }
        return out
    }

    // 11. Delay (Digital delay with stereo/ping-pong tap spread)
    fun applyDelay(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: DelayParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        val frames = samples.size / channels
        val baseDelaySamples = ((params.delayMs.coerceIn(10f, 1500f) / 1000f) * sampleRate).toInt().coerceAtLeast(1)
        val secondaryDelaySamples = max(1, (baseDelaySamples * (1f - params.stereoSpread * 0.35f)).toInt())
        val delayBuf = Array(channels) { c ->
            FloatArray(if (c == 1) secondaryDelaySamples else baseDelaySamples)
        }
        val ptrs = IntArray(channels)
        val fb = params.feedback.coerceIn(0f, 0.92f)
        val mix = params.mix.coerceIn(0f, 1f)
        val dry = 1f - mix

        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                val buf = delayBuf[c]
                val delayed = buf[ptrs[c]]
                val crossChannel = if (channels == 2) delayBuf[1 - c][ptrs[1 - c]] else delayed
                val feedbackSample = delayed * (1f - params.stereoSpread * 0.5f) + crossChannel * (params.stereoSpread * 0.5f)
                buf[ptrs[c]] = (x + feedbackSample * fb).coerceIn(-1f, 1f)
                ptrs[c] = (ptrs[c] + 1) % buf.size
                out[idx] = (x * dry + delayed * mix).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    // 12. Reverb (Schroeder/Freeverb 4-parallel damped comb filters + 2 series all-pass diffusers)
    fun applyReverb(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: ReverbParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        val frames = samples.size / channels
        val scale = sampleRate / 44100f
        val preDelaySamples = max(1, (params.preDelayMs / 1000f * sampleRate).toInt())

        val combBase = intArrayOf(1557, 1617, 1491, 1422)
        val apBase = intArrayOf(556, 441)
        val fb = 0.72f + params.roomSize.coerceIn(0f, 1f) * 0.23f
        val damp = params.damping.coerceIn(0.05f, 0.9f)
        val mix = params.mix.coerceIn(0f, 1f)
        val dry = 1f - mix

        for (c in 0 until channels) {
            val preBuf = FloatArray(preDelaySamples)
            var prePtr = 0

            val combs = combBase.map { len ->
                FloatArray(max(8, (len * scale + c * 23).toInt()))
            }
            val combPtrs = IntArray(combs.size)
            val combFilters = FloatArray(combs.size)

            val allpasses = apBase.map { len ->
                FloatArray(max(8, (len * scale + c * 17).toInt()))
            }
            val apPtrs = IntArray(allpasses.size)

            for (f in 0 until frames) {
                val idx = f * channels + c
                val x = samples[idx]

                val preOut = preBuf[prePtr]
                preBuf[prePtr] = x
                prePtr = (prePtr + 1) % preDelaySamples

                var combSum = 0f
                for (i in combs.indices) {
                    val buf = combs[i]
                    val ptr = combPtrs[i]
                    val y = buf[ptr]
                    combFilters[i] = y * (1f - damp) + combFilters[i] * damp
                    buf[ptr] = (preOut + combFilters[i] * fb).coerceIn(-1f, 1f)
                    combPtrs[i] = (ptr + 1) % buf.size
                    combSum += y
                }
                var diffused = combSum * 0.25f

                for (i in allpasses.indices) {
                    val buf = allpasses[i]
                    val ptr = apPtrs[i]
                    val bufOut = buf[ptr]
                    val y = -diffused + bufOut
                    buf[ptr] = (diffused + bufOut * 0.5f).coerceIn(-1f, 1f)
                    apPtrs[i] = (ptr + 1) % buf.size
                    diffused = y
                }

                out[idx] = (x * dry + diffused * mix).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    private fun readFractional(buffer: FloatArray, writePtr: Int, delaySamples: Float): Float {
        val size = buffer.size
        val readPos = (writePtr - delaySamples + size * 2) % size
        val i0 = readPos.toInt() % size
        val i1 = (i0 + 1) % size
        val frac = readPos - readPos.toInt()
        return buffer[i0] * (1f - frac) + buffer[i1] * frac
    }
}
