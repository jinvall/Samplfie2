# Samplfie2

**Samplfie2** is a Kotlin + Jetpack Compose live DJ performance studio built around a **Circular Production Booth Hub-and-Spoke** workstation featuring universal audio import (`.wav`/`.mp3`/`.aac`/`.ogg`/`.flac` decoded to editable `.wav`), tactile TAC mic capture, real-time waveform & FFT spectrum visualization, 12 expanded pro audio DSP FX, a **Samples Loop Refinement Editor**, a 16-step Sequencer & Android MIDI rig, a **globally synced multi-track Playhead**, a **Live Stompbox Master Multi-FX Processor**, and local/network release export.

## Circular Studio Topology
- **Production Booth (`BOOTH` & Persistent Master Hub)**: Center-seat circular studio view where **Master Production Control** remains on screen across every station spoke (`DockedCircularProductionHub` with expandable inline 360° wheel on phones, and permanently docked `WideCircularProductionSidebar` on tablets/landscape), providing live beat/bar dial, synced master transport, deck play/pause, tap BPM, stomp bypass, VU metering, universal audio import, and one-tap radial spokes connecting every studio workstation:
  1. **DECK (`DECK`)**: Press-and-hold **TAC** mic capture, universal SAF `OpenDocument` audio import -> `.wav`, dual-layer waveform & 32-band Radix-2 FFT spectrum visualizer (`WAVE` / `FFT` / `DUAL`), draggable `IN`/`OUT` selection markers, **EXTRACT**, **UNDO**, **RESET**, and continuous `AudioTrack` transport review (`PLAY/PAUSE`, `STOP`, `LOOP`, `SEL`, `A/B BYPASS`).
  2. **SAMPLES (`SAMPLES`)**: 4-slot Looper (`1/2/4` bars, mute/active), local `.wav` take bank, and an integrated **Loop Sample Refinement Editor** (zero-crossing snap, loop trim, fade in/out, reverse, DC offset removal, peak normalize, pitch tune, continuous loop audition, commit back to loop slot or Deck).
  3. **INSTRUMENTS (`INST`)**: 16-step Sequencer (`Sample` + synthesized `808 Kick`, `909 Snare`, `Hi-Hat`), BPM (`60–180`), Groove Swing, live trigger pads, and real `android.media.midi.MidiManager` Note/CC/Clock hardware control.
  4. **TRACKS (`TRACKS`)**: 4-track arrangement timeline with a **globally synced playhead** across tracks, looper, and sequencer, clip nudging, mute/solo, interactive scrubbing beat ruler, and master mixdown.
  5. **FX RACK (`FX`)**: Expanded parameter control across all 12 pure-Kotlin DSP FX modules + presets (`Bypass`, `Clean Mic`, `Dub Siren`, `Acid Wah`, `Rotary Club`, `Lo-Fi Vinyl`, `Master Glue`).
  6. **LIVE (`LIVE`)**: Houses the die-cast **Mini Stompbox Master Multi-FX Processor** (`Dynamics Trim`, `DJ Tone Tilt EQ`, `Mod Scale`, `Space Scale`, `Master Wet Mix`, `Drive/Mod/Time Kill` switches, interactive `X/Y Live Pad`, `Tap Tempo`, and `Stomp Bypass`) plus tailored console profiles for **Stage DJ Performance**, **Live Recording**, and **Final Collaborative Audio Studio Release**, alongside local `.WAV`/`.MP3` export and TCP/UDP network streaming config.

## Linux Desktop Companion (`:desktop`)
A companion **Linux Desktop Audio, Signal Editor & Chromatic Tuner** (`com.samplfie.desktop`) built with Kotlin + Java2D/Swing using the exact same dark hardware aesthetic and persistent **Circular Studio Hub Sidebar** as the Android app:
- **SIGNAL EDITOR (`EDITOR`)**: Native Linux mic capture (`javax.sound.sampled` ALSA/PulseAudio/PipeWire), `.WAV` import/export & `.MP3` (`jump3r`) export, interactive dual-layer waveform + 48-band Radix-2 FFT spectrum scopes, zero-crossing snap, IN/OUT extract, linear fades, reverse, DC offset removal, peak normalize, undo/reset, and calibrated test-signal generator (`SINE`, `SQUARE`, `SAWTOOTH`, `TRIANGLE`, `CHIRP`, `NOISE`).
- **CHROMATIC TUNER (`TUNER`)**: Pure-Kotlin YIN pitch detector (`32 Hz – 2200 Hz`) with circular Java2D strobe/cents dial (`-50¢..+50¢`), configurable `A4` calibration (`432–448 Hz`), input switching (`LIVE_MIC`, `DECK_BUFFER`, `SMP2_STREAM`), instrument presets (`Guitar Standard`, `Drop D`, `Bass 4`, `Ukulele`, `Violin`, `Chromatic`), and clickable reference pitch tone pads.
- **FX + STOMP (`FX_STOMP`)**: Matching 12-module DSP chain + presets and Master Stompbox (`Drive/Mod/Time Kill`, `Dynamics Trim`, `Tone Tilt`, `Mod/Space Scale`, `Master Wet`).
- **NET LINK (`NET_LINK`)**: Bidirectional `SMP2`-framed TCP/UDP bridge to stream audio directly between the Android app and Linux desktop, plus sample rate, channel, and buffer controls.

### Launching on Linux
```bash
./scripts/launch-linux-studio.sh
# Or build/run directly via Gradle:
gradle :desktop:run
```

## Documentation Index
- [Architecture Overview](docs/ARCHITECTURE.md)
- [DSP, Stompbox & Real-Time Visualization Reference](docs/DSP_REFERENCE.md)
- [Network Streaming & Local WAV/MP3 Export Reference](docs/NETWORK_AND_EXPORT.md)
- [Build Stages & Verification Log](docs/STAGES_AND_CHANGELOG.md)
