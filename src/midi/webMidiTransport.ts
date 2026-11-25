export interface MidiTransport {
  send(message: Uint8Array): void;
  dispose(): void;
}

/**
 * Web MIDI API transport for Chromium / Android Chrome / Desktop browsers.
 * Automatically discovers and transmits MIDI messages to all connected MIDI outputs
 * without generating any internal audio.
 */
export class WebMidiTransport implements MidiTransport {
  private midiAccess: MIDIAccess | null = null;
  private outputs: MIDIOutput[] = [];

  constructor(_channel = 0) {
    void this.init();
  }

  private async init(): Promise<void> {
    if (typeof navigator === 'undefined' || typeof navigator.requestMIDIAccess !== 'function') {
      return;
    }

    try {
      const access = await navigator.requestMIDIAccess({ sysex: false });
      this.midiAccess = access;
      this.refreshOutputs();
      access.onstatechange = () => {
        this.refreshOutputs();
      };
    } catch {
      // Permission denied or Web MIDI unavailable in current browser context
    }
  }

  private refreshOutputs(): void {
    if (!this.midiAccess) return;
    const nextOutputs: MIDIOutput[] = [];
    this.midiAccess.outputs.forEach((output) => {
      if (output.state === 'connected') {
        nextOutputs.push(output);
      }
    });
    this.outputs = nextOutputs;
  }

  public send(message: Uint8Array): void {
    for (let i = 0; i < this.outputs.length; i++) {
      try {
        this.outputs[i].send(message);
      } catch {
        // Ignore disconnected port during hot-unplug; onstatechange cleans it up
      }
    }
  }

  public dispose(): void {
    if (this.midiAccess) {
      this.midiAccess.onstatechange = null;
    }
    this.outputs = [];
  }
}
