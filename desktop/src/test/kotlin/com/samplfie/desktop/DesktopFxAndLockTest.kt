package com.samplfie.desktop

import com.samplfie.desktop.audio.SignalAnalyzer
import com.samplfie.desktop.dsp.DesktopDspProcessor
import com.samplfie.desktop.model.DesktopAudioBuffer
import com.samplfie.desktop.model.DesktopFxState
import com.samplfie.desktop.model.DesktopStompState
import com.samplfie.desktop.model.SignalWaveformType
import com.samplfie.desktop.network.SessionHandoffSnapshot
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

class DesktopFxAndLockTest {

    private fun impulseBurstBuffer(sampleRate: Int = 44100, frames: Int = 22050): DesktopAudioBuffer {
        val samples = FloatArray(frames)
        // Sharp attack ramp followed by decaying 440Hz tone in first 150ms
        val burstFrames = (sampleRate * 0.15f).toInt().coerceAtMost(frames)
        for (i in 0 until burstFrames) {
            val env = 1f - (i.toFloat() / burstFrames)
            samples[i] = sin(2.0 * PI * 440.0 * i / sampleRate).toFloat() * env * 0.8f
        }
        return DesktopAudioBuffer(samples, sampleRate, 1, "IMPULSE_BURST")
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
    fun `deck and loop refiner fades persist across wide range and survive slot reloads without snapping to 8 or 12`() {
        val ctrl = DesktopStudioController()
        ctrl.generateTestSignal(SignalWaveformType.SAWTOOTH, 220.0)

        ctrl.setDeckFades(340f, 620f)
        assertEquals(340f, ctrl.uiState.value.fadeInMs, 0.01f)
        assertEquals(620f, ctrl.uiState.value.fadeOutMs, 0.01f)

        // Bind to loop slot 0, then configure wide fades on refiner
        ctrl.sendEditorToLoopSlot(0)
        ctrl.arrangement.setLoopRefinerFades(480f, 1250f)
        assertEquals(480f, ctrl.loopRefinerState.value.fadeInMs, 0.01f)
        assertEquals(1250f, ctrl.loopRefinerState.value.fadeOutMs, 0.01f)

        // Assign another slot and reload slot 0: user-configured fades must stay intact (not snap back to 8/12)
        ctrl.sendEditorToLoopSlot(1)
        ctrl.arrangement.loadLoopSlotIntoRefiner(1)
        ctrl.arrangement.loadLoopSlotIntoRefiner(0)
        assertEquals(480f, ctrl.loopRefinerState.value.fadeInMs, 0.01f)
        assertEquals(1250f, ctrl.loopRefinerState.value.fadeOutMs, 0.01f)

        // Apply linear fades and check edge attenuation vs middle amplitude
        ctrl.applyFades(200f, 300f)
        val faded = ctrl.uiState.value.dryBuffer!!
        val edgeInPeak = faded.samples.take(100).maxOf { abs(it) }
        val midPeak = faded.samples.slice(faded.samples.size / 2 until faded.samples.size / 2 + 500).maxOf { abs(it) }
        assertTrue("Fade-in start should be much quieter than midpoint", edgeInPeak < midPeak * 0.1f)
        ctrl.shutdown()
    }

    @Test
    fun `binding deck audio to loop slot and sequencer lane locks and deep-copies sample against future deck edits`() {
        val ctrl = DesktopStudioController()
        ctrl.generateTestSignal(SignalWaveformType.SINE, 440.0)
        val originalDeck = ctrl.uiState.value.processedBuffer!!
        val originalSnapshot = originalDeck.samples.copyOf()

        // Bind to Loop Slot 0 and Sequencer Lane 0
        ctrl.sendEditorToLoopSlot(0)
        ctrl.assignEditorToSeqLane(0)

        val lockedSlot0 = ctrl.looperState.value.slots[0]
        val lockedLane0 = ctrl.sequencerState.value.lanes[0]
        assertTrue(lockedSlot0.isSampleLocked)
        assertTrue(lockedLane0.isSampleLocked)
        assertNotNull(lockedSlot0.buffer)
        assertNotSame(originalDeck.samples, lockedSlot0.buffer!!.samples)
        assertNotSame(originalDeck.samples, lockedLane0.buffer.samples)

        // Mutate the Deck aggressively: extract selection, reverse, undo, reset, and generate new frequency
        ctrl.updateSelection(0.25f, 0.65f)
        ctrl.extractSelection()
        ctrl.reverseSignal()
        ctrl.undoEdit()
        ctrl.resetToOriginal()
        ctrl.generateTestSignal(SignalWaveformType.SQUARE, 110.0)

        val afterDeck = ctrl.uiState.value.processedBuffer!!
        assertFalse(afterDeck.samples.contentEquals(originalSnapshot))

        // Locked Loop Slot 0 and Locked Sequencer Lane 0 must be 100% preserved
        assertTrue(
            "Locked loop slot samples must stay unchanged after Deck edits",
            ctrl.looperState.value.slots[0].buffer!!.samples.contentEquals(originalSnapshot)
        )
        assertTrue(
            "Locked sequencer lane 0 samples must stay unchanged after Deck edits",
            ctrl.sequencerState.value.lanes[0].buffer.samples.contentEquals(originalSnapshot)
        )

        // Unlocking Sequencer Lane 0 allows subsequent Deck loads to update it automatically
        ctrl.toggleSequencerLaneLock(0)
        assertFalse(ctrl.sequencerState.value.lanes[0].isSampleLocked)
        ctrl.generateTestSignal(SignalWaveformType.TRIANGLE, 660.0)
        assertFalse(
            "Unlocked lane 0 should follow new Deck load",
            ctrl.sequencerState.value.lanes[0].buffer.samples.contentEquals(originalSnapshot)
        )

        // Loop slot lock toggle updates lock state cleanly
        ctrl.toggleLoopSlotLock(0)
        assertFalse(ctrl.looperState.value.slots[0].isSampleLocked)
        ctrl.shutdown()
    }

    @Test
    fun `fx level gain scaling, octave, analog delay, and reverse delay on all delays work without NaNs`() {
        val dry = impulseBurstBuffer()
        val stompOff = DesktopStompState(engaged = false)

        // 1. Per-effect level scaling: level = 0 silences module output, level = 1.6 boosts over level = 0.8
        val fxZeroLevel = DesktopFxState(overdriveEnabled = true, driveAmount = 6f, overdriveLevel = 0f)
        val outZero = DesktopDspProcessor.process(dry, fxZeroLevel, stompOff)
        assertEquals(0f, outZero.peakLevel(), 1e-4f)

        val fxNormal = DesktopFxState(gainEnabled = true, gainDb = 0f, softLimiter = false, gainLevel = 0.7f)
        val fxBoost = DesktopFxState(gainEnabled = true, gainDb = 0f, softLimiter = false, gainLevel = 1.6f)
        val outNormal = DesktopDspProcessor.process(dry, fxNormal, stompOff)
        val outBoost = DesktopDspProcessor.process(dry, fxBoost, stompOff)
        assertTrue(outBoost.rmsLevel() > outNormal.rmsLevel())

        // 2. Octave effect (-1 Oct and +1 Oct)
        val sineDry = SignalAnalyzer.generateTestSignal(SignalWaveformType.SINE, 220.0, 0.4f, 44100, 1)
        val fxOctave = DesktopFxState(
            octaveEnabled = true,
            octaveSubMix = 0.85f,
            octaveUpMix = 0.55f,
            octaveDryMix = 0.2f,
            octaveLevel = 1.1f
        )
        val outOctave = DesktopDspProcessor.process(sineDry, fxOctave, stompOff)
        assertTrue(outOctave.samples.none { it.isNaN() || it.isInfinite() })
        assertTrue(outOctave.rmsLevel() > 0.05f)
        assertFalse(outOctave.samples.contentEquals(sineDry.samples))

        // 3. Analog BBD Delay tail energy
        val fxAnalog = DesktopFxState(
            analogDelayEnabled = true,
            analogDelayMs = 200f,
            analogDelayFeedback = 0.55f,
            analogDelayWarmthHz = 2200f,
            analogDelayMix = 0.6f,
            analogDelayLevel = 1.0f
        )
        val outAnalog = DesktopDspProcessor.process(dry, fxAnalog, stompOff)
        val tailEnergy = energyInRange(outAnalog.samples, (44100 * 0.18).toInt(), (44100 * 0.45).toInt())
        assertTrue("Analog delay must produce audible tail energy", tailEnergy > 1.0)

        // 4. Reverse delay produces distinct reversed echo contour on Echo, Digital Delay, and Analog Delay
        val echoFwd = DesktopDspProcessor.process(
            dry,
            DesktopFxState(echoEnabled = true, echoDelayMs = 180f, echoFeedback = 0.5f, echoMix = 0.7f, echoReverseDelay = false),
            stompOff
        )
        val echoRev = DesktopDspProcessor.process(
            dry,
            DesktopFxState(echoEnabled = true, echoDelayMs = 180f, echoFeedback = 0.5f, echoMix = 0.7f, echoReverseDelay = true),
            stompOff
        )
        assertNotEquals(echoFwd, echoRev)

        val delayFwd = DesktopDspProcessor.process(
            dry,
            DesktopFxState(delayEnabled = true, delayTimeMs = 180f, delayFeedback = 0.5f, delayMix = 0.7f, delayReverseDelay = false),
            stompOff
        )
        val delayRev = DesktopDspProcessor.process(
            dry,
            DesktopFxState(delayEnabled = true, delayTimeMs = 180f, delayFeedback = 0.5f, delayMix = 0.7f, delayReverseDelay = true),
            stompOff
        )
        assertNotEquals(delayFwd, delayRev)

        val analogRev = DesktopDspProcessor.process(
            dry,
            fxAnalog.copy(analogDelayReverseDelay = true),
            stompOff
        )
        assertNotEquals(outAnalog, analogRev)
        assertTrue(analogRev.samples.none { it.isNaN() })
    }

    @Test
    fun `handoff codec roundtrip preserves octave, analog delay, reverse delay flags, per-fx levels, and master volume`() {
        val fx = DesktopFxState(
            octaveEnabled = true,
            octaveSubMix = 0.77f,
            octaveUpMix = 0.44f,
            octaveLevel = 1.35f,
            echoEnabled = true,
            echoReverseDelay = true,
            echoLevel = 1.25f,
            delayEnabled = true,
            delayReverseDelay = true,
            delayLevel = 0.85f,
            analogDelayEnabled = true,
            analogDelayMs = 295f,
            analogDelayWarmthHz = 1950f,
            analogDelayReverseDelay = true,
            analogDelayLevel = 1.45f,
            reverbLevel = 1.15f
        )
        val snap = SessionHandoffSnapshot(
            senderName = "DESKTOP_PRO",
            stationKey = "FX_STOMP",
            fxState = fx,
            masterVolume = 0.63f,
            isMasterMuted = true,
            buffer = impulseBurstBuffer(44100, 1024)
        )
        val decoded = SessionHandoffSnapshot.decodeFromWireBytes(snap.encodeToWireBytes())
        assertNotNull(decoded)
        decoded!!
        assertEquals(0.63f, decoded.masterVolume, 0.001f)
        assertTrue(decoded.isMasterMuted)
        assertTrue(decoded.fxState.octaveEnabled)
        assertEquals(0.77f, decoded.fxState.octaveSubMix, 0.001f)
        assertEquals(1.35f, decoded.fxState.octaveLevel, 0.001f)
        assertTrue(decoded.fxState.echoReverseDelay)
        assertEquals(1.25f, decoded.fxState.echoLevel, 0.001f)
        assertTrue(decoded.fxState.delayReverseDelay)
        assertEquals(0.85f, decoded.fxState.delayLevel, 0.001f)
        assertTrue(decoded.fxState.analogDelayEnabled)
        assertEquals(295f, decoded.fxState.analogDelayMs, 0.1f)
        assertEquals(1950f, decoded.fxState.analogDelayWarmthHz, 0.1f)
        assertTrue(decoded.fxState.analogDelayReverseDelay)
        assertEquals(1.45f, decoded.fxState.analogDelayLevel, 0.001f)
        assertEquals(1.15f, decoded.fxState.reverbLevel, 0.001f)
    }
}
