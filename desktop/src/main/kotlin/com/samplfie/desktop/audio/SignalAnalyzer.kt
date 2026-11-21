package com.samplfie.desktop.audio

import com.samplfie.desktop.model.DesktopAudioBuffer
import com.samplfie.desktop.model.SignalWaveformType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class WaveformEnvelope(
    val minPeaks: FloatArray,
    val maxPeaks: FloatArray,
    val rmsPeaks: FloatArray
) {
    val buckets: Int get() = minPeaks.size
}

/**
 * Waveform envelope builder, Radix-2 Cooley-Tukey FFT analyzer, and calibrated Signal Generator.
 */
object SignalAnalyzer {

    fun computeWaveformEnvelope(buffer: DesktopAudioBuffer?, buckets: Int = 180): WaveformEnvelope {
        if (buffer == null || buffer.frameCount == 0 || buckets <= 0) {
            return WaveformEnvelope(FloatArray(0), FloatArray(0), FloatArray(0))
        }
        val mono = buffer.monoMixed()
        val total = mono.size
        val mins = FloatArray(buckets)
        val maxs = FloatArray(buckets)
        val rmss = FloatArray(buckets)
        for (b in 0 until buckets) {
            val s = (b * total) / buckets
            val e = (((b + 1) * total) / buckets).coerceAtLeast(s + 1).coerceAtMost(total)
            var lo = 1f
            var hi = -1f
            var sq = 0.0
            for (i in s until e) {
                val v = mono[i]
                if (v < lo) lo = v
                if (v > hi) hi = v
                sq += v * v
            }
            mins[b] = lo.coerceIn(-1f, 1f)
            maxs[b] = hi.coerceIn(-1f, 1f)
            rmss[b] = sqrt(sq / (e - s)).toFloat().coerceIn(0f, 1f)
        }
        return WaveformEnvelope(mins, maxs, rmss)
    }

    fun computeSpectrumBands(
        buffer: DesktopAudioBuffer?,
        centerFrame: Int = 0,
        fftSize: Int = 1024,
        bandCount: Int = 48
    ): FloatArray {
        if (buffer == null || buffer.frameCount == 0) return FloatArray(bandCount)
        val mono = buffer.monoMixed()
        val half = fftSize / 2
        val start = (centerFrame - half).coerceIn(0, max(0, mono.size - 1))
        val window = FloatArray(fftSize)
        for (i in 0 until fftSize) {
            val idx = start + i
            val sample = if (idx < mono.size) mono[idx] else 0f
            val hann = 0.5f * (1f - cos(2.0 * PI * i / (fftSize - 1)).toFloat())
            window[i] = sample * hann
        }
        return computeBandsFromMonoWindow(window, bandCount)
    }

    fun computeBandsFromMonoWindow(window: FloatArray, bandCount: Int = 48): FloatArray {
        val n = Integer.highestOneBit(window.size.coerceAtLeast(32))
        val re = FloatArray(n) { i -> if (i < window.size) window[i] else 0f }
        val im = FloatArray(n)
        fftRadix2(re, im)

        val bins = n / 2
        val bands = FloatArray(bandCount)
        for (b in 0 until bandCount) {
            val f0 = ((b.toDouble() / bandCount).let { it * it } * bins).toInt().coerceIn(1, bins - 1)
            val f1 = (((b + 1).toDouble() / bandCount).let { it * it } * bins).toInt().coerceIn(f0 + 1, bins)
            var peakMag = 0f
            for (k in f0 until f1) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / (n * 0.25f)
                if (mag > peakMag) peakMag = mag
            }
            bands[b] = (ln(1.0 + peakMag * 12.0) / ln(13.0)).toFloat().coerceIn(0f, 1f)
        }
        return bands
    }

    private fun fftRadix2(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wLenRe = cos(ang).toFloat()
            val wLenIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * wRe - im[i + k + len / 2] * wIm
                    val vIm = re[i + k + len / 2] * wIm + im[i + k + len / 2] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun generateTestSignal(
        type: SignalWaveformType,
        freqHz: Double = 440.0,
        durationSec: Float = 2.0f,
        sampleRate: Int = 44100,
        channels: Int = 1,
        amplitude: Float = 0.72f
    ): DesktopAudioBuffer {
        val frames = max(1, (sampleRate * durationSec).toInt())
        val out = FloatArray(frames * channels)
        val rng = Random(42)
        val f0 = 55.0
        val f1 = 3520.0
        val k = ln(f1 / f0) / durationSec.toDouble()

        for (f in 0 until frames) {
            val t = f.toDouble() / sampleRate
            val env = min(1f, f / (sampleRate * 0.01f).coerceAtLeast(1f)) *
                min(1f, (frames - 1 - f) / (sampleRate * 0.015f).coerceAtLeast(1f))
            val phase = (freqHz * t) % 1.0
            val raw = when (type) {
                SignalWaveformType.SINE -> sin(2.0 * PI * freqHz * t).toFloat()
                SignalWaveformType.SQUARE -> if (phase < 0.5) 1f else -1f
                SignalWaveformType.SAWTOOTH -> (2.0 * phase - 1.0).toFloat()
                SignalWaveformType.TRIANGLE -> (1.0 - 4.0 * abs(phase - 0.5)).toFloat()
                SignalWaveformType.CHIRP -> {
                    val instPhase = 2.0 * PI * f0 * ((exp(k * t) - 1.0) / k)
                    sin(instPhase).toFloat()
                }
                SignalWaveformType.NOISE -> (rng.nextFloat() * 2f - 1f)
            }
            val v = (raw * amplitude * env).coerceIn(-1f, 1f)
            for (c in 0 until channels) {
                out[f * channels + c] = v
            }
        }
        return DesktopAudioBuffer(
            samples = out,
            sampleRate = sampleRate,
            channels = channels,
            name = "${type.label}_${freqHz.toInt()}Hz"
        )
    }
}
