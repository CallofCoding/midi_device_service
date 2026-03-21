# midi_device_service

A production-grade Flutter plugin for **real-time MIDI input** via USB and Bluetooth Low Energy (BLE) on Android. Built directly on `android.media.midi` for minimum latency.

## Features

- ✅ USB MIDI — ~1–3 ms latency
- ✅ BLE MIDI — ~15–30 ms latency (via `CONNECTION_PRIORITY_HIGH`)
- ✅ Automatic BLE/USB device detection
- ✅ Typed MIDI messages (NoteOn, NoteOff, CC, PitchBend, SysEx, etc.)
- ✅ NoteOff deduplication (handles keyboards that send 3× NoteOff)
- ✅ NoteOn vel=0 → NoteOff normalization
- ✅ Running status support
- ✅ BLE MIDI packet parsing (MMA BLE MIDI 1.0 spec)
- ✅ Runtime Bluetooth permission handling (Android 12+)
- ✅ Device connect/disconnect event stream
- ✅ Convenience streams (noteOnStream, noteOffStream, sustainPedalStream, etc.)

## Setup

### 1. Add to your app's `pubspec.yaml`

```yaml
dependencies:
  midi_device_service:
    path: ../midi_device_service  # or your package path
```

### 2. Add to your app's `android/app/src/main/AndroidManifest.xml`

```xml
<!-- MIDI -->
<uses-feature android:name="android.software.midi" android:required="false" />
<uses-feature android:name="android.hardware.usb.host" android:required="false" />

<!-- Bluetooth -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

### 3. Set `minSdk` to at least 23 in `android/app/build.gradle`

```groovy
defaultConfig {
    minSdk 23
}
```

## Usage

```dart
import 'package:midi_device_service/midi_device_service.dart';

final midi = MidiDeviceService.instance;

// Initialize once (e.g. in main() or initState)
await midi.initialize();

// Request BLE permissions (Android 12+)
final status = await midi.requestBluetoothPermissions();

// List devices
final devices = await midi.getDevices();

// Connect
final result = await midi.connectDevice(devices.first);
if (result.success) {
  print('Connected: ${result.device.name}');
}

// Listen for MIDI events
midi.noteOnStream.listen((msg) {
  print('Note ON: ${msg.noteName} vel:${msg.velocity} ch:${msg.channel}');
});

midi.noteOffStream.listen((msg) {
  print('Note OFF: ${msg.noteName}');
});

midi.sustainPedalStream.listen((msg) {
  print('Sustain: ${msg.isSustainOn ? "ON" : "OFF"}');
});

// Listen for device state changes
midi.deviceStateStream.listen((state) {
  print('Device ${state.device.name}: ${state.status}');
});

// Cleanup
await midi.dispose();
```

## API Reference

### MidiDeviceService

| Method | Returns | Description |
|--------|---------|-------------|
| `initialize()` | `Future<void>` | Boot the service. Call once. |
| `dispose()` | `Future<void>` | Release all resources. |
| `getDevices()` | `Future<List<MidiDevice>>` | All available devices (USB + BLE). |
| `connectDevice(device)` | `Future<MidiConnectionResult>` | Open a device. |
| `disconnectDevice(id)` | `Future<void>` | Close a specific device. |
| `disconnectAll()` | `Future<void>` | Close all devices. |
| `isConnected(id)` | `bool` | Cached connection state. |
| `isDeviceConnected(id)` | `Future<bool>` | Native connection check. |
| `getDeviceInfo(id)` | `Future<MidiDeviceInfo>` | Extended device metadata. |
| `getLatencyMs()` | `Future<int>` | Estimated latency in ms. |
| `requestBluetoothPermissions()` | `Future<MidiPermissionStatus>` | Request BLE runtime perms. |
| `hasBluetoothPermissions()` | `Future<bool>` | Check BLE perms. |
| `requestUsbPermission(id)` | `Future<bool>` | Request USB device perm. |
| `hasRequiredPermissions(device)` | `Future<bool>` | Check perms for a device. |

### Streams

| Stream | Type | Description |
|--------|------|-------------|
| `midiEventStream` | `Stream<MidiMessage>` | All MIDI messages. |
| `noteOnStream` | `Stream<MidiMessage>` | NoteOn only (vel > 0). |
| `noteOffStream` | `Stream<MidiMessage>` | NoteOff only. |
| `noteStream` | `Stream<MidiMessage>` | NoteOn + NoteOff. |
| `controlChangeStream` | `Stream<MidiMessage>` | CC messages only. |
| `sustainPedalStream` | `Stream<MidiMessage>` | CC 64 only. |
| `pitchBendStream` | `Stream<MidiMessage>` | Pitch bend only. |
| `deviceStateStream` | `Stream<MidiDeviceState>` | Device connect/disconnect. |

## Why not flutter_midi_command?

`flutter_midi_command` uses a generic BLE layer that delivers BLE MIDI packets in 1–3 second batches due to Android's default BLE connection interval. This plugin uses `android.media.midi` directly and requests `CONNECTION_PRIORITY_HIGH` immediately on BLE device open, reducing BLE latency to ~15–30 ms.

## Latency Notes

| Connection | Expected Latency |
|------------|-----------------|
| USB MIDI | 1–3 ms |
| BLE MIDI (this plugin) | 15–30 ms |
| BLE MIDI (flutter_midi_command) | 1–3 seconds |

BLE latency depends on the Android device's BLE stack and the keyboard's firmware. Results may vary.

## Android Requirements

- `minSdk`: 23 (Android 6.0)
- `android.media.midi` API (available from API 23)
- `CONNECTION_PRIORITY_HIGH` (available from API 21)
