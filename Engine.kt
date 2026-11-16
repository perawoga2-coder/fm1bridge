package com.example.fm1bridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

const val ENGINE_RATE = 44100
const val FX_COUNT = 9

// The first six are the effects of the M-VAVE FM-1 (Filter, Reverb, Delay, Distortion,
// Chorus, Phaser). The last three are extras.
val FX_NAMES = listOf(
    "Filter", "Reverb", "Delay", "Distortion", "Chorus", "Phaser", "Lo-fi", "Wobble", "Chop"
)
val FX_PARAM1 = listOf("Cutoff", "Size", "Time", "Drive", "Rate", "Rate", "Bits", "Rate", "Rate")
val FX_PARAM2 = listOf(
    "Resonance", "Mix", "Feedback", "Mix", "Depth", "Depth", "Rate reduction", "Depth", "Gate length"
)

// ---------------------------------------------------------------
// One effect. Every track owns one of each, switched on or off,
// and a chain order that can be rearranged.
// ---------------------------------------------------------------

class FxUnit(private val rate: Int, private val kind: Int) {
    @Volatile var enabled = false
    @Volatile var p1 = 0.5f
    @Volatile var p2 = 0.5f
    @Volatile var mode = 0 // Filter only: 0 low-pass, 1 high-pass, 2 band-pass

    // coefficients, computed once per audio block
    private var fCoef = 0.1f
    private var damp = 1f
    private var drive = 1f
    private var levels = 65536f
    private var hold = 1
    private var delaySamples = 100
    private var fbk = 0.4f
    private var combFb = 0.82f
    private var lfoInc = 0.0

    // state
    private var low = 0f
    private var band = 0f
    private var crushCount = 0
    private var crushValue = 0f
    private var gateGain = 1f
    private var lfoPhase = 0.0
    private var phLast = 0f

    private val delayBuf = FloatArray(if (kind == 2) rate * 2 else 1)
    private var delayPos = 0
    private val chorusBuf = FloatArray(if (kind == 4) 4096 else 1)
    private var chorusPos = 0
    private val apState = FloatArray(8)

    private val combLen = intArrayOf(1557, 1617, 1491, 1422)
    private val combBuf = Array(4) { FloatArray(if (kind == 1) combLen[it] else 1) }
    private val combPos = IntArray(4)
    private val apLen = intArrayOf(225, 556)
    private val apBuf = Array(2) { FloatArray(if (kind == 1) apLen[it] else 1) }
    private val apPos = IntArray(2)

    fun reset() {
        low = 0f
        band = 0f
        crushCount = 0
        crushValue = 0f
        gateGain = 1f
        lfoPhase = 0.0
        phLast = 0f
        delayPos = 0
        chorusPos = 0
        delayBuf.fill(0f)
        chorusBuf.fill(0f)
        apState.fill(0f)
        for (b in combBuf) b.fill(0f)
        for (b in apBuf) b.fill(0f)
        combPos.fill(0)
        apPos.fill(0)
    }

    fun prepare(bpm: Float) {
        val a = p1
        val b = p2
        when (kind) {
            0 -> {
                val fc = 40f * 175f.pow(a)
                fCoef = 2f * sin(PI.toFloat() * fc / rate)
                damp = 2f - 1.85f * b
            }
            1 -> combFb = 0.70f + 0.22f * a
            2 -> {
                val beats = 0.0625f + a * 1.4375f
                delaySamples = (beats * 60f / bpm * rate).toInt().coerceIn(100, delayBuf.size - 1)
                fbk = 0.85f * b
            }
            3 -> drive = 1f + a * 30f
            4 -> lfoInc = (0.1 + 4.9 * a) / rate
            5 -> lfoInc = (0.05 + 3.95 * a) / rate
            6 -> {
                levels = 2.0.pow(16.0 - a * 12.0).toFloat()
                hold = 1 + (b * 14f).toInt()
            }
        }
    }

    fun process(x: Float, beatPos: Double): Float {
        return when (kind) {
            0 -> filter(x)
            1 -> reverb(x)
            2 -> delay(x)
            3 -> {
                val z = x * drive
                val y = (z / (1f + abs(z))) * 1.2f
                x * (1f - p2) + y * p2
            }
            4 -> chorus(x)
            5 -> phaser(x)
            6 -> lofi(x)
            7 -> {
                val lfo = 0.5f - 0.5f * sin(beatPos * (0.5 + 7.5 * p1) * 2.0 * PI).toFloat()
                x * (1f - p2 * lfo)
            }
            8 -> gate(x, beatPos)
            else -> x
        }
    }

    private fun filter(x: Float): Float {
        low += fCoef * band
        val high = x - low - damp * band
        band += fCoef * high
        return when (mode) {
            1 -> high
            2 -> band * 0.8f
            else -> low
        }
    }

    private fun reverb(x: Float): Float {
        var sum = 0f
        for (c in 0 until 4) {
            val b = combBuf[c]
            val p = combPos[c]
            val y = b[p]
            b[p] = x + y * combFb
            combPos[c] = if (p + 1 >= b.size) 0 else p + 1
            sum += y
        }
        var s = sum * 0.25f
        for (k in 0 until 2) {
            val b = apBuf[k]
            val p = apPos[k]
            val bo = b[p]
            val w = s + 0.5f * bo
            b[p] = w
            s = -0.5f * w + bo
            apPos[k] = if (p + 1 >= b.size) 0 else p + 1
        }
        return x * (1f - 0.5f * p2) + s * p2 * 0.8f
    }

    private fun delay(x: Float): Float {
        var rp = delayPos - delaySamples
        if (rp < 0) rp += delayBuf.size
        val d = delayBuf[rp]
        delayBuf[delayPos] = x + d * fbk
        delayPos++
        if (delayPos >= delayBuf.size) delayPos = 0
        return x * 0.85f + d * 0.6f
    }

    private fun chorus(x: Float): Float {
        chorusBuf[chorusPos] = x
        lfoPhase += lfoInc
        if (lfoPhase >= 1.0) lfoPhase -= 1.0
        val del = 0.015f * rate + p2 * 0.006f * rate * sin(2.0 * PI * lfoPhase).toFloat()
        var rp = chorusPos - del
        while (rp < 0f) rp += chorusBuf.size
        val i0 = rp.toInt() % chorusBuf.size
        val i1 = (i0 + 1) % chorusBuf.size
        val fr = rp - rp.toInt()
        val d = chorusBuf[i0] * (1f - fr) + chorusBuf[i1] * fr
        chorusPos++
        if (chorusPos >= chorusBuf.size) chorusPos = 0
        return x * 0.65f + d * 0.65f
    }

    private fun phaser(x: Float): Float {
        lfoPhase += lfoInc
        if (lfoPhase >= 1.0) lfoPhase -= 1.0
        val sweep = 0.5f - 0.5f * cos(2.0 * PI * lfoPhase).toFloat()
        val fc = 200f + sweep * (300f + p2 * 3500f)
        val tn = tan(PI.toFloat() * fc / rate)
        val a = (tn - 1f) / (tn + 1f)
        var s = x + 0.35f * phLast
        for (k in 0 until 4) {
            val x1 = apState[2 * k]
            val y1 = apState[2 * k + 1]
            val y = a * s + x1 - a * y1
            apState[2 * k] = s
            apState[2 * k + 1] = y
            s = y
        }
        phLast = s
        return x * 0.5f + s * 0.5f
    }

    private fun lofi(x: Float): Float {
        if (crushCount <= 0) {
            val q = levels / 2f
            crushValue = Math.round(x * q) / q
            crushCount = hold
        }
        crushCount--
        return crushValue
    }

    private fun gate(x: Float, beatPos: Double): Float {
        val cycles = 1.0 + (p1 * 7f).toInt()
        val ph = (beatPos * cycles) % 1.0
        val duty = 1f - 0.85f * p2
        val target = if (ph < duty) 1f else 0f
        gateGain += (target - gateGain) * 0.01f
        return x * gateGain
    }
}

// ---------------------------------------------------------------
// One track: a mono sound plus its loop and mixer settings
// ---------------------------------------------------------------

class TrackState(rate: Int) {
    @Volatile var data: ShortArray? = null
    @Volatile var beats = 4
    @Volatile var fit = true
    @Volatile var speed = 1.0f
    @Volatile var reverse = false
    @Volatile var regionStart = 0f
    @Volatile var regionEnd = 1f
    @Volatile var volume = 0.8f
    @Volatile var pan = 0f
    @Volatile var mute = false
    @Volatile var solo = false
    @Volatile var peak = 0f
    @Volatile var loopPhase = 0f
    @Volatile var order = IntArray(FX_COUNT) { it }
    val units = Array(FX_COUNT) { FxUnit(rate, it) }
}

// ---------------------------------------------------------------
// The mixer. All tracks run from one clock. Each track has its own
// speed, so it can also run faster or slower than the clock.
// ---------------------------------------------------------------

class MixerEngine(private val rate: Int, private val tracks: Array<TrackState>) {
    @Volatile var bpm = 96f
    @Volatile var masterVol = 0.8f
    @Volatile var playing = false
    @Volatile var masterPeak = 0f
    @Volatile var beatPos = 0.0
    @Volatile var startNs = 0L
    @Volatile private var gen = 0
    private val phases = DoubleArray(tracks.size)

    fun start() {
        if (playing) return
        beatPos = 0.0
        for (i in phases.indices) phases[i] = 0.0
        for (t in tracks) {
            for (u in t.units) u.reset()
        }
        gen++
        val g = gen
        playing = true
        startNs = System.nanoTime()
        val th = Thread { runLoop(g) }
        th.isDaemon = true
        th.start()
    }

    fun stop() {
        playing = false
    }

    private fun runLoop(g: Int) {
        val chunk = 512
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBuf, chunk * 4 * 4)
        val at = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            playing = false
            return
        }
        val out = ShortArray(chunk * 2)
        try {
            at.play()
            while (playing && g == gen) {
                render(out, chunk)
                at.write(out, 0, out.size)
            }
        } catch (e: Exception) {
        }
        try {
            at.stop()
        } catch (e: Exception) {
        }
        at.release()
    }

    private fun limit(v: Float): Float {
        val a = abs(v)
        if (a <= 0.8f) return v
        val o = 0.8f + 0.2f * (1f - exp(-(a - 0.8f) / 0.2f))
        return if (v < 0f) -o else o
    }

    private fun render(out: ShortArray, frames: Int) {
        val bpmNow = bpm
        val step = bpmNow / 60.0 / rate
        var bp = beatPos
        var anySolo = false
        for (t in tracks) {
            if (t.solo) anySolo = true
            for (u in t.units) {
                if (u.enabled) u.prepare(bpmNow)
            }
        }
        val mv = masterVol
        val trackPeak = FloatArray(tracks.size)
        var mPeak = 0f
        val datas = Array(tracks.size) { tracks[it].data }
        val orders = Array(tracks.size) { tracks[it].order }

        for (n in 0 until frames) {
            var l = 0f
            var r = 0f
            for (ti in tracks.indices) {
                val t = tracks[ti]
                val beatsLen = t.beats
                var ph = phases[ti] + step * t.speed
                if (ph >= beatsLen) ph %= beatsLen
                phases[ti] = ph

                val d = datas[ti] ?: continue
                if (t.mute || (anySolo && !t.solo)) continue

                val len = d.size
                val rs = (t.regionStart * len).toInt().coerceIn(0, len - 1)
                val re = (t.regionEnd * len).toInt().coerceIn(rs + 1, len)
                val regLen = re - rs
                var pos = -1.0
                if (t.fit) {
                    val frac = ph / beatsLen
                    val uu = if (t.reverse) 1.0 - frac else frac
                    pos = rs + uu * regLen
                } else {
                    val el = ph * 60.0 / bpmNow * rate
                    if (el < regLen) {
                        pos = if (t.reverse) rs + regLen - 1 - el else rs + el
                    }
                }
                var s = 0f
                if (pos >= 0.0) {
                    val i0 = pos.toInt()
                    if (i0 < len) {
                        val i1 = if (i0 + 1 < len) i0 + 1 else i0
                        val fr = (pos - i0).toFloat()
                        s = (d[i0] + (d[i1] - d[i0]) * fr) / 32768f
                        val edge = minOf(i0 - rs, re - 1 - i0)
                        if (edge < 64) s *= maxOf(edge, 0) / 64f
                    }
                }

                var y = s
                val ord = orders[ti]
                for (k in 0 until FX_COUNT) {
                    val unit = t.units[ord[k]]
                    if (unit.enabled) y = unit.process(y, bp)
                }
                y *= t.volume
                val ay = abs(y)
                if (ay > trackPeak[ti]) trackPeak[ti] = ay
                val p = t.pan
                val gl = if (p > 0f) 1f - p else 1f
                val gr = if (p < 0f) 1f + p else 1f
                l += y * gl
                r += y * gr
            }
            l = limit(l * mv)
            r = limit(r * mv)
            val al = abs(l)
            val ar = abs(r)
            if (al > mPeak) mPeak = al
            if (ar > mPeak) mPeak = ar
            out[2 * n] = (l * 32767f).toInt().toShort()
            out[2 * n + 1] = (r * 32767f).toInt().toShort()
            bp += step
        }

        beatPos = bp
        for (ti in tracks.indices) {
            val t = tracks[ti]
            val dec = t.peak * 0.93f
            t.peak = if (trackPeak[ti] > dec) trackPeak[ti] else dec
            t.loopPhase = (phases[ti] / t.beats).toFloat()
        }
        val md = masterPeak * 0.93f
        masterPeak = if (mPeak > md) mPeak else md
    }
}
