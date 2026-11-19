# Samplfie2 Architecture

## Overview
Samplfie2 is a Kotlin + Jetpack Compose live DJ performance workstation featuring press-and-hold TAC mic capture, real-time waveform & FFT spectrum visualization, selection extraction, 12 audio DSP FX modules, a 4-slot Looper, 16-step Sequencer, 4-track Timeline with scrubbing Playhead, Android MIDI hardware support, TCP/UDP streaming, and on-device MP3 export.

## Module & Package Layout (`com.example`)
- `ui/theme/` — Live DJ hardware console theme (`ObsidianChassis`, `GunmetalDeck`, `NeonCyan`, `TacCoral`, `AmberHot`, `EmeraldSignal`) and bundled TTF typography (`Space Grotesk`, `JetBrains Mono`, `Inter`).
- `audio/` *(Stage 2)* — `AudioBuffer`, `WavCodec` (RIFF/PCM read & write), `AudioAnalyzer` (Waveform peaks + Radix-2 Cooley-Tukey FFT), `MicCaptureEngine` (`AudioRecord`), and `AudioPlaybackEngine` (`AudioTrack` streaming playback + scrubbing + loop).
- `dsp/` *(Stage 3)* — Non-destructive `DspChainEngine`, `FxModels`, `FxPresets`, and all 12 DSP processors (`DspModules`: Noise Removal, Pitch & Speed, Gain, Overdrive, Wah, Phaser, Flanger, Chorus, Rotary Speaker, Echo, Delay, Reverb).
- `config/` *(Stage 4)* — DataStore-backed `RigConfig` & `RigConfigRepository` (IP Address, Port, Protocol TCP/UDP, Sample Rate, Channels, Buffer Size, MP3 Bitrate).
- `network/` *(Stage 4)* — `NetworkAudioTransmitter` for real TCP (`Socket`) & UDP (`DatagramSocket`) `SMP2`-framed PCM packet streaming.
- `export/` *(Stage 4)* — `Mp3Exporter` powered by `de.sciss:jump3r` (pure-Java LAME MP3 encoder) + `MediaStore.Audio.Media` `Music/Samplfie2/` export.
- `midi/` *(Stage 4)* — `MidiControllerManager` & `MidiMessageParser` (`android.media.midi.MidiManager` Note/CC/PitchBend/Clock integration).
- `studio/` *(Stage 4)* — `StudioEngine` & `DrumKitSynthesizer` (4-slot Looper, 16-Step Sequencer, 4-Track Timeline with Playhead & Mixdown).
- `ui/` *(Stage 5)* — `SamplfieViewModel`, `components/` (`HardwareChrome`, `AudioVisualizerPanel`, `TacHoldPad`), `screens/` (`DeckEditorScreen`, `FxRackScreen`, `StudioPerformanceScreen`, `RigLinkMidiScreen`), and adaptive `MainActivity`.

## Permissions
- `RECORD_AUDIO`: Runtime permission requested gracefully on TAC hold or mic arming.
- `INTERNET`, `ACCESS_NETWORK_STATE`: Install-time permissions for TCP/UDP network streaming.
- `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"`): Only for legacy pre-Android 10 devices; Android 10+ uses zero-permission `MediaStore.Audio.Media`.
