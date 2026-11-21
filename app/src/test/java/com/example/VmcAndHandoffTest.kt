package com.example

import com.example.audio.AudioBuffer
import com.example.audio.AudioPlaybackEngine
import com.example.config.NetworkProtocol
import com.example.config.RigConfig
import com.example.dsp.FxPreset
import com.example.dsp.FxPresets
import com.example.dsp.LiveSessionMode
import com.example.dsp.StompboxMasterState
import com.example.midi.VmcEngine
import com.example.midi.VmcEventPacket
import com.example.midi.VmcMappingRule
import com.example.midi.VmcMsgType
import com.example.midi.VmcTarget
import com.example.network.NetworkAudioTransmitter
import com.example.network.SessionHandoffSnapshot
import com.example.tuner.PitchDetector
import com.example.ui.StudioSpoke
import java.net.ServerSocket
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VmcAndHandoffTest {

    private fun sineWaveBuffer(freqHz: Double, durationSec: Float = 0.6f, sampleRate: Int = 44100): AudioBuffer {
        val frames = (sampleRate * durationSec).toInt()
        val samples = FloatArray(frames) { i ->
            (sin(2.0 * PI * freqHz * i / sampleRate) * 0.7).toFloat()
        }
        return AudioBuffer(samples, sampleRate, 1, "TEST_${freqHz.toInt()}Hz")
    }

    @Test
    fun `android yin pitch detector tracks A4 440Hz, E2 82_4Hz, and A4 432Hz calibration`() {
        val a4 = sineWaveBuffer(440.0)
        val rA4 = PitchDetector.detectPitch(a4.toMonoSamples(), 44100, 440.0)
        assertTrue(rA4.detected)
        assertEquals("A4", rA4.fullNote)
        assertTrue(abs(rA4.centsOffset) <= 2.0)

        val e2 = sineWaveBuffer(82.4069)
        val rE2 = PitchDetector.detectPitch(e2.toMonoSamples(), 44100, 440.0)
        assertTrue(rE2.detected)
        assertEquals("E2", rE2.fullNote)
        assertTrue(abs(rE2.centsOffset) <= 3.0)

        val a432 = sineWaveBuffer(432.0)
        val r432 = PitchDetector.detectPitch(a432.toMonoSamples(), 44100, 432.0)
        assertTrue(r432.detected)
        assertEquals("A4", r432.fullNote)
        assertTrue(abs(r432.centsOffset) <= 2.0)
    }

    @Test
    fun `audio playback engine persists loop across hot buffer swap and selection extract until stop`() {
        val engine = AudioPlaybackEngine()
        val full = sineWaveBuffer(220.0, durationSec = 0.8f)
        engine.setBuffer(full, bufferSizeFrames = 512)
        engine.setLooping(true)
        engine.play(onlySelection = false)

        assertTrue(engine.state.value.isPlaying)
        assertTrue(engine.state.value.isLooping)

        val extracted = full.extractSelection(0.2f, 0.7f)
        engine.updateBufferHot(extracted, bufferSizeFrames = 512, preserveLoopPlayback = true)
        assertTrue("Playback must persist after selection extraction while looping", engine.state.value.isPlaying)
        assertTrue(engine.state.value.isLooping)
        assertEquals(extracted.frameCount, engine.state.value.totalFrames)

        val reversed = extracted.reversed()
        engine.updateBufferHot(reversed, bufferSizeFrames = 512, preserveLoopPlayback = true)
        assertTrue(engine.state.value.isPlaying)
        assertTrue(engine.state.value.isLooping)

        engine.stop()
        assertFalse(engine.state.value.isPlaying)
        engine.release()
    }

    @Test
    fun `64 channel vmc engine handles buses 1 to 4, midi learn, and tcp vmc2 peer control`() {
        val freePort = ServerSocket(0).use { it.localPort }
        var executedTarget: VmcTarget? = null
        var executedVal = -1f

        val rxNode = NetworkAudioTransmitter(
            onVmcEventReceived = { pkt ->
                // Verify peer packet arrives intact on channel 52
                assertEquals(52, pkt.channel64)
            }
        )
        val rxVmc = VmcEngine(
            onExecuteTarget = { t, v ->
                executedTarget = t
                executedVal = v
            },
            onTransmitPeerVmc = {}
        )
        val rxNodeWithEngine = NetworkAudioTransmitter(
            onVmcEventReceived = { pkt -> rxVmc.processIncomingEvent(pkt, fromPeer = true) }
        )

        rxNodeWithEngine.startListening(NetworkProtocol.TCP, freePort)
        Thread.sleep(150)

        val txNode = NetworkAudioTransmitter()
        val txVmc = VmcEngine(
            onExecuteTarget = { _, _ -> },
            onTransmitPeerVmc = { pkt ->
                txNode.sendVmcEvent(pkt, RigConfig(ipAddress = "127.0.0.1", port = freePort, protocol = NetworkProtocol.TCP))
            }
        )

        // Select Channel 52 (Bank 3, Bus 4 Ch 4) and arm MIDI Learn on receiver + transmitter
        txVmc.selectChannel64(52)
        assertEquals(3, txVmc.state.value.selectedBank)
        assertEquals(52, txVmc.state.value.activeChannel64)

        rxVmc.upsertMapping(VmcMappingRule("ch52_rev", 52, VmcMsgType.CC, 22, VmcTarget.FX_REVERB_MIX))
        txVmc.armLearnNewMapping(VmcTarget.FX_ECHO_MIX)
        txVmc.processIncomingEvent(VmcEventPacket(52, VmcMsgType.CC, 25, 90, 0.7f), fromPeer = false)
        assertTrue(txVmc.state.value.mappings.any { it.channel64 == 52 && it.number == 25 && it.target == VmcTarget.FX_ECHO_MIX })

        // Emit control on CH52 CC22 from TX -> should traverse TCP VMC2 and trigger FX_REVERB_MIX on RX
        txVmc.emitVirtualControl(VmcMsgType.CC, 22, 0.85f, channel64 = 52)

        val deadline = System.currentTimeMillis() + 3500
        while (System.currentTimeMillis() < deadline && executedTarget != VmcTarget.FX_REVERB_MIX) {
            Thread.sleep(30)
        }
        assertEquals(VmcTarget.FX_REVERB_MIX, executedTarget)
        assertEquals(0.85f, executedVal, 0.02f)

        rxNode.stopListening()
        rxNodeWithEngine.stopListening()
    }

    @Test
    fun `smh2 session handoff codec roundtrips buffer, fx, stompbox, loop state, and 64ch vmc over tcp`() {
        val port = ServerSocket(0).use { it.localPort }
        var receivedSnapshot: SessionHandoffSnapshot? = null
        val rx = NetworkAudioTransmitter(
            onSessionHandoffReceived = { snap -> receivedSnapshot = snap }
        )
        val tx = NetworkAudioTransmitter()

        rx.startListening(NetworkProtocol.TCP, port)
        Thread.sleep(150)

        val buf = sineWaveBuffer(330.0, 0.25f)
        val snap = SessionHandoffSnapshot(
            senderName = "LINUX WORKSTATION",
            stationKey = "VMC",
            bpm = 132f,
            swing = 0.14f,
            isPlaying = true,
            isLooping = true,
            playheadFraction = 0.42f,
            selectionStart = 0.18f,
            selectionEnd = 0.76f,
            playSelectionOnly = true,
            a4ReferenceHz = 432.0,
            fxRack = FxPresets.build(FxPreset.ACID_WAH),
            stompbox = StompboxMasterState(engaged = true, toneTilt = 0.4f, liveMode = LiveSessionMode.STAGE_DJ),
            seqLaneMasks = listOf(0b1010, 0b0101),
            looperActiveMask = 0b0011,
            looperBars = listOf(1, 2, 1, 4),
            vmcMappings = listOf(VmcMappingRule("m64", 64, VmcMsgType.CC, 90, VmcTarget.FX_DELAY_MIX)),
            buffer = buf
        )

        tx.sendSessionHandoff(snap, NetworkProtocol.TCP, "127.0.0.1", port)

        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline && receivedSnapshot == null) {
            Thread.sleep(40)
        }

        val got = receivedSnapshot
        assertNotNull(got)
        assertEquals("LINUX WORKSTATION", got!!.senderName)
        assertEquals(StudioSpoke.VMC, SessionHandoffSnapshot.mapStationKeyToAndroid(got.stationKey))
        assertEquals(132f, got.bpm, 0.01f)
        assertTrue(got.isPlaying)
        assertTrue(got.isLooping)
        assertEquals(0.42f, got.playheadFraction, 0.01f)
        assertEquals(0.18f, got.selectionStart, 0.01f)
        assertEquals(0.76f, got.selectionEnd, 0.01f)
        assertTrue(got.playSelectionOnly)
        assertEquals(432.0, got.a4ReferenceHz, 0.01)
        assertEquals(3, got.fxRack.activeCount)
        assertEquals(LiveSessionMode.STAGE_DJ, got.stompbox.liveMode)
        assertEquals(listOf(0b1010, 0b0101), got.seqLaneMasks)
        assertEquals(0b0011, got.looperActiveMask)
        assertEquals(1, got.vmcMappings.size)
        assertEquals(64, got.vmcMappings[0].channel64)
        assertNotNull(got.buffer)
        assertEquals(buf.frameCount, got.buffer!!.frameCount)

        rx.stopListening()
    }
}
