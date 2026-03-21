// ignore_for_file: constant_identifier_names

class MidiConstants {
  MidiConstants._();

  // ─── Channel Voice Messages ───────────────────────────────────────────────
  static const int NOTE_OFF         = 0x80;
  static const int NOTE_ON          = 0x90;
  static const int POLY_PRESSURE    = 0xA0;
  static const int CONTROL_CHANGE   = 0xB0;
  static const int PROGRAM_CHANGE   = 0xC0;
  static const int CHANNEL_PRESSURE = 0xD0;
  static const int PITCH_BEND       = 0xE0;

  // ─── System ──────────────────────────────────────────────────────────────
  static const int SYSEX_START    = 0xF0;
  static const int SYSEX_END      = 0xF7;
  static const int TIMING_CLOCK   = 0xF8;
  static const int START          = 0xFA;
  static const int CONTINUE       = 0xFB;
  static const int STOP           = 0xFC;
  static const int ACTIVE_SENSING = 0xFE;
  static const int SYSTEM_RESET   = 0xFF;
  static const int TUNE_REQUEST   = 0xF6;

  // ─── Common CC numbers ────────────────────────────────────────────────────
  static const int CC_SUSTAIN_PEDAL = 64;
  static const int CC_ALL_NOTES_OFF = 123;

  // ─── Note range ───────────────────────────────────────────────────────────
  static const int MIN_NOTE = 0;
  static const int MAX_NOTE = 127;
  static const int MIDDLE_C = 60;

  // ─── Platform channel names ───────────────────────────────────────────────
  static const String methodChannel      = 'com.pianopilot/midi';
  static const String eventChannelMidi   = 'com.pianopilot/midi/events';
  static const String eventChannelDevice = 'com.pianopilot/midi/devices';
  static const String eventChannelScan   = 'com.pianopilot/midi/scan';
  static const String eventChannelBtState = 'com.pianopilot/midi/bt_state';

  // ─── Method names ─────────────────────────────────────────────────────────
  static const String methodGetDevices            = 'getDevices';
  static const String methodConnectDevice         = 'connectDevice';
  static const String methodDisconnectDevice      = 'disconnectDevice';
  static const String methodDisconnectAll         = 'disconnectAll';
  static const String methodGetConnectedDevices   = 'getConnectedDevices';
  static const String methodGetDeviceInfo         = 'getDeviceInfo';
  static const String methodGetLatency            = 'getLatency';
  static const String methodIsConnected           = 'isDeviceConnected';
  static const String methodRequestBlePermissions = 'requestBluetoothPermissions';
  static const String methodHasBlePermissions     = 'hasBluetoothPermissions';
  static const String methodRequestUsbPermission  = 'requestUsbPermission';
  static const String methodStartScan             = 'startScan';
  static const String methodStopScan              = 'stopScan';
  static const String methodIsScanning            = 'isScanning';
  static const String methodGetBtState            = 'getBluetoothState';

  // ─── Note name helpers ────────────────────────────────────────────────────
  static const List<String> _noteNames = [
    'C','C#','D','D#','E','F','F#','G','G#','A','A#','B',
  ];

  static String noteName(int midiNote) {
    final name   = _noteNames[midiNote % 12];
    final octave = (midiNote ~/ 12) - 1;
    return '$name$octave';
  }

  static int? noteFromName(String name) {
    final match = RegExp(r'^([A-Ga-g]#?)(-?\d+)$').firstMatch(name.trim());
    if (match == null) return null;
    final noteStr = match.group(1)!.toUpperCase();
    final octave  = int.parse(match.group(2)!);
    final idx     = _noteNames.indexOf(noteStr);
    if (idx < 0) return null;
    return (octave + 1) * 12 + idx;
  }
}