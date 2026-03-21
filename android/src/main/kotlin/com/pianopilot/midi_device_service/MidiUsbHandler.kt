package com.pianopilot.midi_device_service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

private const val TAG                    = "MidiUsbHandler"
private const val ACTION_USB_PERMISSION  = "com.pianopilot.midi_device_service.USB_PERMISSION"

/**
 * Manages USB MIDI devices via [android.media.midi.MidiManager].
 *
 * Responsibilities:
 *  - Enumerating available USB MIDI devices
 *  - Requesting USB device permission
 *  - Opening devices and their input ports
 *  - Receiving real-time MIDI data on a dedicated [HandlerThread]
 *  - Notifying [MidiEventEmitter] of messages and device state changes
 *  - Cleaning up resources on disconnect
 */
internal class MidiUsbHandler(
    private val context: Context,
    private val midiManager: MidiManager,
    private val emitter: MidiEventEmitter,
) {

    // ── Connection state ─────────────────────────────────────────────────────────
    private data class OpenDevice(
        val device:      MidiDevice,
        val outputPort:  MidiOutputPort,
        val handlerThread: HandlerThread,
    )

    private val openDevices = ConcurrentHashMap<String, OpenDevice>()

    // Pending USB permission callbacks: deviceId → callback
    private val pendingPermissions = ConcurrentHashMap<String, (Boolean) -> Unit>()

    // ── USB permission receiver ──────────────────────────────────────────────────
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val usbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            } ?: return

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val deviceId = usbDevice.deviceId.toString()
            val callback = pendingPermissions.remove(deviceId)
            Log.d(TAG, "USB permission for $deviceId: $granted")
            callback?.invoke(granted)
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }
    }

    // ── Device enumeration ───────────────────────────────────────────────────────

    /** Returns all USB MIDI devices currently visible to [MidiManager]. */
    fun getDevices(): List<Map<String, Any>> {
        return midiManager.devices
            .filter { it.type == MidiDeviceInfo.TYPE_USB }
            .map { it.toMap(isConnected = openDevices.containsKey(it.id.toString())) }
    }

    /** Returns maps for all currently open USB devices. */
    fun getConnectedDevices(): List<Map<String, Any>> {
        return openDevices.keys.mapNotNull { id ->
            midiManager.devices
                .firstOrNull { it.id.toString() == id }
                ?.toMap(isConnected = true)
        }
    }

    // ── Permission ───────────────────────────────────────────────────────────────

    /**
     * Requests USB permission for the given device id.
     * [callback] is invoked on the main thread with the result.
     */
    fun requestPermission(deviceId: String, callback: (Boolean) -> Unit) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return callback(false)

        val deviceInfo = midiManager.devices.firstOrNull { it.id.toString() == deviceId }
            ?: return callback(false)

        val usbDevice = usbManager.deviceList.values
            .firstOrNull { it.productName == deviceInfo.properties.getString(MidiDeviceInfo.PROPERTY_NAME) }
            ?: return callback(true) // android.media.midi device — permission handled by MidiManager

        if (usbManager.hasPermission(usbDevice)) {
            callback(true)
            return
        }

        pendingPermissions[deviceId] = callback

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0

        val permissionIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION),
            flags,
        )
        usbManager.requestPermission(usbDevice, permissionIntent)
    }

    // ── Connect ──────────────────────────────────────────────────────────────────

    /**
     * Opens the USB MIDI device with [deviceId] and starts listening on port 0.
     * [onResult] is called with success=true and an empty errorMessage on success,
     * or success=false with an errorMessage on failure.
     */
    fun connect(deviceId: String, onResult: (success: Boolean, errorMessage: String?) -> Unit) {
        if (openDevices.containsKey(deviceId)) {
            onResult(true, null)
            return
        }

        val deviceInfo = midiManager.devices.firstOrNull { it.id.toString() == deviceId }
        if (deviceInfo == null) {
            onResult(false, "Device not found: $deviceId")
            return
        }

        if (deviceInfo.inputPortCount == 0) {
            onResult(false, "Device has no input ports")
            return
        }

        midiManager.openDevice(deviceInfo, { device ->
            if (device == null) {
                Log.e(TAG, "Failed to open device $deviceId")
                onResult(false, "Failed to open MIDI device")
                emitDeviceState(deviceId, deviceInfo, "error", "Failed to open MIDI device")
                return@openDevice
            }

            try {
                val handlerThread = HandlerThread("MidiUsb-$deviceId").also { it.start() }
                val outputPort    = device.openOutputPort(0)

                if (outputPort == null) {
                    device.close()
                    handlerThread.quitSafely()
                    onResult(false, "Failed to open output port")
                    return@openDevice
                }

                outputPort.connect(MidiInputReceiver(deviceId, emitter))

                openDevices[deviceId] = OpenDevice(device, outputPort, handlerThread)

                Log.d(TAG, "Connected USB device: $deviceId")
                emitDeviceState(deviceId, deviceInfo, "connected", null)
                onResult(true, null)

            } catch (e: Exception) {
                Log.e(TAG, "Exception connecting $deviceId", e)
                device.close()
                onResult(false, e.message ?: "Unknown error")
            }

        }, Handler(Looper.getMainLooper()))
    }

    // ── Disconnect ───────────────────────────────────────────────────────────────

    /** Closes the device with [deviceId] and frees all resources. */
    fun disconnect(deviceId: String) {
        val open = openDevices.remove(deviceId) ?: return
        try {
            open.outputPort.close()
            open.device.close()
            open.handlerThread.quitSafely()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting $deviceId", e)
        }

        val deviceInfo = midiManager.devices.firstOrNull { it.id.toString() == deviceId }
        if (deviceInfo != null) {
            emitDeviceState(deviceId, deviceInfo, "disconnected", null)
        }
        Log.d(TAG, "Disconnected USB device: $deviceId")
    }

    /** Disconnects all open USB devices. */
    fun disconnectAll() {
        openDevices.keys.toList().forEach { disconnect(it) }
    }

    // ── Queries ──────────────────────────────────────────────────────────────────

    fun isConnected(deviceId: String): Boolean = openDevices.containsKey(deviceId)

    fun getDeviceInfo(deviceId: String): Map<String, Any>? {
        val info = midiManager.devices.firstOrNull { it.id.toString() == deviceId }
            ?: return null
        return info.toDetailedMap(isConnected = openDevices.containsKey(deviceId))
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────────

    fun release() {
        disconnectAll()
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {}
        pendingPermissions.clear()
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private fun emitDeviceState(
        deviceId: String,
        deviceInfo: MidiDeviceInfo,
        status: String,
        errorMessage: String?,
    ) {
        emitter.emitDeviceState(
            hashMapOf(
                "device"       to deviceInfo.toMap(isConnected = status == "connected"),
                "status"       to status,
                "errorMessage" to errorMessage,
                "errorCode"    to null,
                "timestampMs"  to System.currentTimeMillis(),
            )
        )
    }
}

// ── MIDI Input Receiver ──────────────────────────────────────────────────────────

/**
 * Receives raw MIDI bytes from an output port (the port the keyboard sends on).
 * Runs on the [HandlerThread] created per device for true real-time delivery.
 */
private class MidiInputReceiver(
    private val deviceId: String,
    private val emitter:  MidiEventEmitter,
) : MidiReceiver() {

    override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
        if (count <= 0) return
        val data     = msg.copyOfRange(offset, offset + count)
        val messages = MidiMessageParser.parse(data, deviceId)
        if (messages.isNotEmpty()) {
            emitter.emitMidiMessages(messages)
        }
    }
}

// ── MidiDeviceInfo extension ─────────────────────────────────────────────────────

private fun MidiDeviceInfo.toMap(isConnected: Boolean): Map<String, Any> = hashMapOf(
    "id"              to id.toString(),
    "name"            to (properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "USB MIDI Device"),
    "type"            to "usb",
    "inputPortCount"  to inputPortCount,
    "outputPortCount" to outputPortCount,
    "manufacturer"    to (properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: ""),
    "product"         to (properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: ""),
    "address"         to "",
    "isConnected"     to isConnected,
)

private fun MidiDeviceInfo.toDetailedMap(isConnected: Boolean): Map<String, Any> = hashMapOf(
    "id"                 to id.toString(),
    "name"               to (properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "USB MIDI Device"),
    "manufacturer"       to (properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: ""),
    "product"            to (properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: ""),
    "version"            to (properties.getString(MidiDeviceInfo.PROPERTY_VERSION) ?: ""),
    "type"               to "usb",
    "inputPortCount"     to inputPortCount,
    "outputPortCount"    to outputPortCount,
    "bleServiceUuid"     to "",
    "usbVendorId"        to -1, // MidiDeviceInfo does not expose USB VID/PID; use UsbManager if needed
    "usbProductId"       to -1,
    "estimatedLatencyMs" to 3,
    "isConnected"        to isConnected,
)