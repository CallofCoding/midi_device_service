package com.pianopilot.midi_device_service

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

/**
 * Thread-safe wrapper around Flutter [EventChannel.EventSink].
 * All sink calls are posted to the main thread.
 */
internal class MidiEventEmitter {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var midiSink:    EventChannel.EventSink? = null
    @Volatile private var deviceSink:  EventChannel.EventSink? = null
    @Volatile private var scanSink:    EventChannel.EventSink? = null
    @Volatile private var btStateSink: EventChannel.EventSink? = null

    // ── Sink registration ────────────────────────────────────────────────────

    fun setMidiSink(sink: EventChannel.EventSink?)    { midiSink    = sink }
    fun setDeviceSink(sink: EventChannel.EventSink?)  { deviceSink  = sink }
    fun setScanSink(sink: EventChannel.EventSink?)    { scanSink    = sink }
    fun setBtStateSink(sink: EventChannel.EventSink?) { btStateSink = sink }

    // ── MIDI events ──────────────────────────────────────────────────────────

    fun emitMidiMessage(message: ParsedMidiMessage) {
        mainHandler.post { midiSink?.success(message.toMap()) }
    }

    fun emitMidiMessages(messages: List<ParsedMidiMessage>) {
        if (messages.isEmpty()) return
        mainHandler.post {
            val sink = midiSink ?: return@post
            for (msg in messages) sink.success(msg.toMap())
        }
    }

    // ── Device state events ──────────────────────────────────────────────────

    fun emitDeviceState(state: Map<String, Any?>) {
        mainHandler.post { deviceSink?.success(state) }
    }

    // ── Scan events ──────────────────────────────────────────────────────────

    fun emitScanResult(result: Map<String, Any>) {
        mainHandler.post { scanSink?.success(hashMapOf("event" to "result") + result) }
    }

    fun emitScanStopped(reason: String) {
        mainHandler.post {
            scanSink?.success(hashMapOf(
                "event"  to "stopped",
                "reason" to reason,
            ))
        }
    }

    // ── Bluetooth adapter state ──────────────────────────────────────────────

    /**
     * Emits Bluetooth adapter state string to Dart.
     * One of: "on", "off", "turningOn", "turningOff", "unknown"
     */
    fun emitBtState(state: String) {
        mainHandler.post { btStateSink?.success(state) }
    }

    // ── Errors ───────────────────────────────────────────────────────────────

    fun emitMidiError(code: String, message: String, details: Any? = null) {
        mainHandler.post { midiSink?.error(code, message, details) }
    }

    fun emitDeviceError(code: String, message: String, details: Any? = null) {
        mainHandler.post { deviceSink?.error(code, message, details) }
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    fun clearSinks() {
        midiSink    = null
        deviceSink  = null
        scanSink    = null
        btStateSink = null
    }
}