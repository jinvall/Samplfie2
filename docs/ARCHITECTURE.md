# Samplfie2 Architecture

## Overview
Samplfie2 is organized as two **equal, customizable Hub-and-Spoke audio workstations** — Android (`:app`) in Kotlin + Jetpack Compose and Linux Desktop (`:desktop`) in Kotlin + Java2D/Swing — sharing wire-compatible DSP, YIN pitch analysis, loop-persistent playback engines, **64-Channel Virtual MIDI (`VMC2`)**, audio streaming (`SMP2`), and instant full-state **Session Handoff (`SMH2`/`SMHR`)**.

## Android Module Layout (`:app` — `com.example`)
- `ui/theme/` — Hardware DJ console theme (`ObsidianChassis`, `GunmetalDeck`, `NeonCyan`, `TacCoral`, `AmberHot`, `EmeraldSignal`, `VioletMod`, `MagentaFx`) and local TTF typography (`Space Grotesk`, `JetBrains Mono`, `Inter`).
- `audio/` —
  - `AudioBuffer`: Immutable interleaved float PCM buffer with selection extraction, linear fades, reverse, DC offset removal, peak normalization, zero-crossing detection, channel conversion, mono downmix, and Hermite resampling.
  - `WavCodec`: RIFF/WAVE PCM 16-bit reader/writer (`File`, `ByteArray`) + 16-bit LE byte decoders for streaming/handoff codecs.
  - `AudioImporter`: Zero-permission SAF `OpenDocument` importer decoding WAV, MP3, AAC/M4A, OGG, FLAC, and Opus via `MediaExtractor`/`MediaCodec` into `.wav` takes.
  - `AudioAnalyzer`: Waveform envelope downsampler + Radix-2 Cooley-Tukey 32-band FFT analyzer.
  - `MicCaptureEngine` (`AudioRecord`) & `AudioPlaybackEngine` (`AudioTrack` streaming playback with `updateBufferHot(..., preserveLoopPlayback)` loop survival across edits/extractions and headless JVM test fallback + live Tuner tap).
- `dsp/` — `FxModels`, `DspModules`, `DspSpatialModModules`, `DspChainEngine` (12-module DSP chain + Stompbox scaling/kill/tilt).
- `tuner/` — `PitchDetector` (pure-Kotlin YIN `32 Hz – 2200 Hz` fundamental pitch detector, presets, A4 reference calibration).
- `midi/` — `MidiControllerManager` (`android.media.midi.MidiManager`), `MidiMessageParser`, and `VmcEngine` (64-channel `4 × 16` mapper, MIDI Learn, virtual control emitter, `VMC2` RX/TX integration).
- `config/` — DataStore-backed `RigConfig` & `RigConfigRepository` plus AES-GCM `SecretsVaultRepository` (`DISBAND_API_KEY` + custom studio secrets, falling back to `BuildConfig.DISBAND_API_KEY`).
- `stems/` — `DisbandModels` (`DisbandStem`, `StemPreset`, `StemSeparationUiState`) & `DisbandApiClient` (`POST /separations`, `GET /separations/{id}`, WAV stem download).
- `network/` — `SessionHandoffCodec` (`SMH2` snapshot serializer) and `NetworkAudioTransmitter` (`SMP2` + `VMC2` + chunked `SMH2` + `SMHR` TCP/UDP multiplexer).
- `export/` — `WavExporter` & `Mp3Exporter` (`de.sciss:jump3r` -> `Music/Samplfie2/`).
- `studio/` — `StudioEngine` & `DrumKitSynthesizer` (4-slot Looper, 16-Step Sequencer, 4-Track Timeline, globally synced playhead).
- `ui/` — `SamplfieUiState` (`StudioSpoke` 9 entries + `WorkspaceConfig` customizer + `TunerUiState`), `SamplfieViewModel`, `components/` (`CircularStudioHub`, `MasterProductionHub`, `SecretsAndStemsComponents`, `StompboxMiniProcessor`, `AudioVisualizerPanel`, `TacHoldPad`, `HardwareChrome`), `screens/` (`DeckEditorScreen`, `SamplesSpokeScreen`, `InstrumentsSpokeScreen`, `TracksSpokeScreen`, `FxRackScreen`, `FxRackSpatialCards`, `LiveSpokeScreen`, `TunerSpokeScreen`, `VmcSpokeScreen`), and `MainActivity`.

## Linux Desktop Module Layout (`:desktop` — `com.samplfie.desktop`)
- `model/` — `DesktopModels` (10 `DesktopStation` entries matching Android spokes + Net Link, `DesktopLiveMode`, `DesktopWorkspaceConfig`, `DesktopAudioBuffer`, `DesktopFxState`, `DesktopStompState`, `DesktopRigConfig`).
- `audio/` — `DesktopWavCodec`, `DesktopMp3Exporter` (`jump3r`), `SignalAnalyzer` (envelope peaks, 48-band Radix-2 FFT, calibrated generator), `LinuxSoundEngine` (`javax.sound.sampled` ALSA/PulseAudio/PipeWire hot-swap loop-persistent playback, mic capture, one-shot pad audition, and live tuner feed).
- `dsp/` — `DesktopDspProcessor` (matching 12-module DSP chain + Stompbox scaling/kill/tilt).
- `studio/` — `DesktopArrangementEngine` (`DesktopDrumKit`, 4-slot Looper + Loop Sample Refiner, 16-Step Sequencer, 4-Track Timeline + `DesktopSyncedPlayheadState`).
- `tuner/` — `PitchDetector` (YIN `32 Hz – 2200 Hz` + strobe/cents computation).
- `midi/` — `DesktopVmcEngine` (64-channel VMC + MIDI Learn + 32 mapped studio targets) & `DesktopHardwareMidiManager` (`javax.sound.midi` 4-bus auto-binder).
- `stems/` — `DesktopSecretsVault` (POSIX `0600` `~/.config/samplfie2/secrets.properties` + env fallback) & `DesktopDisbandClient` (Disband stem separation API client).
- `network/` — `SessionHandoffCodec` (`SMH2`) & `Smp2NetworkNode` (`SMP2` + `VMC2` + `SMH2` + `SMHR` TCP/UDP listener & sender).
- `ui/` — `StudioTheme`, `CircularHubSidebar` (customizable 8-spoke radial wheel + transport + VU meter + `PUSH`/`GRAB` peer buttons), `ProductionBoothPanel`, `DesktopSecretsAndStemsUi`, `SignalEditorPanel`, `SamplesLooperPanel`, `InstrumentsSeqPanel`, `TracksTimelinePanel`, `FxAndStompPanel`, `LiveStagePanel`, `ChromaticTunerPanel`, `VmcMidiPanel`, `NetLinkPanel`, and `SamplfieDesktopFrame` (`Main.kt`).
