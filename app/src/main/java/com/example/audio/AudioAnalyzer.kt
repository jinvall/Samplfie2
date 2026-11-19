package com.example.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class WaveformPeaks(
    val minPeaks: FloatArray,
    val maxPeaks: FloatArray,
    val rmsPeaks: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveformPeaks) return false
        return minPeaks.contentEquals(other.minPeaks) &&
            maxPeaks.contentEquals(other.maxPeaks) &&
            rmsPeaks.contentEquals(other.rmsPeaks)
    }

    override fun hashCode(): Int {
        var result = minPeaks.contentHashCode()
        result = 31 * result + maxPeaks.contentHashCode()
        result = 31 * result + rmsPeaks.contentHashCode()
        return result
    }
}

/**
 * Real-time waveform peak downsampler and Radix-2 Cooley-Tukey FFT spectrum analyzer.
 */
object AudioAnalyzer {

    fun computeWaveformPeaks(buffer: AudioBuffer, bucketCount: Int): WaveformPeaks {
        if (bucketCount <= 0 || buffer.frameCount == 0) {
            return WaveformPeaks(FloatArray(0), FloatArray(0), FloatArray(0))
        }
        val mono = buffer.toMonoSamples()
        val mins = FloatArray(bucketCount)
        val maxs = FloatArray(bucketCount)
        val rms = FloatArray(bucketCount)
        val samplesPerBucket = max(1f, mono.size.toFloat() / bucketCount.toFloat())

        for (b in 0 until bucketCount) {
            val start = (b * samplesPerBucket).toInt().coerceIn(0, mono.size - 1)
            val end = ((b + 1) * samplesPerBucket).toInt().coerceIn(start + 1, mono.size)
            var lo = 1f
            var hi = -1f
            var sumSq = 0.0
            for (i in start until end) {
                val s = mono[i]
                if (s < lo) lo = s
                if (s > hi) hi = s
                sumSq += s * s
            }
            mins[b] = lo
            maxs[b] = hi
            rms[b] = sqrt(sumSq / (end - start)).toFloat().coerceIn(0f, 1f)
        }
        return WaveformPeaks(mins, maxs, rms)
    }

    fun computeSpectrumBands(
        buffer: AudioBuffer,
        centerFrame: Int,
        fftSize: Int = 512,
        bandCount: Int = 32
    ): FloatArray {
        if (buffer.frameCount == 0 || bandCount <= 0) return FloatArray(max(0, bandCount))
        val mono = buffer.toMonoSamples()
        val windowStart = (centerFrame - fftSize / 2).coerceIn(0, max(0, mono.size - fftSize))
        val slice = FloatArray(fftSize)
        val copyLen = min(fftSize, mono.size - windowStart)
        if (copyLen > 0) {
            System.arraycopy(mono, windowStart, slice, 0, copyLen)
        }
        return computeSpectrumFromSamples(slice, buffer.sampleRate, fftSize, bandCount)
    }

    fun computeSpectrumFromSamples(
        samples: FloatArray,
        sampleRate: Int,
        fftSize: Int = 512,
        bandCount: Int = 32
    ): FloatArray {
        if (samples.isEmpty() || bandCount <= 0) return FloatArray(max(0, bandCount))
        val n = highestPowerOfTwo(fftSize.coerceAtLeast(64))
        val real = FloatArray(n)
        val imag = FloatArray(n)
        val count = min(n, samples.size)
        for (i in 0 until count) {
            val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            real[i] = (samples[i] * hann).toFloat()
        }

        fftInPlace(real, imag)

        val half = n / 2
        val magnitudes = FloatArray(half)
        val scale = 2f / n.toFloat()
        for (k in 0 until half) {
            magnitudes[k] = sqrt(real[k] * real[k] + imag[k] * imag[k]) * scale
        }

        val bands = FloatArray(bandCount)
        val minFreq = 35.0
        val maxFreq = max(1000.0, sampleRate / 2.0 * 0.92)
        val logRatio = ln(maxFreq / minFreq)

        for (b in 0 until bandCount) {
            val fLow = minFreq * kotlin.math.exp(logRatio * b / bandCount)
            val fHigh = minFreq * kotlin.math.exp(logRatio * (b + 1) / bandCount)
            val binLow = ((fLow * n) / sampleRate).toInt().coerceIn(1, half - 1)
            val binHigh = ((fHigh * n) / sampleRate).toInt().coerceIn(binLow, half - 1)
            var peakMag = 0f
            for (bin in binLow..binHigh) {
                if (magnitudes[bin] > peakMag) peakMag = magnitudes[bin]
            }
            val db = 20f * log10(max(peakMag, 1e-5f))
            // Map -66 dB .. -3 dB to 0..1
            bands[b] = ((db + 66f) / 63f).coerceIn(0f, 1f)
        }
        return bands
    }

    fun amplitudeToDbfs(amplitude: Float): Float {
        return 20f * log10(max(abs(amplitude), 1e-5f))
    }

    fun fftInPlace(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angle = -2.0 * PI / len
            val wLenR = cos(angle).toFloat()
            val wLenI = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wR = 1f
                var wI = 0f
                for (k in 0 until halfLen) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val idx = i + k + halfLen
                    val vR = real[idx] * wR - imag[idx] * wI
                    val vI = real[idx] * wI + imag[idx] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[idx] = uR - vR
                    imag[idx] = uI - vI
                    val nextWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun highestPowerOfTwo(v: Int): Int {
        var p = 1
        while (p * 2 <= v) p *= 2
        return p
    }
}
