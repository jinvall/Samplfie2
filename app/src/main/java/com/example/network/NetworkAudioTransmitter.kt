package com.example.network

import com.example.audio.AudioBuffer
import com.example.audio.WavCodec
import com.example.config.NetworkProtocol
import com.example.config.RigConfig
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
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

data class NetworkLinkState(
    val isTransmitting: Boolean = false,
    val packetsSent: Long = 0L,
    val bytesSent: Long = 0L,
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
 * Real TCP (Socket) and UDP (DatagramSocket) audio packet streamer.
 * Converts the active AudioBuffer to the configured SampleRate, Channels, and Buffer Frame Size
 * and transmits SMP2-framed 16-bit PCM packets to the configured IP Address and Port.
 */
class NetworkAudioTransmitter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var txJob: Job? = null

    private val _state = MutableStateFlow(NetworkLinkState())
    val state: StateFlow<NetworkLinkState> = _state.asStateFlow()

    fun transmitBuffer(buffer: AudioBuffer, config: RigConfig) {
        if (!config.isValidIpOrHost() || !config.isValidPort()) {
            _state.value = NetworkLinkState(
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

            _state.value = NetworkLinkState(
                isTransmitting = true,
                packetsSent = 0L,
                bytesSent = 0L,
                activeProtocol = config.protocol,
                targetEndpoint = endpoint,
                statusMessage = "STREAMING ${config.protocol.label} -> $endpoint"
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
                            // Cap individual UDP payload under typical 1500/8192 MTU safety if needed,
                            // respecting configured buffer size up to 16384 bytes
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
    }
}
