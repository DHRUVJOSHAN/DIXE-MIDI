export const MAX_MIDI_VELOCITY = 127;
export const MIN_MIDI_VELOCITY = 1;
export const PITCH_BEND_CENTER = 8192;
export const PITCH_BEND_MIN = 0;
export const PITCH_BEND_MAX = 16383;

export function clampMidiNote(note: number): number {
  return Math.max(0, Math.min(127, Math.round(note)));
}

export function clampMidiData(value: number): number {
  return Math.max(0, Math.min(127, Math.round(value)));
}

export function clampVelocity(velocity: number): number {
  return Math.max(MIN_MIDI_VELOCITY, Math.min(MAX_MIDI_VELOCITY, Math.round(velocity)));
}

export function clampChannel(channel: number): number {
  return Math.max(0, Math.min(15, Math.round(channel)));
}

export function buildNoteOn(channel: number, note: number, velocity: number): Uint8Array {
  const ch = clampChannel(channel);
  return new Uint8Array([0x90 | ch, clampMidiNote(note), clampVelocity(velocity)]);
}

export function buildNoteOff(channel: number, note: number, releaseVelocity = 0): Uint8Array {
  const ch = clampChannel(channel);
  return new Uint8Array([0x80 | ch, clampMidiNote(note), clampMidiData(releaseVelocity)]);
}

export function buildControlChange(channel: number, ccNumber: number, value: number): Uint8Array {
  const ch = clampChannel(channel);
  return new Uint8Array([0xb0 | ch, clampMidiData(ccNumber), clampMidiData(value)]);
}

export function buildPitchBend(channel: number, value14Bit: number): Uint8Array {
  const ch = clampChannel(channel);
  const clamped = Math.max(PITCH_BEND_MIN, Math.min(PITCH_BEND_MAX, Math.round(value14Bit)));
  const lsb = clamped & 0x7f;
  const msb = (clamped >> 7) & 0x7f;
  return new Uint8Array([0xe0 | ch, lsb, msb]);
}

export function buildProgramChange(channel: number, program: number): Uint8Array {
  const ch = clampChannel(channel);
  return new Uint8Array([0xc0 | ch, clampMidiData(program)]);
}

/**
 * MIDI RPN 00 00: Pitch Bend Sensitivity (±semitones, cents) followed by Null RPN (7F 7F)
 * per MIDI 1.0 specification.
 */
export function buildRpnPitchBendRange(
  channel: number,
  semitones = 2,
  cents = 0
): Uint8Array[] {
  return [
    buildControlChange(channel, 101, 0),
    buildControlChange(channel, 100, 0),
    buildControlChange(channel, 6, clampMidiData(semitones)),
    buildControlChange(channel, 38, clampMidiData(cents)),
    buildControlChange(channel, 101, 127),
    buildControlChange(channel, 100, 127),
  ];
}
