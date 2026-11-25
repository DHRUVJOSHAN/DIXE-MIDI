package com.dixemidi.midi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MidiMessageBuilderTest {
    @Test
    fun noteOnClampsVelocityTo127() {
        val msg = MidiMessageBuilder.noteOn(0, 60, 200)
        assertEquals(0x90.toByte(), msg[0])
        assertEquals(60.toByte(), msg[1])
        assertEquals(127.toByte(), msg[2])
    }

    @Test
    fun pitchBendCenterEncodes8192() {
        val msg = MidiMessageBuilder.pitchBend(0, 8192)
        assertArrayEquals(byteArrayOf(0xE0.toByte(), 0x00, 0x40), msg)
    }

    @Test
    fun rpnPitchBendSensitivityEmitsStandardSequence() {
        val packets = MidiMessageBuilder.rpnPitchBendSensitivity(0, 2, 0)
        assertEquals(6, packets.size)
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 101, 0), packets[0])
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 100, 0), packets[1])
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 6, 2), packets[2])
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 38, 0), packets[3])
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 101, 127), packets[4])
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 100, 127), packets[5])
    }
}
