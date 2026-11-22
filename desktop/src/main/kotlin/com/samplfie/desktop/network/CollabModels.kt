package com.samplfie.desktop.network

/**
 * Maximum simultaneous remote collaborator devices supported in a Live Band session.
 */
const val MAX_COLLAB_PEERS = 4
const val COLLAB_DISCOVERY_PORT = 9099

/**
 * Musical role / instrument style chosen by each collaborator device.
 * Mirrors Android's CollabInstrumentStyle 1:1 on the wire.
 */
enum class CollabInstrumentStyle(
    val label: String,
    val shortTag: String,
    val defaultTrack: Int
) {
    VOCALS("Vocals", "VOX", 0),
    GUITAR("Guitar", "GTR", 1),
    BASS("Bass", "BAS", 2),
    DRUMS("Drums", "DRM", 3),
    KEYS_SYNTH("Keys / Synth", "KEY", 1),
    DJ_FX("DJ / Stomp", "DJ", 3);

    companion object {
        fun fromOrdinalOrName(ordinal: Int?, name: String?): CollabInstrumentStyle {
            if (!name.isNullOrBlank()) {
                entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.let { return it }
            }
            if (ordinal != null && ordinal in entries.indices) return entries[ordinal]
            return VOCALS
        }
    }
}

data class CollabPeerDevice(
    val peerId: String,
    val name: String,
    val platform: String = "PEER",
    val host: String,
    val listenPort: Int,
    val style: CollabInstrumentStyle = CollabInstrumentStyle.VOCALS,
    val trackSlot: Int = style.defaultTrack,
    val isConnected: Boolean = false,
    val isNearby: Boolean = false,
    val isKnown: Boolean = false,
    val isRecording: Boolean = false,
    val livePeak: Float = 0f,
    val lastSeenMs: Long = System.currentTimeMillis(),
    val rxPackets: Long = 0L
) {
    val endpoint: String get() = "$host:$listenPort"
}

data class LiveBandRecordingState(
    val isRecording: Boolean = false,
    val startedAtMs: Long = 0L,
    val elapsedMs: Long = 0L,
    val activeTakeName: String = "",
    val myStyle: CollabInstrumentStyle = CollabInstrumentStyle.GUITAR,
    val myTrackSlot: Int = 1,
    val lastRecordedSummary: String = ""
)

data class CollabSyncCommand(
    val start: Boolean,
    val bpm: Float,
    val senderPeerId: String,
    val senderName: String,
    val senderStyle: CollabInstrumentStyle,
    val senderTrackSlot: Int,
    val takeName: String,
    val senderListenPort: Int
)

object CollabWireCodec {
    fun encodeBeaconPayload(
        nodeId: String,
        name: String,
        platform: String,
        listenPort: Int,
        style: CollabInstrumentStyle,
        trackSlot: Int,
        isRecording: Boolean
    ): ByteArray {
        val cleanName = name.replace("|", " ").replace(";", " ").replace("\n", " ").take(32)
        val str = "id=$nodeId;name=$cleanName;plat=$platform;port=$listenPort;style=${style.name};track=${trackSlot.coerceIn(0, 3)};rec=$isRecording"
        return str.toByteArray(Charsets.UTF_8)
    }

    fun decodeBeaconPayload(bytes: ByteArray): Map<String, String> {
        val raw = String(bytes, Charsets.UTF_8)
        val map = LinkedHashMap<String, String>()
        raw.split(';').forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) map[part.substring(0, eq)] = part.substring(eq + 1)
        }
        return map
    }

    fun serializeKnownPeers(peers: List<CollabPeerDevice>): String {
        return peers.filter { it.isKnown || it.isConnected }.take(12).joinToString(";") { p ->
            val cleanName = p.name.replace("|", " ").replace(";", " ").take(28)
            "${p.host}|${p.listenPort}|$cleanName|${p.platform}|${p.style.name}|${p.trackSlot}"
        }
    }

    fun parseKnownPeers(spec: String): List<CollabPeerDevice> {
        if (spec.isBlank()) return emptyList()
        return spec.split(';').mapNotNull { entry ->
            val parts = entry.split('|')
            if (parts.size < 2) return@mapNotNull null
            val host = parts[0].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val port = parts[1].toIntOrNull()?.coerceIn(1, 65535) ?: return@mapNotNull null
            val name = parts.getOrNull(2)?.ifBlank { "Studio Peer" } ?: "Studio Peer"
            val plat = parts.getOrNull(3)?.ifBlank { "PEER" } ?: "PEER"
            val style = CollabInstrumentStyle.fromOrdinalOrName(null, parts.getOrNull(4))
            val track = parts.getOrNull(5)?.toIntOrNull()?.coerceIn(0, 3) ?: style.defaultTrack
            CollabPeerDevice(
                peerId = "$host:$port",
                name = name,
                platform = plat,
                host = host,
                listenPort = port,
                style = style,
                trackSlot = track,
                isConnected = false,
                isNearby = false,
                isKnown = true
            )
        }
    }
}
