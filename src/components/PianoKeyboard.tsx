import React, { useRef } from 'react';
import {
  BLACK_KEYS,
  WHITE_KEYS,
  formatMidiKeyLabel,
  hitTestKeyboard,
} from '../midi/keyboardGeometry';
import type { MidiStateEngine } from '../midi/midiStateEngine';
import { calculatePositionVelocity } from '../midi/velocityEngine';

interface PianoKeyboardProps {
  engine: MidiStateEngine;
  pressedKeyIndices: ReadonlySet<number>;
  baseMidiNote: number;
}

export const PianoKeyboard: React.FC<PianoKeyboardProps> = ({
  engine,
  pressedKeyIndices,
  baseMidiNote,
}) => {
  const boardRef = useRef<HTMLDivElement | null>(null);
  const activeMouseOrTouchPointers = useRef<Set<number>>(new Set());

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    e.preventDefault();
    const board = boardRef.current;
    if (!board) return;

    e.currentTarget.setPointerCapture(e.pointerId);
    activeMouseOrTouchPointers.current.add(e.pointerId);

    const rect = board.getBoundingClientRect();
    const hit = hitTestKeyboard(e.clientX, e.clientY, rect);
    if (!hit) return;

    const velocity = calculatePositionVelocity(e.clientY, hit.keyTop, hit.keyHeight);
    engine.handleKeyPointerDown(e.pointerId, hit.keyIndex, hit.semitoneOffset, velocity);
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!activeMouseOrTouchPointers.current.has(e.pointerId)) {
      return;
    }
    const board = boardRef.current;
    if (!board) return;

    const rect = board.getBoundingClientRect();
    const hit = hitTestKeyboard(e.clientX, e.clientY, rect);
    if (!hit) {
      engine.handleKeyPointerMove(e.pointerId, null, null, null);
      return;
    }

    const velocity = calculatePositionVelocity(e.clientY, hit.keyTop, hit.keyHeight);
    engine.handleKeyPointerMove(e.pointerId, hit.keyIndex, hit.semitoneOffset, velocity);
  };

  const handlePointerUpOrCancel = (e: React.PointerEvent<HTMLDivElement>) => {
    e.preventDefault();
    activeMouseOrTouchPointers.current.delete(e.pointerId);
    engine.handleKeyPointerUp(e.pointerId);
  };

  return (
    <main
      aria-label="Piano Keyboard"
      className="w-[90%] h-full shrink-0 p-[0.65vw] select-none touch-none"
      style={{
        background: 'linear-gradient(180deg, #F3CFD9 0%, #EAC2CE 100%)',
      }}
    >
      <div
        ref={boardRef}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUpOrCancel}
        onPointerCancel={handlePointerUpOrCancel}
        onContextMenu={(e) => e.preventDefault()}
        className="relative w-full h-full touch-none"
      >
        {/* 15 White Keys (2 Octaves C to C) */}
        {WHITE_KEYS.map((key) => {
          const isPressed = pressedKeyIndices.has(key.keyIndex);
          const isFirstKey = key.keyIndex === 0;
          return (
            <div
              key={key.keyIndex}
              className="pointer-events-none absolute top-0 bottom-0 px-[0.18vw] py-[0.22vw]"
              style={{
                left: `${key.leftFraction * 100}%`,
                width: `${key.widthFraction * 100}%`,
              }}
            >
              <div
                className={`relative w-full h-full rounded-[8px] origin-top ${
                  isPressed ? 'dixe-spring-key-press' : 'dixe-spring-key-release'
                }`}
                style={{
                  background: isPressed
                    ? 'linear-gradient(180deg, #EFEBE3 0%, #E8E1D6 100%)'
                    : 'linear-gradient(180deg, #FDFBF7 0%, #F6F1E9 100%)',
                  border: '1px solid rgba(150, 114, 124, 0.24)',
                  boxShadow: isPressed
                    ? 'inset 0 2px 6px rgba(84, 58, 66, 0.18), 0 1px 2px rgba(72, 44, 52, 0.1)'
                    : '0 3px 6px rgba(76, 46, 55, 0.16), inset 0 -3px 0 rgba(196, 184, 170, 0.35), inset 0 1px 0 rgba(255, 255, 255, 0.92)',
                  transform: isPressed
                    ? 'translateY(3px) scaleY(0.992) scaleX(0.988)'
                    : 'translateY(0) scaleY(1) scaleX(1)',
                }}
              >
                {isFirstKey && (
                  <span
                    key={baseMidiNote}
                    className="dixe-octave-settle absolute bottom-[6%] left-0 right-0 text-center text-[clamp(11px,1.45vw,16px)] font-medium tracking-tight tabular-nums leading-none"
                    style={{ color: 'rgba(79, 52, 60, 0.56)' }}
                  >
                    {formatMidiKeyLabel(baseMidiNote)}
                  </span>
                )}
              </div>
            </div>
          );
        })}

        {/* 10 Black Keys */}
        {BLACK_KEYS.map((key) => {
          const isPressed = pressedKeyIndices.has(key.keyIndex);
          return (
            <div
              key={key.keyIndex}
              className="pointer-events-none absolute top-0 z-10 px-[0.14vw] pt-[0.22vw]"
              style={{
                left: `${key.leftFraction * 100}%`,
                width: `${key.widthFraction * 100}%`,
                height: `${key.heightFraction * 100}%`,
              }}
            >
              <div
                className={`w-full h-full rounded-[6px] origin-top ${
                  isPressed ? 'dixe-spring-key-press' : 'dixe-spring-key-release'
                }`}
                style={{
                  background: isPressed
                    ? 'linear-gradient(180deg, #1E1E22 0%, #17171A 100%)'
                    : 'linear-gradient(180deg, #2D2D32 0%, #1F1F23 85%, #2A2A2E 100%)',
                  border: '1px solid rgba(15, 15, 18, 0.85)',
                  boxShadow: isPressed
                    ? '0 2px 3px rgba(0, 0, 0, 0.38), inset 0 1px 0 rgba(255, 255, 255, 0.05)'
                    : '0 5px 10px rgba(32, 18, 24, 0.38), inset 0 -4px 0 rgba(0, 0, 0, 0.45), inset 0 1px 0 rgba(255, 255, 255, 0.14)',
                  transform: isPressed
                    ? 'translateY(2.5px) scaleY(0.988) scaleX(0.985)'
                    : 'translateY(0) scaleY(1) scaleX(1)',
                }}
              />
            </div>
          );
        })}
      </div>
    </main>
  );
};
