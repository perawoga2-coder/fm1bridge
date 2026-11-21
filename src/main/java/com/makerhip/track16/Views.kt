package com.makerhip.track16

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

object C {
    val BG = Color.parseColor("#0B0B0F")
    val PANEL = Color.parseColor("#15151B")
    val PANEL2 = Color.parseColor("#22222B")
    val LINE = Color.parseColor("#2E2E3A")
    val TEXT = Color.parseColor("#F2F2F5")
    val DIM = Color.parseColor("#8F8F9C")
    val ORANGE = Color.parseColor("#FF7A2F")
    val RED = Color.parseColor("#FF4D5E")
    val GREEN = Color.parseColor("#34D399")
    val YELLOW = Color.parseColor("#F5C542")
    val BLUE = Color.parseColor("#4DA3FF")
    val PINK = Color.parseColor("#FF5CA8")
    /** the 16 tracks' colours: four families of four */
    val TRACK = IntArray(16) { arrayOf(ORANGE, BLUE, GREEN, PINK)[it / 4] }
}

/** The 16 tracks one under the other: names on the left, their waveforms over time, the play head. Tap a name: select
 * the track; tap the time ruler or a lane: move the play head; drag sideways: scroll; pinch buttons outside zoom. */
class TimelineView(ctx: Context, private val app: MainActivity) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headW = 92 * d
    private val rulerH = 26 * d
    var secondsPerScreen = 20f
    var scrollSamples = 0
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0
    private var dragged = false

    private fun laneH(): Float = (height - rulerH) / Project.N

    override fun onDraw(c: Canvas) {
        val pr = app.project
        val e = app.engine
        c.drawColor(C.BG)
        val w = width - headW
        val spp = pr.rate * secondsPerScreen / w           // samples a pixel
        val lh = laneH()
        // the ruler: seconds and bars
        p.color = C.PANEL
        c.drawRect(0f, 0f, width.toFloat(), rulerH, p)
        p.textSize = 11 * d
        p.typeface = Typeface.DEFAULT
        val beat = e.beatSamples()
        val bar = beat * 4
        var b = (scrollSamples / bar) * bar
        while (b < scrollSamples + spp * w) {
            val x = headW + (b - scrollSamples) / spp
            if (x >= headW) {
                p.color = C.LINE
                c.drawLine(x, rulerH, x, height.toFloat(), p)
                p.color = C.DIM
                c.drawText("${b / bar + 1}", x + 3 * d, rulerH - 8 * d, p)
            }
            b += bar
        }
        for (i in 0 until Project.N) {
            val t = pr.tracks[i]
            val y0 = rulerH + i * lh
            val sel = i == app.selected
            p.color = if (sel) C.PANEL2 else C.PANEL
            c.drawRect(0f, y0 + 1, headW - 2, y0 + lh - 1, p)
            p.color = C.TRACK[i]
            c.drawRect(0f, y0 + 1, 4 * d, y0 + lh - 1, p)
            p.textSize = minOf(13 * d, lh * 0.42f)
            p.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            p.color = if (t.mute) C.DIM else C.TEXT
            c.drawText("${i + 1}", 10 * d, y0 + lh * 0.62f, p)
            p.color = if (t.armed) C.RED else if (t.mute) C.DIM else if (t.solo) C.YELLOW else C.DIM
            val tag = when {
                t.armed -> "REC"
                t.solo -> "S"
                t.mute -> "M"
                else -> ""
            }
            c.drawText(tag, 34 * d, y0 + lh * 0.62f, p)
            // the waveform
            val pk = t.peaks
            if (pk.isNotEmpty()) {
                p.color = if (t.mute) C.LINE else C.TRACK[i]
                val mid = y0 + lh / 2
                val amp = lh * 0.45f
                var x = 0
                while (x < w) {
                    val s0 = scrollSamples + (x * spp).toInt()
                    val s1 = scrollSamples + ((x + 1) * spp).toInt()
                    var m = 0f
                    var k = s0 / Track.PEAK_STEP
                    val ke = minOf(pk.size - 1, s1 / Track.PEAK_STEP)
                    while (k <= ke) {
                        if (k >= 0 && pk[k] > m) m = pk[k]
                        k++
                    }
                    if (m > 0.002f) c.drawLine(headW + x, mid - m * amp, headW + x, mid + m * amp, p)
                    x += 1
                }
            }
            p.color = C.LINE
            c.drawLine(0f, y0 + lh, width.toFloat(), y0 + lh, p)
        }
        // the play head
        val hx = headW + (e.pos - scrollSamples) / spp
        if (hx >= headW && hx <= width) {
            p.color = if (e.recording) C.RED else C.TEXT
            p.strokeWidth = 2 * d
            c.drawLine(hx, 0f, hx, height.toFloat(), p)
            p.strokeWidth = 0f
        }
    }

    /** keeps the play head on screen while playing */
    fun follow() {
        val w = width - headW
        if (w <= 0) return
        val span = (app.project.rate * secondsPerScreen).toInt()
        val pos = app.engine.pos
        if (pos > scrollSamples + span * 0.9f || pos < scrollSamples) scrollSamples = maxOf(0, pos - span / 10)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val w = width - headW
        val spp = app.project.rate * secondsPerScreen / w
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downScroll = scrollSamples; dragged = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                if (kotlin.math.abs(dx) > 12 * d) dragged = true
                if (dragged && downX > headW) {
                    scrollSamples = maxOf(0, downScroll - (dx * spp).toInt())
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) {
                    if (ev.x < headW && ev.y > rulerH) {
                        app.selectTrack(((ev.y - rulerH) / laneH()).toInt().coerceIn(0, Project.N - 1))
                    } else if (ev.x >= headW) {
                        if (ev.y > rulerH) app.selectTrack(((ev.y - rulerH) / laneH()).toInt().coerceIn(0, Project.N - 1))
                        app.seek(maxOf(0, scrollSamples + ((ev.x - headW) * spp).toInt()))
                    }
                }
            }
        }
        return true
    }
}

/** A vertical fader with a meter beside it (0 .. 1.2; 1.0 = 0 dB) */
class FaderView(ctx: Context, private val color: Int, private val onChange: (Float) -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var value = 0.8f
        set(v) { field = v.coerceIn(0f, 1.2f); invalidate() }
    var meter = 0f

    override fun onDraw(c: Canvas) {
        val top = 10 * d
        val bot = height - 10 * d
        val cx = width * 0.42f
        p.color = C.PANEL2
        c.drawRoundRect(RectF(cx - 3 * d, top, cx + 3 * d, bot), 3 * d, 3 * d, p)
        val y = bot - (bot - top) * (value / 1.2f)
        p.color = color
        c.drawRoundRect(RectF(cx - 3 * d, y, cx + 3 * d, bot), 3 * d, 3 * d, p)
        p.color = C.TEXT
        c.drawRoundRect(RectF(cx - 16 * d, y - 7 * d, cx + 16 * d, y + 7 * d), 4 * d, 4 * d, p)
        p.color = C.BG
        c.drawRect(cx - 12 * d, y - 1 * d, cx + 12 * d, y + 1 * d, p)
        // the 0 dB mark
        val y0 = bot - (bot - top) / 1.2f
        p.color = C.DIM
        c.drawRect(cx + 18 * d, y0 - 0.5f * d, cx + 24 * d, y0 + 0.5f * d, p)
        // the meter
        val mx = width * 0.82f
        p.color = C.PANEL2
        c.drawRect(mx - 3 * d, top, mx + 3 * d, bot, p)
        val m = meter.coerceIn(0f, 1f)
        p.color = if (m > 0.95f) C.RED else if (m > 0.7f) C.YELLOW else C.GREEN
        c.drawRect(mx - 3 * d, bot - (bot - top) * m, mx + 3 * d, bot, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val top = 10 * d
        val bot = height - 10 * d
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            value = ((bot - ev.y) / (bot - top) * 1.2f)
            onChange(value)
        }
        return true
    }
}
