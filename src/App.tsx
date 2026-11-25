import { useEffect, useMemo, useState } from 'react';
import { ControlPanel } from './components/ControlPanel';
import { PianoKeyboard } from './components/PianoKeyboard';
import { PITCH_BEND_CENTER } from './midi/midiProtocol';
import { MidiStateEngine, type MidiUiSnapshot } from './midi/midiStateEngine';
import { WebMidiTransport } from './midi/webMidiTransport';

const INITIAL_SNAPSHOT: MidiUiSnapshot = {
  pressedKeyIndices: new Set<number>(),
  baseMidiNote: 60,
  midiChannel: 0,
  sustainEnabled: false,
  chordModeEnabled: false,
  creatingChord: false,
  shiftActive: false,
  octaveDownActive: false,
  octaveUpActive: false,
  octDownLed: 'none',
  octUpLed: 'none',
  holdLed: 'none',
  pitchBendValue: PITCH_BEND_CENTER,
  modulationValue: 0,
};

export default function App() {
  const [snapshot, setSnapshot] = useState<MidiUiSnapshot>(INITIAL_SNAPSHOT);

  const engine = useMemo(() => {
    const transport = new WebMidiTransport(0);
    return new MidiStateEngine(transport, (next) => {
      setSnapshot(next);
    });
  }, []);

  useEffect(() => {
    let wakeLock: WakeLockSentinel | null = null;
    let disposed = false;

    const requestWakeLock = async () => {
      if (disposed || document.hidden) return;
      if (typeof navigator === 'undefined' || !('wakeLock' in navigator)) return;
      if (wakeLock && !wakeLock.released) return;
      try {
        wakeLock = await navigator.wakeLock.request('screen');
      } catch {
        // Ignore if blocked before user gesture or unsupported in current context
      }
    };

    const releaseWakeLock = async () => {
      if (wakeLock && !wakeLock.released) {
        try {
          await wakeLock.release();
        } catch {
          // Ignore release errors
        }
      }
      wakeLock = null;
    };

    void requestWakeLock();

    const handleUserGesture = () => {
      void requestWakeLock();
    };
    const handleBlur = () => {
      engine.releaseActiveTouches();
    };
    const handleVisibility = () => {
      if (document.hidden) {
        engine.releaseAllNotes();
        void releaseWakeLock();
      } else {
        void requestWakeLock();
      }
    };

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'PrintScreen' || ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'p')) {
        e.preventDefault();
      }
    };

    window.addEventListener('pointerdown', handleUserGesture, { passive: true });
    window.addEventListener('keydown', handleKeyDown);
    window.addEventListener('blur', handleBlur);
    document.addEventListener('visibilitychange', handleVisibility);
    return () => {
      disposed = true;
      window.removeEventListener('pointerdown', handleUserGesture);
      window.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('blur', handleBlur);
      document.removeEventListener('visibilitychange', handleVisibility);
      void releaseWakeLock();
      engine.dispose();
    };
  }, [engine]);

  return (
    <div
      onContextMenu={(e) => e.preventDefault()}
      className="w-screen h-dvh flex flex-row overflow-hidden select-none touch-none"
      style={{ backgroundColor: '#F3D1DA' }}
    >
      <ControlPanel
        engine={engine}
        sustainEnabled={snapshot.sustainEnabled}
        chordModeEnabled={snapshot.chordModeEnabled}
        creatingChord={snapshot.creatingChord}
        shiftActive={snapshot.shiftActive}
        octaveDownActive={snapshot.octaveDownActive}
        octaveUpActive={snapshot.octaveUpActive}
        octDownLed={snapshot.octDownLed}
        octUpLed={snapshot.octUpLed}
        holdLed={snapshot.holdLed}
        pitchBendValue={snapshot.pitchBendValue}
        modulationValue={snapshot.modulationValue}
      />
      <PianoKeyboard
        engine={engine}
        pressedKeyIndices={snapshot.pressedKeyIndices}
        baseMidiNote={snapshot.baseMidiNote}
      />
    </div>
  );
}
