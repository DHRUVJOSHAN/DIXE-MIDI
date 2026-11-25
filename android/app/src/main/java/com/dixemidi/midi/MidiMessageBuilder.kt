package com.dixemidi.midi

object MidiMessageBuilder {
    const val MIN_VELOCITY = 1
    const val MAX_VELOCITY = 127
    const val PITCH_BEND_MIN = 0
    const val PITCH_BEND_CENTER = 8192
    const val PITCH_BEND_MAX = 16383

    fun clampNote(note: Int): Int = note.coerceIn(0, 127)

    fun clampData(value: Int): Int = value.coerceIn(0, 127)

    fun clampVelocity(velocity: Int): Int = velocity.coerceIn(MIN_VELOCITY, MAX_VELOCITY)

    fun clampChannel(channel: Int): Int = channel.coerceIn(0, 15)

    fun noteOn(channel: Int, note: Int, velocity: Int): ByteArray {
        val ch = clampChannel(channel)
        return byteArrayOf(
            (0x90 or ch).toByte(),
            clampNote(note).toByte(),
            clampVelocity(velocity).toByte()
        )
    }

    fun noteOff(channel: Int, note: Int, releaseVelocity: Int = 0): ByteArray {
        val ch = clampChannel(channel)
        return byteArrayOf(
            (0x80 or ch).toByte(),
            clampNote(note).toByte(),
            clampData(releaseVelocity).toByte()
        )
    }

    fun controlChange(channel: Int, ccNumber: Int, value: Int): ByteArray {
        val ch = clampChannel(channel)
        return byteArrayOf(
            (0xB0 or ch).toByte(),
            clampData(ccNumber).toByte(),
            clampData(value).toByte()
        )
    }

    fun pitchBend(channel: Int, value14Bit: Int): ByteArray {
        val ch = clampChannel(channel)
        val clamped = value14Bit.coerceIn(PITCH_BEND_MIN, PITCH_BEND_MAX)
        val lsb = clamped and 0x7F
        val msb = (clamped ushr 7) and 0x7F
        return byteArrayOf(
            (0xE0 or ch).toByte(),
            lsb.toByte(),
            msb.toByte()
        )
    }

    fun programChange(channel: Int, program: Int): ByteArray {
        val ch = clampChannel(channel)
        return byteArrayOf(
            (0xC0 or ch).toByte(),
            clampData(program).toByte()
        )
    }

    /**
     * RPN 00 00: Pitch Bend Sensitivity (±semitones, cents) followed by Null RPN (7F 7F).
     */
    fun rpnPitchBendSensitivity(
        channel: Int,
        semitones: Int = 2,
        cents: Int = 0
    ): List<ByteArray> = listOf(
        controlChange(channel, 101, 0),
        controlChange(channel, 100, 0),
        controlChange(channel, 6, clampData(semitones)),
        controlChange(channel, 38, clampData(cents)),
        controlChange(channel, 101, 127),
        controlChange(channel, 100, 127)
    )
}
