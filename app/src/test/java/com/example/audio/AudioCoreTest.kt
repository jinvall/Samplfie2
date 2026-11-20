package com.example.audio

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCoreTest {

    private fun generateSineBuffer(
        freqHz: Float = 440f,
        sampleRate: Int = 44100,
        channels: Int = 1,
        durationSec: Float = 0.5f,
        amplitude: Float = 0.6f
    ): AudioBuffer {
        val frames = (sampleRate * durationSec).toInt()
        val samples = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (sin(2.0 * PI * freqHz * f / sampleRate) * amplitude).toFloat()
            for (c in 0 until channels) {
                samples[f * channels + c] = v
            }
        }
        return AudioBuffer(samples, sampleRate, channels, "SineTest")
    }

    @Test
    fun `wav codec round trip preserves mono and stereo pcm samples`() {
        val stereo = generateSineBuffer(freqHz = 880f, sampleRate = 48000, channels = 2, durationSec = 0.25f)
        val tempFile = File.createTempFile("samplfie_test_", ".wav")
        try {
            WavCodec.writeWav(stereo, tempFile)
            assertTrue(tempFile.exists() && tempFile.length() > 44)

            val decoded = WavCodec.readWav(tempFile)
            assertEquals(48000, decoded.sampleRate)
            assertEquals(2, decoded.channels)
            assertEquals(stereo.frameCount, decoded.frameCount)

            for (i in decoded.samples.indices) {
                assertEquals(stereo.samples[i], decoded.samples[i], 0.001f)
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `extract selection trims buffer accurately`() {
        val buf = generateSineBuffer(sampleRate = 44100, channels = 1, durationSec = 1.0f)
        val extracted = buf.extractSelection(0.25f, 0.75f)
        assertEquals(22050f, extracted.frameCount.toFloat(), 2f)
        assertEquals(0.5f, extracted.durationSeconds, 0.01f)
    }

    @Test
    fun `loop sample refinement helpers normalize, fade, reverse, dc fix, and snap zero crossing`() {
        val base = generateSineBuffer(freqHz = 200f, sampleRate = 44100, channels = 1, durationSec = 0.2f, amplitude = 0.25f)
        val biased = base.copy(samples = FloatArray(base.samples.size) { base.samples[it] + 0.15f })

        val dcFixed = biased.removeDcOffset()
        assertTrue(abs(dcFixed.samples.average()) < 0.01)

        val normalized = dcFixed.normalizePeak(0.95f)
        assertEquals(0.95f, normalized.computePeakAndRms().first, 0.02f)

        val faded = normalized.applyFadeInOut(20f, 20f)
        assertEquals(0f, faded.samples.first(), 0.001f)
        assertEquals(0f, faded.samples.last(), 0.001f)

        val rev = normalized.reversed()
        assertEquals(normalized.samples.first(), rev.samples.last(), 0.0001f)
        assertEquals(normalized.samples.last(), rev.samples.first(), 0.0001f)

        val snapped = normalized.snapFractionToZeroCrossing(0.31f, searchWindowFrames = 400)
        val frameIdx = (snapped * normalized.frameCount).toInt().coerceIn(1, normalized.frameCount - 1)
        assertTrue(abs(normalized.samples[frameIdx]) < 0.15f)
    }

    @Test
    fun `channel conversion and resampling preserve duration and energy`() {
        val mono = generateSineBuffer(freqHz = 440f, sampleRate = 44100, channels = 1, durationSec = 0.4f)
        val stereo = mono.convertChannels(2)
        assertEquals(2, stereo.channels)
        assertEquals(mono.frameCount, stereo.frameCount)

        val resampled = stereo.resample(48000)
        assertEquals(48000, resampled.sampleRate)
        assertEquals(0.4f, resampled.durationSeconds, 0.01f)
        val (peak, rms) = resampled.computePeakAndRms()
        assertTrue(peak > 0.5f && rms > 0.3f)
    }

    @Test
    fun `fft spectrum analyzer peaks at correct frequency band for 1kHz sine`() {
        val buf = generateSineBuffer(freqHz = 1000f, sampleRate = 44100, channels = 1, durationSec = 0.5f, amplitude = 0.8f)
        val bands = AudioAnalyzer.computeSpectrumBands(buf, centerFrame = buf.frameCount / 2, fftSize = 1024, bandCount = 32)
        assertEquals(32, bands.size)
        val peakBandIndex = bands.indices.maxByOrNull { bands[it] } ?: -1
        assertTrue("Expected peak band around 14..20 for 1kHz, got $peakBandIndex", peakBandIndex in 14..20)
        assertTrue(bands[peakBandIndex] > 0.75f)

        val peaks = AudioAnalyzer.computeWaveformPeaks(buf, 64)
        assertEquals(64, peaks.maxPeaks.size)
        assertTrue(peaks.maxPeaks.all { abs(it) <= 1f })
    }
}
