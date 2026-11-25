import React, { useRef, useState } from 'react';
import { PITCH_BEND_MAX } from '../midi/midiProtocol';
import type { ButtonLedState, MidiStateEngine } from '../midi/midiStateEngine';

interface ControlPanelProps {
  engine: MidiStateEngine;
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

const LED_TOP_SHEEN = 'linear-gradient(180deg, rgba(255, 255, 255, 0.38) 0%, rgba(255, 255, 255, 0.04) 100%)';

function getLedSurfaceStyle(
  led: ButtonLedState,
  pressed: boolean
): { classNameExtra: string; style: React.CSSProperties } {
  const transform = pressed ? 'translateY(1.5px) scale(0.935)' : 'translateY(0) scale(1)';

  if (led === 'blink-white-blue') {
    return {
      classNameExtra: 'dixe-led-blink-wb',
      style: {
        backgroundImage: LED_TOP_SHEEN,
        borderWidth: '1px',
        borderStyle: 'solid',
        transform,
      },
    };
  }
  if (led === 'pulse-white-blue') {
    return {
      classNameExtra: 'dixe-led-pulse-wb',
      style: {
        backgroundImage: LED_TOP_SHEEN,
        borderWidth: '1px',
        borderStyle: 'solid',
        transform,
      },
    };
  }
  if (led === 'white') {
    return {
      classNameExtra: '',
      style: {
        backgroundImage: LED_TOP_SHEEN,
        backgroundColor: '#FAF7F8',
        border: '1px solid rgba(255, 255, 255, 0.94)',
        boxShadow: pressed
          ? 'inset 0 2px 4px rgba(105, 58, 72, 0.16), 0 0 12px rgba(255, 255, 255, 0.78)'
          : '0 0 14px rgba(255, 255, 255, 0.8), 0 2px 9px rgba(255, 255, 255, 0.68), inset 0 1px 0 rgba(255, 255, 255, 0.96)',
        transform,
      },
    };
  }
  if (led === 'blue') {
    return {
      classNameExtra: '',
      style: {
        backgroundImage: LED_TOP_SHEEN,
        backgroundColor: '#C8DFFE',
        border: '1px solid rgba(79, 140, 245, 0.65)',
        boxShadow: pressed
          ? 'inset 0 2px 4px rgba(30, 64, 175, 0.2), 0 0 12px rgba(96, 165, 250, 0.56)'
          : '0 0 14px rgba(96, 165, 250, 0.58), 0 2px 9px rgba(96, 165, 250, 0.45), inset 0 1px 0 rgba(255, 255, 255, 0.88)',
        transform,
      },
    };
  }
  return {
    classNameExtra: '',
    style: {
      backgroundImage: LED_TOP_SHEEN,
      backgroundColor: pressed ? '#E9C1CD' : '#F6DAE2',
      border: '1px solid rgba(168, 114, 129, 0.28)',
      boxShadow: pressed
        ? 'inset 0 2px 4px rgba(105, 58, 72, 0.22)'
        : '0 2px 5px rgba(112, 64, 78, 0.14), inset 0 1px 0 rgba(255, 255, 255, 0.74)',
      transform,
    },
  };
}

export const ControlPanel: React.FC<ControlPanelProps> = ({
  engine,
  sustainEnabled,
  chordModeEnabled,
  creatingChord,
  shiftActive,
  octaveDownActive,
  octaveUpActive,
  octDownLed,
  octUpLed,
  holdLed,
  pitchBendValue,
  modulationValue,
}) => {
  const pitchStripRef = useRef<HTMLDivElement | null>(null);
  const modStripRef = useRef<HTMLDivElement | null>(null);
  const [holdPressed, setHoldPressed] = useState(false);
  const [pitchDragging, setPitchDragging] = useState(false);
  const [modDragging, setModDragging] = useState(false);

  const computeNormFromBottom = (clientY: number, el: HTMLDivElement | null): number => {
    if (!el) return 0.5;
    const rect = el.getBoundingClientRect();
    if (rect.height <= 0) return 0.5;
    const fromTop = (clientY - rect.top) / rect.height;
    return Math.max(0, Math.min(1, 1 - fromTop));
  };

  const pitchPercentFromBottom = (pitchBendValue / PITCH_BEND_MAX) * 100;
  const modPercentFromBottom = (modulationValue / 127) * 100;

  const holdSurface = getLedSurfaceStyle(
    holdLed,
    holdPressed || sustainEnabled || chordModeEnabled || creatingChord
  );
  const octDownSurface = getLedSurfaceStyle(octDownLed, octaveDownActive);
  const octUpSurface = getLedSurfaceStyle(octUpLed, octaveUpActive);

  return (
    <aside
      aria-label="MIDI Controls"
      className="w-[10%] h-full shrink-0 flex flex-col p-[0.75vw] gap-[0.8vw] select-none touch-none"
      style={{
        background: 'linear-gradient(180deg, #F8DCE4 0%, #F2CDD7 100%)',
        borderRight: '1px solid rgba(154, 102, 117, 0.22)',
      }}
    >
      {/* Top 2x2 grid: Shift (⌘), Hold/Chord (∞), Octave Down (−), Octave Up (+) */}
      <div className="grid grid-cols-2 gap-[0.55vw] shrink-0">
        {/* 1. ⌘ = SHIFT (hold to enable, release to disable) */}
        <button
          type="button"
          aria-label="Shift"
          aria-pressed={shiftActive}
          onPointerDown={(e) => {
            e.preventDefault();
            e.currentTarget.setPointerCapture(e.pointerId);
            engine.setShiftPointer(e.pointerId, true);
          }}
          onPointerUp={(e) => {
            e.preventDefault();
            engine.setShiftPointer(e.pointerId, false);
          }}
          onPointerCancel={(e) => {
            e.preventDefault();
            engine.setShiftPointer(e.pointerId, false);
          }}
          className="dixe-spring-button aspect-square w-full rounded-[8px] flex items-center justify-center text-[clamp(12px,1.55vw,20px)] font-normal leading-none touch-none focus:outline-none focus-visible:ring-2 focus-visible:ring-[#7E4C5A]"
          style={{
            color: '#4F343C',
            background: shiftActive
              ? 'linear-gradient(180deg, #E8BECA 0%, #ECC6D2 100%)'
              : 'linear-gradient(180deg, #FCEAF0 0%, #F5D7DF 100%)',
            border: '1px solid rgba(168, 114, 129, 0.28)',
            boxShadow: shiftActive
              ? 'inset 0 2px 4px rgba(105, 58, 72, 0.24)'
              : '0 2px 5px rgba(112, 64, 78, 0.14), inset 0 1px 0 rgba(255, 255, 255, 0.72)',
            transform: shiftActive ? 'translateY(1.5px) scale(0.935)' : 'translateY(0) scale(1)',
          }}
        >
          ⌘
        </button>

        {/* 2. ∞ = HOLD / CHORD MODE (Shift+Tap = Chord Mode, Shift+LongPress = Create Chord) */}
        <button
          type="button"
          aria-label="Hold"
          aria-pressed={sustainEnabled || chordModeEnabled}
          onPointerDown={(e) => {
            e.preventDefault();
            setHoldPressed(true);
            e.currentTarget.setPointerCapture(e.pointerId);
            engine.handleHoldPointerDown(e.pointerId);
          }}
          onPointerUp={(e) => {
            e.preventDefault();
            setHoldPressed(false);
            engine.handleHoldPointerUp(e.pointerId, false);
          }}
          onPointerCancel={(e) => {
            e.preventDefault();
            setHoldPressed(false);
            engine.handleHoldPointerUp(e.pointerId, true);
          }}
          className={`dixe-spring-button aspect-square w-full rounded-[8px] flex items-center justify-center text-[clamp(13px,1.7vw,22px)] font-normal leading-none touch-none focus:outline-none focus-visible:ring-2 focus-visible:ring-[#7E4C5A] ${holdSurface.classNameExtra}`}
          style={{
            color: '#4F343C',
            ...holdSurface.style,
          }}
        >
          ∞
        </button>

        {/* 3. − = OCTAVE DOWN / SEMITONE DOWN */}
        <button
          type="button"
          aria-label="Octave Down"
          aria-pressed={octaveDownActive}
          onPointerDown={(e) => {
            e.preventDefault();
            e.currentTarget.setPointerCapture(e.pointerId);
            engine.setTransposePointer(e.pointerId, -1, true);
          }}
          onPointerUp={(e) => {
            e.preventDefault();
            engine.setTransposePointer(e.pointerId, -1, false);
          }}
          onPointerCancel={(e) => {
            e.preventDefault();
            engine.setTransposePointer(e.pointerId, -1, false);
          }}
          className={`dixe-spring-button aspect-square w-full rounded-[8px] flex items-center justify-center text-[clamp(13px,1.65vw,21px)] font-normal leading-none touch-none focus:outline-none focus-visible:ring-2 focus-visible:ring-[#7E4C5A] ${octDownSurface.classNameExtra}`}
          style={{
            color: '#4F343C',
            ...octDownSurface.style,
          }}
        >
          −
        </button>

        {/* 4. + = OCTAVE UP / SEMITONE UP */}
        <button
          type="button"
          aria-label="Octave Up"
          aria-pressed={octaveUpActive}
          onPointerDown={(e) => {
            e.preventDefault();
            e.currentTarget.setPointerCapture(e.pointerId);
            engine.setTransposePointer(e.pointerId, 1, true);
          }}
          onPointerUp={(e) => {
            e.preventDefault();
            engine.setTransposePointer(e.pointerId, 1, false);
          }}
          onPointerCancel={(e) => {
            e.preventDefault();
            engine.setTransposePointer(e.pointerId, 1, false);
          }}
          className={`dixe-spring-button aspect-square w-full rounded-[8px] flex items-center justify-center text-[clamp(13px,1.65vw,21px)] font-normal leading-none touch-none focus:outline-none focus-visible:ring-2 focus-visible:ring-[#7E4C5A] ${octUpSurface.classNameExtra}`}
          style={{
            color: '#4F343C',
            ...octUpSurface.style,
          }}
        >
          +
        </button>
      </div>

      {/* Bottom two vertical touch strips: Pitch Bend (left) & Modulation (right) */}
      <div className="flex-1 min-h-0 grid grid-cols-2 gap-[0.55vw]">
        {/* Left Strip: Pitch Bend */}
        <div
          ref={pitchStripRef}
          role="slider"
          aria-label="Pitch Bend"
          aria-valuemin={0}
          aria-valuemax={16383}
          aria-valuenow={pitchBendValue}
          onPointerDown={(e) => {
            e.preventDefault();
            setPitchDragging(true);
            e.currentTarget.setPointerCapture(e.pointerId);
            engine.startPitchBend(
              e.pointerId,
              computeNormFromBottom(e.clientY, pitchStripRef.current)
            );
          }}
          onPointerMove={(e) => {
            if (e.buttons === 0 && e.pointerType === 'mouse') return;
            engine.movePitchBend(
              e.pointerId,
              computeNormFromBottom(e.clientY, pitchStripRef.current)
            );
          }}
          onPointerUp={(e) => {
            e.preventDefault();
            setPitchDragging(false);
            engine.endPitchBend(e.pointerId);
          }}
          onPointerCancel={(e) => {
            e.preventDefault();
            setPitchDragging(false);
            engine.endPitchBend(e.pointerId);
          }}
          className="relative h-full w-full rounded-[8px] overflow-hidden touch-none transition-shadow duration-150"
          style={{
            background: 'linear-gradient(180deg, #E2BAC6 0%, #DCAFBCE0 100%)',
            border: '1px solid rgba(154, 102, 117, 0.28)',
            boxShadow: pitchDragging
              ? 'inset 0 2px 8px rgba(96, 52, 66, 0.28), 0 0 0 1px rgba(255, 255, 255, 0.35)'
              : 'inset 0 2px 6px rgba(96, 52, 66, 0.2), 0 1px 0 rgba(255, 255, 255, 0.5)',
          }}
        >
          <div
            className={`pointer-events-none absolute left-[12%] right-[12%] h-[2.5px] rounded-full ${
              pitchDragging ? '' : 'dixe-strip-snap'
            }`}
            style={{
              bottom: `calc(${pitchPercentFromBottom}% - 1.25px)`,
              backgroundColor: pitchDragging
                ? 'rgba(255, 255, 255, 1)'
                : 'rgba(255, 255, 255, 0.94)',
              boxShadow: pitchDragging
                ? '0 0 8px rgba(255, 255, 255, 0.85), 0 1px 2px rgba(72, 36, 48, 0.3)'
                : '0 1px 2px rgba(72, 36, 48, 0.28)',
              transform: pitchDragging ? 'scaleY(1.35)' : 'scaleY(1)',
            }}
          />
        </div>

        {/* Right Strip: Modulation (CC1) */}
        <div
          ref={modStripRef}
          role="slider"
          aria-label="Modulation"
          aria-valuemin={0}
          aria-valuemax={127}
          aria-valuenow={modulationValue}
          onPointerDown={(e) => {
            e.preventDefault();
            setModDragging(true);
            e.currentTarget.setPointerCapture(e.pointerId);
            engine.startModulation(
              e.pointerId,
              computeNormFromBottom(e.clientY, modStripRef.current)
            );
          }}
          onPointerMove={(e) => {
            if (e.buttons === 0 && e.pointerType === 'mouse') return;
            engine.moveModulation(
              e.pointerId,
              computeNormFromBottom(e.clientY, modStripRef.current)
            );
          }}
          onPointerUp={(e) => {
            e.preventDefault();
            setModDragging(false);
            engine.endModulation(e.pointerId);
          }}
          onPointerCancel={(e) => {
            e.preventDefault();
            setModDragging(false);
            engine.endModulation(e.pointerId);
          }}
          className="relative h-full w-full rounded-[8px] overflow-hidden touch-none transition-shadow duration-150"
          style={{
            background: 'linear-gradient(180deg, #E2BAC6 0%, #DCAFBCE0 100%)',
            border: '1px solid rgba(154, 102, 117, 0.28)',
            boxShadow: modDragging
              ? 'inset 0 2px 8px rgba(96, 52, 66, 0.28), 0 0 0 1px rgba(255, 255, 255, 0.35)'
              : 'inset 0 2px 6px rgba(96, 52, 66, 0.2), 0 1px 0 rgba(255, 255, 255, 0.5)',
          }}
        >
          <div
            className={`pointer-events-none absolute left-[12%] right-[12%] h-[2.5px] rounded-full ${
              modDragging ? '' : 'dixe-strip-snap'
            }`}
            style={{
              bottom: `calc(${modPercentFromBottom}% - 1.25px)`,
              backgroundColor: modDragging
                ? 'rgba(255, 255, 255, 1)'
                : 'rgba(255, 255, 255, 0.94)',
              boxShadow: modDragging
                ? '0 0 8px rgba(255, 255, 255, 0.85), 0 1px 2px rgba(72, 36, 48, 0.3)'
                : '0 1px 2px rgba(72, 36, 48, 0.28)',
              transform: modDragging ? 'scaleY(1.35)' : 'scaleY(1)',
            }}
          />
        </div>
      </div>
    </aside>
  );
};
