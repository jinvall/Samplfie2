# Samplfie2

**Samplfie2** is a Kotlin + Jetpack Compose live DJ performance sampler, real-time audio visualizer & editor, 12-module DSP FX rack, 4-slot Looper, 16-step Sequencer, Multi-Track Timeline with Playhead, Android MIDI controller, TCP/UDP packet streamer, and device MP3 exporter.

## Key Capabilities
1. **TAC Press-and-Hold Mic Capture**: Hold the tactile `TAC HOLD` pad to capture live microphone PCM audio via `AudioRecord`; release to write a `.wav` file and load it immediately into the editor.
2. **Real-Time Audio Visualizer & Editor**:
   - Switchable **WAVE**, **FFT**, and **DUAL** modes on responsive Compose `Canvas`.
   - Dual-layer ghost dry vs. processed waveform overlay, 32-band logarithmic Radix-2 Cooley-Tukey FFT spectrum analyzer with peak caps, draggable `IN`/`OUT` selection handles, playhead scrubbing, **EXTRACT**, **UNDO**, and **RESET**.
   - Audio is reviewable at any time via `AudioTrack` transport (`PLAY/PAUSE`, `STOP`, `LOOP`, `SEL ONLY`, `A/B BYPASS`) with live FX hot-swapping.
3. **12 Functional Audio DSP FX Modules + Live Presets**:
   - Noise Removal (80 Hz HPF + soft-knee RMS gate)
   - Pitch & Speed (WSOLA time-stretch + Hermite pitch + Vinyl Link)
   - Gain & Limiter (dB gain + Peak Normalize + Soft-clip Limiter)
   - Overdrive (Asymmetric `tanh` tube waveshaper + Tone LPF)
   - Wah (Envelope/LFO resonant State-Variable Bandpass)
   - Phaser (4-stage swept all-pass network)
   - Flanger (Resonant modulated comb delay)
   - Chorus (3-voice modulated ensemble)
   - Rotary Speaker (Dual-band Leslie horn/drum crossover)
   - Echo (HF-damped tape echo)
   - Delay (Cross-tap digital delay)
   - Reverb (Schroeder 4-comb + 2-allpass network)
4. **Live DJ Looper, 16-Step Sequencer & Track Timelines**:
   - **4-Slot Looper**: Bar-synced loop decks (`1/2/4` bars), mute/active toggles, mixdown bounce to editor.
   - **16-Step Sequencer**: 4 lanes (`Sample` + synthesized `808 Kick`, `909 Snare`, `Hi-Hat`), BPM (`60–180`), Swing, live step cursor, bounce to timeline.
   - **Track Timeline with Playhead**: 4 arrangement tracks with positioned clips, mute/solo, interactive playhead scrubbing ruler, and master mixdown.
5. **MIDI Hardware Support**:
   - Real `android.media.midi.MidiManager` device discovery, Note triggers (`36-39` Looper, `40-43` Drums, `48-72` Chromatic Pitch), CC mapping (`CC1/74` Wah, `CC71` Overdrive, `CC91` Reverb, `CC93` Echo, `CC7` Gain), and MIDI Clock BPM sync.
6. **Rig Config, TCP/UDP Streaming & MP3 Export**:
   - DataStore-persisted **IP Address**, **Port**, **TCP / UDP**, **Sample Rate**, **Channels**, **Buffer Size**, and **MP3 Bitrate**.
   - Real `Socket` (TCP) & `DatagramSocket` (UDP) `SMP2` framed audio packet streaming.
   - Pure-Java LAME MP3 encoding (`de.sciss:jump3r`) saving directly to `Music/Samplfie2/` via `MediaStore`.

## Documentation Index
- [Architecture Overview](docs/ARCHITECTURE.md)
- [DSP & Real-Time Visualization Reference](docs/DSP_REFERENCE.md)
- [Network Streaming & MP3 Export Reference](docs/NETWORK_AND_EXPORT.md)
- [Build Stages & Verification Log](docs/STAGES_AND_CHANGELOG.md)
