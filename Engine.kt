package com.example.fm1bridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

const val ENGINE_RATE = 44100

val FX_NAMES = listOf(
    "Off", "Muffle", "Thin", "Dirty", "Lo-fi", "Echo", "Space", "Wobble", "Chop", "Backwards"
)
const val FX_REVERSE = 9

// ---------------------------------------------------------------
// One effect slot. Each track has two slots, each can hold any effect.
// ---------------------------------------------------------------

class Fx(private val rate: Int) {
    @Volatile var type = 0
    @Volatile var amount = 0.5f

    private var coef = 0f
    private var drive = 1f
    private var crushLevels = 65536f
    private var crushHold = 1
    private var delaySamples = 100

    private var lpState = 0f
    private var gateGain = 1f
    private var crushCount = 0
    private var crushValue = 0f

    private val delayBuf = FloatArray(rate * 2)
    private var delayPos = 0

    private val combLen = intArrayOf(1557, 1617, 1491, 1422)
    private val combBuf = Array(4) { FloatArray(combLen[it]) }
    private val combPos = IntArray(4)
    private val apLen = intArrayOf(225, 556)
    private val apBuf = Array(2) { FloatArray(apLen[it]) }
    private val apPos = IntArray(2)

    fun reset() {
        lpState = 0f
        gateGain = 1f
        crushCount = 0
        crushValue = 0f
        delayPos = 0
        delayBuf.fill(0f)
        for (b in combBuf) b.fill(0f)
        for (b in apBuf) b.fill(0f)
    }

    // Called once per audio block: computes the coefficients from the current settings
    fun prepare(bpm: Float) {
        val a = amount
        when (type) {
            1 -> {
                val fc = 12000f * 0.025.pow(a.toDouble()).toFloat()
                coef = 1f - exp(-2f * PI.toFloat() * fc / rate)
            }
            2 -> {
                val fc = 40f + 3000f * a * a
                coef = 1f - exp(-2f * PI.toFloat() * fc / rate)
            }
            3 -> drive = 1f + a * 24f
            4 -> {
                crushLevels = 2.0.pow(16.0 - a * 12.0).toFloat()
                crushHold = 1 + (a * 14f).toInt()
            }
            5 -> {
                val secs = 60f / bpm * 0.75f
                delaySamples = (secs * rate).toInt().coerceIn(100, delayBuf.size - 1)
            }
        }
    }

    fun process(x: Float, beatPos: Double): Float {
        val a = amount
        return when (type) {
            1 -> {
                lpState += coef * (x - lpState)
                lpState
            }
            2 -> {
                lpState += coef * (x - lpState)
                x - lpState
            }
            3 -> {
                val z = x * drive
                (z / (1f + abs(z))) * 1.2f
            }
            4 -> {
                if (crushCount <= 0) {
                    val q = crushLevels / 2f
                    crushValue = Math.round(x * q) / q
                    crushCount = crushHold
                }
                crushCount--
                crushValue
            }
            5 -> {
                var rp = delayPos - delaySamples
                if (rp < 0) rp += delayBuf.size
                val d = delayBuf[rp]
                delayBuf[delayPos] = x + d * (0.35f + 0.3f * a)
                delayPos++
                if (delayPos >= delayBuf.size) delayPos = 0
                x * (1f - 0.4f * a) + d * a
            }
            6 -> reverb(x, a)
            7 -> {
                val lfo = 0.5f - 0.5f * sin(beatPos * 4.0 * PI).toFloat()
                x * (1f - a * lfo)
            }
            8 -> {
                val ph = (beatPos * 4.0) % 1.0
                val duty = 1f - 0.8f * a
                val target = if (ph < duty) 1f else 0f
                gateGain += (target - gateGain) * 0.01f
                x * gateGain
            }
            else -> x
        }
    }

    private fun reverb(x: Float, a: Float): Float {
        var sum = 0f
        for (c in 0 until 4) {
            val b = combBuf[c]
            val p = combPos[c]
            val y = b[p]
            b[p] = x + y * 0.82f
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
        return x * (1f - 0.5f * a) + s * a * 0.8f
    }
}

// ---------------------------------------------------------------
// One track: a mono sound plus its mixer settings
// ---------------------------------------------------------------

class TrackState(rate: Int) {
    @Volatile var data: ShortArray? = null
    @Volatile var beats = 4
    @Volatile var fit = true
    @Volatile var volume = 0.8f
    @Volatile var pan = 0f
    @Volatile var mute = false
    @Volatile var solo = false
    @Volatile var peak = 0f
    @Volatile var loopPhase = 0f
    val fx = arrayOf(Fx(rate), Fx(rate))
}

// ---------------------------------------------------------------
// The mixer. All tracks share one tempo and one beat position, so
// they always play in time with each other.
// ---------------------------------------------------------------

class MixerEngine(private val rate: Int, private val tracks: Array<TrackState>) {
    @Volatile var bpm = 96f
    @Volatile var masterVol = 0.8f
    @Volatile var playing = false
    @Volatile var masterPeak = 0f
    @Volatile var beatPos = 0.0
    @Volatile var startNs = 0L
    @Volatile private var gen = 0

    fun start() {
        if (playing) return
        beatPos = 0.0
        for (t in tracks) {
            for (f in t.fx) f.reset()
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
            for (f in t.fx) f.prepare(bpmNow)
        }
        val mv = masterVol
        val trackPeak = FloatArray(tracks.size)
        var mPeak = 0f
        val datas = Array(tracks.size) { tracks[it].data }

        for (n in 0 until frames) {
            var l = 0f
            var r = 0f
            for (ti in tracks.indices) {
                val t = tracks[ti]
                val d = datas[ti] ?: continue
                if (t.mute || (anySolo && !t.solo)) continue

                val beatsLen = t.beats
                val loopBeat = bp % beatsLen
                val rev = t.fx[0].type == FX_REVERSE || t.fx[1].type == FX_REVERSE
                val idx: Double
                if (t.fit) {
                    val frac = loopBeat / beatsLen
                    idx = (if (rev) 1.0 - frac else frac) * d.size
                } else {
                    val el = loopBeat * 60.0 / bpmNow * rate
                    idx = if (rev) (d.size - 1 - el) else el
                }
                val i0 = idx.toInt()
                var s = 0f
                if (idx >= 0.0 && i0 < d.size) {
                    val i1 = if (i0 + 1 < d.size) i0 + 1 else i0
                    val fr = (idx - i0).toFloat()
                    s = (d[i0] + (d[i1] - d[i0]) * fr) / 32768f
                    val edge = minOf(i0, d.size - 1 - i0)
                    if (edge < 64) s *= edge / 64f
                }

                var y = t.fx[0].process(s, bp)
                y = t.fx[1].process(y, bp)
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
            t.loopPhase = ((bp % t.beats) / t.beats).toFloat()
        }
        val md = masterPeak * 0.93f
        masterPeak = if (mPeak > md) mPeak else md
    }
}
