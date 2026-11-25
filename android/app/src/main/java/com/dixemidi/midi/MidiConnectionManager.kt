package com.dixemidi.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages USB MIDI Peripheral Port discovery, hot-plug reconnection, and low-latency
 * timestamped packet transmission via Android's android.media.midi framework.
 *
 * When the phone is connected to Windows 11 via USB with USB mode set to "MIDI",
 * Android exposes a system USB Peripheral MidiDeviceInfo with an input port.
 * Opening inputPort(0) on that device sends raw MIDI bytes directly over the USB pipe
 * to Windows 11 / FL Studio.
 */
class MidiConnectionManager(
    private val context: Context,
    private val channel: Int = 0
) {
    private val midiManager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val portLock = ReentrantLock()

    private var openDevice: MidiDevice? = null
    private var openDeviceId: Int = -1
    private var openInputPort: MidiInputPort? = null
    private var started = false

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            evaluateAndConnectPreferredDevice()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            portLock.withLock {
                if (device.id == openDeviceId) {
                    closeCurrentDeviceLocked()
                }
            }
            evaluateAndConnectPreferredDevice()
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        val manager = midiManager ?: return
        if (started) return
        started = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.registerDeviceCallback(
                MidiManager.TRANSPORT_MIDI_BYTE_STREAM,
                { runnable -> mainHandler.post(runnable) },
                deviceCallback
            )
        } else {
            manager.registerDeviceCallback(deviceCallback, mainHandler)
        }

        evaluateAndConnectPreferredDevice()
    }

    @Suppress("DEPRECATION")
    fun stop() {
        val manager = midiManager ?: return
        if (started) {
            started = false
            manager.unregisterDeviceCallback(deviceCallback)
        }
        portLock.withLock {
            closeCurrentDeviceLocked()
        }
    }

    fun send(message: ByteArray) {
        val timestampNs = System.nanoTime()

        portLock.withLock {
            val port = openInputPort
            if (port != null) {
                try {
                    port.send(message, 0, message.size, timestampNs)
                } catch (_: IOException) {
                    closeCurrentDeviceLocked()
                }
            }
        }

        DixeMidiDeviceService.activeInstance?.broadcastToVirtualOutputs(message, timestampNs)
    }

    @Suppress("DEPRECATION")
    private fun evaluateAndConnectPreferredDevice() {
        val manager = midiManager ?: return
        val devices: Array<MidiDeviceInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toTypedArray()
        } else {
            manager.devices
        }

        val target = selectBestPeripheralTarget(devices) ?: return

        portLock.withLock {
            if (target.id == openDeviceId && openInputPort != null) {
                return
            }
        }

        manager.openDevice(target, { device ->
            if (device == null) return@openDevice
            val port = device.openInputPort(0)
            if (port == null) {
                try {
                    device.close()
                } catch (_: IOException) {
                }
                return@openDevice
            }

            portLock.withLock {
                closeCurrentDeviceLocked()
                openDevice = device
                openDeviceId = target.id
                openInputPort = port
            }
        }, mainHandler)
    }

    private fun selectBestPeripheralTarget(devices: Array<MidiDeviceInfo>): MidiDeviceInfo? {
        val candidates = devices.filter { info ->
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME).orEmpty()
            info.inputPortCount > 0 && name != "DIXE MIDI"
        }
        if (candidates.isEmpty()) return null

        // Priority 1: Explicit Android USB Peripheral Port / USB MIDI device
        candidates.firstOrNull { info ->
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME).orEmpty()
            val product = info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT).orEmpty()
            info.type == MidiDeviceInfo.TYPE_USB ||
                name.contains("USB", ignoreCase = true) ||
                product.contains("USB", ignoreCase = true) ||
                name.contains("Peripheral", ignoreCase = true)
        }?.let { return it }

        // Priority 2: First available external/system MIDI sink
        return candidates.firstOrNull()
    }

    private fun closeCurrentDeviceLocked() {
        try {
            openInputPort?.close()
        } catch (_: IOException) {
        }
        try {
            openDevice?.close()
        } catch (_: IOException) {
        }
        openInputPort = null
        openDevice = null
        openDeviceId = -1
    }
}
