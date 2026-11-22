package com.example.network

import com.example.audio.AudioBuffer
import com.example.audio.WavCodec
import com.example.config.NetworkProtocol
import com.example.config.RigConfig
import com.example.midi.VmcEventPacket
import com.example.midi.VmcMsgType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NetworkLinkState(
    val isTransmitting: Boolean = false,
    val isListening: Boolean = false,
    val isDiscovering: Boolean = false,
    val packetsSent: Long = 0L,
    val bytesSent: Long = 0L,
    val rxPackets: Long = 0L,
    val rxBytes: Long = 0L,
    val rxSampleRate: Int = 44100,
    val rxChannels: Int = 1,
    val vmcRxCount: Long = 0L,
    val vmcTxCount: Long = 0L,
    val connectedPeerCount: Int = 0,
    val lastHandoffSender: String? = null,
    val activeProtocol: NetworkProtocol? = null,
    val targetEndpoint: String = "",
    val statusMessage: String = "IDLE",
    val errorMessage: String? = null
)

data class AudioPacketHeader(
    val magic: String,
    val sequence: Int,
    val sampleRate: Int,
    val channels: Int,
    val payloadBytes: Int,
    val peakScaled: Int
)

/**
 * Bidirectional Equal-Peer Network Bridge (TCP & UDP) for Samplfie2 Android & Desktop.
 * Supports up to 4 simultaneous collaborators with nearby discovery (SMDP),
 * tagged multi-peer live band audio (SMCL), synchronized recording control (SMRC),
 * 64-Channel VMC2 MIDI, SMH2/SMHR Session Handoff, and SMP2 streaming.
 */
class NetworkAudioTransmitter(
    private val onStreamChunkForTuner: (FloatArray, Int) -> Unit = { _, _ -> },
    private val onVmcEventReceived: (VmcEventPacket) -> Unit = {},
    private val onSessionHandoffReceived: (SessionHandoffSnapshot) -> Unit = {},
    private val onHandoffRequestedByPeer: (senderHost: String, replyPort: Int) -> Unit = { _, _ -> },
    private val onCollabSyncCommandReceived: (CollabSyncCommand) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var txJob: Job? = null
    private var listenJob: Job? = null
    private var beaconRxJob: Job? = null
    private var beaconTxJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var udpRxSocket: DatagramSocket? = null
    private var beaconRxSocket: DatagramSocket? = null

    val localNodeId: String = "droid_${UUID.randomUUID().toString().take(8)}"

    private val pcmAccumulator = ByteArrayOutputStream()
    private val accumLock = Any()
    private val handoffSeq = AtomicInteger(1)

    private class CollabStreamAccum(val sampleRate: Int, val channels: Int) {
        val stream = ByteArrayOutputStream()
    }
    private val collabAccumulators = ConcurrentHashMap<String, CollabStreamAccum>()

    @Volatile
    private var localPeerName: String = "ANDROID RIG"

    @Volatile
    private var localListenPort: Int = 9001

    @Volatile
    private var localStyle: CollabInstrumentStyle = CollabInstrumentStyle.VOCALS

    @Volatile
    private var localTrackSlot: Int = 0

    @Volatile
    private var localIsRecording: Boolean = false

    private val _collabPeers = MutableStateFlow<List<CollabPeerDevice>>(emptyList())
    val collabPeers: StateFlow<List<CollabPeerDevice>> = _collabPeers.asStateFlow()

    private class HandoffReassembly(val totalChunks: Int, val totalBytes: Int) {
        val chunks = arrayOfNulls<ByteArray>(totalChunks)
        var receivedCount = 0
    }
    private val reassemblyMap = ConcurrentHashMap<Int, HandoffReassembly>()

    private val _state = MutableStateFlow(NetworkLinkState())
    val state: StateFlow<NetworkLinkState> = _state.asStateFlow()

    fun startListening(protocol: NetworkProtocol, bindPort: Int) {
        stopListening()
        synchronized(accumLock) { pcmAccumulator.reset() }
        reassemblyMap.clear()
        _state.value = _state.value.copy(
            isListening = true,
            rxPackets = 0L,
            rxBytes = 0L,
            activeProtocol = protocol,
            statusMessage = "LISTENING ${protocol.label} :$bindPort (SMP2/VMC2/HANDOFF)",
            errorMessage = null
        )

        listenJob = scope.launch {
            try {
                when (protocol) {
                    NetworkProtocol.UDP -> listenUdp(bindPort)
                    NetworkProtocol.TCP -> listenTcp(bindPort)
                }
            } catch (e: Exception) {
                if (isActive) {
                    _state.value = _state.value.copy(
                        isListening = false,
                        statusMessage = "RX STOPPED",
                        errorMessage = e.message
                    )
                }
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        runCatching { serverSocket?.close() }
        runCatching { udpRxSocket?.close() }
        serverSocket = null
        udpRxSocket = null
        _state.value = _state.value.copy(isListening = false)
    }

    private fun listenUdp(port: Int) {
        val sock = DatagramSocket(port).also { udpRxSocket = it }
        val buf = ByteArray(32768)
        sock.use { s ->
            while (listenJob?.isActive == true && !s.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                if (pkt.length >= HEADER_SIZE) {
                    val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                    processIncomingDatagram(data, pkt.address.hostAddress ?: "127.0.0.1")
                }
            }
        }
    }

    private fun listenTcp(port: Int) {
        val srv = ServerSocket(port).also { serverSocket = it }
        srv.use { server ->
            while (listenJob?.isActive == true && !server.isClosed) {
                val client = server.accept()
                val remoteHost = client.inetAddress.hostAddress ?: "127.0.0.1"
                client.use { sock ->
                    val input = BufferedInputStream(sock.getInputStream())
                    while (listenJob?.isActive == true) {
                        val headerBytes = readExact(input, HEADER_SIZE) ?: break
                        val magic = String(headerBytes, 0, 4, Charsets.US_ASCII)
                        when (magic) {
                            MAGIC -> {
                                val header = parsePacketHeader(headerBytes) ?: continue
                                val payload = readExact(input, header.payloadBytes) ?: break
                                ingestPcmPayload(header, payload)
                            }
                            MAGIC_VMC -> {
                                parseVmcPacket(headerBytes)?.let { vmc ->
                                    _state.value = _state.value.copy(
                                        vmcRxCount = _state.value.vmcRxCount + 1,
                                        statusMessage = "VMC2 RX CH${vmc.channel64} ${vmc.msgType.label} #${vmc.data1}"
                                    )
                                    onVmcEventReceived(vmc)
                                }
                            }
                            MAGIC_HANDOFF_REQ -> {
                                val replyPort = parseHandoffReqPort(headerBytes)
                                _state.value = _state.value.copy(
                                    statusMessage = "PEER $remoteHost:$replyPort REQUESTED HANDOFF"
                                )
                                onHandoffRequestedByPeer(remoteHost, replyPort)
                            }
                            MAGIC_HANDOFF -> {
                                val bb = ByteBuffer.wrap(headerBytes, 4, 16).order(ByteOrder.LITTLE_ENDIAN)
                                val sessionId = bb.int
                                val chunkIdx = bb.short.toInt() and 0xFFFF
                                val totalChunks = bb.short.toInt() and 0xFFFF
                                val totalSize = bb.int
                                val chunkLen = bb.int
                                val payload = readExact(input, chunkLen) ?: break
                                ingestHandoffChunk(sessionId, chunkIdx, totalChunks, totalSize, payload)
                            }
                            MAGIC_BEACON -> {
                                val bb = ByteBuffer.wrap(headerBytes, 4, 16).order(ByteOrder.LITTLE_ENDIAN)
                                val listenPort = bb.int
                                val styleOrd = bb.int
                                val flags = bb.short.toInt() and 0xFFFF
                                val payLen = bb.short.toInt() and 0xFFFF
                                val trackSlot = bb.int
                                val payload = readExact(input, payLen) ?: break
                                ingestBeaconFrame(remoteHost, listenPort, styleOrd, flags, trackSlot, payload)
                            }
                            MAGIC_COLLAB_AUDIO -> {
                                val bb = ByteBuffer.wrap(headerBytes, 4, 16).order(ByteOrder.LITTLE_ENDIAN)
                                val metaLen = bb.short.toInt() and 0xFFFF
                                val styleOrd = bb.short.toInt() and 0xFFFF
                                val sr = bb.int
                                val chTrack = bb.short.toInt() and 0xFFFF
                                val pcmLen = bb.short.toInt() and 0xFFFF
                                val peakScaled = bb.int
                                val payload = readExact(input, metaLen + pcmLen) ?: break
                                ingestCollabAudioFrame(remoteHost, metaLen, styleOrd, sr, chTrack, pcmLen, peakScaled, payload)
                            }
                            MAGIC_REC_CMD -> {
                                val bb = ByteBuffer.wrap(headerBytes, 4, 16).order(ByteOrder.LITTLE_ENDIAN)
                                val cmdCode = bb.int
                                val bpmX100 = bb.int
                                val styleOrd = bb.short.toInt() and 0xFFFF
                                val payLen = bb.short.toInt() and 0xFFFF
                                val senderPort = bb.int
                                val payload = readExact(input, payLen) ?: break
                                ingestRecCommandFrame(remoteHost, cmdCode, bpmX100, styleOrd, senderPort, payload)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun readExact(input: BufferedInputStream, length: Int): ByteArray? {
        if (length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        var readTotal = 0
        while (readTotal < length) {
            val n = input.read(out, readTotal, length - readTotal)
            if (n < 0) return null
            readTotal += n
        }
        return out
    }

    private fun processIncomingDatagram(packet: ByteArray, remoteHost: String) {
        if (packet.size < HEADER_SIZE) return
        val magic = String(packet, 0, 4, Charsets.US_ASCII)
        when (magic) {
            MAGIC -> {
                val header = parsePacketHeader(packet) ?: return
                val actualLen = min(header.payloadBytes, packet.size - HEADER_SIZE)
                if (actualLen <= 0) return
                val payload = packet.copyOfRange(HEADER_SIZE, HEADER_SIZE + actualLen)
                ingestPcmPayload(header, payload)
            }
            MAGIC_VMC -> {
                parseVmcPacket(packet)?.let { vmc ->
                    _state.value = _state.value.copy(
                        vmcRxCount = _state.value.vmcRxCount + 1,
                        statusMessage = "VMC2 RX CH${vmc.channel64} ${vmc.msgType.label} #${vmc.data1}"
                    )
                    onVmcEventReceived(vmc)
                }
            }
            MAGIC_HANDOFF_REQ -> {
                val replyPort = parseHandoffReqPort(packet)
                _state.value = _state.value.copy(statusMessage = "PEER $remoteHost:$replyPort REQUESTED HANDOFF")
                onHandoffRequestedByPeer(remoteHost, replyPort)
            }
            MAGIC_HANDOFF -> {
                val bb = ByteBuffer.wrap(packet, 4, 16).order(ByteOrder.LITTLE_ENDIAN)
                val sessionId = bb.int
                val chunkIdx = bb.short.toInt() and 0xFFFF
                val totalChunks = bb.short.toInt() and 0xFFFF
                val totalSize = bb.int
                val chunkLen = bb.int
                val actualLen = min(chunkLen, packet.size - HEADER_SIZE)
                if (actualLen > 0) {
                    val payload = packet.copyOfRange(HEADER_SIZE, HEADER_SIZE + actualLen)
                    ingestHandoffChunk(sessionId, chunkIdx, totalChunks, totalSize, payload)
                }
            }
            MAGIC_BEACON -> {
                val bb = ByteBuffer.wrap(packet, 4, 16).order(ByteOrder.LITTLE_ENDIAN)
                val listenPort = bb.int
                val styleOrd = bb.int
                val flags = bb.short.toInt() and 0xFFFF
                val payLen = bb.short.toInt() and 0xFFFF
                val trackSlot = bb.int
                val actualLen = min(payLen, packet.size - HEADER_SIZE)
                if (actualLen > 0) {
                    val payload = packet.copyOfRange(HEADER_SIZE, HEADER_SIZE + actualLen)
                    ingestBeaconFrame(remoteHost, listenPort, styleOrd, flags, trackSlot, payload)
                }
            }
            MAGIC_COLLAB_AUDIO -> {
                val bb = ByteBuffer.wrap(packet, 4, 16).order(ByteOrder.LITTLE_ENDIAN)
                val metaLen = bb.short.toInt() and 0xFFFF
                val styleOrd = bb.short.toInt() and 0xFFFF
                val sr = bb.int
                val chTrack = bb.short.toInt() and 0xFFFF
                val pcmLen = bb.short.toInt() and 0xFFFF
                val peakScaled = bb.int
                val actualTotal = min(metaLen + pcmLen, packet.size - HEADER_SIZE)
                if (actualTotal > metaLen) {
                    val payload = packet.copyOfRange(HEADER_SIZE, HEADER_SIZE + actualTotal)
                    ingestCollabAudioFrame(remoteHost, metaLen, styleOrd, sr, chTrack, actualTotal - metaLen, peakScaled, payload)
                }
            }
            MAGIC_REC_CMD -> {
                val bb = ByteBuffer.wrap(packet, 4, 16).order(ByteOrder.LITTLE_ENDIAN)
                val cmdCode = bb.int
                val bpmX100 = bb.int
                val styleOrd = bb.short.toInt() and 0xFFFF
                val payLen = bb.short.toInt() and 0xFFFF
                val senderPort = bb.int
                val actualLen = min(payLen, packet.size - HEADER_SIZE)
                val payload = if (actualLen > 0) packet.copyOfRange(HEADER_SIZE, HEADER_SIZE + actualLen) else ByteArray(0)
                ingestRecCommandFrame(remoteHost, cmdCode, bpmX100, styleOrd, senderPort, payload)
            }
        }
    }

    private fun ingestHandoffChunk(
        sessionId: Int,
        chunkIdx: Int,
        totalChunks: Int,
        totalSize: Int,
        payload: ByteArray
    ) {
        if (totalChunks <= 0 || chunkIdx !in 0 until totalChunks) return
        val entry = reassemblyMap.computeIfAbsent(sessionId) { HandoffReassembly(totalChunks, totalSize) }
        var completedWire: ByteArray? = null
        synchronized(entry) {
            if (entry.chunks[chunkIdx] == null) {
                entry.chunks[chunkIdx] = payload
                entry.receivedCount++
            }
            if (entry.receivedCount >= entry.totalChunks) {
                val bos = ByteArrayOutputStream(entry.totalBytes.coerceAtLeast(1024))
                for (c in entry.chunks) {
                    if (c != null) bos.write(c)
                }
                completedWire = bos.toByteArray()
                reassemblyMap.remove(sessionId)
            }
        }
        val wire = completedWire ?: return
        val snap = SessionHandoffSnapshot.decodeFromWireBytes(wire) ?: return
        _state.value = _state.value.copy(
            lastHandoffSender = snap.senderName,
            statusMessage = "HANDOFF RX FROM ${snap.senderName} (${snap.stationKey})"
        )
        onSessionHandoffReceived(snap)
    }

    private fun ingestPcmPayload(header: AudioPacketHeader, payload: ByteArray) {
        synchronized(accumLock) {
            if (pcmAccumulator.size() > 6_000_000) pcmAccumulator.reset()
            pcmAccumulator.write(payload)
        }
        val floats = WavCodec.pcm16BytesToFloats(payload)
        val mono = if (header.channels > 1) {
            AudioBuffer(floats, header.sampleRate, header.channels).toMonoSamples()
        } else {
            floats
        }
        onStreamChunkForTuner(mono, header.sampleRate)
        _state.value = _state.value.copy(
            rxPackets = _state.value.rxPackets + 1,
            rxBytes = _state.value.rxBytes + HEADER_SIZE + payload.size,
            rxSampleRate = header.sampleRate,
            rxChannels = header.channels,
            statusMessage = "RX ${header.sampleRate}Hz ${header.channels}CH • ${_state.value.rxPackets + 1} PKTS"
        )
    }

    fun captureReceivedBuffer(name: String = "SMP2_RX"): AudioBuffer? {
        val bytes = synchronized(accumLock) { pcmAccumulator.toByteArray() }
        if (bytes.size < 4) return null
        val cur = _state.value
        val floats = WavCodec.pcm16BytesToFloats(bytes)
        return AudioBuffer(floats, cur.rxSampleRate, cur.rxChannels, name)
    }

    fun sendVmcEvent(pkt: VmcEventPacket, config: RigConfig) {
        if (!config.isValidIpOrHost() || !config.isValidPort()) return
        val bytes = buildVmcPacket(pkt)
        scope.launch {
            runCatching {
                when (config.protocol) {
                    NetworkProtocol.UDP -> {
                        val addr = InetAddress.getByName(config.ipAddress.trim())
                        DatagramSocket().use { s ->
                            s.send(DatagramPacket(bytes, bytes.size, addr, config.port))
                        }
                    }
                    NetworkProtocol.TCP -> {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(config.ipAddress.trim(), config.port), 1500)
                            s.getOutputStream().apply {
                                write(bytes)
                                flush()
                            }
                        }
                    }
                }
                _state.value = _state.value.copy(
                    vmcTxCount = _state.value.vmcTxCount + 1,
                    statusMessage = "VMC2 TX CH${pkt.channel64} ${pkt.msgType.label} #${pkt.data1}"
                )
            }
        }
    }

    fun sendSessionHandoff(
        snapshot: SessionHandoffSnapshot,
        protocol: NetworkProtocol,
        targetHost: String,
        targetPort: Int
    ) {
        scope.launch {
            val wire = snapshot.encodeToWireBytes()
            val sid = handoffSeq.getAndIncrement()
            val chunkSize = if (protocol == NetworkProtocol.UDP) 11500 else 32768
            val totalChunks = ((wire.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
            runCatching {
                when (protocol) {
                    NetworkProtocol.UDP -> {
                        val addr = InetAddress.getByName(targetHost.trim())
                        DatagramSocket().use { s ->
                            for (idx in 0 until totalChunks) {
                                val start = idx * chunkSize
                                val len = min(chunkSize, wire.size - start)
                                val frame = buildHandoffFrame(sid, idx, totalChunks, wire.size, wire, start, len)
                                s.send(DatagramPacket(frame, frame.size, addr, targetPort))
                                if (totalChunks > 4 && idx % 4 == 3) Thread.sleep(2)
                            }
                        }
                    }
                    NetworkProtocol.TCP -> {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(targetHost.trim(), targetPort), 3000)
                            val out = BufferedOutputStream(s.getOutputStream())
                            for (idx in 0 until totalChunks) {
                                val start = idx * chunkSize
                                val len = min(chunkSize, wire.size - start)
                                out.write(buildHandoffFrame(sid, idx, totalChunks, wire.size, wire, start, len))
                            }
                            out.flush()
                        }
                    }
                }
                _state.value = _state.value.copy(
                    statusMessage = "PUSHED HANDOFF (${wire.size / 1024}KB) -> $targetHost:$targetPort",
                    errorMessage = null
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    statusMessage = "HANDOFF PUSH FAILED",
                    errorMessage = e.message
                )
            }
        }
    }

    fun requestSessionFromPeer(config: RigConfig) {
        if (!config.isValidIpOrHost() || !config.isValidPort()) return
        scope.launch {
            val req = buildHandoffRequestFrame(config.bindPort)
            runCatching {
                when (config.protocol) {
                    NetworkProtocol.UDP -> {
                        val addr = InetAddress.getByName(config.ipAddress.trim())
                        DatagramSocket().use { s ->
                            s.send(DatagramPacket(req, req.size, addr, config.port))
                        }
                    }
                    NetworkProtocol.TCP -> {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(config.ipAddress.trim(), config.port), 2500)
                            s.getOutputStream().apply {
                                write(req)
                                flush()
                            }
                        }
                    }
                }
                _state.value = _state.value.copy(
                    statusMessage = "GRABBING HANDOFF FROM ${config.ipAddress}:${config.port} (REPLY :${config.bindPort})",
                    errorMessage = null
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    statusMessage = "HANDOFF GRAB FAILED",
                    errorMessage = e.message
                )
            }
        }
    }

    fun transmitBuffer(buffer: AudioBuffer, config: RigConfig) {
        if (!config.isValidIpOrHost() || !config.isValidPort()) {
            _state.value = _state.value.copy(
                isTransmitting = false,
                targetEndpoint = "${config.ipAddress}:${config.port}",
                statusMessage = "INVALID ENDPOINT",
                errorMessage = "Enter a valid IP/host and port (1-65535)"
            )
            return
        }

        txJob?.cancel()
        txJob = scope.launch {
            val prepared = buffer
                .convertChannels(config.channels)
                .resample(config.sampleRate)
            val pcmBytes = WavCodec.floatToPcm16Bytes(prepared.samples)
            val bytesPerFrame = config.channels * 2
            val maxPacketPcmBytes = (config.bufferSize * bytesPerFrame).coerceAtLeast(bytesPerFrame)
            val endpoint = "${config.ipAddress.trim()}:${config.port}"

            _state.value = _state.value.copy(
                isTransmitting = true,
                packetsSent = 0L,
                bytesSent = 0L,
                activeProtocol = config.protocol,
                targetEndpoint = endpoint,
                statusMessage = "STREAMING ${config.protocol.label} -> $endpoint",
                errorMessage = null
            )

            try {
                var packets = 0L
                var bytesTotal = 0L

                when (config.protocol) {
                    NetworkProtocol.TCP -> {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(config.ipAddress.trim(), config.port), 3000)
                            socket.soTimeout = 3000
                            val out = BufferedOutputStream(socket.getOutputStream())
                            var offset = 0
                            var seq = 0
                            while (offset < pcmBytes.size && isActive) {
                                val chunkLen = min(maxPacketPcmBytes, pcmBytes.size - offset)
                                val packet = buildPacketBytes(
                                    sequence = seq++,
                                    sampleRate = config.sampleRate,
                                    channels = config.channels,
                                    pcmPayload = pcmBytes,
                                    offset = offset,
                                    length = chunkLen
                                )
                                out.write(packet)
                                offset += chunkLen
                                packets++
                                bytesTotal += packet.size
                                _state.value = _state.value.copy(
                                    packetsSent = packets,
                                    bytesSent = bytesTotal
                                )
                            }
                            out.flush()
                        }
                    }

                    NetworkProtocol.UDP -> {
                        val address = InetAddress.getByName(config.ipAddress.trim())
                        DatagramSocket().use { datagramSocket ->
                            val udpChunkCap = min(maxPacketPcmBytes, 16384)
                            var offset = 0
                            var seq = 0
                            while (offset < pcmBytes.size && isActive) {
                                val chunkLen = min(udpChunkCap, pcmBytes.size - offset)
                                val packetBytes = buildPacketBytes(
                                    sequence = seq++,
                                    sampleRate = config.sampleRate,
                                    channels = config.channels,
                                    pcmPayload = pcmBytes,
                                    offset = offset,
                                    length = chunkLen
                                )
                                val dgram = DatagramPacket(packetBytes, packetBytes.size, address, config.port)
                                datagramSocket.send(dgram)
                                offset += chunkLen
                                packets++
                                bytesTotal += packetBytes.size
                                _state.value = _state.value.copy(
                                    packetsSent = packets,
                                    bytesSent = bytesTotal
                                )
                            }
                        }
                    }
                }

                _state.value = _state.value.copy(
                    isTransmitting = false,
                    statusMessage = "TX COMPLETE ($packets PKTS)",
                    errorMessage = null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isTransmitting = false,
                    statusMessage = "TX FAILED",
                    errorMessage = e.message ?: "Network error"
                )
            }
        }
    }

    // --- 4-DEVICE COLLABORATION, NEARBY DISCOVERY & LIVE BAND RECORDING ---

    fun updateLocalPresence(
        nodeName: String,
        listenPort: Int,
        style: CollabInstrumentStyle,
        trackSlot: Int,
        isRecording: Boolean
    ) {
        localPeerName = nodeName.ifBlank { "ANDROID RIG" }
        localListenPort = listenPort.coerceIn(1, 65535)
        localStyle = style
        localTrackSlot = trackSlot.coerceIn(0, 3)
        localIsRecording = isRecording
    }

    fun seedKnownPeers(peers: List<CollabPeerDevice>) {
        if (peers.isEmpty()) return
        val current = _collabPeers.value.associateBy { it.peerId }.toMutableMap()
        peers.forEach { kp ->
            val existing = current[kp.peerId]
            if (existing == null) {
                current[kp.peerId] = kp.copy(isKnown = true)
            } else {
                current[kp.peerId] = existing.copy(isKnown = true)
            }
        }
        publishPeers(current.values.toList())
    }

    fun startDiscovery(discoveryPort: Int = COLLAB_DISCOVERY_PORT) {
        stopDiscovery()
        _state.value = _state.value.copy(isDiscovering = true)

        beaconRxJob = scope.launch {
            runCatching {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(discoveryPort))
                }.also { beaconRxSocket = it }
                val buf = ByteArray(4096)
                sock.use { s ->
                    while (isActive && !s.isClosed) {
                        val pkt = DatagramPacket(buf, buf.size)
                        s.receive(pkt)
                        if (pkt.length >= HEADER_SIZE) {
                            val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                            val host = pkt.address.hostAddress ?: "127.0.0.1"
                            processIncomingDatagram(data, host)
                        }
                    }
                }
            }
        }

        beaconTxJob = scope.launch {
            while (isActive) {
                runCatching { sendDiscoveryBeaconOnce(discoveryPort) }
                delay(2500L)
            }
        }
    }

    fun stopDiscovery() {
        beaconRxJob?.cancel()
        beaconTxJob?.cancel()
        beaconRxJob = null
        beaconTxJob = null
        runCatching { beaconRxSocket?.close() }
        beaconRxSocket = null
        _state.value = _state.value.copy(isDiscovering = false)
    }

    fun sendDiscoveryBeaconOnce(
        discoveryPort: Int = COLLAB_DISCOVERY_PORT,
        explicitTargets: List<Pair<String, Int>> = emptyList()
    ) {
        val frame = buildBeaconFrame(
            nodeId = localNodeId,
            name = localPeerName,
            platform = "ANDROID",
            listenPort = localListenPort,
            style = localStyle,
            trackSlot = localTrackSlot,
            isRecording = localIsRecording
        )
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                // LAN broadcast on beacon port
                runCatching {
                    val bcast = InetAddress.getByName("255.255.255.255")
                    s.send(DatagramPacket(frame, frame.size, bcast, discoveryPort))
                }
                // Emulator host & known peers direct probes (port + beacon port)
                val probeSet = LinkedHashSet<Pair<String, Int>>()
                probeSet += "10.0.2.2" to 9000
                probeSet += "10.0.2.2" to discoveryPort
                _collabPeers.value.forEach { p ->
                    probeSet += p.host to p.listenPort
                    probeSet += p.host to discoveryPort
                }
                probeSet.addAll(explicitTargets)
                probeSet.forEach { (h, pt) ->
                    runCatching {
                        val addr = InetAddress.getByName(h)
                        s.send(DatagramPacket(frame, frame.size, addr, pt))
                    }
                }
            }
        }
    }

    fun connectPeer(
        host: String,
        listenPort: Int,
        name: String = "Studio Peer",
        platform: String = "PEER",
        style: CollabInstrumentStyle? = null,
        trackSlot: Int? = null
    ): Result<CollabPeerDevice> {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty() || listenPort !in 1..65535) {
            return Result.failure(IllegalArgumentException("Enter valid host and port"))
        }
        val key = "$cleanHost:$listenPort"
        val curList = _collabPeers.value
        val existing = curList.firstOrNull { it.peerId == key || (it.host == cleanHost && it.listenPort == listenPort) }
        val connectedAlready = curList.count { it.isConnected }
        if (existing?.isConnected != true && connectedAlready >= MAX_COLLAB_PEERS) {
            val msg = "MAX $MAX_COLLAB_PEERS SIMULTANEOUS COLLABORATORS REACHED"
            _state.value = _state.value.copy(statusMessage = msg, errorMessage = msg)
            return Result.failure(IllegalStateException(msg))
        }
        val assignedStyle = style ?: existing?.style ?: suggestNextStyle(curList)
        val assignedTrack = (trackSlot ?: existing?.trackSlot ?: assignedStyle.defaultTrack).coerceIn(0, 3)
        val updatedPeer = (existing ?: CollabPeerDevice(
            peerId = key,
            name = name.ifBlank { "Peer $cleanHost" },
            platform = platform,
            host = cleanHost,
            listenPort = listenPort
        )).copy(
            isConnected = true,
            isKnown = true,
            style = assignedStyle,
            trackSlot = assignedTrack,
            lastSeenMs = System.currentTimeMillis()
        )
        val nextList = curList.filterNot { it.peerId == updatedPeer.peerId } + updatedPeer
        publishPeers(nextList)
        scope.launch {
            sendDiscoveryBeaconOnce(explicitTargets = listOf(cleanHost to listenPort))
        }
        _state.value = _state.value.copy(
            targetEndpoint = key,
            statusMessage = "LINKED ${updatedPeer.name} (${assignedStyle.shortTag}) • ${nextList.count { it.isConnected }}/$MAX_COLLAB_PEERS",
            errorMessage = null
        )
        return Result.success(updatedPeer)
    }

    fun disconnectPeer(peerId: String) {
        val next = _collabPeers.value.map {
            if (it.peerId == peerId) it.copy(isConnected = false, isRecording = false, livePeak = 0f) else it
        }
        publishPeers(next)
    }

    fun removeKnownPeer(peerId: String) {
        val next = _collabPeers.value.filterNot { it.peerId == peerId }
        publishPeers(next)
    }

    fun updatePeerStyleAndTrack(peerId: String, style: CollabInstrumentStyle, trackSlot: Int = style.defaultTrack) {
        val next = _collabPeers.value.map {
            if (it.peerId == peerId) it.copy(style = style, trackSlot = trackSlot.coerceIn(0, 3)) else it
        }
        publishPeers(next)
    }

    fun getConnectedPeers(): List<CollabPeerDevice> =
        _collabPeers.value.filter { it.isConnected }.take(MAX_COLLAB_PEERS)

    fun beginCollabAudioCapture() {
        collabAccumulators.clear()
    }

    fun stopAndCollectCollabTracks(takePrefix: String = "BAND"): List<Pair<CollabPeerDevice, AudioBuffer>> {
        val out = mutableListOf<Pair<CollabPeerDevice, AudioBuffer>>()
        val currentPeers = _collabPeers.value
        collabAccumulators.forEach { (key, acc) ->
            val raw = synchronized(acc) { acc.stream.toByteArray() }
            if (raw.size >= 512) {
                val peer = currentPeers.firstOrNull { it.peerId == key || it.host == key }
                    ?: CollabPeerDevice(
                        peerId = key,
                        name = key,
                        host = key.substringBefore(':'),
                        listenPort = key.substringAfter(':', "9000").toIntOrNull() ?: 9000,
                        style = CollabInstrumentStyle.GUITAR
                    )
                val floats = WavCodec.pcm16BytesToFloats(raw)
                val bufName = "${takePrefix}_${peer.style.shortTag}_${peer.name}".take(24)
                out += peer to AudioBuffer(floats, acc.sampleRate, acc.channels, bufName)
            }
        }
        collabAccumulators.clear()
        return out
    }

    fun sendCollabLiveChunk(
        samples: FloatArray,
        sampleRate: Int,
        style: CollabInstrumentStyle,
        trackSlot: Int
    ) {
        val peers = getConnectedPeers()
        if (peers.isEmpty() || samples.isEmpty()) return
        val pcmBytes = WavCodec.floatToPcm16Bytes(samples)
        val senderTag = "$localPeerName|$localListenPort".take(42)
        // Split if large so UDP packets remain well below MTU fragmentation limit
        val maxPcmPerFrame = 8192
        val total = pcmBytes.size
        scope.launch {
            var offset = 0
            DatagramSocket().use { udp ->
                while (offset < total) {
                    val len = min(maxPcmPerFrame, total - offset)
                    val frame = buildCollabAudioFrame(
                        senderTag = senderTag,
                        style = style,
                        sampleRate = sampleRate,
                        channels = 1,
                        trackSlot = trackSlot,
                        pcmBytes = pcmBytes,
                        offset = offset,
                        length = len
                    )
                    peers.forEach { p ->
                        runCatching {
                            val addr = InetAddress.getByName(p.host)
                            udp.send(DatagramPacket(frame, frame.size, addr, p.listenPort))
                        }
                    }
                    offset += len
                }
            }
        }
    }

    fun sendCollabRecSync(cmd: CollabSyncCommand) {
        val peers = getConnectedPeers()
        if (peers.isEmpty()) return
        val frame = buildRecCommandFrame(cmd)
        scope.launch {
            DatagramSocket().use { udp ->
                peers.forEach { p ->
                    runCatching {
                        val addr = InetAddress.getByName(p.host)
                        udp.send(DatagramPacket(frame, frame.size, addr, p.listenPort))
                    }
                }
            }
        }
    }

    private fun ingestBeaconFrame(
        remoteHost: String,
        listenPort: Int,
        styleOrd: Int,
        flags: Int,
        trackSlot: Int,
        payload: ByteArray
    ) {
        val kv = CollabWireCodec.decodeBeaconPayload(payload)
        val nodeId = kv["id"].orEmpty()
        if (nodeId == localNodeId) return // Ignore own multicast/broadcast loopback
        val port = kv["port"]?.toIntOrNull()?.coerceIn(1, 65535) ?: listenPort.coerceIn(1, 65535)
        val name = kv["name"]?.ifBlank { "Peer $remoteHost" } ?: "Peer $remoteHost"
        val plat = kv["plat"]?.ifBlank { "LINUX" } ?: "LINUX"
        val style = CollabInstrumentStyle.fromOrdinalOrName(styleOrd, kv["style"])
        val track = (kv["track"]?.toIntOrNull() ?: trackSlot).coerceIn(0, 3)
        val rec = (flags and 1) == 1 || kv["rec"] == "true"
        val key = "$remoteHost:$port"

        val current = _collabPeers.value.associateBy { it.peerId }.toMutableMap()
        val prev = current[key]
        val updated = (prev ?: CollabPeerDevice(
            peerId = key,
            name = name,
            platform = plat,
            host = remoteHost,
            listenPort = port
        )).copy(
            name = name,
            platform = plat,
            style = if (prev?.isConnected == true) prev.style else style,
            trackSlot = if (prev?.isConnected == true) prev.trackSlot else track,
            isNearby = true,
            isRecording = rec,
            lastSeenMs = System.currentTimeMillis()
        )
        current[key] = updated
        publishPeers(current.values.toList())
    }

    private fun ingestCollabAudioFrame(
        remoteHost: String,
        metaLen: Int,
        styleOrd: Int,
        sampleRate: Int,
        chTrack: Int,
        pcmLen: Int,
        peakScaled: Int,
        payload: ByteArray
    ) {
        if (pcmLen < 2 || metaLen + pcmLen > payload.size) return
        val tag = String(payload, 0, metaLen, Charsets.UTF_8)
        val peerName = tag.substringBefore('|').ifBlank { "Band Peer" }
        val peerPort = tag.substringAfter('|', "9000").toIntOrNull()?.coerceIn(1, 65535) ?: 9000
        val peerKey = "$remoteHost:$peerPort"
        val channels = (chTrack and 0xFF).coerceIn(1, 2)
        val trackSlot = ((chTrack shr 8) and 0xFF).coerceIn(0, 3)
        val style = CollabInstrumentStyle.fromOrdinalOrName(styleOrd, null)
        val pcmPayload = payload.copyOfRange(metaLen, metaLen + pcmLen)
        val peakNorm = (peakScaled / 32768f).coerceIn(0f, 1f)

        val current = _collabPeers.value.associateBy { it.peerId }.toMutableMap()
        val prev = current[peerKey]
        val canConnect = prev?.isConnected == true || current.values.count { it.isConnected } < MAX_COLLAB_PEERS
        current[peerKey] = (prev ?: CollabPeerDevice(
            peerId = peerKey,
            name = peerName,
            platform = "PEER",
            host = remoteHost,
            listenPort = peerPort
        )).copy(
            name = peerName,
            style = prev?.style ?: style,
            trackSlot = prev?.trackSlot ?: trackSlot,
            isConnected = canConnect,
            isNearby = true,
            livePeak = peakNorm,
            rxPackets = (prev?.rxPackets ?: 0L) + 1L,
            lastSeenMs = System.currentTimeMillis()
        )
        publishPeers(current.values.toList())

        val acc = collabAccumulators.computeIfAbsent(peerKey) {
            CollabStreamAccum(sampleRate.coerceIn(8000, 96000), channels)
        }
        synchronized(acc) {
            if (acc.stream.size() < 24_000_000) acc.stream.write(pcmPayload)
        }

        val floats = WavCodec.pcm16BytesToFloats(pcmPayload)
        val mono = if (channels > 1) AudioBuffer(floats, sampleRate, channels).toMonoSamples() else floats
        onStreamChunkForTuner(mono, sampleRate)
        _state.value = _state.value.copy(
            rxPackets = _state.value.rxPackets + 1,
            rxBytes = _state.value.rxBytes + HEADER_SIZE + payload.size,
            statusMessage = "BAND LIVE RX • ${peerName} (${style.shortTag})"
        )
    }

    private fun ingestRecCommandFrame(
        remoteHost: String,
        cmdCode: Int,
        bpmX100: Int,
        styleOrd: Int,
        senderPort: Int,
        payload: ByteArray
    ) {
        val kv = CollabWireCodec.decodeBeaconPayload(payload)
        val name = kv["name"]?.ifBlank { "Peer" } ?: "Peer"
        val port = senderPort.coerceIn(1, 65535)
        val key = "$remoteHost:$port"
        val style = CollabInstrumentStyle.fromOrdinalOrName(styleOrd, kv["style"])
        val track = kv["track"]?.toIntOrNull()?.coerceIn(0, 3) ?: style.defaultTrack
        val take = kv["take"].orEmpty()
        val start = (cmdCode == 1)
        val bpm = (bpmX100 / 100f).coerceIn(40f, 240f)
        onCollabSyncCommandReceived(
            CollabSyncCommand(
                start = start,
                bpm = bpm,
                senderPeerId = key,
                senderName = name,
                senderStyle = style,
                senderTrackSlot = track,
                takeName = take,
                senderListenPort = port
            )
        )
    }

    private fun publishPeers(raw: List<CollabPeerDevice>) {
        val sorted = raw.sortedWith(
            compareByDescending<CollabPeerDevice> { it.isConnected }
                .thenByDescending { it.isNearby }
                .thenBy { it.name }
        )
        _collabPeers.value = sorted
        _state.value = _state.value.copy(
            connectedPeerCount = sorted.count { it.isConnected }.coerceAtMost(MAX_COLLAB_PEERS)
        )
    }

    private fun suggestNextStyle(current: List<CollabPeerDevice>): CollabInstrumentStyle {
        val used = current.filter { it.isConnected }.map { it.style }.toSet()
        return CollabInstrumentStyle.entries.firstOrNull { it != localStyle && it !in used }
            ?: CollabInstrumentStyle.GUITAR
    }

    fun stopTransmission() {
        txJob?.cancel()
        txJob = null
        _state.value = _state.value.copy(
            isTransmitting = false,
            statusMessage = "STOPPED"
        )
    }

    fun release() {
        stopTransmission()
        stopListening()
        stopDiscovery()
    }

    companion object {
        const val MAGIC = "SMP2"
        const val MAGIC_VMC = "VMC2"
        const val MAGIC_HANDOFF = "SMH2"
        const val MAGIC_HANDOFF_REQ = "SMHR"
        const val MAGIC_BEACON = "SMDP"
        const val MAGIC_COLLAB_AUDIO = "SMCL"
        const val MAGIC_REC_CMD = "SMRC"
        const val HEADER_SIZE = 20

        fun buildBeaconFrame(
            nodeId: String,
            name: String,
            platform: String,
            listenPort: Int,
            style: CollabInstrumentStyle,
            trackSlot: Int,
            isRecording: Boolean
        ): ByteArray {
            val payload = CollabWireCodec.encodeBeaconPayload(nodeId, name, platform, listenPort, style, trackSlot, isRecording)
            val out = ByteArray(HEADER_SIZE + payload.size)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(MAGIC_BEACON.toByteArray(Charsets.US_ASCII))
            bb.putInt(listenPort.coerceIn(1, 65535))
            bb.putInt(style.ordinal)
            bb.putShort((if (isRecording) 1 else 0).toShort())
            bb.putShort(payload.size.toShort())
            bb.putInt(trackSlot.coerceIn(0, 3))
            System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
            return out
        }

        fun buildCollabAudioFrame(
            senderTag: String,
            style: CollabInstrumentStyle,
            sampleRate: Int,
            channels: Int,
            trackSlot: Int,
            pcmBytes: ByteArray,
            offset: Int = 0,
            length: Int = pcmBytes.size - offset
        ): ByteArray {
            val metaBytes = senderTag.toByteArray(Charsets.UTF_8)
            var peak = 0
            var i = offset
            while (i + 1 < offset + length) {
                val lo = pcmBytes[i].toInt() and 0xFF
                val hi = pcmBytes[i + 1].toInt()
                val s = abs((hi shl 8) or lo)
                if (s > peak) peak = s
                i += 2
            }
            val chTrack = (channels.coerceIn(1, 2) and 0xFF) or ((trackSlot.coerceIn(0, 3) and 0xFF) shl 8)
            val out = ByteArray(HEADER_SIZE + metaBytes.size + length)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(MAGIC_COLLAB_AUDIO.toByteArray(Charsets.US_ASCII))
            bb.putShort(metaBytes.size.toShort())
            bb.putShort(style.ordinal.toShort())
            bb.putInt(sampleRate)
            bb.putShort(chTrack.toShort())
            bb.putShort(length.toShort())
            bb.putInt(peak)
            System.arraycopy(metaBytes, 0, out, HEADER_SIZE, metaBytes.size)
            System.arraycopy(pcmBytes, offset, out, HEADER_SIZE + metaBytes.size, length)
            return out
        }

        fun buildRecCommandFrame(cmd: CollabSyncCommand): ByteArray {
            val cleanName = cmd.senderName.replace(";", " ").take(24)
            val cleanTake = cmd.takeName.replace(";", " ").take(24)
            val payload = "name=$cleanName;style=${cmd.senderStyle.name};track=${cmd.senderTrackSlot};take=$cleanTake"
                .toByteArray(Charsets.UTF_8)
            val out = ByteArray(HEADER_SIZE + payload.size)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(MAGIC_REC_CMD.toByteArray(Charsets.US_ASCII))
            bb.putInt(if (cmd.start) 1 else 2)
            bb.putInt((cmd.bpm * 100f).toInt())
            bb.putShort(cmd.senderStyle.ordinal.toShort())
            bb.putShort(payload.size.toShort())
            bb.putInt(cmd.senderListenPort.coerceIn(1, 65535))
            System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
            return out
        }

        fun buildPacketBytes(
            sequence: Int,
            sampleRate: Int,
            channels: Int,
            pcmPayload: ByteArray,
            offset: Int = 0,
            length: Int = pcmPayload.size - offset
        ): ByteArray {
            var peak = 0
            var i = offset
            while (i + 1 < offset + length) {
                val lo = pcmPayload[i].toInt() and 0xFF
                val hi = pcmPayload[i + 1].toInt()
                val s = abs((hi shl 8) or lo)
                if (s > peak) peak = s
                i += 2
            }

            val packet = ByteArray(HEADER_SIZE + length)
            val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(MAGIC.toByteArray(Charsets.US_ASCII))
            bb.putInt(sequence)
            bb.putInt(sampleRate)
            bb.putShort(channels.toShort())
            bb.putShort(length.toShort())
            bb.putInt(peak)
            System.arraycopy(pcmPayload, offset, packet, HEADER_SIZE, length)
            return packet
        }

        fun parsePacketHeader(packet: ByteArray): AudioPacketHeader? {
            if (packet.size < HEADER_SIZE) return null
            val bb = ByteBuffer.wrap(packet, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val magicBytes = ByteArray(4).also { bb.get(it) }
            val magic = String(magicBytes, Charsets.US_ASCII)
            if (magic != MAGIC) return null
            return AudioPacketHeader(
                magic = magic,
                sequence = bb.int,
                sampleRate = bb.int,
                channels = bb.short.toInt() and 0xFFFF,
                payloadBytes = bb.short.toInt() and 0xFFFF,
                peakScaled = bb.int
            )
        }

        fun buildVmcPacket(pkt: VmcEventPacket): ByteArray {
            val out = ByteArray(HEADER_SIZE)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(MAGIC_VMC.toByteArray(Charsets.US_ASCII))
            bb.putInt(pkt.channel64.coerceIn(1, 64))
            bb.putInt(pkt.msgType.code)
            bb.putShort(pkt.data1.toShort())
            bb.putShort(pkt.data2.toShort())
            bb.putFloat(pkt.normalized.coerceIn(0f, 1f))
            return out
        }

        fun parseVmcPacket(packet: ByteArray): VmcEventPacket? {
            if (packet.size < HEADER_SIZE) return null
            val bb = ByteBuffer.wrap(packet, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { bb.get(it) }.toString(Charsets.US_ASCII)
            if (magic != MAGIC_VMC) return null
            val ch = bb.int.coerceIn(1, 64)
            val msgType = VmcMsgType.fromCode(bb.int)
            val d1 = bb.short.toInt() and 0xFFFF
            val d2 = bb.short.toInt() and 0xFFFF
            val norm = bb.float.coerceIn(0f, 1f)
            return VmcEventPacket(ch, msgType, d1, d2, norm)
        }

        fun buildHandoffFrame(
            sessionId: Int,
            chunkIdx: Int,
            totalChunks: Int,
            totalBytes: Int,
            payload: ByteArray,
            offset: Int,
            len: Int
        ): ByteArray {
            val out = ByteArray(HEADER_SIZE + len)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(MAGIC_HANDOFF.toByteArray(Charsets.US_ASCII))
            bb.putInt(sessionId)
            bb.putShort(chunkIdx.toShort())
            bb.putShort(totalChunks.toShort())
            bb.putInt(totalBytes)
            bb.putInt(len)
            System.arraycopy(payload, offset, out, HEADER_SIZE, len)
            return out
        }

        fun buildHandoffRequestFrame(replyPort: Int): ByteArray {
            val out = ByteArray(HEADER_SIZE)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(MAGIC_HANDOFF_REQ.toByteArray(Charsets.US_ASCII))
            bb.putInt(replyPort.coerceIn(1, 65535))
            return out
        }

        fun parseHandoffReqPort(packet: ByteArray): Int {
            if (packet.size < 8) return 9001
            val bb = ByteBuffer.wrap(packet, 4, 4).order(ByteOrder.LITTLE_ENDIAN)
            return bb.int.coerceIn(1, 65535)
        }
    }
}
