package com.dixemidi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VelocityEngineTest {
    @Test
    fun topOfKeyBaseVelocityIs30AndBottomIs127() {
        assertEquals(30, VelocityEngine.calculateBase(100f, 100f, 500f))
        assertEquals(127, VelocityEngine.calculateBase(600f, 100f, 500f))
        assertEquals(127, VelocityEngine.calculateBase(900f, 100f, 500f))
    }

    @Test
    fun baseVelocityIncreasesMonotonicallyWithin30To127() {
        var prev = 0
        for (step in 0..100) {
            val y = 50f + step * 4f
            val v = VelocityEngine.calculateBase(y, 50f, 400f)
            assertTrue("Base velocity must be within 30..127", v in 30..127)
            assertTrue("Base velocity must increase monotonically", v >= prev)
            prev = v
        }
    }

    @Test
    fun randomizedVelocityStaysWithin30To127AndWithinPlusMinus2OfBase() {
        val baseMid = VelocityEngine.calculateBase(250f, 50f, 400f)
        for (i in 0 until 200) {
            val top = VelocityEngine.calculate(50f, 50f, 400f)
            val mid = VelocityEngine.calculate(250f, 50f, 400f)
            val bottom = VelocityEngine.calculate(450f, 50f, 400f)
            assertTrue(top in 30..32)
            assertTrue(mid in (baseMid - 2)..(baseMid + 2))
            assertTrue(bottom in 125..127)
        }
    }
}
