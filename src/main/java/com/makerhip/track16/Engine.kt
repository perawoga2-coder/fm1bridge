package com.makerhip.track16

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The audio: one playback thread mixes the 16 tracks (fader, pan, mute, solo) and the metronome into an AudioTrack;
 * while recording, a second thread reads the input (AudioRecord) into RAM. Stop writes the take into the armed track,
 * moved earlier by the latency (what was heard came out late, what was played came in late).
 */
class Engine(var project: Project) {
    @Volatile var playing = false
        private set
    @Volatile var recording = false
        private set
    @Volatile var pos = 0                         // the play head (samples)
    @Volatile var metronome = true
    @Volatile var countIn = true                  // REC: one bar of clicks first
    @Volatile var latencyMs = 120                 // (Settings: the recording offset)
    @Volatile var input: AudioDeviceInfo? = null  // the input to record (null: the default, the built-in mic)
    @Volatile var masterPeak = 0f
    @Volatile var inputPeak = 0f

    private var playThread: Thread? = null
    private var recThread: Thread? = null
    private var recBuf = ShortArray(0)
    private var recLen = 0
    private var recStartPos = 0                   // the play head when the input began
    private var punchPos = 0                      // where the take goes (after the count-in)
    private var recTrack = -1

    val rate get() = project.rate

    fun beatSamples(): Int = rate * 60 / project.bpm.coerceIn(30, 300)

    fun play() {
        if (playing) return
        playing = true
        val t = Thread({ playLoop() }, "play")
        playThread = t
        t.start()
    }

    /** REC: the armed track (the first armed one) records from the play head; a bar of clicks first (count-in). */
    @SuppressLint("MissingPermission")
    fun record(): Boolean {
        if (playing) return false
        recTrack = project.tracks.indexOfFirst { it.armed }
        if (recTrack < 0) return false
        punchPos = pos
        if (countIn && metronome) pos = maxOf(0, pos) - beatSamples() * 4
        recStartPos = pos
        recLen = 0
        recBuf = ShortArray(rate * 30)
        recording = true
        val t = Thread({ recLoop() }, "rec")
        recThread = t
        t.start()
        play()
        return true
    }

    /** Stop; a take is written into its track (on this thread: the caller runs it off the UI) and true returned. */
    fun stop(): Boolean {
        val wasRec = recording
        recording = false
        playing = false
        recThread?.join(1500)
        playThread?.join(1500)
        recThread = null
        playThread = null
        if (pos < 0) pos = 0
        if (!wasRec || recTrack < 0 || recLen <= 0) return false
        val lat = latencyMs * rate / 1000
        val skip = (punchPos - recStartPos + lat).coerceIn(0, recLen)   // (the count-in's bar is not written)
        val n = recLen - skip
        if (n > 0) {
            val take = recBuf.copyOfRange(skip, recLen)
            project.write(recTrack, punchPos, take, n)
        }
        recBuf = ShortArray(0)
        return n > 0
    }

    // ---------------- the threads ----------------

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min, 2048 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val n = 256
        val l = FloatArray(n)
        val r = FloatArray(n)
        val out = ShortArray(n * 2)
        track.play()
        try {
            while (playing) {
                mix(pos, n, l, r, true)
                var mp = 0f
                for (k in 0 until n) {
                    val a = soft(l[k]); val b = soft(r[k])
                    val m = maxOf(abs(a), abs(b))
                    if (m > mp) mp = m
                    out[2 * k] = (a * 32767f).toInt().toShort()
                    out[2 * k + 1] = (b * 32767f).toInt().toShort()
                }
                if (mp > masterPeak) masterPeak = mp
                track.write(out, 0, out.size)
                pos += n
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    private fun recLoop() {
        try {
            recLoopInner()
        } catch (e: Exception) {                  // (no permission, no input): no take, the transport runs on
            recording = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun recLoopInner() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096) * 2)
        } catch (e: Exception) {
            null
        }?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?: AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096) * 2)
        input?.let { rec.setPreferredDevice(it) }
        val buf = ShortArray(512)
        rec.startRecording()
        try {
            while (recording) {
                val got = rec.read(buf, 0, buf.size)
                if (got <= 0) continue
                if (recLen + got > recBuf.size) recBuf = recBuf.copyOf(recBuf.size + rate * 30)
                var ip = 0
                for (k in 0 until got) {
                    recBuf[recLen + k] = buf[k]
                    val a = abs(buf[k].toInt())
                    if (a > ip) ip = a
                }
                recLen += got
                val p = ip / 32768f
                if (p > inputPeak) inputPeak = p
            }
        } finally {
            rec.stop()
            rec.release()
        }
    }

    /** n samples of the mix from sample p into l / r (the meters too when live); the click while playing */
    fun mix(p: Int, n: Int, l: FloatArray, r: FloatArray, live: Boolean) {
        java.util.Arrays.fill(l, 0f)
        java.util.Arrays.fill(r, 0f)
        val tr = project.tracks
        val anySolo = tr.any { it.solo }
        for (t in tr) {
            val d = t.data ?: continue
            val audible = if (anySolo) t.solo && !t.mute else !t.mute
            if (!audible || (live && recording && t.index == recTrack)) continue
            val len = minOf(t.length, d.limit())
            if (p + n <= 0 || p >= len) continue
            val g = t.gain
            val gl = g * (if (t.pan > 0) 1f - t.pan else 1f)
            val gr = g * (if (t.pan < 0) 1f + t.pan else 1f)
            var pk = 0f
            for (k in 0 until n) {
                val q = p + k
                if (q < 0 || q >= len) continue
                val s = d.get(q) / 32768f
                l[k] += s * gl
                r[k] += s * gr
                val a = abs(s * g)
                if (a > pk) pk = a
            }
            if (live && pk > t.peak) t.peak = pk
        }
        if (live && metronome) click(p, n, l, r)
    }

    private fun click(p: Int, n: Int, l: FloatArray, r: FloatArray) {
        val beat = beatSamples()
        val len = rate / 30
        for (k in 0 until n) {
            val q = p + k
            val m = ((q % beat) + beat) % beat
            if (m >= len) continue
            val bar = Math.floorDiv(q, beat) % 4 == 0
            val f = if (bar) 1600.0 else 1000.0
            val v = (sin(2 * PI * f * m / rate) * exp(-m * 8.0 / len) * 0.35).toFloat()
            l[k] += v
            r[k] += v
        }
    }

    private fun soft(x: Float): Float = if (x > 1f) 1f else if (x < -1f) -1f else x

    // ---------------- export ----------------

    /** The whole song, as heard (fader, pan, mute, solo; no click), into a stereo 16-bit WAV */
    fun mixdown(out: OutputStream) {
        val total = project.longest()
        Wav.header(out, rate, 2, total)
        val n = 4096
        val l = FloatArray(n)
        val r = FloatArray(n)
        val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0
        while (p < total) {
            val m = minOf(n, total - p)
            mix(p, m, l, r, false)
            bb.clear()
            for (k in 0 until m) {
                bb.putShort((soft(l[k]) * 32767f).toInt().toShort())
                bb.putShort((soft(r[k]) * 32767f).toInt().toShort())
            }
            out.write(bb.array(), 0, m * 4)
            p += m
        }
    }

    /** One track's audio as a mono 16-bit WAV (a stem) */
    fun stem(i: Int, out: OutputStream) {
        val t = project.tracks[i]
        val d = t.data ?: return
        Wav.header(out, rate, 1, t.length)
        val bb = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0
        while (p < t.length) {
            val m = minOf(4096, t.length - p)
            bb.clear()
            for (k in 0 until m) bb.putShort(d.get(p + k))
            out.write(bb.array(), 0, m * 2)
            p += m
        }
    }
}
