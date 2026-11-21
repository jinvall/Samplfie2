package com.samplfie.desktop

import com.samplfie.desktop.audio.DesktopMp3Exporter
import com.samplfie.desktop.audio.DesktopWavCodec
import com.samplfie.desktop.audio.SignalAnalyzer
import com.samplfie.desktop.midi.VmcEventPacket
import com.samplfie.desktop.midi.VmcMappingRule
import com.samplfie.desktop.midi.VmcMsgType
import com.samplfie.desktop.midi.VmcTarget
import com.samplfie.desktop.model.DesktopLiveMode
import com.samplfie.desktop.model.DesktopPreset
import com.samplfie.desktop.model.DesktopRigConfig
import com.samplfie.desktop.model.DesktopStation
import com.samplfie.desktop.model.NetworkProtocol
import com.samplfie.desktop.model.SignalWaveformType
import com.samplfie.desktop.network.SessionHandoffSnapshot
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
        ctrl.shutdown()
    }

    @Test
    fun `loop playback persists through edits, selection extractions, undo, and fx updates until stop`() {
        val ctrl = DesktopStudioController()
        ctrl.generateTestSignal(SignalWaveformType.SAWTOOTH, 220.0)
        ctrl.soundEngine.setLooping(true)
        ctrl.soundEngine.startPlayback()

        assertTrue(ctrl.playbackState.value.isPlaying)
        assertTrue(ctrl.playbackState.value.isLooping)

        ctrl.updateSelection(0.15f, 0.75f)
        ctrl.extractSelection()
        assertTrue("Loop must keep playing after selection extraction", ctrl.playbackState.value.isPlaying)
        assertTrue(ctrl.playbackState.value.isLooping)

        ctrl.reverseSignal()
        ctrl.normalizeSignal()
        ctrl.updateFx { copy(overdriveEnabled = true, driveAmount = 6f, reverbEnabled = true, reverbMix = 0.4f) }
        ctrl.updateStomp { copy(engaged = true, toneTilt = 0.3f) }
        assertTrue("Loop must keep playing through signal edits and FX updates", ctrl.playbackState.value.isPlaying)
        assertTrue(ctrl.playbackState.value.isLooping)

        ctrl.undoEdit()
        assertTrue("Loop must keep playing after undo", ctrl.playbackState.value.isPlaying)

        ctrl.soundEngine.stopPlayback()
        assertFalse(ctrl.playbackState.value.isPlaying)
        ctrl.shutdown()
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
    fun `samples looper refiner, sequencer, and synced timeline work cleanly`() {
        val ctrl = DesktopStudioController()
        ctrl.generateTestSignal(SignalWaveformType.TRIANGLE, 196.0)
        ctrl.sendEditorToLoopSlot(0)
        ctrl.arrangement.snapLoopSelectionZeroCross()
        ctrl.arrangement.normalizeLoopRefiner()
        ctrl.arrangement.commitRefinerToSlot()
        ctrl.arrangement.toggleLoopSlotActive(0)

        val looperMix = ctrl.arrangement.bounceLooperMixdown(44100)
        assertTrue(looperMix.frameCount > 1000)

        ctrl.arrangement.toggleSequencerStep(0, 0)
        val beatMix = ctrl.arrangement.renderSequenceToBuffer(1, 44100)
        assertTrue(beatMix.frameCount > 1000)

        ctrl.addEditorClipToTimeline(0)
        ctrl.bounceSequencerToTimeline()
        assertTrue(ctrl.timelineState.value.tracks[0].clips.isNotEmpty())
        assertTrue(ctrl.timelineState.value.tracks[2].clips.isNotEmpty())
        ctrl.shutdown()
    }

    @Test
    fun `64 channel vmc engine handles buses 1 to 4, midi learn, and peer vmc2 transport`() {
        val freePort = ServerSocket(0).use { it.localPort }
        val receiverCtrl = DesktopStudioController()
        val senderCtrl = DesktopStudioController()

        receiverCtrl.updateRigConfig { copy(protocol = NetworkProtocol.TCP, bindPort = freePort) }
        receiverCtrl.toggleNetworkListener()
        senderCtrl.updateRigConfig { copy(protocol = NetworkProtocol.TCP, txHost = "127.0.0.1", txPort = freePort) }
        Thread.sleep(150)

        // Verify 64-ch bank math (Bank 3, Bus 4 CH 4 = Global CH 52)
        senderCtrl.vmcEngine.selectChannel64(52)
        assertEquals(3, senderCtrl.vmcState.value.selectedBank)
        assertEquals(52, senderCtrl.vmcState.value.activeChannel64)

        // Test MIDI Learn for new mapping on CH 52
        senderCtrl.vmcEngine.armLearnNewMapping(VmcTarget.FX_CHORUS_MIX)
        senderCtrl.vmcEngine.processIncomingEvent(VmcEventPacket(52, VmcMsgType.CC, 22, 100, 0.78f), fromPeer = false)
        assertTrue(senderCtrl.vmcState.value.mappings.any { it.channel64 == 52 && it.number == 22 && it.target == VmcTarget.FX_CHORUS_MIX })

        // Transmit CH17 Drive CC71 over VMC2 to peer receiver
        senderCtrl.vmcEngine.emitVirtualControl(VmcMsgType.CC, 71, 0.9f, channel64 = 17)

        val deadline = System.currentTimeMillis() + 3500
        while (System.currentTimeMillis() < deadline && receiverCtrl.netState.value.vmcRxCount == 0L) {
            Thread.sleep(30)
        }
        assertTrue(receiverCtrl.netState.value.vmcRxCount >= 1L)
        assertTrue(receiverCtrl.uiState.value.fxState.overdriveEnabled)

        senderCtrl.shutdown()
        receiverCtrl.shutdown()
    }

    @Test
    fun `bidirectional smh2 session handoff and smhr pull request transfer full state over tcp`() {
        val portA = ServerSocket(0).use { it.localPort }
        val portB = ServerSocket(0).use { it.localPort }

        val ctrlA = DesktopStudioController()
        val ctrlB = DesktopStudioController()

        ctrlA.updateRigConfig { copy(protocol = NetworkProtocol.TCP, bindPort = portA, txHost = "127.0.0.1", txPort = portB) }
        ctrlB.updateRigConfig { copy(protocol = NetworkProtocol.TCP, bindPort = portB, txHost = "127.0.0.1", txPort = portA) }
        ctrlA.toggleNetworkListener()
        ctrlB.toggleNetworkListener()
        Thread.sleep(150)

        ctrlA.generateTestSignal(SignalWaveformType.SQUARE, 330.0)
        ctrlA.soundEngine.setLooping(true)
        ctrlA.soundEngine.startPlayback()
        ctrlA.updateSelection(0.22f, 0.78f)
        ctrlA.applyPreset(DesktopPreset.ACID_WAH)
        ctrlA.setLiveSessionMode(DesktopLiveMode.STAGE_DJ)
        ctrlA.selectStation(DesktopStation.VMC)
        ctrlA.vmcEngine.upsertMapping(VmcMappingRule("custom_64", 64, VmcMsgType.CC, 88, VmcTarget.FX_DELAY_MIX))

        ctrlA.pushSessionToPeer()

        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline && ctrlB.uiState.value.station != DesktopStation.VMC) {
            Thread.sleep(40)
        }

        val stateB = ctrlB.uiState.value
        assertEquals(DesktopStation.VMC, stateB.station)
        assertEquals(3, stateB.fxState.activeCount)
        assertEquals(DesktopLiveMode.STAGE_DJ, stateB.stompState.liveMode)
        assertEquals(0.22f, stateB.selectionStart, 0.01f)
        assertEquals(0.78f, stateB.selectionEnd, 0.01f)
        assertTrue(ctrlB.playbackState.value.isLooping)
        assertTrue(ctrlB.playbackState.value.isPlaying)
        assertTrue(ctrlB.vmcState.value.mappings.any { it.id == "custom_64" && it.channel64 == 64 })

        // Verify wire round-trip station key mapping for Android/Desktop parity
        assertEquals(DesktopStation.EDITOR, SessionHandoffSnapshot.mapStationKeyToDesktop("DECK"))
        assertEquals(DesktopStation.TUNER, SessionHandoffSnapshot.mapStationKeyToDesktop("TUNER"))

        // Now modify ctrlB and let ctrlA grab (pull) via SMHR!
        ctrlB.soundEngine.stopPlayback()
        ctrlB.selectStation(DesktopStation.TRACKS)
        ctrlA.grabSessionFromPeer()

        val deadline2 = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline2 && ctrlA.uiState.value.station != DesktopStation.TRACKS) {
            Thread.sleep(40)
        }
        assertEquals(DesktopStation.TRACKS, ctrlA.uiState.value.station)

        ctrlA.shutdown()
        ctrlB.shutdown()
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
