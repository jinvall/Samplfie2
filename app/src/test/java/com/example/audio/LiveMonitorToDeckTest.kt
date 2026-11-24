package com.example.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMonitorToDeckTest {

    private fun sineChunk(size: Int, freqHz: Double = 440.0, amp: Float = 0.5f, sampleRate: Int = 8000): FloatArray {
        return FloatArray(size) { i ->
            (amp * sin(2.0 * PI * freqHz * i / sampleRate)).toFloat()
        }
    }

    @Test
    fun `live monitor updates telemetry, wraps rolling window, and streams snapshots to Deck callback`() {
        val snapshots = mutableListOf<AudioBuffer>()
        val engine = MicCaptureEngine(
            onMonitorDeckSnapshot = { buf -> snapshots += buf }
        )
        try {
            val windowSec = 2
            val sr = 8000
            val capacity = windowSec * sr // 16000 samples

            // Chunk 1: first fill, auto-sync enabled + forced snapshot
            val chunk1 = FloatArray(10_000) { 0.25f }
            val snap1 = engine.ingestLiveMonitorSamplesForTest(
                samples = chunk1,
                sampleRate = sr,
                channels = 1,
                windowSeconds = windowSec,
                syncToDeck = true,
                forceSnapshot = true
            )
            assertNotNull(snap1)
            assertEquals(1, snapshots.size)
            assertEquals(10_000, snap1!!.samples.size)
            assertTrue(engine.telemetry.value.isLiveMonitoring)
            assertTrue(engine.telemetry.value.isActiveCapture)
            assertEquals(10_000, engine.telemetry.value.monitorBufferedSamples)
            assertEquals(0.25f, engine.telemetry.value.peakLevel, 0.01f)
            assertEquals(0.25f, engine.telemetry.value.rmsLevel, 0.01f)

            // Chunk 2: overflows 16000-sample ring (10000 + 9000 = 19000 -> newest 16000 retained)
            val chunk2 = FloatArray(9_000) { 0.75f }
            val snap2 = engine.ingestLiveMonitorSamplesForTest(
                samples = chunk2,
                sampleRate = sr,
                channels = 1,
                windowSeconds = windowSec,
                syncToDeck = true,
                forceSnapshot = true
            )
            assertNotNull(snap2)
            assertEquals(2, snapshots.size)
            assertEquals(capacity, snap2!!.samples.size)
            assertEquals(capacity, engine.telemetry.value.monitorBufferedSamples)
            // Oldest 7000 samples from chunk1 (0.25f) followed by newest 9000 samples from chunk2 (0.75f)
            assertEquals(0.25f, snap2.samples[0], 0.001f)
            assertEquals(0.25f, snap2.samples[6999], 0.001f)
            assertEquals(0.75f, snap2.samples[7000], 0.001f)
            assertEquals(0.75f, snap2.samples[15999], 0.001f)
        } finally {
            engine.release()
        }
    }

    @Test
    fun `passthrough, gain, window resize, and manual vs auto deck sync behave cleanly`() {
        val snapshots = mutableListOf<AudioBuffer>()
        val engine = MicCaptureEngine(
            onMonitorDeckSnapshot = { buf -> snapshots += buf }
        )
        try {
            engine.setMonitorPassthrough(true)
            engine.setMonitorGain(0.42f)
            assertTrue(engine.telemetry.value.monitorPassthrough)
            assertEquals(0.42f, engine.telemetry.value.monitorGain, 0.001f)

            // With liveSyncToDeck = false and forceSnapshot = false, automatic callback does not fire
            val samples = sineChunk(12_000, freqHz = 330.0, amp = 0.6f, sampleRate = 8000)
            val autoSnap = engine.ingestLiveMonitorSamplesForTest(
                samples = samples,
                sampleRate = 8000,
                channels = 1,
                windowSeconds = 3,
                syncToDeck = false,
                forceSnapshot = false
            )
            assertNull(autoSnap)
            assertTrue(snapshots.isEmpty())
            assertFalse(engine.telemetry.value.liveSyncToDeck)

            // Manual snapshot for LOAD -> DECK still captures the ring buffer immediately
            val manual = engine.snapshotLiveMonitorBuffer("MANUAL_LOAD")
            assertNotNull(manual)
            assertEquals("MANUAL_LOAD", manual!!.name)
            assertEquals(12_000, manual.samples.size)
            assertTrue(manual.samples.maxOf { abs(it) } > 0.5f)

            // Shrinking monitor window from 3s (24000) -> 2s (16000) preserves latest samples cleanly
            engine.setMonitorWindowSec(2)
            assertEquals(2, engine.telemetry.value.monitorWindowSec)
            assertEquals(12_000, engine.telemetry.value.monitorBufferedSamples)

            // Feeding 8000 more samples fills the 2s window at 16000
            engine.ingestLiveMonitorSamplesForTest(
                samples = FloatArray(8_000) { 0.1f },
                sampleRate = 8000,
                channels = 1,
                windowSeconds = 2,
                syncToDeck = false,
                forceSnapshot = false
            )
            assertEquals(16_000, engine.telemetry.value.monitorBufferedSamples)
            val afterResize = engine.snapshotLiveMonitorBuffer("RESIZED")!!
            assertEquals(16_000, afterResize.samples.size)
            assertEquals(0.1f, afterResize.samples.last(), 0.001f)
        } finally {
            engine.release()
        }
    }
}
