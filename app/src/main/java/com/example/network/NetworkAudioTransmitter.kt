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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NetworkLinkState(
    val isTransmitting: Boolean = false,
    val isListening: Boolean = false,
    val packetsSent: Long = 0L,
    val bytesSent: Long = 0L,
    val rxPackets: Long = 0L,
    val rxBytes: Long = 0L,
    val rxSampleRate: Int = 44100,
    val rxChannels: Int = 1,
    val vmcRxCount: Long = 0L,
    val vmcTxCount: Long = 0L,
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
 * Multiplexes SMP2 PCM Audio, VMC2 64-Channel MIDI control packets, SMH2 Session Handoff snapshots,
 * and SMHR Session Handoff Pull Requests on the active TCP/UDP channel.
 */
class NetworkAudioTransmitter(
    private val onStreamChunkForTuner: (FloatArray, Int) -> Unit = { _, _ -> },
    private val onVmcEventReceived: (VmcEventPacket) -> Unit = {},
    private val onSessionHandoffReceived: (SessionHandoffSnapshot) -> Unit = {},
    private val onHandoffRequestedByPeer: (senderHost: String, replyPort: Int) -> Unit = { _, _ -> }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var txJob: Job? = null
    private var listenJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var udpRxSocket: DatagramSocket? = null

    private val pcmAccumulator = ByteArrayOutputStream()
    private val accumLock = Any()
    private val handoffSeq = AtomicInteger(1)

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
    }

    companion object {
        const val MAGIC = "SMP2"
        const val MAGIC_VMC = "VMC2"
        const val MAGIC_HANDOFF = "SMH2"
        const val MAGIC_HANDOFF_REQ = "SMHR"
        const val HEADER_SIZE = 20

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
