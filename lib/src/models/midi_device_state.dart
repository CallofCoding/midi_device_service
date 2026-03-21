import 'midi_device.dart';

/// Represents a state change event for a MIDI device.
class MidiDeviceState {
  /// The device this state change is for.
  final MidiDevice device;

  /// The new status of the device.
  final MidiConnectionStatus status;

  /// Human-readable error message. Only populated when [status] is [MidiConnectionStatus.error].
  final String? errorMessage;

  /// Error code from Android if available. Only populated on error.
  final int? errorCode;

  /// Timestamp when this state change occurred (milliseconds since epoch).
  final int timestampMs;

  const MidiDeviceState({
    required this.device,
    required this.status,
    this.errorMessage,
    this.errorCode,
    required this.timestampMs,
  });

  bool get isConnected    => status == MidiConnectionStatus.connected;
  bool get isDisconnected => status == MidiConnectionStatus.disconnected;
  bool get isError        => status == MidiConnectionStatus.error;
  bool get isReconnecting => status == MidiConnectionStatus.reconnecting;
  bool get isConnecting   => status == MidiConnectionStatus.connecting;

  factory MidiDeviceState.fromMap(Map<dynamic, dynamic> map) {
    return MidiDeviceState(
      device:       MidiDevice.fromMap(Map<dynamic, dynamic>.from(map['device'] as Map)),
      status:       MidiConnectionStatus.fromString(map['status']?.toString() ?? ''),
      errorMessage: map['errorMessage']?.toString(),
      errorCode:    map['errorCode'] as int?,
      timestampMs:  (map['timestampMs'] as int?) ?? DateTime.now().millisecondsSinceEpoch,
    );
  }

  Map<String, dynamic> toMap() => {
    'device':       device.toMap(),
    'status':       status.value,
    'errorMessage': errorMessage,
    'errorCode':    errorCode,
    'timestampMs':  timestampMs,
  };

  @override
  String toString() =>
      'MidiDeviceState(device: ${device.name}, status: ${status.value}'
      '${errorMessage != null ? ", error: $errorMessage" : ""})';
}

// ─────────────────────────────────────────────
// CONNECTION STATUS
// ─────────────────────────────────────────────

enum MidiConnectionStatus {
  connecting('connecting'),
  connected('connected'),
  disconnected('disconnected'),
  reconnecting('reconnecting'),
  error('error');

  const MidiConnectionStatus(this.value);
  final String value;

  static MidiConnectionStatus fromString(String value) {
    return MidiConnectionStatus.values.firstWhere(
      (e) => e.value == value.toLowerCase(),
      orElse: () => MidiConnectionStatus.error,
    );
  }
}
