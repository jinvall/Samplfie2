package com.samplfie.desktop.midi

import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage

/**
 * Native Linux JVM MidiSystem interface supporting multi-bus hardware/ALSA MIDI ports mapped into 64 virtual channels.
 * Gracefully degrades if no ALSA MIDI sequencer or ports exist in headless Linux environments.
 */
class DesktopHardwareMidiManager(
    private val onMidiEvent: (VmcEventPacket) -> Unit
) {
    private val openedDevices = mutableListOf<MidiDevice>()
    private val openedOutputs = mutableListOf<Receiver>()

    private var cachedInputs: List<String> = emptyList()
    private var cachedOutputs: List<String> = emptyList()

    fun refreshAndBindAll(): Pair<List<String>, List<String>> {
        closeAll()
        val ins = mutableListOf<String>()
        val outs = mutableListOf<String>()

        runCatching {
            val infos = MidiSystem.getMidiDeviceInfo()
            var inputBus = 0
            infos.forEach { info ->
                runCatching {
                    val dev = MidiSystem.getMidiDevice(info)
                    if (dev.maxTransmitters != 0) {
                        val busIdx = (inputBus % 4)
                        inputBus++
                        dev.open()
                        dev.transmitter.receiver = object : Receiver {
                            override fun send(message: MidiMessage, timeStamp: Long) {
                                parseMidiMessage(message, busIdx)?.let(onMidiEvent)
                            }
                            override fun close() {}
                        }
                        openedDevices.add(dev)
                        ins.add("${info.name} (Bus ${busIdx + 1})")
                    }
                    if (dev.maxReceivers != 0 && info.name != "Real Time Sequencer") {
                        if (!dev.isOpen) dev.open()
                        openedOutputs.add(dev.receiver)
                        if (!openedDevices.contains(dev)) openedDevices.add(dev)
                        outs.add(info.name)
                    }
                }
            }
        }
        cachedInputs = ins
        cachedOutputs = outs
        return ins to outs
    }

    fun sendToHardware(pkt: VmcEventPacket) {
        if (openedOutputs.isEmpty()) return
        val ch16 = ((pkt.channel64 - 1).coerceIn(0, 63)) % 16
        val msg = runCatching {
            when (pkt.msgType) {
                VmcMsgType.NOTE_ON -> ShortMessage(ShortMessage.NOTE_ON, ch16, pkt.data1.coerceIn(0, 127), pkt.data2.coerceIn(1, 127))
                VmcMsgType.NOTE_OFF -> ShortMessage(ShortMessage.NOTE_OFF, ch16, pkt.data1.coerceIn(0, 127), 0)
                VmcMsgType.CC -> ShortMessage(ShortMessage.CONTROL_CHANGE, ch16, pkt.data1.coerceIn(0, 127), pkt.data2.coerceIn(0, 127))
                VmcMsgType.PITCH_BEND -> {
                    val raw = (pkt.normalized.coerceIn(0f, 1f) * 16383f).toInt()
                    ShortMessage(ShortMessage.PITCH_BEND, ch16, raw and 0x7F, (raw shr 7) and 0x7F)
                }
                VmcMsgType.TRANSPORT_START -> ShortMessage(ShortMessage.START)
                VmcMsgType.TRANSPORT_STOP -> ShortMessage(ShortMessage.STOP)
            }
        }.getOrNull() ?: return

        openedOutputs.forEach { rx ->
            runCatching { rx.send(msg, -1L) }
        }
    }

    fun closeAll() {
        openedOutputs.forEach { runCatching { it.close() } }
        openedOutputs.clear()
        openedDevices.forEach { runCatching { if (it.isOpen) it.close() } }
        openedDevices.clear()
    }

    companion object {
        fun parseMidiMessage(message: MidiMessage, busIndex0to3: Int = 0): VmcEventPacket? {
            if (message !is ShortMessage) return null
            val bus = busIndex0to3.coerceIn(0, 3)
            val ch64 = (bus * 16) + (message.channel and 0x0F) + 1
            return when (message.command) {
                ShortMessage.NOTE_ON -> {
                    val vel = message.data2
                    if (vel > 0) VmcEventPacket(ch64, VmcMsgType.NOTE_ON, message.data1, vel, vel / 127f)
                    else VmcEventPacket(ch64, VmcMsgType.NOTE_OFF, message.data1, 0, 0f)
                }
                ShortMessage.NOTE_OFF -> VmcEventPacket(ch64, VmcMsgType.NOTE_OFF, message.data1, 0, 0f)
                ShortMessage.CONTROL_CHANGE -> VmcEventPacket(ch64, VmcMsgType.CC, message.data1, message.data2, message.data2 / 127f)
                ShortMessage.PITCH_BEND -> {
                    val raw = ((message.data2 and 0x7F) shl 7) or (message.data1 and 0x7F)
                    VmcEventPacket(ch64, VmcMsgType.PITCH_BEND, 0, message.data2, (raw / 16383f).coerceIn(0f, 1f))
                }
                else -> when (message.status) {
                    ShortMessage.START -> VmcEventPacket(ch64, VmcMsgType.TRANSPORT_START, 0, 127, 1f)
                    ShortMessage.STOP -> VmcEventPacket(ch64, VmcMsgType.TRANSPORT_STOP, 0, 0, 0f)
                    else -> null
                }
            }
        }
    }
}
