# Samplfie2 Audio DSP, Stompbox & Real-Time Visualization Reference

All 12 audio processors operate on normalized 32-bit floating point PCM buffers (`[-1.0f, 1.0f]`) in `com.example.dsp`, supporting Mono (`1 ch`) and Interleaved Stereo (`2 ch`) across `22050`, `44100`, `48000`, and `96000` Hz sample rates.

## Signal Chain & Expanded Controls
1. **Noise Removal** — Sweepable high-pass rumble filter (`30–250 Hz`) + RMS soft-knee noise-floor expander with adjustable `thresholdDb`, `reductionDb`, and `releaseMs`.
2. **Pitch & Speed** — WSOLA time-stretching (`grainSizeMs`), Cubic Hermite resampling (`-12..+12` semitones + `fineCents` `±50ct`), and DJ **Vinyl Link Mode**.
3. **Gain & Dynamics** — Linear gain (`±24 dB`), soft-knee compressor (`compressionRatio` `1:1..6:1`), Peak Normalize, Mid/Side `stereoWidth` (`0–180%`), and variable-ceiling soft limiter.
4. **Overdrive** — Pre-clip HPF (`preHighPassHz`), tunable asymmetric tube `tanh` waveshaper (`drive`, `asymmetry`), 1-pole `toneHz` LPF, and `mix`.
5. **Wah** — Resonant State-Variable Bandpass Filter with 3 modes (`AUTO_ENV`, `LFO_SWEEP`, `MANUAL_PEDAL`), `centerFreqHz`, `resonanceQ`, `rateHz`, and `manualPedalPos`.
6. **Phaser** — Selectable `4 / 6 / 8`-stage cascaded all-pass filter network with LFO `rateHz`, `centerFreqHz`, `stereoPhaseOffset`, `feedback`, and `mix`.
7. **Flanger** — Fractional modulated comb delay with `manualDelayMs`, `depthMs`, `rateHz`, bipolar `feedback`, and `stereoSpread`.
8. **Chorus** — Selectable `2 / 3 / 4`-voice ensemble modulated delay (`baseDelayMs`, `depthMs`, `rateHz`) + 1-pole `highCutHz` shimmer filter.
9. **Rotary Speaker** — Dual-band Leslie cabinet with `crossoverHz`, `fastMode` / `brake` switches, `cabinetDrive`, Doppler pitch vibrato, and quadrature AM swirl.
10. **Echo** — Analog tape echo with `wowFlutter` pitch drift, `saturation` in feedback loop, and HF `damping`.
11. **Delay** — Digital cross-tap delay with optional **BPM Sync** (`1/4`, `1/8D`, `1/8`, `1/16`), feedback `highPassHz`/`lowPassHz`, and `stereoSpread`.
12. **Reverb** — Schroeder 4-parallel damped comb + early reflections generator + low-cut filter + M/S `stereoWidth` + 2 series all-pass diffusers.

## Mini Stompbox Master Processor (`StompboxMasterState`)
Controls the overall signal chain output on the **LIVE** spoke:
- **Group Kill Switches**: `DRIVE KILL`, `MOD KILL`, `SPACE KILL` instantaneously mute entire FX stages.
- **Macro Scalars**: `dynamicsTrimDb` (`±6 dB`), `toneTilt` DJ 1-knob Bass/Air EQ, `modScale` (`0–180%`), `spaceScale` (`0–180%`), and `masterWetMix` (`0–100%`).
- **X/Y Live Pad**: Sweeps `toneTilt` & `modScale` (X-axis) and `spaceScale` (Y-axis) in real time.

## Real-Time Visualization & Loop Sample Refiner
- `AudioAnalyzer` computes downsampled min/max envelopes for ghost dry vs. FX-processed waveform overlay and 32-band logarithmic Radix-2 Cooley-Tukey FFT spectra around the active playhead or mic stream.
- **Samples Loop Refinement Editor** refines loop slices with zero-crossing snap, IN/OUT trim, linear fade-in/out, reverse, DC offset removal, peak normalization, pitch tuning, and continuous loop audition.
