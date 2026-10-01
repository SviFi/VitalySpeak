package com.svifi.vitalyspeak

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
 *  - Front blob: solid red, no border. Its outline is a slow travelling wave, so the whole
 *    shape morphs with your voice.
 *  - Middle blob: soft white, different wave count, counter-rotating.
 *  - Back blob: near-black, a third wave pattern — three layered, shape-shifting outlines.
 *
 * Motion is deliberately calm: the waves rotate at a constant slow speed and only their
 * height follows the voice (fast attack, slow release), so it swells and settles smoothly
 * instead of jittering.
 *
 * Loudness makes the shape grow and ripple; pitch (voice brightness) makes ripples taller.
 * [reaction] 0..1 comes from the "Voice reaction" slider: 0 = calm, 1 = wild.
 */
class WaveRingView(
    context: Context,
    private val baseRadiusPx: Float,
    var reaction: Float = 0.6f
) : View(context) {

    /** Rotation speed multiplier (1 = the calm base speed; settings allow 1–5×, default 2×). */
    var speed: Float = 2f
    /** Wave-count multiplier on the base 9/7/5 waves (settings allow 1–3×, default 2×). */
    var waveMult: Float = 2f

    private val density = context.resources.displayMetrics.density

    private val frontFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xF2EF4444.toInt()          // recording red
    }
    private val backFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xE6F4F4F6.toInt()          // near-white, slightly translucent
    }
    private val darkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xF0141416.toInt()          // near-black, back layer
    }
    private val frontPath = Path()
    private val darkPath = Path()
    private val backPath = Path()

    private var active = false
    private var targetLevel = 0f
    private var targetPitch = 0f
    private var level = 0f
    private var pitch = 0f
    private var phase = 0f

    fun setColors(front: Int, middle: Int, back: Int) {
        frontFill.color = front; backFill.color = middle; darkFill.color = back
        invalidate()
    }

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
        // Attack/release envelope: rises quickly with the voice, falls back gently.
        val k = if (targetLevel > level) 0.16f + 0.08f * r else 0.05f
        level += (targetLevel - level) * k
        pitch += (targetPitch - pitch) * 0.03f      // pitch only drifts: no jitter
        phase += 0.07f * speed                      // constant rotation; speed from settings

        val cx = width / 2f
        val cy = height / 2f
        val room = min(cx, cy) - 2f * density

        val growth = 0.08f + 0.32f * r              // how much the blob swells with loudness
        // Ripple height ~30% smaller than before: the shape stays close to the dot.
        val maxAmp = baseRadiusPx * (0.12f + 0.48f * r) * 0.7f
        val base = baseRadiusPx * (1f + growth * level)
        val amp = level * (0.3f + 0.7f * pitch) * maxAmp
        // Never draw outside the overlay window.
        val outer = base + amp * 0.7f + amp * 1.2f
        val scale = if (outer > room) room / outer else 1f

        // More, shorter waves (9/7/5 around the circle) for a finer, livelier outline.
        // Whole wave counts keep each outline closed; more waves = finer ripples.
        val w = waveMult.coerceIn(1f, 3f)
        buildBlob(darkPath, cx, cy, (base + amp * 0.7f) * scale, amp * 1.2f * scale, waves = Math.round(5 * w), phase = phase * 0.6f + 1.3f)
        buildBlob(backPath, cx, cy, (base + amp * 0.35f) * scale, amp * 1.1f * scale, waves = Math.round(7 * w), phase = -phase * 0.8f)
        buildBlob(frontPath, cx, cy, base * scale, amp * scale, waves = Math.round(9 * w), phase = phase)

        canvas.drawPath(darkPath, darkFill)
        canvas.drawPath(backPath, backFill)
        canvas.drawPath(frontPath, frontFill)

        if (active) postInvalidateOnAnimation()
    }

    private fun buildBlob(path: Path, cx: Float, cy: Float, r: Float, amp: Float, waves: Int, phase: Float) {
        path.reset()
        val steps = maxOf(144, waves * 12)   // enough points for smooth curves at high wave counts
        for (i in 0..steps) {
            val t = (i.toFloat() / steps) * 2f * PI.toFloat()
            // A soft second harmonic keeps it organic without looking busy.
            val rr = r + amp * (0.82f * sin(waves * t + phase) + 0.18f * sin((waves + 2) * t - phase * 1.3f))
            val x = cx + rr * cos(t)
            val y = cy + rr * sin(t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }
}
