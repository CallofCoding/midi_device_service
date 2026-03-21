/// A BLE MIDI device discovered during an active scan.
class MidiScanResult {
  /// MAC address of the device — used as [deviceId] when calling connectDevice().
  final String id;

  /// Human-readable device name.
  final String name;

  /// MAC address (same as [id] for BLE scan results).
  final String address;

  /// Received Signal Strength Indicator in dBm. Closer to 0 = stronger signal.
  final int rssi;

  const MidiScanResult({
    required this.id,
    required this.name,
    required this.address,
    required this.rssi,
  });

  /// Signal quality label based on RSSI.
  String get signalLabel {
    if (rssi >= -60) return 'Excellent';
    if (rssi >= -70) return 'Good';
    if (rssi >= -80) return 'Fair';
    return 'Weak';
  }

  factory MidiScanResult.fromMap(Map<dynamic, dynamic> map) {
    return MidiScanResult(
      id:      map['id']?.toString()      ?? '',
      name:    map['name']?.toString()    ?? 'BLE MIDI Device',
      address: map['address']?.toString() ?? '',
      rssi:    (map['rssi'] as int?)      ?? -100,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is MidiScanResult && other.id == id;

  @override
  int get hashCode => id.hashCode;

  @override
  String toString() => 'MidiScanResult(name: $name, address: $address, rssi: $rssi)';
}