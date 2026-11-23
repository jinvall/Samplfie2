package com.samplfie.desktop

import com.samplfie.desktop.audio.DesktopSpectrogramAnalyzer
import com.samplfie.desktop.audio.DesktopSpectrogramPalette
import com.samplfie.desktop.audio.DesktopWavCodec
import com.samplfie.desktop.audio.SignalAnalyzer
import com.samplfie.desktop.model.DesktopAudioBuffer
import com.samplfie.desktop.model.DesktopSampleFormat
import com.samplfie.desktop.model.DesktopStation
import com.samplfie.desktop.model.SignalWaveformType
import com.samplfie.desktop.network.SessionHandoffSnapshot
import com.samplfie.desktop.plugins.DesktopPluginCatalogRegistry
import com.samplfie.desktop.plugins.DesktopPluginHostEngine
import com.samplfie.desktop.plugins.DesktopPluginRepository
import com.samplfie.desktop.plugins.PluginDspKernel
import com.samplfie.desktop.tools.ChannelScope
import com.samplfie.desktop.tools.DesktopAudioToolsEngine
import com.samplfie.desktop.tools.DesktopToolsTab
import com.samplfie.desktop.tools.MonoFoldMode
import com.samplfie.desktop.tools.StereoSplitMode
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopToolsAndPluginsTest {

    @Test
    fun `10-stop spectrogram palette presets, custom hex parser, LUT, and STFT analyzer work`() {
        for (preset in DesktopSpectrogramPalette.PRESETS) {
            assertEquals(10, preset.stopsArgb.size)
            val lut = DesktopSpectrogramPalette.buildLut256(preset.stopsArgb)
            assertEquals(256, lut.size)
            assertEquals(preset.stopsArgb.first().toInt(), lut[0])
            assertEquals(preset.stopsArgb.last().toInt(), lut[255])
        }

        val custom = DesktopSpectrogramPalette.parseTenHexStops(
            "#000000 #111111 #222222 #333333 #444444 #555555 #666666 #777777 #888888 #FFFFFF"
        )
        assertTrue(custom.isSuccess)
        assertEquals(10, custom.getOrThrow().stopsArgb.size)
        assertTrue(DesktopSpectrogramPalette.parseTenHexStops("#000000 #FFFFFF").isFailure)

        val sig = SignalAnalyzer.generateTestSignal(SignalWaveformType.SINE, 2500.0, 0.35f, 44100, 1)
        val spec = DesktopSpectrogramAnalyzer.compute(sig, maxFrames = 64, freqBins = 48, fftSize = 512)
        assertFalse(spec.isEmpty)
        val midF = spec.frames / 2
        val bin2500 = (spec.freqHzToNormY(2500f) * spec.bins).toInt().coerceIn(0, spec.bins - 1)
        val bin9000 = (spec.freqHzToNormY(9000f) * spec.bins).toInt().coerceIn(0, spec.bins - 1)
        val peakNear2500 = ((bin2500 - 1)..(bin2500 + 1)).maxOf { b ->
            spec.valueAt(midF, b.coerceIn(0, spec.bins - 1))
        }
        assertTrue(
            "Expected 2.5kHz energy ($peakNear2500) > 9kHz energy (${spec.valueAt(midF, bin9000)})",
            peakNear2500 > spec.valueAt(midF, bin9000) + 0.25f
        )
    }

    @Test
    fun `desktop wav codec roundtrips all 6 PCM and IEEE float formats`() {
        val base = SignalAnalyzer.generateTestSignal(SignalWaveformType.SINE, 440.0, 0.1f, 48000, 2)
        for (fmt in DesktopSampleFormat.entries) {
            val bytes = DesktopWavCodec.encodeToBytes(base, targetFormat = fmt, dither = fmt == DesktopSampleFormat.PCM_8)
            val decoded = DesktopWavCodec.readWav(bytes, "RT_${fmt.name}")
            assertEquals(48000, decoded.sampleRate)
            assertEquals(2, decoded.channels)
            assertEquals(fmt, decoded.sampleFormat)
            assertEquals(base.frameCount, decoded.frameCount)
            val tol = when (fmt) {
                DesktopSampleFormat.PCM_8 -> 0.03f
                DesktopSampleFormat.PCM_16 -> 0.002f
                else -> 0.0002f
            }
            val diff = DesktopAudioToolsEngine.computeAudioDifference(base, decoded)
            assertTrue("Format $fmt RMS diff ${diff.rmsDiff} exceeded $tol", diff.rmsDiff <= tol)
        }
    }

    @Test
    fun `desktop audio tools suite executes all 8 DSP tools accurately`() {
        val sr = 44100
        val frames = (0.45f * sr).toInt()
        val dualSamples = FloatArray(frames) { f ->
            (0.42 * sin(2.0 * PI * 350.0 * f / sr) + 0.42 * sin(2.0 * PI * 4200.0 * f / sr)).toFloat()
        }
        val dual = DesktopAudioBuffer(dualSamples, sr, 1, "Dual")
        val specBefore = DesktopSpectrogramAnalyzer.compute(dual, 64, 64, 512)
        val cleaned = DesktopAudioToolsEngine.spectralDelete(dual, 0.1f, 0.9f, 3400f, 5000f, 96f, 1024)
        val specAfter = DesktopSpectrogramAnalyzer.compute(cleaned, 64, 64, 512)
        val midF = 32
        val b4200 = (specAfter.freqHzToNormY(4200f) * (specAfter.bins - 1)).toInt()
        val b350 = (specAfter.freqHzToNormY(350f) * (specAfter.bins - 1)).toInt()
        assertTrue(specAfter.valueAt(midF, b4200) < specBefore.valueAt(midF, b4200) - 0.25f)
        assertTrue(specAfter.valueAt(midF, b350) > 0.55f)

        val stereo = SignalAnalyzer.generateTestSignal(SignalWaveformType.SAWTOOTH, 220.0, 0.15f, 44100, 2)
        val invAll = DesktopAudioToolsEngine.invertPolarity(stereo, 0f, 1f, ChannelScope.ALL)
        val diffSame = DesktopAudioToolsEngine.computeAudioDifference(stereo, stereo)
        assertTrue(diffSame.nullDepthDb <= -110f)
        assertTrue(diffSame.correlation > 0.999f)

        val diffInv = DesktopAudioToolsEngine.computeAudioDifference(stereo, invAll)
        assertTrue(diffInv.correlation < -0.999f)

        val cancelMix = DesktopAudioToolsEngine.mixBuffers(stereo, invAll, 1f, 1f, 0f, false)
        assertTrue(cancelMix.peakLevel() < 1e-4f)

        val mono = DesktopAudioToolsEngine.mixToMono(stereo, MonoFoldMode.EQUAL_POWER)
        assertEquals(1, mono.channels)

        val split = DesktopAudioToolsEngine.splitToStereo(mono, StereoSplitMode.PSEUDO_STEREO_COMB, 10f)
        assertEquals(2, split.channels)

        val resampled = DesktopAudioToolsEngine.convertSampleRate(mono, 96000, reinterpretHeaderOnly = false)
        assertEquals(96000, resampled.sampleRate)
        assertTrue(abs(resampled.durationSeconds - mono.durationSeconds) < 0.01f)

        val reinterpreted = DesktopAudioToolsEngine.convertSampleRate(mono, 22050, reinterpretHeaderOnly = true)
        assertEquals(22050, reinterpreted.sampleRate)
        assertEquals(mono.frameCount, reinterpreted.frameCount)

        val fmt24 = DesktopAudioToolsEngine.convertSampleFormat(mono, DesktopSampleFormat.PCM_24, dither = true)
        assertEquals(DesktopSampleFormat.PCM_24, fmt24.sampleFormat)
    }

    @Test
    fun `desktop plugin repository, linux native plugin scanner, and 12 DSP kernels work`() {
        val tempRoot = Files.createTempDirectory("desktop_plugins_test").toFile()
        val pluginStore = File(tempRoot, "store")
        val lv2Dir = File(tempRoot, "lv2").apply { mkdirs() }
        val vst3Dir = File(tempRoot, "vst3").apply { mkdirs() }
        File(lv2Dir, "calf_reverb.lv2").mkdirs()
        File(vst3Dir, "SamplfieSyn.vst3").mkdirs()
        File(vst3Dir, "ladspa_comp.so").writeText("stub")

        try {
            val repo = DesktopPluginRepository(pluginStore)
            val builtins = repo.loadAllPlugins()
            assertEquals(2, builtins.size)

            val scanned = repo.scanLinuxNativePlugins(extraDirs = listOf(lv2Dir, vst3Dir))
            assertTrue(scanned.any { it.name == "calf_reverb" && it.formatTag == "LV2" })
            assertTrue(scanned.any { it.name == "SamplfieSyn" && it.formatTag == "VST3" })
            assertTrue(scanned.any { it.name == "ladspa_comp" && it.formatTag == "CUSTOM" })

            val tubeEntry = DesktopPluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.TUBE_WARMTH }
            val installed = repo.installPlugin(tubeEntry.toDefinition())
            val roundtrip = DesktopPluginCatalogRegistry.fromJson(DesktopPluginCatalogRegistry.toJson(installed)).getOrThrow()
            assertEquals(installed.id, roundtrip.id)
            assertEquals(installed.kernel, roundtrip.kernel)
            assertTrue(repo.loadAllPlugins().any { it.id == installed.id })

            val sig = SignalAnalyzer.generateTestSignal(SignalWaveformType.SINE, 330.0, 0.2f, 44100, 2)
            for (entry in DesktopPluginCatalogRegistry.INSTALLABLE_CATALOG) {
                val def = entry.toDefinition()
                val out = DesktopPluginHostEngine.processChain(sig, listOf(def.createSlotInstance()), mapOf(def.id to def))
                assertNotNull(out)
                assertTrue(out.samples.all { it.isFinite() && it in -1f..1f })
            }

            assertTrue(repo.uninstallPlugin(installed.id))
            assertFalse(repo.loadAllPlugins().any { it.id == installed.id })
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `controller wires tools tab, custom 10-stop gradient, plugins chain, bounce, and station handoff`() {
        val ctrl = DesktopStudioController()
        try {
            ctrl.generateTestSignal(SignalWaveformType.SAWTOOTH, 220.0)
            ctrl.selectStation(DesktopStation.TOOLS)
            assertEquals(DesktopStation.TOOLS, ctrl.uiState.value.station)
            assertFalse(ctrl.uiState.value.spectrogram.isEmpty)

            ctrl.setToolsTab(DesktopToolsTab.PLUGIN_INSTALLER)
            assertEquals(DesktopToolsTab.PLUGIN_INSTALLER, ctrl.toolsState.value.activeTab)

            val customHex = "#010101, #121212, #232323, #343434, #454545, #565656, #676767, #787878, #898989, #FEFEFE"
            assertTrue(ctrl.applyCustomTenStopGradient(customHex).isSuccess)
            assertEquals("custom10", ctrl.toolsState.value.specPalette.id)

            val exciter = ctrl.toolsState.value.catalogEntries.first { it.kernel == PluginDspKernel.MULTIBAND_EXCITER }
            ctrl.installCatalogPlugin(exciter)
            ctrl.addPluginToChain(exciter.id)
            assertEquals(1, ctrl.toolsState.value.pluginChain.size)

            val dryBeforeBounce = ctrl.uiState.value.dryBuffer!!.samples.copyOf()
            ctrl.bouncePluginChainToDryBuffer()
            assertFalse(ctrl.uiState.value.dryBuffer!!.samples.contentEquals(dryBeforeBounce))
            assertTrue(ctrl.toolsState.value.pluginChain.first().bypassed)

            val outWav = Files.createTempFile("samplfie_dt_32f", ".wav").toFile()
            outWav.deleteOnExit()
            ctrl.setFormatConversionParams(DesktopSampleFormat.FLOAT_32, dither = false)
            val exportRes = ctrl.exportWav(outWav)
            assertTrue(exportRes.isSuccess)
            val reread = DesktopWavCodec.readWav(outWav)
            assertEquals(DesktopSampleFormat.FLOAT_32, reread.sampleFormat)

            assertEquals(DesktopStation.TOOLS, SessionHandoffSnapshot.mapStationKeyToDesktop("TOOLS"))
            assertEquals(DesktopStation.TOOLS, SessionHandoffSnapshot.mapStationKeyToDesktop("PLUGINS"))

            ctrl.uninstallCustomPlugin(exciter.id)
        } finally {
            ctrl.shutdown()
        }
    }
}
