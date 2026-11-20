# Samplfie2

**Samplfie2** is a Kotlin + Jetpack Compose live DJ performance studio built around a **Circular Production Booth Hub-and-Spoke** workstation featuring universal audio import (`.wav`/`.mp3`/`.aac`/`.ogg`/`.flac` decoded to editable `.wav`), tactile TAC mic capture, real-time waveform & FFT spectrum visualization, 12 expanded pro audio DSP FX, a **Samples Loop Refinement Editor**, a 16-step Sequencer & Android MIDI rig, a **globally synced multi-track Playhead**, a **Live Stompbox Master Multi-FX Processor**, and local/network release export.

## Circular Studio Topology
- **Production Booth (`BOOTH`)**: Central studio hub displaying the live beat/bar ring, synced master transport, universal audio import, and one-tap radial spokes connecting every studio workstation:
  1. **DECK (`DECK`)**: Press-and-hold **TAC** mic capture, universal SAF `OpenDocument` audio import -> `.wav`, dual-layer waveform & 32-band Radix-2 FFT spectrum visualizer (`WAVE` / `FFT` / `DUAL`), draggable `IN`/`OUT` selection markers, **EXTRACT**, **UNDO**, **RESET**, and continuous `AudioTrack` transport review (`PLAY/PAUSE`, `STOP`, `LOOP`, `SEL`, `A/B BYPASS`).
  2. **SAMPLES (`SAMPLES`)**: 4-slot Looper (`1/2/4` bars, mute/active), local `.wav` take bank, and an integrated **Loop Sample Refinement Editor** (zero-crossing snap, loop trim, fade in/out, reverse, DC offset removal, peak normalize, pitch tune, continuous loop audition, commit back to loop slot or Deck).
  3. **INSTRUMENTS (`INST`)**: 16-step Sequencer (`Sample` + synthesized `808 Kick`, `909 Snare`, `Hi-Hat`), BPM (`60–180`), Groove Swing, live trigger pads, and real `android.media.midi.MidiManager` Note/CC/Clock hardware control.
  4. **TRACKS (`TRACKS`)**: 4-track arrangement timeline with a **globally synced playhead** across tracks, looper, and sequencer, clip nudging, mute/solo, interactive scrubbing beat ruler, and master mixdown.
  5. **FX RACK (`FX`)**: Expanded parameter control across all 12 pure-Kotlin DSP FX modules + presets (`Bypass`, `Clean Mic`, `Dub Siren`, `Acid Wah`, `Rotary Club`, `Lo-Fi Vinyl`, `Master Glue`).
  6. **LIVE (`LIVE`)**: Houses the die-cast **Mini Stompbox Master Multi-FX Processor** (`Dynamics Trim`, `DJ Tone Tilt EQ`, `Mod Scale`, `Space Scale`, `Master Wet Mix`, `Drive/Mod/Time Kill` switches, interactive `X/Y Live Pad`, `Tap Tempo`, and `Stomp Bypass`) plus tailored console profiles for **Stage DJ Performance**, **Live Recording**, and **Final Collaborative Audio Studio Release**, alongside local `.WAV`/`.MP3` export and TCP/UDP network streaming config.

## Documentation Index
- [Architecture Overview](docs/ARCHITECTURE.md)
- [DSP, Stompbox & Real-Time Visualization Reference](docs/DSP_REFERENCE.md)
- [Network Streaming & Local WAV/MP3 Export Reference](docs/NETWORK_AND_EXPORT.md)
- [Build Stages & Verification Log](docs/STAGES_AND_CHANGELOG.md)
