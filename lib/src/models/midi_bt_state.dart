/// Bluetooth adapter state as reported by the Android system.
enum MidiBtState {
  on,
  off,
  turningOn,
  turningOff,
  unknown;

  bool get isOn  => this == MidiBtState.on;
  bool get isOff => this == MidiBtState.off || this == MidiBtState.unknown;

  static MidiBtState fromString(String value) {
    switch (value) {
      case 'on':         return MidiBtState.on;
      case 'off':        return MidiBtState.off;
      case 'turningOn':  return MidiBtState.turningOn;
      case 'turningOff': return MidiBtState.turningOff;
      default:           return MidiBtState.unknown;
    }
  }
}