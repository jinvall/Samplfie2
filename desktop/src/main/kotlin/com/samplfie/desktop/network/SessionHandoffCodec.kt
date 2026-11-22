package com.samplfie.desktop.network

import com.samplfie.desktop.audio.DesktopWavCodec
import com.samplfie.desktop.midi.VmcMappingRule
import com.samplfie.desktop.midi.VmcMsgType
import com.samplfie.desktop.midi.VmcRouteMode
import com.samplfie.desktop.midi.VmcTarget
import com.samplfie.desktop.model.DesktopAudioBuffer
import com.samplfie.desktop.model.DesktopFxState
import com.samplfie.desktop.model.DesktopLiveMode
import com.samplfie.desktop.model.DesktopStation
import com.samplfie.desktop.model.DesktopStompState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Portable wire snapshot shared symmetrically between Samplfie2 Android and Samplfie2 Linux Desktop
 * so either workstation can immediately pick up exact song, loop, selection, playhead, FX, Stompbox,
 * sequencer/looper, and 64-channel VMC mappings from the other over TCP/UDP.
 */
data class SessionHandoffSnapshot(
    val senderName: String = "LINUX DESKTOP",
    val stationKey: String = "EDITOR",
    val bpm: Float = 124f,
    val swing: Float = 0.08f,
    val isPlaying: Boolean = false,
    val isLooping: Boolean = false,
    val playheadFraction: Float = 0f,
    val selectionStart: Float = 0f,
    val selectionEnd: Float = 1f,
    val playSelectionOnly: Boolean = false,
    val a4ReferenceHz: Double = 440.0,
    val fxState: DesktopFxState = DesktopFxState(),
    val stompState: DesktopStompState = DesktopStompState(),
    val seqLaneMasks: List<Int> = emptyList(),
    val looperActiveMask: Int = 0,
    val looperBars: List<Int> = listOf(1, 1, 1, 1),
    val vmcMappings: List<VmcMappingRule> = emptyList(),
    val masterVolume: Float = 0.85f,
    val isMasterMuted: Boolean = false,
    val buffer: DesktopAudioBuffer? = null
) {
    fun encodeToWireBytes(): ByteArray {
        val props = linkedMapOf(
            "senderName" to sanitize(senderName),
            "stationKey" to stationKey,
            "bpm" to bpm.toString(),
            "swing" to swing.toString(),
            "isPlaying" to isPlaying.toString(),
            "isLooping" to isLooping.toString(),
            "playheadFraction" to playheadFraction.toString(),
            "selectionStart" to selectionStart.toString(),
            "selectionEnd" to selectionEnd.toString(),
            "playSelectionOnly" to playSelectionOnly.toString(),
            "a4ReferenceHz" to a4ReferenceHz.toString(),
            "fx.masterBypass" to fxState.masterBypass.toString(),
            "fx.noiseGateEnabled" to fxState.noiseGateEnabled.toString(),
            "fx.noiseThresholdDb" to fxState.noiseThresholdDb.toString(),
            "fx.pitchEnabled" to fxState.pitchEnabled.toString(),
            "fx.pitchSemitones" to fxState.pitchSemitones.toString(),
            "fx.speedFactor" to fxState.speedFactor.toString(),
            "fx.gainEnabled" to fxState.gainEnabled.toString(),
            "fx.gainDb" to fxState.gainDb.toString(),
            "fx.normalize" to fxState.normalize.toString(),
            "fx.overdriveEnabled" to fxState.overdriveEnabled.toString(),
            "fx.driveAmount" to fxState.driveAmount.toString(),
            "fx.wahEnabled" to fxState.wahEnabled.toString(),
            "fx.wahFreqHz" to fxState.wahFreqHz.toString(),
            "fx.phaserEnabled" to fxState.phaserEnabled.toString(),
            "fx.phaserMix" to fxState.phaserMix.toString(),
            "fx.flangerEnabled" to fxState.flangerEnabled.toString(),
            "fx.flangerMix" to fxState.flangerMix.toString(),
            "fx.chorusEnabled" to fxState.chorusEnabled.toString(),
            "fx.chorusMix" to fxState.chorusMix.toString(),
            "fx.rotaryEnabled" to fxState.rotaryEnabled.toString(),
            "fx.rotaryRateHz" to fxState.rotaryRateHz.toString(),
            "fx.echoEnabled" to fxState.echoEnabled.toString(),
            "fx.echoDelayMs" to fxState.echoDelayMs.toString(),
            "fx.echoFeedback" to fxState.echoFeedback.toString(),
            "fx.echoMix" to fxState.echoMix.toString(),
            "fx.delayEnabled" to fxState.delayEnabled.toString(),
            "fx.delayTimeMs" to fxState.delayTimeMs.toString(),
            "fx.delayMix" to fxState.delayMix.toString(),
            "fx.reverbEnabled" to fxState.reverbEnabled.toString(),
            "fx.reverbSize" to fxState.reverbSize.toString(),
            "fx.reverbMix" to fxState.reverbMix.toString(),
            "stomp.engaged" to stompState.engaged.toString(),
            "stomp.killDrive" to stompState.killDrive.toString(),
            "stomp.killMod" to stompState.killMod.toString(),
            "stomp.killTime" to stompState.killTime.toString(),
            "stomp.toneTilt" to stompState.toneTilt.toString(),
            "stomp.dynamicsTrimDb" to stompState.dynamicsTrimDb.toString(),
            "stomp.masterWetMix" to stompState.masterWetMix.toString(),
            "stomp.xyPadX" to stompState.xyPadX.toString(),
            "stomp.xyPadY" to stompState.xyPadY.toString(),
            "stomp.liveMode" to stompState.liveMode.name,
            "seqLaneMasks" to seqLaneMasks.joinToString(","),
            "looperActiveMask" to looperActiveMask.toString(),
            "looperBars" to looperBars.joinToString(","),
            "vmcMappings" to vmcMappings.joinToString(";") { r ->
                "${r.id}|${r.channel64}|${r.msgType.name}|${r.number}|${r.target.name}|${r.routeMode.name}"
            },
            "master.volume" to masterVolume.toString(),
            "master.muted" to isMasterMuted.toString(),
            "buf.name" to sanitize(buffer?.name ?: ""),
            "buf.sampleRate" to (buffer?.sampleRate ?: 44100).toString(),
            "buf.channels" to (buffer?.channels ?: 1).toString()
        )
        val metaBytes = props.entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray(Charsets.UTF_8)
        val pcmBytes = buffer?.let { DesktopWavCodec.floatToPcm16Bytes(it.samples) } ?: ByteArray(0)
        val out = ByteArray(4 + metaBytes.size + pcmBytes.size)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(metaBytes.size)
        bb.put(metaBytes)
        bb.put(pcmBytes)
        return out
    }

    companion object {
        private fun sanitize(v: String): String =
            v.replace("\n", " ").replace("=", ":").replace(";", ",").replace("|", "/")

        fun mapStationKeyToDesktop(key: String): DesktopStation = when (key.uppercase()) {
            "BOOTH" -> DesktopStation.BOOTH
            "DECK", "EDITOR" -> DesktopStation.EDITOR
            "SAMPLES" -> DesktopStation.SAMPLES
            "INSTRUMENTS", "INST" -> DesktopStation.INSTRUMENTS
            "TRACKS" -> DesktopStation.TRACKS
            "FX_RACK", "FX_STOMP" -> DesktopStation.FX_STOMP
            "LIVE" -> DesktopStation.LIVE
            "TUNER" -> DesktopStation.TUNER
            "VMC" -> DesktopStation.VMC
            "NET_LINK" -> DesktopStation.NET_LINK
            else -> DesktopStation.EDITOR
        }

        fun decodeFromWireBytes(wire: ByteArray): SessionHandoffSnapshot? {
            if (wire.size < 4) return null
            val bb = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN)
            val metaLen = bb.int
            if (metaLen <= 0 || metaLen > wire.size - 4) return null
            val metaStr = String(wire, 4, metaLen, Charsets.UTF_8)
            val map = mutableMapOf<String, String>()
            metaStr.lineSequence().forEach { line ->
                val eq = line.indexOf('=')
                if (eq > 0) {
                    map[line.substring(0, eq)] = line.substring(eq + 1)
                }
            }
            val pcmLen = wire.size - 4 - metaLen
            val buf = if (pcmLen >= 2) {
                val pcmBytes = wire.copyOfRange(4 + metaLen, wire.size)
                val floats = DesktopWavCodec.pcm16BytesToFloats(pcmBytes)
                val sr = map["buf.sampleRate"]?.toIntOrNull() ?: 44100
                val ch = (map["buf.channels"]?.toIntOrNull() ?: 1).coerceIn(1, 2)
                val nm = map["buf.name"]?.ifBlank { "HANDOFF_SONG" } ?: "HANDOFF_SONG"
                DesktopAudioBuffer(floats, sr, ch, nm)
            } else {
                null
            }

            fun b(k: String, d: Boolean) = map[k]?.toBooleanStrictOrNull() ?: d
            fun f(k: String, d: Float) = map[k]?.toFloatOrNull() ?: d
            fun d(k: String, def: Double) = map[k]?.toDoubleOrNull() ?: def

            val vmcList = map["vmcMappings"]?.split(";")?.mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size < 6) return@mapNotNull null
                val type = runCatching { VmcMsgType.valueOf(parts[2]) }.getOrNull() ?: return@mapNotNull null
                val target = runCatching { VmcTarget.valueOf(parts[4]) }.getOrNull() ?: return@mapNotNull null
                val route = runCatching { VmcRouteMode.valueOf(parts[5]) }.getOrDefault(VmcRouteMode.LOCAL_AND_PEER)
                VmcMappingRule(
                    id = parts[0],
                    channel64 = parts[1].toIntOrNull()?.coerceIn(0, 64) ?: 1,
                    msgType = type,
                    number = parts[3].toIntOrNull()?.coerceIn(0, 127) ?: 0,
                    target = target,
                    routeMode = route
                )
            } ?: emptyList()

            val fx = DesktopFxState(
                masterBypass = b("fx.masterBypass", false),
                noiseGateEnabled = b("fx.noiseGateEnabled", false),
                noiseThresholdDb = f("fx.noiseThresholdDb", -46f),
                pitchEnabled = b("fx.pitchEnabled", false),
                pitchSemitones = f("fx.pitchSemitones", 0f),
                speedFactor = f("fx.speedFactor", 1f),
                gainEnabled = b("fx.gainEnabled", false),
                gainDb = f("fx.gainDb", 0f),
                normalize = b("fx.normalize", false),
                overdriveEnabled = b("fx.overdriveEnabled", false),
                driveAmount = f("fx.driveAmount", 4f),
                wahEnabled = b("fx.wahEnabled", false),
                wahFreqHz = f("fx.wahFreqHz", 950f),
                phaserEnabled = b("fx.phaserEnabled", false),
                phaserMix = f("fx.phaserMix", 0.5f),
                flangerEnabled = b("fx.flangerEnabled", false),
                flangerMix = f("fx.flangerMix", 0.5f),
                chorusEnabled = b("fx.chorusEnabled", false),
                chorusMix = f("fx.chorusMix", 0.45f),
                rotaryEnabled = b("fx.rotaryEnabled", false),
                rotaryRateHz = f("fx.rotaryRateHz", 3.2f),
                echoEnabled = b("fx.echoEnabled", false),
                echoDelayMs = f("fx.echoDelayMs", 240f),
                echoFeedback = f("fx.echoFeedback", 0.4f),
                echoMix = f("fx.echoMix", 0.35f),
                delayEnabled = b("fx.delayEnabled", false),
                delayTimeMs = f("fx.delayTimeMs", 360f),
                delayMix = f("fx.delayMix", 0.3f),
                reverbEnabled = b("fx.reverbEnabled", false),
                reverbSize = f("fx.reverbSize", 0.6f),
                reverbMix = f("fx.reverbMix", 0.35f)
            )

            val stomp = DesktopStompState(
                engaged = b("stomp.engaged", true),
                killDrive = b("stomp.killDrive", false),
                killMod = b("stomp.killMod", false),
                killTime = b("stomp.killTime", false),
                toneTilt = f("stomp.toneTilt", 0f),
                dynamicsTrimDb = f("stomp.dynamicsTrimDb", 0f),
                masterWetMix = f("stomp.masterWetMix", 1f),
                xyPadX = f("stomp.xyPadX", 0.5f),
                xyPadY = f("stomp.xyPadY", 0.5f),
                liveMode = runCatching {
                    DesktopLiveMode.valueOf(map["stomp.liveMode"] ?: "STAGE_DJ")
                }.getOrDefault(DesktopLiveMode.STAGE_DJ)
            )

            return SessionHandoffSnapshot(
                senderName = map["senderName"] ?: "PEER",
                stationKey = map["stationKey"] ?: "EDITOR",
                bpm = f("bpm", 124f),
                swing = f("swing", 0.08f),
                isPlaying = b("isPlaying", false),
                isLooping = b("isLooping", false),
                playheadFraction = f("playheadFraction", 0f),
                selectionStart = f("selectionStart", 0f),
                selectionEnd = f("selectionEnd", 1f),
                playSelectionOnly = b("playSelectionOnly", false),
                a4ReferenceHz = d("a4ReferenceHz", 440.0),
                fxState = fx,
                stompState = stomp,
                seqLaneMasks = map["seqLaneMasks"]?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList(),
                looperActiveMask = map["looperActiveMask"]?.toIntOrNull() ?: 0,
                looperBars = map["looperBars"]?.split(",")?.mapNotNull { it.toIntOrNull() } ?: listOf(1, 1, 1, 1),
                vmcMappings = vmcList,
                masterVolume = f("master.volume", 0.85f).coerceIn(0f, 1f),
                isMasterMuted = b("master.muted", false),
                buffer = buf
            )
        }
    }
}
