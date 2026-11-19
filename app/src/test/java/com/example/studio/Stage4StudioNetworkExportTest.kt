package com.example.studio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioBuffer
import com.example.config.NetworkProtocol
import com.example.config.RigConfig
import com.example.config.RigConfigRepository
import com.example.export.Mp3Exporter
import com.example.midi.MidiEvent
import com.example.midi.MidiMessageParser
import com.example.network.NetworkAudioTransmitter
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Stage4StudioNetworkExportTest {

    private fun testBuffer(durationSec: Float = 0.25f, sr: Int = 44100): AudioBuffer {
        val frames = (sr * durationSec).toInt()
        val samples = FloatArray(frames) { i ->
            (sin(2.0 * PI * 440.0 * i / sr) * 0.65).toFloat()
        }
        return AudioBuffer(samples, sr, 1, "Stage4Tone")
    }

    @Test
    fun `rig config validation and datastore persistence work`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = RigConfigRepository(context)
        val custom = RigConfig(
            ipAddress = "10.0.0.42",
            port = 9555,
            protocol = NetworkProtocol.TCP,
            sampleRate = 48000,
            channels = 2,
            bufferSize = 2048,
            mp3BitrateKbps = 256
        )
        assertTrue(custom.isValidIpOrHost())
        assertTrue(custom.isValidPort())
        assertFalse(custom.copy(port = 70000).isValidPort())
        assertFalse(custom.copy(ipAddress = "").isValidIpOrHost())

        repo.saveConfig(custom)
        val loaded = repo.configFlow.first()
        assertEquals(custom, loaded)
    }

    @Test
    fun `mp3 exporter encodes valid playable mp3 frames via jump3r`() = runBlocking {
        val buf = testBuffer(durationSec = 0.4f, sr = 44100)
        val outMp3 = File.createTempFile("samplfie2_test_", ".mp3")
        try {
            val res = Mp3Exporter.encodeBufferToMp3File(buf, outMp3, bitrateKbps = 192)
            assertTrue("MP3 export failed: ${res.exceptionOrNull()}", res.isSuccess)
            val bytes = outMp3.readBytes()
            assertTrue("MP3 file too small: ${bytes.size}", bytes.size > 1000)
            val hasMp3Sync = (0 until bytes.size - 1).any { idx ->
                val b0 = bytes[idx].toInt() and 0xFF
                val b1 = bytes[idx + 1].toInt() and 0xE0
                b0 == 0xFF && b1 == 0xE0
            }
            assertTrue("Missing MPEG frame sync header in exported MP3", hasMp3Sync)
        } finally {
            outMp3.delete()
        }
    }

    @Test
    fun `network transmitter streams SMP2 packets over real UDP and TCP loopback`() {
        val buf = testBuffer(durationSec = 0.08f, sr = 44100)
        val transmitter = NetworkAudioTransmitter()

        // 1. UDP test
        DatagramSocket(0).use { receiver ->
            receiver.soTimeout = 3000
            val udpCfg = RigConfig(
                ipAddress = "127.0.0.1",
                port = receiver.localPort,
                protocol = NetworkProtocol.UDP,
                sampleRate = 44100,
                channels = 1,
                bufferSize = 512
            )
            transmitter.transmitBuffer(buf, udpCfg)

            val recvBuf = ByteArray(4096)
            val packet = DatagramPacket(recvBuf, recvBuf.size)
            receiver.receive(packet)
            val header = NetworkAudioTransmitter.parsePacketHeader(packet.data.copyOf(packet.length))
            assertTrue(header != null)
            assertEquals("SMP2", header!!.magic)
            assertEquals(44100, header.sampleRate)
            assertEquals(1, header.channels)
            assertTrue(header.payloadBytes > 0)
            transmitter.stopTransmission()
        }

        // 2. TCP test
        ServerSocket(0).use { server ->
            server.soTimeout = 3000
            val tcpCfg = RigConfig(
                ipAddress = "127.0.0.1",
                port = server.localPort,
                protocol = NetworkProtocol.TCP,
                sampleRate = 44100,
                channels = 1,
                bufferSize = 512
            )
            transmitter.transmitBuffer(buf, tcpCfg)
            server.accept().use { client ->
                val headerBytes = ByteArray(NetworkAudioTransmitter.HEADER_SIZE)
                val read = client.getInputStream().read(headerBytes)
                assertEquals(NetworkAudioTransmitter.HEADER_SIZE, read)
                val header = NetworkAudioTransmitter.parsePacketHeader(headerBytes)
                assertEquals("SMP2", header?.magic)
                assertEquals(44100, header?.sampleRate)
            }
            transmitter.stopTransmission()
        }
    }

    @Test
    fun `midi parser decodes note on, note off, cc, pitch bend, and clock`() {
        val bytes = byteArrayOf(
            0x90.toByte(), 60, 110, // Note On ch1 C4 v110
            0x80.toByte(), 60, 0,   // Note Off
            0xB0.toByte(), 74, 127, // CC 74 = 127
            0xE0.toByte(), 0x00, 0x40, // Pitch bend center
            0xF8.toByte(),          // Clock
            0xFA.toByte(),          // Start
            0xFC.toByte()           // Stop
        )
        val events = MidiMessageParser.parse(bytes)
        assertEquals(7, events.size)
        assertEquals(MidiEvent.NoteOn(0, 60, 110), events[0])
        assertEquals(MidiEvent.NoteOff(0, 60), events[1])
        assertEquals(MidiEvent.ControlChange(0, 74, 127), events[2])
        assertEquals(0f, (events[3] as MidiEvent.PitchBend).normalized, 0.01f)
        assertEquals(MidiEvent.ClockTick, events[4])
        assertEquals(MidiEvent.TransportStart, events[5])
        assertEquals(MidiEvent.TransportStop, events[6])
    }

    @Test
    fun `studio engine bounces looper, sequencer, and timeline mixdown`() {
        val studio = StudioEngine()
        val sample = testBuffer(0.25f)

        // Looper
        studio.assignBufferToLoopSlot(0, sample, "Vocal Loop")
        val loopMix = studio.bounceLooperMixdown()
        assertTrue(loopMix.frameCount > 0)
        assertTrue(loopMix.computePeakAndRms().first > 0.2f)

        // 16-Step Sequencer
        studio.setSequencerSampleForLane(0, sample)
        studio.toggleSequencerStep(0, 2)
        val seqBounce = studio.renderSequenceToBuffer(bars = 1)
        assertTrue(seqBounce.frameCount > 44100)
        assertTrue(seqBounce.computePeakAndRms().first > 0.2f)

        // Timeline
        studio.addClipToTimeline(0, sample, startTimeSec = 0.0f)
        studio.addClipToTimeline(2, seqBounce, startTimeSec = 0.5f)
        studio.seekTimelinePlayhead(0.25f)
        assertEquals(0.25f, studio.timelineState.value.playheadSec, 0.01f)
        val masterMix = studio.mixdownTimeline()
        assertTrue(masterMix.durationSeconds >= 1.5f)
        assertTrue(masterMix.computePeakAndRms().first > 0.2f)
    }
}
