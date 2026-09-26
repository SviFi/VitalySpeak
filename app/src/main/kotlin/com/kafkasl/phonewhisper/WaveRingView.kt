package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Shape-shifting "blob" that replaces the round dot while recording.
 *
 *  - Front blob: red fill + orange border. Its outline is a travelling wave, so the whole
 *    shape (not just a ring) morphs with your voice.
 *  - Back blob: filled yellow, different wave count, counter-rotating — peeks out around
 *    the red one as a second shape.
 *
 * Loudness makes the shape grow and ripple; pitch (voice brightness) makes ripples taller.
 * [reaction] 0..1 comes from the "Voice reaction" slider: 0 = calm, 1 = wild.
 */
class WaveRingView(
    context: Context,
    private val baseRadiusPx: Float,
    var reaction: Float = 0.6f
) : View(context) {

    private val density = context.resources.displayMetrics.density

    private val frontFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xF2EF4444.toInt()          // recording red
    }
    private val frontStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0xFFFF7A1A.toInt()          // orange border
        strokeJoin = Paint.Join.ROUND
    }
    private val backFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xC8FFC928.toInt()          // warm yellow, slightly translucent
    }
    private val frontPath = Path()
    private val backPath = Path()

    private var active = false
    private var targetLevel = 0f
    private var targetPitch = 0f
    private var level = 0f
    private var pitch = 0f
    private var phase = 0f

    fun start() { active = true; visibility = VISIBLE; postInvalidateOnAnimation() }

    fun stop() {
        active = false
        targetLevel = 0f; targetPitch = 0f; level = 0f; pitch = 0f
        visibility = INVISIBLE
    }

    /** level and pitch in 0..1, from the recording thread (any thread is fine). */
    fun update(level: Float, pitch: Float) {
        targetLevel = level.coerceIn(0f, 1f)
        targetPitch = pitch.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        val r = reaction.coerceIn(0f, 1f)
        // Faster attack at high reaction so it feels snappier/wilder.
        level += (targetLevel - level) * (0.22f + 0.2f * r)
        pitch += (targetPitch - pitch) * 0.15f
        phase += 0.08f + level * (0.2f + 0.35f * r)

        val cx = width / 2f
        val cy = height / 2f
        val room = min(cx, cy) - frontStroke.strokeWidth

        val growth = 0.08f + 0.32f * r              // how much the blob swells with loudness
        val maxAmp = baseRadiusPx * (0.12f + 0.48f * r)
        val base = baseRadiusPx * (1f + growth * level)
        val amp = level * (0.3f + 0.7f * pitch) * maxAmp
        // Never draw outside the overlay window.
        val scale = if (base + amp * 1.15f > room) room / (base + amp * 1.15f) else 1f

        buildBlob(backPath, cx, cy, (base + amp * 0.35f) * scale, amp * 1.15f * scale, waves = 5, phase = -phase * 0.9f)
        buildBlob(frontPath, cx, cy, base * scale, amp * scale, waves = 7, phase = phase)

        canvas.drawPath(backPath, backFill)
        canvas.drawPath(frontPath, frontFill)
        canvas.drawPath(frontPath, frontStroke)

        if (active) postInvalidateOnAnimation()
    }

    private fun buildBlob(path: Path, cx: Float, cy: Float, r: Float, amp: Float, waves: Int, phase: Float) {
        path.reset()
        val steps = 144
        for (i in 0..steps) {
            val t = (i.toFloat() / steps) * 2f * PI.toFloat()
            // Two harmonics make it organic rather than gear-like.
            val rr = r + amp * (0.72f * sin(waves * t + phase) + 0.28f * sin((waves + 3) * t - phase * 1.7f))
            val x = cx + rr * cos(t)
            val y = cy + rr * sin(t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }
}
