package com.example.network

import com.example.audio.AudioBuffer
import com.example.audio.WavCodec
import com.example.dsp.EchoParams
import com.example.dsp.FlangerParams
import com.example.dsp.FxRackState
import com.example.dsp.GainParams
import com.example.dsp.LiveSessionMode
import com.example.dsp.NoiseRemovalParams
import com.example.dsp.OverdriveParams
import com.example.dsp.PhaserParams
import com.example.dsp.PitchSpeedParams
import com.example.dsp.DelayParams
import com.example.dsp.ChorusParams
import com.example.dsp.ReverbParams
import com.example.dsp.RotaryParams
import com.example.dsp.StompboxMasterState
import com.example.dsp.WahParams
import com.example.midi.VmcMappingRule
import com.example.midi.VmcMsgType
import com.example.midi.VmcRouteMode
import com.example.midi.VmcTarget
import com.example.ui.StudioSpoke
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire-compatible SMH2 Session Handoff Codec shared identically with Samplfie2 Linux Desktop.
 * Allows either workstation to immediately pick up song PCM, playhead, loop state, selections,
 * FX rack, Stompbox state, sequencer/looper state, and 64-channel VMC mappings from the other.
 */
data class SessionHandoffSnapshot(
    val senderName: String = "ANDROID RIG",
    val stationKey: String = "DECK",
    val bpm: Float = 124f,
    val swing: Float = 0.08f,
    val isPlaying: Boolean = false,
    val isLooping: Boolean = false,
    val playheadFraction: Float = 0f,
    val selectionStart: Float = 0f,
    val selectionEnd: Float = 1f,
    val playSelectionOnly: Boolean = false,
    val a4ReferenceHz: Double = 440.0,
    val fxRack: FxRackState = FxRackState(),
    val stompbox: StompboxMasterState = StompboxMasterState(),
    val seqLaneMasks: List<Int> = emptyList(),
    val looperActiveMask: Int = 0,
    val looperBars: List<Int> = listOf(1, 1, 1, 1),
    val vmcMappings: List<VmcMappingRule> = emptyList(),
    val buffer: AudioBuffer? = null
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
            "fx.masterBypass" to fxRack.masterBypass.toString(),
            "fx.noiseGateEnabled" to fxRack.noiseRemoval.enabled.toString(),
            "fx.noiseThresholdDb" to fxRack.noiseRemoval.thresholdDb.toString(),
            "fx.pitchEnabled" to fxRack.pitchSpeed.enabled.toString(),
            "fx.pitchSemitones" to fxRack.pitchSpeed.pitchSemitones.toString(),
            "fx.speedFactor" to fxRack.pitchSpeed.speedFactor.toString(),
            "fx.gainEnabled" to fxRack.gain.enabled.toString(),
            "fx.gainDb" to fxRack.gain.gainDb.toString(),
            "fx.normalize" to fxRack.gain.normalize.toString(),
            "fx.overdriveEnabled" to fxRack.overdrive.enabled.toString(),
            "fx.driveAmount" to fxRack.overdrive.drive.toString(),
            "fx.wahEnabled" to fxRack.wah.enabled.toString(),
            "fx.wahFreqHz" to fxRack.wah.centerFreqHz.toString(),
            "fx.phaserEnabled" to fxRack.phaser.enabled.toString(),
            "fx.phaserMix" to fxRack.phaser.mix.toString(),
            "fx.flangerEnabled" to fxRack.flanger.enabled.toString(),
            "fx.flangerMix" to fxRack.flanger.mix.toString(),
            "fx.chorusEnabled" to fxRack.chorus.enabled.toString(),
            "fx.chorusMix" to fxRack.chorus.mix.toString(),
            "fx.rotaryEnabled" to fxRack.rotarySpeaker.enabled.toString(),
            "fx.rotaryRateHz" to fxRack.rotarySpeaker.rateHz.toString(),
            "fx.echoEnabled" to fxRack.echo.enabled.toString(),
            "fx.echoDelayMs" to fxRack.echo.delayMs.toString(),
            "fx.echoFeedback" to fxRack.echo.feedback.toString(),
            "fx.echoMix" to fxRack.echo.mix.toString(),
            "fx.delayEnabled" to fxRack.delay.enabled.toString(),
            "fx.delayTimeMs" to fxRack.delay.delayMs.toString(),
            "fx.delayMix" to fxRack.delay.mix.toString(),
            "fx.reverbEnabled" to fxRack.reverb.enabled.toString(),
            "fx.reverbSize" to fxRack.reverb.roomSize.toString(),
            "fx.reverbMix" to fxRack.reverb.mix.toString(),
            "stomp.engaged" to stompbox.engaged.toString(),
            "stomp.killDrive" to stompbox.killDrive.toString(),
            "stomp.killMod" to stompbox.killMod.toString(),
            "stomp.killTime" to stompbox.killTime.toString(),
            "stomp.toneTilt" to stompbox.toneTilt.toString(),
            "stomp.dynamicsTrimDb" to stompbox.dynamicsTrimDb.toString(),
            "stomp.masterWetMix" to stompbox.masterWetMix.toString(),
            "stomp.xyPadX" to stompbox.xyPadX.toString(),
            "stomp.xyPadY" to stompbox.xyPadY.toString(),
            "stomp.liveMode" to stompbox.liveMode.name,
            "seqLaneMasks" to seqLaneMasks.joinToString(","),
            "looperActiveMask" to looperActiveMask.toString(),
            "looperBars" to looperBars.joinToString(","),
            "vmcMappings" to vmcMappings.joinToString(";") { r ->
                "${r.id}|${r.channel64}|${r.msgType.name}|${r.number}|${r.target.name}|${r.routeMode.name}"
            },
            "buf.name" to sanitize(buffer?.name ?: ""),
            "buf.sampleRate" to (buffer?.sampleRate ?: 44100).toString(),
            "buf.channels" to (buffer?.channels ?: 1).toString()
        )
        val metaBytes = props.entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray(Charsets.UTF_8)
        val pcmBytes = buffer?.let { WavCodec.floatToPcm16Bytes(it.samples) } ?: ByteArray(0)
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

        fun mapStationKeyToAndroid(key: String): StudioSpoke = when (key.uppercase()) {
            "BOOTH" -> StudioSpoke.BOOTH
            "DECK", "EDITOR" -> StudioSpoke.DECK
            "SAMPLES" -> StudioSpoke.SAMPLES
            "INSTRUMENTS", "INST" -> StudioSpoke.INSTRUMENTS
            "TRACKS" -> StudioSpoke.TRACKS
            "FX_RACK", "FX_STOMP" -> StudioSpoke.FX_RACK
            "LIVE", "NET_LINK" -> StudioSpoke.LIVE
            "TUNER" -> StudioSpoke.TUNER
            "VMC" -> StudioSpoke.VMC
            else -> StudioSpoke.DECK
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
                if (eq > 0) map[line.substring(0, eq)] = line.substring(eq + 1)
            }
            val pcmLen = wire.size - 4 - metaLen
            val buf = if (pcmLen >= 2) {
                val pcmBytes = wire.copyOfRange(4 + metaLen, wire.size)
                val floats = WavCodec.pcm16BytesToFloats(pcmBytes)
                val sr = map["buf.sampleRate"]?.toIntOrNull() ?: 44100
                val ch = (map["buf.channels"]?.toIntOrNull() ?: 1).coerceIn(1, 2)
                val nm = map["buf.name"]?.ifBlank { "HANDOFF_SONG" } ?: "HANDOFF_SONG"
                AudioBuffer(floats, sr, ch, nm)
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

            val fx = FxRackState(
                masterBypass = b("fx.masterBypass", false),
                noiseRemoval = NoiseRemovalParams(
                    enabled = b("fx.noiseGateEnabled", false),
                    thresholdDb = f("fx.noiseThresholdDb", -38f)
                ),
                pitchSpeed = PitchSpeedParams(
                    enabled = b("fx.pitchEnabled", false),
                    pitchSemitones = f("fx.pitchSemitones", 0f),
                    speedFactor = f("fx.speedFactor", 1f)
                ),
                gain = GainParams(
                    enabled = b("fx.gainEnabled", false),
                    gainDb = f("fx.gainDb", 0f),
                    normalize = b("fx.normalize", false)
                ),
                overdrive = OverdriveParams(
                    enabled = b("fx.overdriveEnabled", false),
                    drive = f("fx.driveAmount", 4f)
                ),
                wah = WahParams(
                    enabled = b("fx.wahEnabled", false),
                    centerFreqHz = f("fx.wahFreqHz", 950f)
                ),
                phaser = PhaserParams(
                    enabled = b("fx.phaserEnabled", false),
                    mix = f("fx.phaserMix", 0.6f)
                ),
                flanger = FlangerParams(
                    enabled = b("fx.flangerEnabled", false),
                    mix = f("fx.flangerMix", 0.55f)
                ),
                chorus = ChorusParams(
                    enabled = b("fx.chorusEnabled", false),
                    mix = f("fx.chorusMix", 0.5f)
                ),
                rotarySpeaker = RotaryParams(
                    enabled = b("fx.rotaryEnabled", false),
                    rateHz = f("fx.rotaryRateHz", 3.8f)
                ),
                echo = EchoParams(
                    enabled = b("fx.echoEnabled", false),
                    delayMs = f("fx.echoDelayMs", 320f),
                    feedback = f("fx.echoFeedback", 0.45f),
                    mix = f("fx.echoMix", 0.45f)
                ),
                delay = DelayParams(
                    enabled = b("fx.delayEnabled", false),
                    delayMs = f("fx.delayTimeMs", 240f),
                    mix = f("fx.delayMix", 0.4f)
                ),
                reverb = ReverbParams(
                    enabled = b("fx.reverbEnabled", false),
                    roomSize = f("fx.reverbSize", 0.65f),
                    mix = f("fx.reverbMix", 0.35f)
                )
            )

            val stomp = StompboxMasterState(
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
                    LiveSessionMode.valueOf(map["stomp.liveMode"] ?: "STAGE_DJ")
                }.getOrDefault(LiveSessionMode.STAGE_DJ)
            )

            return SessionHandoffSnapshot(
                senderName = map["senderName"] ?: "PEER",
                stationKey = map["stationKey"] ?: "DECK",
                bpm = f("bpm", 124f),
                swing = f("swing", 0.08f),
                isPlaying = b("isPlaying", false),
                isLooping = b("isLooping", false),
                playheadFraction = f("playheadFraction", 0f),
                selectionStart = f("selectionStart", 0f),
                selectionEnd = f("selectionEnd", 1f),
                playSelectionOnly = b("playSelectionOnly", false),
                a4ReferenceHz = d("a4ReferenceHz", 440.0),
                fxRack = fx,
                stompbox = stomp,
                seqLaneMasks = map["seqLaneMasks"]?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList(),
                looperActiveMask = map["looperActiveMask"]?.toIntOrNull() ?: 0,
                looperBars = map["looperBars"]?.split(",")?.mapNotNull { it.toIntOrNull() } ?: listOf(1, 1, 1, 1),
                vmcMappings = vmcList,
                buffer = buf
            )
        }
    }
}
