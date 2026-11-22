package com.example.midi

import android.media.midi.MidiDeviceService
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiReceiver
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared thread-safe bridge between Samplfie2's UI/ViewModel and Android's USB/Virtual MidiDeviceService.
 * When Android is connected over USB-C in MIDI mode to a Linux/Mac/Windows host, outgoing Drum Pad
 * hits are streamed directly out of the peripheral Output Port as real class-compliant MIDI messages.
 */
object MidiPeripheralBridge {
    private val hostOutputReceivers = CopyOnWriteArrayList<MidiReceiver>()
    private val incomingEventListener = AtomicReference<((MidiEvent) -> Unit)?>(null)
    @Volatile
    var isHostConnected: Boolean = false
        private set

    fun registerIncomingListener(listener: ((MidiEvent) -> Unit)?) {
        incomingEventListener.set(listener)
    }

    internal fun attachOutputReceiver(receivers: Array<MidiReceiver>?) {
        hostOutputReceivers.clear()
        if (receivers != null && receivers.isNotEmpty()) {
            hostOutputReceivers.addAll(receivers.filterNotNull())
            isHostConnected = hostOutputReceivers.isNotEmpty()
        } else {
            isHostConnected = false
        }
    }

    internal fun onInboundBytes(msg: ByteArray, offset: Int, count: Int) {
        val parsed = MidiMessageParser.parse(msg, offset, count)
        val listener = incomingEventListener.get() ?: return
        parsed.forEach { listener(it) }
    }

    fun sendRawMidi(bytes: ByteArray): Boolean {
        if (hostOutputReceivers.isEmpty()) return false
        var sentAny = false
        hostOutputReceivers.forEach { rx ->
            runCatching {
                rx.send(bytes, 0, bytes.size)
                sentAny = true
            }
        }
        return sentAny
    }
}

/**
 * Exposes Samplfie2 as a USB/Virtual MIDI Peripheral Device when plugged into a DAW or Linux workstation.
 */
class SamplfieMidiDeviceService : MidiDeviceService() {

    private val inputReceiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            MidiPeripheralBridge.onInboundBytes(msg, offset, count)
        }
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> {
        return arrayOf(inputReceiver)
    }

    override fun onDeviceStatusChanged(status: MidiDeviceStatus?) {
        super.onDeviceStatusChanged(status)
        MidiPeripheralBridge.attachOutputReceiver(outputPortReceivers)
    }

    override fun onDestroy() {
        MidiPeripheralBridge.attachOutputReceiver(null)
        super.onDestroy()
    }
}
