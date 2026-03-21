import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../../midi_device_service.dart';


class MidiDeviceService {
  MidiDeviceService._();
  static final MidiDeviceService instance = MidiDeviceService._();

  // ── Channels ─────────────────────────────────────────────────────────────
  static const _method       = MethodChannel(MidiConstants.methodChannel);
  static const _midiEvents   = EventChannel(MidiConstants.eventChannelMidi);
  static const _deviceEvents = EventChannel(MidiConstants.eventChannelDevice);
  static const _scanEvents   = EventChannel(MidiConstants.eventChannelScan);
  static const _btEvents     = EventChannel(MidiConstants.eventChannelBtState);

  // ── State ─────────────────────────────────────────────────────────────────
  bool _initialized = false;
  bool get isInitialized => _initialized;
  bool _scanning = false;
  bool get isScanning => _scanning;

  final Map<String, MidiDevice> _connectedDevices = {};

  // ── Stream controllers ────────────────────────────────────────────────────
  final _midiController   = StreamController<MidiMessage>.broadcast();
  final _deviceController = StreamController<MidiDeviceState>.broadcast();
  final _scanController   = StreamController<MidiScanResult>.broadcast();
  final _btController     = StreamController<MidiBtState>.broadcast();

  StreamSubscription<dynamic>? _midiSub;
  StreamSubscription<dynamic>? _deviceSub;
  StreamSubscription<dynamic>? _scanSub;
  StreamSubscription<dynamic>? _btSub;

  final Map<String, int> _lastNoteOffTs = {};

  // ── Public streams ────────────────────────────────────────────────────────

  Stream<MidiMessage>     get midiEventStream    => _midiController.stream;
  Stream<MidiDeviceState> get deviceStateStream  => _deviceController.stream;
  Stream<MidiScanResult>  get scanResultStream   => _scanController.stream;

  /// Emits whenever the system Bluetooth adapter state changes.
  /// Also emits immediately with the current state on first listen.
  Stream<MidiBtState>     get btStateStream      => _btController.stream;

  Stream<MidiMessage> get noteOnStream       => midiEventStream.where((m) => m.isNoteOn);
  Stream<MidiMessage> get noteOffStream      => midiEventStream.where((m) => m.isNoteOff);
  Stream<MidiMessage> get noteStream         => midiEventStream.where((m) => m.isNote);
  Stream<MidiMessage> get controlChangeStream =>
      midiEventStream.where((m) => m.type == MidiMessageType.controlChange);
  Stream<MidiMessage> get sustainPedalStream =>
      midiEventStream.where((m) => m.isSustainPedal);

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  Future<void> initialize() async {
    if (_initialized) return;

    _midiSub = _midiEvents.receiveBroadcastStream().listen(
      _onNativeMidi, onError: (e) => debugPrint('[MIDI] midi error: $e'),
    );
    _deviceSub = _deviceEvents.receiveBroadcastStream().listen(
      _onNativeDevice, onError: (e) => debugPrint('[MIDI] device error: $e'),
    );
    _scanSub = _scanEvents.receiveBroadcastStream().listen(
      _onNativeScan, onError: (e) => debugPrint('[MIDI] scan error: $e'),
    );
    _btSub = _btEvents.receiveBroadcastStream().listen(
      _onNativeBtState, onError: (e) => debugPrint('[MIDI] bt state error: $e'),
    );

    _initialized = true;
    debugPrint('[MidiDeviceService] initialized');
  }

  Future<void> dispose() async {
    await disconnectAll();
    await _midiSub?.cancel();
    await _deviceSub?.cancel();
    await _scanSub?.cancel();
    await _btSub?.cancel();
    await _midiController.close();
    await _deviceController.close();
    await _scanController.close();
    await _btController.close();
    _initialized = false;
  }

  // ── Bluetooth state ───────────────────────────────────────────────────────

  /// Returns the current Bluetooth adapter state immediately (one-shot call).
  Future<MidiBtState> getBluetoothState() async {
    _assertInitialized();
    final result = await _method.invokeMethod<String>(MidiConstants.methodGetBtState);
    return MidiBtState.fromString(result ?? '');
  }

  // ── Device discovery ──────────────────────────────────────────────────────

  Future<List<MidiDevice>> getDevices() async {
    _assertInitialized();
    try {
      final result = await _method.invokeMethod<List<dynamic>>(
          MidiConstants.methodGetDevices);
      return (result ?? [])
          .map((e) => MidiDevice.fromMap(Map<dynamic, dynamic>.from(e as Map)))
          .toList();
    } on PlatformException catch (e) {
      debugPrint('[MIDI] getDevices error: ${e.message}');
      rethrow;
    }
  }

  Future<List<MidiDevice>> getConnectedDevices() async {
    _assertInitialized();
    final result = await _method.invokeMethod<List<dynamic>>(
        MidiConstants.methodGetConnectedDevices);
    return (result ?? [])
        .map((e) => MidiDevice.fromMap(Map<dynamic, dynamic>.from(e as Map)))
        .toList();
  }

  // ── BLE Scan ──────────────────────────────────────────────────────────────

  Future<void> startScan() async {
    _assertInitialized();
    try {
      await _method.invokeMethod<void>(MidiConstants.methodStartScan);
      _scanning = true;
    } on PlatformException catch (e) {
      debugPrint('[MIDI] startScan error: ${e.message}');
      rethrow;
    }
  }

  Future<void> stopScan() async {
    _assertInitialized();
    try {
      await _method.invokeMethod<void>(MidiConstants.methodStopScan);
      _scanning = false;
    } on PlatformException catch (e) {
      debugPrint('[MIDI] stopScan error: ${e.message}');
    }
  }

  // ── Connection ────────────────────────────────────────────────────────────

  Future<MidiConnectionResult> connectDevice(MidiDevice device) async {
    _assertInitialized();
    try {
      final result = await _method.invokeMethod<Map<dynamic, dynamic>>(
        MidiConstants.methodConnectDevice,
        {'deviceId': device.id},
      );
      final r = MidiConnectionResult.fromMap(result!);
      if (r.success) _connectedDevices[device.id] = device;
      return r;
    } on PlatformException catch (e) {
      return MidiConnectionResult.failure(device,
          errorMessage: e.message ?? 'Unknown error');
    }
  }

  Future<void> disconnectDevice(String deviceId) async {
    _assertInitialized();
    try {
      await _method.invokeMethod<void>(
          MidiConstants.methodDisconnectDevice, {'deviceId': deviceId});
      _connectedDevices.remove(deviceId);
    } on PlatformException catch (e) {
      debugPrint('[MIDI] disconnectDevice error: ${e.message}');
      rethrow;
    }
  }

  Future<void> disconnectAll() async {
    if (!_initialized) return;
    try {
      await _method.invokeMethod<void>(MidiConstants.methodDisconnectAll);
      _connectedDevices.clear();
    } on PlatformException catch (e) {
      debugPrint('[MIDI] disconnectAll error: ${e.message}');
    }
  }

  bool isConnected(String deviceId) => _connectedDevices.containsKey(deviceId);

  Future<bool> isDeviceConnected(String deviceId) async {
    _assertInitialized();
    return await _method.invokeMethod<bool>(
        MidiConstants.methodIsConnected, {'deviceId': deviceId}) ??
        false;
  }

  // ── Permissions ───────────────────────────────────────────────────────────

  Future<MidiPermissionStatus> requestBluetoothPermissions() async {
    _assertInitialized();
    final result = await _method
        .invokeMethod<String>(MidiConstants.methodRequestBlePermissions);
    return MidiPermissionStatus.fromString(result ?? '');
  }

  Future<bool> hasBluetoothPermissions() async {
    _assertInitialized();
    return await _method
        .invokeMethod<bool>(MidiConstants.methodHasBlePermissions) ??
        false;
  }

  Future<bool> requestUsbPermission(String deviceId) async {
    _assertInitialized();
    return await _method.invokeMethod<bool>(
        MidiConstants.methodRequestUsbPermission,
        {'deviceId': deviceId}) ??
        false;
  }

  Future<bool> hasRequiredPermissions(MidiDevice device) async {
    if (device.isBle) return hasBluetoothPermissions();
    return true;
  }

  // ── Diagnostics ───────────────────────────────────────────────────────────

  Future<MidiDeviceInfo> getDeviceInfo(String deviceId) async {
    _assertInitialized();
    final result = await _method.invokeMethod<Map<dynamic, dynamic>>(
        MidiConstants.methodGetDeviceInfo, {'deviceId': deviceId});
    return MidiDeviceInfo.fromMap(result!);
  }

  Future<int> getLatencyMs() async {
    _assertInitialized();
    return await _method.invokeMethod<int>(MidiConstants.methodGetLatency) ?? -1;
  }

  // ── Snapshots ─────────────────────────────────────────────────────────────

  List<MidiDevice> get connectedDevices =>
      List.unmodifiable(_connectedDevices.values.toList());
  bool get hasConnectedDevice => _connectedDevices.isNotEmpty;
  MidiDevice? get primaryDevice =>
      _connectedDevices.isEmpty ? null : _connectedDevices.values.first;

  // ── Native event handlers ─────────────────────────────────────────────────

  void _onNativeMidi(dynamic event) {
    if (event is! Map) return;
    try {
      final msg = _processMessage(MidiMessage.fromMap(event));
      if (msg != null) _midiController.add(msg);
    } catch (e) {
      debugPrint('[MIDI] parse error: $e');
    }
  }

  void _onNativeDevice(dynamic event) {
    if (event is! Map) return;
    try {
      final state = MidiDeviceState.fromMap(event);
      if (state.isConnected) {
        _connectedDevices[state.device.id] = state.device;
      } else if (state.isDisconnected) {
        _connectedDevices.remove(state.device.id);
      }
      _deviceController.add(state);
    } catch (e) {
      debugPrint('[MIDI] device event error: $e');
    }
  }

  void _onNativeScan(dynamic event) {
    if (event is! Map) return;
    final map  = Map<dynamic, dynamic>.from(event);
    final type = map['event']?.toString();
    if (type == 'result') {
      try {
        _scanController.add(MidiScanResult.fromMap(map));
      } catch (e) {
        debugPrint('[MIDI] scan result error: $e');
      }
    } else if (type == 'stopped') {
      _scanning = false;
    }
  }

  void _onNativeBtState(dynamic event) {
    if (event is! String) return;
    _btController.add(MidiBtState.fromString(event));
  }

  MidiMessage? _processMessage(MidiMessage message) {
    if (message.type == MidiMessageType.noteOn && message.velocity == 0) {
      message = MidiMessage.noteOff(
        channel: message.channel, note: message.note,
        velocity: 0, deviceId: message.deviceId,
        timestampUs: message.timestampUs, rawBytes: message.rawBytes,
      );
    }
    if (message.type == MidiMessageType.noteOff) {
      final key   = '${message.deviceId}_${message.channel}_${message.note}';
      final last  = _lastNoteOffTs[key];
      const window = 10000;
      if (last != null && (message.timestampUs - last).abs() < window) {
        return null;
      }
      _lastNoteOffTs[key] = message.timestampUs;
    }
    return message;
  }

  void _assertInitialized() {
    assert(_initialized,
    'MidiDeviceService not initialized. Call initialize() first.');
  }
}