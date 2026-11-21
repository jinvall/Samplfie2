# Samplfie2 Import, Network Bridge (`SMP2`/`VMC2`/`SMH2`) & Local Export

## Universal Audio Import
- **Android (`AudioImporter`)**: Zero-permission SAF `ActivityResultContracts.OpenDocument(arrayOf("audio/*"))` picker decoding `.wav`, `.mp3`, `.aac`/`.m4a`, `.ogg`, `.flac`, `.opus` via `MediaExtractor` + `MediaCodec`, resampling to active rig rate, and saving an editable `.wav` take into the session directory.
- **Linux Desktop (`DesktopWavCodec`)**: Native file chooser importing RIFF `.wav` files cleanly into the Signal Editor Deck with zero-crossing snap, fades, reverse, DC fix, normalize, and selection extract.

## Rig & Peer Network Configuration
- **IP / Host**, **TX Port** (default `9000` on Android, `9001` on Desktop or user-set), **RX Bind Port** (default `9001` on Android, `9000` on Desktop), **Node Name**, **Protocol** (`TCP` or `UDP`), **Sample Rate** (`22050–96000 Hz`), **Channels** (`1`/`2`), **Buffer Size** (`256–4096`), **MP3 Bitrate** (`128–320 kbps`), **Auto-Accept Handoff**, and **Auto-Resume Playback**.

## Multiplexed Wire Protocols (20-Byte LE Frame Headers)
All 4 protocols multiplex seamlessly on the same TCP `ServerSocket` or UDP `DatagramSocket` port:
1. **`SMP2`** (`AudioStream`): 20-byte header (`magic="SMP2"`, `seq`, `sampleRate`, `channels`, `payloadBytes`, `peakScaled`) + 16-bit LE PCM chunks for live peer playback/capture/tuning.
2. **`VMC2`** (`Virtual MIDI Companion`): 20-byte header (`magic="VMC2"`, `channel64` `1..64`, `msgType` code `0..5`, `data1` CC/Note `0..127`, `data2` velocity/value `0..127`, `normalizedBits` IEEE-754 float) enabling either workstation to act as a live 64-channel MIDI controller for the other.
3. **`SMH2`** (`Session Handoff Snapshot`): Chunked frames (`sessionId`, `chunkIdx`, `totalChunks`, `totalSize`, `chunkLen`) carrying UTF-8 session metadata (`senderName`, `stationKey`, `bpm`, `swing`, `isPlaying`, `isLooping`, `playheadFraction`, `selectionStart/End`, `playSelectionOnly`, `a4ReferenceHz`, all 12 `fx.*` & `stomp.*` states, `seqLaneMasks`, `looperActiveMask`, `looperBars`, 64-ch `vmcMappings`) + full 16-bit PCM Deck `.wav` audio.
4. **`SMHR`** (`Session Handoff Request`): 20-byte request carrying requester `replyPort` so pressing `GRAB <- PEER` pulls an immediate `SMH2` snapshot back from the remote workstation.

## Local Device Export (`.WAV` & `.MP3`)
- Writes `.WAV` (RIFF PCM 16-bit) and `.MP3` (MPEG-1 Layer III via `de.sciss:jump3r:1.0.5`) to `Music/Samplfie2/` via `MediaStore` on Android 10+ (or app-external fallback) and `~/Music/Samplfie2/` on Linux Desktop.
