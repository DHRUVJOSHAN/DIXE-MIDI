package com.dixemidi.midi

import android.media.midi.MidiDeviceService
import android.media.midi.MidiReceiver
import java.io.IOException

/**
 * Exposes DIXE MIDI as a virtual MIDI output device on Android's MIDI routing bus
 * for inter-app routing in addition to direct USB Peripheral Port transmission.
 */
class DixeMidiDeviceService : MidiDeviceService() {

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> {
        return emptyArray()
    }

    fun broadcastToVirtualOutputs(data: ByteArray, timestampNs: Long) {
        val receivers = outputPortReceivers ?: return
        for (receiver in receivers) {
            try {
                receiver.send(data, 0, data.size, timestampNs)
            } catch (_: IOException) {
                // Receiver disconnected; ignore safely
            }
        }
    }

    companion object {
        @Volatile
        var activeInstance: DixeMidiDeviceService? = null
            private set
    }
}
