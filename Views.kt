package com.example.fm1bridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
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


// Vertical fader like on a mixer: a thin line and a blue handle. Value 0..1.
class FaderView(context: Context) : View(context) {

    var value = 0.8f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var onChange: ((Float) -> Unit)? = null

    private val line = Paint()
    private val knob = Paint()
    private val density = context.resources.displayMetrics.density

    init {
        line.color = Color.parseColor("#4A4A4A")
        line.strokeWidth = 3f * density
        knob.color = Color.parseColor("#4DA3FF")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 14f * density
        val cx = w / 2f
        canvas.drawLine(cx, pad, cx, h - pad, line)
        val y = pad + (1f - value) * (h - 2f * pad)
        val kw = minOf(w * 0.8f, 22f * density)
        canvas.drawRect(cx - kw / 2f, y - 7f * density, cx + kw / 2f, y + 7f * density, knob)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val h = height.toFloat()
        val pad = 14f * density
        if (h <= 2f * pad) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                value = 1f - (ev.y - pad) / (h - 2f * pad)
                onChange?.invoke(value)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}

// Vertical stereo level meter from -60 dB to 0 dB, green to red
class VMeterView(context: Context) : View(context) {

    var level = 0f
        set(v) {
            if (abs(field - v) > 0.003f) {
                field = v
                invalidate()
            }
        }

    private val paint = Paint()
    private val segments = 26

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val db = if (level <= 0.001f) -60f else 20f * log10(level)
        val frac = ((db + 60f) / 60f).coerceIn(0f, 1f)
        val lit = (frac * segments).toInt()
        val gap = h * 0.008f
        val sh = (h - gap * (segments - 1)) / segments
        val mid = w * 0.1f
        val colW = (w - mid) / 2f
        for (i in 0 until segments) {
            val on = i < lit
            val pos = i.toFloat() / segments
            paint.color = when {
                !on -> Color.parseColor("#1E201F")
                pos < 0.55f -> Color.parseColor("#4CD137")
                pos < 0.75f -> Color.parseColor("#FBC531")
                pos < 0.9f -> Color.parseColor("#F5821F")
                else -> Color.parseColor("#E84118")
            }
            val y = h - (i + 1) * sh - i * gap
            canvas.drawRect(0f, y, colW, y + sh, paint)
            canvas.drawRect(colW + mid, y, w, y + sh, paint)
        }
    }
}
