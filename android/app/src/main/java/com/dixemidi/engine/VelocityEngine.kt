package com.dixemidi.engine

import com.dixemidi.midi.MidiMessageBuilder
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Continuous nonlinear position-based velocity engine with ±2 humanization jitter.
 * Minimum key velocity: 30 (top of key).
 * Maximum key velocity: 127 (bottom of key).
 */
object VelocityEngine {
    const val MIN_KEY_VELOCITY = 30
    const val MAX_KEY_VELOCITY = 127
    const val VELOCITY_JITTER = 2

    fun calculateBase(touchY: Float, keyTop: Float, keyHeight: Float): Int {
        if (keyHeight <= 0f) return 64
        val p = ((touchY - keyTop) / keyHeight).coerceIn(0f, 1f).toDouble()
        val span = (MAX_KEY_VELOCITY - MIN_KEY_VELOCITY).toDouble()
        val raw =
            MIN_KEY_VELOCITY +
                (span * ((exp(3.0 * p) - 1.0) / (exp(3.0) - 1.0))).roundToInt()
        return raw.coerceIn(MIN_KEY_VELOCITY, MAX_KEY_VELOCITY)
    }

    fun calculate(touchY: Float, keyTop: Float, keyHeight: Float): Int {
        val base = calculateBase(touchY, keyTop, keyHeight)
        val jitter = Random.nextInt(-VELOCITY_JITTER, VELOCITY_JITTER + 1)
        return MidiMessageBuilder.clampVelocity(
            (base + jitter).coerceIn(MIN_KEY_VELOCITY, MAX_KEY_VELOCITY)
        )
    }
}
