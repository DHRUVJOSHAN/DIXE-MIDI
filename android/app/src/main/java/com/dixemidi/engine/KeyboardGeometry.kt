package com.dixemidi.engine

import android.graphics.RectF

data class KeyHitResult(
    val keyIndex: Int,
    val semitoneOffset: Int,
    val isBlack: Boolean,
    val keyTop: Float,
    val keyHeight: Float
)

data class KeyVisualRect(
    val keyIndex: Int,
    val semitoneOffset: Int,
    val isBlack: Boolean,
    val rect: RectF
)

/**
 * 25-key 2-octave C-to-C geometry (15 white keys + 10 black keys) with black-key hit-testing priority.
 */
class KeyboardGeometry {
    companion object {
        private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun formatKeyLabel(midiNote: Int): String {
            val clamped = midiNote.coerceIn(0, 127)
            val noteName = NOTE_NAMES[clamped % 12]
            val octave = (clamped / 12) - 1
            return "$noteName$octave"
        }
    }

    private val whiteSemitones = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24)
    private val blackSpecs = arrayOf(
        Triple(1, 1, 0.94f),
        Triple(3, 3, 2.06f),
        Triple(6, 6, 3.93f),
        Triple(8, 8, 5.00f),
        Triple(10, 10, 6.07f),
        Triple(13, 13, 7.94f),
        Triple(15, 15, 9.06f),
        Triple(18, 18, 10.93f),
        Triple(20, 20, 12.00f),
        Triple(22, 22, 13.07f)
    )

    val whiteKeys = ArrayList<KeyVisualRect>(15)
    val blackKeys = ArrayList<KeyVisualRect>(10)

    private var boardLeft = 0f
    private var boardTop = 0f
    private var boardWidth = 0f
    private var boardHeight = 0f

    fun layout(left: Float, top: Float, right: Float, bottom: Float) {
        boardLeft = left
        boardTop = top
        boardWidth = (right - left).coerceAtLeast(1f)
        boardHeight = (bottom - top).coerceAtLeast(1f)

        whiteKeys.clear()
        blackKeys.clear()

        val whiteCount = whiteSemitones.size
        val whiteW = boardWidth / whiteCount.toFloat()
        val whiteGap = boardWidth * 0.0022f

        for (i in 0 until whiteCount) {
            val l = boardLeft + i * whiteW + whiteGap
            val r = boardLeft + (i + 1) * whiteW - whiteGap
            val semitone = whiteSemitones[i]
            whiteKeys.add(
                KeyVisualRect(
                    keyIndex = semitone,
                    semitoneOffset = semitone,
                    isBlack = false,
                    rect = RectF(l, boardTop, r, bottom)
                )
            )
        }

        val blackW = whiteW * 0.56f
        val blackH = boardHeight * 0.585f

        for ((keyIndex, semitone, centerUnits) in blackSpecs) {
            val cx = boardLeft + centerUnits * whiteW
            val l = cx - blackW / 2f
            val r = cx + blackW / 2f
            blackKeys.add(
                KeyVisualRect(
                    keyIndex = keyIndex,
                    semitoneOffset = semitone,
                    isBlack = true,
                    rect = RectF(l, boardTop, r, boardTop + blackH)
                )
            )
        }
    }

    fun hitTest(x: Float, y: Float): KeyHitResult? {
        if (x < boardLeft || x > boardLeft + boardWidth || y < boardTop || y > boardTop + boardHeight) {
            return null
        }

        // Priority 1: Black keys
        for (i in 0 until blackKeys.size) {
            val k = blackKeys[i]
            if (k.rect.contains(x, y)) {
                return KeyHitResult(
                    keyIndex = k.keyIndex,
                    semitoneOffset = k.semitoneOffset,
                    isBlack = true,
                    keyTop = k.rect.top,
                    keyHeight = k.rect.height()
                )
            }
        }

        // Priority 2: White keys
        val whiteCount = whiteSemitones.size
        val whiteW = boardWidth / whiteCount.toFloat()
        val idx = ((x - boardLeft) / whiteW).toInt().coerceIn(0, whiteCount - 1)
        val wk = whiteKeys[idx]
        return KeyHitResult(
            keyIndex = wk.keyIndex,
            semitoneOffset = wk.semitoneOffset,
            isBlack = false,
            keyTop = boardTop,
            keyHeight = boardHeight
        )
    }
}
