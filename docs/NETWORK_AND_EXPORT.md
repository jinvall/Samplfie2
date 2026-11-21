# Samplfie2 Import, Network Streaming & Local WAV/MP3 Export

## Universal Audio Import (`AudioImporter`)
- Uses the zero-permission Android SAF `ActivityResultContracts.OpenDocument(arrayOf("audio/*"))` picker.
- Supports RIFF `.wav` directly and decodes `.mp3`, `.aac`/`.m4a`, `.ogg`, `.flac`, `.opus`, and other device-supported audio formats via `MediaExtractor` + `MediaCodec`.
- Resamples to the active rig sample rate, writes an editable `.wav` take into the session directory, and opens directly in the **DECK** spoke.

## Rig & Network Link Configuration (`RigConfig`)
Persisted via Jetpack `DataStore` Preferences (`samplfie2_rig_prefs`):
- **IP Address**: Target host IPv4 or hostname (default `192.168.1.100`).
- **Port**: `1..65535` (default `9000`).
- **Protocol**: `TCP` (`java.net.Socket`) or `UDP` (`java.net.DatagramSocket`).
- **Sample Rate**: `22050`, `44100`, `48000`, `96000` Hz (default `44100`).
- **Channels**: `1` (Mono) or `2` (Stereo) (default `1`).
- **Buffer Size**: `256`, `512`, `1024`, `2048`, `4096` frames per packet (default `1024`).

### Wire Packet Format (`SMP2`)
20-byte big-endian/LE framed header followed by 16-bit LE PCM payload:
- Magic: `"SMP2"` (`0x53 0x4D 0x50 0x32`)
- Sequence number (`UInt32` LE), Sample rate (`UInt32` LE), Channels (`UInt16` LE), Payload bytes (`UInt16` LE), Peak flags (`UInt32` LE), and PCM frames.

## Local Device Export (`WavExporter` & `Mp3Exporter`)
- **`.WAV` Export**: Writes standard RIFF PCM 16-bit `.wav` files via `WavExporter.saveWavToDevice`.
- **`.MP3` Export**: Encodes MPEG-1 Layer III (`128–320` kbps CBR) via pure-Java LAME (`de.sciss:jump3r:1.0.5`).
- Saves into `Music/Samplfie2/` using `MediaStore.Audio.Media` on Android 10+ (API 29+) with automatic app-external `Music/Samplfie2/` fallback on legacy or headless environments.

## Desktop Linux Bridge & Export (`:desktop`)
- **`Smp2NetworkNode`**: Listens on any configured TCP (`ServerSocket`) or UDP (`DatagramSocket`) port to receive `SMP2` frames streamed from the Android **LIVE** spoke, route incoming frames directly to the **Chromatic Tuner** (`SMP2_STREAM`), and capture received frames directly into the **Signal Editor** buffer—or transmit processed desktop audio back to Android or another studio host.
- **Desktop Export**: Uses `DesktopWavCodec` and `DesktopMp3Exporter` (`de.sciss:jump3r`) with Swing file choosers for direct `.wav` / `.mp3` read/write on Linux filesystems.
