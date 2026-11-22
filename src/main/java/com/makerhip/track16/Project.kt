package com.makerhip.track16

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/** One track: its audio (16-bit mono PCM at the project's rate, track NN.pcm) and its mixer settings. */
class Track(val index: Int) {
    var name = "Track ${index + 1}"
    @Volatile var gain = 0.8f          // 0 .. 1.2 (the fader)
    @Volatile var pan = 0f             // -1 .. 1
    @Volatile var mute = false
    @Volatile var solo = false
    @Volatile var armed = false
    @Volatile var data: ShortBuffer? = null      // the file mapped (read-only): playback reads it
    @Volatile var length = 0                     // samples
    @Volatile var peak = 0f                      // meter (playback writes, the UI reads and decays)
    var peaks: FloatArray = FloatArray(0)        // the waveform: one peak per PEAK_STEP samples
    // the EQ (eqVer counts changes: playback recomputes its filters when it moves)
    @Volatile var eqOn = true
    @Volatile var lowCut = 0                     // Hz, 0 = off
    @Volatile var lowDb = 0f                     // -12 .. 12
    @Volatile var midDb = 0f
    @Volatile var midHz = 1000                   // 200 .. 5000
    @Volatile var highDb = 0f
    @Volatile var eqVer = 0
    val eq = TrackEq()
    val eqUsed get() = eqOn && (lowCut > 0 || lowDb != 0f || midDb != 0f || highDb != 0f)

    fun eqReset() { lowCut = 0; lowDb = 0f; midDb = 0f; midHz = 1000; highDb = 0f; eqOn = true; eqVer++ }

    companion object {
        const val PEAK_STEP = 512
    }
}

/** A project: a folder with project.json and the 16 tracks' PCM files. */
class Project(val dir: File) {
    var title = dir.name
    var bpm = 120
    var rate = 48000
    val tracks = Array(N) { Track(it) }

    fun pcm(i: Int) = File(dir, "track%02d.pcm".format(i + 1))

    fun save() {
        val o = JSONObject()
        o.put("title", title)
        o.put("bpm", bpm)
        o.put("rate", rate)
        val a = JSONArray()
        for (t in tracks) {
            val j = JSONObject()
            j.put("name", t.name)
            j.put("gain", t.gain.toDouble())
            j.put("pan", t.pan.toDouble())
            j.put("mute", t.mute)
            j.put("solo", t.solo)
            val e = JSONObject()
            e.put("on", t.eqOn); e.put("cut", t.lowCut); e.put("low", t.lowDb.toDouble()); e.put("mid", t.midDb.toDouble())
            e.put("midHz", t.midHz); e.put("high", t.highDb.toDouble())
            j.put("eq", e)
            a.put(j)
        }
        o.put("tracks", a)
        File(dir, "project.json").writeText(o.toString(1))
    }

    fun load() {
        val f = File(dir, "project.json")
        if (f.exists()) {
            val o = JSONObject(f.readText())
            title = o.optString("title", dir.name)
            bpm = o.optInt("bpm", 120).coerceIn(30, 300)
            rate = o.optInt("rate", 48000)
            val a = o.optJSONArray("tracks")
            if (a != null) for (i in 0 until minOf(N, a.length())) {
                val j = a.getJSONObject(i)
                val t = tracks[i]
                t.name = j.optString("name", t.name)
                t.gain = j.optDouble("gain", 0.8).toFloat()
                t.pan = j.optDouble("pan", 0.0).toFloat()
                t.mute = j.optBoolean("mute", false)
                t.solo = j.optBoolean("solo", false)
                j.optJSONObject("eq")?.let { e ->
                    t.eqOn = e.optBoolean("on", true)
                    t.lowCut = e.optInt("cut", 0).coerceIn(0, 300)
                    t.lowDb = e.optDouble("low", 0.0).toFloat().coerceIn(-12f, 12f)
                    t.midDb = e.optDouble("mid", 0.0).toFloat().coerceIn(-12f, 12f)
                    t.midHz = e.optInt("midHz", 1000).coerceIn(200, 5000)
                    t.highDb = e.optDouble("high", 0.0).toFloat().coerceIn(-12f, 12f)
                    t.eqVer++
                }
            }
        }
        for (i in 0 until N) map(i)
    }

    /** (Re)maps track i's file for playback and rebuilds its waveform. */
    fun map(i: Int) {
        val t = tracks[i]
        val f = pcm(i)
        if (!f.exists() || f.length() < 2) {
            t.data = null
            t.length = 0
            t.peaks = FloatArray(0)
            return
        }
        RandomAccessFile(f, "r").use { raf ->
            val bb = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            bb.order(ByteOrder.LITTLE_ENDIAN)
            val sb = bb.asShortBuffer()
            t.length = sb.limit()
            val n = (t.length + Track.PEAK_STEP - 1) / Track.PEAK_STEP
            val p = FloatArray(n)
            for (k in 0 until n) {
                var m = 0
                val e = minOf(t.length, (k + 1) * Track.PEAK_STEP)
                var s = k * Track.PEAK_STEP
                while (s < e) {
                    val v = sb.get(s).toInt()
                    val a = if (v < 0) -v else v
                    if (a > m) m = a
                    s += 4
                }
                p[k] = m / 32768f
            }
            t.peaks = p
            t.data = sb
        }
    }

    /** Writes samples into track i from sample `at` on (replacing what was there, the file grown as needed). */
    fun write(i: Int, at: Int, samples: ShortArray, count: Int) {
        if (count <= 0) return
        val start = maxOf(0, at)
        val skip = start - at                        // (a negative start: the first samples fall before 0)
        if (skip >= count) return
        tracks[i].data = null                        // (playback lets go of the old mapping first)
        RandomAccessFile(pcm(i), "rw").use { raf ->
            val bb = ByteBuffer.allocate((count - skip) * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (k in skip until count) bb.putShort(samples[k])
            bb.flip()
            raf.channel.write(bb, start.toLong() * 2)
        }
        map(i)
    }

    fun clear(i: Int) {
        tracks[i].data = null
        pcm(i).delete()
        map(i)
    }

    fun longest(): Int = tracks.maxOf { it.length }

    companion object {
        const val N = 16
    }
}
