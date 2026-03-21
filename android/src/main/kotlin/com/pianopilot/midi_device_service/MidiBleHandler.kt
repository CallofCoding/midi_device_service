package com.pianopilot.midi_device_service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MidiBleHandler"

// BLE scan auto-stop timeout
private const val SCAN_TIMEOUT_MS = 30_000L

// ── BLE MIDI UUIDs (MMA/AMEI BLE MIDI 1.0 spec) ─────────────────────────────────
private val BLE_MIDI_SERVICE_UUID        = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
private val BLE_MIDI_CHARACTERISTIC_UUID = UUID.fromString("7772E5DB-3868-4112-A1A9-F2669D106BF3")
private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

@SuppressLint("MissingPermission")
internal class MidiBleHandler(
    private val context: Context,
    private val midiManager: MidiManager,
    private val emitter: MidiEventEmitter,
) {

    // ── Open device state ────────────────────────────────────────────────────────
    private data class OpenBleDevice(
        val gatt:          BluetoothGatt,
        val deviceId:      String,
        val name:          String,
        val address:       String,
        val handlerThread: HandlerThread,
    )

    private val openDevices    = ConcurrentHashMap<String, OpenBleDevice>()
    private val pendingConnects = ConcurrentHashMap<String, (Boolean, String?) -> Unit>()

    // ── Scan state ───────────────────────────────────────────────────────────────
    private var isScanning        = false
    private var bleScanner        = getAdapter()?.bluetoothLeScanner
    private val scanStopHandler   = Handler(Looper.getMainLooper())
    private val scannedAddresses  = mutableSetOf<String>() // deduplicate scan results

    // ── Device enumeration ───────────────────────────────────────────────────────

    fun getDevices(): List<Map<String, Any>> {
        val midiDevices = midiManager.devices
            .filter { it.type == MidiDeviceInfo.TYPE_BLUETOOTH }
            .map { it.toBleMap(isConnected = openDevices.containsKey(it.id.toString())) }

        val gattOnly = openDevices.values
            .filter { open -> midiDevices.none { it["address"] == open.address } }
            .map { open ->
                hashMapOf(
                    "id"              to open.deviceId,
                    "name"            to open.name,
                    "type"            to "bluetooth",
                    "inputPortCount"  to 1,
                    "outputPortCount" to 1,
                    "manufacturer"    to "",
                    "product"         to "",
                    "address"         to open.address,
                    "isConnected"     to true,
                )
            }

        return midiDevices + gattOnly
    }

    fun getConnectedDevices(): List<Map<String, Any>> {
        return openDevices.values.map { open ->
            hashMapOf(
                "id"              to open.deviceId,
                "name"            to open.name,
                "type"            to "bluetooth",
                "inputPortCount"  to 1,
                "outputPortCount" to 1,
                "manufacturer"    to "",
                "product"         to "",
                "address"         to open.address,
                "isConnected"     to true,
            )
        }
    }

    // ── Permissions ──────────────────────────────────────────────────────────────

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
                    hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH)
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    // ── BLE Scan ─────────────────────────────────────────────────────────────────

    /**
     * Starts a BLE scan filtered to the MIDI service UUID.
     * Results are emitted to Dart via [MidiEventEmitter.emitScanResult].
     * Automatically stops after [SCAN_TIMEOUT_MS].
     */
    fun startScan(): String? {
        if (!hasPermissions()) return "Bluetooth permissions not granted"

        val adapter = getAdapter() ?: return "Bluetooth not available on this device"
        if (!adapter.isEnabled)   return "Bluetooth is turned off"

        bleScanner = adapter.bluetoothLeScanner
            ?: return "BLE scanner not available"

        if (isScanning) {
            Log.d(TAG, "Scan already running")
            return null
        }

        scannedAddresses.clear()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BLE_MIDI_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(listOf(filter), settings, bleScanCallback)
        isScanning = true
        Log.d(TAG, "BLE scan started")

        // Auto-stop after timeout
        scanStopHandler.postDelayed({
            if (isScanning) {
                stopScan()
                emitter.emitScanStopped("timeout")
            }
        }, SCAN_TIMEOUT_MS)

        return null // null = no error
    }

    /**
     * Stops an ongoing BLE scan.
     */
    fun stopScan() {
        if (!isScanning) return
        try {
            bleScanner?.stopScan(bleScanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan: ${e.message}")
        }
        scanStopHandler.removeCallbacksAndMessages(null)
        isScanning = false
        scannedAddresses.clear()
        Log.d(TAG, "BLE scan stopped")
    }

    fun isScanRunning(): Boolean = isScanning

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address ?: return

            // Deduplicate — only emit each device once per scan session
            if (!scannedAddresses.add(address)) return

            val name = device.name?.takeIf { it.isNotBlank() } ?: "BLE MIDI Device"
            val rssi = result.rssi

            Log.d(TAG, "BLE scan result: $name ($address) rssi=$rssi")

            emitter.emitScanResult(hashMapOf(
                "id"      to address, // MAC address used as deviceId for BLE
                "name"    to name,
                "address" to address,
                "rssi"    to rssi,
            ))
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
            emitter.emitScanStopped("error:$errorCode")
        }
    }

    // ── Connect ──────────────────────────────────────────────────────────────────

    /**
     * Connects to a BLE MIDI device by [deviceId] (MAC address or MidiManager id).
     */
    fun connect(deviceId: String, onResult: (success: Boolean, errorMessage: String?) -> Unit) {
        if (openDevices.containsKey(deviceId)) {
            onResult(true, null)
            return
        }

        val midiDeviceInfo  = midiManager.devices.firstOrNull { it.id.toString() == deviceId }
        val bluetoothDevice = resolveBluetoothDevice(deviceId, midiDeviceInfo)

        if (bluetoothDevice == null) {
            onResult(false, "BLE device not found: $deviceId. Ensure it is paired or was found by scan.")
            return
        }

        Log.d(TAG, "Connecting to: ${bluetoothDevice.name} (${bluetoothDevice.address})")

        pendingConnects[deviceId] = onResult

        val handlerThread = HandlerThread(
            "MidiBle-$deviceId",
            android.os.Process.THREAD_PRIORITY_URGENT_AUDIO,
        ).also { it.start() }

        val gattCallback = MidiGattCallback(
            deviceId       = deviceId,
            deviceName     = bluetoothDevice.name ?: "BLE MIDI Device",
            address        = bluetoothDevice.address,
            handlerThread  = handlerThread,
            emitter        = emitter,
            onConnected    = { gatt ->
                openDevices[deviceId] = OpenBleDevice(
                    gatt          = gatt,
                    deviceId      = deviceId,
                    name          = bluetoothDevice.name ?: "BLE MIDI Device",
                    address       = bluetoothDevice.address,
                    handlerThread = handlerThread,
                )
                val cb = pendingConnects.remove(deviceId)
                Handler(Looper.getMainLooper()).post {
                    emitDeviceState(deviceId, bluetoothDevice.name ?: "BLE MIDI Device",
                        bluetoothDevice.address, "connected", null)
                    cb?.invoke(true, null)
                }
            },
            onFailed       = { errorMsg ->
                handlerThread.quitSafely()
                val cb = pendingConnects.remove(deviceId)
                Handler(Looper.getMainLooper()).post {
                    emitDeviceState(deviceId, bluetoothDevice.name ?: "BLE MIDI Device",
                        bluetoothDevice.address, "error", errorMsg)
                    cb?.invoke(false, errorMsg)
                }
            },
            onDisconnected = {
                openDevices.remove(deviceId)
                handlerThread.quitSafely()
                Handler(Looper.getMainLooper()).post {
                    emitDeviceState(deviceId, bluetoothDevice.name ?: "BLE MIDI Device",
                        bluetoothDevice.address, "disconnected", null)
                }
            },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bluetoothDevice.connectGatt(
                context, false, gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                Handler(handlerThread.looper),
            )
        } else {
            bluetoothDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    // ── Disconnect ───────────────────────────────────────────────────────────────

    fun disconnect(deviceId: String) {
        val open = openDevices.remove(deviceId) ?: return
        try {
            open.gatt.disconnect()
            open.gatt.close()
            open.handlerThread.quitSafely()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting $deviceId: ${e.message}")
        }
        emitDeviceState(deviceId, open.name, open.address, "disconnected", null)
    }

    fun disconnectAll() {
        openDevices.keys.toList().forEach { disconnect(it) }
    }

    // ── Queries ──────────────────────────────────────────────────────────────────

    fun isConnected(deviceId: String): Boolean = openDevices.containsKey(deviceId)

    fun getDeviceInfo(deviceId: String): Map<String, Any>? {
        val open = openDevices[deviceId] ?: return null
        return hashMapOf(
            "id"                 to open.deviceId,
            "name"               to open.name,
            "manufacturer"       to "",
            "product"            to "",
            "version"            to "",
            "type"               to "bluetooth",
            "inputPortCount"     to 1,
            "outputPortCount"    to 1,
            "bleServiceUuid"     to BLE_MIDI_SERVICE_UUID.toString(),
            "usbVendorId"        to -1,
            "usbProductId"       to -1,
            "estimatedLatencyMs" to 15,
            "isConnected"        to true,
        )
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────────

    fun release() {
        stopScan()
        disconnectAll()
        pendingConnects.clear()
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private fun getAdapter() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun resolveBluetoothDevice(
        deviceId: String,
        midiDeviceInfo: MidiDeviceInfo?,
    ): BluetoothDevice? {
        val adapter = getAdapter() ?: return null

        // 1. Try MidiDeviceInfo address property
        if (midiDeviceInfo != null) {
            val address = midiDeviceInfo.properties.getString("address")
            if (!address.isNullOrBlank()) {
                return try { adapter.getRemoteDevice(address) } catch (_: Exception) { null }
            }
        }

        // 2. deviceId is a MAC address (scan result)
        if (deviceId.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
            return try { adapter.getRemoteDevice(deviceId) } catch (_: Exception) { null }
        }

        // 3. Search bonded devices advertising BLE MIDI service UUID
        return adapter.bondedDevices?.firstOrNull { device ->
            device.uuids?.any { it.uuid == BLE_MIDI_SERVICE_UUID } == true
        }
    }

    private fun emitDeviceState(
        deviceId: String, name: String, address: String,
        status: String, errorMessage: String?,
    ) {
        emitter.emitDeviceState(hashMapOf(
            "device" to hashMapOf(
                "id"              to deviceId,
                "name"            to name,
                "type"            to "bluetooth",
                "inputPortCount"  to 1,
                "outputPortCount" to 1,
                "manufacturer"    to "",
                "product"         to "",
                "address"         to address,
            ),
            "status"       to status,
            "errorMessage" to errorMessage,
            "errorCode"    to null,
            "timestampMs"  to System.currentTimeMillis(),
        ))
    }
}

// ── MidiDeviceInfo BLE extension ─────────────────────────────────────────────────

private fun MidiDeviceInfo.toBleMap(isConnected: Boolean): Map<String, Any> = hashMapOf(
    "id"              to id.toString(),
    "name"            to (properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "BLE MIDI Device"),
    "type"            to "bluetooth",
    "inputPortCount"  to inputPortCount,
    "outputPortCount" to outputPortCount,
    "manufacturer"    to (properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: ""),
    "product"         to (properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: ""),
    "address"         to (properties.getString("address") ?: ""),
    "isConnected"     to isConnected,
)

// ── Raw GATT Callback ─────────────────────────────────────────────────────────────

private class MidiGattCallback(
    private val deviceId:       String,
    private val deviceName:     String,
    private val address:        String,
    private val handlerThread:  HandlerThread,
    private val emitter:        MidiEventEmitter,
    private val onConnected:    (BluetoothGatt) -> Unit,
    private val onFailed:       (String) -> Unit,
    private val onDisconnected: () -> Unit,
) : BluetoothGattCallback() {

    private var midiCharacteristic: BluetoothGattCharacteristic? = null
    private var isReady = false

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        when {
            newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                Log.d(TAG, "[$deviceId] Connected — discovering services")
                Handler(handlerThread.looper).postDelayed({ gatt.discoverServices() }, 200)
            }
            newState == BluetoothProfile.STATE_DISCONNECTED -> {
                gatt.close()
                if (!isReady) onFailed("GATT disconnected before setup (status=$status)")
                else          onDisconnected()
            }
            status != BluetoothGatt.GATT_SUCCESS -> {
                gatt.close()
                onFailed("GATT error (status=$status)")
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            gatt.close()
            onFailed("Service discovery failed (status=$status)")
            return
        }

        val service = gatt.getService(BLE_MIDI_SERVICE_UUID)
        if (service == null) {
            gatt.close()
            onFailed("BLE MIDI service not found on device")
            return
        }

        val characteristic = service.getCharacteristic(BLE_MIDI_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            gatt.close()
            onFailed("BLE MIDI characteristic not found")
            return
        }

        midiCharacteristic = characteristic
        enableNotifications(gatt, characteristic)
    }

    private fun enableNotifications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(char, true)

        val descriptor = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor == null) {
            finalizeConnection(gatt)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int,
    ) {
        if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG) {
            finalizeConnection(gatt)
        }
    }

    private fun finalizeConnection(gatt: BluetoothGatt) {
        val ok = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        Log.d(TAG, "[$deviceId] CONNECTION_PRIORITY_HIGH: $ok")
        isReady = true
        onConnected(gatt)
    }

    @Deprecated("Used on Android < 13")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
    ) {
        if (characteristic.uuid == BLE_MIDI_CHARACTERISTIC_UUID) {
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            dispatch(data)
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
    ) {
        if (characteristic.uuid == BLE_MIDI_CHARACTERISTIC_UUID) dispatch(value)
    }

    private fun dispatch(data: ByteArray) {
        val messages = MidiMessageParser.parse(data, deviceId)
        if (messages.isNotEmpty()) emitter.emitMidiMessages(messages)
    }
}