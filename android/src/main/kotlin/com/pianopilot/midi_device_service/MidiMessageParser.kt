package com.pianopilot.midi_device_service

import android.os.SystemClock
import android.util.Log

private const val TAG = "MidiMessageParser"

/**
 * Parses raw MIDI byte arrays into a list of [ParsedMidiMessage].
 *
 * Detection order:
 *  1. Korg/proprietary MIDI-in-SysEx wrapper  — F0 0D/13/42 ... F7
 *  2. Generic MIDI-in-SysEx (heuristic)       — F0 <mfr> <embedded MIDI> F7
 *  3. Standard BLE MIDI packet                — header byte with bits 7+6 set
 *  4. Standard USB / serial MIDI stream       — raw status bytes
 */
internal object MidiMessageParser {

    // ── MIDI status constants ────────────────────────────────────────────────────
    private const val NOTE_OFF         = 0x80
    private const val NOTE_ON          = 0x90
    private const val POLY_PRESSURE    = 0xA0
    private const val CONTROL_CHANGE   = 0xB0
    private const val PROGRAM_CHANGE   = 0xC0
    private const val CHANNEL_PRESSURE = 0xD0
    private const val PITCH_BEND       = 0xE0
    private const val SYSEX_START      = 0xF0
    private const val SYSEX_END        = 0xF7
    private const val TIMING_CLOCK     = 0xF8
    private const val START_MSG        = 0xFA
    private const val CONTINUE_MSG     = 0xFB
    private const val STOP_MSG         = 0xFC
    private const val ACTIVE_SENSING   = 0xFE
    private const val SYSTEM_RESET     = 0xFF
    private const val TUNE_REQUEST     = 0xF6

    private val REALTIME_BYTES = setOf(
        TIMING_CLOCK, START_MSG, CONTINUE_MSG, STOP_MSG, ACTIVE_SENSING, SYSTEM_RESET,
    )

    private val CHANNEL_MESSAGE_TYPES = setOf(
        NOTE_OFF, NOTE_ON, POLY_PRESSURE, CONTROL_CHANGE,
        PROGRAM_CHANGE, CHANNEL_PRESSURE, PITCH_BEND,
    )

    // Manufacturer IDs whose SysEx packets contain embedded raw MIDI channel messages
    private val MIDI_IN_SYSEX_MANUFACTURERS = setOf(0x0D, 0x13, 0x42)

    // Bytes to skip after F0: manufacturer byte (index 1) + device ID byte (index 2)
    private const val KORG_HEADER_SKIP = 2

    // ────────────────────────────────────────────────────────────────────────────
    // PUBLIC ENTRY POINT
    // ────────────────────────────────────────────────────────────────────────────

    fun parse(data: ByteArray, deviceId: String): List<ParsedMidiMessage> {
        if (data.isEmpty()) return emptyList()
        return try {
            val first  = data[0].toInt() and 0xFF
            val second = if (data.size > 1) data[1].toInt() and 0xFF else -1

            when {
                first == SYSEX_START && second in MIDI_IN_SYSEX_MANUFACTURERS ->
                    parseProprietarySysex(data, deviceId)

                first == SYSEX_START && containsEmbeddedMidi(data) ->
                    parseProprietarySysex(data, deviceId)

                (first and 0xC0) == 0xC0 && first != 0xFF && data.size >= 3 ->
                    parseBleMidiPacket(data, deviceId)

                else -> parseStandardMidi(data, deviceId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error for $deviceId: ${e.message}")
            emptyList()
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 1. PROPRIETARY SYSEX UNWRAPPING
    // ────────────────────────────────────────────────────────────────────────────

    private fun parseProprietarySysex(data: ByteArray, deviceId: String): List<ParsedMidiMessage> {
        if (data.size < 5) return emptyList()

        val payloadStart = 1 + KORG_HEADER_SKIP  // index 3
        val payloadEnd   = if ((data.last().toInt() and 0xFF) == SYSEX_END)
            data.size - 1 else data.size

        if (payloadStart >= payloadEnd) return emptyList()

        val payload = data.copyOfRange(payloadStart, payloadEnd)
        Log.d(TAG, "SysEx unwrap [$deviceId]: ${data.size}B → ${payload.size}B payload")

        return parseStandardMidi(payload, deviceId)
    }

    private fun containsEmbeddedMidi(data: ByteArray): Boolean {
        if (data.size < 6) return false
        for (i in 3 until data.size - 1) {
            val type = (data[i].toInt() and 0xFF) and 0xF0
            if (type in CHANNEL_MESSAGE_TYPES) return true
        }
        return false
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 2. STANDARD BLE MIDI PACKET (MMA/AMEI BLE MIDI 1.0)
    // ────────────────────────────────────────────────────────────────────────────

    private fun parseBleMidiPacket(data: ByteArray, deviceId: String): List<ParsedMidiMessage> {
        val messages  = mutableListOf<ParsedMidiMessage>()
        val nowUs     = SystemClock.elapsedRealtimeNanos() / 1000
        var i         = 1  // skip header byte
        var runStatus = 0

        while (i < data.size) {
            val byte = data[i].toInt() and 0xFF

            // Skip BLE timestamp bytes (bit7=1, bit6=0)
            if ((byte and 0x80) != 0 && (byte and 0x40) == 0) { i++; continue }

            // System Real-Time
            if (byte in REALTIME_BYTES) {
                messages.add(buildSystemRealTime(byte, deviceId, nowUs))
                i++; continue
            }

            // Status or running status
            if (byte and 0x80 != 0) runStatus = byte
            val status      = if (byte and 0x80 != 0) byte else runStatus
            if (status == 0) { i++; continue }

            val type        = status and 0xF0
            val channel     = status and 0x0F
            val isNewStatus = (byte and 0x80) != 0

            // ── FIX: use helper functions instead of return@when (invalid in Kotlin) ──
            when (type) {
                NOTE_OFF, NOTE_ON, POLY_PRESSURE, CONTROL_CHANGE, PITCH_BEND -> {
                    val baseIdx = if (isNewStatus) i + 1 else i
                    val j1 = skipBleTs(data, baseIdx)
                    val d1 = bleDataByte(data, j1)
                    if (d1 == null) { i = j1; i++; continue }

                    val j2 = skipBleTs(data, j1 + 1)
                    val d2 = bleDataByte(data, j2)
                    if (d2 == null) { i = j2; i++; continue }

                    i = j2
                    messages.add(buildTwoDataByte(type, channel, d1, d2, deviceId, nowUs, status))
                }
                PROGRAM_CHANGE, CHANNEL_PRESSURE -> {
                    val baseIdx = if (isNewStatus) i + 1 else i
                    val j1 = skipBleTs(data, baseIdx)
                    val d1 = bleDataByte(data, j1)
                    if (d1 == null) { i = j1; i++; continue }

                    i = j1
                    messages.add(buildOneDataByte(type, channel, d1, deviceId, nowUs))
                }
            }

            i++
        }
        return messages
    }

    private fun skipBleTs(data: ByteArray, idx: Int): Int {
        var j = idx
        while (j < data.size) {
            val b = data[j].toInt() and 0xFF
            if ((b and 0x80) != 0 && (b and 0x40) == 0) j++ else break
        }
        return j
    }

    private fun bleDataByte(data: ByteArray, idx: Int): Int? {
        if (idx >= data.size) return null
        val b = data[idx].toInt() and 0xFF
        return if (b and 0x80 == 0) b else null
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 3. STANDARD USB / SERIAL MIDI STREAM
    // ────────────────────────────────────────────────────────────────────────────

    private fun parseStandardMidi(data: ByteArray, deviceId: String): List<ParsedMidiMessage> {
        val messages  = mutableListOf<ParsedMidiMessage>()
        val nowUs     = SystemClock.elapsedRealtimeNanos() / 1000
        var i         = 0
        var runStatus = 0
        val sysexBuf  = mutableListOf<Int>()
        var inSysex   = false

        while (i < data.size) {
            val byte = data[i].toInt() and 0xFF

            // System Real-Time (can interrupt any message including SysEx)
            if (byte in REALTIME_BYTES) {
                messages.add(buildSystemRealTime(byte, deviceId, nowUs))
                i++; continue
            }

            // SysEx accumulation
            if (inSysex) {
                sysexBuf.add(byte)
                if (byte == SYSEX_END) {
                    messages.add(buildSysex(sysexBuf, deviceId, nowUs))
                    sysexBuf.clear()
                    inSysex = false
                }
                i++; continue
            }

            if (byte == SYSEX_START) {
                val remaining = data.copyOfRange(i, data.size)
                val nextMfr   = if (remaining.size > 1) remaining[1].toInt() and 0xFF else -1
                if (nextMfr in MIDI_IN_SYSEX_MANUFACTURERS || containsEmbeddedMidi(remaining)) {
                    messages.addAll(parseProprietarySysex(remaining, deviceId))
                    break
                }
                sysexBuf.clear()
                sysexBuf.add(byte)
                inSysex = true
                i++; continue
            }

            // Status or running status
            if (byte and 0x80 != 0) runStatus = byte
            val status      = if (byte and 0x80 != 0) byte else runStatus
            if (status == 0) { i++; continue }

            val type        = status and 0xF0
            val channel     = status and 0x0F
            val isNewStatus = (byte and 0x80) != 0

            // ── FIX: use explicit null checks instead of return@when ──────────────
            when (type) {
                NOTE_OFF, NOTE_ON, POLY_PRESSURE, CONTROL_CHANGE, PITCH_BEND -> {
                    if (isNewStatus) {
                        val d1 = dataAt(data, i + 1)
                        val d2 = dataAt(data, i + 2)
                        if (d1 != null && d2 != null) {
                            i += 2
                            messages.add(buildTwoDataByte(type, channel, d1, d2, deviceId, nowUs, status))
                        } else {
                            i++
                        }
                    } else {
                        // Running status: current byte is first data byte
                        val d2 = dataAt(data, i + 1)
                        if (d2 != null) {
                            i++
                            messages.add(buildTwoDataByte(type, channel, byte, d2, deviceId, nowUs, status))
                        } else {
                            i++
                        }
                    }
                }
                PROGRAM_CHANGE, CHANNEL_PRESSURE -> {
                    if (isNewStatus) {
                        val d1 = dataAt(data, i + 1)
                        if (d1 != null) {
                            i++
                            messages.add(buildOneDataByte(type, channel, d1, deviceId, nowUs))
                        } else {
                            i++
                        }
                    } else {
                        messages.add(buildOneDataByte(type, channel, byte, deviceId, nowUs))
                        i++
                    }
                    continue
                }
                0xF0 -> {
                    val msg = buildSystemCommon(status, deviceId, nowUs)
                    if (msg != null) messages.add(msg)
                }
            }

            i++
        }

        return messages
    }

    private fun dataAt(data: ByteArray, idx: Int): Int? {
        if (idx >= data.size) return null
        val b = data[idx].toInt() and 0xFF
        return if (b and 0x80 == 0) b else null
    }

    // ────────────────────────────────────────────────────────────────────────────
    // BUILDERS
    // ────────────────────────────────────────────────────────────────────────────

    private fun buildTwoDataByte(
        type: Int, channel: Int, d1: Int, d2: Int,
        deviceId: String, timestampUs: Long, rawStatus: Int,
    ): ParsedMidiMessage {
        val raw = intArrayOf(rawStatus, d1, d2)
        return when (type) {
            NOTE_OFF       -> ParsedMidiMessage("noteOff", channel, d1, d2, -1, -1, 0, -1,
                intArrayOf(), raw, timestampUs, deviceId)
            NOTE_ON        -> ParsedMidiMessage("noteOn", channel, d1, d2, -1, -1, 0, -1,
                intArrayOf(), raw, timestampUs, deviceId)
            POLY_PRESSURE  -> ParsedMidiMessage("polyPressure", channel, d1, -1, -1, -1, 0, d2,
                intArrayOf(), raw, timestampUs, deviceId)
            CONTROL_CHANGE -> ParsedMidiMessage("controlChange", channel, -1, -1, d1, d2, 0, -1,
                intArrayOf(), raw, timestampUs, deviceId)
            PITCH_BEND     -> {
                val pb = ((d2 shl 7) or d1) - 8192
                ParsedMidiMessage("pitchBend", channel, -1, -1, -1, -1, pb, -1,
                    intArrayOf(), raw, timestampUs, deviceId)
            }
            else           -> ParsedMidiMessage("unknown", channel, d1, d2, -1, -1, 0, -1,
                intArrayOf(), raw, timestampUs, deviceId)
        }
    }

    private fun buildOneDataByte(
        type: Int, channel: Int, d1: Int,
        deviceId: String, timestampUs: Long,
    ): ParsedMidiMessage {
        val raw = intArrayOf((type or channel), d1)
        return when (type) {
            PROGRAM_CHANGE   -> ParsedMidiMessage("programChange", channel, -1, -1, -1, d1, 0, -1,
                intArrayOf(), raw, timestampUs, deviceId)
            CHANNEL_PRESSURE -> ParsedMidiMessage("channelPressure", channel, -1, -1, -1, -1, 0, d1,
                intArrayOf(), raw, timestampUs, deviceId)
            else             -> ParsedMidiMessage("unknown", channel, d1, -1, -1, -1, 0, -1,
                intArrayOf(), raw, timestampUs, deviceId)
        }
    }

    private fun buildSystemRealTime(byte: Int, deviceId: String, timestampUs: Long) =
        ParsedMidiMessage(
            type = when (byte) {
                TIMING_CLOCK   -> "timingClock"
                START_MSG      -> "start"
                CONTINUE_MSG   -> "continue"
                STOP_MSG       -> "stop"
                ACTIVE_SENSING -> "activeSensing"
                SYSTEM_RESET   -> "systemReset"
                else           -> "unknown"
            },
            channel = -1, note = -1, velocity = -1,
            controller = -1, value = -1, pitchBend = 0, pressure = -1,
            sysexData = intArrayOf(), rawBytes = intArrayOf(byte),
            timestampUs = timestampUs, deviceId = deviceId,
        )

    private fun buildSysex(buf: List<Int>, deviceId: String, timestampUs: Long): ParsedMidiMessage {
        val arr = buf.toIntArray()
        return ParsedMidiMessage("sysex", -1, -1, -1, -1, -1, 0, -1,
            arr, arr, timestampUs, deviceId)
    }

    private fun buildSystemCommon(status: Int, deviceId: String, timestampUs: Long): ParsedMidiMessage? {
        if (status != TUNE_REQUEST) return null
        return ParsedMidiMessage("tuneRequest", -1, -1, -1, -1, -1, 0, -1,
            intArrayOf(), intArrayOf(status), timestampUs, deviceId)
    }

    private fun List<Int>.toIntArray() = IntArray(size) { this[it] }
}

// ── Data class ───────────────────────────────────────────────────────────────────

internal data class ParsedMidiMessage(
    val type:        String,
    val channel:     Int,
    val note:        Int,
    val velocity:    Int,
    val controller:  Int,
    val value:       Int,
    val pitchBend:   Int,
    val pressure:    Int,
    val sysexData:   IntArray,
    val rawBytes:    IntArray,
    val timestampUs: Long,
    val deviceId:    String,
) {
    fun toMap(): Map<String, Any> = hashMapOf(
        "type"        to type,
        "channel"     to channel,
        "note"        to note,
        "velocity"    to velocity,
        "controller"  to controller,
        "value"       to value,
        "pitchBend"   to pitchBend,
        "pressure"    to pressure,
        "sysexData"   to sysexData.toList(),
        "rawBytes"    to rawBytes.toList(),
        "timestampUs" to timestampUs,
        "deviceId"    to deviceId,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedMidiMessage) return false
        return type == other.type && channel == other.channel && note == other.note &&
                velocity == other.velocity && timestampUs == other.timestampUs &&
                deviceId == other.deviceId
    }

    override fun hashCode(): Int {
        var r = type.hashCode()
        r = 31 * r + channel; r = 31 * r + note; r = 31 * r + velocity
        r = 31 * r + timestampUs.hashCode(); r = 31 * r + deviceId.hashCode()
        return r
    }
}