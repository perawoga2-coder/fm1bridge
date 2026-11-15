package com.example.fm1bridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.log10

// 16 bars like a step sequencer. The bar heights follow the sound,
// the bright bar is the playhead that moves while the track plays.
class StepBarView(context: Context) : View(context) {

    var color = Color.WHITE
        set(v) {
            field = v
            invalidate()
        }

    var bars = FloatArray(16)
        set(v) {
            field = v
            invalidate()
        }

    var active = -1
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val paint = Paint()
    private val playheadColor = Color.parseColor("#F0EBDD")
    private val idleColor = Color.parseColor("#666B68")

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val n = 16
        val gap = w * 0.012f
        val bw = (w - gap * (n - 1)) / n
        for (i in 0 until n) {
            val v = if (i < bars.size) bars[i].coerceIn(0f, 1f) else 0f
            val bh = h * (0.3f + 0.7f * v)
            val x = i * (bw + gap)
            paint.color = when {
                i == active -> playheadColor
                v > 0.02f -> color
                else -> idleColor
            }
            canvas.drawRect(x, (h - bh) / 2f, x + bw, (h + bh) / 2f, paint)
        }
    }
}

// Horizontal level meter in segments: green, yellow, red (scale -48 dB to 0 dB)
class MeterView(context: Context) : View(context) {

    var level = 0f
        set(v) {
            if (abs(field - v) > 0.004f) {
                field = v
                invalidate()
            }
        }

    private val paint = Paint()
    private val segments = 14

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val db = if (level <= 0.004f) -48f else 20f * log10(level)
        val frac = ((db + 48f) / 48f).coerceIn(0f, 1f)
        val lit = (frac * segments).toInt()
        val gap = w * 0.015f
        val sw = (w - gap * (segments - 1)) / segments
        for (i in 0 until segments) {
            val on = i < lit
            val pos = i.toFloat() / segments
            paint.color = when {
                !on -> Color.parseColor("#2A2C2B")
                pos < 0.6f -> Color.parseColor("#4CD137")
                pos < 0.85f -> Color.parseColor("#FBC531")
                else -> Color.parseColor("#E84118")
            }
            val x = i * (sw + gap)
            canvas.drawRect(x, 0f, x + sw, h, paint)
        }
    }
}
