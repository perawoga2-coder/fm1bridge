package com.makerhip.track16

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One biquad (RBJ's cookbook), with two sets of state: [0] playback, [1] export */
class Biquad {
    var b0 = 1f; var b1 = 0f; var b2 = 0f; var a1 = 0f; var a2 = 0f
    private val x1 = FloatArray(2); private val x2 = FloatArray(2)
    private val y1 = FloatArray(2); private val y2 = FloatArray(2)

    fun set(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        this.b0 = (b0 / a0).toFloat(); this.b1 = (b1 / a0).toFloat(); this.b2 = (b2 / a0).toFloat()
        this.a1 = (a1 / a0).toFloat(); this.a2 = (a2 / a0).toFloat()
    }

    fun flat() = set(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    fun reset(s: Int) { x1[s] = 0f; x2[s] = 0f; y1[s] = 0f; y2[s] = 0f }

    fun run(buf: FloatArray, n: Int, s: Int) {
        var xa = x1[s]; var xb = x2[s]; var ya = y1[s]; var yb = y2[s]
        for (k in 0 until n) {
            val x = buf[k]
            var y = b0 * x + b1 * xa + b2 * xb - a1 * ya - a2 * yb
            if (y > -1e-15f && y < 1e-15f) y = 0f              // (no denormals)
            xb = xa; xa = x; yb = ya; ya = y
            buf[k] = y
        }
        x1[s] = xa; x2[s] = xb; y1[s] = ya; y2[s] = yb
    }

    /** |H| in dB at f (the EQ's picture) */
    fun db(f: Double, rate: Int): Double {
        val w = 2 * PI * f / rate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di) + 1e-20)
    }
}

/** A track's EQ: LOW-CUT (12 dB / octave), LOW shelf 100 Hz, MID bell (Q 0.9), HIGH shelf 8 kHz */
class TrackEq {
    val cut = Biquad(); val low = Biquad(); val mid = Biquad(); val high = Biquad()
    var ver = -1
    var active = false

    fun update(t: Track, rate: Int) {
        ver = t.eqVer
        if (t.lowCut > 0) highPass(cut, t.lowCut.toDouble(), rate) else cut.flat()
        shelf(low, 100.0, t.lowDb.toDouble(), rate, false)
        bell(mid, t.midHz.toDouble(), t.midDb.toDouble(), rate)
        shelf(high, 8000.0, t.highDb.toDouble(), rate, true)
        active = t.eqOn && (t.lowCut > 0 || t.lowDb != 0f || t.midDb != 0f || t.highDb != 0f)
    }

    fun run(buf: FloatArray, n: Int, s: Int) {
        cut.run(buf, n, s); low.run(buf, n, s); mid.run(buf, n, s); high.run(buf, n, s)
    }

    fun reset(s: Int) { cut.reset(s); low.reset(s); mid.reset(s); high.reset(s) }

    fun db(f: Double, rate: Int) = cut.db(f, rate) + low.db(f, rate) + mid.db(f, rate) + high.db(f, rate)

    companion object {
        fun highPass(b: Biquad, f: Double, rate: Int) {
            val w = 2 * PI * f / rate; val al = sin(w) / (2 * 0.707); val c = cos(w)
            b.set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
        }

        fun shelf(b: Biquad, f: Double, g: Double, rate: Int, hi: Boolean) {
            if (g == 0.0) { b.flat(); return }
            val a = 10.0.pow(g / 40); val w = 2 * PI * f / rate; val c = cos(w)
            val al = sin(w) / 2 * sqrt(2.0)             // (S = 1)
            val sa = 2 * sqrt(a) * al
            if (!hi) b.set(a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa)
            else b.set(a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa)
        }

        fun bell(b: Biquad, f: Double, g: Double, rate: Int) {
            if (g == 0.0) { b.flat(); return }
            val a = 10.0.pow(g / 40); val w = 2 * PI * f / rate; val al = sin(w) / (2 * 0.9); val c = cos(w)
            b.set(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
        }
    }
}
