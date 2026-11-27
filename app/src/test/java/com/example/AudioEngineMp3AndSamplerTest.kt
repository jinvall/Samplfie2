package com.example

import com.example.audio.AudioBuffer
import com.example.audio.AudioEngine
import com.example.audio.Mp3Jump3rDecoder
import com.example.audio.SAMPLER_MODULE_COUNT
import com.example.export.Mp3Exporter
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEngineMp3AndSamplerTest {

    private fun generateSineBuffer(
        name: String = "TEST_SINE",
        freqHz: Double = 440.0,
        durationSec: Float = 0.35f,
        sampleRate: Int = 44100,
        channels: Int = 1
    ): AudioBuffer {
        val frames = (sampleRate * durationSec).toInt()
        val samples = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (sin(2.0 * PI * freqHz * f / sampleRate) * 0.65).toFloat()
            for (c in 0 until channels) {
                samples[f * channels + c] = v
            }
        }
        return AudioBuffer(samples = samples, sampleRate = sampleRate, channels = channels, name = name)
    }

    @Test
    fun `jump3r decodes encoded MP3 bytes cleanly and binds to 12-module sampler`() = runBlocking {
        val tempDir = Files.createTempDirectory("samplfie_mp3_test").toFile()
        val mp3File = File(tempDir, "tone_440.mp3")
        try {
            val src = generateSineBuffer(name = "SINE_440", freqHz = 440.0, durationSec = 0.45f, channels = 2)
            val encodeRes = Mp3Exporter.encodeBufferToMp3File(src, mp3File, bitrateKbps = 192)
            assertTrue("MP3 encode failed: ${encodeRes.exceptionOrNull()}", encodeRes.isSuccess)
            val mp3Bytes = mp3File.readBytes()

            assertTrue(Mp3Jump3rDecoder.isLikelyMp3(mp3Bytes, mp3File.name))
            val decoded = Mp3Jump3rDecoder.decodeMp3Bytes(mp3Bytes, "TONE_440").getOrThrow()
            assertTrue(decoded.frameCount > 4000)
            assertTrue(decoded.buffer.samples.maxOf { kotlin.math.abs(it) } > 0.1f)
            assertEquals(44100, decoded.buffer.sampleRate)

            val engine = AudioEngine()
            assertEquals(SAMPLER_MODULE_COUNT, engine.slots.value.size)
            val bound = engine.loadMp3BytesToModule(11, mp3Bytes, "KICK_12", lockSlot = true)
            assertTrue(bound.isSuccess)
            val slot12 = engine.slots.value[11]
            assertTrue(slot12.hasSample)
            assertTrue(slot12.isLocked)
            assertTrue(slot12.sourceFormatTag.startsWith("MP3"))
            engine.release()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `12-module sampler enforces lock protection and mixes active slots`() {
        val engine = AudioEngine()
        try {
            val bufA = generateSineBuffer("MOD_A", 220.0, 0.25f, 44100, 1)
            val bufB = generateSineBuffer("MOD_B", 660.0, 0.25f, 44100, 2)

            assertTrue(engine.loadSampleToModule(0, bufA, lockSlot = true, sourceTag = "DECK LOCK"))
            // Second normal load must NOT overwrite locked slot
            assertFalse(engine.loadSampleToModule(0, bufB, lockSlot = true, sourceTag = "DECK LOCK"))
            assertEquals("MOD_A", engine.slots.value[0].sampleName)
            assertFalse(engine.clearModule(0, force = false))

            // Force replace succeeds, and unlocking permits clear
            assertTrue(engine.forceReplaceModule(0, bufB, lockSlot = true, sourceTag = "FORCED"))
            assertEquals("MOD_B", engine.slots.value[0].sampleName)
            engine.toggleModuleLock(0)
            assertFalse(engine.slots.value[0].isLocked)

            // Load second module + adjust pan/gain/pitch & mix snapshot
            assertTrue(engine.loadSampleToModule(5, bufA, lockSlot = false, sourceTag = "WAV"))
            engine.setModuleParams(5, gain = 1.2f, pan = -0.5f, pitchSemitones = 3.0f)
            assertEquals(1.2f, engine.slots.value[5].gain, 0.01f)
            assertEquals(-0.5f, engine.slots.value[5].pan, 0.01f)
            assertEquals(3.0f, engine.slots.value[5].pitchSemitones, 0.01f)

            val mix = engine.mixActiveModulesSnapshot(targetSampleRate = 44100)
            assertNotNull(mix)
            assertEquals(2, mix!!.channels)
            assertTrue(mix.samples.maxOf { kotlin.math.abs(it) } > 0.05f)

            assertTrue(engine.clearModule(0, force = false))
            assertFalse(engine.slots.value[0].hasSample)
        } finally {
            engine.release()
        }
    }

    @Test
    fun `id3v2 frame offset scanner locates sync header safely`() {
        val payload = ByteArray(32)
        payload[0] = 'I'.code.toByte()
        payload[1] = 'D'.code.toByte()
        payload[2] = '3'.code.toByte()
        payload[9] = 10 // synchsafe 10 bytes tag
        payload[20] = 0xFF.toByte()
        payload[21] = 0xFB.toByte()
        assertEquals(20, Mp3Jump3rDecoder.skipId3v2Offset(payload))
        assertTrue(Mp3Jump3rDecoder.isLikelyMp3(payload))
    }
}
