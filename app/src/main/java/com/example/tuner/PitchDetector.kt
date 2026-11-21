package com.example.tuner

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class TuningPreset(val label: String, val notes: List<Pair<String, Double>>) {
    CHROMATIC(
        "Chromatic",
        listOf("C4" to 261.63, "D4" to 293.66, "E4" to 329.63, "F4" to 349.23, "G4" to 392.00, "A4" to 440.00, "B4" to 493.88)
    ),
    GUITAR_STD(
        "Guitar Std",
        listOf("E2" to 82.41, "A2" to 110.00, "D3" to 146.83, "G3" to 196.00, "B3" to 246.94, "E4" to 329.63)
    ),
    BASS_4(
        "Bass 4-Str",
        listOf("E1" to 41.20, "A1" to 55.00, "D2" to 73.42, "G2" to 98.00)
    ),
    DROP_D(
        "Drop D",
        listOf("D2" to 73.42, "A2" to 110.00, "D3" to 146.83, "G3" to 196.00, "B3" to 246.94, "E4" to 329.63)
    ),
    UKULELE(
        "Ukulele GCEA",
        listOf("G4" to 392.00, "C4" to 261.63, "E4" to 329.63, "A4" to 440.00)
    )
}

enum class TunerInputSource(val label: String) {
    DECK_BUFFER("DECK SIGNAL"),
    LIVE_MIC("LIVE MIC"),
    SMP2_STREAM("SMP2 PEER")
}

data class TunerReading(
    val detected: Boolean = false,
    val frequencyHz: Double = 0.0,
    val noteName: String = "--",
    val octave: Int = 0,
    val centsOffset: Double = 0.0,
    val targetFrequencyHz: Double = 0.0,
    val confidence: Float = 0f
) {
    val fullNote: String get() = if (detected) "$noteName$octave" else "--"
    val isInTune: Boolean get() = detected && abs(centsOffset) <= 3.0
}

/**
 * Pure-Kotlin YIN Fundamental Pitch Detector (32 Hz - 2200 Hz) for Samplfie2 Android.
 */
object PitchDetector {
    private val NOTE_NAMES = arrayOf(
        "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    )

    fun detectPitch(
        monoSamples: FloatArray,
        sampleRate: Int,
        a4ReferenceHz: Double = 440.0,
        yinThreshold: Float = 0.16f
    ): TunerReading {
        if (monoSamples.size < 512 || sampleRate <= 0) return TunerReading()

        var sumSq = 0.0
        for (s in monoSamples) sumSq += s * s
        val rms = sqrt(sumSq / monoSamples.size)
        if (rms < 0.0025) return TunerReading()

        val minFreqHz = 32.0
        val maxFreqHz = 2200.0
        val minTau = (sampleRate / maxFreqHz).toInt().coerceAtLeast(2)
        val maxTau = (sampleRate / minFreqHz).toInt().coerceAtMost(monoSamples.size / 2 - 2)
        if (maxTau <= minTau + 2) return TunerReading()

        val yinSize = maxTau + 1
        val diff = FloatArray(yinSize)
        val windowLen = monoSamples.size - maxTau
        for (tau in 1 until yinSize) {
            var sum = 0f
            for (j in 0 until windowLen) {
                val delta = monoSamples[j] - monoSamples[j + tau]
                sum += delta * delta
            }
            diff[tau] = sum
        }

        val cmndf = FloatArray(yinSize)
        cmndf[0] = 1f
        var runningSum = 0f
        for (tau in 1 until yinSize) {
            runningSum += diff[tau]
            cmndf[tau] = if (runningSum > 1e-12f) diff[tau] * tau / runningSum else 1f
        }

        var bestTau = -1
        var tau = minTau
        while (tau < maxTau) {
            if (cmndf[tau] < yinThreshold) {
                while (tau + 1 < maxTau && cmndf[tau + 1] < cmndf[tau]) {
                    tau++
                }
                bestTau = tau
                break
            }
            tau++
        }

        if (bestTau < 0) {
            var minVal = Float.MAX_VALUE
            for (t in minTau until maxTau) {
                if (cmndf[t] < minVal) {
                    minVal = cmndf[t]
                    bestTau = t
                }
            }
            if (bestTau < 0 || minVal > 0.35f) return TunerReading()
        }

        val refinedTau: Double = if (bestTau in 1 until maxTau) {
            val s0 = cmndf[bestTau - 1].toDouble()
            val s1 = cmndf[bestTau].toDouble()
            val s2 = cmndf[bestTau + 1].toDouble()
            val denom = 2.0 * (2.0 * s1 - s2 - s0)
            if (abs(denom) > 1e-9) {
                bestTau + ((s2 - s0) / denom).coerceIn(-0.5, 0.5)
            } else {
                bestTau.toDouble()
            }
        } else {
            bestTau.toDouble()
        }

        val freqHz = sampleRate / refinedTau
        if (freqHz < minFreqHz || freqHz > maxFreqHz) return TunerReading()

        val confidence = (1f - cmndf[bestTau]).coerceIn(0f, 1f)
        return frequencyToReading(freqHz, a4ReferenceHz, confidence)
    }

    fun frequencyToReading(
        freqHz: Double,
        a4ReferenceHz: Double = 440.0,
        confidence: Float = 1f
    ): TunerReading {
        if (freqHz <= 0.0 || a4ReferenceHz <= 0.0) return TunerReading()
        val midiExact = 69.0 + 12.0 * (ln(freqHz / a4ReferenceHz) / ln(2.0))
        val midiNearest = midiExact.roundToInt()
        val targetFreq = a4ReferenceHz * 2.0.pow((midiNearest - 69) / 12.0)
        val cents = (midiExact - midiNearest) * 100.0
        val noteIndex = ((midiNearest % 12) + 12) % 12
        val octave = (midiNearest / 12) - 1

        return TunerReading(
            detected = true,
            frequencyHz = freqHz,
            noteName = NOTE_NAMES[noteIndex],
            octave = octave,
            centsOffset = cents,
            targetFrequencyHz = targetFreq,
            confidence = confidence
        )
    }
}
