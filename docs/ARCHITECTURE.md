# Samplfie2 Architecture

## Overview
Samplfie2 is organized around a **Circular Production Booth Hub-and-Spoke** model in Jetpack Compose (`StudioSpoke`), backed by pure-Kotlin floating-point audio DSP, `AudioRecord`/`AudioTrack`, `MediaExtractor`/`MediaCodec` audio import, `StudioEngine` synchronized playback clocks, `MidiManager`, `Socket`/`DatagramSocket` streaming, and local `.WAV` / `.MP3` (`jump3r`) export.

## Module & Package Layout (`com.example`)
- `ui/theme/` — Hardware DJ console theme (`ObsidianChassis`, `GunmetalDeck`, `NeonCyan`, `TacCoral`, `AmberHot`, `EmeraldSignal`, `VioletMod`, `MagentaFx`) and local TTF typography (`Space Grotesk`, `JetBrains Mono`, `Inter`).
- `audio/` —
  - `AudioBuffer`: Immutable interleaved float PCM buffer with selection extraction, linear fades, reverse, DC offset removal, peak normalization, zero-crossing detection, channel conversion, and Hermite resampling.
  - `WavCodec`: RIFF/WAVE PCM 16-bit reader/writer (`File` & `ByteArray`).
  - `AudioImporter`: Zero-permission SAF `OpenDocument` importer decoding WAV, MP3, AAC/M4A, OGG, FLAC, and Opus via `MediaExtractor`/`MediaCodec` and persisting `.wav` takes to internal storage for immediate Deck editing.
  - `AudioAnalyzer`: Waveform envelope downsampler + Radix-2 Cooley-Tukey 32-band FFT analyzer.
  - `MicCaptureEngine` (`AudioRecord`) & `AudioPlaybackEngine` (`AudioTrack` streaming playback + scrubbing + loop).
- `dsp/` —
  - `FxModels`: Expanded parameter models for all 12 FX plus `StompboxMasterState` and `LiveSessionMode` (`STAGE_DJ`, `LIVE_REC`, `STUDIO_RELEASE`).
  - `DspModules` & `DspSpatialModModules`: All 12 pure-Kotlin mono/stereo DSP processors + Master DJ Tilt EQ.
  - `DspChainEngine`: Non-destructive signal chain + Stompbox master scaling, kill switches, and wet/dry blending.
- `config/` — DataStore-backed `RigConfig` & `RigConfigRepository` (IP Address, Port, Protocol TCP/UDP, Sample Rate, Channels, Buffer Size, MP3 Bitrate).
- `network/` — `NetworkAudioTransmitter` for real TCP (`Socket`) & UDP (`DatagramSocket`) `SMP2`-framed PCM packet streaming.
- `export/` — `WavExporter` (`.wav` export) & `Mp3Exporter` (`de.sciss:jump3r` pure-Java LAME `.mp3` encoder) saving to `Music/Samplfie2/` via `MediaStore` (with app-external fallback).
- `midi/` — `MidiControllerManager` & `MidiMessageParser` (`android.media.midi.MidiManager` Note/CC/PitchBend/Clock integration).
- `studio/` — `StudioEngine` & `DrumKitSynthesizer` (4-slot Looper, 16-Step Sequencer, 4-Track Timeline, and `SyncedPlayheadState` synchronizing timeline, sequencer, and looper clocks).
- `ui/` — `SamplfieUiState`, `SamplfieViewModel`, `components/` (`MasterProductionHub` providing persistent on-screen `DockedCircularProductionHub` on portrait stations and `WideCircularProductionSidebar` on wide/tablet screens, `CircularStudioHub` / `CircularStudioWheel`, `StompboxMiniProcessor`, `HardwareChrome`, `AudioVisualizerPanel`, `TacHoldPad`), `screens/` (`DeckEditorScreen`, `SamplesSpokeScreen`, `InstrumentsSpokeScreen`, `TracksSpokeScreen`, `FxRackScreen`, `FxRackSpatialCards`, `LiveSpokeScreen`), and adaptive `MainActivity`.

## Permissions (Android)
- `RECORD_AUDIO`: Runtime permission requested only when arming or holding the TAC mic capture pad.
- `INTERNET`, `ACCESS_NETWORK_STATE`: Install-time permissions for TCP/UDP network streaming.
- `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"`): Only for legacy pre-Android 10 devices; Android 10+ uses zero-permission `MediaStore.Audio.Media` and SAF `OpenDocument` requires zero storage permissions.

## Desktop Companion Module Layout (`:desktop` — `com.samplfie.desktop`)
- `model/` — `DesktopModels` (`DesktopStation`, `DesktopAudioBuffer`, `DesktopFxState`, `DesktopStompState`, `DesktopPresetFactory`, `TunerReading`, `TuningPreset`, `DesktopRigConfig`).
- `audio/` — `DesktopWavCodec`, `DesktopMp3Exporter` (`jump3r`), `SignalAnalyzer` (envelope peaks, 48-band Radix-2 FFT, calibrated test signal generator), `LinuxSoundEngine` (`javax.sound.sampled` ALSA/PulseAudio/PipeWire playback, mic capture, live tuner feeding, and reference pitch synthesis with headless fallback).
- `tuner/` — `PitchDetector` (YIN fundamental pitch detection across `32 Hz – 2200 Hz` with parabolic interpolation and configurable `A4` calibration).
- `dsp/` — `DesktopDspProcessor` (matching 12-module DSP chain + Stompbox master scaling/kill/tilt processing).
- `network/` — `Smp2NetworkNode` (bidirectional `SMP2` 20-byte header TCP `ServerSocket`/`Socket` and UDP `DatagramSocket` listener + transmitter).
- `ui/` — `StudioTheme` (matching Samplfie2 Android palette), `CircularHubSidebar` (persistent 360° Java2D wheel + transport + VU meter), `SignalEditorPanel`, `ChromaticTunerPanel`, `FxAndStompPanel`, `NetLinkPanel`, and `SamplfieDesktopFrame` (`Main.kt`).
- `scripts/` — `launch-linux-studio.sh` & `samplfie2-desktop.desktop` Linux launcher and desktop entry.
