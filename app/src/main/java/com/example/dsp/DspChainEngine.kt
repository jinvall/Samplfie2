package com.example.dsp

import com.example.audio.AudioBuffer
import com.example.plugins.AudioPluginHostEngine
import com.example.plugins.PluginSlotInstance
import com.example.plugins.SamplfiePluginDefinition

/**
 * Applies the active FX Rack signal chain, Live Stompbox Master Multi-FX macro processor,
 * and active DSP Plugin Chain non-destructively to an AudioBuffer.
 */
object DspChainEngine {

    fun process(
        source: AudioBuffer,
        rack: FxRackState,
        stompbox: StompboxMasterState = StompboxMasterState(engaged = false),
        bpm: Float = 124f,
        pluginChain: List<PluginSlotInstance> = emptyList(),
        pluginDefs: Map<String, SamplfiePluginDefinition> = emptyMap()
    ): AudioBuffer {
        if (rack.masterBypass || source.frameCount == 0) {
            return source
        }

        val activePlugins = pluginChain.any { it.enabled && !it.bypassed }
        val stompActive = stompbox.engaged
        val hasStompEqOrTrim = stompActive && (
            kotlin.math.abs(stompbox.toneTilt) >= 0.02f ||
                kotlin.math.abs(stompbox.dynamicsTrimDb) >= 0.05f
            )
        if (rack.activeCount == 0 && !hasStompEqOrTrim && !activePlugins) {
            return source
        }

        var working = source.samples
        val sr = source.sampleRate
        val ch = source.channels

        // 1. Cleanup, Pitch/Time & Octave
        working = DspModules.applyNoiseRemoval(working, sr, ch, rack.noiseRemoval)
        working = DspModules.applyPitchAndSpeed(working, sr, ch, rack.pitchSpeed)
        working = DspModules.applyOctave(working, sr, ch, rack.octave)

        // 2. Dynamics & Saturation (honors Stompbox Drive/Dyn Kill)
        if (!stompActive || !stompbox.killDrive) {
            working = DspModules.applyGain(working, rack.gain, ch)
            working = DspModules.applyOverdrive(working, sr, ch, rack.overdrive)
            working = DspModules.applyDistortion(working, sr, ch, rack.distortion)
        }

        // 3. Filters & Modulation (honors Stompbox Mod Kill + Mod Scale)
        if (!stompActive || !stompbox.killMod) {
            val modScale = if (stompActive) stompbox.modScale.coerceIn(0f, 1.8f) else 1f
            working = DspModules.applyBiquadFilter(
                working,
                sr,
                ch,
                rack.biquadFilter.copy(mix = (rack.biquadFilter.mix * modScale).coerceIn(0f, 1f))
            )
            working = DspModules.applyWah(
                working,
                sr,
                ch,
                rack.wah.copy(mix = (rack.wah.mix * modScale).coerceIn(0f, 1f))
            )
            working = DspModules.applyPhaser(
                working,
                sr,
                ch,
                rack.phaser.copy(mix = (rack.phaser.mix * modScale).coerceIn(0f, 1f))
            )
            working = DspModules.applyFlanger(
                working,
                sr,
                ch,
                rack.flanger.copy(mix = (rack.flanger.mix * modScale).coerceIn(0f, 1f))
            )
            working = DspModules.applyChorus(
                working,
                sr,
                ch,
                rack.chorus.copy(mix = (rack.chorus.mix * modScale).coerceIn(0f, 1f))
            )
            working = DspModules.applyRotarySpeaker(
                working,
                sr,
                ch,
                rack.rotarySpeaker.copy(mix = (rack.rotarySpeaker.mix * modScale).coerceIn(0f, 1f))
            )
        }

        // 4. Time & Space (honors Stompbox Time Kill + Space Scale + BPM sync)
        if (!stompActive || !stompbox.killTime) {
            val spaceScale = if (stompActive) stompbox.spaceScale.coerceIn(0f, 1.8f) else 1f
            working = DspModules.applyEcho(
                working,
                sr,
                ch,
                rack.echo.copy(mix = (rack.echo.mix * spaceScale).coerceIn(0f, 1f))
            )
            working = DspModules.applyDelay(
                working,
                sr,
                ch,
                rack.delay.copy(mix = (rack.delay.mix * spaceScale).coerceIn(0f, 1f)),
                bpm = bpm
            )
            working = DspModules.applyAnalogDelay(
                working,
                sr,
                ch,
                rack.analogDelay.copy(mix = (rack.analogDelay.mix * spaceScale).coerceIn(0f, 1f))
            )
            working = DspModules.applyReverb(
                working,
                sr,
                ch,
                rack.reverb.copy(mix = (rack.reverb.mix * spaceScale).coerceIn(0f, 1f))
            )
        }

        // 5. Stompbox Master Tilt EQ, Dynamics Trim & Overall Master Wet/Dry Blend
        if (stompActive) {
            working = DspModules.applyTiltEqAndTrim(
                samples = working,
                sampleRate = sr,
                channels = ch,
                toneTilt = stompbox.toneTilt,
                trimDb = stompbox.dynamicsTrimDb
            )
            val wetMix = stompbox.masterWetMix.coerceIn(0f, 1f)
            if (wetMix < 0.995f && working.size == source.samples.size) {
                val dryMix = 1f - wetMix
                val blended = FloatArray(working.size) { i ->
                    (source.samples[i] * dryMix + working[i] * wetMix).coerceIn(-1f, 1f)
                }
                working = blended
            }
        }

        var resultBuf = source.copy(samples = working)
        if (activePlugins) {
            resultBuf = AudioPluginHostEngine.processChain(resultBuf, pluginChain, pluginDefs)
        }
        return resultBuf
    }
}
