package com.example.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Immutable PCM audio container normalized to [-1.0f, 1.0f] interleaved floating-point samples.
 */
data class AudioBuffer(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int,
    val name: String = "Take"
) {
    val frameCount: Int
        get() = if (channels > 0) samples.size / channels else 0

    val durationSeconds: Float
        get() = if (sampleRate > 0) frameCount.toFloat() / sampleRate.toFloat() else 0f

    val durationMs: Long
        get() = (durationSeconds * 1000f).toLong()

    fun extractSelection(
        startFraction: Float,
        endFraction: Float,
        newName: String = "$name (Extract)"
    ): AudioBuffer {
        if (frameCount == 0 || channels <= 0) return this
        val clampedStart = min(startFraction, endFraction).coerceIn(0f, 1f)
        val clampedEnd = max(startFraction, endFraction).coerceIn(0f, 1f)
        val startFrame = (clampedStart * frameCount).toInt().coerceIn(0, max(0, frameCount - 1))
        val endFrame = (clampedEnd * frameCount).toInt().coerceIn(startFrame + 1, frameCount)
        val startIdx = startFrame * channels
        val endIdx = (endFrame * channels).coerceAtMost(samples.size)
        return AudioBuffer(
            samples = samples.copyOfRange(startIdx, endIdx),
            sampleRate = sampleRate,
            channels = channels,
            name = newName
        )
    }

    fun applyFadeInOut(fadeInMs: Float, fadeOutMs: Float): AudioBuffer {
        if (frameCount == 0) return this
        val out = samples.copyOf()
        val fadeInFrames = ((fadeInMs.coerceAtLeast(0f) / 1000f) * sampleRate).toInt().coerceAtMost(frameCount / 2)
        val fadeOutFrames = ((fadeOutMs.coerceAtLeast(0f) / 1000f) * sampleRate).toInt().coerceAtMost(frameCount / 2)

        for (f in 0 until fadeInFrames) {
            val gain = f.toFloat() / fadeInFrames.coerceAtLeast(1)
            for (c in 0 until channels) {
                out[f * channels + c] *= gain
            }
        }
        for (f in 0 until fadeOutFrames) {
            val frameIdx = frameCount - 1 - f
            val gain = f.toFloat() / fadeOutFrames.coerceAtLeast(1)
            for (c in 0 until channels) {
                out[frameIdx * channels + c] *= gain
            }
        }
        return copy(samples = out)
    }

    fun reversed(): AudioBuffer {
        if (frameCount <= 1) return this
        val out = FloatArray(samples.size)
        for (f in 0 until frameCount) {
            val srcFrame = frameCount - 1 - f
            for (c in 0 until channels) {
                out[f * channels + c] = samples[srcFrame * channels + c]
            }
        }
        return copy(samples = out)
    }

    fun removeDcOffset(): AudioBuffer {
        if (frameCount == 0) return this
        val out = samples.copyOf()
        for (c in 0 until channels) {
            var sum = 0.0
            for (f in 0 until frameCount) {
                sum += out[f * channels + c]
            }
            val mean = (sum / frameCount).toFloat()
            for (f in 0 until frameCount) {
                out[f * channels + c] = (out[f * channels + c] - mean).coerceIn(-1f, 1f)
            }
        }
        return copy(samples = out)
    }

    fun normalizePeak(targetPeak: Float = 0.966f): AudioBuffer {
        val (peak, _) = computePeakAndRms()
        if (peak <= 1e-5f) return this
        val scale = targetPeak / peak
        val out = FloatArray(samples.size) { i -> (samples[i] * scale).coerceIn(-1f, 1f) }
        return copy(samples = out)
    }

    fun snapFractionToZeroCrossing(fraction: Float, searchWindowFrames: Int = 512): Float {
        if (frameCount < 4) return fraction.coerceIn(0f, 1f)
        val center = (fraction.coerceIn(0f, 1f) * frameCount).toInt().coerceIn(1, frameCount - 2)
        val lo = (center - searchWindowFrames).coerceAtLeast(1)
        val hi = (center + searchWindowFrames).coerceAtMost(frameCount - 2)
        var bestFrame = center
        var bestDist = Int.MAX_VALUE
        for (f in lo..hi) {
            val s0 = samples[(f - 1) * channels]
            val s1 = samples[f * channels]
            if ((s0 <= 0f && s1 >= 0f) || (s0 >= 0f && s1 <= 0f)) {
                val dist = abs(f - center)
                if (dist < bestDist) {
                    bestDist = dist
                    bestFrame = f
                }
            }
        }
        return bestFrame.toFloat() / frameCount.toFloat()
    }

    fun toMonoSamples(): FloatArray {
        if (channels <= 1) return samples.copyOf()
        val frames = frameCount
        val out = FloatArray(frames)
        val invCh = 1f / channels
        for (f in 0 until frames) {
            var sum = 0f
            val base = f * channels
            for (c in 0 until channels) {
                sum += samples[base + c]
            }
            out[f] = sum * invCh
        }
        return out
    }

    fun convertChannels(targetChannels: Int): AudioBuffer {
        if (targetChannels == channels || targetChannels !in 1..2) return this
        val frames = frameCount
        return if (channels == 1 && targetChannels == 2) {
            val stereo = FloatArray(frames * 2)
            for (f in 0 until frames) {
                val v = samples[f]
                stereo[f * 2] = v
                stereo[f * 2 + 1] = v
            }
            copy(samples = stereo, channels = 2)
        } else {
            copy(samples = toMonoSamples(), channels = 1)
        }
    }

    fun resample(targetSampleRate: Int): AudioBuffer {
        if (targetSampleRate == sampleRate || targetSampleRate <= 0 || frameCount < 2) {
            return copy(sampleRate = if (targetSampleRate > 0) targetSampleRate else sampleRate)
        }
        val ratio = sampleRate.toDouble() / targetSampleRate.toDouble()
        val outFrames = max(1, (frameCount / ratio).toInt())
        val out = FloatArray(outFrames * channels)
        for (ch in 0 until channels) {
            for (i in 0 until outFrames) {
                val srcPos = i * ratio
                val idx = srcPos.toInt()
                val frac = (srcPos - idx).toFloat()
                val xm1 = sampleAt(idx - 1, ch)
                val x0 = sampleAt(idx, ch)
                val x1 = sampleAt(idx + 1, ch)
                val x2 = sampleAt(idx + 2, ch)
                out[i * channels + ch] = hermite(frac, xm1, x0, x1, x2).coerceIn(-1f, 1f)
            }
        }
        return copy(samples = out, sampleRate = targetSampleRate)
    }

    fun computePeakAndRms(): Pair<Float, Float> {
        if (samples.isEmpty()) return 0f to 0f
        var peak = 0f
        var sumSq = 0.0
        for (s in samples) {
            val a = abs(s)
            if (a > peak) peak = a
            sumSq += s * s
        }
        val rms = sqrt(sumSq / samples.size).toFloat()
        return peak to rms
    }

    private fun sampleAt(frame: Int, channel: Int): Float {
        val clamped = frame.coerceIn(0, max(0, frameCount - 1))
        return samples[clamped * channels + channel]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioBuffer) return false
        return sampleRate == other.sampleRate &&
            channels == other.channels &&
            name == other.name &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + name.hashCode()
        return result
    }

    companion object {
        fun hermite(t: Float, xm1: Float, x0: Float, x1: Float, x2: Float): Float {
            val c0 = x0
            val c1 = 0.5f * (x1 - xm1)
            val c2 = xm1 - 2.5f * x0 + 2f * x1 - 0.5f * x2
            val c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1)
            return ((c3 * t + c2) * t + c1) * t + c0
        }
    }
}
