package com.example.audio

import kotlin.math.pow
import kotlin.math.round
import kotlin.random.Random

/**
 * Supported sample formats / bit depths for internal precision shaping and RIFF/WAVE encoding.
 */
enum class AudioSampleFormat(
    val label: String,
    val shortLabel: String,
    val bitsPerSample: Int,
    val isFloat: Boolean,
    val wavFormatCode: Int
) {
    PCM_8("8-bit Unsigned PCM", "PCM 8", 8, false, 1),
    PCM_16("16-bit Signed PCM", "PCM 16", 16, false, 1),
    PCM_24("24-bit Signed PCM", "PCM 24", 24, false, 1),
    PCM_32("32-bit Signed PCM", "PCM 32", 32, false, 1),
    FLOAT_32("32-bit IEEE Float", "FLT 32", 32, true, 3),
    FLOAT_64("64-bit IEEE Float", "FLT 64", 64, true, 3);

    companion object {
        val STANDARD_SAMPLE_RATES = listOf(8000, 16000, 22050, 44100, 48000, 88200, 96000, 192000)

        fun fromWavHeader(audioFormat: Int, bitsPerSample: Int): AudioSampleFormat = when {
            audioFormat == 3 && bitsPerSample == 64 -> FLOAT_64
            audioFormat == 3 -> FLOAT_32
            bitsPerSample == 8 -> PCM_8
            bitsPerSample == 24 -> PCM_24
            bitsPerSample == 32 -> PCM_32
            else -> PCM_16
        }

        /**
         * Shapes floating-point samples to the effective dynamic resolution of [targetFormat],
         * applying optional triangular probability density function (TPDF) dither for integer PCM formats.
         */
        fun quantizeSamples(
            samples: FloatArray,
            targetFormat: AudioSampleFormat,
            dither: Boolean = false,
            seed: Int = 42
        ): FloatArray {
            if (targetFormat == FLOAT_32 || targetFormat == FLOAT_64) {
                return FloatArray(samples.size) { i -> samples[i].coerceIn(-1f, 1f) }
            }
            val maxInt = when (targetFormat) {
                PCM_8 -> 127.0
                PCM_16 -> 32767.0
                PCM_24 -> 8388607.0
                PCM_32 -> 2147483647.0
                else -> 32767.0
            }
            val inv = 1.0 / maxInt
            val lsb = inv
            val rng = if (dither) Random(seed) else null
            return FloatArray(samples.size) { i ->
                var s = samples[i].toDouble().coerceIn(-1.0, 1.0)
                if (rng != null) {
                    val tpdf = (rng.nextDouble() - rng.nextDouble()) * lsb
                    s = (s + tpdf).coerceIn(-1.0, 1.0)
                }
                val q = round(s * maxInt).coerceIn(-maxInt, maxInt)
                (q * inv).toFloat().coerceIn(-1f, 1f)
            }
        }
    }
}
