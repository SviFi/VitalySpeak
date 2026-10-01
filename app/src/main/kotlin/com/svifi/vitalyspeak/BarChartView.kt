package com.svifi.vitalyspeak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.util.Calendar

/** Minimal 14-day activity chart: rounded bars (minutes/day), today highlighted, weekday initials. */
class BarChartView(context: Context, private val barColor: Int, private val mutedColor: Int, private val labelColor: Int) : View(context) {

    var values: List<Double> = emptyList()
        set(v) { field = v; invalidate() }

    private val d = context.resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * d; color = labelColor; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (96 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        val n = values.size
        val labelH = 16 * d
        val chartH = height - labelH
        val slot = width.toFloat() / n
        val bw = slot * 0.56f
        val max = maxOf(1.0, values.max())
        val initials = arrayOf("S", "M", "T", "W", "T", "F", "S")
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(n - 1)) }
        for (i in 0 until n) {
            val v = values[i]
            val h = if (v <= 0) 3 * d else maxOf(4 * d, (v / max * (chartH - 4 * d)).toFloat())
            val x = slot * i + (slot - bw) / 2
            rect.set(x, chartH - h, x + bw, chartH)
            bar.color = if (v <= 0) mutedColor else barColor
            bar.alpha = if (i == n - 1 || v <= 0) 255 else 190
            c.drawRoundRect(rect, bw / 2, bw / 2, bar)
            c.drawText(initials[cal.get(Calendar.DAY_OF_WEEK) - 1], x + bw / 2, height - 3 * d, label)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
    }
}
