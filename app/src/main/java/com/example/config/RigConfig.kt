package com.example.config

enum class NetworkProtocol(val label: String) {
    TCP("TCP"),
    UDP("UDP")
}

data class RigConfig(
    val ipAddress: String = "192.168.1.100",
    val port: Int = 9000,
    val protocol: NetworkProtocol = NetworkProtocol.UDP,
    val sampleRate: Int = 44100,
    val channels: Int = 1,
    val bufferSize: Int = 1024,
    val mp3BitrateKbps: Int = 192
) {
    fun isValidPort(): Boolean = port in 1..65535

    fun isValidIpOrHost(): Boolean {
        val trimmed = ipAddress.trim()
        if (trimmed.isEmpty()) return false
        val ipv4Regex = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
        val hostRegex = Regex("^[a-zA-Z0-9.-]{1,253}$")
        return ipv4Regex.matches(trimmed) || hostRegex.matches(trimmed)
    }

    companion object {
        val SUPPORTED_SAMPLE_RATES = listOf(22050, 44100, 48000, 96000)
        val SUPPORTED_CHANNELS = listOf(1, 2)
        val SUPPORTED_BUFFERS = listOf(256, 512, 1024, 2048, 4096)
        val SUPPORTED_MP3_BITRATES = listOf(128, 192, 256, 320)
    }
}
