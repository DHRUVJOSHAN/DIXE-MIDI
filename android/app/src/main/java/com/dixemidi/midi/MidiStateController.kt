package com.dixemidi.midi

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.roundToInt

enum class ButtonLedState {
    NONE,
    WHITE,
    BLUE,
    BLINK_WHITE_BLUE,
    PULSE_WHITE_BLUE
}

/**
 * Authoritative MIDI state machine for multitouch note ownership, smooth glide,
 * MiniLab 3 Hold simultaneous latching, Chord Mode + persistent user chord creation,
 * Shift + Keys (F1..G#2) MIDI Channel 1..16 selection, -4..+4 Octave & -12..+12 Semitone
 * transposition with LED states, immediate spring-loaded Pitch Bend return, and CC1 Modulation retention.
 */
class MidiStateController(
    private val connectionManager: MidiConnectionManager,
    private val onStateChanged: () -> Unit,
    initialChannel: Int = 0,
    private val prefs: SharedPreferences? = null
) {
    private data class ActivePointerNote(
        val keyIndex: Int,
        val notes: IntArray,
        val isChannelSelect: Boolean = false
    )

    companion object {
        const val DEFAULT_BASE_MIDI_NOTE = 60 // C4
        private const val MIN_OCTAVE_OFFSET = -4
        private const val MAX_OCTAVE_OFFSET = 4
        private const val MIN_SEMITONE_OFFSET = -12
        private const val MAX_SEMITONE_OFFSET = 12
        private const val HOLD_LONG_PRESS_MS = 600L
        private const val SHIFT_TAP_THRESHOLD_MS = 400L
        private const val SHIFT_AUTO_OFF_MS = 2000L
        private const val PREF_CHORD_KEY = "dixe_midi_user_chord_v1"
        private val DEFAULT_CHORD_INTERVALS = intArrayOf(0, 4, 7)
    }

    var channel: Int = initialChannel.coerceIn(0, 15)
        private set

    private var octaveOffset: Int = 0
    private var semitoneOffset: Int = 0

    val baseMidiNote: Int
        get() = MidiMessageBuilder.clampNote(
            DEFAULT_BASE_MIDI_NOTE + octaveOffset * 12 + semitoneOffset
        )

    var sustainEnabled: Boolean = false
        private set

    var chordModeEnabled: Boolean = false
        private set

    var creatingChord: Boolean = false
        private set

    var pitchBendValue: Int = MidiMessageBuilder.PITCH_BEND_CENTER
        private set

    var modulationValue: Int = 0
        private set

    val isShiftActive: Boolean
        get() = shiftPointers.isNotEmpty() || shiftLatched

    val isOctaveDownActive: Boolean
        get() = octDownPointers.isNotEmpty()

    val isOctaveUpActive: Boolean
        get() = octUpPointers.isNotEmpty()

    private var userChordIntervals: IntArray = loadPersistedChord()
    private val recordedChordNotes = ArrayList<Int>(16)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeHoldPointerId: Int? = null
    private var holdShiftInitiated = false
    private var holdLongPressed = false
    private val holdLongPressRunnable = Runnable {
        if (activeHoldPointerId != null) {
            holdLongPressed = true
            creatingChord = true
            recordedChordNotes.clear()
            onStateChanged()
        }
    }

    private val activePointers = HashMap<Int, ActivePointerNote>(16)
    private val shiftPointers = HashSet<Int>(4)
    private var shiftLatched = false
    private var shiftWasLatchedOnPress = false
    private var shiftComboUsedDuringHold = false
    private var shiftPressTimeMs = 0L
    private val shiftAutoOffRunnable = Runnable {
        if (shiftLatched) {
            shiftLatched = false
            onStateChanged()
        }
    }
    private val octDownPointers = HashSet<Int>(4)
    private val octUpPointers = HashSet<Int>(4)
    private var octaveComboConsumed = false
    private val physicalHoldCount = IntArray(128)
    private val sustainedNotes = HashSet<Int>(16)

    private var activePitchPointerId: Int? = null
    private var activeModPointerId: Int? = null

    private fun loadPersistedChord(): IntArray {
        val raw = prefs?.getString(PREF_CHORD_KEY, null) ?: return DEFAULT_CHORD_INTERVALS.copyOf()
        val parsed = raw.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..127 }
            .distinct()
            .sorted()
        return if (parsed.isNotEmpty()) parsed.toIntArray() else DEFAULT_CHORD_INTERVALS.copyOf()
    }

    private fun persistChord(intervals: IntArray) {
        prefs?.edit()?.putString(PREF_CHORD_KEY, intervals.joinToString(","))?.apply()
    }

    fun getOctDownLedState(): ButtonLedState = getOctaveLedState(-1)

    fun getOctUpLedState(): ButtonLedState = getOctaveLedState(1)

    private fun getOctaveLedState(direction: Int): ButtonLedState {
        val octActive = if (direction < 0) octaveOffset < 0 else octaveOffset > 0
        val semiActive = if (direction < 0) semitoneOffset < 0 else semitoneOffset > 0

        if (octActive && semiActive) {
            return ButtonLedState.BLINK_WHITE_BLUE
        }
        if (octActive) return ButtonLedState.WHITE
        if (semiActive) return ButtonLedState.BLUE
        return ButtonLedState.NONE
    }

    fun getHoldLedState(): ButtonLedState {
        if (creatingChord) return ButtonLedState.BLUE
        if (sustainEnabled && chordModeEnabled) return ButtonLedState.PULSE_WHITE_BLUE
        if (sustainEnabled) return ButtonLedState.WHITE
        if (chordModeEnabled) return ButtonLedState.BLUE
        return ButtonLedState.NONE
    }

    fun needsLedAnimation(): Boolean {
        val octLed = getOctDownLedState() == ButtonLedState.BLINK_WHITE_BLUE ||
            getOctUpLedState() == ButtonLedState.BLINK_WHITE_BLUE
        val holdLed = getHoldLedState() == ButtonLedState.PULSE_WHITE_BLUE
        return octLed || holdLed
    }

    fun isKeyPressed(keyIndex: Int): Boolean {
        for (entry in activePointers.values) {
            if (!entry.isChannelSelect && entry.keyIndex == keyIndex) return true
        }
        return false
    }

    private fun resolveNotesForKey(semitoneFromBase: Int): IntArray {
        val rootNote = MidiMessageBuilder.clampNote(baseMidiNote + semitoneFromBase)
        if (creatingChord || !chordModeEnabled) {
            return intArrayOf(rootNote)
        }
        val notes = ArrayList<Int>(userChordIntervals.size)
        for (interval in userChordIntervals) {
            val candidate = rootNote + interval
            if (candidate in 0..127 && !notes.contains(candidate)) {
                notes.add(candidate)
            }
        }
        return if (notes.isNotEmpty()) notes.toIntArray() else intArrayOf(rootNote)
    }

    private fun countPlayingKeyPointers(): Int {
        var count = 0
        for (entry in activePointers.values) {
            if (!entry.isChannelSelect) count += 1
        }
        return count
    }

    private fun clearLatchedNotes() {
        for (note in sustainedNotes) {
            if (physicalHoldCount[note] == 0) {
                connectionManager.send(MidiMessageBuilder.noteOff(channel, note, 0))
            }
        }
        sustainedNotes.clear()
    }

    private fun switchGlobalChannel(nextChannel: Int) {
        val clamped = nextChannel.coerceIn(0, 15)
        if (clamped == channel) return

        clearLatchedNotes()
        for (entry in activePointers.values) {
            if (!entry.isChannelSelect) {
                for (note in entry.notes) {
                    connectionManager.send(MidiMessageBuilder.noteOff(channel, note, 0))
                }
            }
        }
        activePointers.clear()
        physicalHoldCount.fill(0)

        channel = clamped
    }

    fun onKeyPointerDown(pointerId: Int, keyIndex: Int, semitoneOffset: Int, velocity: Int) {
        if (activePointers.containsKey(pointerId)) {
            releasePointerInternal(pointerId)
        }

        // Shift + Keys (F1 to G#2, semitone offsets 5..20) selects global MIDI Channel 1..16
        if (isShiftActive && !creatingChord && semitoneOffset in 5..20) {
            if (shiftPointers.isNotEmpty()) {
                shiftComboUsedDuringHold = true
            }
            switchGlobalChannel(semitoneOffset - 5)
            activePointers[pointerId] = ActivePointerNote(
                keyIndex = keyIndex,
                notes = IntArray(0),
                isChannelSelect = true
            )
            onStateChanged()
            return
        }

        // MiniLab 3 Hold Mode simultaneous latching rule:
        // Pressing a new key or chord after lifting all fingers stops previously latched notes.
        if (sustainEnabled && !creatingChord && countPlayingKeyPointers() == 0) {
            clearLatchedNotes()
        }

        val notes = resolveNotesForKey(semitoneOffset)
        if (creatingChord) {
            for (note in notes) {
                if (!recordedChordNotes.contains(note)) {
                    recordedChordNotes.add(note)
                }
            }
        }

        triggerNotesOn(pointerId, keyIndex, notes, velocity)
        onStateChanged()
    }

    fun onKeyPointerMove(
        pointerId: Int,
        keyIndex: Int?,
        semitoneOffset: Int?,
        velocity: Int?
    ) {
        val existing = activePointers[pointerId] ?: return
        if (existing.isChannelSelect) return

        if (keyIndex == null || semitoneOffset == null || velocity == null) {
            releasePointerInternal(pointerId)
            onStateChanged()
            return
        }
        if (existing.keyIndex == keyIndex) return

        // Glide: Note Off old key/chord, immediately Note On new key/chord
        releasePointerInternal(pointerId)
        val notes = resolveNotesForKey(semitoneOffset)
        if (creatingChord) {
            for (note in notes) {
                if (!recordedChordNotes.contains(note)) {
                    recordedChordNotes.add(note)
                }
            }
        }
        triggerNotesOn(pointerId, keyIndex, notes, velocity)
        onStateChanged()
    }

    fun onKeyPointerUp(pointerId: Int) {
        if (!activePointers.containsKey(pointerId)) return
        releasePointerInternal(pointerId)
        onStateChanged()
    }

    private fun triggerNotesOn(pointerId: Int, keyIndex: Int, notes: IntArray, velocity: Int) {
        for (midiNote in notes) {
            if (sustainedNotes.remove(midiNote)) {
                connectionManager.send(MidiMessageBuilder.noteOff(channel, midiNote, 0))
            }
            physicalHoldCount[midiNote] += 1
        }
        activePointers[pointerId] = ActivePointerNote(keyIndex, notes)
        for (midiNote in notes) {
            connectionManager.send(MidiMessageBuilder.noteOn(channel, midiNote, velocity))
        }
    }

    private fun releasePointerInternal(pointerId: Int) {
        val entry = activePointers.remove(pointerId) ?: return
        if (entry.isChannelSelect) return

        for (note in entry.notes) {
            if (physicalHoldCount[note] > 0) {
                physicalHoldCount[note] -= 1
            }
            if (physicalHoldCount[note] == 0) {
                if (sustainEnabled && !creatingChord) {
                    sustainedNotes.add(note)
                } else {
                    connectionManager.send(MidiMessageBuilder.noteOff(channel, note, 0))
                }
            }
        }
    }

    fun handleHoldPointerDown(pointerId: Int) {
        mainHandler.removeCallbacks(holdLongPressRunnable)
        activeHoldPointerId = pointerId
        holdLongPressed = false
        holdShiftInitiated = isShiftActive

        if (!holdShiftInitiated) {
            toggleSustain()
            return
        }

        if (shiftPointers.isNotEmpty()) {
            shiftComboUsedDuringHold = true
        }

        mainHandler.postDelayed(holdLongPressRunnable, HOLD_LONG_PRESS_MS)
    }

    fun handleHoldPointerUp(pointerId: Int, cancelled: Boolean = false) {
        if (activeHoldPointerId != pointerId) return
        activeHoldPointerId = null
        val shiftInitiated = holdShiftInitiated
        holdShiftInitiated = false
        mainHandler.removeCallbacks(holdLongPressRunnable)

        if (holdLongPressed) {
            holdLongPressed = false
            creatingChord = false
            if (recordedChordNotes.isNotEmpty()) {
                val minNote = recordedChordNotes.minOrNull() ?: 0
                val intervals = recordedChordNotes
                    .map { it - minNote }
                    .distinct()
                    .sorted()
                    .toIntArray()
                userChordIntervals = intervals
                persistChord(intervals)
                chordModeEnabled = true
            }
            recordedChordNotes.clear()
            onStateChanged()
            return
        }

        if (!cancelled && shiftInitiated) {
            chordModeEnabled = !chordModeEnabled
            onStateChanged()
        }
    }

    fun toggleSustain() {
        sustainEnabled = !sustainEnabled
        if (!sustainEnabled) {
            clearLatchedNotes()
        }
        onStateChanged()
    }

    fun setShiftPointer(pointerId: Int, active: Boolean) {
        if (active) {
            if (shiftPointers.contains(pointerId)) return
            if (shiftPointers.isEmpty()) {
                shiftWasLatchedOnPress = shiftLatched
                shiftComboUsedDuringHold = false
                shiftPressTimeMs = SystemClock.uptimeMillis()
                mainHandler.removeCallbacks(shiftAutoOffRunnable)
            }
            shiftPointers.add(pointerId)
            onStateChanged()
            return
        }

        if (!shiftPointers.remove(pointerId)) return

        if (shiftPointers.isEmpty()) {
            val heldDurationMs = SystemClock.uptimeMillis() - shiftPressTimeMs
            if (shiftWasLatchedOnPress ||
                shiftComboUsedDuringHold ||
                heldDurationMs >= SHIFT_TAP_THRESHOLD_MS
            ) {
                shiftLatched = false
                mainHandler.removeCallbacks(shiftAutoOffRunnable)
            } else {
                shiftLatched = true
                mainHandler.removeCallbacks(shiftAutoOffRunnable)
                mainHandler.postDelayed(shiftAutoOffRunnable, SHIFT_AUTO_OFF_MS)
            }
            shiftWasLatchedOnPress = false
            shiftComboUsedDuringHold = false
        }
        onStateChanged()
    }

    fun setTransposePointer(pointerId: Int, direction: Int, active: Boolean) {
        val targetSet = if (direction > 0) octUpPointers else octDownPointers
        if (active) {
            val alreadyTracked = targetSet.contains(pointerId)
            targetSet.add(pointerId)
            if (shiftPointers.isNotEmpty()) {
                shiftComboUsedDuringHold = true
            }

            if (octDownPointers.isNotEmpty() && octUpPointers.isNotEmpty()) {
                // Simultaneous OCTAVE UP (+) and OCTAVE DOWN (−):
                // Reset octave & semitone transposition to 0 (default C-to-C mapping),
                // regardless of Shift state and without interrupting sounding notes.
                octaveOffset = 0
                semitoneOffset = 0
                octaveComboConsumed = true
            } else if (!alreadyTracked && !octaveComboConsumed) {
                transpose(direction)
            }
            onStateChanged()
        } else {
            val removed = targetSet.remove(pointerId)
            if (octDownPointers.isEmpty() && octUpPointers.isEmpty()) {
                octaveComboConsumed = false
            }
            if (removed) {
                onStateChanged()
            }
        }
    }

    fun transpose(direction: Int) {
        if (isShiftActive) {
            semitoneOffset = (semitoneOffset + direction)
                .coerceIn(MIN_SEMITONE_OFFSET, MAX_SEMITONE_OFFSET)
        } else {
            octaveOffset = (octaveOffset + direction)
                .coerceIn(MIN_OCTAVE_OFFSET, MAX_OCTAVE_OFFSET)
        }
    }

    fun startPitchBend(pointerId: Int, normalizedYFromBottom: Float) {
        activePitchPointerId = pointerId
        applyPitchBend(normalizedYFromBottom, forceEmit = true)
    }

    fun movePitchBend(pointerId: Int, normalizedYFromBottom: Float) {
        if (activePitchPointerId != pointerId) return
        applyPitchBend(normalizedYFromBottom, forceEmit = false)
    }

    fun endPitchBend(pointerId: Int) {
        if (activePitchPointerId != pointerId) return
        activePitchPointerId = null
        // Spring-loaded Pitch Bend strip: snap immediately back to center on release
        if (pitchBendValue != MidiMessageBuilder.PITCH_BEND_CENTER) {
            pitchBendValue = MidiMessageBuilder.PITCH_BEND_CENTER
            connectionManager.send(MidiMessageBuilder.pitchBend(channel, pitchBendValue))
            onStateChanged()
        }
    }

    private fun applyPitchBend(norm: Float, forceEmit: Boolean) {
        val clamped = norm.coerceIn(0f, 1f)
        val nextValue = if (clamped >= 0.5f) {
            MidiMessageBuilder.PITCH_BEND_CENTER +
                (((clamped - 0.5f) / 0.5f) *
                    (MidiMessageBuilder.PITCH_BEND_MAX - MidiMessageBuilder.PITCH_BEND_CENTER)).roundToInt()
        } else {
            ((clamped / 0.5f) * MidiMessageBuilder.PITCH_BEND_CENTER).roundToInt()
        }.coerceIn(MidiMessageBuilder.PITCH_BEND_MIN, MidiMessageBuilder.PITCH_BEND_MAX)

        if (forceEmit || nextValue != pitchBendValue) {
            val changed = nextValue != pitchBendValue
            pitchBendValue = nextValue
            connectionManager.send(MidiMessageBuilder.pitchBend(channel, nextValue))
            if (changed) {
                onStateChanged()
            }
        }
    }

    fun startModulation(pointerId: Int, normalizedYFromBottom: Float) {
        activeModPointerId = pointerId
        applyModulation(normalizedYFromBottom, forceEmit = true)
    }

    fun moveModulation(pointerId: Int, normalizedYFromBottom: Float) {
        if (activeModPointerId != pointerId) return
        applyModulation(normalizedYFromBottom, forceEmit = false)
    }

    fun endModulation(pointerId: Int) {
        if (activeModPointerId == pointerId) {
            activeModPointerId = null
        }
    }

    private fun applyModulation(norm: Float, forceEmit: Boolean) {
        val clamped = norm.coerceIn(0f, 1f)
        val nextValue = MidiMessageBuilder.clampData((clamped * 127f).roundToInt())
        if (forceEmit || nextValue != modulationValue) {
            val changed = nextValue != modulationValue
            modulationValue = nextValue
            connectionManager.send(MidiMessageBuilder.controlChange(channel, 1, nextValue))
            if (changed) {
                onStateChanged()
            }
        }
    }

    /**
     * Releases all active finger touches (e.g. on ACTION_CANCEL or transient pause)
     * without disabling latched toggles like Hold, Chord Mode, Shift, or Octave/Semitone offsets.
     */
    fun releaseActiveTouches() {
        mainHandler.removeCallbacks(holdLongPressRunnable)
        mainHandler.removeCallbacks(shiftAutoOffRunnable)
        shiftLatched = false
        activeHoldPointerId = null
        holdShiftInitiated = false
        holdLongPressed = false
        creatingChord = false
        recordedChordNotes.clear()

        val ids = activePointers.keys.toList()
        for (id in ids) {
            releasePointerInternal(id)
        }
        shiftPointers.clear()
        octDownPointers.clear()
        octUpPointers.clear()
        octaveComboConsumed = false
        activePitchPointerId = null
        activeModPointerId = null
        if (pitchBendValue != MidiMessageBuilder.PITCH_BEND_CENTER) {
            pitchBendValue = MidiMessageBuilder.PITCH_BEND_CENTER
            connectionManager.send(MidiMessageBuilder.pitchBend(channel, pitchBendValue))
        }
        onStateChanged()
    }

    fun releaseAll() {
        releaseActiveTouches()
        if (sustainEnabled) {
            sustainEnabled = false
            connectionManager.send(MidiMessageBuilder.controlChange(channel, 64, 0))
        }
        clearLatchedNotes()
        physicalHoldCount.fill(0)
        onStateChanged()
    }
}
