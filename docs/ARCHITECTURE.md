# Samplfie2 Architecture

## Overview
Samplfie2 is organized as two **equal, customizable Hub-and-Spoke audio workstations** — Android (`:app`) in Kotlin + Jetpack Compose and Linux Desktop (`:desktop`) in Kotlin + Java2D/Swing — sharing wire-compatible DSP, YIN pitch analysis, loop-persistent playback engines, **64-Channel Virtual MIDI (`VMC2`)**, audio streaming (`SMP2`), and instant full-state **Session Handoff (`SMH2`/`SMHR`)**.

## Android Module Layout (`:app` — `com.example`)
- `ui/theme/` — Hardware DJ console theme (`ObsidianChassis`, `GunmetalDeck`, `NeonCyan`, `TacCoral`, `AmberHot`, `EmeraldSignal`, `VioletMod`, `MagentaFx`) and local TTF typography (`Space Grotesk`, `JetBrains Mono`, `Inter`).
- `audio/` —
  - `AudioBuffer`: Immutable interleaved float PCM buffer with selection extraction, linear fades, reverse, DC offset removal, peak normalization, zero-crossing detection, channel conversion, mono downmix, and Hermite resampling.
  - `WavCodec`: RIFF/WAVE PCM 16-bit reader/writer (`File`, `ByteArray`) + 16-bit LE byte decoders for streaming/handoff codecs.
  - `AudioImporter`: Runtime-permission-aware (`READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`) + SAF `OpenDocument` importer with 3-tier `MediaExtractor`/`MediaCodec` data source fallback (`ParcelFileDescriptor`, `Context+Uri`, temp cache file) decoding WAV, MP3, AAC/M4A, OGG, FLAC, and Opus into `.wav` takes.
  - `AudioAnalyzer`: Waveform envelope downsampler + Radix-2 Cooley-Tukey 32-band FFT analyzer.
  - `MicCaptureEngine` (`AudioRecord` + live band chunk forwarding) & `AudioPlaybackEngine` (`AudioTrack` streaming playback with master volume/mute scaling, `updateBufferHot(..., preserveLoopPlayback)` loop survival across edits/extractions, headless JVM test fallback, and live Tuner tap).
- `dsp/` — `FxModels`, `DspModules`, `DspSpatialModModules`, `DspChainEngine` (12-module DSP chain + Stompbox scaling/kill/tilt).
- `tuner/` — `PitchDetector` (pure-Kotlin YIN `32 Hz – 2200 Hz` fundamental pitch detector, presets, A4 reference calibration).
- `midi/` — `MidiControllerManager` (`android.media.midi.MidiManager`), `MidiMessageParser`, and `VmcEngine` (64-channel `4 × 16` mapper, MIDI Learn, virtual control emitter, `VMC2` RX/TX integration).
- `config/` — DataStore-backed `RigConfig` (including `masterVolume`, `isMasterMuted`, `myStyleName`, `knownPeersSpec`) & `RigConfigRepository`, plus AES-GCM `SecretsVaultRepository` (`DISBAND_API_KEY` + custom studio secrets, falling back to `BuildConfig.DISBAND_API_KEY`).
- `stems/` — `DisbandModels` (`DisbandStem`, `StemPreset`, `StemSeparationUiState`) & `DisbandApiClient` (`POST /separations`, `GET /separations/{id}` with cooperative cancellation, WAV stem download).
- `network/` — `CollabModels` (`CollabInstrumentStyle`, `CollabPeerDevice`, `LiveBandRecordingState`, `CollabSyncCommand`, `CollabWireCodec`, `MAX_COLLAB_PEERS = 4`), `SessionHandoffCodec` (`SMH2` snapshot serializer), and `NetworkAudioTransmitter` (`SMP2` + `VMC2` + `SMH2`/`SMHR` + `SMDP` discovery beacons + `SMCL` band audio + `SMRC` synced rec commands).
- `export/` — `WavExporter` & `Mp3Exporter` (`de.sciss:jump3r` -> `Music/Samplfie2/`).
- `studio/` — `StudioEngine` & `DrumKitSynthesizer` (4-slot Looper, 16-Step Sequencer, 4-Track Timeline, master volume scaling, globally synced playhead).
- `ui/` — `SamplfieUiState` (`StudioSpoke` 9 entries + `WorkspaceConfig` customizer + `TunerUiState`), `SamplfieViewModel`, `components/` (`CircularStudioHub`, `MasterProductionHub` with Master Volume/Mute + Collab badge, `CollabBandComponents`, `SecretsAndStemsComponents`, `StompboxMiniProcessor`, `AudioVisualizerPanel`, `TacHoldPad`, `HardwareChrome`), `screens/` (`DeckEditorScreen`, `SamplesSpokeScreen`, `InstrumentsSpokeScreen`, `TracksSpokeScreen`, `FxRackScreen`, `FxRackSpatialCards`, `LiveSpokeScreen`, `TunerSpokeScreen`, `VmcSpokeScreen`), and `MainActivity`.

## Linux Desktop Module Layout (`:desktop` — `com.samplfie.desktop`)
- `model/` — `DesktopModels` (10 `DesktopStation` entries matching Android spokes + Net Link, `DesktopLiveMode`, `DesktopWorkspaceConfig`, `DesktopAudioBuffer`, `DesktopFxState`, `DesktopStompState`, `DesktopRigConfig`).
- `audio/` — `DesktopWavCodec`, `DesktopMp3Exporter` (`jump3r`), `SignalAnalyzer` (envelope peaks, 48-band Radix-2 FFT, calibrated generator), `LinuxSoundEngine` (`javax.sound.sampled` ALSA/PulseAudio/PipeWire hot-swap loop-persistent playback, master volume scaling, mic capture with live band TX tap, one-shot pad audition, and live tuner feed).
- `dsp/` — `DesktopDspProcessor` (matching 12-module DSP chain + Stompbox scaling/kill/tilt).
- `studio/` — `DesktopArrangementEngine` (`DesktopDrumKit`, 4-slot Looper + Loop Sample Refiner, 16-Step Sequencer, 4-Track Timeline, master volume scaling + `DesktopSyncedPlayheadState`).
- `tuner/` — `PitchDetector` (YIN `32 Hz – 2200 Hz` + strobe/cents computation).
- `midi/` — `DesktopVmcEngine` (64-channel VMC + MIDI Learn + 32 mapped studio targets) & `DesktopHardwareMidiManager` (`javax.sound.midi` 4-bus auto-binder).
- `stems/` — `DesktopSecretsVault` (POSIX `0600` `~/.config/samplfie2/secrets.properties` + env fallback) & `DesktopDisbandClient` (Disband stem separation API client with cooperative cancellation).
- `network/` — `CollabModels` (`CollabInstrumentStyle`, `CollabPeerDevice`, `LiveBandRecordingState`, `CollabWireCodec`, `MAX_COLLAB_PEERS = 4`), `SessionHandoffCodec` (`SMH2`), & `Smp2NetworkNode` (`SMP2` + `VMC2` + `SMH2`/`SMHR` + `SMDP`/`SMCL`/`SMRC` TCP/UDP listener & sender).
- `ui/` — `StudioTheme`, `CircularHubSidebar` (Master Volume/Mute controls, Band Link & Band Rec triggers, customizable 8-spoke radial wheel + transport + VU meter + `PUSH`/`GRAB` peer buttons), `DesktopCollabUi` (`DesktopBandCollabStrip`, `DesktopCollabDialog`), `ProductionBoothPanel`, `DesktopSecretsAndStemsUi`, `SignalEditorPanel`, `SamplesLooperPanel`, `InstrumentsSeqPanel`, `TracksTimelinePanel`, `FxAndStompPanel`, `LiveStagePanel`, `ChromaticTunerPanel`, `VmcMidiPanel`, `NetLinkPanel`, and `SamplfieDesktopFrame` (`Main.kt`).
