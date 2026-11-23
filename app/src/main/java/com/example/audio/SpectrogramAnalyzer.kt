package com.example.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 10-Stop Spectrogram Gradient palette + fast 256-entry RGB lookup table & parser.
 * Allows instant preset switching or pasting a custom 10-stop hex gradient (#RRGGBB).
 */
data class SpectrogramPalette(
    val id: String,
    val name: String,
    val stopsArgb: List<Long>
) {
    init {
        require(stopsArgb.size == REQUIRED_STOP_COUNT) {
            "Spectrogram palette requires exactly $REQUIRED_STOP_COUNT color stops (got ${stopsArgb.size})"
        }
    }

    val hexListText: String
        get() = stopsArgb.joinToString(", ") { argb ->
            String.format("#%06X", (argb and 0xFFFFFFL).toInt())
        }

    companion object {
        const val REQUIRED_STOP_COUNT = 10

        val SAMPLFIE_NEON = SpectrogramPalette(
            id = "neon10",
            name = "SAMPLFIE NEON (10-STOP)",
            stopsArgb = listOf(
                0xFF05070EL, // 0: Deep Void
                0xFF121242L, // 1: Indigo Night
                0xFF1D358CL, // 2: Cobalt Blue
                0xFF0077B6L, // 3: Ocean Blue
                0xFF00E5FFL, // 4: Neon Cyan
                0xFF00E676L, // 5: Emerald Signal
                0xFFAEEB20L, // 6: Chartreuse
                0xFFFFB020L, // 7: Amber Hot
                0xFFFF4D5AL, // 8: Tac Coral
                0xFFFFFFFFL  // 9: White-Hot Core
            )
        )

        val THERMAL_IRON = SpectrogramPalette(
            id = "iron10",
            name = "THERMAL IRON (10-STOP)",
            stopsArgb = listOf(
                0xFF06040AL,
                0xFF1E0836L,
                0xFF420A68L,
                0xFF6A176EL,
                0xFF932667L,
                0xFFBC3754L,
                0xFFDD513AL,
                0xFFF37819L,
                0xFFFCA50AL,
                0xFFFCFFA4L
            )
        )

        val VIRIDIS_PRO = SpectrogramPalette(
            id = "viridis10",
            name = "VIRIDIS PRO (10-STOP)",
            stopsArgb = listOf(
                0xFF0B0D12L,
                0xFF440154L,
                0xFF482878L,
                0xFF3E4989L,
                0xFF31688EL,
                0xFF26828EL,
                0xFF1F9E89L,
                0xFF6ECE58L,
                0xFFB5DE2BL,
                0xFFFDE725L
            )
        )

        val PRESETS = listOf(SAMPLFIE_NEON, THERMAL_IRON, VIRIDIS_PRO)

        fun interpolateArgb(stops: List<Long>, norm: Float): Int {
            val safe = if (stops.size >= REQUIRED_STOP_COUNT) stops.take(REQUIRED_STOP_COUNT) else SAMPLFIE_NEON.stopsArgb
            val t = norm.coerceIn(0f, 1f)
            val scaled = t * (safe.size - 1)
            val idx0 = scaled.toInt().coerceIn(0, safe.size - 2)
            val idx1 = idx0 + 1
            val frac = scaled - idx0
            val c0 = safe[idx0]
            val c1 = safe[idx1]
            val a0 = ((c0 ushr 24) and 0xFF).toInt()
            val r0 = ((c0 ushr 16) and 0xFF).toInt()
            val g0 = ((c0 ushr 8) and 0xFF).toInt()
            val b0 = (c0 and 0xFF).toInt()
            val a1 = ((c1 ushr 24) and 0xFF).toInt()
            val r1 = ((c1 ushr 16) and 0xFF).toInt()
            val g1 = ((c1 ushr 8) and 0xFF).toInt()
            val b1 = (c1 and 0xFF).toInt()
            val a = (a0 + (a1 - a0) * frac).roundToInt().coerceIn(0, 255)
            val r = (r0 + (r1 - r0) * frac).roundToInt().coerceIn(0, 255)
            val g = (g0 + (g1 - g0) * frac).roundToInt().coerceIn(0, 255)
            val b = (b0 + (b1 - b0) * frac).roundToInt().coerceIn(0, 255)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        fun buildLut256(stops: List<Long>): IntArray =
            IntArray(256) { i -> interpolateArgb(stops, i / 255f) }

        /**
         * Parses 10 hex color tokens (#RRGGBB, RRGGBB, #AARRGGBB, 0xFFRRGGBB) into a custom palette.
         */
        fun parseTenHexStops(raw: String, name: String = "CUSTOM (10-STOP)"): Result<SpectrogramPalette> {
            val tokens = raw.split(Regex("[\\s,;]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (tokens.size != REQUIRED_STOP_COUNT) {
                return Result.failure(
                    IllegalArgumentException("Expected 10 hex stops, found ${tokens.size}")
                )
            }
            val parsed = mutableListOf<Long>()
            for (tok in tokens) {
                val clean = tok.removePrefix("#").removePrefix("0x").removePrefix("0X")
                val v = clean.toLongOrNull(16)
                    ?: return Result.failure(IllegalArgumentException("Invalid hex color: $tok"))
                val argb = when (clean.length) {
                    6 -> 0xFF000000L or v
                    8 -> v and 0xFFFFFFFFL
                    else -> return Result.failure(IllegalArgumentException("Expected 6 or 8 hex digits: $tok"))
                }
                parsed.add(argb)
            }
            return Result.success(SpectrogramPalette("custom10", name, parsed))
        }
    }
}

/**
 * Computed 2D Time-Frequency Spectrogram Matrix.
 * Values are row-major [frame * bins + bin] in [0f..1f] mapped from -84 dBFS .. -3 dBFS.
 */
data class SpectrogramData(
    val frames: Int,
    val bins: Int,
    val magnitudesNorm: FloatArray,
    val minFreqHz: Float,
    val maxFreqHz: Float,
    val sampleRate: Int,
    val durationSec: Float,
    val logScale: Boolean
) {
    val isEmpty: Boolean get() = frames <= 0 || bins <= 0 || magnitudesNorm.isEmpty()

    fun valueAt(frame: Int, bin: Int): Float {
        if (isEmpty) return 0f
        val f = frame.coerceIn(0, frames - 1)
        val b = bin.coerceIn(0, bins - 1)
        return magnitudesNorm[f * bins + b]
    }

    /** Returns frequency (Hz) for a normalized vertical axis fraction [0f = bottom, 1f = top]. */
    fun normYToFreqHz(normY: Float): Float {
        val t = normY.coerceIn(0f, 1f).toDouble()
        val fMin = minFreqHz.toDouble().coerceAtLeast(20.0)
        val fMax = maxFreqHz.toDouble().coerceAtLeast(fMin + 100.0)
        return if (logScale) {
            (fMin * (fMax / fMin).pow(t)).toFloat()
        } else {
            (fMin + (fMax - fMin) * t).toFloat()
        }
    }

    /** Inverse mapping: frequency (Hz) to normalized vertical axis fraction [0f..1f]. */
    fun freqHzToNormY(freqHz: Float): Float {
        val fMin = minFreqHz.toDouble().coerceAtLeast(20.0)
        val fMax = maxFreqHz.toDouble().coerceAtLeast(fMin + 100.0)
        val f = freqHz.toDouble().coerceIn(fMin, fMax)
        return if (logScale) {
            (ln(f / fMin) / ln(fMax / fMin)).toFloat().coerceIn(0f, 1f)
        } else {
            ((f - fMin) / (fMax - fMin)).toFloat().coerceIn(0f, 1f)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpectrogramData) return false
        return frames == other.frames &&
            bins == other.bins &&
            minFreqHz == other.minFreqHz &&
            maxFreqHz == other.maxFreqHz &&
            sampleRate == other.sampleRate &&
            durationSec == other.durationSec &&
            logScale == other.logScale &&
            magnitudesNorm.contentEquals(other.magnitudesNorm)
    }

    override fun hashCode(): Int {
        var result = frames
        result = 31 * result + bins
        result = 31 * result + magnitudesNorm.contentHashCode()
        result = 31 * result + minFreqHz.hashCode()
        result = 31 * result + maxFreqHz.hashCode()
        result = 31 * result + sampleRate
        return result
    }

    companion object {
        val EMPTY = SpectrogramData(0, 0, FloatArray(0), 40f, 20000f, 44100, 0f, true)
    }
}

object SpectrogramAnalyzer {

    fun compute(
        buffer: AudioBuffer?,
        maxFrames: Int = 128,
        freqBins: Int = 72,
        fftSize: Int = 512,
        minFreqHz: Float = 40f,
        logScale: Boolean = true
    ): SpectrogramData {
        if (buffer == null || buffer.frameCount < 32 || maxFrames <= 0 || freqBins <= 0) {
            return SpectrogramData.EMPTY
        }
        val mono = buffer.toMonoSamples()
        val n = highestPowerOfTwo(fftSize.coerceIn(128, 2048))
        val sr = buffer.sampleRate.coerceAtLeast(8000)
        val nyquist = (sr / 2f * 0.96f).coerceAtLeast(400f)
        val lowHz = minFreqHz.coerceIn(20f, nyquist * 0.5f)
        val cols = maxFrames.coerceIn(16, 256)
        val half = n / 2

        val hann = FloatArray(n) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))).toFloat()
        }
        val out = FloatArray(cols * freqBins)
        val real = FloatArray(n)
        val imag = FloatArray(n)
        val mag = FloatArray(half)
        val logRatio = ln((nyquist / lowHz).toDouble())
        val scale = 2f / n.toFloat()

        for (c in 0 until cols) {
            val center = ((c + 0.5) / cols.toDouble() * mono.size).toInt()
            val start = (center - n / 2).coerceIn(0, max(0, mono.size - 1))
            for (i in 0 until n) {
                val idx = start + i
                real[i] = if (idx < mono.size) mono[idx] * hann[i] else 0f
                imag[i] = 0f
            }
            AudioAnalyzer.fftInPlace(real, imag)
            for (k in 0 until half) {
                mag[k] = sqrt(real[k] * real[k] + imag[k] * imag[k]) * scale
            }
            for (b in 0 until freqBins) {
                val f0: Double
                val f1: Double
                if (logScale) {
                    f0 = lowHz * exp(logRatio * b / freqBins)
                    f1 = lowHz * exp(logRatio * (b + 1) / freqBins)
                } else {
                    f0 = lowHz + (nyquist - lowHz) * (b.toDouble() / freqBins)
                    f1 = lowHz + (nyquist - lowHz) * ((b + 1).toDouble() / freqBins)
                }
                val bin0 = ((f0 * n) / sr).toInt().coerceIn(1, half - 1)
                val bin1 = ((f1 * n) / sr).toInt().coerceIn(bin0, half - 1)
                var peak = 0f
                for (k in bin0..bin1) {
                    if (mag[k] > peak) peak = mag[k]
                }
                val db = 20f * log10(max(peak, 1e-5f))
                out[c * freqBins + b] = ((db + 84f) / 81f).coerceIn(0f, 1f)
            }
        }
        return SpectrogramData(
            frames = cols,
            bins = freqBins,
            magnitudesNorm = out,
            minFreqHz = lowHz,
            maxFreqHz = nyquist,
            sampleRate = sr,
            durationSec = buffer.durationSeconds,
            logScale = logScale
        )
    }

    private fun highestPowerOfTwo(v: Int): Int {
        var p = 1
        while (p * 2 <= v) p *= 2
        return p
    }
}
