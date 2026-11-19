package com.example.dsp

import com.example.audio.AudioBuffer

/**
 * Applies the active FX Rack signal chain non-destructively to an AudioBuffer.
 */
object DspChainEngine {

    fun process(source: AudioBuffer, rack: FxRackState): AudioBuffer {
        if (rack.masterBypass || rack.activeCount == 0 || source.frameCount == 0) {
            return source
        }

        var working = source.samples
        val sr = source.sampleRate
        val ch = source.channels

        // 1. Cleanup & Pitch/Time
        working = DspModules.applyNoiseRemoval(working, sr, ch, rack.noiseRemoval)
        working = DspModules.applyPitchAndSpeed(working, sr, ch, rack.pitchSpeed)

        // 2. Dynamics & Saturation
        working = DspModules.applyGain(working, rack.gain)
        working = DspModules.applyOverdrive(working, sr, ch, rack.overdrive)

        // 3. Filters & Modulation
        working = DspModules.applyWah(working, sr, ch, rack.wah)
        working = DspModules.applyPhaser(working, sr, ch, rack.phaser)
        working = DspModules.applyFlanger(working, sr, ch, rack.flanger)
        working = DspModules.applyChorus(working, sr, ch, rack.chorus)
        working = DspModules.applyRotarySpeaker(working, sr, ch, rack.rotarySpeaker)

        // 4. Time & Space
        working = DspModules.applyEcho(working, sr, ch, rack.echo)
        working = DspModules.applyDelay(working, sr, ch, rack.delay)
        working = DspModules.applyReverb(working, sr, ch, rack.reverb)

        return source.copy(samples = working)
    }
}
