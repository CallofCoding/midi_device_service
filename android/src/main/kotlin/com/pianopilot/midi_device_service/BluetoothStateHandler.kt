package com.pianopilot.midi_device_service

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

private const val TAG = "BluetoothStateHandler"

/**
 * Monitors system Bluetooth adapter state via [BluetoothAdapter.ACTION_STATE_CHANGED].
 *
 * Emits one of: "on", "off", "turningOn", "turningOff", "unknown"
 * to the Dart side via [MidiEventEmitter.emitBtState].
 *
 * Also reads the current state immediately on [register] so Dart
 * gets the initial state without waiting for the next change event.
 */
internal class BluetoothStateHandler(
    private val context: Context,
    private val emitter: MidiEventEmitter,
) {

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return

            val state = intent.getIntExtra(
                BluetoothAdapter.EXTRA_STATE,
                BluetoothAdapter.ERROR,
            )
            val stateStr = mapState(state)
            Log.d(TAG, "Bluetooth adapter state changed → $stateStr")
            emitter.emitBtState(stateStr)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun register() {
        if (registered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
        Log.d(TAG, "Registered Bluetooth state receiver")

        // Emit current state immediately so Dart doesn't wait for the next change
        emitter.emitBtState(currentState())
    }

    fun unregister() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver: ${e.message}")
        }
        registered = false
        Log.d(TAG, "Unregistered Bluetooth state receiver")
    }

    // ── Current state ─────────────────────────────────────────────────────────

    fun currentState(): String {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "unknown"
        return mapState(adapter.state)
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private fun mapState(state: Int): String = when (state) {
        BluetoothAdapter.STATE_ON          -> "on"
        BluetoothAdapter.STATE_OFF         -> "off"
        BluetoothAdapter.STATE_TURNING_ON  -> "turningOn"
        BluetoothAdapter.STATE_TURNING_OFF -> "turningOff"
        else                               -> "unknown"
    }
}