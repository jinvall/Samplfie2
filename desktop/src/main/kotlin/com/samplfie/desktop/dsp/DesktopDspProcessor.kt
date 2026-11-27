package com.samplfie.desktop.dsp

import com.samplfie.desktop.model.DesktopAudioBuffer
import com.samplfie.desktop.model.DesktopBiquadFilterMode
import com.samplfie.desktop.model.DesktopDelayDivision
import com.samplfie.desktop.model.DesktopDistortionCurve
import com.samplfie.desktop.model.DesktopFxState
import com.samplfie.desktop.model.DesktopStompState
import com.samplfie.desktop.model.DesktopWahMode
import com.samplfie.desktop.plugins.DesktopPluginDefinition
import com.samplfie.desktop.plugins.DesktopPluginHostEngine
import com.samplfie.desktop.plugins.DesktopPluginSlotInstance
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
 * Full-scope 14-module pure-Kotlin DSP chain + Stompbox Master Tilt/Kill/Wet processor + Plugin Chain
 * matching Samplfie2 Android's audio effects, Octave, Analog Delay, reverse delays, and per-effect levels.
 */
object DesktopDspProcessor {

    fun process(
        dry: DesktopAudioBuffer,
        fx: DesktopFxState,
        stomp: DesktopStompState,
        pluginChain: List<DesktopPluginSlotInstance> = emptyList(),
        pluginDefs: Map<String, DesktopPluginDefinition> = emptyMap(),
        bpm: Float = 124f
    ): DesktopAudioBuffer {
        if (dry.frameCount == 0 || fx.masterBypass) return dry

        var buf = dry
        val sr = buf.sampleRate.coerceAtLeast(8000)

        if (fx.noiseGateEnabled) {
            buf = applyNoiseGate(buf, fx, sr)
        }
        if (fx.pitchEnabled) {
            buf = applyPitchAndSpeed(buf, fx, sr)
        }
        if (fx.octaveEnabled) {
            buf = applyOctave(buf, fx, sr)
        }
        if (!stomp.engaged || !stomp.killDrive) {
            if (fx.gainEnabled) buf = applyGain(buf, fx)
            if (fx.overdriveEnabled) buf = applyOverdrive(buf, fx, sr)
            if (fx.distortionEnabled) buf = applyDistortion(buf, fx, sr)
        }
        if (!stomp.engaged || !stomp.killMod) {
            val mScale = if (stomp.engaged) stomp.modScale.coerceIn(0f, 1.8f) else 1f
            if (fx.filterEnabled) buf = applyBiquadFilter(buf, fx, mScale, sr)
            if (fx.wahEnabled) buf = applyWah(buf, fx, mScale, sr)
            if (fx.phaserEnabled) buf = applyPhaser(buf, fx, mScale, sr)
            if (fx.flangerEnabled) buf = applyFlanger(buf, fx, mScale, sr)
            if (fx.chorusEnabled) buf = applyChorus(buf, fx, mScale, sr)
            if (fx.rotaryEnabled) buf = applyRotary(buf, fx, mScale, sr)
        }
        if (!stomp.engaged || !stomp.killTime) {
            val sScale = if (stomp.engaged) stomp.spaceScale.coerceIn(0f, 1.8f) else 1f
            if (fx.echoEnabled) buf = applyEcho(buf, fx, sScale, sr)
            if (fx.delayEnabled) buf = applyDelay(buf, fx, sScale, sr, bpm)
            if (fx.analogDelayEnabled) buf = applyAnalogDelay(buf, fx, sScale, sr)
            if (fx.reverbEnabled) buf = applyReverb(buf, fx, sScale, sr)
        }
        if (stomp.engaged) {
            buf = applyStompMaster(dry, buf, stomp, sr)
        }
        if (pluginChain.any { it.enabled && !it.bypassed }) {
            buf = DesktopPluginHostEngine.processChain(buf, pluginChain, pluginDefs)
        }
        return buf
    }

    private fun applyNoiseGate(buf: DesktopAudioBuffer, fx: DesktopFxState, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val frames = buf.frameCount
        val out = FloatArray(buf.samples.size)
        val hpHz = fx.noiseHighPassHz.coerceIn(25f, 300f)
        val rc = 1f / (2f * PI.toFloat() * hpHz)
        val hpAlpha = rc / (rc + 1f / sr)
        val prevIn = FloatArray(ch)
        val prevHp = FloatArray(ch)

        val atkCoeff = exp(-1f / ((fx.noiseAttackMs.coerceIn(0.5f, 50f) / 1000f) * sr))
        val relCoeff = exp(-1f / ((fx.noiseReleaseMs.coerceIn(10f, 500f) / 1000f) * sr))
        val threshLin = 10f.pow(fx.noiseThresholdDb / 20f)
        val kneeRatio = 10f.pow(-fx.noiseKneeDb.coerceIn(0f, 18f) / 20f)
        val floorGain = 10f.pow(-fx.noiseReductionDb.coerceIn(0f, 60f) / 20f)
        val levelGain = fx.noiseGateLevel.coerceIn(0f, 2f)
        var env = 0f

        for (f in 0 until frames) {
            var power = 0f
            for (c in 0 until ch) {
                val idx = f * ch + c
                val x = buf.samples[idx]
                val hp = if (fx.noiseHighPassEnabled) {
                    val y = hpAlpha * (prevHp[c] + x - prevIn[c])
                    prevIn[c] = x
                    prevHp[c] = y
                    y
                } else x
                out[idx] = hp
                power += hp * hp
            }
            val rms = sqrt(power / ch)
            env = if (rms > env) atkCoeff * env + (1f - atkCoeff) * rms else relCoeff * env + (1f - relCoeff) * rms
            val lowerKnee = threshLin * kneeRatio
            val gain = when {
                env >= threshLin -> 1f
                env <= lowerKnee -> {
                    val r = (env / max(lowerKnee, 1e-6f)).coerceIn(0f, 1f)
                    floorGain + (1f - floorGain) * r * r * 0.1f
                }
                else -> {
                    val t = ((env - lowerKnee) / max(threshLin - lowerKnee, 1e-6f)).coerceIn(0f, 1f)
                    floorGain + (1f - floorGain) * (0.1f + 0.9f * t * t)
                }
            }
            for (c in 0 until ch) {
                out[f * ch + c] = (out[f * ch + c] * gain * levelGain).coerceIn(-1f, 1f)
            }
        }
        return buf.copy(samples = out)
    }

    private fun applyPitchAndSpeed(buf: DesktopAudioBuffer, fx: DesktopFxState, sr: Int): DesktopAudioBuffer {
        val speed = fx.speedFactor.coerceIn(0.5f, 2.0f)
        val totalSemis = (fx.pitchSemitones + fx.pitchFineCents.coerceIn(-50f, 50f) / 100f).coerceIn(-12.5f, 12.5f)
        val pitchRatio = if (fx.pitchVinylLink) speed else 2.0f.pow(totalSemis / 12.0f)
        val levelGain = fx.pitchLevel.coerceIn(0f, 2f)

        var working = buf.samples
        if (abs(speed - 1f) > 0.005f || abs(pitchRatio - 1f) > 0.005f) {
            val stretchFactor = if (fx.pitchVinylLink) 1f else (pitchRatio / speed).coerceIn(0.25f, 4f)
            if (abs(stretchFactor - 1f) > 0.005f) {
                working = wsolaStretch(working, sr, buf.channels, stretchFactor, fx.pitchGrainMs)
            }
            if (abs(pitchRatio - 1f) > 0.005f) {
                working = resampleByRatio(working, buf.channels, pitchRatio)
            }
        }
        if (abs(levelGain - 1f) >= 0.002f) {
            working = FloatArray(working.size) { i -> (working[i] * levelGain).coerceIn(-1f, 1f) }
        }
        return buf.copy(samples = working)
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
        val windowSize = (sampleRate * (grainSizeMs.coerceIn(14f, 64f) / 1000f)).toInt().coerceIn(256, max(256, inFrames / 2))
        val hopOut = windowSize / 2
        val hopIn = max(1, (hopOut / stretchFactor).toInt())
        val searchRadius = (sampleRate * 0.006f).toInt().coerceIn(8, windowSize / 3)
        val outFrames = max(windowSize, (inFrames * stretchFactor).toInt())
        val out = FloatArray(outFrames * channels)
        val norm = FloatArray(outFrames)
        val hann = FloatArray(windowSize) { i -> (0.5 * (1.0 - cos(2.0 * PI * i / (windowSize - 1)))).toFloat() }
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
                        corr += out[(outPos + k) * channels] * samples[(cand + k) * channels]
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
                for (c in 0 until channels) out[(outPos + k) * channels + c] += samples[srcFrame * channels + c] * w
            }
            inPos += hopIn
            outPos += hopOut
        }
        for (f in 0 until outFrames) {
            val n = norm[f]
            if (n > 1e-4f) {
                val inv = 1f / n
                for (c in 0 until channels) out[f * channels + c] = (out[f * channels + c] * inv).coerceIn(-1f, 1f)
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
                val frac = (pos - idx).toFloat()
                val a = samples[idx.coerceIn(0, inFrames - 1) * channels + c]
                val b = samples[(idx + 1).coerceIn(0, inFrames - 1) * channels + c]
                out[i * channels + c] = (a + (b - a) * frac).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    private fun applyGain(buf: DesktopAudioBuffer, fx: DesktopFxState): DesktopAudioBuffer {
        val out = FloatArray(buf.samples.size)
        val ceilingLin = 10f.pow(fx.limiterCeilingDb.coerceIn(-6f, -0.1f) / 20f)
        var normScale = 1f
        if (fx.normalize) {
            val peak = buf.peakLevel()
            if (peak > 1e-5f) normScale = ceilingLin / peak
        }
        val gainLin = 10f.pow(fx.gainDb.coerceIn(-24f, 24f) / 20f) * normScale
        val compRatio = fx.compressionRatio.coerceIn(1f, 8f)
        val compThresh = 0.5f
        for (i in buf.samples.indices) {
            var y = buf.samples[i] * gainLin
            if (compRatio > 1.05f && abs(y) > compThresh) {
                val s = if (y >= 0f) 1f else -1f
                y = s * (compThresh + (abs(y) - compThresh) / compRatio)
            }
            if (fx.softLimiter && abs(y) > ceilingLin * 0.9f) {
                val s = if (y >= 0f) 1f else -1f
                val knee = ceilingLin * 0.9f
                val head = (ceilingLin - knee).coerceAtLeast(0.02f)
                y = s * (knee + head * tanh((abs(y) - knee) / head))
            }
            out[i] = y.coerceIn(-1f, 1f)
        }
        if (buf.channels == 2 && abs(fx.stereoWidth - 1f) > 0.01f) {
            val w = fx.stereoWidth.coerceIn(0f, 2f)
            for (f in 0 until buf.frameCount) {
                val l = out[f * 2]
                val r = out[f * 2 + 1]
                val m = (l + r) * 0.5f
                val s = (l - r) * 0.5f * w
                out[f * 2] = (m + s).coerceIn(-1f, 1f)
                out[f * 2 + 1] = (m - s).coerceIn(-1f, 1f)
            }
        }
        val levelGain = fx.gainLevel.coerceIn(0f, 2f)
        if (abs(levelGain - 1f) >= 0.002f) {
            for (i in out.indices) out[i] = (out[i] * levelGain).coerceIn(-1f, 1f)
        }
        return buf.copy(samples = out)
    }

    private fun applyOctave(buf: DesktopAudioBuffer, fx: DesktopFxState, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val frames = buf.frameCount
        val out = FloatArray(buf.samples.size)
        val dryMix = fx.octaveDryMix.coerceIn(0f, 1f)
        val subMix = fx.octaveSubMix.coerceIn(0f, 1f)
        val upMix = fx.octaveUpMix.coerceIn(0f, 1f)
        val levelGain = fx.octaveLevel.coerceIn(0f, 2f)

        val subToneAlpha = (1f - exp(-2f * PI.toFloat() * fx.octaveSubToneHz.coerceIn(250f, 4000f) / sr)).coerceIn(0.01f, 0.99f)
        val dcAlpha = exp(-2f * PI.toFloat() * 35f / sr).coerceIn(0.8f, 0.9999f)
        val atk = exp(-1f / (0.003f * sr))
        val rel = exp(-1f / (0.045f * sr))

        val prev = FloatArray(ch)
        val flip = FloatArray(ch) { 1f }
        val subLp = FloatArray(ch)
        val env = FloatArray(ch)
        val upPrevIn = FloatArray(ch)
        val upDc = FloatArray(ch)

        for (f in 0 until frames) {
            for (c in 0 until ch) {
                val idx = f * ch + c
                val x = buf.samples[idx]
                val ax = abs(x)
                env[c] = if (ax > env[c]) atk * env[c] + (1f - atk) * ax else rel * env[c] + (1f - rel) * ax
                if (prev[c] <= 0.0005f && x > 0.0005f) flip[c] = -flip[c]
                prev[c] = x
                subLp[c] += subToneAlpha * (flip[c] * env[c] * 1.45f - subLp[c])
                val rect = abs(x)
                val upHp = dcAlpha * (upDc[c] + rect - upPrevIn[c])
                upPrevIn[c] = rect
                upDc[c] = upHp
                val octUp = tanh(upHp * 1.8f)
                out[idx] = ((x * dryMix + subLp[c] * subMix + octUp * upMix) * levelGain).coerceIn(-1f, 1f)
            }
        }
        return buf.copy(samples = out)
    }

    private fun applyOverdrive(buf: DesktopAudioBuffer, fx: DesktopFxState, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = FloatArray(buf.samples.size)
        val drive = fx.driveAmount.coerceIn(1f, 20f)
        val mix = fx.overdriveMix.coerceIn(0f, 1f)
        val dry = 1f - mix
        val bias = fx.overdriveAsymmetry.coerceIn(0f, 0.8f) * 0.4f
        val norm = 1f / max(0.35f, tanh(drive * 0.7f))
        val fc = fx.overdriveToneHz.coerceIn(400f, sr * 0.45f)
        val lpAlpha = (1f - exp(-2f * PI.toFloat() * fc / sr)).coerceIn(0.01f, 1f)
        val lpState = FloatArray(ch)
        val rc = 1f / (2f * PI.toFloat() * fx.overdrivePreHpHz.coerceIn(20f, 800f))
        val hpAlpha = rc / (rc + 1f / sr)
        val hpPrevIn = FloatArray(ch)
        val hpPrevOut = FloatArray(ch)
        val levelGain = fx.overdriveLevel.coerceIn(0f, 2f)

        for (f in 0 until buf.frameCount) {
            for (c in 0 until ch) {
                val idx = f * ch + c
                val x = buf.samples[idx]
                val pre = hpAlpha * (hpPrevOut[c] + x - hpPrevIn[c])
                hpPrevIn[c] = x
                hpPrevOut[c] = pre
                val shaped = (tanh(pre * drive + bias) - tanh(bias)) * norm
                lpState[c] += lpAlpha * (shaped - lpState[c])
                out[idx] = ((x * dry + lpState[c] * mix) * levelGain).coerceIn(-1f, 1f)
            }
        }
        return buf.copy(samples = out)
    }

    private fun applyDistortion(buf: DesktopAudioBuffer, fx: DesktopFxState, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = FloatArray(buf.samples.size)
        val drive = fx.distortionDrive.coerceIn(1f, 24f)
        val mix = fx.distortionMix.coerceIn(0f, 1f)
        val dry = 1f - mix
        val sym = fx.distortionSymmetry.coerceIn(0f, 1f) * 0.45f
        val fc = fx.distortionToneHz.coerceIn(450f, sr * 0.45f)
        val lpAlpha = (1f - exp(-2f * PI.toFloat() * fc / sr)).coerceIn(0.01f, 1f)
        val lp = FloatArray(ch)
        val levelGain = fx.distortionLevel.coerceIn(0f, 2f)

        for (f in 0 until buf.frameCount) {
            for (c in 0 until ch) {
                val idx = f * ch + c
                val x = buf.samples[idx]
                val pre = x * drive + sym * 0.35f
                val shaped = when (fx.distortionCurve) {
                    DesktopDistortionCurve.SOFT_CLIP -> tanh(pre * 0.85f)
                    DesktopDistortionCurve.HARD_CLIP -> pre.coerceIn(-0.82f, 0.82f) / 0.82f
                    DesktopDistortionCurve.TUBE_ASYM -> {
                        val pos = if (pre >= 0f) tanh(pre * 1.15f) else tanh(pre * 0.65f)
                        (pos - tanh(sym * 0.35f)).coerceIn(-1f, 1f)
                    }
                    DesktopDistortionCurve.WAVEFOLD -> sin(pre * 1.35f).coerceIn(-1f, 1f)
                    DesktopDistortionCurve.BIT_CRUSH -> {
                        val steps = max(4f, 36f - drive * 1.25f)
                        (kotlin.math.round(pre.coerceIn(-1.2f, 1.2f) * steps) / steps).coerceIn(-1f, 1f)
                    }
                    DesktopDistortionCurve.FUZZ -> {
                        val hard = (pre * 1.4f).coerceIn(-1f, 1f)
                        val sign = if (hard >= 0f) 1f else -1f
                        sign * (1f - exp(-abs(hard) * 3.2f))
                    }
                }
                lp[c] += lpAlpha * (shaped - lp[c])
                out[idx] = ((x * dry + lp[c] * mix) * levelGain).coerceIn(-1f, 1f)
            }
        }
        return buf.copy(samples = out)
    }

    private fun applyBiquadFilter(buf: DesktopAudioBuffer, fx: DesktopFxState, mScale: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = FloatArray(buf.samples.size)
        val mix = (fx.filterMix * mScale).coerceIn(0f, 1f)
        val dry = 1f - mix
        val qInv = 1f / fx.filterResonanceQ.coerceIn(0.5f, 12f)
        val drive = fx.filterDrive.coerceIn(0.8f, 5f)
        val levelGain = fx.filterLevel.coerceIn(0f, 2f)
        val low = FloatArray(ch)
        val band = FloatArray(ch)
        val low2 = FloatArray(ch)
        val band2 = FloatArray(ch)

        for (f in 0 until buf.frameCount) {
            val lfo = if (fx.filterLfoDepth > 0.01f && fx.filterLfoRateHz > 0.01f) {
                sin(2.0 * PI * fx.filterLfoRateHz * f / sr).toFloat() * fx.filterLfoDepth.coerceIn(0f, 1f)
            } else 0f
            val baseCutoff = fx.filterCutoffHz.coerceIn(45f, sr * 0.42f)
            val modCutoff = (baseCutoff * (1f + lfo * 0.75f)).coerceIn(45f, sr * 0.42f)
            val fCoef = (2f * sin(PI.toFloat() * modCutoff / sr)).coerceIn(0.002f, 0.92f)
            val fCoef2 = (2f * sin(PI.toFloat() * (modCutoff * 1.48f).coerceIn(120f, sr * 0.44f) / sr)).coerceIn(0.002f, 0.92f)

            for (c in 0 until ch) {
                val idx = f * ch + c
                val raw = buf.samples[idx]
                val x = if (drive > 1.05f) tanh(raw * drive) / tanh(drive) else raw
                low[c] += fCoef * band[c]
                val high = x - low[c] - qInv * band[c]
                band[c] += fCoef * high

                val filtered = when (fx.filterMode) {
                    DesktopBiquadFilterMode.LOW_PASS -> low[c]
                    DesktopBiquadFilterMode.HIGH_PASS -> high
                    DesktopBiquadFilterMode.BAND_PASS -> band[c] * 1.35f
                    DesktopBiquadFilterMode.NOTCH -> low[c] + high
                    DesktopBiquadFilterMode.PEAKING -> x + band[c] * 0.85f
                    DesktopBiquadFilterMode.FORMANT -> {
                        low2[c] += fCoef2 * band2[c]
                        val hp2 = x - low2[c] - qInv * band2[c]
                        band2[c] += fCoef2 * hp2
                        (band[c] * 0.75f + band2[c] * 0.65f + x * 0.25f)
                    }
                }.coerceIn(-1f, 1f)
                out[idx] = ((raw * dry + filtered * mix) * levelGain).coerceIn(-1f, 1f)
            }
        }
        return buf.copy(samples = out)
    }

    private fun applyWah(buf: DesktopAudioBuffer, fx: DesktopFxState, mScale: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = FloatArray(buf.samples.size)
        val mix = (fx.wahMix * mScale).coerceIn(0f, 1f)
        val dry = 1f - mix
        val qInv = 1f / fx.wahResonanceQ.coerceIn(1.2f, 10f)
        val low = FloatArray(ch)
        val band = FloatArray(ch)
        val atk = exp(-1f / (0.004f * sr))
        val rel = exp(-1f / (0.08f * sr))
        val levelGain = fx.wahLevel.coerceIn(0f, 2f)
        var env = 0f

        for (f in 0 until buf.frameCount) {
            var monoAbs = 0f
            for (c in 0 until ch) monoAbs += abs(buf.samples[f * ch + c])
            monoAbs /= ch
            env = if (monoAbs > env) atk * env + (1f - atk) * monoAbs else rel * env + (1f - rel) * monoAbs
            val lfo = 0.5f + 0.5f * sin(2.0 * PI * fx.wahRateHz * f / sr).toFloat()
            val modFreq = when (fx.wahMode) {
                DesktopWahMode.AUTO_ENV -> fx.wahFreqHz * (0.45f + 1.15f * lfo + env * fx.wahEnvSens * 2.4f)
                DesktopWahMode.LFO_SWEEP -> fx.wahFreqHz * (0.4f + 1.6f * lfo)
                DesktopWahMode.MANUAL_PEDAL -> fx.wahFreqHz * (0.35f + 1.85f * fx.wahPedalPos.coerceIn(0f, 1f))
            }.coerceIn(180f, sr * 0.36f)
            val fCoef = (2f * sin(PI.toFloat() * modFreq / sr)).coerceIn(0.001f, 0.95f)
            for (c in 0 until ch) {
                val idx = f * ch + c
                val x = buf.samples[idx]
                repeat(2) {
                    low[c] += fCoef * 0.5f * band[c]
                    val high = x - low[c] - qInv * band[c]
                    band[c] += fCoef * 0.5f * high
                }
                val wet = (band[c] * 1.4f).coerceIn(-1f, 1f)
                out[idx] = ((x * dry + wet * mix) * levelGain).coerceIn(-1f, 1f)
            }
        }
        return buf.copy(samples = out)
    }

    private fun applyPhaser(buf: DesktopAudioBuffer, fx: DesktopFxState, mScale: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = FloatArray(buf.samples.size)
        val mix = (fx.phaserMix * mScale).coerceIn(0f, 1f)
        val dry = 1f - mix
        val fb = fx.phaserFeedback.coerceIn(0f, 0.9f)
        val stages = fx.phaserStages.coerceIn(2, 8)
        val ap = Array(ch) { FloatArray(stages) }
        val lastOut = FloatArray(ch)
        val center = fx.phaserCenterHz.coerceIn(250f, 3200f)
        val levelGain = fx.phaserLevel.coerceIn(0f, 2f)

        for (f in 0 until buf.frameCount) {
            for (c in 0 until ch) {
                val chPhase = if (ch == 2 && c == 1) fx.phaserStereoOffset * PI.toFloat() else 0f
                val lfo = 0.5f + 0.5f * sin(2.0 * PI * fx.phaserRateHz * f / sr + chPhase).toFloat()
                val sweepHz = (center * 0.25f + (lfo * fx.phaserDepth) * center * 2.4f).coerceIn(100f, sr * 0.4f)
                val tanVal = tan(PI.toFloat() * sweepHz / sr)
                val coeff = (1f - tanVal) / (1f + tanVal)
                val idx = f * ch + c
                val x = buf.samples[idx]
                var sig = x + lastOut[c] * fb
                for (s in 0 until stages) {
                    val y = -coeff * sig + ap[c][s]
                    ap[c][s] = sig + coeff * y
                    sig = y
                }
                lastOut[c] = sig.coerceIn(-1f, 1f)
                out[idx] = ((x * dry + lastOut[c] * mix) * levelGain).coerceIn(-1f, 1f)
            }
        }
        return buf.copy(samples = out)
    }

    private fun applyFlanger(buf: DesktopAudioBuffer, fx: DesktopFxState, mScale: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = FloatArray(buf.samples.size)
        val manualMs = fx.flangerManualMs.coerceIn(0.2f, 5f)
        val maxDelay = (((manualMs + fx.flangerDepthMs + 2f) / 1000f) * sr).toInt().coerceAtLeast(32)
        val line = Array(ch) { FloatArray(maxDelay + 4) }
        var wp = 0
        val mix = (fx.flangerMix * mScale).coerceIn(0f, 1f)
        val dry = 1f - mix
        val fb = fx.flangerFeedback.coerceIn(-0.88f, 0.88f)
        val spread = fx.flangerSpread.coerceIn(0f, 1f) * PI.toFloat()
        val levelGain = fx.flangerLevel.coerceIn(0f, 2f)

        for (f in 0 until buf.frameCount) {
            for (c in 0 until ch) {
                val chPhase = if (ch == 2 && c == 1) spread else 0f
                val lfo = 0.5f + 0.5f * sin(2.0 * PI * fx.flangerRateHz * f / sr + chPhase).toFloat()
                val dSamples = ((manualMs + fx.flangerDepthMs * lfo) / 1000f) * sr
                val idx = f * ch + c
                val x = buf.samples[idx]
                val delayed = readFractional(line[c], wp, dSamples)
                line[c][wp] = (x + delayed * fb).coerceIn(-1f, 1f)
                out[idx] = ((x * dry + delayed * mix) * levelGain).coerceIn(-1f, 1f)
            }
            wp = (wp + 1) % line[0].size
        }
        return buf.copy(samples = out)
    }

    private fun applyChorus(buf: DesktopAudioBuffer, fx: DesktopFxState, mScale: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = FloatArray(buf.samples.size)
        val voices = fx.chorusVoices.coerceIn(2, 4)
        val baseMs = fx.chorusBaseMs.coerceIn(6f, 28f)
        val maxDelay = (((baseMs + 14f + fx.chorusDepthMs) / 1000f) * sr).toInt().coerceAtLeast(64)
        val line = Array(ch) { FloatArray(maxDelay + 4) }
        var wp = 0
        val mix = (fx.chorusMix * mScale).coerceIn(0f, 1f)
        val dry = 1f - mix
        val lpAlpha = (1f - exp(-2f * PI.toFloat() * fx.chorusHighCutHz.coerceIn(1500f, sr * 0.45f) / sr)).coerceIn(0.05f, 1f)
        val lp = FloatArray(ch)
        val levelGain = fx.chorusLevel.coerceIn(0f, 2f)

        for (f in 0 until buf.frameCount) {
            val basePhase = (2.0 * PI * fx.chorusRateHz * f / sr).toFloat()
            for (c in 0 until ch) {
                val idx = f * ch + c
                val x = buf.samples[idx]
                line[c][wp] = x
                var wet = 0f
                for (v in 0 until voices) {
                    val lfo = 0.5f + 0.5f * sin(basePhase + (2.0 * PI * v / voices).toFloat() + c * 0.65f)
                    val dSamples = ((baseMs + v * 5.5f + fx.chorusDepthMs * lfo) / 1000f) * sr
                    wet += readFractional(line[c], wp, dSamples)
                }
                wet /= voices.toFloat()
                lp[c] += lpAlpha * (wet - lp[c])
                out[idx] = ((x * dry + lp[c] * mix) * levelGain).coerceIn(-1f, 1f)
            }
            wp = (wp + 1) % line[0].size
        }
        return buf.copy(samples = out)
    }

    private fun applyRotary(buf: DesktopAudioBuffer, fx: DesktopFxState, mScale: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = FloatArray(buf.samples.size)
        val mix = (fx.rotaryMix * mScale).coerceIn(0f, 1f)
        val dry = 1f - mix
        val lpAlpha = (1f - exp(-2f * PI.toFloat() * fx.rotaryCrossoverHz.coerceIn(350f, 1800f) / sr)).coerceIn(0.01f, 1f)
        val lp = FloatArray(ch)
        val effectiveRate = when {
            fx.rotaryBrake -> 0.08f
            fx.rotaryFastMode -> max(fx.rotaryRateHz, 6.2f)
            else -> fx.rotaryRateHz
        }
        val hornBuf = Array(ch) { FloatArray((0.004f * sr).toInt().coerceAtLeast(32) + 4) }
        var wp = 0
        val drive = 1f + fx.rotaryCabinetDrive.coerceIn(0f, 1f) * 3.5f
        val levelGain = fx.rotaryLevel.coerceIn(0f, 2f)

        for (f in 0 until buf.frameCount) {
            val hornPhase = (2.0 * PI * effectiveRate * f / sr).toFloat()
            val drumPhase = (2.0 * PI * (effectiveRate * 0.72f) * f / sr).toFloat()
            for (c in 0 until ch) {
                val idx = f * ch + c
                val raw = buf.samples[idx]
                val x = if (fx.rotaryCabinetDrive > 0.02f) tanh(raw * drive) / tanh(drive) else raw
                lp[c] += lpAlpha * (x - lp[c])
                val lowDrum = lp[c]
                val highHorn = x - lowDrum
                val chOff = if (ch == 2 && c == 1) (PI / 2.0).toFloat() else 0f
                val hornAm = 1f - fx.rotaryDepth * 0.42f * (0.5f + 0.5f * cos(hornPhase + chOff))
                val drumAm = 1f - fx.rotaryDepth * 0.30f * (0.5f + 0.5f * sin(drumPhase + chOff))
                hornBuf[c][wp] = highHorn
                val dDelay = (0.0014f + 0.0012f * fx.rotaryDepth * sin(hornPhase + chOff)) * sr
                val dopplerHorn = readFractional(hornBuf[c], wp, dDelay.coerceAtLeast(1f))
                val wet = dopplerHorn * hornAm * fx.rotaryHornBalance * 1.4f +
                    lowDrum * drumAm * (1f - fx.rotaryHornBalance) * 1.4f
                out[idx] = ((raw * dry + wet * mix) * levelGain).coerceIn(-1f, 1f)
            }
            wp = (wp + 1) % hornBuf[0].size
        }
        return buf.copy(samples = out)
    }

    private fun applyEcho(buf: DesktopAudioBuffer, fx: DesktopFxState, sScale: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = FloatArray(buf.samples.size)
        val baseDelaySamples = ((fx.echoDelayMs.coerceIn(20f, 1500f) / 1000f) * sr).coerceAtLeast(8f)
        val maxSamples = (baseDelaySamples + 0.008f * sr).toInt().coerceAtLeast(32)
        val delayBuf = Array(ch) { FloatArray(maxSamples + 4) }
        val revLines = if (fx.echoReverseDelay) Array(ch) { ReverseDelayLine(baseDelaySamples.toInt()) } else null
        var wp = 0
        val fb = fx.echoFeedback.coerceIn(0f, 0.9f)
        val damp = (1f - fx.echoDamping.coerceIn(0f, 0.92f)).coerceIn(0.05f, 1f)
        val dampState = FloatArray(ch)
        val mix = (fx.echoMix * sScale).coerceIn(0f, 1f)
        val dry = 1f - mix
        val levelGain = fx.echoLevel.coerceIn(0f, 2f)
        val sat = 1f + fx.echoSaturation.coerceIn(0f, 1f) * 3f
        val wowSamples = fx.echoWowFlutter.coerceIn(0f, 1f) * 0.0025f * sr

        for (f in 0 until buf.frameCount) {
            val wow = sin(2.0 * PI * 1.45 * f / sr).toFloat() * wowSamples
            val dSamples = (baseDelaySamples + wow).coerceAtLeast(1f)
            for (c in 0 until ch) {
                val idx = f * ch + c
                val x = buf.samples[idx]
                val delayed = if (revLines != null) revLines[c].readReversed() else readFractional(delayBuf[c], wp, dSamples)
                dampState[c] += damp * (delayed - dampState[c])
                val toWrite = (if (fx.echoSaturation > 0.01f) {
                    tanh((x + dampState[c] * fb) * sat) / tanh(sat)
                } else {
                    x + dampState[c] * fb
                }).coerceIn(-1f, 1f)
                if (revLines != null) revLines[c].push(toWrite) else delayBuf[c][wp] = toWrite
                out[idx] = ((x * dry + delayed * mix) * levelGain).coerceIn(-1f, 1f)
            }
            wp = (wp + 1) % delayBuf[0].size
        }
        return buf.copy(samples = out)
    }

    private fun applyDelay(buf: DesktopAudioBuffer, fx: DesktopFxState, sScale: Float, sr: Int, bpm: Float): DesktopAudioBuffer {
        val ch = buf.channels
        val out = FloatArray(buf.samples.size)
        val effectiveMs = if (fx.delayBpmSync && fx.delayDivision != DesktopDelayDivision.FREE_MS) {
            ((60_000f / bpm.coerceIn(60f, 180f)) * fx.delayDivision.quarterMultiplier).coerceIn(20f, 1500f)
        } else {
            fx.delayTimeMs.coerceIn(10f, 1500f)
        }
        val baseDelaySamples = ((effectiveMs / 1000f) * sr).toInt().coerceAtLeast(1)
        val secondarySamples = max(1, (baseDelaySamples * (1f - fx.delaySpread * 0.35f)).toInt())
        val delayBuf = Array(ch) { c -> FloatArray(if (c == 1) secondarySamples else baseDelaySamples) }
        val revLines = if (fx.delayReverseDelay) {
            Array(ch) { c -> ReverseDelayLine(if (c == 1) secondarySamples else baseDelaySamples) }
        } else null
        val ptrs = IntArray(ch)
        val fb = fx.delayFeedback.coerceIn(0f, 0.92f)
        val mix = (fx.delayMix * sScale).coerceIn(0f, 1f)
        val dry = 1f - mix
        val levelGain = fx.delayLevel.coerceIn(0f, 2f)

        val lpAlpha = (1f - exp(-2f * PI.toFloat() * fx.delayLowPassHz.coerceIn(800f, sr * 0.45f) / sr)).coerceIn(0.05f, 1f)
        val rcHp = 1f / (2f * PI.toFloat() * fx.delayHighPassHz.coerceIn(20f, 1500f))
        val hpAlpha = rcHp / (rcHp + 1f / sr)
        val lpState = FloatArray(ch)
        val hpPrevIn = FloatArray(ch)
        val hpPrevOut = FloatArray(ch)
        val lastDelayed = FloatArray(ch)

        for (f in 0 until buf.frameCount) {
            for (c in 0 until ch) {
                lastDelayed[c] = if (revLines != null) revLines[c].readReversed() else delayBuf[c][ptrs[c]]
            }
            for (c in 0 until ch) {
                val idx = f * ch + c
                val x = buf.samples[idx]
                val delayed = lastDelayed[c]
                val hp = hpAlpha * (hpPrevOut[c] + delayed - hpPrevIn[c])
                hpPrevIn[c] = delayed
                hpPrevOut[c] = hp
                lpState[c] += lpAlpha * (hp - lpState[c])
                val cross = if (ch == 2) lastDelayed[1 - c] else delayed
                val fbSample = (x + (lpState[c] * (1f - fx.delaySpread * 0.5f) + cross * (fx.delaySpread * 0.5f)) * fb).coerceIn(-1f, 1f)
                if (revLines != null) {
                    revLines[c].push(fbSample)
                } else {
                    delayBuf[c][ptrs[c]] = fbSample
                    ptrs[c] = (ptrs[c] + 1) % delayBuf[c].size
                }
                out[idx] = ((x * dry + delayed * mix) * levelGain).coerceIn(-1f, 1f)
            }
        }
        return buf.copy(samples = out)
    }

    private fun applyAnalogDelay(buf: DesktopAudioBuffer, fx: DesktopFxState, sScale: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = FloatArray(buf.samples.size)
        val baseSamples = ((fx.analogDelayMs.coerceIn(20f, 1200f) / 1000f) * sr).coerceAtLeast(32f)
        val maxSamples = (baseSamples + 0.012f * sr).toInt().coerceAtLeast(64)
        val delayBuf = Array(ch) { FloatArray(maxSamples + 4) }
        val revLines = if (fx.analogDelayReverseDelay) Array(ch) { ReverseDelayLine(baseSamples.toInt()) } else null
        var wp = 0
        val fb = fx.analogDelayFeedback.coerceIn(0f, 0.92f)
        val mix = (fx.analogDelayMix * sScale).coerceIn(0f, 1f)
        val dry = 1f - mix
        val levelGain = fx.analogDelayLevel.coerceIn(0f, 2f)
        val sat = 1f + fx.analogDelaySaturation.coerceIn(0f, 1f) * 3.8f
        val satNorm = 1f / max(0.25f, tanh(sat * 0.7f))
        val modRate = fx.analogDelayModRateHz.coerceIn(0.1f, 6f)
        val modSamples = fx.analogDelayModDepth.coerceIn(0f, 1f) * 0.0045f * sr
        val warmthAlpha = (1f - exp(-2f * PI.toFloat() * fx.analogDelayWarmthHz.coerceIn(500f, 6500f) / sr)).coerceIn(0.03f, 0.99f)
        val lpState = FloatArray(ch)

        for (f in 0 until buf.frameCount) {
            val lfo = sin(2.0 * PI * modRate * f / sr).toFloat()
            val dSamples = (baseSamples + lfo * modSamples).coerceAtLeast(1f)
            for (c in 0 until ch) {
                val idx = f * ch + c
                val x = buf.samples[idx]
                val rawDelayed = if (revLines != null) revLines[c].readReversed() else readFractional(delayBuf[c], wp, dSamples)
                lpState[c] += warmthAlpha * (rawDelayed - lpState[c])
                val warmDelayed = tanh(lpState[c] * sat) * satNorm
                val toWrite = (x + warmDelayed * fb).coerceIn(-1f, 1f)
                if (revLines != null) revLines[c].push(toWrite) else delayBuf[c][wp] = toWrite
                out[idx] = ((x * dry + warmDelayed * mix) * levelGain).coerceIn(-1f, 1f)
            }
            wp = (wp + 1) % delayBuf[0].size
        }
        return buf.copy(samples = out)
    }

    private fun applyReverb(buf: DesktopAudioBuffer, fx: DesktopFxState, sScale: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val frames = buf.frameCount
        val out = FloatArray(buf.samples.size)
        val scale = sr / 44100f
        val preDelaySamples = max(1, (fx.reverbPreDelayMs / 1000f * sr).toInt())
        val combBase = intArrayOf(1557, 1617, 1491, 1422)
        val apBase = intArrayOf(556, 441)
        val fb = 0.72f + fx.reverbSize.coerceIn(0f, 1f) * 0.23f
        val damp = fx.reverbDamping.coerceIn(0.05f, 0.9f)
        val mix = (fx.reverbMix * sScale).coerceIn(0f, 1f)
        val dry = 1f - mix
        val erAmount = fx.reverbEarlyReflections.coerceIn(0f, 1f)
        val rcLc = 1f / (2f * PI.toFloat() * fx.reverbLowCutHz.coerceIn(20f, 1000f))
        val lcAlpha = rcLc / (rcLc + 1f / sr)
        val wetChannels = Array(ch) { FloatArray(frames) }

        for (c in 0 until ch) {
            val preBuf = FloatArray(preDelaySamples)
            var prePtr = 0
            var lcPrevIn = 0f
            var lcPrevOut = 0f
            val tap1Len = max(8, (0.011f * sr).toInt())
            val tap2Len = max(8, (0.023f * sr).toInt())
            val erBuf = FloatArray(tap2Len + 2)
            var erPtr = 0
            val combs = combBase.map { len -> FloatArray(max(8, (len * scale + c * 23).toInt())) }
            val combPtrs = IntArray(combs.size)
            val combFilters = FloatArray(combs.size)
            val allpasses = apBase.map { len -> FloatArray(max(8, (len * scale + c * 17).toInt())) }
            val apPtrs = IntArray(allpasses.size)

            for (f in 0 until frames) {
                val x = buf.samples[f * ch + c]
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
                    val line = combs[i]
                    val ptr = combPtrs[i]
                    val y = line[ptr]
                    combFilters[i] = y * (1f - damp) + combFilters[i] * damp
                    line[ptr] = (preOut + combFilters[i] * fb).coerceIn(-1f, 1f)
                    combPtrs[i] = (ptr + 1) % line.size
                    combSum += y
                }
                var diffused = combSum * 0.25f
                for (i in allpasses.indices) {
                    val line = allpasses[i]
                    val ptr = apPtrs[i]
                    val bufOut = line[ptr]
                    val y = -diffused + bufOut
                    line[ptr] = (diffused + bufOut * 0.5f).coerceIn(-1f, 1f)
                    apPtrs[i] = (ptr + 1) % line.size
                    diffused = y
                }
                wetChannels[c][f] = (diffused + erSum * 0.5f).coerceIn(-1f, 1f)
            }
        }
        val width = fx.reverbStereoWidth.coerceIn(0f, 1.5f)
        val levelGain = fx.reverbLevel.coerceIn(0f, 2f)
        for (f in 0 until frames) {
            if (ch == 2) {
                val wL = wetChannels[0][f]
                val wR = wetChannels[1][f]
                val mid = (wL + wR) * 0.5f
                val side = (wL - wR) * 0.5f * width
                out[f * 2] = ((buf.samples[f * 2] * dry + (mid + side) * mix) * levelGain).coerceIn(-1f, 1f)
                out[f * 2 + 1] = ((buf.samples[f * 2 + 1] * dry + (mid - side) * mix) * levelGain).coerceIn(-1f, 1f)
            } else {
                out[f] = ((buf.samples[f] * dry + wetChannels[0][f] * mix) * levelGain).coerceIn(-1f, 1f)
            }
        }
        return buf.copy(samples = out)
    }

    private fun applyStompMaster(
        dry: DesktopAudioBuffer,
        wet: DesktopAudioBuffer,
        stomp: DesktopStompState,
        sr: Int
    ): DesktopAudioBuffer {
        val trim = 10.0.pow(stomp.dynamicsTrimDb / 20.0).toFloat()
        val tilt = stomp.toneTilt.coerceIn(-1f, 1f)
        val mix = stomp.masterWetMix.coerceIn(0f, 1f)
        val ch = wet.channels
        val out = FloatArray(wet.samples.size)
        val alpha = exp(-2.0 * PI * 900.0 / sr).toFloat()
        val lp = FloatArray(ch)
        for (f in 0 until wet.frameCount) {
            for (c in 0 until ch) {
                val idx = f * ch + c
                val wetS = wet.samples[idx]
                val dryS = if (idx < dry.samples.size) dry.samples[idx] else wetS
                val blended = dryS * (1f - mix) + wetS * mix
                lp[c] = lp[c] * alpha + blended * (1f - alpha)
                val low = lp[c]
                val high = blended - low
                val tilted = blended + (if (tilt < 0f) low * (-tilt * 0.55f) else high * (tilt * 0.55f))
                out[idx] = (tilted * trim).coerceIn(-1f, 1f)
            }
        }
        return wet.copy(samples = out)
    }

    private fun readFractional(buffer: FloatArray, writePtr: Int, delaySamples: Float): Float {
        val size = buffer.size
        val readPos = (writePtr - delaySamples + size * 2) % size
        val i0 = readPos.toInt() % size
        val i1 = (i0 + 1) % size
        val frac = readPos - readPos.toInt()
        return buffer[i0] * (1f - frac) + buffer[i1] * frac
    }

    private class ReverseDelayLine(rawBlockLen: Int) {
        private val blockLen = rawBlockLen.coerceIn(64, 96000)
        private val writeBuf = FloatArray(blockLen)
        private val readBuf = FloatArray(blockLen)
        private var cursor = 0
        private val edgeSamples = min(64, max(4, blockLen / 10)).toFloat()

        fun readReversed(): Float {
            val revIdx = blockLen - 1 - cursor
            val dist = min(cursor, revIdx).toFloat()
            val taper = (dist / edgeSamples).coerceIn(0f, 1f)
            return readBuf[revIdx] * taper
        }

        fun push(sample: Float) {
            writeBuf[cursor] = sample
            cursor++
            if (cursor >= blockLen) {
                System.arraycopy(writeBuf, 0, readBuf, 0, blockLen)
                writeBuf.fill(0f)
                cursor = 0
            }
        }
    }
}
