package com.samplfie.desktop.network

import com.samplfie.desktop.audio.DesktopWavCodec
import com.samplfie.desktop.midi.VmcEventPacket
import com.samplfie.desktop.midi.VmcMsgType
import com.samplfie.desktop.model.DesktopAudioBuffer
import com.samplfie.desktop.model.DesktopRigConfig
import com.samplfie.desktop.model.NetworkProtocol
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

data class Smp2Header(
    val magic: String,
    val sequence: Int,
    val sampleRate: Int,
    val channels: Int,
    val payloadBytes: Int,
    val peakScaled: Int
)

data class NetBridgeState(
    val isListening: Boolean = false,
    val isTransmitting: Boolean = false,
    val rxPackets: Long = 0L,
    val rxBytes: Long = 0L,
    val rxSampleRate: Int = 44100,
    val rxChannels: Int = 1,
    val rxLivePeak: Float = 0f,
    val txPackets: Long = 0L,
    val txBytes: Long = 0L,
    val vmcRxCount: Long = 0L,
    val vmcTxCount: Long = 0L,
    val lastHandoffSender: String? = null,
    val lastHandoffTimestamp: Long = 0L,
    val statusText: String = "IDLE — READY FOR SMP2 / VMC2 / HANDOFF BRIDGE",
    val rxReadyBuffer: DesktopAudioBuffer? = null
)

/**
 * Bidirectional Equal-Peer Network Bridge compatible with Samplfie2 Android.
 * Multiplexes 4 frame types over the active TCP or UDP channel:
 * - SMP2: Streaming 16-bit PCM audio frames
 * - VMC2: Real-time 64-Channel Virtual MIDI Controller events
 * - SMH2: Full Session Handoff (chunk-reassembled snapshot + audio buffer)
 * - SMHR: Session Handoff Pull Request (grabs session from active peer)
 */
class Smp2NetworkNode(
    private val onStreamChunkForTuner: (FloatArray, Int) -> Unit = { _, _ -> },
    private val onVmcEventReceived: (VmcEventPacket) -> Unit = {},
    private val onSessionHandoffReceived: (SessionHandoffSnapshot) -> Unit = {},
    private val onHandoffRequestedByPeer: (senderHost: String, replyPort: Int) -> Unit = { _, _ -> }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenJob: Job? = null
    private var txJob: Job? = null
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

    private val _state = MutableStateFlow(NetBridgeState())
    val state: StateFlow<NetBridgeState> = _state.asStateFlow()

    fun startListening(protocol: NetworkProtocol, port: Int) {
        stopListening()
        synchronized(accumLock) { pcmAccumulator.reset() }
        reassemblyMap.clear()
        _state.value = _state.value.copy(
            isListening = true,
            rxPackets = 0L,
            rxBytes = 0L,
            statusText = "LISTENING ON ${protocol.label} :$port (SMP2/VMC2/HANDOFF)"
        )

        listenJob = scope.launch {
            try {
                when (protocol) {
                    NetworkProtocol.UDP -> listenUdp(port)
                    NetworkProtocol.TCP -> listenTcp(port)
                }
            } catch (e: Exception) {
                if (isActive) {
                    _state.value = _state.value.copy(
                        isListening = false,
                        statusText = "RX ERROR: ${e.message ?: "Socket closed"}"
                    )
                }
            }
        }
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
                                val header = parseHeader(headerBytes) ?: continue
                                val payload = readExact(input, header.payloadBytes) ?: break
                                ingestPcmPayload(header, payload)
                            }
                            MAGIC_VMC -> {
                                parseVmcPacket(headerBytes)?.let { vmc ->
                                    _state.value = _state.value.copy(
                                        vmcRxCount = _state.value.vmcRxCount + 1,
                                        statusText = "VMC2 RX CH${vmc.channel64} ${vmc.msgType.label} #${vmc.data1}"
                                    )
                                    onVmcEventReceived(vmc)
                                }
                            }
                            MAGIC_HANDOFF_REQ -> {
                                val replyPort = parseHandoffReqPort(headerBytes)
                                _state.value = _state.value.copy(statusText = "PEER $remoteHost:$replyPort REQUESTED SESSION")
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
                val header = parseHeader(packet) ?: return
                val actualLen = min(header.payloadBytes, packet.size - HEADER_SIZE)
                if (actualLen <= 0) return
                val payload = packet.copyOfRange(HEADER_SIZE, HEADER_SIZE + actualLen)
                ingestPcmPayload(header, payload)
            }
            MAGIC_VMC -> {
                parseVmcPacket(packet)?.let { vmc ->
                    _state.value = _state.value.copy(
                        vmcRxCount = _state.value.vmcRxCount + 1,
                        statusText = "VMC2 RX CH${vmc.channel64} ${vmc.msgType.label} #${vmc.data1}"
                    )
                    onVmcEventReceived(vmc)
                }
            }
            MAGIC_HANDOFF_REQ -> {
                val replyPort = parseHandoffReqPort(packet)
                _state.value = _state.value.copy(statusText = "PEER $remoteHost:$replyPort REQUESTED SESSION")
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
            lastHandoffTimestamp = System.currentTimeMillis(),
            statusText = "SESSION HANDOFF RX FROM ${snap.senderName} (${snap.stationKey})"
        )
        onSessionHandoffReceived(snap)
    }

    private fun ingestPcmPayload(header: Smp2Header, payload: ByteArray) {
        synchronized(accumLock) {
            if (pcmAccumulator.size() > 6_000_000) pcmAccumulator.reset()
            pcmAccumulator.write(payload)
        }
        val floats = DesktopWavCodec.pcm16BytesToFloats(payload)
        val mono = if (header.channels > 1) {
            DesktopAudioBuffer(floats, header.sampleRate, header.channels).monoMixed()
        } else {
            floats
        }
        onStreamChunkForTuner(mono, header.sampleRate)

        val peakNorm = (header.peakScaled / 32768f).coerceIn(0f, 1f)
        val newPkts = _state.value.rxPackets + 1
        val newBytes = _state.value.rxBytes + HEADER_SIZE + payload.size
        _state.value = _state.value.copy(
            rxPackets = newPkts,
            rxBytes = newBytes,
            rxSampleRate = header.sampleRate,
            rxChannels = header.channels,
            rxLivePeak = peakNorm,
            statusText = "RX ${header.sampleRate}Hz ${header.channels}CH • $newPkts PKTS"
        )
    }

    fun captureReceivedBuffer(name: String = "SMP2_RX"): DesktopAudioBuffer? {
        val bytes = synchronized(accumLock) { pcmAccumulator.toByteArray() }
        if (bytes.size < 4) return null
        val cur = _state.value
        val floats = DesktopWavCodec.pcm16BytesToFloats(bytes)
        val buf = DesktopAudioBuffer(floats, cur.rxSampleRate, cur.rxChannels, name)
        _state.value = cur.copy(rxReadyBuffer = buf)
        return buf
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        runCatching { serverSocket?.close() }
        runCatching { udpRxSocket?.close() }
        serverSocket = null
        udpRxSocket = null
        _state.value = _state.value.copy(isListening = false, statusText = "RX LISTENER STOPPED")
    }

    fun sendVmcEvent(pkt: VmcEventPacket, config: DesktopRigConfig) {
        val bytes = buildVmcPacket(pkt)
        scope.launch {
            runCatching {
                when (config.protocol) {
                    NetworkProtocol.UDP -> {
                        val addr = InetAddress.getByName(config.txHost.trim())
                        DatagramSocket().use { s ->
                            s.send(DatagramPacket(bytes, bytes.size, addr, config.txPort))
                        }
                    }
                    NetworkProtocol.TCP -> {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(config.txHost.trim(), config.txPort), 1500)
                            s.getOutputStream().apply {
                                write(bytes)
                                flush()
                            }
                        }
                    }
                }
                _state.value = _state.value.copy(
                    vmcTxCount = _state.value.vmcTxCount + 1,
                    statusText = "VMC2 TX CH${pkt.channel64} ${pkt.msgType.label} #${pkt.data1}"
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
                    statusText = "PUSHED SESSION HANDOFF (${wire.size / 1024}KB) -> $targetHost:$targetPort"
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(statusText = "HANDOFF TX FAILED: ${e.message}")
            }
        }
    }

    fun requestSessionFromPeer(config: DesktopRigConfig) {
        scope.launch {
            val req = buildHandoffRequestFrame(config.bindPort)
            runCatching {
                when (config.protocol) {
                    NetworkProtocol.UDP -> {
                        val addr = InetAddress.getByName(config.txHost.trim())
                        DatagramSocket().use { s ->
                            s.send(DatagramPacket(req, req.size, addr, config.txPort))
                        }
                    }
                    NetworkProtocol.TCP -> {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(config.txHost.trim(), config.txPort), 2500)
                            s.getOutputStream().apply {
                                write(req)
                                flush()
                            }
                        }
                    }
                }
                _state.value = _state.value.copy(
                    statusText = "REQUESTED HANDOFF FROM ${config.txHost}:${config.txPort} (REPLY :${config.bindPort})"
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(statusText = "HANDOFF PULL FAILED: ${e.message}")
            }
        }
    }

    fun transmitBuffer(buffer: DesktopAudioBuffer, config: DesktopRigConfig) {
        txJob?.cancel()
        txJob = scope.launch {
            val prepared = buffer.convertChannels(config.channels).resample(config.sampleRate)
            val pcm = DesktopWavCodec.floatToPcm16Bytes(prepared.samples)
            val bytesPerFrame = config.channels * 2
            val maxPcm = (config.bufferSize * bytesPerFrame).coerceAtLeast(bytesPerFrame)
            _state.value = _state.value.copy(
                isTransmitting = true,
                txPackets = 0L,
                txBytes = 0L,
                statusText = "TX ${config.protocol.label} -> ${config.txHost}:${config.txPort}"
            )
            try {
                var pkts = 0L
                var bytes = 0L
                when (config.protocol) {
                    NetworkProtocol.TCP -> {
                        Socket().use { sock ->
                            sock.connect(InetSocketAddress(config.txHost.trim(), config.txPort), 3000)
                            val out = BufferedOutputStream(sock.getOutputStream())
                            var offset = 0
                            var seq = 0
                            while (offset < pcm.size && isActive) {
                                val len = min(maxPcm, pcm.size - offset)
                                val pkt = buildPacketBytes(seq++, config.sampleRate, config.channels, pcm, offset, len)
                                out.write(pkt)
                                offset += len
                                pkts++
                                bytes += pkt.size
                                _state.value = _state.value.copy(txPackets = pkts, txBytes = bytes)
                            }
                            out.flush()
                        }
                    }
                    NetworkProtocol.UDP -> {
                        val addr = InetAddress.getByName(config.txHost.trim())
                        DatagramSocket().use { sock ->
                            val cap = min(maxPcm, 16384)
                            var offset = 0
                            var seq = 0
                            while (offset < pcm.size && isActive) {
                                val len = min(cap, pcm.size - offset)
                                val pktBytes = buildPacketBytes(seq++, config.sampleRate, config.channels, pcm, offset, len)
                                sock.send(DatagramPacket(pktBytes, pktBytes.size, addr, config.txPort))
                                offset += len
                                pkts++
                                bytes += pktBytes.size
                                _state.value = _state.value.copy(txPackets = pkts, txBytes = bytes)
                            }
                        }
                    }
                }
                _state.value = _state.value.copy(
                    isTransmitting = false,
                    statusText = "TX COMPLETE ($pkts PKTS)"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isTransmitting = false,
                    statusText = "TX ERROR: ${e.message ?: "Network error"}"
                )
            }
        }
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

        fun parseHeader(packet: ByteArray): Smp2Header? {
            if (packet.size < HEADER_SIZE) return null
            val bb = ByteBuffer.wrap(packet, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { bb.get(it) }.toString(Charsets.US_ASCII)
            if (magic != MAGIC) return null
            return Smp2Header(
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
            if (packet.size < 8) return 9000
            val bb = ByteBuffer.wrap(packet, 4, 4).order(ByteOrder.LITTLE_ENDIAN)
            return bb.int.coerceIn(1, 65535)
        }
    }
}
