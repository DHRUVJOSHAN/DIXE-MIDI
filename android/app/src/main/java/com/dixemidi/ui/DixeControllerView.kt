package com.dixemidi.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.dixemidi.engine.KeyboardGeometry
import com.dixemidi.engine.VelocityEngine
import com.dixemidi.midi.ButtonLedState
import com.dixemidi.midi.MidiMessageBuilder
import com.dixemidi.midi.MidiStateController
import kotlin.math.abs
import kotlin.math.cos

/**
 * Custom low-latency Android View implementing the 10% / 90% landscape DIXE MIDI
 * interface with 60/120Hz Apple-grade spring physics: instantaneous key strike with
 * spring-damped rebound, tactile button scale compression, sinusoidal white/blue LED
 * breathing, 1:1 strip tracking with spring snap-back, and zero idle frame overhead.
 */
class DixeControllerView(
    context: Context,
    private val controller: MidiStateController
) : View(context) {

    private enum class PointerTarget {
        SHIFT, SUSTAIN, OCTAVE_DOWN, OCTAVE_UP, PITCH_STRIP, MOD_STRIP, KEYBOARD
    }

    private val pointerTargets = HashMap<Int, PointerTarget>(16)
    private val geometry = KeyboardGeometry()

    private val shiftRect = RectF()
    private val sustainRect = RectF()
    private val octDownRect = RectF()
    private val octUpRect = RectF()
    private val shiftHitRect = RectF()
    private val sustainHitRect = RectF()
    private val octDownHitRect = RectF()
    private val octUpHitRect = RectF()
    private val pitchStripRect = RectF()
    private val modStripRect = RectF()
    private val pitchStripHitRect = RectF()
    private val modStripHitRect = RectF()
    private val controlPanelRect = RectF()
    private val keyboardPanelRect = RectF()
    private val tempRect = RectF()

    init {
        keepScreenOn = true
    }

    // Physics state (delta-time driven for 60Hz/120Hz displays)
    private var lastFrameMs = 0L
    private val keyPos = FloatArray(25)
    private val keyVel = FloatArray(25)
    private val btnPos = FloatArray(4) // 0=shift, 1=hold, 2=octDown, 3=octUp
    private val btnVel = FloatArray(4)
    private val btnWhiteGlow = FloatArray(4)
    private val btnBlueGlow = FloatArray(4)

    private var holdFingerPressed = false
    private var pitchDragging = false
    private var modDragging = false

    private var visualPitchNorm = 0.5f
    private var visualPitchVel = 0f
    private var pitchGlow = 0f
    private var modGlow = 0f

    private var lastObservedBaseNote = controller.baseMidiNote
    private var labelAnimStartMs = 0L

    private val chassisPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyboardBedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x389A6675
        strokeWidth = 2f
    }

    private val buttonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonWhiteLedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonBlueLedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonDynamicLedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x47A87281
    }
    private val buttonLedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val buttonLedHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4F343C.toInt()
        textAlign = Paint.Align.CENTER
    }

    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x479A6675
    }
    private val stripLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val whiteKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whiteKeyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whiteKeyOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whiteKeyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x3D96727C
    }
    private val whiteKeyShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val blackKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackKeyOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackKeyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xD90F0F12.toInt()
    }
    private val blackKeyShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var cornerRadiusButton = 18f
    private var cornerRadiusWhite = 18f
    private var cornerRadiusBlack = 14f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val panelWidth = w * 0.10f
        controlPanelRect.set(0f, 0f, panelWidth, h.toFloat())
        keyboardPanelRect.set(panelWidth, 0f, w.toFloat(), h.toFloat())

        val panelPad = panelWidth * 0.075f
        val innerGap = panelWidth * 0.055f
        val btnSize = (panelWidth - panelPad * 2f - innerGap) / 2f

        val row1Top = panelPad
        val row2Top = row1Top + btnSize + innerGap
        val col1Left = panelPad
        val col2Left = col1Left + btnSize + innerGap

        shiftRect.set(col1Left, row1Top, col1Left + btnSize, row1Top + btnSize)
        sustainRect.set(col2Left, row1Top, col2Left + btnSize, row1Top + btnSize)
        octDownRect.set(col1Left, row2Top, col1Left + btnSize, row2Top + btnSize)
        octUpRect.set(col2Left, row2Top, col2Left + btnSize, row2Top + btnSize)

        val midX = (shiftRect.right + sustainRect.left) * 0.5f
        val midY = (shiftRect.bottom + octDownRect.top) * 0.5f
        val row2BottomHit = row2Top + btnSize + panelPad * 0.45f
        shiftHitRect.set(0f, 0f, midX, midY)
        sustainHitRect.set(midX, 0f, panelWidth, midY)
        octDownHitRect.set(0f, midY, midX, row2BottomHit)
        octUpHitRect.set(midX, midY, panelWidth, row2BottomHit)

        val stripsTop = row2Top + btnSize + panelPad
        val stripsBottom = h.toFloat() - panelPad
        pitchStripRect.set(col1Left, stripsTop, col1Left + btnSize, stripsBottom)
        modStripRect.set(col2Left, stripsTop, col2Left + btnSize, stripsBottom)
        pitchStripHitRect.set(0f, row2BottomHit, midX, h.toFloat())
        modStripHitRect.set(midX, row2BottomHit, panelWidth, h.toFloat())

        val kbPad = w * 0.0065f
        geometry.layout(
            panelWidth + kbPad,
            kbPad,
            w.toFloat() - kbPad,
            h.toFloat() - kbPad
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }

        cornerRadiusButton = btnSize * 0.2f
        cornerRadiusWhite = w * 0.0065f
        cornerRadiusBlack = w * 0.005f
        symbolPaint.textSize = btnSize * 0.44f
        keyLabelPaint.textSize = (h * 0.036f).coerceIn(22f, 44f)

        chassisPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0xFFF8DCE4.toInt(), 0xFFF2CDD7.toInt(),
            Shader.TileMode.CLAMP
        )
        keyboardBedPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0xFFF3CFD9.toInt(), 0xFFEAC2CE.toInt(),
            Shader.TileMode.CLAMP
        )
        buttonFillPaint.shader = LinearGradient(
            0f, row1Top, 0f, row1Top + btnSize,
            0xFFFCEAF0.toInt(), 0xFFF5D7DF.toInt(),
            Shader.TileMode.CLAMP
        )
        buttonPressedPaint.shader = LinearGradient(
            0f, row1Top, 0f, row1Top + btnSize,
            0xFFE8BECA.toInt(), 0xFFECC6D2.toInt(),
            Shader.TileMode.CLAMP
        )
        buttonWhiteLedPaint.shader = LinearGradient(
            0f, row1Top, 0f, row1Top + btnSize,
            0xFFFFFFFF.toInt(), 0xFFF5F2F4.toInt(),
            Shader.TileMode.CLAMP
        )
        buttonBlueLedPaint.shader = LinearGradient(
            0f, row1Top, 0f, row1Top + btnSize,
            0xFFDCEAFE.toInt(), 0xFFBFD9FD.toInt(),
            Shader.TileMode.CLAMP
        )
        stripPaint.shader = LinearGradient(
            0f, stripsTop, 0f, stripsBottom,
            0xFFE2BAC6.toInt(), 0xFFDCAFBC.toInt(),
            Shader.TileMode.CLAMP
        )
        whiteKeyPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0xFFFDFBF7.toInt(), 0xFFF6F1E9.toInt(),
            Shader.TileMode.CLAMP
        )
        whiteKeyPressedPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0xFFEFEBE3.toInt(), 0xFFE8E1D6.toInt(),
            Shader.TileMode.CLAMP
        )
        blackKeyPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.585f,
            0xFF2D2D32.toInt(), 0xFF1F1F23.toInt(),
            Shader.TileMode.CLAMP
        )
    }

    private fun stepSpring(
        pos: Float,
        vel: Float,
        target: Float,
        stiffness: Float,
        damping: Float,
        dt: Float
    ): Pair<Float, Float> {
        val accel = (target - pos) * stiffness - vel * damping
        val nextVel = vel + accel * dt
        val nextPos = pos + nextVel * dt
        return if (abs(target - nextPos) < 0.0015f && abs(nextVel) < 0.008f) {
            target to 0f
        } else {
            nextPos to nextVel
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val nowMs = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) {
            0.016f
        } else {
            ((nowMs - lastFrameMs).coerceIn(1L, 32L)) / 1000f
        }
        lastFrameMs = nowMs

        if (controller.baseMidiNote != lastObservedBaseNote) {
            lastObservedBaseNote = controller.baseMidiNote
            labelAnimStartMs = nowMs
        }

        var animating = controller.needsLedAnimation()

        // Step button spring animations
        val btnTargets = booleanArrayOf(
            controller.isShiftActive,
            holdFingerPressed || controller.sustainEnabled || controller.chordModeEnabled || controller.creatingChord,
            controller.isOctaveDownActive,
            controller.isOctaveUpActive
        )
        for (i in 0 until 4) {
            val target = if (btnTargets[i]) 1f else 0f
            val (np, nv) = stepSpring(btnPos[i], btnVel[i], target, 460f, 28f, dt)
            btnPos[i] = np
            btnVel[i] = nv
            if (np != target || nv != 0f) animating = true
        }

        // Step key physics (instant attack, spring-cushioned rebound)
        for (i in 0 until 25) {
            val pressed = controller.isKeyPressed(i)
            if (pressed) {
                // Fast strike
                val next = keyPos[i] + (1f - keyPos[i]) * (1f - kotlin.math.exp(-52f * dt))
                keyPos[i] = if (next > 0.995f) 1f else next
                keyVel[i] = 0f
                if (keyPos[i] != 1f) animating = true
            } else if (keyPos[i] != 0f || keyVel[i] != 0f) {
                val (np, nv) = stepSpring(keyPos[i], keyVel[i], 0f, 400f, 25f, dt)
                keyPos[i] = np
                keyVel[i] = nv
                if (np != 0f || nv != 0f) animating = true
            }
        }

        // Step touch strip visual physics
        val targetPitchNorm =
            controller.pitchBendValue.toFloat() / MidiMessageBuilder.PITCH_BEND_MAX.toFloat()
        if (pitchDragging) {
            visualPitchNorm = targetPitchNorm
            visualPitchVel = 0f
        } else if (visualPitchNorm != targetPitchNorm || visualPitchVel != 0f) {
            val (np, nv) = stepSpring(visualPitchNorm, visualPitchVel, targetPitchNorm, 520f, 27f, dt)
            visualPitchNorm = np
            visualPitchVel = nv
            if (np != targetPitchNorm || nv != 0f) animating = true
        }

        val targetPitchGlow = if (pitchDragging) 1f else 0f
        if (pitchGlow != targetPitchGlow) {
            pitchGlow += (targetPitchGlow - pitchGlow) * (1f - kotlin.math.exp(-22f * dt))
            if (abs(pitchGlow - targetPitchGlow) < 0.01f) pitchGlow = targetPitchGlow
            if (pitchGlow != targetPitchGlow) animating = true
        }

        val targetModGlow = if (modDragging) 1f else 0f
        if (modGlow != targetModGlow) {
            modGlow += (targetModGlow - modGlow) * (1f - kotlin.math.exp(-22f * dt))
            if (abs(modGlow - targetModGlow) < 0.01f) modGlow = targetModGlow
            if (modGlow != targetModGlow) animating = true
        }

        // 10% Left Chassis + 90% Right Keyboard Bed
        canvas.drawRect(controlPanelRect, chassisPaint)
        canvas.drawRect(keyboardPanelRect, keyboardBedPaint)
        canvas.drawLine(
            controlPanelRect.right, 0f,
            controlPanelRect.right, height.toFloat(),
            dividerPaint
        )

        val ledStates = arrayOf(
            ButtonLedState.NONE,
            controller.getHoldLedState(),
            controller.getOctDownLedState(),
            controller.getOctUpLedState()
        )
        val ledDecay = 1f - kotlin.math.exp(-13f * dt)
        for (i in 0 until 4) {
            val (targetWhite, targetBlue) = when (ledStates[i]) {
                ButtonLedState.NONE -> 0f to 0f
                ButtonLedState.WHITE -> 1f to 0f
                ButtonLedState.BLUE -> 0f to 1f
                ButtonLedState.BLINK_WHITE_BLUE -> {
                    val phase = (nowMs % 1350L) / 1350.0
                    val blueWave = ((1.0 - cos(phase * 2.0 * Math.PI)) * 0.5).toFloat()
                    (1f - blueWave) to blueWave
                }
                ButtonLedState.PULSE_WHITE_BLUE -> {
                    val phase = (nowMs % 1550L) / 1550.0
                    val blueWave = ((1.0 - cos(phase * 2.0 * Math.PI)) * 0.5).toFloat()
                    (1f - blueWave) to blueWave
                }
            }
            if (btnWhiteGlow[i] != targetWhite) {
                btnWhiteGlow[i] += (targetWhite - btnWhiteGlow[i]) * ledDecay
                if (abs(targetWhite - btnWhiteGlow[i]) < 0.004f) {
                    btnWhiteGlow[i] = targetWhite
                } else {
                    animating = true
                }
            }
            if (btnBlueGlow[i] != targetBlue) {
                btnBlueGlow[i] += (targetBlue - btnBlueGlow[i]) * ledDecay
                if (abs(targetBlue - btnBlueGlow[i]) < 0.004f) {
                    btnBlueGlow[i] = targetBlue
                } else {
                    animating = true
                }
            }
        }

        // 2x2 Control Buttons with Apple spring depression & smooth LED glow/crossfade
        drawControlButton(canvas, shiftRect, "⌘", btnPos[0], btnWhiteGlow[0], btnBlueGlow[0])
        drawControlButton(canvas, sustainRect, "∞", btnPos[1], btnWhiteGlow[1], btnBlueGlow[1])
        drawControlButton(canvas, octDownRect, "−", btnPos[2], btnWhiteGlow[2], btnBlueGlow[2])
        drawControlButton(canvas, octUpRect, "+", btnPos[3], btnWhiteGlow[3], btnBlueGlow[3])

        // Pitch Bend Strip (left) & Modulation Strip (right)
        drawTouchStrip(canvas, pitchStripRect, visualPitchNorm, pitchGlow)
        val modNorm = controller.modulationValue.toFloat() / 127f
        drawTouchStrip(canvas, modStripRect, modNorm, modGlow)

        // Label settle animation progress
        val labelElapsed = if (labelAnimStartMs > 0L) (nowMs - labelAnimStartMs) else 999L
        val labelProgress = (labelElapsed / 190f).coerceIn(0f, 1f)
        if (labelProgress < 1f) animating = true

        // White Keys
        for (wk in geometry.whiteKeys) {
            val depth = keyPos[wk.keyIndex]
            val clampedDepth = depth.coerceIn(-0.08f, 1.05f)
            val offsetY = clampedDepth * 5f
            val insetX = wk.rect.width() * 0.006f * clampedDepth.coerceAtLeast(0f)

            tempRect.set(
                wk.rect.left + insetX,
                wk.rect.top + offsetY,
                wk.rect.right - insetX,
                wk.rect.bottom + offsetY * 0.35f
            )

            val shadowAlpha = (0x28 * (1f - clampedDepth * 0.65f)).toInt().coerceIn(0, 0x30)
            if (shadowAlpha > 0) {
                whiteKeyShadowPaint.color = Color.argb(shadowAlpha, 0x4C, 0x2E, 0x37)
                canvas.drawRoundRect(
                    tempRect.left, tempRect.top + 4f,
                    tempRect.right, tempRect.bottom + 5f * (1f - clampedDepth * 0.6f),
                    cornerRadiusWhite, cornerRadiusWhite, whiteKeyShadowPaint
                )
            }

            canvas.drawRoundRect(tempRect, cornerRadiusWhite, cornerRadiusWhite, whiteKeyPaint)
            if (clampedDepth > 0.01f) {
                whiteKeyOverlayPaint.color =
                    Color.argb((clampedDepth.coerceIn(0f, 1f) * 185f).toInt(), 0xEA, 0xE2, 0xD6)
                canvas.drawRoundRect(
                    tempRect, cornerRadiusWhite, cornerRadiusWhite, whiteKeyOverlayPaint
                )
            }
            canvas.drawRoundRect(
                tempRect, cornerRadiusWhite, cornerRadiusWhite, whiteKeyBorderPaint
            )

            if (wk.keyIndex == 0) {
                val label = KeyboardGeometry.formatKeyLabel(controller.baseMidiNote)
                val settleOffset = (1f - labelProgress) * 5f
                val alpha = (0x59 + (0x36 * labelProgress).toInt()).coerceIn(0, 0x8F)
                keyLabelPaint.color = Color.argb(alpha, 0x4F, 0x34, 0x3C)
                val labelY = tempRect.bottom - tempRect.height() * 0.06f + settleOffset
                canvas.drawText(label, tempRect.centerX(), labelY, keyLabelPaint)
            }
        }

        // Black Keys
        for (bk in geometry.blackKeys) {
            val depth = keyPos[bk.keyIndex]
            val clampedDepth = depth.coerceIn(-0.08f, 1.05f)
            val offsetY = clampedDepth * 4.5f
            val insetX = bk.rect.width() * 0.01f * clampedDepth.coerceAtLeast(0f)

            tempRect.set(
                bk.rect.left + insetX,
                bk.rect.top + offsetY,
                bk.rect.right - insetX,
                bk.rect.bottom + offsetY * 0.35f
            )

            val shadowAlpha = (0x5E * (1f - clampedDepth * 0.6f)).toInt().coerceIn(0, 0x68)
            if (shadowAlpha > 0) {
                blackKeyShadowPaint.color = Color.argb(shadowAlpha, 0x20, 0x12, 0x18)
                canvas.drawRoundRect(
                    tempRect.left, tempRect.top + 6f,
                    tempRect.right, tempRect.bottom + 8f * (1f - clampedDepth * 0.65f),
                    cornerRadiusBlack, cornerRadiusBlack, blackKeyShadowPaint
                )
            }

            canvas.drawRoundRect(tempRect, cornerRadiusBlack, cornerRadiusBlack, blackKeyPaint)
            if (clampedDepth > 0.01f) {
                blackKeyOverlayPaint.color =
                    Color.argb((clampedDepth.coerceIn(0f, 1f) * 140f).toInt(), 0x14, 0x14, 0x17)
                canvas.drawRoundRect(
                    tempRect, cornerRadiusBlack, cornerRadiusBlack, blackKeyOverlayPaint
                )
            }
            canvas.drawRoundRect(
                tempRect, cornerRadiusBlack, cornerRadiusBlack, blackKeyBorderPaint
            )
        }

        if (animating) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawControlButton(
        canvas: Canvas,
        rect: RectF,
        symbol: String,
        pressAmount: Float,
        whiteGlow: Float,
        blueGlow: Float
    ) {
        val clampedPress = pressAmount.coerceIn(-0.06f, 1.06f)
        val scale = 1f - 0.065f * clampedPress
        val offsetY = 2.2f * clampedPress

        val cx = rect.centerX()
        val cy = rect.centerY() + offsetY
        val halfW = (rect.width() * 0.5f) * scale
        val halfH = (rect.height() * 0.5f) * scale
        tempRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)

        val shadowAlpha = (0x24 * (1f - clampedPress * 0.7f)).toInt().coerceIn(0, 0x28)
        if (shadowAlpha > 0) {
            buttonShadowPaint.color = Color.argb(shadowAlpha, 0x70, 0x40, 0x4E)
            canvas.drawRoundRect(
                tempRect.left, tempRect.top + 3f,
                tempRect.right, tempRect.bottom + 4f * (1f - clampedPress * 0.6f),
                cornerRadiusButton, cornerRadiusButton, buttonShadowPaint
            )
        }

        val totalGlow = (whiteGlow + blueGlow).coerceIn(0f, 1f)

        // Base unlit button + pressed tint
        canvas.drawRoundRect(tempRect, cornerRadiusButton, cornerRadiusButton, buttonFillPaint)
        if (clampedPress > 0.01f && totalGlow < 0.99f) {
            buttonDynamicLedPaint.color = Color.argb(
                (clampedPress.coerceIn(0f, 1f) * (1f - totalGlow) * 190f).toInt(),
                0xE8, 0xBE, 0xCA
            )
            canvas.drawRoundRect(tempRect, cornerRadiusButton, cornerRadiusButton, buttonDynamicLedPaint)
        }
        canvas.drawRoundRect(tempRect, cornerRadiusButton, cornerRadiusButton, buttonBorderPaint)

        // Smooth multi-stage luminous halo & interpolated LED fill
        if (totalGlow > 0.005f) {
            val blueRatio = (blueGlow / (whiteGlow + blueGlow).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
            val haloColor = blendArgb(0x88FFFFFF.toInt(), 0x80528EF6.toInt(), blueRatio)
            val fillColor = blendArgb(0xFFFAF7F8.toInt(), 0xFFC8E0FE.toInt(), blueRatio)
            val borderColor = blendArgb(0xF5FFFFFF.toInt(), 0xB84F8CF5.toInt(), blueRatio)

            val outerHaloAlpha = (Color.alpha(haloColor) * totalGlow * 0.42f).toInt().coerceIn(0, 255)
            buttonLedHaloPaint.color = Color.argb(
                outerHaloAlpha,
                Color.red(haloColor), Color.green(haloColor), Color.blue(haloColor)
            )
            canvas.drawRoundRect(
                tempRect.left - 5f, tempRect.top - 5f,
                tempRect.right + 5f, tempRect.bottom + 5f,
                cornerRadiusButton + 5f, cornerRadiusButton + 5f, buttonLedHaloPaint
            )

            val innerHaloAlpha = (Color.alpha(haloColor) * totalGlow * 0.85f).toInt().coerceIn(0, 255)
            buttonLedHaloPaint.color = Color.argb(
                innerHaloAlpha,
                Color.red(haloColor), Color.green(haloColor), Color.blue(haloColor)
            )
            canvas.drawRoundRect(
                tempRect.left - 2f, tempRect.top - 2f,
                tempRect.right + 2f, tempRect.bottom + 2f,
                cornerRadiusButton + 2f, cornerRadiusButton + 2f, buttonLedHaloPaint
            )

            buttonDynamicLedPaint.color = Color.argb(
                (255f * totalGlow).toInt().coerceIn(0, 255),
                Color.red(fillColor), Color.green(fillColor), Color.blue(fillColor)
            )
            canvas.drawRoundRect(tempRect, cornerRadiusButton, cornerRadiusButton, buttonDynamicLedPaint)

            buttonLedBorderPaint.color = Color.argb(
                (Color.alpha(borderColor) * totalGlow).toInt().coerceIn(0, 255),
                Color.red(borderColor), Color.green(borderColor), Color.blue(borderColor)
            )
            canvas.drawRoundRect(tempRect, cornerRadiusButton, cornerRadiusButton, buttonLedBorderPaint)
        }

        val fm = symbolPaint.fontMetrics
        val textY = tempRect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(symbol, tempRect.centerX(), textY, symbolPaint)
    }

    private fun blendArgb(colorA: Int, colorB: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        val a = (Color.alpha(colorA) + (Color.alpha(colorB) - Color.alpha(colorA)) * clamped).toInt()
        val r = (Color.red(colorA) + (Color.red(colorB) - Color.red(colorA)) * clamped).toInt()
        val g = (Color.green(colorA) + (Color.green(colorB) - Color.green(colorA)) * clamped).toInt()
        val b = (Color.blue(colorA) + (Color.blue(colorB) - Color.blue(colorA)) * clamped).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun drawTouchStrip(
        canvas: Canvas,
        rect: RectF,
        normalizedFromBottom: Float,
        glowAmount: Float
    ) {
        canvas.drawRoundRect(rect, cornerRadiusButton, cornerRadiusButton, stripPaint)
        canvas.drawRoundRect(rect, cornerRadiusButton, cornerRadiusButton, stripBorderPaint)

        val clamped = normalizedFromBottom.coerceIn(0f, 1f)
        val lineY = rect.bottom - clamped * rect.height()
        val inset = rect.width() * 0.12f
        val baseHalfThickness = (height * 0.003f).coerceAtLeast(2.5f)
        val halfThickness = baseHalfThickness * (1f + 0.35f * glowAmount)

        val topY = (lineY - halfThickness).coerceIn(rect.top + 2f, rect.bottom - halfThickness * 2f)
        val botY = (lineY + halfThickness).coerceIn(rect.top + halfThickness * 2f, rect.bottom - 2f)

        if (glowAmount > 0.02f) {
            stripGlowPaint.color = Color.argb((glowAmount * 110f).toInt(), 255, 255, 255)
            canvas.drawRoundRect(
                rect.left + inset - 2f,
                (topY - 3f).coerceAtLeast(rect.top + 1f),
                rect.right - inset + 2f,
                (botY + 3f).coerceAtMost(rect.bottom - 1f),
                halfThickness + 3f,
                halfThickness + 3f,
                stripGlowPaint
            )
        }

        stripLinePaint.color = Color.argb((240 + (15 * glowAmount).toInt()).coerceAtMost(255), 255, 255, 255)
        canvas.drawRoundRect(
            rect.left + inset,
            topY,
            rect.right - inset,
            botY,
            halfThickness,
            halfThickness,
            stripLinePaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val pointerId = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                handlePointerDown(pointerId, x, y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val count = event.pointerCount
                for (i in 0 until count) {
                    val pointerId = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    handlePointerMove(pointerId, x, y)
                }
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val pointerId = event.getPointerId(idx)
                handlePointerUp(pointerId)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pointerTargets.clear()
                holdFingerPressed = false
                pitchDragging = false
                modDragging = false
                controller.releaseActiveTouches()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handlePointerDown(pointerId: Int, x: Float, y: Float) {
        when {
            shiftHitRect.contains(x, y) -> {
                pointerTargets[pointerId] = PointerTarget.SHIFT
                controller.setShiftPointer(pointerId, true)
            }
            sustainHitRect.contains(x, y) -> {
                pointerTargets[pointerId] = PointerTarget.SUSTAIN
                holdFingerPressed = true
                controller.handleHoldPointerDown(pointerId)
                invalidate()
            }
            octDownHitRect.contains(x, y) -> {
                pointerTargets[pointerId] = PointerTarget.OCTAVE_DOWN
                controller.setTransposePointer(pointerId, -1, true)
            }
            octUpHitRect.contains(x, y) -> {
                pointerTargets[pointerId] = PointerTarget.OCTAVE_UP
                controller.setTransposePointer(pointerId, 1, true)
            }
            pitchStripHitRect.contains(x, y) -> {
                pointerTargets[pointerId] = PointerTarget.PITCH_STRIP
                pitchDragging = true
                controller.startPitchBend(pointerId, normFromBottom(y, pitchStripRect))
                invalidate()
            }
            modStripHitRect.contains(x, y) -> {
                pointerTargets[pointerId] = PointerTarget.MOD_STRIP
                modDragging = true
                controller.startModulation(pointerId, normFromBottom(y, modStripRect))
                invalidate()
            }
            else -> {
                val hit = geometry.hitTest(x, y)
                if (hit != null) {
                    pointerTargets[pointerId] = PointerTarget.KEYBOARD
                    val velocity = VelocityEngine.calculate(y, hit.keyTop, hit.keyHeight)
                    controller.onKeyPointerDown(
                        pointerId,
                        hit.keyIndex,
                        hit.semitoneOffset,
                        velocity
                    )
                }
            }
        }
    }

    private fun handlePointerMove(pointerId: Int, x: Float, y: Float) {
        when (pointerTargets[pointerId]) {
            PointerTarget.KEYBOARD -> {
                val hit = geometry.hitTest(x, y)
                if (hit == null) {
                    controller.onKeyPointerMove(pointerId, null, null, null)
                } else {
                    val velocity = VelocityEngine.calculate(y, hit.keyTop, hit.keyHeight)
                    controller.onKeyPointerMove(
                        pointerId,
                        hit.keyIndex,
                        hit.semitoneOffset,
                        velocity
                    )
                }
            }
            PointerTarget.PITCH_STRIP -> {
                controller.movePitchBend(pointerId, normFromBottom(y, pitchStripRect))
            }
            PointerTarget.MOD_STRIP -> {
                controller.moveModulation(pointerId, normFromBottom(y, modStripRect))
            }
            else -> Unit
        }
    }

    private fun handlePointerUp(pointerId: Int) {
        when (pointerTargets.remove(pointerId)) {
            PointerTarget.SHIFT -> {
                controller.setShiftPointer(pointerId, false)
            }
            null -> Unit
            PointerTarget.SUSTAIN -> {
                holdFingerPressed = false
                controller.handleHoldPointerUp(pointerId, false)
                invalidate()
            }
            PointerTarget.OCTAVE_DOWN -> {
                controller.setTransposePointer(pointerId, -1, false)
            }
            PointerTarget.OCTAVE_UP -> {
                controller.setTransposePointer(pointerId, 1, false)
            }
            PointerTarget.PITCH_STRIP -> {
                pitchDragging = false
                controller.endPitchBend(pointerId)
                invalidate()
            }
            PointerTarget.MOD_STRIP -> {
                modDragging = false
                controller.endModulation(pointerId)
                invalidate()
            }
            PointerTarget.KEYBOARD -> controller.onKeyPointerUp(pointerId)
        }
    }

    private fun normFromBottom(y: Float, rect: RectF): Float {
        if (rect.height() <= 0f) return 0.5f
        return (1f - (y - rect.top) / rect.height()).coerceIn(0f, 1f)
    }
}
