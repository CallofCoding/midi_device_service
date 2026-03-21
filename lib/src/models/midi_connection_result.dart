import 'midi_device.dart';

// ─────────────────────────────────────────────
// CONNECTION RESULT
// ─────────────────────────────────────────────

/// Result returned from [MidiDeviceService.connectDevice].
class MidiConnectionResult {
  /// Whether the connection was successful.
  final bool success;

  /// The device that was connected (or attempted).
  final MidiDevice device;

  /// Human-readable error message on failure. Null on success.
  final String? errorMessage;

  /// Android error code if available. Null on success.
  final int? errorCode;

  const MidiConnectionResult._({
    required this.success,
    required this.device,
    this.errorMessage,
    this.errorCode,
  });

  factory MidiConnectionResult.success(MidiDevice device) {
    return MidiConnectionResult._(success: true, device: device);
  }

  factory MidiConnectionResult.failure(
    MidiDevice device, {
    required String errorMessage,
    int? errorCode,
  }) {
    return MidiConnectionResult._(
      success:      false,
      device:       device,
      errorMessage: errorMessage,
      errorCode:    errorCode,
    );
  }

  factory MidiConnectionResult.fromMap(Map<dynamic, dynamic> map) {
    final device = MidiDevice.fromMap(Map<dynamic, dynamic>.from(map['device'] as Map));
    final success = map['success'] as bool? ?? false;
    return MidiConnectionResult._(
      success:      success,
      device:       device,
      errorMessage: map['errorMessage']?.toString(),
      errorCode:    map['errorCode'] as int?,
    );
  }

  @override
  String toString() => success
      ? 'MidiConnectionResult.success(${device.name})'
      : 'MidiConnectionResult.failure(${device.name}, error: $errorMessage)';
}

// ─────────────────────────────────────────────
// DEVICE INFO
// ─────────────────────────────────────────────

/// Extended metadata about a MIDI device.
class MidiDeviceInfo {
  final String id;
  final String name;
  final String manufacturer;
  final String product;
  final String version;
  final MidiDeviceType type;
  final int inputPortCount;
  final int outputPortCount;

  /// BLE service UUID for Bluetooth devices. Empty for USB.
  final String bleServiceUuid;

  /// Android USB Vendor ID. -1 for Bluetooth devices.
  final int usbVendorId;

  /// Android USB Product ID. -1 for Bluetooth devices.
  final int usbProductId;

  /// Estimated round-trip latency in milliseconds. -1 if unknown.
  final int estimatedLatencyMs;

  /// Whether the device is currently open/connected.
  final bool isConnected;

  const MidiDeviceInfo({
    required this.id,
    required this.name,
    required this.manufacturer,
    required this.product,
    required this.version,
    required this.type,
    required this.inputPortCount,
    required this.outputPortCount,
    required this.bleServiceUuid,
    required this.usbVendorId,
    required this.usbProductId,
    required this.estimatedLatencyMs,
    required this.isConnected,
  });

  factory MidiDeviceInfo.fromMap(Map<dynamic, dynamic> map) {
    return MidiDeviceInfo(
      id:                  map['id']?.toString()           ?? '',
      name:                map['name']?.toString()         ?? '',
      manufacturer:        map['manufacturer']?.toString() ?? '',
      product:             map['product']?.toString()      ?? '',
      version:             map['version']?.toString()      ?? '',
      type:                MidiDeviceType.fromString(map['type']?.toString() ?? ''),
      inputPortCount:      (map['inputPortCount']      as int?) ?? 0,
      outputPortCount:     (map['outputPortCount']     as int?) ?? 0,
      bleServiceUuid:      map['bleServiceUuid']?.toString()    ?? '',
      usbVendorId:         (map['usbVendorId']         as int?) ?? -1,
      usbProductId:        (map['usbProductId']        as int?) ?? -1,
      estimatedLatencyMs:  (map['estimatedLatencyMs']  as int?) ?? -1,
      isConnected:         (map['isConnected']         as bool?) ?? false,
    );
  }

  @override
  String toString() =>
      'MidiDeviceInfo(id: $id, name: $name, type: ${type.value}, '
      'latency: ${estimatedLatencyMs}ms, connected: $isConnected)';
}

// ─────────────────────────────────────────────
// PERMISSION STATUS
// ─────────────────────────────────────────────

enum MidiPermissionStatus {
  granted,
  denied,
  permanentlyDenied,
  restricted,
  unknown;

  bool get isGranted => this == MidiPermissionStatus.granted;

  static MidiPermissionStatus fromString(String value) {
    switch (value.toLowerCase()) {
      case 'granted':           return MidiPermissionStatus.granted;
      case 'denied':            return MidiPermissionStatus.denied;
      case 'permanentlydenied': return MidiPermissionStatus.permanentlyDenied;
      case 'restricted':        return MidiPermissionStatus.restricted;
      default:                  return MidiPermissionStatus.unknown;
    }
  }
}
