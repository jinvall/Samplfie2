package com.example.dsp

import com.example.audio.AudioBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspEngineTest {

    private fun impulseAndToneBuffer(
        sampleRate: Int = 44100,
        channels: Int = 1,
        durationSec: Float = 0.5f,
        freqHz: Float = 440f,
        amplitude: Float = 0.5f
    ): AudioBuffer {
        val frames = (sampleRate * durationSec).toInt()
        val samples = FloatArray(frames * channels)
        for (f in 0 until frames) {
            // Burst tone in first half, quiet floor in second half
            val env = if (f < frames / 2) 1f else 0.01f
            val v = (sin(2.0 * PI * freqHz * f / sampleRate) * amplitude * env).toFloat()
            for (c in 0 until channels) {
                samples[f * channels + c] = v
            }
        }
        return AudioBuffer(samples, sampleRate, channels, "DspTest")
    }

    @Test
    fun `master bypass returns identical audio`() {
        val src = impulseAndToneBuffer()
        val rack = FxPresets.build(FxPreset.ACID_WAH).copy(masterBypass = true)
        val out = DspChainEngine.process(src, rack)
        assertEquals(src, out)
    }

    @Test
    fun `noise removal attenuates quiet noise floor while preserving signal`() {
        val src = impulseAndToneBuffer()
        val out = DspChainEngine.process(
            src,
            FxRackState(noiseRemoval = NoiseRemovalParams(enabled = true, thresholdDb = -26f, reductionDb = 30f))
        )
        val tailStart = (src.frameCount * 0.75f).toInt()
        val srcTailRms = src.samples.slice(tailStart until src.samples.size).map { it * it }.average()
        val outTailRms = out.samples.slice(tailStart until out.samples.size).map { it * it }.average()
        assertTrue("Expected noise gate to attenuate tail noise", outTailRms < srcTailRms * 0.35)
    }

    @Test
    fun `pitch and speed modify duration and pitch independently and in vinyl mode`() {
        val src = impulseAndToneBuffer(durationSec = 0.5f)

        val fastOut = DspChainEngine.process(
            src,
            FxRackState(pitchSpeed = PitchSpeedParams(enabled = true, pitchSemitones = 0f, speedFactor = 2.0f, vinylLink = false))
        )
        assertEquals(0.25f, fastOut.durationSeconds, 0.04f)

        val pitchedOut = DspChainEngine.process(
            src,
            FxRackState(pitchSpeed = PitchSpeedParams(enabled = true, pitchSemitones = 7f, speedFactor = 1.0f, vinylLink = false))
        )
        assertEquals(0.5f, pitchedOut.durationSeconds, 0.06f)
        assertFalse(pitchedOut.samples.contentEquals(src.samples))

        val vinylSlow = DspChainEngine.process(
            src,
            FxRackState(pitchSpeed = PitchSpeedParams(enabled = true, speedFactor = 0.5f, vinylLink = true))
        )
        assertEquals(1.0f, vinylSlow.durationSeconds, 0.03f)
    }

    @Test
    fun `gain normalizes and boosts cleanly without clipping above 1_0`() {
        val src = impulseAndToneBuffer(amplitude = 0.25f)
        val out = DspChainEngine.process(
            src,
            FxRackState(gain = GainParams(enabled = true, gainDb = 12f, normalize = true, softLimiter = true))
        )
        val (peak, _) = out.computePeakAndRms()
        assertTrue("Expected peak boost", peak > 0.85f && peak <= 1.0f)
    }

    @Test
    fun `all remaining 9 fx modules process mono and stereo without NaN or silence`() {
        val configs = listOf(
            "Overdrive" to FxRackState(overdrive = OverdriveParams(enabled = true, drive = 8f)),
            "Wah" to FxRackState(wah = WahParams(enabled = true, rateHz = 2.0f, resonanceQ = 4.0f)),
            "Phaser" to FxRackState(phaser = PhaserParams(enabled = true, rateHz = 1.2f, feedback = 0.6f)),
            "Flanger" to FxRackState(flanger = FlangerParams(enabled = true, rateHz = 0.8f, depthMs = 3.5f)),
            "Chorus" to FxRackState(chorus = ChorusParams(enabled = true, rateHz = 1.4f, depthMs = 8.0f)),
            "Rotary" to FxRackState(rotarySpeaker = RotaryParams(enabled = true, rateHz = 4.5f, depth = 0.8f)),
            "Echo" to FxRackState(echo = EchoParams(enabled = true, delayMs = 120f, feedback = 0.5f)),
            "Delay" to FxRackState(delay = DelayParams(enabled = true, delayMs = 100f, stereoSpread = 0.7f)),
            "Reverb" to FxRackState(reverb = ReverbParams(enabled = true, roomSize = 0.7f, mix = 0.5f))
        )

        for (channels in listOf(1, 2)) {
            val src = impulseAndToneBuffer(channels = channels, durationSec = 0.4f)
            for ((name, rack) in configs) {
                val out = DspChainEngine.process(src, rack)
                assertEquals("$name frameCount mismatch", src.frameCount, out.frameCount)
                assertEquals("$name channels mismatch", channels, out.channels)
                assertTrue("$name produced NaN", out.samples.none { it.isNaN() || it.isInfinite() })
                val (peak, _) = out.computePeakAndRms()
                assertTrue("$name produced silence", peak > 0.05f)
                assertFalse("$name left buffer unchanged", out.samples.contentEquals(src.samples))
                // Verify tail energy added for Echo/Delay/Reverb
                if (name in listOf("Echo", "Delay", "Reverb")) {
                    val tailPeak = out.samples.slice((out.samples.size * 0.7).toInt() until out.samples.size).maxOf { abs(it) }
                    assertTrue("$name did not sustain tail energy", tailPeak > 0.02f)
                }
            }
        }
    }
}
