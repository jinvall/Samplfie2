package com.samplfie.desktop

import com.samplfie.desktop.audio.SignalAnalyzer
import com.samplfie.desktop.model.SignalWaveformType
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLiveMonitorTest {

    private fun testSamples(frames: Int, sampleRate: Int = 8000, freqHz: Double = 330.0, amp: Float = 0.65f): FloatArray {
        return FloatArray(frames) { i -> (amp * sin(2.0 * PI * freqHz * i / sampleRate)).toFloat() }
    }

    @Test
    fun liveMonitor_loadsDeckBufferAutomaticallyAndManuallyWhilePreservingLoopAndLockedSlots() {
        val controller = DesktopStudioController()
        try {
            // 1. Bind and lock a distinct sample in Loop Slot A and Sequencer Lane 1
            val lockedLoopSample = SignalAnalyzer.generateTestSignal(SignalWaveformType.SQUARE, 180.0, 0.25f, 8000, 1)
                .copy(name = "LOCKED_LOOP_SAMPLE")
            controller.arrangement.assignBufferToLoopSlot(0, lockedLoopSample, "LOCKED_LOOP_SAMPLE")
            val lockedLaneSample = SignalAnalyzer.generateTestSignal(SignalWaveformType.SAWTOOTH, 240.0, 0.25f, 8000, 1)
                .copy(name = "LOCKED_LANE_SAMPLE")
            controller.arrangement.setSequencerSampleForLane(0, lockedLaneSample, lockSample = true)

            assertTrue(controller.looperState.value.slots[0].isSampleLocked)
            assertTrue(controller.sequencerState.value.lanes[0].isSampleLocked)

            // 2. Engage loop playback on Deck before ingesting Live Monitor
            controller.soundEngine.setLooping(true)
            controller.soundEngine.startPlayback()
            assertTrue(controller.playbackState.value.isPlaying)
            assertTrue(controller.playbackState.value.isLooping)

            // 3. Configure Live Monitor passthrough & gain
            controller.setMonitorPassthrough(true)
            controller.setMonitorGain(0.55f)
            assertTrue(controller.micState.value.monitorPassthrough)
            assertEquals(0.55f, controller.micState.value.monitorGain, 0.001f)

            // 4. Ingest live monitor audio with Auto -> Deck enabled
            val monSamples = testSamples(6000, sampleRate = 8000, freqHz = 520.0, amp = 0.7f)
            val snap = controller.ingestLiveMonitorForTest(
                samples = monSamples,
                sampleRate = 8000,
                channels = 1,
                windowSeconds = 5,
                syncToDeck = true,
                forceSnapshot = true
            )
            assertNotNull(snap)

            val deadline = System.currentTimeMillis() + 3000
            while (controller.uiState.value.dryBuffer?.name != "LIVE_MONITOR" && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }

            assertEquals("LIVE_MONITOR", controller.uiState.value.dryBuffer?.name)
            assertEquals(6000, controller.uiState.value.dryBuffer?.samples?.size)
            assertNotNull(controller.uiState.value.processedBuffer)
            assertEquals(6000, controller.uiState.value.processedBuffer?.samples?.size)

            // Active loop playback must continue uninterrupted
            assertTrue("Loop playback should remain playing", controller.playbackState.value.isPlaying)
            assertTrue("Loop flag should remain enabled", controller.playbackState.value.isLooping)

            // Locked Loop Slot A and locked Sequencer Lane 1 must retain their locked audio
            assertEquals("LOCKED_LOOP_SAMPLE", controller.looperState.value.slots[0].buffer?.name)
            assertEquals("LOCKED_LANE_SAMPLE", controller.sequencerState.value.lanes[0].buffer.name)
            assertTrue(controller.sequencerState.value.lanes[0].buffer.samples.contentEquals(lockedLaneSample.samples))

            // 5. Turn Auto -> Deck OFF: ingesting new chunk does NOT overwrite Deck automatically
            controller.setLiveMonitorSyncToDeck(false)
            assertFalse(controller.micState.value.liveSyncToDeck)
            val secondChunk = testSamples(2000, sampleRate = 8000, freqHz = 880.0, amp = 0.4f)
            val autoSuppressed = controller.ingestLiveMonitorForTest(
                samples = secondChunk,
                sampleRate = 8000,
                channels = 1,
                windowSeconds = 5,
                syncToDeck = false,
                forceSnapshot = false
            )
            assertNull(autoSuppressed)
            assertEquals(6000, controller.uiState.value.dryBuffer?.samples?.size)
            assertEquals(8000, controller.micState.value.bufferedSamples)

            // 6. Explicit manual LOAD -> DECK loads all 8000 buffered samples while keeping loop active
            assertTrue(controller.loadLiveMonitorToDeck())
            assertEquals("LIVE_MON_DECK", controller.uiState.value.dryBuffer?.name)
            assertEquals(8000, controller.uiState.value.dryBuffer?.samples?.size)
            assertTrue(controller.playbackState.value.isPlaying)
            assertTrue(controller.playbackState.value.isLooping)
        } finally {
            controller.shutdown()
        }
    }
}
