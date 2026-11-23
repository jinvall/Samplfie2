package com.example.dsp

import com.example.audio.AudioBuffer
import com.example.network.SessionHandoffSnapshot
import com.example.studio.StudioEngine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FxChainAndLockParityTest {

    private fun impulseBurstBuffer(sampleRate: Int = 44100, frames: Int = 22050): AudioBuffer {
        val samples = FloatArray(frames)
        val burstFrames = (sampleRate * 0.15f).toInt().coerceAtMost(frames)
        for (i in 0 until burstFrames) {
            val env = 1f - (i.toFloat() / burstFrames)
            samples[i] = sin(2.0 * PI * 440.0 * i / sampleRate).toFloat() * env * 0.8f
        }
        return AudioBuffer(samples, sampleRate, 1, "IMPULSE_BURST")
    }

    private fun sineBuffer(freqHz: Double = 220.0, sampleRate: Int = 44100, frames: Int = 22050): AudioBuffer {
        val samples = FloatArray(frames) { i ->
            (sin(2.0 * PI * freqHz * i / sampleRate) * 0.7).toFloat()
        }
        return AudioBuffer(samples, sampleRate, 1, "SINE_$freqHz")
    }

    private fun energyInRange(samples: FloatArray, start: Int, end: Int): Double {
        var sum = 0.0
        val lo = start.coerceIn(0, samples.size)
        val hi = end.coerceIn(lo, samples.size)
        for (i in lo until hi) {
            sum += samples[i] * samples[i]
        }
        return sum
    }

    @Test
    fun `studio engine locks loop slots and sequencer lanes with deep copied buffers`() {
        val engine = StudioEngine()
        val src = sineBuffer(440.0)
        val expected = src.samples.copyOf()

        engine.assignBufferToLoopSlot(0, src, "DECK_BIND")
        engine.setSequencerSampleForLane(0, src, lockSample = true)

        val slot0 = engine.looperState.value.slots[0]
        val lane0 = engine.sequencerState.value.lanes[0]
        assertTrue(slot0.isSampleLocked)
        assertTrue(lane0.isSampleLocked)
        assertNotNull(slot0.buffer)
        assertNotSame(src.samples, slot0.buffer!!.samples)
        assertNotSame(src.samples, lane0.buffer.samples)

        // Mutate the original array & try unlocked update while locked
        src.samples.fill(0f)
        val replacement = sineBuffer(110.0)
        engine.setSequencerSampleIfUnlocked(0, replacement)

        assertTrue(engine.looperState.value.slots[0].buffer!!.samples.contentEquals(expected))
        assertTrue(engine.sequencerState.value.lanes[0].buffer.samples.contentEquals(expected))

        // Unlock lane 0 and verify unlocked updates now take effect
        engine.toggleSequencerLaneLock(0)
        assertFalse(engine.sequencerState.value.lanes[0].isSampleLocked)
        engine.setSequencerSampleIfUnlocked(0, replacement)
        assertTrue(engine.sequencerState.value.lanes[0].buffer.samples.contentEquals(replacement.samples))

        engine.toggleLoopSlotLock(0)
        assertFalse(engine.looperState.value.slots[0].isSampleLocked)
    }

    @Test
    fun `linear fades apply cleanly across wide ms ranges without NaN`() {
        val buf = sineBuffer(330.0, 44100, 44100)
        val faded = buf.applyFadeInOut(340f, 1250f)
        val startPeak = faded.samples.take(150).maxOf { abs(it) }
        val midPeak = faded.samples.slice(20000 until 21000).maxOf { abs(it) }
        val endPeak = faded.samples.takeLast(150).maxOf { abs(it) }
        assertTrue(startPeak < midPeak * 0.1f)
        assertTrue(endPeak < midPeak * 0.1f)
    }

    @Test
    fun `14-module rack supports per-fx level gain, octave, analog delay, and reverse delay on all delays`() {
        val dry = impulseBurstBuffer()
        val stompOff = StompboxMasterState(engaged = false)

        // Per-effect level scaling
        val rackZero = FxRackState(overdrive = OverdriveParams(enabled = true, drive = 6f, level = 0f))
        val outZero = DspChainEngine.process(dry, rackZero, stompOff)
        assertEquals(0f, outZero.computePeakAndRms().first, 1e-4f)

        val rackLow = FxRackState(gain = GainParams(enabled = true, gainDb = 0f, softLimiter = false, level = 0.6f))
        val rackBoost = FxRackState(gain = GainParams(enabled = true, gainDb = 0f, softLimiter = false, level = 1.6f))
        val outLow = DspChainEngine.process(dry, rackLow, stompOff)
        val outBoost = DspChainEngine.process(dry, rackBoost, stompOff)
        assertTrue(outBoost.computePeakAndRms().second > outLow.computePeakAndRms().second)

        // Octave
        val tone = sineBuffer(220.0)
        val outOctave = DspChainEngine.process(
            tone,
            FxRackState(
                octave = OctaveParams(
                    enabled = true,
                    subOctaveMix = 0.85f,
                    octaveUpMix = 0.5f,
                    dryMix = 0.25f,
                    level = 1.15f
                )
            ),
            stompOff
        )
        assertTrue(outOctave.samples.none { it.isNaN() || it.isInfinite() })
        assertTrue(outOctave.computePeakAndRms().second > 0.05f)
        assertFalse(outOctave.samples.contentEquals(tone.samples))

        // Analog BBD Delay
        val analogFwdRack = FxRackState(
            analogDelay = AnalogDelayParams(
                enabled = true,
                delayMs = 200f,
                feedback = 0.55f,
                warmthHz = 2200f,
                mix = 0.6f,
                reverseDelay = false,
                level = 1.0f
            )
        )
        val analogFwd = DspChainEngine.process(dry, analogFwdRack, stompOff)
        val tailEnergy = energyInRange(analogFwd.samples, (44100 * 0.18).toInt(), (44100 * 0.45).toInt())
        assertTrue("Analog delay must produce delayed tail energy", tailEnergy > 1.0)

        // Reverse delay on Echo, Delay, Analog Delay
        val echoFwd = DspChainEngine.process(
            dry,
            FxRackState(echo = EchoParams(enabled = true, delayMs = 180f, feedback = 0.5f, mix = 0.7f, reverseDelay = false)),
            stompOff
        )
        val echoRev = DspChainEngine.process(
            dry,
            FxRackState(echo = EchoParams(enabled = true, delayMs = 180f, feedback = 0.5f, mix = 0.7f, reverseDelay = true)),
            stompOff
        )
        assertNotEquals(echoFwd, echoRev)

        val delayFwd = DspChainEngine.process(
            dry,
            FxRackState(delay = DelayParams(enabled = true, delayMs = 180f, feedback = 0.5f, mix = 0.7f, reverseDelay = false)),
            stompOff
        )
        val delayRev = DspChainEngine.process(
            dry,
            FxRackState(delay = DelayParams(enabled = true, delayMs = 180f, feedback = 0.5f, mix = 0.7f, reverseDelay = true)),
            stompOff
        )
        assertNotEquals(delayFwd, delayRev)

        val analogRev = DspChainEngine.process(
            dry,
            analogFwdRack.copy(analogDelay = analogFwdRack.analogDelay.copy(reverseDelay = true)),
            stompOff
        )
        assertNotEquals(analogFwd, analogRev)
        assertTrue(analogRev.samples.none { it.isNaN() })
    }

    @Test
    fun `session handoff codec preserves octave, analog delay, reverse delay, and per-fx levels`() {
        val rack = FxRackState(
            octave = OctaveParams(enabled = true, subOctaveMix = 0.77f, octaveUpMix = 0.44f, level = 1.35f),
            echo = EchoParams(enabled = true, reverseDelay = true, level = 1.25f),
            delay = DelayParams(enabled = true, reverseDelay = true, level = 0.85f),
            analogDelay = AnalogDelayParams(
                enabled = true,
                delayMs = 295f,
                warmthHz = 1950f,
                reverseDelay = true,
                level = 1.45f
            ),
            reverb = ReverbParams(level = 1.15f)
        )
        val snap = SessionHandoffSnapshot(
            senderName = "ANDROID_PRO",
            stationKey = "FX_RACK",
            fxRack = rack,
            masterVolume = 0.63f,
            isMasterMuted = true,
            buffer = impulseBurstBuffer(44100, 1024)
        )
        val decoded = SessionHandoffSnapshot.decodeFromWireBytes(snap.encodeToWireBytes())
        assertNotNull(decoded)
        decoded!!
        assertEquals(0.63f, decoded.masterVolume, 0.001f)
        assertTrue(decoded.isMasterMuted)
        assertTrue(decoded.fxRack.octave.enabled)
        assertEquals(0.77f, decoded.fxRack.octave.subOctaveMix, 0.001f)
        assertEquals(1.35f, decoded.fxRack.octave.level, 0.001f)
        assertTrue(decoded.fxRack.echo.reverseDelay)
        assertEquals(1.25f, decoded.fxRack.echo.level, 0.001f)
        assertTrue(decoded.fxRack.delay.reverseDelay)
        assertEquals(0.85f, decoded.fxRack.delay.level, 0.001f)
        assertTrue(decoded.fxRack.analogDelay.enabled)
        assertEquals(295f, decoded.fxRack.analogDelay.delayMs, 0.1f)
        assertEquals(1950f, decoded.fxRack.analogDelay.warmthHz, 0.1f)
        assertTrue(decoded.fxRack.analogDelay.reverseDelay)
        assertEquals(1.45f, decoded.fxRack.analogDelay.level, 0.001f)
        assertEquals(1.15f, decoded.fxRack.reverb.level, 0.001f)
    }
}
