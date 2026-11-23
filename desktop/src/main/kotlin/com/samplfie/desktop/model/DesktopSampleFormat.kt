package com.samplfie.desktop.model

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Supported uncompressed RIFF/WAVE sample formats across encoding, decoding, and precision conversion.
 */
enum class DesktopSampleFormat(
    val shortLabel: String,
    val label: String,
    val bitsPerSample: Int,
    val isFloat: Boolean,
    val wavFormatCode: Int
) {
    PCM_8("8-BIT", "PCM 8-bit Unsigned", 8, false, 1),
    PCM_16("16-BIT", "PCM 16-bit Integer", 16, false, 1),
    PCM_24("24-BIT", "PCM 24-bit Integer", 24, false, 1),
    PCM_32("32-BIT", "PCM 32-bit Integer", 32, false, 1),
    FLOAT_32("32F", "IEEE Float 32-bit", 32, true, 3),
    FLOAT_64("64F", "IEEE Float 64-bit", 64, true, 3);

    val bytesPerSample: Int get() = bitsPerSample / 8

    companion object {
        val STANDARD_SAMPLE_RATES = listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 176400, 192000)

        fun fromWavHeader(formatTag: Int, bitsPerSample: Int): DesktopSampleFormat? = when {
            formatTag == 1 && bitsPerSample == 8 -> PCM_8
            formatTag == 1 && bitsPerSample == 16 -> PCM_16
            formatTag == 1 && bitsPerSample == 24 -> PCM_24
            formatTag == 1 && bitsPerSample == 32 -> PCM_32
            formatTag == 3 && bitsPerSample == 32 -> FLOAT_32
            formatTag == 3 && bitsPerSample == 64 -> FLOAT_64
            else -> null
        }

        fun quantizeSamples(
            samples: FloatArray,
            target: DesktopSampleFormat,
            dither: Boolean = false
        ): FloatArray {
            if (target.isFloat) {
                return if (target == FLOAT_32) {
                    samples.copyOf()
                } else {
                    FloatArray(samples.size) { i -> samples[i].toDouble().toFloat().coerceIn(-1f, 1f) }
                }
            }
            val rng = if (dither) Random(1337) else null
            val out = FloatArray(samples.size)
            when (target) {
                PCM_8 -> {
                    val scale = 127f
                    val step = 1f / scale
                    for (i in samples.indices) {
                        val tpdf = if (rng != null) ((rng.nextFloat() + rng.nextFloat()) - 1f) * step else 0f
                        val q = (((samples[i] + tpdf).coerceIn(-1f, 1f) * scale).roundToInt().coerceIn(-128, 127)) / 128f
                        out[i] = q.coerceIn(-1f, 1f)
                    }
                }
                PCM_16 -> {
                    val scale = 32767f
                    val step = 1f / scale
                    for (i in samples.indices) {
                        val tpdf = if (rng != null) ((rng.nextFloat() + rng.nextFloat()) - 1f) * step else 0f
                        val q = (((samples[i] + tpdf).coerceIn(-1f, 1f) * scale).roundToInt().coerceIn(-32768, 32767)) / 32768f
                        out[i] = q.coerceIn(-1f, 1f)
                    }
                }
                PCM_24 -> {
                    val scale = 8388607.0
                    val step = 1.0 / scale
                    for (i in samples.indices) {
                        val tpdf = if (rng != null) ((rng.nextFloat() + rng.nextFloat()) - 1f).toDouble() * step else 0.0
                        val v = (samples[i].toDouble() + tpdf).coerceIn(-1.0, 1.0)
                        val iv = (v * scale).roundToInt().coerceIn(-8388608, 8388607)
                        out[i] = (iv / 8388608.0).toFloat().coerceIn(-1f, 1f)
                    }
                }
                PCM_32 -> {
                    val scale = 2147483647.0
                    val step = 1.0 / scale
                    for (i in samples.indices) {
                        val tpdf = if (rng != null) ((rng.nextFloat() + rng.nextFloat()) - 1f).toDouble() * step else 0.0
                        val v = (samples[i].toDouble() + tpdf).coerceIn(-1.0, 1.0)
                        val iv = (v * scale).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                        out[i] = (iv / 2147483648.0).toFloat().coerceIn(-1f, 1f)
                    }
                }
                else -> Unit
            }
            for (i in out.indices) {
                if (abs(out[i]) < 1e-8f) out[i] = 0f
            }
            return out
        }
    }
}
