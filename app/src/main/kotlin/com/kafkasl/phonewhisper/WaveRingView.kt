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
 * Animated "snake" ring drawn around the mic dot while recording.
 * The ring is a circle whose radius ripples as a travelling sine wave:
 *  - loudness sets how strongly it ripples,
 *  - pitch (brightness of the voice) sets the ripple height on top of that,
 * so higher-pitched speech gives taller waves. Two layered waves rotate in opposite
 * directions for a fluid look. Idle = nothing drawn.
 */
class WaveRingView(context: Context, private val baseRadiusPx: Float) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val maxAmpPx = 11f * density

    private val front = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0xFFFF7A1A.toInt()          // orange
        strokeJoin = Paint.Join.ROUND
    }
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x99FFB36B.toInt()          // softer orange behind
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

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
        // Smooth towards the latest audio values so the ring breathes instead of jittering.
        level += (targetLevel - level) * 0.25f
        pitch += (targetPitch - pitch) * 0.15f
        phase += 0.10f + level * 0.25f

        val cx = width / 2f
        val cy = height / 2f
        val room = min(cx, cy) - front.strokeWidth
        // The dot itself grows up to 28% with loudness; keep the ring just outside it.
        val base = baseRadiusPx * (1f + 0.28f * level) + 3f * density
        val amp = maxOf(0f, min(maxAmpPx, room - base)) * level * (0.35f + 0.65f * pitch)

        drawWave(canvas, cx, cy, base, amp * 0.7f, waves = 5, phase = -phase * 0.8f, paint = back)
        drawWave(canvas, cx, cy, base, amp, waves = 7, phase = phase, paint = front)

        if (active) postInvalidateOnAnimation()
    }

    private fun drawWave(c: Canvas, cx: Float, cy: Float, r: Float, amp: Float, waves: Int, phase: Float, paint: Paint) {
        path.reset()
        val steps = 120
        for (i in 0..steps) {
            val t = (i.toFloat() / steps) * 2f * PI.toFloat()
            // Two harmonics make it look organic rather than like a gear.
            val rr = r + amp * (0.75f * sin(waves * t + phase) + 0.25f * sin((waves + 3) * t - phase * 1.7f))
            val x = cx + rr * cos(t)
            val y = cy + rr * sin(t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, paint)
    }
}
