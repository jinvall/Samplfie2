package com.samplfie.desktop

import com.samplfie.desktop.audio.DesktopMp3Exporter
import com.samplfie.desktop.audio.DesktopWavCodec
import com.samplfie.desktop.audio.SignalAnalyzer
import com.samplfie.desktop.model.DesktopPreset
import com.samplfie.desktop.model.DesktopRigConfig
import com.samplfie.desktop.model.DesktopStation
import com.samplfie.desktop.model.NetworkProtocol
import com.samplfie.desktop.model.SignalWaveformType
import com.samplfie.desktop.network.Smp2NetworkNode
import com.samplfie.desktop.tuner.PitchDetector
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopStudioTest {

    @Test
    fun `yin pitch detector accurately tracks A4 440Hz, E2 82_4Hz, and A4 432Hz calibration`() {
        val a4 = SignalAnalyzer.generateTestSignal(SignalWaveformType.SINE, 440.0, 0.5f, 44100, 1)
        val rA4 = PitchDetector.detectPitch(a4.monoMixed(), 44100, 440.0)
        assertTrue(rA4.detected)
        assertEquals("A", rA4.noteName)
        assertEquals(4, rA4.octave)
        assertTrue("Expected <= 2 cents offset, got ${rA4.centsOffset}", abs(rA4.centsOffset) <= 2.0)

        val e2 = SignalAnalyzer.generateTestSignal(SignalWaveformType.SAWTOOTH, 82.4069, 0.5f, 44100, 1)
        val rE2 = PitchDetector.detectPitch(e2.monoMixed(), 44100, 440.0)
        assertTrue(rE2.detected)
        assertEquals("E", rE2.noteName)
        assertEquals(2, rE2.octave)
        assertTrue("Expected E2 within 4 cents, got ${rE2.centsOffset}", abs(rE2.centsOffset) <= 4.0)

        val a432 = SignalAnalyzer.generateTestSignal(SignalWaveformType.SINE, 432.0, 0.5f, 44100, 1)
        val r432 = PitchDetector.detectPitch(a432.monoMixed(), 44100, 432.0)
        assertTrue(r432.detected)
        assertEquals("A4", r432.fullNote)
        assertTrue(abs(r432.centsOffset) <= 2.0)

        val silence = FloatArray(2048)
        assertFalse(PitchDetector.detectPitch(silence, 44100).detected)
    }

    @Test
    fun `signal editor selection, zero-crossing, fades, reverse, dc fix, normalize, and undo work`() {
        val ctrl = DesktopStudioController()
        ctrl.generateTestSignal(SignalWaveformType.SAWTOOTH, 220.0)
        val initialFrames = ctrl.uiState.value.dryBuffer!!.frameCount
        assertTrue(initialFrames > 10_000)

        ctrl.updateSelection(0.2f, 0.7f)
        ctrl.snapSelectionZeroCrossing()
        ctrl.extractSelection()
        val trimmedFrames = ctrl.uiState.value.dryBuffer!!.frameCount
        assertTrue(trimmedFrames < initialFrames)
        assertTrue(ctrl.uiState.value.canUndo)

        ctrl.applyFades(15f, 15f)
        ctrl.reverseSignal()
        ctrl.removeDcOffset()
        ctrl.normalizeSignal()
        assertTrue(ctrl.uiState.value.processedBuffer!!.peakLevel() > 0.85f)

        ctrl.undoEdit()
        ctrl.resetToOriginal()
        assertEquals(initialFrames, ctrl.uiState.value.dryBuffer!!.frameCount)
    }

    @Test
    fun `dsp preset, stomp kill switches, and wav plus mp3 export succeed`() {
        val ctrl = DesktopStudioController()
        ctrl.generateTestSignal(SignalWaveformType.SQUARE, 330.0)
        ctrl.applyPreset(DesktopPreset.ACID_WAH)
        assertEquals(3, ctrl.uiState.value.fxState.activeCount)

        ctrl.updateStomp { copy(engaged = true, killDrive = true, toneTilt = 0.6f) }
        val proc = ctrl.uiState.value.processedBuffer!!
        assertTrue(proc.frameCount > 0)

        val tmpDir = Files.createTempDirectory("samplfie2_dt_export").toFile()
        val wavFile = File(tmpDir, "test_signal.wav")
        val mp3File = File(tmpDir, "test_signal.mp3")

        val wavRes = ctrl.exportWav(wavFile)
        assertTrue(wavRes.isSuccess)
        val decoded = DesktopWavCodec.readWav(wavFile)
        assertEquals(proc.sampleRate, decoded.sampleRate)
        assertEquals(proc.frameCount, decoded.frameCount)

        val mp3Res = DesktopMp3Exporter.encodeToFile(proc, mp3File, 192)
        assertTrue(mp3Res.isSuccess)
        assertTrue(mp3File.length() > 500L)

        tmpDir.deleteRecursively()
        ctrl.shutdown()
    }

    @Test
    fun `smp2 tcp loopback bridge receives android-compatible packets and loads into editor`() {
        val freePort = ServerSocket(0).use { it.localPort }
        val receiver = Smp2NetworkNode()
        val sender = Smp2NetworkNode()

        receiver.startListening(NetworkProtocol.TCP, freePort)
        Thread.sleep(150)

        val tone = SignalAnalyzer.generateTestSignal(SignalWaveformType.SINE, 440.0, 0.35f, 44100, 1)
        val cfg = DesktopRigConfig(
            sampleRate = 44100,
            channels = 1,
            bufferSize = 512,
            protocol = NetworkProtocol.TCP,
            txHost = "127.0.0.1",
            txPort = freePort
        )
        sender.transmitBuffer(tone, cfg)

        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline && receiver.state.value.rxPackets == 0L) {
            Thread.sleep(40)
        }
        Thread.sleep(120)

        val rxBuf = receiver.captureReceivedBuffer("ANDROID_LOOPBACK")
        assertNotNull(rxBuf)
        assertTrue(rxBuf!!.frameCount > 1000)
        val pitch = PitchDetector.detectPitch(rxBuf.monoMixed().copyOfRange(512, 4608), 44100)
        assertTrue(pitch.detected)
        assertEquals("A4", pitch.fullNote)

        receiver.stopListening()
    }

    @Test
    fun `station switching triggers deck tuner analysis cleanly`() {
        val ctrl = DesktopStudioController()
        ctrl.generateTestSignal(SignalWaveformType.SINE, 329.63)
        ctrl.selectStation(DesktopStation.TUNER)
        val r = ctrl.uiState.value.tunerReading
        assertTrue(r.detected)
        assertEquals("E4", r.fullNote)
        ctrl.shutdown()
    }
}
