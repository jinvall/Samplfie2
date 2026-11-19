# Samplfie2 Audio DSP & FX Reference

All 12 audio processors operate on normalized 32-bit floating point PCM buffers (`[-1.0f, 1.0f]`) in `com.example.dsp`, supporting Mono (`1 ch`) and Interleaved Stereo (`2 ch`) across `22050`, `44100`, `48000`, and `96000` Hz sample rates.

## Signal Chain Order
1. **Noise Removal** — 80 Hz 1st-order high-pass rumble filter + adaptive RMS noise-floor gate with soft-knee expander and smooth attack/release envelope (`thresholdDb`, `reductionDb`, `highPassEnabled`).
2. **Pitch & Speed** —
   - Independent **Speed** (`0.5x – 2.0x`): WSOLA-style overlap-add time stretching.
   - Independent **Pitch** (`-12.0 – +12.0` semitones): Cubic Hermite resampling paired with WSOLA length compensation.
   - **Vinyl Link Mode**: Classic DJ turntable pitch-follows-speed mode.
3. **Gain & Dynamics** — Linear dB gain (`-24 dB` to `+24 dB`), optional Peak Normalize to `-0.3 dBFS`, and soft-clip safety limiter (`tanh` knee above `0.92f`).
4. **Overdrive** — Asymmetric tube/diode waveshaper (`tanh(x * drive + bias)`) with post-shaper 1-pole tone low-pass filter and wet/dry mix.
5. **Wah** — Resonant State-Variable Bandpass Filter (SVF) with sweepable center frequency, Q resonance, LFO rate, and envelope-follower sensitivity (Auto-Wah + manual sweep).
6. **Phaser** — 4-stage cascaded first-order all-pass filter network modulated by a triangle/sine LFO with feedback resonance and wet/dry mix.
7. **Flanger** — Short modulated fractional delay line (`0.4 ms – 7.5 ms`) using linear/cubic interpolation, bipolar feedback resonance, LFO rate/depth, and wet/dry mix.
8. **Chorus** — Multi-voice modulated delay (`14 ms – 34 ms` base delay, 3 detuned LFO phases) for wide stereo/mono thickening.
9. **Rotary Speaker** — Dual-band Leslie cabinet simulation splitting low drum (`< 800 Hz`) and high horn (`>= 800 Hz`) crossovers with Doppler pitch vibrato and quadrature amplitude modulation (`rateHz`, `depth`, `mix`).
10. **Echo** — Analog/tape-style delay (`50 ms – 1000 ms`) with low-pass high-frequency damping inside the feedback path.
11. **Delay** — Crisp digital delay (`20 ms – 1200 ms`) with feedback and stereo cross-tap spread.
12. **Reverb** — Schroeder/Freeverb algorithmic network (4 parallel damped comb filters + 2 series all-pass diffusers + pre-delay).

## Real-Time Visualization & FFT Analyzer
- `AudioAnalyzer` (`com.example.audio.AudioAnalyzer`) computes:
  - Downsampled waveform peaks (`min`/`max` RMS envelopes) for both raw/dry and FX-processed buffers, enabling instant visual comparison when toggling FX or A/B Bypass.
  - Real-time Radix-2 Cooley-Tukey FFT (`512` / `1024` point Hann-windowed spectrum) around the active playhead or live mic capture buffer, feeding 32–64 logarithmic frequency bands for the DJ console spectrum analyzer.
