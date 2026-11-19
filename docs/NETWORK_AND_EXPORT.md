# Samplfie2 Network Link & MP3 Export Reference

## Rig & Network Link Configuration (`RigConfig`)
Persisted via Jetpack `DataStore` Preferences (`samplfie2_rig_prefs`):
- **IP Address**: Target host IPv4 or hostname (default `192.168.1.100`).
- **Port**: `1..65535` (default `9000`).
- **Protocol**: `TCP` (`java.net.Socket`) or `UDP` (`java.net.DatagramSocket`).
- **Sample Rate**: `22050`, `44100`, `48000`, `96000` Hz (default `44100`).
- **Channels**: `1` (Mono) or `2` (Stereo) (default `1`).
- **Buffer Size**: `256`, `512`, `1024`, `2048`, `4096` samples per frame packet (default `1024`).

### Wire Packet Format (`AudioPacketFrame`)
Each transmitted frame carries a compact 20-byte header followed by 16-bit little-endian PCM payload:
- Magic bytes: `0x53 0x4D 0x50 0x32` (`"SMP2"`)
- Sequence number (`UInt32` LE)
- Sample rate (`UInt32` LE)
- Channels (`UInt16` LE)
- Payload byte count (`UInt16` LE)
- Flags / Peak level (`UInt32` LE)
- PCM 16-bit LE samples (`bufferSize * channels * 2` bytes)

## MP3 Device Export (`Mp3Exporter`)
- Encodes the active (or selected) `AudioBuffer` to a standard MPEG-1 Layer III (`.mp3`) file using pure-Java LAME (`de.sciss:jump3r:1.0.5`).
- Supports `128`, `192`, `256`, and `320` kbps CBR encoding.
- Saves cleanly to `Music/Samplfie2/` using `MediaStore.Audio.Media` (`RELATIVETIME` / `RELATIVE_PATH`) on Android 10+ (API 29+) with zero storage permission prompts, and falls back to `Environment.getExternalStoragePublicDirectory(DIRECTORY_MUSIC)/Samplfie2` on API 24–28.
