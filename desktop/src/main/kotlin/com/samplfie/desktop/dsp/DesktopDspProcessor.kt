package com.samplfie.desktop.dsp

import com.samplfie.desktop.model.DesktopAudioBuffer
import com.samplfie.desktop.model.DesktopFxState
import com.samplfie.desktop.model.DesktopStompState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Pure-Kotlin 12-module DSP chain + Stompbox Master Tilt/Kill/Wet processor matching
 * Samplfie2 Android's audio effects engine.
 */
object DesktopDspProcessor {

    fun process(
        dry: DesktopAudioBuffer,
        fx: DesktopFxState,
        stomp: DesktopStompState
    ): DesktopAudioBuffer {
        if (dry.frameCount == 0 || fx.masterBypass) return dry

        var buf = dry
        val sr = buf.sampleRate.coerceAtLeast(8000)

        if (fx.noiseGateEnabled) {
            buf = applyNoiseGate(buf, fx.noiseThresholdDb)
        }
        if (fx.pitchEnabled && (abs(fx.pitchSemitones) > 0.05f || abs(fx.speedFactor - 1f) > 0.02f)) {
            val pitchRatio = 2.0.pow(fx.pitchSemitones / 12.0).toFloat()
            val factor = (pitchRatio * fx.speedFactor).coerceIn(0.45f, 2.2f)
            buf = resampleRateSpeed(buf, factor)
        }
        if (fx.overdriveEnabled && (!stomp.engaged || !stomp.killDrive)) {
            buf = applyOverdrive(buf, fx.driveAmount)
        }
        if (fx.wahEnabled) {
            buf = applyWah(buf, fx.wahFreqHz, sr)
        }
        if (!stomp.engaged || !stomp.killMod) {
            if (fx.phaserEnabled) buf = applyPhaser(buf, fx.phaserMix, sr)
            if (fx.flangerEnabled) buf = applyFlanger(buf, fx.flangerMix, sr)
            if (fx.chorusEnabled) buf = applyChorus(buf, fx.chorusMix, sr)
            if (fx.rotaryEnabled) buf = applyRotary(buf, fx.rotaryRateHz, sr)
        }
        if (!stomp.engaged || !stomp.killTime) {
            if (fx.echoEnabled) buf = applyEcho(buf, fx.echoDelayMs, fx.echoFeedback, fx.echoMix, sr)
            if (fx.delayEnabled) buf = applyDelay(buf, fx.delayTimeMs, fx.delayMix, sr)
            if (fx.reverbEnabled) buf = applyReverb(buf, fx.reverbSize, fx.reverbMix, sr)
        }
        if (fx.gainEnabled) {
            buf = applyGain(buf, fx.gainDb, fx.normalize)
        }
        if (stomp.engaged) {
            buf = applyStompMaster(dry, buf, stomp, sr)
        }
        return buf
    }

    private fun applyNoiseGate(buf: DesktopAudioBuffer, threshDb: Float): DesktopAudioBuffer {
        val threshLin = 10.0.pow(threshDb / 20.0).toFloat()
        val out = buf.samples.copyOf()
        val ch = buf.channels
        var env = 0f
        for (f in 0 until buf.frameCount) {
            var peak = 0f
            for (c in 0 until ch) peak = max(peak, abs(out[f * ch + c]))
            env = env * 0.992f + peak * 0.008f
            val gain = if (env >= threshLin) 1f else {
                val ratio = (env / threshLin.coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
                0.08f + 0.92f * ratio * ratio
            }
            for (c in 0 until ch) out[f * ch + c] *= gain
        }
        return buf.copy(samples = out)
    }

    private fun resampleRateSpeed(buf: DesktopAudioBuffer, speed: Float): DesktopAudioBuffer {
        val frames = buf.frameCount
        val outFrames = max(64, (frames / speed).toInt())
        val ch = buf.channels
        val out = FloatArray(outFrames * ch)
        for (of in 0 until outFrames) {
            val pos = of * speed
            val i0 = pos.toInt().coerceIn(0, frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val frac = pos - i0
            for (c in 0 until ch) {
                val a = buf.samples[i0 * ch + c]
                val b = buf.samples[i1 * ch + c]
                out[of * ch + c] = (a + (b - a) * frac).coerceIn(-1f, 1f)
            }
        }
        return buf.copy(samples = out)
    }

    private fun applyOverdrive(buf: DesktopAudioBuffer, drive: Float): DesktopAudioBuffer {
        val norm = tanh(drive.toDouble()).toFloat().coerceAtLeast(0.1f)
        val out = FloatArray(buf.samples.size) { i ->
            (tanh((buf.samples[i] * drive).toDouble()).toFloat() / norm).coerceIn(-1f, 1f)
        }
        return buf.copy(samples = out)
    }

    private fun applyWah(buf: DesktopAudioBuffer, centerHz: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = buf.samples.copyOf()
        val lfoRate = 2.1f
        val q = 4.2f
        val z1 = FloatArray(ch)
        val z2 = FloatArray(ch)
        for (f in 0 until buf.frameCount) {
            val mod = 0.5f + 0.5f * sin(2.0 * PI * lfoRate * f / sr).toFloat()
            val freq = (centerHz * (0.45f + 1.15f * mod)).coerceIn(180f, sr * 0.42f)
            val fCoef = (2f * sin(PI * freq / sr).toFloat()).coerceIn(0.01f, 0.9f)
            val damp = (1f / q).coerceIn(0.1f, 0.95f)
            for (c in 0 until ch) {
                val idx = f * ch + c
                val x = out[idx]
                val hp = x - z1[c] * damp - z2[c]
                val bp = z1[c] + fCoef * hp
                val lp = z2[c] + fCoef * bp
                z1[c] = bp.coerceIn(-2f, 2f)
                z2[c] = lp.coerceIn(-2f, 2f)
                out[idx] = (x * 0.35f + bp * 0.85f).coerceIn(-1f, 1f)
            }
        }
        return buf.copy(samples = out)
    }

    private fun applyPhaser(buf: DesktopAudioBuffer, mix: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = buf.samples.copyOf()
        val ap = Array(ch) { FloatArray(6) }
        for (f in 0 until buf.frameCount) {
            val lfo = 0.5f + 0.5f * sin(2.0 * PI * 0.55 * f / sr).toFloat()
            val fc = 300f + lfo * 1800f
            val tanVal = kotlin.math.tan(PI * fc / sr).toFloat()
            val coef = (tanVal - 1f) / (tanVal + 1f)
            for (c in 0 until ch) {
                val idx = f * ch + c
                val dry = out[idx]
                var y = dry
                for (s in 0 until 6) {
                    val v = y - coef * ap[c][s]
                    y = coef * v + ap[c][s]
                    ap[c][s] = v
                }
                out[idx] = (dry * (1f - mix * 0.5f) + y * mix * 0.5f).coerceIn(-1f, 1f)
            }
        }
        return buf.copy(samples = out)
    }

    private fun applyFlanger(buf: DesktopAudioBuffer, mix: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = buf.samples.copyOf()
        val maxDelay = (0.012f * sr).toInt().coerceAtLeast(16)
        val line = Array(ch) { FloatArray(maxDelay) }
        var wp = 0
        for (f in 0 until buf.frameCount) {
            val lfo = 0.5f + 0.5f * sin(2.0 * PI * 0.38 * f / sr).toFloat()
            val dSamples = (1.5f + lfo * (maxDelay - 4)).coerceIn(1f, (maxDelay - 2).toFloat())
            val dInt = dSamples.toInt()
            val frac = dSamples - dInt
            for (c in 0 until ch) {
                val idx = f * ch + c
                val dry = out[idx]
                val r0 = (wp - dInt + maxDelay) % maxDelay
                val r1 = (r0 - 1 + maxDelay) % maxDelay
                val delayed = line[c][r0] * (1f - frac) + line[c][r1] * frac
                line[c][wp] = (dry + delayed * 0.48f).coerceIn(-1f, 1f)
                out[idx] = (dry * (1f - mix * 0.5f) + delayed * mix * 0.7f).coerceIn(-1f, 1f)
            }
            wp = (wp + 1) % maxDelay
        }
        return buf.copy(samples = out)
    }

    private fun applyChorus(buf: DesktopAudioBuffer, mix: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = buf.samples.copyOf()
        val maxDelay = (0.045f * sr).toInt().coerceAtLeast(32)
        val line = Array(ch) { FloatArray(maxDelay) }
        var wp = 0
        for (f in 0 until buf.frameCount) {
            val lfo1 = 0.5f + 0.5f * sin(2.0 * PI * 0.75 * f / sr).toFloat()
            val lfo2 = 0.5f + 0.5f * sin(2.0 * PI * 1.15 * f / sr + 1.8).toFloat()
            val d1 = ((0.012f + lfo1 * 0.010f) * sr).toInt().coerceIn(2, maxDelay - 2)
            val d2 = ((0.018f + lfo2 * 0.012f) * sr).toInt().coerceIn(2, maxDelay - 2)
            for (c in 0 until ch) {
                val idx = f * ch + c
                val dry = out[idx]
                line[c][wp] = dry
                val tap = 0.5f * (line[c][(wp - d1 + maxDelay) % maxDelay] + line[c][(wp - d2 + maxDelay) % maxDelay])
                out[idx] = (dry * (1f - mix * 0.45f) + tap * mix * 0.75f).coerceIn(-1f, 1f)
            }
            wp = (wp + 1) % maxDelay
        }
        return buf.copy(samples = out)
    }

    private fun applyRotary(buf: DesktopAudioBuffer, rateHz: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = buf.samples.copyOf()
        for (f in 0 until buf.frameCount) {
            val phase = 2.0 * PI * rateHz * f / sr
            for (c in 0 until ch) {
                val idx = f * ch + c
                val am = 0.65f + 0.35f * sin(phase + c * PI * 0.5).toFloat()
                out[idx] = (out[idx] * am).coerceIn(-1f, 1f)
            }
        }
        return buf.copy(samples = out)
    }

    private fun applyEcho(buf: DesktopAudioBuffer, delayMs: Float, feedback: Float, mix: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = buf.samples.copyOf()
        val dFrames = ((delayMs / 1000f) * sr).toInt().coerceIn(64, sr * 2)
        val line = Array(ch) { FloatArray(dFrames) }
        val lp = FloatArray(ch)
        var wp = 0
        for (f in 0 until buf.frameCount) {
            for (c in 0 until ch) {
                val idx = f * ch + c
                val dry = out[idx]
                val delayed = line[c][wp]
                lp[c] = lp[c] * 0.38f + delayed * 0.62f
                line[c][wp] = tanh((dry + lp[c] * feedback.coerceIn(0f, 0.88f)).toDouble()).toFloat()
                out[idx] = (dry * (1f - mix * 0.5f) + lp[c] * mix).coerceIn(-1f, 1f)
            }
            wp = (wp + 1) % dFrames
        }
        return buf.copy(samples = out)
    }

    private fun applyDelay(buf: DesktopAudioBuffer, timeMs: Float, mix: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = buf.samples.copyOf()
        val dFrames = ((timeMs / 1000f) * sr).toInt().coerceIn(64, sr * 2)
        val line = Array(ch) { FloatArray(dFrames) }
        var wp = 0
        for (f in 0 until buf.frameCount) {
            for (c in 0 until ch) {
                val idx = f * ch + c
                val dry = out[idx]
                val delayed = line[c][wp]
                line[c][wp] = (dry + delayed * 0.42f).coerceIn(-1f, 1f)
                out[idx] = (dry * (1f - mix * 0.4f) + delayed * mix).coerceIn(-1f, 1f)
            }
            wp = (wp + 1) % dFrames
        }
        return buf.copy(samples = out)
    }

    private fun applyReverb(buf: DesktopAudioBuffer, size: Float, mix: Float, sr: Int): DesktopAudioBuffer {
        val ch = buf.channels
        val out = buf.samples.copyOf()
        val combBase = intArrayOf(1116, 1188, 1277, 1356)
        val scale = (sr / 44100f) * (0.6f + size * 0.8f)
        val combs = Array(ch) {
            Array(combBase.size) { k -> FloatArray((combBase[k] * scale).toInt().coerceAtLeast(32)) }
        }
        val ptrs = Array(ch) { IntArray(combBase.size) }
        val fb = (0.68f + size * 0.2f).coerceIn(0.4f, 0.89f)
        for (f in 0 until buf.frameCount) {
            for (c in 0 until ch) {
                val idx = f * ch + c
                val dry = out[idx]
                var wet = 0f
                for (k in combBase.indices) {
                    val line = combs[c][k]
                    val p = ptrs[c][k]
                    val y = line[p]
                    line[p] = (dry + y * fb).coerceIn(-1f, 1f)
                    ptrs[c][k] = (p + 1) % line.size
                    wet += y
                }
                wet *= 0.25f
                out[idx] = (dry * (1f - mix * 0.45f) + wet * mix).coerceIn(-1f, 1f)
            }
        }
        return buf.copy(samples = out)
    }

    private fun applyGain(buf: DesktopAudioBuffer, gainDb: Float, normalize: Boolean): DesktopAudioBuffer {
        val gainLin = 10.0.pow(gainDb / 20.0).toFloat()
        var out = FloatArray(buf.samples.size) { i ->
            tanh((buf.samples[i] * gainLin).toDouble()).toFloat()
        }
        var res = buf.copy(samples = out)
        if (normalize) res = res.peakNormalized(0.95f)
        return res
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
}
