# DIXE MIDI — Native Android USB MIDI Controller

## Architecture Overview

- `MainActivity.kt`: Enforces immersive sticky fullscreen `sensorLandscape` mode and manages MIDI lifecycle (`onStart`/`onStop`/`onPause` panic-free note release).
- `ui/DixeControllerView.kt`: Low-latency custom `View`/`Canvas` renderer implementing the 15% left control panel (`⌘`, `∞`, `−`, `+` + Pitch Bend & Modulation vertical strips) and 85% right 13-key C-to-C keyboard with zero visible branding.
- `engine/KeyboardGeometry.kt`: 13-key C-to-C layout (`8` white keys, `5` black keys) with black-key hit-testing priority over overlapping white keys.
- `engine/VelocityEngine.kt`: Continuous nonlinear position-based velocity (`1..120`, strictly capped at `120`), normalized per individual key's visible height.
- `midi/MidiStateController.kt`: Multitouch pointer ownership, reference-counted active MIDI notes, seamless key-to-key glide, CC64 sustain hold/release without terminating physically held keys, momentary Shift (`±1` semitone) vs normal (`±12` semitones), `Choreographer`-driven progressive Pitch Bend return-to-center (`0..16383`, center `8192`), and Modulation (`CC1`) retention.
- `midi/MidiConnectionManager.kt` & `DixeMidiDeviceService.kt`: Discovers and opens Android's system USB MIDI Peripheral Port (`MidiManager.openDevice` + `openInputPort(0)`) with hot-plug reconnection, sends RPN `00 00` (`±2` semitones) on connect, and exposes `DixeMidiDeviceService` on the Android MIDI routing bus.

## Android USB MIDI Peripheral Mode & Vivo V30e Notes

1. **OS Requirement**: Android 6.0+ (`android.media.midi` + `android.software.midi`) exposes a class-compliant USB MIDI gadget when the phone is connected via a data-capable USB cable and the user selects **MIDI** in the Android USB Preferences notification (or **Developer Options > Default USB configuration > MIDI**).
2. **How App -> Windows 11 Routing Works**: Selecting **MIDI** in Android USB settings activates the kernel USB MIDI gadget (`MidiDeviceInfo.TYPE_USB` / `Android USB Peripheral Port`). The app must open that system `MidiDeviceInfo` and write packets to its `MidiInputPort(0)` (`MidiConnectionManager.kt`). `MidiDeviceService` alone only creates an inter-app virtual MIDI port inside Android and does **not** automatically bind to the USB cable unless routed.
3. **OEM Firmware Caveat (Vivo V30e / Funtouch OS)**: Some OEM USB gadget configurations reset to *Charging Only* upon cable reconnect unless **Default USB configuration -> MIDI** is enabled in Developer Options. Additionally, if Vivo's USB driver suite on Windows 11 binds a composite ADB/MTP driver ahead of the class-compliant `usbaudio.sys` MIDI driver, disable USB Debugging temporarily or select **MIDI** only after unlocking the phone so Windows 11 enumerates the class-compliant USB MIDI input device cleanly.
4. **FL Studio Setup**:
   - Connect Vivo V30e via USB-C data cable to Windows 11.
   - Select **MIDI** in the phone's USB Preferences shade.
   - Open FL Studio -> `Options > MIDI Settings` (`F10`) -> locate the Android USB MIDI device under **Input** -> click **Enable**.

## Building an Installable Release APK

Because this web workspace environment does not include the Android SDK, JDK, or Gradle, compile the APK locally in Android Studio or via Gradle CLI:

```bash
cd android
# Generate or use Android Studio's Gradle wrapper, then:
./gradlew assembleRelease
```

For testing on Vivo V30e via ADB:

```bash
./gradlew installDebug
```
