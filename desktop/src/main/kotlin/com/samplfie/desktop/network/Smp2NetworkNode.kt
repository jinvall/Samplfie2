package com.samplfie.desktop.network

import com.samplfie.desktop.audio.DesktopWavCodec
import com.samplfie.desktop.model.DesktopAudioBuffer
import com.samplfie.desktop.model.DesktopRigConfig
import com.samplfie.desktop.model.NetworkProtocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    val statusText: String = "IDLE — READY FOR SMP2 TCP/UDP STREAM",
    val rxReadyBuffer: DesktopAudioBuffer? = null
)

/**
 * Bidirectional SMP2 network audio bridge compatible with Samplfie2 Android's NetworkAudioTransmitter.
 * Receives live 20-byte SMP2-framed PCM streams over TCP or UDP for editing & tuning on Linux desktop,
 * and transmits desktop audio back out over TCP or UDP.
 */
class Smp2NetworkNode(
    private val onStreamChunkForTuner: (FloatArray, Int) -> Unit = { _, _ -> }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenJob: Job? = null
    private var txJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var udpRxSocket: DatagramSocket? = null

    private val pcmAccumulator = ByteArrayOutputStream()
    private val accumLock = Any()

    private val _state = MutableStateFlow(NetBridgeState())
    val state: StateFlow<NetBridgeState> = _state.asStateFlow()

    fun startListening(protocol: NetworkProtocol, port: Int) {
        stopListening()
        synchronized(accumLock) { pcmAccumulator.reset() }
        _state.value = _state.value.copy(
            isListening = true,
            rxPackets = 0L,
            rxBytes = 0L,
            statusText = "LISTENING ON ${protocol.label} :$port (SMP2)"
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
        val buf = ByteArray(20480)
        sock.use { s ->
            while (listenJob?.isActive == true && !s.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                if (pkt.length >= HEADER_SIZE) {
                    val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                    processIncomingPacket(data)
                }
            }
        }
    }

    private fun listenTcp(port: Int) {
        val srv = ServerSocket(port).also { serverSocket = it }
        srv.use { server ->
            while (listenJob?.isActive == true && !server.isClosed) {
                val client = server.accept()
                client.use { sock ->
                    val input = BufferedInputStream(sock.getInputStream())
                    while (listenJob?.isActive == true) {
                        val headerBytes = readExact(input, HEADER_SIZE) ?: break
                        val header = parseHeader(headerBytes) ?: continue
                        val payload = readExact(input, header.payloadBytes) ?: break
                        ingestPayload(header, payload)
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

    private fun processIncomingPacket(packet: ByteArray) {
        val header = parseHeader(packet) ?: return
        val actualLen = min(header.payloadBytes, packet.size - HEADER_SIZE)
        if (actualLen <= 0) return
        val payload = packet.copyOfRange(HEADER_SIZE, HEADER_SIZE + actualLen)
        ingestPayload(header, payload)
    }

    private fun ingestPayload(header: Smp2Header, payload: ByteArray) {
        synchronized(accumLock) {
            // Cap live ring at ~30s of 48kHz stereo (~5.7MB)
            if (pcmAccumulator.size() > 6_000_000) {
                pcmAccumulator.reset()
            }
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
    }
}
