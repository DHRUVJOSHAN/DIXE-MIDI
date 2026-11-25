export interface KeyDefinition {
  /** Index 0..24 in the 25-key 2-octave C-to-C keyboard */
  keyIndex: number;
  /** Semitone offset 0..24 from base MIDI note */
  semitoneOffset: number;
  isBlack: boolean;
  /** Fractional left edge [0..1] of total keyboard width */
  leftFraction: number;
  /** Fractional width [0..1] of total keyboard width */
  widthFraction: number;
  /** Fractional height [0..1] of total keyboard height */
  heightFraction: number;
}

export interface KeyHitResult {
  keyIndex: number;
  semitoneOffset: number;
  isBlack: boolean;
  keyTop: number;
  keyHeight: number;
}

const WHITE_KEY_SEMITONES = [0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24] as const;

const BLACK_KEY_SPECS: ReadonlyArray<{
  keyIndex: number;
  semitoneOffset: number;
  centerWhiteUnits: number;
}> = [
  { keyIndex: 1, semitoneOffset: 1, centerWhiteUnits: 0.94 },
  { keyIndex: 3, semitoneOffset: 3, centerWhiteUnits: 2.06 },
  { keyIndex: 6, semitoneOffset: 6, centerWhiteUnits: 3.93 },
  { keyIndex: 8, semitoneOffset: 8, centerWhiteUnits: 5.0 },
  { keyIndex: 10, semitoneOffset: 10, centerWhiteUnits: 6.07 },
  { keyIndex: 13, semitoneOffset: 13, centerWhiteUnits: 7.94 },
  { keyIndex: 15, semitoneOffset: 15, centerWhiteUnits: 9.06 },
  { keyIndex: 18, semitoneOffset: 18, centerWhiteUnits: 10.93 },
  { keyIndex: 20, semitoneOffset: 20, centerWhiteUnits: 12.0 },
  { keyIndex: 22, semitoneOffset: 22, centerWhiteUnits: 13.07 },
];

const WHITE_KEY_COUNT = WHITE_KEY_SEMITONES.length; // 15
const WHITE_KEY_WIDTH_FRACTION = 1 / WHITE_KEY_COUNT;
const BLACK_KEY_WIDTH_FRACTION = WHITE_KEY_WIDTH_FRACTION * 0.56;
const BLACK_KEY_HEIGHT_FRACTION = 0.585;

const NOTE_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'] as const;

export function formatMidiKeyLabel(midiNote: number): string {
  const clamped = Math.max(0, Math.min(127, Math.round(midiNote)));
  const noteName = NOTE_NAMES[clamped % 12];
  const octave = Math.floor(clamped / 12) - 1;
  return `${noteName}${octave}`;
}

export const WHITE_KEYS: ReadonlyArray<KeyDefinition> = WHITE_KEY_SEMITONES.map(
  (semitoneOffset, whiteIdx) => ({
    keyIndex: semitoneOffset,
    semitoneOffset,
    isBlack: false,
    leftFraction: whiteIdx * WHITE_KEY_WIDTH_FRACTION,
    widthFraction: WHITE_KEY_WIDTH_FRACTION,
    heightFraction: 1,
  })
);

export const BLACK_KEYS: ReadonlyArray<KeyDefinition> = BLACK_KEY_SPECS.map((spec) => ({
  keyIndex: spec.keyIndex,
  semitoneOffset: spec.semitoneOffset,
  isBlack: true,
  leftFraction:
    spec.centerWhiteUnits * WHITE_KEY_WIDTH_FRACTION - BLACK_KEY_WIDTH_FRACTION / 2,
  widthFraction: BLACK_KEY_WIDTH_FRACTION,
  heightFraction: BLACK_KEY_HEIGHT_FRACTION,
}));

/**
 * Hit-tests a pointer coordinate against the 25-key 2-octave C-to-C keyboard rect.
 * Black keys always receive touch priority over overlapping white keys.
 */
export function hitTestKeyboard(
  clientX: number,
  clientY: number,
  boardRect: DOMRect
): KeyHitResult | null {
  if (boardRect.width <= 0 || boardRect.height <= 0) {
    return null;
  }

  const localX = clientX - boardRect.left;
  const localY = clientY - boardRect.top;

  if (localX < 0 || localX > boardRect.width || localY < 0 || localY > boardRect.height) {
    return null;
  }

  const normX = localX / boardRect.width;
  const normY = localY / boardRect.height;

  // Priority 1: Black keys
  for (let i = 0; i < BLACK_KEYS.length; i++) {
    const key = BLACK_KEYS[i];
    if (
      normX >= key.leftFraction &&
      normX <= key.leftFraction + key.widthFraction &&
      normY >= 0 &&
      normY <= key.heightFraction
    ) {
      return {
        keyIndex: key.keyIndex,
        semitoneOffset: key.semitoneOffset,
        isBlack: true,
        keyTop: boardRect.top,
        keyHeight: boardRect.height * key.heightFraction,
      };
    }
  }

  // Priority 2: White keys
  const whiteIdx = Math.max(0, Math.min(WHITE_KEY_COUNT - 1, Math.floor(normX * WHITE_KEY_COUNT)));
  const whiteKey = WHITE_KEYS[whiteIdx];
  return {
    keyIndex: whiteKey.keyIndex,
    semitoneOffset: whiteKey.semitoneOffset,
    isBlack: false,
    keyTop: boardRect.top,
    keyHeight: boardRect.height,
  };
}
