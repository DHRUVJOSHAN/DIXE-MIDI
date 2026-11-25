import {
  PITCH_BEND_CENTER,
  PITCH_BEND_MAX,
  PITCH_BEND_MIN,
  buildControlChange,
  buildNoteOff,
  buildNoteOn,
  buildPitchBend,
  clampMidiData,
  clampMidiNote,
} from './midiProtocol';
import type { MidiTransport } from './webMidiTransport';

export type ButtonLedState = 'none' | 'white' | 'blue' | 'blink-white-blue' | 'pulse-white-blue';

export interface MidiUiSnapshot {
  pressedKeyIndices: ReadonlySet<number>;
  baseMidiNote: number;
  midiChannel: number;
  sustainEnabled: boolean;
  chordModeEnabled: boolean;
  creatingChord: boolean;
  shiftActive: boolean;
  octaveDownActive: boolean;
  octaveUpActive: boolean;
  octDownLed: ButtonLedState;
  octUpLed: ButtonLedState;
  holdLed: ButtonLedState;
  pitchBendValue: number;
  modulationValue: number;
}

interface ActivePointerNote {
  keyIndex: number;
  notes: number[];
  isChannelSelect?: boolean;
}

const CHORD_STORAGE_KEY = 'dixe_midi_user_chord_v1';
const DEFAULT_CHORD_INTERVALS: readonly number[] = [0, 4, 7];
const HOLD_LONG_PRESS_MS = 600;

export class MidiStateEngine {
  private readonly transport: MidiTransport;
  private channel: number;
  private readonly onSnapshot: (snapshot: MidiUiSnapshot) => void;

  private static readonly DEFAULT_BASE_MIDI_NOTE = 60; // C4
  private static readonly MIN_OCTAVE_OFFSET = -4;
  private static readonly MAX_OCTAVE_OFFSET = 4;
  private static readonly MIN_SEMITONE_OFFSET = -12;
  private static readonly MAX_SEMITONE_OFFSET = 12;
  private static readonly SHIFT_TAP_THRESHOLD_MS = 400;
  private static readonly SHIFT_AUTO_OFF_MS = 2000;

  private octaveOffset = 0;
  private semitoneOffset = 0;
  private sustainEnabled = false;
  private chordModeEnabled = false;
  private creatingChord = false;
  private pitchBendValue = PITCH_BEND_CENTER;
  private modulationValue = 0;

  private userChordIntervals: number[] = [...DEFAULT_CHORD_INTERVALS];
  private recordedChordNotes: number[] = [];

  private activeHoldPointerId: number | null = null;
  private holdShiftInitiated = false;
  private holdLongPressTimer: ReturnType<typeof setTimeout> | null = null;
  private holdLongPressed = false;

  private readonly activePointers = new Map<number, ActivePointerNote>();
  private readonly shiftPointers = new Set<number>();
  private shiftLatched = false;
  private shiftWasLatchedOnPress = false;
  private shiftComboUsedDuringHold = false;
  private shiftPressTimeMs = 0;
  private shiftAutoOffTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly octDownPointers = new Set<number>();
  private readonly octUpPointers = new Set<number>();
  private octaveComboConsumed = false;

  private get shiftEnabled(): boolean {
    return this.shiftPointers.size > 0 || this.shiftLatched;
  }
  private readonly physicalHoldCount = new Int32Array(128);
  private readonly sustainedNotes = new Set<number>();

  private activePitchPointerId: number | null = null;
  private activeModPointerId: number | null = null;

  constructor(
    transport: MidiTransport,
    onSnapshot: (snapshot: MidiUiSnapshot) => void,
    channel = 0
  ) {
    this.transport = transport;
    this.onSnapshot = onSnapshot;
    this.channel = Math.max(0, Math.min(15, channel));
    this.userChordIntervals = this.loadPersistedChord();
  }

  private loadPersistedChord(): number[] {
    if (typeof window === 'undefined' || !window.localStorage) {
      return [...DEFAULT_CHORD_INTERVALS];
    }
    try {
      const raw = window.localStorage.getItem(CHORD_STORAGE_KEY);
      if (!raw) return [...DEFAULT_CHORD_INTERVALS];
      const parsed: unknown = JSON.parse(raw);
      if (
        Array.isArray(parsed) &&
        parsed.length > 0 &&
        parsed.every((n) => typeof n === 'number' && Number.isInteger(n) && n >= 0 && n <= 127)
      ) {
        return Array.from(new Set(parsed)).sort((a, b) => a - b);
      }
    } catch {
      // Ignore storage read errors
    }
    return [...DEFAULT_CHORD_INTERVALS];
  }

  private persistChord(intervals: number[]): void {
    if (typeof window === 'undefined' || !window.localStorage) {
      return;
    }
    try {
      window.localStorage.setItem(CHORD_STORAGE_KEY, JSON.stringify(intervals));
    } catch {
      // Ignore storage write errors
    }
  }

  public getBaseMidiNote(): number {
    return clampMidiNote(
      MidiStateEngine.DEFAULT_BASE_MIDI_NOTE + this.octaveOffset * 12 + this.semitoneOffset
    );
  }

  private getOctaveLedState(direction: -1 | 1): ButtonLedState {
    const octActive =
      direction < 0 ? this.octaveOffset < 0 : this.octaveOffset > 0;
    const semiActive =
      direction < 0 ? this.semitoneOffset < 0 : this.semitoneOffset > 0;

    if (octActive && semiActive) {
      return 'blink-white-blue';
    }
    if (octActive) return 'white';
    if (semiActive) return 'blue';
    return 'none';
  }

  private getHoldLedState(): ButtonLedState {
    if (this.creatingChord) return 'blue';
    if (this.sustainEnabled && this.chordModeEnabled) return 'pulse-white-blue';
    if (this.sustainEnabled) return 'white';
    if (this.chordModeEnabled) return 'blue';
    return 'none';
  }

  public getSnapshot(): MidiUiSnapshot {
    const pressed = new Set<number>();
    this.activePointers.forEach((entry) => {
      if (!entry.isChannelSelect) {
        pressed.add(entry.keyIndex);
      }
    });
    return {
      pressedKeyIndices: pressed,
      baseMidiNote: this.getBaseMidiNote(),
      midiChannel: this.channel,
      sustainEnabled: this.sustainEnabled,
      chordModeEnabled: this.chordModeEnabled,
      creatingChord: this.creatingChord,
      shiftActive: this.shiftEnabled,
      octaveDownActive: this.octDownPointers.size > 0,
      octaveUpActive: this.octUpPointers.size > 0,
      octDownLed: this.getOctaveLedState(-1),
      octUpLed: this.getOctaveLedState(1),
      holdLed: this.getHoldLedState(),
      pitchBendValue: this.pitchBendValue,
      modulationValue: this.modulationValue,
    };
  }

  private emitSnapshot(): void {
    this.onSnapshot(this.getSnapshot());
  }

  private resolveNotesForKey(semitoneFromBase: number): number[] {
    const rootNote = clampMidiNote(this.getBaseMidiNote() + semitoneFromBase);
    if (this.creatingChord || !this.chordModeEnabled) {
      return [rootNote];
    }
    const notes: number[] = [];
    for (const interval of this.userChordIntervals) {
      const candidate = rootNote + interval;
      if (candidate >= 0 && candidate <= 127 && !notes.includes(candidate)) {
        notes.push(candidate);
      }
    }
    return notes.length > 0 ? notes : [rootNote];
  }

  private countPlayingKeyPointers(): number {
    let count = 0;
    this.activePointers.forEach((entry) => {
      if (!entry.isChannelSelect) count += 1;
    });
    return count;
  }

  private clearLatchedNotes(): void {
    this.sustainedNotes.forEach((note) => {
      if (this.physicalHoldCount[note] === 0) {
        this.transport.send(buildNoteOff(this.channel, note, 0));
      }
    });
    this.sustainedNotes.clear();
  }

  private switchGlobalChannel(nextChannel: number): void {
    const clamped = Math.max(0, Math.min(15, nextChannel));
    if (clamped === this.channel) return;

    // Release sounding/latched notes on the previous channel cleanly before switching
    this.clearLatchedNotes();
    const pointerIds = Array.from(this.activePointers.keys());
    for (const id of pointerIds) {
      const entry = this.activePointers.get(id);
      if (entry && !entry.isChannelSelect) {
        for (const note of entry.notes) {
          this.transport.send(buildNoteOff(this.channel, note, 0));
        }
      }
    }
    this.activePointers.clear();
    this.physicalHoldCount.fill(0);

    this.channel = clamped;
  }

  public handleKeyPointerDown(
    pointerId: number,
    keyIndex: number,
    semitoneFromBase: number,
    velocity: number
  ): void {
    if (this.activePointers.has(pointerId)) {
      this.releaseKeyPointerInternal(pointerId);
    }

    // Shift + Keys (F1 to G#2, semitone offsets 5..20) selects global MIDI Channel 1..16
    if (this.shiftEnabled && !this.creatingChord && semitoneFromBase >= 5 && semitoneFromBase <= 20) {
      if (this.shiftPointers.size > 0) {
        this.shiftComboUsedDuringHold = true;
      }
      this.switchGlobalChannel(semitoneFromBase - 5);
      this.activePointers.set(pointerId, {
        keyIndex,
        notes: [],
        isChannelSelect: true,
      });
      this.emitSnapshot();
      return;
    }

    // MiniLab 3 Hold Mode simultaneous latching rule:
    // If Hold is ON and no finger is currently holding a key, pressing a new key/chord
    // immediately stops the previously latched notes before latching the new ones.
    if (this.sustainEnabled && !this.creatingChord && this.countPlayingKeyPointers() === 0) {
      this.clearLatchedNotes();
    }

    const notes = this.resolveNotesForKey(semitoneFromBase);
    if (this.creatingChord) {
      for (const note of notes) {
        if (!this.recordedChordNotes.includes(note)) {
          this.recordedChordNotes.push(note);
        }
      }
    }

    this.triggerNotesOn(pointerId, keyIndex, notes, velocity);
    this.emitSnapshot();
  }

  public handleKeyPointerMove(
    pointerId: number,
    keyIndex: number | null,
    semitoneFromBase: number | null,
    velocity: number | null
  ): void {
    const existing = this.activePointers.get(pointerId);
    if (!existing || existing.isChannelSelect) {
      return;
    }

    if (keyIndex === null || semitoneFromBase === null || velocity === null) {
      this.releaseKeyPointerInternal(pointerId);
      this.emitSnapshot();
      return;
    }

    if (existing.keyIndex === keyIndex) {
      return;
    }

    // Glide: Note Off previous key/chord, immediately Note On newly entered key/chord
    this.releaseKeyPointerInternal(pointerId);
    const notes = this.resolveNotesForKey(semitoneFromBase);
    if (this.creatingChord) {
      for (const note of notes) {
        if (!this.recordedChordNotes.includes(note)) {
          this.recordedChordNotes.push(note);
        }
      }
    }
    this.triggerNotesOn(pointerId, keyIndex, notes, velocity);
    this.emitSnapshot();
  }

  public handleKeyPointerUp(pointerId: number): void {
    if (!this.activePointers.has(pointerId)) {
      return;
    }
    this.releaseKeyPointerInternal(pointerId);
    this.emitSnapshot();
  }

  private triggerNotesOn(
    pointerId: number,
    keyIndex: number,
    notes: number[],
    velocity: number
  ): void {
    for (const midiNote of notes) {
      if (this.sustainedNotes.has(midiNote)) {
        this.sustainedNotes.delete(midiNote);
        this.transport.send(buildNoteOff(this.channel, midiNote, 0));
      }
      this.physicalHoldCount[midiNote] += 1;
    }
    this.activePointers.set(pointerId, { keyIndex, notes });
    for (const midiNote of notes) {
      this.transport.send(buildNoteOn(this.channel, midiNote, velocity));
    }
  }

  private releaseKeyPointerInternal(pointerId: number): void {
    const entry = this.activePointers.get(pointerId);
    if (!entry) {
      return;
    }
    this.activePointers.delete(pointerId);
    if (entry.isChannelSelect) {
      return;
    }

    for (const note of entry.notes) {
      if (this.physicalHoldCount[note] > 0) {
        this.physicalHoldCount[note] -= 1;
      }
      if (this.physicalHoldCount[note] === 0) {
        if (this.sustainEnabled && !this.creatingChord) {
          this.sustainedNotes.add(note);
        } else {
          this.transport.send(buildNoteOff(this.channel, note, 0));
        }
      }
    }
  }

  private clearHoldTimer(): void {
    if (this.holdLongPressTimer !== null) {
      clearTimeout(this.holdLongPressTimer);
      this.holdLongPressTimer = null;
    }
  }

  public handleHoldPointerDown(pointerId: number): void {
    this.clearHoldTimer();
    this.activeHoldPointerId = pointerId;
    this.holdLongPressed = false;
    this.holdShiftInitiated = this.shiftEnabled;

    if (!this.holdShiftInitiated) {
      this.toggleSustain();
      return;
    }

    if (this.shiftPointers.size > 0) {
      this.shiftComboUsedDuringHold = true;
    }

    // Shift + Hold: short tap toggles Chord Mode; long press (>= 600ms) enters Create Chord mode
    this.holdLongPressTimer = setTimeout(() => {
      this.holdLongPressTimer = null;
      if (this.activeHoldPointerId === pointerId) {
        this.holdLongPressed = true;
        this.creatingChord = true;
        this.recordedChordNotes = [];
        this.emitSnapshot();
      }
    }, HOLD_LONG_PRESS_MS);
  }

  public handleHoldPointerUp(pointerId: number, cancelled = false): void {
    if (this.activeHoldPointerId !== pointerId) {
      return;
    }
    this.activeHoldPointerId = null;
    const shiftInitiated = this.holdShiftInitiated;
    this.holdShiftInitiated = false;
    this.clearHoldTimer();

    if (this.holdLongPressed) {
      this.holdLongPressed = false;
      this.creatingChord = false;
      if (this.recordedChordNotes.length > 0) {
        const minNote = Math.min(...this.recordedChordNotes);
        const intervals = Array.from(
          new Set(this.recordedChordNotes.map((n) => n - minNote))
        ).sort((a, b) => a - b);
        this.userChordIntervals = intervals;
        this.persistChord(intervals);
        this.chordModeEnabled = true;
      }
      this.recordedChordNotes = [];
      this.emitSnapshot();
      return;
    }

    if (!cancelled && shiftInitiated) {
      this.chordModeEnabled = !this.chordModeEnabled;
      this.emitSnapshot();
    }
  }

  public toggleSustain(): void {
    this.sustainEnabled = !this.sustainEnabled;
    if (!this.sustainEnabled) {
      this.clearLatchedNotes();
    }
    this.emitSnapshot();
  }

  private clearShiftAutoOffTimer(): void {
    if (this.shiftAutoOffTimer !== null) {
      clearTimeout(this.shiftAutoOffTimer);
      this.shiftAutoOffTimer = null;
    }
  }

  private scheduleShiftAutoOffTimer(): void {
    this.clearShiftAutoOffTimer();
    this.shiftAutoOffTimer = setTimeout(() => {
      this.shiftAutoOffTimer = null;
      if (this.shiftLatched) {
        this.shiftLatched = false;
        this.emitSnapshot();
      }
    }, MidiStateEngine.SHIFT_AUTO_OFF_MS);
  }

  public setShiftPointer(pointerId: number, active: boolean): void {
    if (active) {
      if (this.shiftPointers.has(pointerId)) return;
      if (this.shiftPointers.size === 0) {
        this.shiftWasLatchedOnPress = this.shiftLatched;
        this.shiftComboUsedDuringHold = false;
        this.shiftPressTimeMs = Date.now();
        this.clearShiftAutoOffTimer();
      }
      this.shiftPointers.add(pointerId);
      this.emitSnapshot();
      return;
    }

    if (!this.shiftPointers.delete(pointerId)) {
      return;
    }

    if (this.shiftPointers.size === 0) {
      const heldDurationMs = Date.now() - this.shiftPressTimeMs;
      if (
        this.shiftWasLatchedOnPress ||
        this.shiftComboUsedDuringHold ||
        heldDurationMs >= MidiStateEngine.SHIFT_TAP_THRESHOLD_MS
      ) {
        this.shiftLatched = false;
        this.clearShiftAutoOffTimer();
      } else {
        this.shiftLatched = true;
        this.scheduleShiftAutoOffTimer();
      }
      this.shiftWasLatchedOnPress = false;
      this.shiftComboUsedDuringHold = false;
    }
    this.emitSnapshot();
  }

  public setTransposePointer(
    pointerId: number,
    direction: 1 | -1,
    active: boolean
  ): void {
    const targetSet = direction === 1 ? this.octUpPointers : this.octDownPointers;

    if (active) {
      const alreadyTracked = targetSet.has(pointerId);
      targetSet.add(pointerId);
      if (this.shiftPointers.size > 0) {
        this.shiftComboUsedDuringHold = true;
      }

      if (this.octDownPointers.size > 0 && this.octUpPointers.size > 0) {
        // Simultaneous OCTAVE UP (+) and OCTAVE DOWN (−):
        // Reset octave & semitone transposition to 0 (default C-to-C mapping),
        // regardless of Shift state and without interrupting sounding notes.
        this.octaveOffset = 0;
        this.semitoneOffset = 0;
        this.octaveComboConsumed = true;
      } else if (!alreadyTracked && !this.octaveComboConsumed) {
        this.transpose(direction);
      }
      this.emitSnapshot();
    } else {
      const removed = targetSet.delete(pointerId);
      if (this.octDownPointers.size === 0 && this.octUpPointers.size === 0) {
        this.octaveComboConsumed = false;
      }
      if (removed) {
        this.emitSnapshot();
      }
    }
  }

  public transpose(direction: 1 | -1): void {
    if (this.shiftEnabled) {
      this.semitoneOffset = Math.max(
        MidiStateEngine.MIN_SEMITONE_OFFSET,
        Math.min(MidiStateEngine.MAX_SEMITONE_OFFSET, this.semitoneOffset + direction)
      );
    } else {
      this.octaveOffset = Math.max(
        MidiStateEngine.MIN_OCTAVE_OFFSET,
        Math.min(MidiStateEngine.MAX_OCTAVE_OFFSET, this.octaveOffset + direction)
      );
    }
    // Existing sounding notes remain undisturbed; new notes use updated baseMidiNote.
  }

  public startPitchBend(pointerId: number, normalizedYFromBottom: number): void {
    this.activePitchPointerId = pointerId;
    this.applyPitchBendFromNormalized(normalizedYFromBottom, true);
  }

  public movePitchBend(pointerId: number, normalizedYFromBottom: number): void {
    if (this.activePitchPointerId !== pointerId) {
      return;
    }
    this.applyPitchBendFromNormalized(normalizedYFromBottom, false);
  }

  public endPitchBend(pointerId: number): void {
    if (this.activePitchPointerId !== pointerId) {
      return;
    }
    this.activePitchPointerId = null;
    // Spring-loaded Pitch Bend strip: snap immediately back to center on finger release
    if (this.pitchBendValue !== PITCH_BEND_CENTER) {
      this.pitchBendValue = PITCH_BEND_CENTER;
      this.transport.send(buildPitchBend(this.channel, PITCH_BEND_CENTER));
      this.emitSnapshot();
    }
  }

  private applyPitchBendFromNormalized(norm: number, forceEmit: boolean): void {
    const clamped = Math.max(0, Math.min(1, norm));
    let nextValue: number;
    if (clamped >= 0.5) {
      nextValue =
        PITCH_BEND_CENTER +
        Math.round(((clamped - 0.5) / 0.5) * (PITCH_BEND_MAX - PITCH_BEND_CENTER));
    } else {
      nextValue = Math.round((clamped / 0.5) * PITCH_BEND_CENTER);
    }
    nextValue = Math.max(PITCH_BEND_MIN, Math.min(PITCH_BEND_MAX, nextValue));

    if (forceEmit || nextValue !== this.pitchBendValue) {
      const changed = nextValue !== this.pitchBendValue;
      this.pitchBendValue = nextValue;
      this.transport.send(buildPitchBend(this.channel, nextValue));
      if (changed) {
        this.emitSnapshot();
      }
    }
  }

  public startModulation(pointerId: number, normalizedYFromBottom: number): void {
    this.activeModPointerId = pointerId;
    this.applyModulationFromNormalized(normalizedYFromBottom, true);
  }

  public moveModulation(pointerId: number, normalizedYFromBottom: number): void {
    if (this.activeModPointerId !== pointerId) {
      return;
    }
    this.applyModulationFromNormalized(normalizedYFromBottom, false);
  }

  public endModulation(pointerId: number): void {
    if (this.activeModPointerId === pointerId) {
      this.activeModPointerId = null;
    }
    // Modulation retains its last position and value upon release.
  }

  private applyModulationFromNormalized(norm: number, forceEmit: boolean): void {
    const clamped = Math.max(0, Math.min(1, norm));
    const nextValue = clampMidiData(Math.round(clamped * 127));
    if (forceEmit || nextValue !== this.modulationValue) {
      const changed = nextValue !== this.modulationValue;
      this.modulationValue = nextValue;
      this.transport.send(buildControlChange(this.channel, 1, nextValue));
      if (changed) {
        this.emitSnapshot();
      }
    }
  }

  public releaseActiveTouches(): void {
    this.clearHoldTimer();
    this.clearShiftAutoOffTimer();
    this.shiftLatched = false;
    this.activeHoldPointerId = null;
    this.holdShiftInitiated = false;
    this.holdLongPressed = false;
    this.creatingChord = false;
    this.recordedChordNotes = [];

    const pointerIds = Array.from(this.activePointers.keys());
    for (const id of pointerIds) {
      this.releaseKeyPointerInternal(id);
    }
    this.shiftPointers.clear();
    this.octDownPointers.clear();
    this.octUpPointers.clear();
    this.octaveComboConsumed = false;
    this.activePitchPointerId = null;
    this.activeModPointerId = null;
    if (this.pitchBendValue !== PITCH_BEND_CENTER) {
      this.pitchBendValue = PITCH_BEND_CENTER;
      this.transport.send(buildPitchBend(this.channel, PITCH_BEND_CENTER));
    }
    this.emitSnapshot();
  }

  public releaseAllNotes(): void {
    this.releaseActiveTouches();
    if (this.sustainEnabled) {
      this.sustainEnabled = false;
      this.transport.send(buildControlChange(this.channel, 64, 0));
    }
    this.clearLatchedNotes();
    this.physicalHoldCount.fill(0);
    this.emitSnapshot();
  }

  public dispose(): void {
    this.releaseAllNotes();
    this.transport.dispose();
  }
}
