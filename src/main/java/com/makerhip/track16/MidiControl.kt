package com.makerhip.track16

import android.content.SharedPreferences
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** What MIDI does to the mixer: MIDI LEARN (any controller), the M-VAVE SMC-Mixer's DAW mode layout, the values
 * sent back (LEDs, motor faders, the SMC-Mixer's fader pick-up), MIDI THRU and the MIDI clock out.
 *
 * Targets: "fader:N", "pan:N", "mute:N", "solo:N", "arm:N" (N = 0..15), "master", "play", "rec", "rtz".
 * Keys (what the controller sends): "cc:CH:NUM", "nt:CH:NUM" (note on), "pb:CH" (pitch bend). */
class MidiControl(private val a: MainActivity, private val prefs: SharedPreferences) {
    val hub = MidiHub(a, a.ui, ::onMsg) { a.midiChanged() }
    var learn = false
    var pending: String? = null                     // LEARN: the control tapped, waiting for the controller
    var clockOn = prefs.getBoolean("midiClock", false)
    var thruOn = prefs.getBoolean("midiThru", false)
    val map = LinkedHashMap<String, String>()       // key -> target
    private val lastIn = HashMap<String, Int>()     // key -> the value it last sent (a CC button's press edge)
    private val lastOut = HashMap<String, Int>()    // key -> the value last sent back (or received: no echo)

    // the SMC-Mixer
    var bank = 0                                    // 0: tracks 1..8, 1: tracks 9..16
    private val picked = BooleanArray(9)            // fader n (8 = MASTER) has picked the value up
    private val lastPos = FloatArray(9) { -1f }      // its last report
    private val smcLed = HashMap<Int, IntArray>()   // conn id -> each note's LED as sent (-1 unknown)
    private val smcPos = HashMap<Int, IntArray>()   // conn id -> each fader's position as sent (14 bit, -1 unknown)

    init {
        try {
            val o = JSONObject(prefs.getString("midiMap", "{}") ?: "{}")
            for (k in o.keys()) map[k] = o.getString(k)
        } catch (_: Exception) {}
    }

    private fun saveMap() {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        prefs.edit().putString("midiMap", o.toString()).apply()
    }

    fun saveFlags() = prefs.edit().putBoolean("midiClock", clockOn).putBoolean("midiThru", thruOn).apply()

    // ---------------- LEARN ----------------

    /** A control in the mixer was touched. In LEARN it becomes the one waiting (true: the touch did that only). */
    fun learnTap(target: String): Boolean {
        if (!learn) return false
        pending = target
        a.midiChanged()
        return true
    }

    fun forget(target: String) {
        map.entries.removeAll { it.value == target }
        saveMap()
    }

    fun clearAll() {
        map.clear()
        lastOut.clear()
        saveMap()
    }

    /** the keys bound to a target, short: "CC7", "N36", "PB1" */
    fun label(target: String): String = map.filter { it.value == target }.keys.joinToString(" ") { short(it) }

    private fun short(key: String): String {
        val p = key.split(":")
        return when (p[0]) {
            "cc" -> "CC${p[2]}"
            "nt" -> "N${p[2]}"
            else -> "PB${p[1].toInt() + 1}"
        }
    }

    // ---------------- in ----------------

    private fun keyOf(m: Msg): Pair<String, Int>? = when (m.type) {
        0xB0 -> Pair("cc:${m.ch}:${m.d1}", m.d2)
        0x90 -> if (m.d2 > 0) Pair("nt:${m.ch}:${m.d1}", 127) else Pair("nt:${m.ch}:${m.d1}", 0)
        0x80 -> Pair("nt:${m.ch}:${m.d1}", 0)
        0xE0 -> Pair("pb:${m.ch}", m.d1 or (m.d2 shl 7))
        else -> null
    }

    private fun onMsg(c: Conn, m: Msg) {
        if (thruOn) thru(c, m)
        if (m.status >= 0xF0) return                  // (an incoming clock is passed on, not followed)
        val kv = keyOf(m) ?: return
        val (key, v) = kv
        if (learn) {
            val t = pending ?: return
            if (key.startsWith("nt") && v == 0) return    // (a note's release)
            map.entries.removeAll { it.value == t || it.key == key }
            map[key] = t
            saveMap()
            lastIn[key] = v
            lastOut.remove(key)
            pending = null
            a.midiChanged()
            return
        }
        if (c.smc && smcIn(c, m)) return
        val t = map[key] ?: return
        val before = lastIn[key] ?: 0
        lastIn[key] = v
        val cont = continuous(t)
        when {
            key.startsWith("pb") -> if (cont) setValue(t, v / 16383f)
            key.startsWith("nt") -> if (v > 0) press(t)
            cont -> setValue(t, v / 127f)
            v >= 64 && before < 64 -> press(t)        // a CC button: its press
        }
        lastOut[key] = outValue(key, t)               // (what it sent is where it is: nothing to echo)
        a.refreshAll()
    }

    private fun thru(c: Conn, m: Msg) {
        if (m.status >= 0xF0) {
            if (!clockOn) hub.send(m.status, skip = c)      // (with CLOCK on, ours only)
            return
        }
        if (m.type == 0xC0 || m.type == 0xD0) hub.send(m.status, m.d1, skip = c)
        else hub.send(m.status, m.d1, m.d2, skip = c)
    }

    private fun continuous(t: String) = t.startsWith("fader") || t.startsWith("pan") || t == "master" || t.startsWith("eq")

    private fun idx(t: String) = t.substringAfter(':').toInt()

    /** the value of a continuous target, 0..1 */
    private fun value(t: String): Float = when {
        t == "master" -> a.engine.masterGain / 1.2f
        t.startsWith("fader") -> a.project.tracks[idx(t)].gain / 1.2f
        t.startsWith("pan") -> (a.project.tracks[idx(t)].pan + 1f) / 2f
        t.startsWith("eqlow") -> (a.project.tracks[idx(t)].lowDb + 12f) / 24f
        t.startsWith("eqmid") -> (a.project.tracks[idx(t)].midDb + 12f) / 24f
        t.startsWith("eqhigh") -> (a.project.tracks[idx(t)].highDb + 12f) / 24f
        t.startsWith("eqfreq") -> (kotlin.math.ln(a.project.tracks[idx(t)].midHz / 200f) / kotlin.math.ln(25f))
        t.startsWith("eqcut") -> a.project.tracks[idx(t)].lowCut / 300f
        else -> 0f
    }

    private fun setValue(t: String, v01: Float) {
        val v = v01.coerceIn(0f, 1f)
        when {
            t == "master" -> a.setMaster(v * 1.2f)
            t.startsWith("fader") -> a.project.tracks[idx(t)].gain = v * 1.2f
            t.startsWith("pan") -> a.project.tracks[idx(t)].pan = if (abs(v - 0.5f) < 0.02f) 0f else v * 2f - 1f
            t.startsWith("eq") -> {
                val tr = a.project.tracks[idx(t)]
                val db = (v * 24f - 12f).let { if (abs(it) < 0.4f) 0f else (it * 2).roundToInt() / 2f }
                when {
                    t.startsWith("eqlow") -> tr.lowDb = db
                    t.startsWith("eqmid") -> tr.midDb = db
                    t.startsWith("eqhigh") -> tr.highDb = db
                    t.startsWith("eqfreq") -> tr.midHz = (200f * Math.pow(25.0, v.toDouble()).toFloat()).roundToInt().coerceIn(200, 5000)
                    t.startsWith("eqcut") -> tr.lowCut = if (v < 0.1f) 0 else (v * 300).roundToInt().coerceIn(40, 300)
                }
                tr.eqVer++
                a.eqChanged(idx(t))
            }
        }
    }

    private fun on(t: String): Boolean = when {
        t.startsWith("mute") -> a.project.tracks[idx(t)].mute
        t.startsWith("solo") -> a.project.tracks[idx(t)].solo
        t.startsWith("arm") -> a.project.tracks[idx(t)].armed
        t == "play" -> a.engine.playing && !a.engine.recording
        t == "rec" -> a.engine.recording
        else -> false
    }

    private fun press(t: String) {
        when {
            t.startsWith("mute") -> a.project.tracks[idx(t)].let { it.mute = !it.mute }
            t.startsWith("solo") -> a.project.tracks[idx(t)].let { it.solo = !it.solo }
            t.startsWith("arm") -> a.midiArm(idx(t))
            t == "play" -> a.midiPlay()
            t == "rec" -> a.midiRec()
            t == "stop" -> a.midiStop()
            t == "rtz" -> a.seek(0)
        }
    }

    // ---------------- the SMC-Mixer (DAW / Mackie Control mode) ----------------
    // faders 1..8 = pitch bend ch 1..8 (ch 9: a master fader, if the unit sends one); knobs = CC 16..23 ch 1,
    // relative (bit 6 = down); buttons = notes ch 1: REC 0x00, SOLO 0x08, MUTE 0x10, SELECT 0x18 (+ strip),
    // REW 0x5B, FF 0x5C, STOP 0x5D, PLAY 0x5E, REC 0x5F, BANK < > 0x2E / 0x2F, CHANNEL < > 0x30 / 0x31.
    // LEDs: the same notes, 127 / 0. Each fader's position goes back as pitch bend (the unit shows the pick-up).

    private fun smcIn(c: Conn, m: Msg): Boolean {
        when (m.type) {
            0xE0 -> {
                if (m.ch > 8) return false
                val v = (m.d1 or (m.d2 shl 7)) / 16383f
                val n = m.ch
                val t = if (n == 8) "master" else "fader:${bank * 8 + n}"
                val cur = value(t)
                if (!picked[n]) {
                    val crossed = lastPos[n] >= 0f && (lastPos[n] < cur) != (v < cur)
                    lastPos[n] = v
                    if (!crossed && abs(v - cur) > 0.03f) return true
                    picked[n] = true
                }
                lastPos[n] = v
                setValue(t, v)
                smcPos[c.id]?.set(n, (v * 16383).roundToInt())
            }
            0xB0 -> {
                if (m.ch != 0 || m.d1 !in 16..23) return false
                val clicks = if (m.d2 and 0x40 != 0) -(m.d2 and 0x3F) else (m.d2 and 0x3F)
                val tr = a.project.tracks[bank * 8 + m.d1 - 16]
                var p = (tr.pan + clicks * 0.02f).coerceIn(-1f, 1f)
                if (abs(p) < 0.01f) p = 0f
                tr.pan = p
            }
            0x90 -> {
                if (m.ch != 0) return false
                if (m.d2 == 0) return true
                val n = m.d1
                val strip = bank * 8 + (n and 7)
                when {
                    n < 0x08 -> a.midiArm(strip)
                    n < 0x10 -> a.project.tracks[strip].let { it.solo = !it.solo }
                    n < 0x18 -> a.project.tracks[strip].let { it.mute = !it.mute }
                    n < 0x20 -> a.selectTrack(strip)
                    n == 0x5B -> a.seek(maxOf(0, a.engine.pos - a.engine.beatSamples() * 4))
                    n == 0x5C -> a.seek(a.engine.pos + a.engine.beatSamples() * 4)
                    n == 0x5D -> a.midiStop()
                    n == 0x5E -> a.midiPlay()
                    n == 0x5F -> a.midiRec()
                    n == 0x2E -> switchBank(0)
                    n == 0x2F -> switchBank(1)
                    n == 0x30 -> a.selectTrack(maxOf(0, a.selected - 1)).also { switchBank(a.selected / 8) }
                    n == 0x31 -> a.selectTrack(minOf(Project.N - 1, a.selected + 1)).also { switchBank(a.selected / 8) }
                    else -> return false
                }
            }
            0x80 -> return m.ch == 0
            else -> return false
        }
        a.refreshAll()
        return true
    }

    private fun switchBank(b: Int) {
        if (b == bank) return
        bank = b
        for (i in 0 until 8) { picked[i] = false; lastPos[i] = -1f }
        for (p in smcPos.values) p.fill(-1)          // every position to send for the new strips
        for (l in smcLed.values) l.fill(-1)
        a.midiChanged()
    }

    // ---------------- out: every 40 ms from the UI's tick, only what changed ----------------

    private fun outValue(key: String, t: String): Int {
        val cont = continuous(t)
        return when {
            key.startsWith("pb") -> (value(t) * 16383).roundToInt()
            cont -> (value(t) * 127).roundToInt()
            else -> if (on(t)) 127 else 0
        }
    }

    fun feedback() {
        if (hub.conns.isEmpty()) return
        for ((key, t) in map) {
            val v = outValue(key, t)
            if (lastOut[key] == v) continue
            lastOut[key] = v
            val p = key.split(":")
            val ch = p[1].toInt()
            when (p[0]) {
                "cc" -> hub.send(0xB0 or ch, p[2].toInt(), v)
                "nt" -> hub.send(0x90 or ch, p[2].toInt(), v)
                "pb" -> hub.send(0xE0 or ch, v and 0x7F, v shr 7)
            }
        }
        for (c in hub.conns) if (c.smc) smcOut(c)
    }

    private fun smcOut(c: Conn) {
        val led = smcLed.getOrPut(c.id) { IntArray(128) { -1 } }
        val pos = smcPos.getOrPut(c.id) { IntArray(9) { -1 } }
        fun note(n: Int, lit: Boolean) {
            val v = if (lit) 127 else 0
            if (led[n] != v) { led[n] = v; hub.sendTo(c, 0x90, n, v) }
        }
        for (s in 0 until 8) {
            val tr = a.project.tracks[bank * 8 + s]
            note(0x00 + s, tr.armed)
            note(0x08 + s, tr.solo)
            note(0x10 + s, tr.mute)
            note(0x18 + s, a.selected == bank * 8 + s)
        }
        note(0x5E, a.engine.playing && !a.engine.recording)
        note(0x5D, false)
        note(0x5F, a.engine.recording)
        for (n in 0..8) {
            val t = if (n == 8) "master" else "fader:${bank * 8 + n}"
            val v = (value(t) * 16383).roundToInt()
            if (pos[n] < 0 || abs(pos[n] - v) > 8) {
                if (pos[n] >= 0) picked[n] = false        // (moved here, not by the unit's fader: pick it up again)
                pos[n] = v
                hub.sendTo(c, 0xE0 + n, v and 0x7F, v shr 7)
            }
        }
    }

    fun forgetConn(c: Conn) {
        smcLed.remove(c.id)
        smcPos.remove(c.id)
    }
}
