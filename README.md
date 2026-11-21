# Samplfie2

**Samplfie2** is an equal-peer Kotlin DJ performance and studio production system spanning **Android (Kotlin + Jetpack Compose)** and **Linux Desktop (Kotlin + Java2D/Swing)**. Both workstations share the exact same **Circular Production Booth Hub-and-Spoke** architecture, 12-module audio DSP FX rack, Mini Stompbox Master Multi-FX Processor, YIN Chromatic Tuner, customizable studio spokes, persistent loop playback through edits/extractions, a **64-Channel Virtual MIDI Controller (`VMC`)**, and live **`SMH2`/`VMC2`/`SMP2` Equal-Peer Session Handoff**.

## Equal, Customizable Peer Workstations (Android & Linux Desktop)
Picking up where you left off between phone and desktop works bidirectionally over TCP/UDP:
- **Disband AI Stem Separation & Secrets Vault**: Both top toolbars and Production Booth hubs include a persistent **Keys & Secrets Store** (`SecretsVaultRepository` AES-GCM on Android + `.env` `BuildConfig.DISBAND_API_KEY`; POSIX-`0600` `~/.config/samplfie2/secrets.properties` on Linux Desktop) and a full **Disband API** (`https://api.disband.io/v1`) stem separation client. Select exact stems (`Vocals`, `Instrumental`, `Drums`, `Bass`, `Guitar`, `Piano`, `Other`, plus pro sub-stems) or leave unspecified to receive an estimated credit **Toast + Confirmation Dialog** before running a full all-stems separation. Separated WAV stems route cleanly to **Deck**, **Looper**, or **Multitrack Timeline**.
- **Session Handoff (`PUSH -> PEER` / `GRAB <- PEER`)**: Transfers the complete active song `.wav` PCM, playhead fraction, loop flag, playing state, IN/OUT selection, BPM/swing, 12 FX rack parameters, Stompbox X/Y & kill switches, looper & sequencer patterns, YIN `A4` reference calibration, 64-channel VMC mapping table, and active station/spoke.
- **Persistent Loop Playback**: On both Android and Linux Desktop, whenever a track/sample is actively playing with **LOOP** enabled, playback persists uninterrupted through waveform slicing/extractions, linear fades, reverse, DC offset removal, peak normalization, undo/reset, stem swaps, and live DSP/Stompbox tweaks until **STOP** (or **PAUSE**) is pressed.
- **Customizable Spoke Layout**: Both Android (**BOOTH** customizer card) and Linux Desktop (`Workspace` toolbar/booth controls) let musicians customize which stations appear in their navigation bar and on their 360° Circular Studio Wheel.

## Circular Studio Hub & 10 Peer Spokes (`BOOTH` Hub + Stations)
1. **PRODUCTION BOOTH (`BOOTH`)**: Central 360° workstation diagram (`CircularStudioHub` / `ProductionBoothPanel`), Disband AI Stem Separation console, Keys & Secrets Vault, Session Handoff (`PUSH`/`GRAB`/`APPLY`), Dock/Wheel customizer, global tap BPM, VU metering, universal import, and quick export.
2. **CAPTURE & WAV DECK / SIGNAL EDITOR (`DECK` / `EDITOR`)**: Hold-to-record **TAC** capture / Linux mic capture, universal audio import (`.wav`/`.mp3`/`.aac`/`.ogg`/`.flac` decoded to `.wav` on Android; `.wav` on Desktop), waveform + FFT spectrum visualizer (`WAVE`/`FFT`/`DUAL`), `IN`/`OUT` selection markers, zero-crossing snap, fades, reverse, DC fix, peak normalize, **EXTRACT**, **UNDO**, **RESET**, and loop-persistent audition.
3. **SAMPLES & LOOP REFINER (`SAMPLES`)**: 4-slot Looper (`1/2/4` bars, mute/active, bounce mixdown) + integrated **Loop Sample Refinement Editor** (start/end selection, zero-crossing snap, fade in/out, reverse, normalize, DC offset removal, pitch/speed tune, loop audition, commit to slot or send to Deck).
4. **INSTRUMENTS (`INST`)**: 16-Step Sequencer (`Sample` + synthesized `808 Kick`, `909 Snare`, `Hi-Hat`), BPM (`60–180`), Groove Swing, live trigger pads, and hardware MIDI bridging (`MidiManager` on Android, `javax.sound.midi` 4-bus interface on Linux Desktop).
5. **TRACKS (`TRACKS`)**: 4-track arrangement timeline with a **globally synced playhead** synchronized across Tracks, Sequencer, and Looper, plus clip nudging, track mute/solo, beat-ruler scrubbing, and master mixdown.
6. **FX RACK (`FX` / `FX_STOMP`)**: 12 pure-Kotlin mono/stereo DSP processors (`Noise Gate`, `Pitch & Speed`, `Gain & Normalize`, `Overdrive`, `Wah`, `Phaser`, `Flanger`, `Chorus`, `Rotary Speaker`, `Echo`, `BPM-Sync Delay`, `Reverb`) + quick presets (`Bypass`, `Clean Mic`, `Dub Siren`, `Acid Wah`, `Rotary Club`, `Lo-Fi Vinyl`, `Studio Master`).
7. **LIVE (`LIVE`)**: Die-cast **Mini Stompbox Master Multi-FX Processor** (`Dynamics Trim`, `DJ Tone Tilt EQ`, `Mod Scale`, `Space Scale`, `Master Wet Mix`, `Drive/Mod/Time Kill` switches, interactive `X/Y Live Pad`, `Tap Tempo`, `Stomp Bypass`), workflow profiles (`Stage DJ`, `Live Recording`, `Studio Release`), `.WAV`/`.MP3` export, and peer network configuration.
8. **CHROMATIC TUNER (`TUNER`)**: Pure-Kotlin YIN pitch detector (`32 Hz – 2200 Hz`) with arc/strobe gauge, `-50¢..+50¢` meter, configurable `A4` calibration (`430–450 Hz`), source switching (`DECK_BUFFER`, `LIVE_MIC`, `SMP2_STREAM`), instrument presets, and audible reference tone generator pads.
9. **64-CHANNEL VMC (`VMC`)**: Mappable **64-Channel Virtual MIDI Controller** (`4 Buses × 16 Channels = CH 1..64` plus `CH 0 = ANY`), MIDI Learn, virtual performance pads & CC faders, local/peer/hybrid routing modes, hardware MIDI bridging, and live `VMC2` control packets between phone and Linux desktop.
10. **NET LINK (`NET_LINK` on Desktop / Network Matrix in `LIVE` on Android)**: Configurable IP, TX Port, RX Bind Port, Sample Rate, Channels, Buffer Size, `TCP`/`UDP` mode, live `SMP2` RX capture into Deck, and `SMH2`/`SMHR` handoff status.

### Launching Linux Desktop Workstation
```bash
./scripts/launch-linux-studio.sh
# Or run via Gradle:
gradle :desktop:run
```

## Documentation Index
- [Architecture Overview](docs/ARCHITECTURE.md)
- [DSP, Stompbox & Real-Time Visualization Reference](docs/DSP_REFERENCE.md)
- [Network Streaming, VMC2, Session Handoff & Export Reference](docs/NETWORK_AND_EXPORT.md)
- [Build Stages & Verification Log](docs/STAGES_AND_CHANGELOG.md)
