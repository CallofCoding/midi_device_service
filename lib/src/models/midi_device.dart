/// Represents a physical MIDI device discovered by the plugin.
class MidiDevice {
  /// Unique identifier assigned by Android MidiManager (device id as string).
  final String id;

  /// Human-readable device name (e.g. "Casio CT-S300").
  final String name;

  /// Connection type of this device.
  final MidiDeviceType type;

  /// Number of input ports available on this device.
  final int inputPortCount;

  /// Number of output ports available on this device.
  final int outputPortCount;

  /// Manufacturer string if provided by the device, otherwise empty.
  final String manufacturer;

  /// Product string if provided by the device, otherwise empty.
  final String product;

  /// BLE MAC address for Bluetooth devices, empty for USB.
  final String address;

  const MidiDevice({
    required this.id,
    required this.name,
    required this.type,
    required this.inputPortCount,
    required this.outputPortCount,
    this.manufacturer = '',
    this.product = '',
    this.address = '',
  });

  /// Whether this device has at least one input port (can send MIDI to app).
  bool get hasInput => inputPortCount > 0;

  /// Whether this device has at least one output port (app can send MIDI to it).
  bool get hasOutput => outputPortCount > 0;

  /// Whether this is a Bluetooth Low Energy MIDI device.
  bool get isBle => type == MidiDeviceType.bluetooth;

  /// Whether this is a USB MIDI device.
  bool get isUsb => type == MidiDeviceType.usb;

  factory MidiDevice.fromMap(Map<dynamic, dynamic> map) {
    return MidiDevice(
      id:              map['id']?.toString()           ?? '',
      name:            map['name']?.toString()         ?? 'Unknown Device',
      type:            MidiDeviceType.fromString(map['type']?.toString() ?? ''),
      inputPortCount:  (map['inputPortCount']  as int?) ?? 0,
      outputPortCount: (map['outputPortCount'] as int?) ?? 0,
      manufacturer:    map['manufacturer']?.toString() ?? '',
      product:         map['product']?.toString()      ?? '',
      address:         map['address']?.toString()      ?? '',
    );
  }

  Map<String, dynamic> toMap() => {
    'id':              id,
    'name':            name,
    'type':            type.value,
    'inputPortCount':  inputPortCount,
    'outputPortCount': outputPortCount,
    'manufacturer':    manufacturer,
    'product':         product,
    'address':         address,
  };

  MidiDevice copyWith({
    String?         id,
    String?         name,
    MidiDeviceType? type,
    int?            inputPortCount,
    int?            outputPortCount,
    String?         manufacturer,
    String?         product,
    String?         address,
  }) {
    return MidiDevice(
      id:              id              ?? this.id,
      name:            name            ?? this.name,
      type:            type            ?? this.type,
      inputPortCount:  inputPortCount  ?? this.inputPortCount,
      outputPortCount: outputPortCount ?? this.outputPortCount,
      manufacturer:    manufacturer    ?? this.manufacturer,
      product:         product         ?? this.product,
      address:         address         ?? this.address,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is MidiDevice && runtimeType == other.runtimeType && id == other.id;

  @override
  int get hashCode => id.hashCode;

  @override
  String toString() =>
      'MidiDevice(id: $id, name: $name, type: ${type.value}, '
      'inputs: $inputPortCount, outputs: $outputPortCount)';
}

// ─────────────────────────────────────────────
// DEVICE TYPE
// ─────────────────────────────────────────────

enum MidiDeviceType {
  usb('usb'),
  bluetooth('bluetooth'),
  unknown('unknown');

  const MidiDeviceType(this.value);
  final String value;

  static MidiDeviceType fromString(String value) {
    return MidiDeviceType.values.firstWhere(
      (e) => e.value == value.toLowerCase(),
      orElse: () => MidiDeviceType.unknown,
    );
  }
}
