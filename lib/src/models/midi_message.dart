import '../constants/midi_constants.dart';

/// A fully parsed, typed MIDI message received from a connected device.
class MidiMessage {
  /// The type of MIDI message.
  final MidiMessageType type;

  /// MIDI channel (0–15). -1 for system messages that have no channel.
  final int channel;

  /// Note number (0–127) for NoteOn / NoteOff / PolyPressure. -1 otherwise.
  final int note;

  /// Velocity (0–127) for NoteOn / NoteOff. -1 otherwise.
  final int velocity;

  /// Controller number for ControlChange. -1 otherwise.
  final int controller;

  /// Controller value for ControlChange / Program number for ProgramChange. -1 otherwise.
  final int value;

  /// Pitch bend value (-8192 to +8191). 0 for non-pitch-bend messages.
  final int pitchBend;

  /// Pressure value for ChannelPressure / PolyPressure. -1 otherwise.
  final int pressure;

  /// Raw SysEx bytes including 0xF0 and 0xF7. Empty for non-SysEx messages.
  final List<int> sysexData;

  /// Raw original bytes as received from the device (after BLE unwrapping).
  final List<int> rawBytes;

  /// Monotonic timestamp in microseconds when the message was received
  /// (from Android SystemClock.elapsedRealtimeNanos() / 1000).
  final int timestampUs;

  /// ID of the device this message came from.
  final String deviceId;

  const MidiMessage({
    required this.type,
    required this.channel,
    required this.note,
    required this.velocity,
    required this.controller,
    required this.value,
    required this.pitchBend,
    required this.pressure,
    required this.sysexData,
    required this.rawBytes,
    required this.timestampUs,
    required this.deviceId,
  });

  // ─── Convenience Constructors ───────────────────────────────────────────────

  factory MidiMessage.noteOn({
    required int channel,
    required int note,
    required int velocity,
    required String deviceId,
    required int timestampUs,
    List<int> rawBytes = const [],
  }) {
    return MidiMessage(
      type:        MidiMessageType.noteOn,
      channel:     channel,
      note:        note,
      velocity:    velocity,
      controller:  -1,
      value:       -1,
      pitchBend:   0,
      pressure:    -1,
      sysexData:   const [],
      rawBytes:    rawBytes,
      timestampUs: timestampUs,
      deviceId:    deviceId,
    );
  }

  factory MidiMessage.noteOff({
    required int channel,
    required int note,
    required int velocity,
    required String deviceId,
    required int timestampUs,
    List<int> rawBytes = const [],
  }) {
    return MidiMessage(
      type:        MidiMessageType.noteOff,
      channel:     channel,
      note:        note,
      velocity:    velocity,
      controller:  -1,
      value:       -1,
      pitchBend:   0,
      pressure:    -1,
      sysexData:   const [],
      rawBytes:    rawBytes,
      timestampUs: timestampUs,
      deviceId:    deviceId,
    );
  }

  factory MidiMessage.controlChange({
    required int channel,
    required int controller,
    required int value,
    required String deviceId,
    required int timestampUs,
    List<int> rawBytes = const [],
  }) {
    return MidiMessage(
      type:        MidiMessageType.controlChange,
      channel:     channel,
      note:        -1,
      velocity:    -1,
      controller:  controller,
      value:       value,
      pitchBend:   0,
      pressure:    -1,
      sysexData:   const [],
      rawBytes:    rawBytes,
      timestampUs: timestampUs,
      deviceId:    deviceId,
    );
  }

  factory MidiMessage.pitchBend({
    required int channel,
    required int pitchBend,
    required String deviceId,
    required int timestampUs,
    List<int> rawBytes = const [],
  }) {
    return MidiMessage(
      type:        MidiMessageType.pitchBend,
      channel:     channel,
      note:        -1,
      velocity:    -1,
      controller:  -1,
      value:       -1,
      pitchBend:   pitchBend,
      pressure:    -1,
      sysexData:   const [],
      rawBytes:    rawBytes,
      timestampUs: timestampUs,
      deviceId:    deviceId,
    );
  }

  factory MidiMessage.programChange({
    required int channel,
    required int program,
    required String deviceId,
    required int timestampUs,
    List<int> rawBytes = const [],
  }) {
    return MidiMessage(
      type:        MidiMessageType.programChange,
      channel:     channel,
      note:        -1,
      velocity:    -1,
      controller:  -1,
      value:       program,
      pitchBend:   0,
      pressure:    -1,
      sysexData:   const [],
      rawBytes:    rawBytes,
      timestampUs: timestampUs,
      deviceId:    deviceId,
    );
  }

  factory MidiMessage.channelPressure({
    required int channel,
    required int pressure,
    required String deviceId,
    required int timestampUs,
    List<int> rawBytes = const [],
  }) {
    return MidiMessage(
      type:        MidiMessageType.channelPressure,
      channel:     channel,
      note:        -1,
      velocity:    -1,
      controller:  -1,
      value:       -1,
      pitchBend:   0,
      pressure:    pressure,
      sysexData:   const [],
      rawBytes:    rawBytes,
      timestampUs: timestampUs,
      deviceId:    deviceId,
    );
  }

  factory MidiMessage.polyPressure({
    required int channel,
    required int note,
    required int pressure,
    required String deviceId,
    required int timestampUs,
    List<int> rawBytes = const [],
  }) {
    return MidiMessage(
      type:        MidiMessageType.polyPressure,
      channel:     channel,
      note:        note,
      velocity:    -1,
      controller:  -1,
      value:       -1,
      pitchBend:   0,
      pressure:    pressure,
      sysexData:   const [],
      rawBytes:    rawBytes,
      timestampUs: timestampUs,
      deviceId:    deviceId,
    );
  }

  factory MidiMessage.sysex({
    required List<int> data,
    required String deviceId,
    required int timestampUs,
  }) {
    return MidiMessage(
      type:        MidiMessageType.sysex,
      channel:     -1,
      note:        -1,
      velocity:    -1,
      controller:  -1,
      value:       -1,
      pitchBend:   0,
      pressure:    -1,
      sysexData:   data,
      rawBytes:    data,
      timestampUs: timestampUs,
      deviceId:    deviceId,
    );
  }

  factory MidiMessage.system({
    required MidiMessageType type,
    required String deviceId,
    required int timestampUs,
    List<int> rawBytes = const [],
  }) {
    return MidiMessage(
      type:        type,
      channel:     -1,
      note:        -1,
      velocity:    -1,
      controller:  -1,
      value:       -1,
      pitchBend:   0,
      pressure:    -1,
      sysexData:   const [],
      rawBytes:    rawBytes,
      timestampUs: timestampUs,
      deviceId:    deviceId,
    );
  }

  // ─── Convenience Getters ────────────────────────────────────────────────────

  /// True if this is a NoteOn with velocity > 0.
  bool get isNoteOn  => type == MidiMessageType.noteOn  && velocity > 0;

  /// True if this is a NoteOff OR a NoteOn with velocity == 0.
  bool get isNoteOff => type == MidiMessageType.noteOff ||
                        (type == MidiMessageType.noteOn && velocity == 0);

  /// True if this is any note message (NoteOn or NoteOff).
  bool get isNote => type == MidiMessageType.noteOn || type == MidiMessageType.noteOff;

  /// True if this is a sustain pedal CC (CC 64).
  bool get isSustainPedal =>
      type == MidiMessageType.controlChange &&
      controller == MidiConstants.CC_SUSTAIN_PEDAL;

  /// Sustain pedal pressed (value >= 64). Only valid if [isSustainPedal] is true.
  bool get isSustainOn => isSustainPedal && value >= 64;

  /// Note name string e.g. "C4" for MIDI note 60. Only valid for note messages.
  String get noteName => note >= 0 ? MidiConstants.noteName(note) : '';

  /// Pitch bend as a normalized float in [-1.0, +1.0].
  double get pitchBendNormalized => pitchBend / 8192.0;

  factory MidiMessage.fromMap(Map<dynamic, dynamic> map) {
    return MidiMessage(
      type:        MidiMessageType.fromString(map['type']?.toString() ?? ''),
      channel:     (map['channel']     as int?) ?? -1,
      note:        (map['note']        as int?) ?? -1,
      velocity:    (map['velocity']    as int?) ?? -1,
      controller:  (map['controller']  as int?) ?? -1,
      value:       (map['value']       as int?) ?? -1,
      pitchBend:   (map['pitchBend']   as int?) ?? 0,
      pressure:    (map['pressure']    as int?) ?? -1,
      sysexData:   List<int>.from(map['sysexData'] as List? ?? []),
      rawBytes:    List<int>.from(map['rawBytes']  as List? ?? []),
      timestampUs: (map['timestampUs'] as int?) ?? 0,
      deviceId:    map['deviceId']?.toString() ?? '',
    );
  }

  Map<String, dynamic> toMap() => {
    'type':        type.value,
    'channel':     channel,
    'note':        note,
    'velocity':    velocity,
    'controller':  controller,
    'value':       value,
    'pitchBend':   pitchBend,
    'pressure':    pressure,
    'sysexData':   sysexData,
    'rawBytes':    rawBytes,
    'timestampUs': timestampUs,
    'deviceId':    deviceId,
  };

  @override
  String toString() {
    switch (type) {
      case MidiMessageType.noteOn:
        return 'NoteOn(ch:$channel note:$noteName vel:$velocity)';
      case MidiMessageType.noteOff:
        return 'NoteOff(ch:$channel note:$noteName vel:$velocity)';
      case MidiMessageType.controlChange:
        return 'CC(ch:$channel ctrl:$controller val:$value)';
      case MidiMessageType.pitchBend:
        return 'PitchBend(ch:$channel val:$pitchBend)';
      case MidiMessageType.programChange:
        return 'ProgramChange(ch:$channel prog:$value)';
      case MidiMessageType.channelPressure:
        return 'ChannelPressure(ch:$channel val:$pressure)';
      case MidiMessageType.polyPressure:
        return 'PolyPressure(ch:$channel note:$noteName val:$pressure)';
      case MidiMessageType.sysex:
        return 'SysEx(${sysexData.length} bytes)';
      default:
        return 'MidiMessage(type:${type.value})';
    }
  }
}

// ─────────────────────────────────────────────
// MESSAGE TYPE
// ─────────────────────────────────────────────

enum MidiMessageType {
  noteOff('noteOff'),
  noteOn('noteOn'),
  polyPressure('polyPressure'),
  controlChange('controlChange'),
  programChange('programChange'),
  channelPressure('channelPressure'),
  pitchBend('pitchBend'),
  sysex('sysex'),
  timingClock('timingClock'),
  start('start'),
  continueMsg('continue'),
  stop('stop'),
  activeSensing('activeSensing'),
  systemReset('systemReset'),
  unknown('unknown');

  const MidiMessageType(this.value);
  final String value;

  static MidiMessageType fromString(String value) {
    return MidiMessageType.values.firstWhere(
      (e) => e.value == value,
      orElse: () => MidiMessageType.unknown,
    );
  }
}
