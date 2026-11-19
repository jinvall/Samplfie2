package com.example.midi

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class MidiEvent {
    data class NoteOn(val channel: Int, val note: Int, val velocity: Int) : MidiEvent()
    data class NoteOff(val channel: Int, val note: Int) : MidiEvent()
    data class ControlChange(val channel: Int, val controller: Int, val value: Int) : MidiEvent()
    data class PitchBend(val channel: Int, val normalized: Float) : MidiEvent()
    data object ClockTick : MidiEvent()
    data object TransportStart : MidiEvent()
    data object TransportStop : MidiEvent()
}

data class MidiDeviceSummary(
    val id: Int,
    val name: String,
    val manufacturer: String,
    val inputPortCount: Int,
    val outputPortCount: Int
)

data class MidiState(
    val isSupported: Boolean = false,
    val connectedDevices: List<MidiDeviceSummary> = emptyList(),
    val recentLog: List<String> = emptyList(),
    val syncedBpm: Float? = null,
    val lastMappedAction: String = "READY"
)

/**
 * Real Android MidiManager controller supporting hardware/virtual MIDI input/output ports,
 * MIDI Clock BPM detection, Note triggers, Pitch Bend, and CC parameter mapping.
 */
class MidiControllerManager(context: Context) {
    private val appContext = context.applicationContext
    private val isMidiSupported = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)
    private val midiManager: MidiManager? = if (isMidiSupported) {
        appContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    } else {
        null
    }

    private val openDevices = mutableMapOf<Int, MidiDevice>()
    private val openOutputPorts = mutableListOf<MidiOutputPort>()
    private var activeInputPort: MidiInputPort? = null

    private var clockTickTimes = ArrayDeque<Long>(24)

    private val _state = MutableStateFlow(MidiState(isSupported = isMidiSupported && midiManager != null))
    val state: StateFlow<MidiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<MidiEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<MidiEvent> = _events.asSharedFlow()

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            refreshDevices()
            connectDevice(device)
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            openDevices.remove(device.id)?.close()
            refreshDevices()
        }
    }

    fun start() {
        val mgr = midiManager ?: return
        val handler = Handler(Looper.getMainLooper())
        @Suppress("DEPRECATION")
        mgr.registerDeviceCallback(deviceCallback, handler)
        refreshDevices()
        @Suppress("DEPRECATION")
        mgr.devices.forEach { connectDevice(it) }
    }

    fun refreshDevices() {
        val mgr = midiManager ?: return
        @Suppress("DEPRECATION")
        val summaries = mgr.devices.map { info ->
            val props = info.properties
            MidiDeviceSummary(
                id = info.id,
                name = props.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI Device #${info.id}",
                manufacturer = props.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: "Generic",
                inputPortCount = info.inputPortCount,
                outputPortCount = info.outputPortCount
            )
        }
        _state.value = _state.value.copy(connectedDevices = summaries)
    }

    private fun connectDevice(info: MidiDeviceInfo) {
        val mgr = midiManager ?: return
        if (openDevices.containsKey(info.id)) return
        val handler = Handler(Looper.getMainLooper())
        mgr.openDevice(info, { device ->
            if (device == null) return@openDevice
            openDevices[info.id] = device
            // Device output ports send MIDI INTO our app
            for (i in 0 until info.outputPortCount) {
                val outPort = device.openOutputPort(i) ?: continue
                outPort.connect(object : MidiReceiver() {
                    override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        val parsed = MidiMessageParser.parse(msg, offset, count)
                        parsed.forEach { handleParsedEvent(it) }
                    }
                })
                openOutputPorts.add(outPort)
            }
            // Device input ports allow our app to send MIDI OUT
            if (activeInputPort == null && info.inputPortCount > 0) {
                activeInputPort = device.openInputPort(0)
            }
        }, handler)
    }

    fun sendNoteOut(channel: Int, note: Int, velocity: Int) {
        val port = activeInputPort ?: return
        try {
            val status = (0x90 or (channel and 0x0F)).toByte()
            val bytes = byteArrayOf(status, (note and 0x7F).toByte(), (velocity and 0x7F).toByte())
            port.send(bytes, 0, bytes.size)
        } catch (_: Exception) {
        }
    }

    fun handleParsedEvent(event: MidiEvent) {
        _events.tryEmit(event)
        val label = when (event) {
            is MidiEvent.NoteOn -> "NOTE ON ch${event.channel + 1} #${event.note} v${event.velocity}"
            is MidiEvent.NoteOff -> "NOTE OFF ch${event.channel + 1} #${event.note}"
            is MidiEvent.ControlChange -> "CC ch${event.channel + 1} #${event.controller}=${event.value}"
            is MidiEvent.PitchBend -> "PITCH BEND ${"%.2f".format(event.normalized)}"
            MidiEvent.ClockTick -> {
                updateMidiClock()
                null
            }
            MidiEvent.TransportStart -> "MIDI START"
            MidiEvent.TransportStop -> "MIDI STOP"
        }
        if (label != null) {
            val updatedLog = (listOf(label) + _state.value.recentLog).take(12)
            _state.value = _state.value.copy(
                recentLog = updatedLog,
                lastMappedAction = label
            )
        }
    }

    private fun updateMidiClock() {
        val now = System.nanoTime()
        clockTickTimes.addLast(now)
        if (clockTickTimes.size > 24) clockTickTimes.removeFirst()
        if (clockTickTimes.size >= 12) {
            val elapsedNs = clockTickTimes.last() - clockTickTimes.first()
            if (elapsedNs > 0) {
                val ticks = clockTickTimes.size - 1
                // 24 MIDI clock pulses per quarter note (PPQN)
                val bpm = (60_000_000_000.0 * ticks / (24.0 * elapsedNs)).toFloat().coerceIn(40f, 240f)
                _state.value = _state.value.copy(syncedBpm = bpm)
            }
        }
    }

    fun release() {
        val mgr = midiManager
        if (mgr != null) {
            try {
                mgr.unregisterDeviceCallback(deviceCallback)
            } catch (_: Exception) {
            }
        }
        openOutputPorts.forEach { runCatching { it.close() } }
        openOutputPorts.clear()
        runCatching { activeInputPort?.close() }
        activeInputPort = null
        openDevices.values.forEach { runCatching { it.close() } }
        openDevices.clear()
    }
}

object MidiMessageParser {
    fun parse(data: ByteArray, offset: Int = 0, count: Int = data.size - offset): List<MidiEvent> {
        val events = mutableListOf<MidiEvent>()
        var i = offset
        val end = (offset + count).coerceAtMost(data.size)
        while (i < end) {
            val status = data[i].toInt() and 0xFF
            when {
                status == 0xF8 -> {
                    events.add(MidiEvent.ClockTick)
                    i += 1
                }
                status == 0xFA -> {
                    events.add(MidiEvent.TransportStart)
                    i += 1
                }
                status == 0xFC -> {
                    events.add(MidiEvent.TransportStop)
                    i += 1
                }
                status in 0x80..0xEF -> {
                    val type = status and 0xF0
                    val channel = status and 0x0F
                    if (i + 2 < end) {
                        val d1 = data[i + 1].toInt() and 0x7F
                        val d2 = data[i + 2].toInt() and 0x7F
                        when (type) {
                            0x90 -> {
                                if (d2 > 0) {
                                    events.add(MidiEvent.NoteOn(channel, d1, d2))
                                } else {
                                    events.add(MidiEvent.NoteOff(channel, d1))
                                }
                            }
                            0x80 -> events.add(MidiEvent.NoteOff(channel, d1))
                            0xB0 -> events.add(MidiEvent.ControlChange(channel, d1, d2))
                            0xE0 -> {
                                val raw14 = (d2 shl 7) or d1
                                val norm = ((raw14 - 8192) / 8192f).coerceIn(-1f, 1f)
                                events.add(MidiEvent.PitchBend(channel, norm))
                            }
                        }
                        i += 3
                    } else {
                        break
                    }
                }
                else -> i += 1
            }
        }
        return events
    }
}
