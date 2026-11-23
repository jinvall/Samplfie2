package com.example.tools

import com.example.audio.AudioBuffer
import com.example.audio.AudioSampleFormat
import com.example.audio.SpectrogramAnalyzer
import com.example.audio.SpectrogramPalette
import com.example.audio.WavCodec
import com.example.plugins.AndroidPluginRepository
import com.example.plugins.AudioPluginHostEngine
import com.example.plugins.PluginCatalogRegistry
import com.example.plugins.PluginDspKernel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ToolsAndPluginsTest {

    private fun makeSineBuffer(
        freqHz: Double = 440.0,
        durationSec: Float = 0.5f,
        sampleRate: Int = 44100,
        channels: Int = 1,
        amplitude: Float = 0.6f,
        name: String = "TestSine"
    ): AudioBuffer {
        val frames = (durationSec * sampleRate).toInt()
        val samples = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (sin(2.0 * PI * freqHz * f / sampleRate) * amplitude).toFloat()
            for (c in 0 until channels) {
                samples[f * channels + c] = v
            }
        }
        return AudioBuffer(samples = samples, sampleRate = sampleRate, channels = channels, name = name)
    }

    @Test
    fun `10-stop spectrogram presets, hex parser, LUT interpolation, and STFT analyzer work`() {
        for (preset in SpectrogramPalette.PRESETS) {
            assertEquals(10, preset.stopsArgb.size)
            val lut = SpectrogramPalette.buildLut256(preset.stopsArgb)
            assertEquals(256, lut.size)
            assertEquals(preset.stopsArgb.first().toInt(), lut[0])
            assertEquals(preset.stopsArgb.last().toInt(), lut[255])
        }

        val customHex = "#010203, #112233, #223344, #334455, #445566, #556677, #667788, #778899, #AA5500, #FFFFFF"
        val parsed = SpectrogramPalette.parseTenHexStops(customHex)
        assertTrue(parsed.isSuccess)
        assertEquals(10, parsed.getOrThrow().stopsArgb.size)
        assertEquals(0xFF010203L, parsed.getOrThrow().stopsArgb.first())
        assertEquals(0xFFFFFFFFL, parsed.getOrThrow().stopsArgb.last())

        val invalid = SpectrogramPalette.parseTenHexStops("#000000, #111111, #222222")
        assertTrue(invalid.isFailure)

        val tone = makeSineBuffer(freqHz = 2000.0, durationSec = 0.4f, sampleRate = 44100)
        val spec = SpectrogramAnalyzer.compute(tone, maxFrames = 64, freqBins = 48, fftSize = 512)
        assertFalse(spec.isEmpty)
        assertEquals(64, spec.frames)
        assertEquals(48, spec.bins)

        // Peak energy in mid frames should align near 2 kHz bin rather than 8 kHz bin
        val midFrame = spec.frames / 2
        val bin2k = (spec.freqHzToNormY(2000f) * (spec.bins - 1)).toInt().coerceIn(0, spec.bins - 1)
        val bin8k = (spec.freqHzToNormY(8000f) * (spec.bins - 1)).toInt().coerceIn(0, spec.bins - 1)
        assertTrue(
            "Expected 2 kHz energy (${spec.valueAt(midFrame, bin2k)}) > 8 kHz energy (${spec.valueAt(midFrame, bin8k)})",
            spec.valueAt(midFrame, bin2k) > spec.valueAt(midFrame, bin8k) + 0.25f
        )
    }

    @Test
    fun `wav codec roundtrips all 6 PCM and IEEE float formats`() {
        val base = makeSineBuffer(freqHz = 330.0, durationSec = 0.1f, sampleRate = 48000, channels = 2, amplitude = 0.5f)
        for (fmt in AudioSampleFormat.entries) {
            val encoded = WavCodec.encodeToBytes(base, targetFormat = fmt, dither = fmt == AudioSampleFormat.PCM_8)
            val decoded = WavCodec.readWav(encoded, "RT_${fmt.name}")
            assertEquals(48000, decoded.sampleRate)
            assertEquals(2, decoded.channels)
            assertEquals(fmt, decoded.sampleFormat)
            assertEquals(base.frameCount, decoded.frameCount)
            val tol = when (fmt) {
                AudioSampleFormat.PCM_8 -> 0.03f
                AudioSampleFormat.PCM_16 -> 0.002f
                else -> 0.0002f
            }
            val diff = AudioToolsEngine.computeAudioDifference(base, decoded)
            assertTrue("Format $fmt RMS diff ${diff.rmsDiff} exceeded tol $tol", diff.rmsDiff <= tol)
        }
    }

    @Test
    fun `spectral delete attenuates targeted frequency band while preserving other frequencies`() {
        val sr = 44100
        val frames = (0.5f * sr).toInt()
        val samples = FloatArray(frames) { f ->
            (0.45 * sin(2.0 * PI * 400.0 * f / sr) + 0.45 * sin(2.0 * PI * 3500.0 * f / sr)).toFloat()
        }
        val dualTone = AudioBuffer(samples, sr, 1, "DualTone")
        val beforeSpec = SpectrogramAnalyzer.compute(dualTone, maxFrames = 64, freqBins = 64, fftSize = 512)

        val cleaned = AudioToolsEngine.spectralDelete(
            buffer = dualTone,
            startFrac = 0.1f,
            endFrac = 0.9f,
            lowFreqHz = 2800f,
            highFreqHz = 4200f,
            attenuationDb = 96f,
            fftSize = 1024
        )
        val afterSpec = SpectrogramAnalyzer.compute(cleaned, maxFrames = 64, freqBins = 64, fftSize = 512)
        val midFrame = 32
        val bin3500 = (afterSpec.freqHzToNormY(3500f) * (afterSpec.bins - 1)).toInt()
        val bin400 = (afterSpec.freqHzToNormY(400f) * (afterSpec.bins - 1)).toInt()

        assertTrue(
            "3.5kHz energy should drop significantly",
            afterSpec.valueAt(midFrame, bin3500) < beforeSpec.valueAt(midFrame, bin3500) - 0.3f
        )
        assertTrue(
            "400Hz energy should stay intact",
            afterSpec.valueAt(midFrame, bin400) > 0.6f
        )
    }

    @Test
    fun `invert polarity, audio difference, mix, mono, stereo split, resample, and format convert work`() {
        val stereo = AudioBuffer(
            samples = floatArrayOf(0.5f, -0.25f, 0.25f, -0.5f, 0.1f, 0.4f, -0.3f, 0.2f),
            sampleRate = 44100,
            channels = 2,
            name = "Stereo"
        )
        val invLeft = AudioToolsEngine.invertPolarity(stereo, 0f, 1f, ChannelScope.LEFT_ONLY)
        assertEquals(-0.5f, invLeft.samples[0], 1e-5f)
        assertEquals(-0.25f, invLeft.samples[1], 1e-5f)

        val toneA = makeSineBuffer(freqHz = 440.0, durationSec = 0.25f, sampleRate = 44100, channels = 1)
        val toneInv = AudioToolsEngine.invertPolarity(toneA, 0f, 1f, ChannelScope.ALL)
        val diffSame = AudioToolsEngine.computeAudioDifference(toneA, toneA)
        assertTrue(diffSame.nullDepthDb <= -110f)
        assertTrue(diffSame.correlation > 0.999f)

        val diffOpposite = AudioToolsEngine.computeAudioDifference(toneA, toneInv)
        assertTrue(diffOpposite.correlation < -0.999f)
        assertTrue(diffOpposite.peakDiff > 1.0f - 1e-4f)

        val mixed = AudioToolsEngine.mixBuffers(toneA, toneInv, primaryGain = 1f, secondaryGain = 1f, offsetSec = 0f, softClipLimiter = false)
        assertTrue("Cancelling mix peak should be near 0, got ${mixed.computePeakAndRms().first}", mixed.computePeakAndRms().first < 1e-4f)

        val monoAvg = AudioToolsEngine.mixToMono(stereo, MonoFoldMode.AVERAGE)
        assertEquals(1, monoAvg.channels)
        assertEquals(0.125f, monoAvg.samples[0], 1e-5f)

        val splitHaas = AudioToolsEngine.splitToStereo(monoAvg, StereoSplitMode.HAAS_DELAY, paramValue = 10f)
        assertEquals(2, splitHaas.channels)
        assertEquals(monoAvg.frameCount, splitHaas.frameCount)

        val resampled = AudioToolsEngine.convertSampleRate(toneA, 48000, reinterpretHeaderOnly = false)
        assertEquals(48000, resampled.sampleRate)
        assertTrue(abs(resampled.durationSeconds - toneA.durationSeconds) < 0.01f)

        val reinterpreted = AudioToolsEngine.convertSampleRate(toneA, 88200, reinterpretHeaderOnly = true)
        assertEquals(88200, reinterpreted.sampleRate)
        assertEquals(toneA.frameCount, reinterpreted.frameCount)

        val fmt8 = AudioToolsEngine.convertSampleFormat(toneA, AudioSampleFormat.PCM_8, dither = true)
        assertEquals(AudioSampleFormat.PCM_8, fmt8.sampleFormat)
    }

    @Test
    fun `plugin registry, repository persistence, IR creation, and DSP host engine work`() {
        val tempDir = Files.createTempDirectory("android_plugins_test").toFile()
        try {
            val repo = AndroidPluginRepository(tempDir)
            val initial = repo.loadAllPlugins()
            assertTrue(initial.any { it.isBuiltIn && it.kernel == PluginDspKernel.RESONANT_SVF })
            assertTrue(initial.any { it.isBuiltIn && it.kernel == PluginDspKernel.BRICKWALL_LIMITER })

            val catalogEntry = PluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.TUBE_WARMTH }
            val installedDef = repo.installPlugin(catalogEntry.toDefinition())
            val jsonRoundtrip = PluginCatalogRegistry.fromJson(PluginCatalogRegistry.toJson(installedDef)).getOrThrow()
            assertEquals(installedDef.id, jsonRoundtrip.id)
            assertEquals(installedDef.kernel, jsonRoundtrip.kernel)
            assertEquals(installedDef.params.size, jsonRoundtrip.params.size)

            val reloaded = repo.loadAllPlugins()
            assertTrue(reloaded.any { it.id == installedDef.id })

            val dry = makeSineBuffer(freqHz = 220.0, durationSec = 0.25f, sampleRate = 44100, channels = 2, amplitude = 0.5f)
            val slot = installedDef.createSlotInstance()
            val defsMap = reloaded.associateBy { it.id }

            val processed = AudioPluginHostEngine.processChain(dry, listOf(slot), defsMap)
            assertFalse(processed.samples.contentEquals(dry.samples))

            val bypassed = AudioPluginHostEngine.processChain(dry, listOf(slot.copy(bypassed = true)), defsMap)
            assertTrue(bypassed.samples.contentEquals(dry.samples))

            // Exercise all 12 kernels without NaN or clipping beyond [-1, 1]
            for (cat in PluginCatalogRegistry.INSTALLABLE_CATALOG) {
                val def = cat.toDefinition()
                val out = AudioPluginHostEngine.processChain(dry, listOf(def.createSlotInstance()), mapOf(def.id to def))
                assertNotNull(out)
                assertTrue(out.samples.all { it.isFinite() && it in -1f..1f })
            }

            // Custom WAV IR plugin generation
            val irDef = PluginCatalogRegistry.createIrPluginFromBuffer(dry, "HallImpulse")
            assertEquals(PluginDspKernel.CONVOLUTION_IR, irDef.kernel)
            assertNotNull(irDef.irSamples)
            val irOut = AudioPluginHostEngine.processChain(dry, listOf(irDef.createSlotInstance()), mapOf(irDef.id to irDef))
            assertFalse(irOut.samples.contentEquals(dry.samples))

            assertTrue(repo.uninstallPlugin(installedDef.id))
            assertFalse(repo.loadAllPlugins().any { it.id == installedDef.id })
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
