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
 * Pure-Kotlin floating-point audio DSP spatial & modulation processors (Modules 6–12).
 */
internal object DspSpatialModModules {

    // 6. Phaser (4/6/8-stage cascaded swept 1st-order all-pass filters with stereo phase offset)
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
        val fb = params.feedback.coerceIn(0f, 0.9f)
        val stages = params.stages.coerceIn(2, 8)
        val apState = Array(channels) { FloatArray(stages) }
        val lastOut = FloatArray(channels)
        val baseCenter = params.centerFreqHz.coerceIn(250f, 3200f)

        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val chPhase = if (channels == 2 && c == 1) params.stereoPhaseOffset * PI.toFloat() else 0f
                val lfo = 0.5f + 0.5f * sin(2.0 * PI * params.rateHz * f / sampleRate + chPhase).toFloat()
                val sweepHz = (baseCenter * 0.25f + (lfo * params.depth) * baseCenter * 2.4f)
                    .coerceIn(100f, sampleRate * 0.4f)
                val tanVal = tan(PI.toFloat() * sweepHz / sampleRate)
                val coeff = (1f - tanVal) / (1f + tanVal)

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

    // 7. Flanger (Short modulated fractional delay line with manual sweep & stereo spread)
    fun applyFlanger(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: FlangerParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        val frames = samples.size / channels
        val manualMs = params.manualDelayMs.coerceIn(0.2f, 5f)
        val maxDelaySamples = (((manualMs + params.depthMs + 2f) / 1000f) * sampleRate).toInt().coerceAtLeast(32)
        val delayBuf = Array(channels) { FloatArray(maxDelaySamples + 4) }
        var writePtr = 0
        val mix = params.mix.coerceIn(0f, 1f)
        val dry = 1f - mix
        val fb = params.feedback.coerceIn(-0.88f, 0.88f)
        val spreadPhase = params.stereoSpread.coerceIn(0f, 1f) * PI.toFloat()

        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val chPhase = if (channels == 2 && c == 1) spreadPhase else 0f
                val lfo = 0.5f + 0.5f * sin(2.0 * PI * params.rateHz * f / sampleRate + chPhase).toFloat()
                val delaySamples = ((manualMs + params.depthMs * lfo) / 1000f) * sampleRate

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

    // 8. Chorus (2–4 voice detuned LFO modulated delay with selectable base delay & HF cut)
    fun applyChorus(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: ChorusParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        val frames = samples.size / channels
        val voiceCount = params.voices.coerceIn(2, 4)
        val baseMs = params.baseDelayMs.coerceIn(6f, 28f)
        val maxDelaySamples = (((baseMs + 14f + params.depthMs) / 1000f) * sampleRate).toInt().coerceAtLeast(64)
        val delayBuf = Array(channels) { FloatArray(maxDelaySamples + 4) }
        var writePtr = 0
        val mix = params.mix.coerceIn(0f, 1f)
        val dry = 1f - mix
        val lpAlpha = (1f - exp(-2f * PI.toFloat() * params.highCutHz.coerceIn(1500f, sampleRate * 0.45f) / sampleRate))
            .coerceIn(0.05f, 1f)
        val lpState = FloatArray(channels)

        for (f in 0 until frames) {
            val basePhase = (2.0 * PI * params.rateHz * f / sampleRate).toFloat()
            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                val buf = delayBuf[c]
                buf[writePtr] = x

                var wet = 0f
                for (v in 0 until voiceCount) {
                    val offset = (2.0 * PI * v / voiceCount).toFloat()
                    val lfo = 0.5f + 0.5f * sin(basePhase + offset + c * 0.65f)
                    val voiceBase = baseMs + v * 5.5f
                    val dSamples = ((voiceBase + params.depthMs * lfo) / 1000f) * sampleRate
                    wet += readFractional(buf, writePtr, dSamples)
                }
                wet /= voiceCount.toFloat()
                lpState[c] += lpAlpha * (wet - lpState[c])
                out[idx] = (x * dry + lpState[c] * mix).coerceIn(-1f, 1f)
            }
            writePtr = (writePtr + 1) % delayBuf[0].size
        }
        return out
    }

    // 9. Rotary Speaker (Dual-band Leslie cabinet: crossover + brake/fast + Doppler vibrato + cabinet saturation)
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
        val crossover = params.crossoverHz.coerceIn(350f, 1800f)
        val lpAlpha = (1f - exp(-2f * PI.toFloat() * crossover / sampleRate)).coerceIn(0.01f, 1f)
        val lpState = FloatArray(channels)
        val effectiveRate = when {
            params.brake -> 0.08f
            params.fastMode -> max(params.rateHz, 6.2f)
            else -> params.rateHz
        }

        val maxDopplerSamples = (0.004f * sampleRate).toInt().coerceAtLeast(32)
        val hornBuf = Array(channels) { FloatArray(maxDopplerSamples + 4) }
        var writePtr = 0
        val drive = 1f + params.cabinetDrive.coerceIn(0f, 1f) * 3.5f

        for (f in 0 until frames) {
            val hornPhase = (2.0 * PI * effectiveRate * f / sampleRate).toFloat()
            val drumPhase = (2.0 * PI * (effectiveRate * 0.72f) * f / sampleRate).toFloat()

            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = if (params.cabinetDrive > 0.02f) tanh(samples[idx] * drive) / tanh(drive) else samples[idx]
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
                out[idx] = (samples[idx] * dry + wet * mix).coerceIn(-1f, 1f)
            }
            writePtr = (writePtr + 1) % hornBuf[0].size
        }
        return out
    }

    // 10. Echo (Tape-style delay with wow/flutter modulation + saturation + HF damping)
    fun applyEcho(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: EchoParams
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        val frames = samples.size / channels
        val baseDelaySamples = ((params.delayMs.coerceIn(20f, 1500f) / 1000f) * sampleRate).coerceAtLeast(8f)
        val maxSamples = (baseDelaySamples + 0.008f * sampleRate).toInt().coerceAtLeast(32)
        val delayBuf = Array(channels) { FloatArray(maxSamples + 4) }
        var writePtr = 0
        val fb = params.feedback.coerceIn(0f, 0.9f)
        val damp = (1f - params.damping.coerceIn(0f, 0.92f)).coerceIn(0.05f, 1f)
        val dampState = FloatArray(channels)
        val mix = params.mix.coerceIn(0f, 1f)
        val dry = 1f - mix
        val sat = 1f + params.saturation.coerceIn(0f, 1f) * 3f
        val wowDepthSamples = params.wowFlutter.coerceIn(0f, 1f) * 0.0025f * sampleRate

        for (f in 0 until frames) {
            val wow = sin(2.0 * PI * 1.45 * f / sampleRate).toFloat() * wowDepthSamples
            val dSamples = (baseDelaySamples + wow).coerceAtLeast(1f)
            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                val delayed = readFractional(delayBuf[c], writePtr, dSamples)
                dampState[c] += damp * (delayed - dampState[c])
                val saturatedFb = if (params.saturation > 0.01f) {
                    tanh((x + dampState[c] * fb) * sat) / tanh(sat)
                } else {
                    x + dampState[c] * fb
                }
                delayBuf[c][writePtr] = saturatedFb.coerceIn(-1f, 1f)
                out[idx] = (x * dry + delayed * mix).coerceIn(-1f, 1f)
            }
            writePtr = (writePtr + 1) % delayBuf[0].size
        }
        return out
    }

    // 11. Delay (Digital delay with optional BPM sync, HP/LP feedback filters & stereo spread)
    fun applyDelay(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        params: DelayParams,
        bpm: Float = 124f
    ): FloatArray {
        if (!params.enabled || samples.isEmpty()) return samples
        val out = FloatArray(samples.size)
        val frames = samples.size / channels
        val effectiveDelayMs = if (params.bpmSync && params.division != DelayNoteDivision.FREE_MS) {
            ((60_000f / bpm.coerceIn(60f, 180f)) * params.division.quarterMultiplier).coerceIn(20f, 1500f)
        } else {
            params.delayMs.coerceIn(10f, 1500f)
        }
        val baseDelaySamples = ((effectiveDelayMs / 1000f) * sampleRate).toInt().coerceAtLeast(1)
        val secondaryDelaySamples = max(1, (baseDelaySamples * (1f - params.stereoSpread * 0.35f)).toInt())
        val delayBuf = Array(channels) { c ->
            FloatArray(if (c == 1) secondaryDelaySamples else baseDelaySamples)
        }
        val ptrs = IntArray(channels)
        val fb = params.feedback.coerceIn(0f, 0.92f)
        val mix = params.mix.coerceIn(0f, 1f)
        val dry = 1f - mix

        val lpAlpha = (1f - exp(-2f * PI.toFloat() * params.lowPassHz.coerceIn(800f, sampleRate * 0.45f) / sampleRate))
            .coerceIn(0.05f, 1f)
        val rcHp = 1f / (2f * PI.toFloat() * params.highPassHz.coerceIn(20f, 1500f))
        val hpAlpha = rcHp / (rcHp + 1f / sampleRate)
        val lpState = FloatArray(channels)
        val hpPrevIn = FloatArray(channels)
        val hpPrevOut = FloatArray(channels)

        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val idx = f * channels + c
                val x = samples[idx]
                val buf = delayBuf[c]
                val delayed = buf[ptrs[c]]
                val hp = hpAlpha * (hpPrevOut[c] + delayed - hpPrevIn[c])
                hpPrevIn[c] = delayed
                hpPrevOut[c] = hp
                lpState[c] += lpAlpha * (hp - lpState[c])

                val crossChannel = if (channels == 2) delayBuf[1 - c][ptrs[1 - c]] else delayed
                val feedbackSample = lpState[c] * (1f - params.stereoSpread * 0.5f) + crossChannel * (params.stereoSpread * 0.5f)
                buf[ptrs[c]] = (x + feedbackSample * fb).coerceIn(-1f, 1f)
                ptrs[c] = (ptrs[c] + 1) % buf.size
                out[idx] = (x * dry + delayed * mix).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    // 12. Reverb (Schroeder/Freeverb 4-comb + early reflections + low-cut + stereo width + 2 all-pass)
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
        val erAmount = params.earlyReflections.coerceIn(0f, 1f)

        val rcLc = 1f / (2f * PI.toFloat() * params.lowCutHz.coerceIn(20f, 1000f))
        val lcAlpha = rcLc / (rcLc + 1f / sampleRate)

        val wetChannels = Array(channels) { FloatArray(frames) }

        for (c in 0 until channels) {
            val preBuf = FloatArray(preDelaySamples)
            var prePtr = 0
            var lcPrevIn = 0f
            var lcPrevOut = 0f

            val tap1Len = max(8, (0.011f * sampleRate).toInt())
            val tap2Len = max(8, (0.023f * sampleRate).toInt())
            val erBuf = FloatArray(tap2Len + 2)
            var erPtr = 0

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
                val x = samples[f * channels + c]
                val lc = lcAlpha * (lcPrevOut + x - lcPrevIn)
                lcPrevIn = x
                lcPrevOut = lc

                val preOut = preBuf[prePtr]
                preBuf[prePtr] = lc
                prePtr = (prePtr + 1) % preDelaySamples

                erBuf[erPtr] = lc
                val er1 = erBuf[(erPtr - tap1Len + erBuf.size) % erBuf.size]
                val er2 = erBuf[(erPtr - tap2Len + erBuf.size) % erBuf.size]
                erPtr = (erPtr + 1) % erBuf.size
                val erSum = (er1 * 0.6f + er2 * 0.4f) * erAmount

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
                wetChannels[c][f] = (diffused + erSum * 0.5f).coerceIn(-1f, 1f)
            }
        }

        val width = params.stereoWidth.coerceIn(0f, 1.5f)
        for (f in 0 until frames) {
            if (channels == 2) {
                val wL = wetChannels[0][f]
                val wR = wetChannels[1][f]
                val mid = (wL + wR) * 0.5f
                val side = (wL - wR) * 0.5f * width
                out[f * 2] = (samples[f * 2] * dry + (mid + side) * mix).coerceIn(-1f, 1f)
                out[f * 2 + 1] = (samples[f * 2 + 1] * dry + (mid - side) * mix).coerceIn(-1f, 1f)
            } else {
                out[f] = (samples[f] * dry + wetChannels[0][f] * mix).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    internal fun readFractional(buffer: FloatArray, writePtr: Int, delaySamples: Float): Float {
        val size = buffer.size
        val readPos = (writePtr - delaySamples + size * 2) % size
        val i0 = readPos.toInt() % size
        val i1 = (i0 + 1) % size
        val frac = readPos - readPos.toInt()
        return buffer[i0] * (1f - frac) + buffer[i1] * frac
    }
}
