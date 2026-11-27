package com.example

import com.example.audio.AudioBuffer
import com.example.dsp.BiquadFilterMode
import com.example.dsp.BiquadFilterParams
import com.example.dsp.DistortionCurve
import com.example.dsp.DistortionParams
import com.example.dsp.DspChainEngine
import com.example.dsp.DspModules
import com.example.dsp.FxRackState
import com.example.network.SessionHandoffSnapshot
import com.example.plugins.LadspaBrowserClient
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspDistortionFilterAndLadspaTest {

    private fun testStereoTone(frames: Int = 2048, sampleRate: Int = 44100): FloatArray {
        val out = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val s = (sin(2.0 * PI * 330.0 * f / sampleRate) * 0.5 +
                sin(2.0 * PI * 4200.0 * f / sampleRate) * 0.35).toFloat()
            out[f * 2] = s
            out[f * 2 + 1] = s
        }
        return out
    }

    @Test
    fun `distortion applies all 6 curves and respects level scaling`() {
        val input = testStereoTone()
        for (curve in DistortionCurve.entries) {
            val out = DspModules.applyDistortion(
                samples = input,
                sampleRate = 44100,
                channels = 2,
                params = DistortionParams(
                    enabled = true,
                    curve = curve,
                    drive = 10f,
                    toneHz = 4500f,
                    symmetry = 0.4f,
                    mix = 0.85f,
                    level = 1.15f
                )
            )
            assertEquals(input.size, out.size)
            assertTrue("Zero output for $curve", out.maxOf { kotlin.math.abs(it) } > 0.1f)
            assertTrue("Out of range for $curve", out.all { it in -1f..1f })
        }
    }

    @Test
    fun `biquad filter processes all 6 modes and changes frequency balance`() {
        val input = testStereoTone()
        for (mode in BiquadFilterMode.entries) {
            val out = DspModules.applyBiquadFilter(
                samples = input,
                sampleRate = 44100,
                channels = 2,
                params = BiquadFilterParams(
                    enabled = true,
                    mode = mode,
                    cutoffHz = 900f,
                    resonanceQ = 2.8f,
                    drive = 1.4f,
                    lfoRateHz = 0.8f,
                    lfoDepth = 0.25f,
                    mix = 1.0f,
                    level = 1.0f
                )
            )
            assertEquals(input.size, out.size)
            assertTrue("Zero output for $mode", out.maxOf { kotlin.math.abs(it) } > 0.05f)
            assertTrue("NaN/clipping for $mode", out.all { it.isFinite() && it in -1f..1f })
        }
    }

    @Test
    fun `fx rack counts 13 fx plus 3 filter modules and roundtrips via SessionHandoffSnapshot`() {
        val rack = FxRackState(
            distortion = DistortionParams(enabled = true, curve = DistortionCurve.WAVEFOLD, drive = 12f, level = 1.3f),
            biquadFilter = BiquadFilterParams(enabled = true, mode = BiquadFilterMode.BAND_PASS, cutoffHz = 1350f, resonanceQ = 3.6f, level = 1.1f),
            wah = com.example.dsp.WahParams(enabled = true),
            reverb = com.example.dsp.ReverbParams(enabled = true)
        )
        assertEquals(2, rack.fxActiveCount)
        assertEquals(2, rack.filterActiveCount)
        assertEquals(4, rack.activeCount)
        assertEquals(13, FxRackState.FX_MODULE_COUNT)
        assertEquals(3, FxRackState.FILTER_MODULE_COUNT)

        val buf = AudioBuffer(samples = testStereoTone(512), sampleRate = 44100, channels = 2, name = "ROUNDTRIP")
        val snap = SessionHandoffSnapshot(fxRack = rack, buffer = buf)
        val decoded = SessionHandoffSnapshot.decodeFromWireBytes(snap.encodeToWireBytes())!!

        assertTrue(decoded.fxRack.distortion.enabled)
        assertEquals(DistortionCurve.WAVEFOLD, decoded.fxRack.distortion.curve)
        assertEquals(12f, decoded.fxRack.distortion.drive, 0.01f)
        assertTrue(decoded.fxRack.biquadFilter.enabled)
        assertEquals(BiquadFilterMode.BAND_PASS, decoded.fxRack.biquadFilter.mode)
        assertEquals(1350f, decoded.fxRack.biquadFilter.cutoffHz, 0.1f)

        val processed = DspChainEngine.process(buf, rack)
        assertEquals(buf.frameCount, processed.frameCount)
        assertTrue(processed.samples.maxOf { kotlin.math.abs(it) } > 0.05f)
    }

    @Test
    fun `ladspa browser blocks native binaries and parses rdf xml and json dlc feeds`() {
        assertTrue(LadspaBrowserClient.isBlockedBinaryUrl("https://example.org/plugins/tap_echo.so"))
        assertTrue(LadspaBrowserClient.isBlockedBinaryUrl("https://example.org/vst/synth.dll?dl=1"))
        assertTrue(LadspaBrowserClient.isBlockedBinaryUrl("https://example.org/code.jar"))
        assertTrue(LadspaBrowserClient.isBlockedBinaryUrl("https://example.org/payload.dex"))
        assertFalse(LadspaBrowserClient.isBlockedBinaryUrl("https://ladspa.org/ladspa_sdk/rdf/ladspa.rdfs"))

        val rdfXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:dc="http://purl.org/dc/elements/1.1/"
                     xmlns:ladspa="http://ladspa.org/ontology#">
              <ladspa:Plugin rdf:about="http://ladspa.org/ontology#2158">
                <dc:title>TAP TubeWarmth Test</dc:title>
                <dc:creator>Tom Szilagyi</dc:creator>
                <ladspa:hasUniqueId>2158</ladspa:hasUniqueId>
                <ladspa:InputControlPort>
                  <ladspa:hasLabel>Drive</ladspa:hasLabel>
                  <ladspa:hasLowerBound>1.0</ladspa:hasLowerBound>
                  <ladspa:hasUpperBound>10.0</ladspa:hasUpperBound>
                  <ladspa:hasDefault>4.2</ladspa:hasDefault>
                </ladspa:InputControlPort>
              </ladspa:Plugin>
            </rdf:RDF>
        """.trimIndent()

        val parsedRdf = LadspaBrowserClient.parseLadspaRdfXml(rdfXml)
        assertEquals(1, parsedRdf.size)
        assertEquals(2158, parsedRdf[0].ladspaUniqueId)
        assertEquals("TAP TubeWarmth Test", parsedRdf[0].name)
        assertFalse(parsedRdf[0].params.isEmpty())

        assertTrue(LadspaBrowserClient.CURATED_COMMUNITY_LADSPA.size >= 8)
    }
}
