package com.pianopilot.midi_device_service

import android.app.Activity
import android.content.Context
import android.media.midi.MidiManager
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

private const val TAG = "MidiDeviceServicePlugin"

// ── Channel names ─────────────────────────────────────────────────────────────
private const val METHOD_CHANNEL       = "com.pianopilot/midi"
private const val EVENT_CHANNEL_MIDI   = "com.pianopilot/midi/events"
private const val EVENT_CHANNEL_DEVICE = "com.pianopilot/midi/devices"
private const val EVENT_CHANNEL_SCAN   = "com.pianopilot/midi/scan"
private const val EVENT_CHANNEL_BT     = "com.pianopilot/midi/bt_state"

// ── Method names ─────────────────────────────────────────────────────────────
private const val GET_DEVICES            = "getDevices"
private const val CONNECT_DEVICE         = "connectDevice"
private const val DISCONNECT_DEVICE      = "disconnectDevice"
private const val DISCONNECT_ALL         = "disconnectAll"
private const val GET_CONNECTED_DEVICES  = "getConnectedDevices"
private const val GET_DEVICE_INFO        = "getDeviceInfo"
private const val GET_LATENCY            = "getLatency"
private const val IS_DEVICE_CONNECTED    = "isDeviceConnected"
private const val REQUEST_BLE_PERMS      = "requestBluetoothPermissions"
private const val HAS_BLE_PERMS          = "hasBluetoothPermissions"
private const val REQUEST_USB_PERMISSION = "requestUsbPermission"
private const val START_SCAN             = "startScan"
private const val STOP_SCAN              = "stopScan"
private const val IS_SCANNING            = "isScanning"
private const val GET_BT_STATE           = "getBluetoothState"

class MidiDeviceServicePlugin :
    FlutterPlugin,
    MethodCallHandler,
    ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {

    private lateinit var context:       Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var midiChannel:   EventChannel
    private lateinit var deviceChannel: EventChannel
    private lateinit var scanChannel:   EventChannel
    private lateinit var btChannel:     EventChannel

    private lateinit var emitter:    MidiEventEmitter
    private lateinit var usbHandler: MidiUsbHandler
    private lateinit var bleHandler: MidiBleHandler
    private lateinit var permHelper: BlePermissionHelper
    private lateinit var btHandler:  BluetoothStateHandler

    private var activityBinding: ActivityPluginBinding? = null
    private val pendingUsbResults = mutableMapOf<String, Result>()

    // ── FlutterPlugin ─────────────────────────────────────────────────────────

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext

        val midiManager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        if (midiManager == null) {
            Log.e(TAG, "MIDI service not available on this device")
            return
        }

        emitter    = MidiEventEmitter()
        usbHandler = MidiUsbHandler(context, midiManager, emitter)
        bleHandler = MidiBleHandler(context, midiManager, emitter)
        permHelper = BlePermissionHelper(context)
        btHandler  = BluetoothStateHandler(context, emitter)

        // ── Method channel ────────────────────────────────────────────────────
        methodChannel = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
        methodChannel.setMethodCallHandler(this)

        // ── MIDI event channel ────────────────────────────────────────────────
        midiChannel = EventChannel(binding.binaryMessenger, EVENT_CHANNEL_MIDI)
        midiChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                emitter.setMidiSink(events)
            }
            override fun onCancel(arguments: Any?) { emitter.setMidiSink(null) }
        })

        // ── Device state channel ──────────────────────────────────────────────
        deviceChannel = EventChannel(binding.binaryMessenger, EVENT_CHANNEL_DEVICE)
        deviceChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                emitter.setDeviceSink(events)
            }
            override fun onCancel(arguments: Any?) { emitter.setDeviceSink(null) }
        })

        // ── Scan event channel ────────────────────────────────────────────────
        scanChannel = EventChannel(binding.binaryMessenger, EVENT_CHANNEL_SCAN)
        scanChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                emitter.setScanSink(events)
            }
            override fun onCancel(arguments: Any?) {
                emitter.setScanSink(null)
                bleHandler.stopScan()
            }
        })

        // ── Bluetooth adapter state channel ───────────────────────────────────
        btChannel = EventChannel(binding.binaryMessenger, EVENT_CHANNEL_BT)
        btChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                emitter.setBtStateSink(events)
                // Register receiver and immediately emit current state
                btHandler.register()
            }
            override fun onCancel(arguments: Any?) {
                emitter.setBtStateSink(null)
                btHandler.unregister()
            }
        })

        Log.d(TAG, "Plugin attached")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        midiChannel.setStreamHandler(null)
        deviceChannel.setStreamHandler(null)
        scanChannel.setStreamHandler(null)
        btChannel.setStreamHandler(null)
        bleHandler.stopScan()
        btHandler.unregister()
        usbHandler.release()
        bleHandler.release()
        emitter.clearSinks()
    }

    // ── ActivityAware ─────────────────────────────────────────────────────────

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addRequestPermissionsResultListener(this)
        permHelper.activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        onAttachedToActivity(binding)

    override fun onDetachedFromActivity() {
        activityBinding?.removeRequestPermissionsResultListener(this)
        activityBinding  = null
        permHelper.activity = null
    }

    // ── MethodCallHandler ─────────────────────────────────────────────────────

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {

            GET_DEVICES ->
                result.success(usbHandler.getDevices() + bleHandler.getDevices())

            GET_CONNECTED_DEVICES ->
                result.success(usbHandler.getConnectedDevices() + bleHandler.getConnectedDevices())

            CONNECT_DEVICE -> {
                val deviceId = call.argument<String>("deviceId")
                if (deviceId.isNullOrBlank()) {
                    result.error("INVALID_ARGS", "deviceId is required", null); return
                }
                connectDevice(deviceId, result)
            }

            DISCONNECT_DEVICE -> {
                val deviceId = call.argument<String>("deviceId")
                if (deviceId.isNullOrBlank()) {
                    result.error("INVALID_ARGS", "deviceId is required", null); return
                }
                if (usbHandler.isConnected(deviceId)) usbHandler.disconnect(deviceId)
                else bleHandler.disconnect(deviceId)
                result.success(null)
            }

            DISCONNECT_ALL -> {
                usbHandler.disconnectAll()
                bleHandler.disconnectAll()
                result.success(null)
            }

            IS_DEVICE_CONNECTED -> {
                val deviceId = call.argument<String>("deviceId") ?: ""
                result.success(
                    usbHandler.isConnected(deviceId) || bleHandler.isConnected(deviceId)
                )
            }

            GET_DEVICE_INFO -> {
                val deviceId = call.argument<String>("deviceId") ?: ""
                val info = usbHandler.getDeviceInfo(deviceId)
                    ?: bleHandler.getDeviceInfo(deviceId)
                if (info != null) result.success(info)
                else result.error("NOT_FOUND", "Device not found: $deviceId", null)
            }

            GET_LATENCY -> {
                val latency = when {
                    usbHandler.getConnectedDevices().isNotEmpty() -> 3
                    bleHandler.getConnectedDevices().isNotEmpty() -> 15
                    else -> -1
                }
                result.success(latency)
            }

            // ── Scan ──────────────────────────────────────────────────────────

            START_SCAN -> {
                val error = bleHandler.startScan()
                if (error != null) result.error("SCAN_ERROR", error, null)
                else result.success(null)
            }

            STOP_SCAN -> {
                bleHandler.stopScan()
                result.success(null)
            }

            IS_SCANNING -> result.success(bleHandler.isScanRunning())

            // ── Bluetooth state ───────────────────────────────────────────────

            GET_BT_STATE -> result.success(btHandler.currentState())

            // ── Permissions ───────────────────────────────────────────────────

            REQUEST_BLE_PERMS -> {
                permHelper.requestPermissions { status -> result.success(status) }
            }

            HAS_BLE_PERMS -> result.success(bleHandler.hasPermissions())

            REQUEST_USB_PERMISSION -> {
                val deviceId = call.argument<String>("deviceId")
                if (deviceId.isNullOrBlank()) {
                    result.error("INVALID_ARGS", "deviceId is required", null); return
                }
                pendingUsbResults[deviceId] = result
                usbHandler.requestPermission(deviceId) { granted ->
                    pendingUsbResults.remove(deviceId)?.success(granted)
                }
            }

            else -> result.notImplemented()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ): Boolean = permHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)

    // ── Private ───────────────────────────────────────────────────────────────

    private fun connectDevice(deviceId: String, result: Result) {
        val isUsb = usbHandler.getDevices().any { it["id"] == deviceId }

        val onResult: (Boolean, String?) -> Unit = { success, errorMessage ->
            val device = (if (isUsb) usbHandler.getDevices() else bleHandler.getDevices())
                .firstOrNull { it["id"] == deviceId }
                ?: mapOf(
                    "id" to deviceId, "name" to "Unknown",
                    "type" to if (isUsb) "usb" else "bluetooth",
                    "inputPortCount" to 0, "outputPortCount" to 0,
                    "manufacturer" to "", "product" to "", "address" to "",
                )
            result.success(hashMapOf(
                "success"      to success,
                "device"       to device,
                "errorMessage" to errorMessage,
                "errorCode"    to null,
            ))
        }

        if (isUsb) {
            usbHandler.connect(deviceId, onResult)
        } else {
            if (!bleHandler.hasPermissions()) {
                onResult(false, "Bluetooth permissions not granted"); return
            }
            bleHandler.connect(deviceId, onResult)
        }
    }
}