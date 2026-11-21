package com.example.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.rigDataStore: DataStore<Preferences> by preferencesDataStore(name = "samplfie2_rig_prefs")

class RigConfigRepository(private val context: Context) {

    private object Keys {
        val IP_ADDRESS = stringPreferencesKey("ip_address")
        val PORT = intPreferencesKey("port")
        val BIND_PORT = intPreferencesKey("bind_port")
        val PROTOCOL = stringPreferencesKey("protocol")
        val SAMPLE_RATE = intPreferencesKey("sample_rate")
        val CHANNELS = intPreferencesKey("channels")
        val BUFFER_SIZE = intPreferencesKey("buffer_size")
        val MP3_BITRATE = intPreferencesKey("mp3_bitrate")
        val A4_REF_HZ = doublePreferencesKey("a4_ref_hz")
        val NODE_NAME = stringPreferencesKey("node_name")
        val AUTO_RESUME_HANDOFF = booleanPreferencesKey("auto_resume_handoff")
        val AUTO_ACCEPT_HANDOFF = booleanPreferencesKey("auto_accept_handoff")
    }

    val configFlow: Flow<RigConfig> = context.rigDataStore.data.map { prefs ->
        val protoName = prefs[Keys.PROTOCOL] ?: NetworkProtocol.UDP.name
        val protocol = NetworkProtocol.entries.firstOrNull { it.name == protoName } ?: NetworkProtocol.UDP
        RigConfig(
            ipAddress = prefs[Keys.IP_ADDRESS] ?: "192.168.1.100",
            port = (prefs[Keys.PORT] ?: 9000).coerceIn(1, 65535),
            bindPort = (prefs[Keys.BIND_PORT] ?: 9001).coerceIn(1, 65535),
            protocol = protocol,
            sampleRate = prefs[Keys.SAMPLE_RATE]?.takeIf { it in RigConfig.SUPPORTED_SAMPLE_RATES } ?: 44100,
            channels = prefs[Keys.CHANNELS]?.takeIf { it in RigConfig.SUPPORTED_CHANNELS } ?: 1,
            bufferSize = prefs[Keys.BUFFER_SIZE]?.takeIf { it in RigConfig.SUPPORTED_BUFFERS } ?: 1024,
            mp3BitrateKbps = prefs[Keys.MP3_BITRATE]?.takeIf { it in RigConfig.SUPPORTED_MP3_BITRATES } ?: 192,
            a4ReferenceHz = (prefs[Keys.A4_REF_HZ] ?: 440.0).coerceIn(430.0, 450.0),
            nodeName = prefs[Keys.NODE_NAME] ?: "ANDROID WORKSTATION",
            autoResumeOnHandoff = prefs[Keys.AUTO_RESUME_HANDOFF] ?: true,
            autoAcceptIncomingHandoff = prefs[Keys.AUTO_ACCEPT_HANDOFF] ?: true
        )
    }

    suspend fun saveConfig(config: RigConfig) {
        context.rigDataStore.edit { prefs ->
            prefs[Keys.IP_ADDRESS] = config.ipAddress.trim()
            prefs[Keys.PORT] = config.port.coerceIn(1, 65535)
            prefs[Keys.BIND_PORT] = config.bindPort.coerceIn(1, 65535)
            prefs[Keys.PROTOCOL] = config.protocol.name
            prefs[Keys.SAMPLE_RATE] = config.sampleRate
            prefs[Keys.CHANNELS] = config.channels
            prefs[Keys.BUFFER_SIZE] = config.bufferSize
            prefs[Keys.MP3_BITRATE] = config.mp3BitrateKbps
            prefs[Keys.A4_REF_HZ] = config.a4ReferenceHz.coerceIn(430.0, 450.0)
            prefs[Keys.NODE_NAME] = config.nodeName
            prefs[Keys.AUTO_RESUME_HANDOFF] = config.autoResumeOnHandoff
            prefs[Keys.AUTO_ACCEPT_HANDOFF] = config.autoAcceptIncomingHandoff
        }
    }
}
