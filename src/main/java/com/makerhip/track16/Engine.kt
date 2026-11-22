package com.makerhip.track16

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * The audio.
 *
 * Playback: one thread mixes the 16 tracks (fader, pan, mute, solo), the input being monitored, the click, then the
 * MASTER fader and a limiter, into an AudioTrack (the device's own rate, low-latency mode, its burst size; the buffer
 * grows by a burst whenever the device reports an underrun).
 *
 * Recording: a second thread reads the input (AudioRecord: raw / unprocessed when the device has it, float, mono or
 * one channel of a stereo input, with an input gain) into RAM, and hands it to playback for monitoring (a ring).
 *
 * Alignment: both streams report when a frame left the speaker / came into the input (AudioTimestamp, one clock). From
 * pairs of them each recorded frame is placed at the song position that was being heard when it was played, so a take
 * lands in time by itself on any device and output (speaker, wired, USB, Bluetooth). When a device reports no
 * timestamps, the recording offset (Settings, or measured by Calibrate) is used. A fine offset is added either way.
 *
 * 0.10 CUE (headphones, like a DJ's): a track in CUE leaves the main mix and goes to a headphone mix (the CUE tracks +
 * CUE MIX x the main mix, its own level). Where the headphone mix goes: channels 3-4 of a USB output with four or more
 * (one 4-channel track), else a second AudioTrack on another, headphone-type output (written non-blocking: the main
 * never waits for it), or the right side of a split cable (main left, both mono). With no headphone path the CUE
 * tracks stay in the main. Export never looks at CUE.
 */
class Engine(var project: Project) {
    @Volatile var playing = false
        private set
    @Volatile var recording = false
        private set
    @Volatile var pos = 0                          // the play head (samples)
    @Volatile var metronome = true
    @Volatile var countIn = true                   // REC: one bar of clicks first

    // alignment
    @Volatile var autoAlign = true                 // from the timestamps (off: the manual offset)
    @Volatile var latencyMs = 120                  // the manual recording offset (no timestamps, or autoAlign off)
    @Volatile var fineMs = 0                       // added either way (Calibrate sets it)
    @Volatile var lastAlign = ""                   // how the last take was placed (Settings shows it)

    // input
    @Volatile var input: AudioDeviceInfo? = null   // null: the default (the built-in mic)
    @Volatile var inputChannel = 0                 // 0 mono mix, 1 left, 2 right (a stereo input)
    @Volatile var inputGainDb = 0f                 // -24 .. +24
    @Volatile var raw = true                       // UNPROCESSED when the device has it, else VOICE_RECOGNITION / MIC
    @Volatile var monitor = false                  // hear the input while recording (through the armed track's fader)
    @Volatile var inputPeak = 0f
    @Volatile var inputClip = false

    // output
    @Volatile var masterGain = 1f                  // 0 .. 1.2
    @Volatile var limiter = true                   // a peak limiter at -0.3 dBFS: the speakers never get a clipped mix
    @Volatile var peakL = 0f
    @Volatile var peakR = 0f
    @Volatile var outRoute: AudioDeviceInfo? = null   // where the sound goes now (the play thread reads it)
    @Volatile var underruns = 0
    @Volatile var bufferFrames = 0
    var burst = 192                                // the device's frames per buffer (MainActivity sets it)

    private var playThread: Thread? = null
    private var recThread: Thread? = null
    private var recBuf = ShortArray(0)
    @Volatile private var recLen = 0
    private var punchPos = 0                       // where the take goes (after the count-in)
    private var recTrack = -1
    private var recording4cal = false              // Calibrate: the take is kept, not written

    // timestamps: (track frame, ns) of the output, (input frame, ns) of the input; the song position of track frame 0
    private val outTs = ArrayList<LongArray>()
    private val inTs = ArrayList<LongArray>()
    @Volatile private var playStartPos = 0

    // monitoring: the input's latest samples, the record thread writes, the play thread reads
    private val ring = FloatArray(16384)
    @Volatile private var ringW = 0L
    private var ringR = 0L

    // the limiter
    private var limGain = 1f

    // 0.10 CUE: the headphone mix
    @Volatile var cueMix = 0.5f                    // how much of the main mix the headphones get (0 .. 1)
    @Volatile var phonesGain = 1f                  // the headphones' own level (0 .. 1.2)
    @Volatile var splitCable = false               // a Y split cable on the jack: left = main, right = headphones (mono)
    @Volatile var splitSwap = false                // (the other way round)
    @Volatile var audio: AudioManager? = null      // to find the outputs (MainActivity sets it)
    @Volatile var cueState = 0                     // 0 none, 1 USB 3-4, 2 a second output, 3 split cable, 4 no headphone output
    @Volatile var cueRoute: AudioDeviceInfo? = null   // the second output (state 2)
    @Volatile private var phones: AudioTrack? = null  // the second track (the cue thread opens / closes it)
    @Volatile private var cueFailed = false        // no distinct output: not tried again until no track is in CUE
    @Volatile private var fourCh = false           // the main track has 4 channels and plays on the USB output
    @Volatile private var mainRouteId = -1         // the main track's routed device (-1: not known yet)
    @Volatile private var blocks = 0L              // blocks played (the cue thread waits on it before a release)
    @Volatile private var playAlive = false
    private val cueLock = Any()
    private var cueRunning = false
    private var limGainH = 1f                      // the headphones' limiter

    val rate get() = project.rate

    fun beatSamples(): Int = rate * 60 / project.bpm.coerceIn(30, 300)

    fun play() {
        if (playing) return
        playing = true
        playAlive = true
        cueFailed = false
        val t = Thread({ try { playLoop() } finally { playAlive = false } }, "play")
        playThread = t
        t.start()
        cueChanged()
    }

    // ---------------- 0.10 CUE ----------------

    private fun anyCue(): Boolean {
        for (t in project.tracks) if (t.cue) return true
        return false
    }

    /** A CUE button moved (or PLAY): while playing with a track in CUE, a small thread looks after the second output. */
    fun cueChanged() {
        if (!playing || !anyCue()) return
        synchronized(cueLock) {
            if (cueRunning) return
            cueRunning = true
        }
        Thread({ cueLoop() }, "cue").start()
    }

    /**
     * Opens the second (headphone) track when a track is in CUE and there is no other headphone path, once; closes it
     * when no track is in CUE (after a recording, never during one). Never on the play thread: opening takes time.
     */
    private fun cueLoop() {
        var chosen = -1
        var openedAt = 0L
        var n = 0
        while (true) {
            val any = anyCue()
            if (!playing || (!any && phones == null)) {
                if (!playing) closePhones()
                val done = synchronized(cueLock) {
                    if (!playing || (!anyCue() && phones == null)) { cueRunning = false; true } else false
                }
                if (done) { if (!anyCue()) cueFailed = false; return }
                continue
            }
            if (!any) cueFailed = false                  // (the next first track in CUE: one more try)
            val cal = recording && recording4cal
            val want = any && !splitCable && !fourCh && !cal
            val ph = phones
            if (ph == null) {
                val mainId = mainRouteId
                if (want && !cueFailed && mainId != -1) {
                    val dev = phonesDevice(mainId)
                    val t = if (dev != null) openPhones(dev) else null
                    if (t != null && dev != null) {
                        chosen = dev.id
                        openedAt = System.nanoTime()
                        cueRoute = dev
                        phones = t
                    } else cueFailed = true
                }
            } else if ((!want || cueFailed) && !recording) {
                closePhones()
            } else if (want && !cueFailed && ++n % 10 == 0 && System.nanoTime() - openedAt > 300_000_000L) {
                // both on one device (the headphones unplugged, or the phone would not route it): give up
                val rd = try { ph.routedDevice } catch (e: Exception) { null }
                val mainId = mainRouteId
                if (rd != null && (rd.id != chosen || (mainId != -1 && rd.id == mainId))) cueFailed = true
            }
            try { Thread.sleep(50) } catch (e: InterruptedException) {}
        }
    }

    /** a headphone-type output that is not where the main track plays (null: none) */
    private fun phonesDevice(mainId: Int): AudioDeviceInfo? {
        val outs = try { audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (e: Exception) { null } ?: return null
        val order = intArrayOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        for (ty in order) for (d in outs) if (d.type == ty && d.id != mainId) return d
        return null
    }

    private fun openPhones(dev: AudioDeviceInfo): AudioTrack? {
        var t: AudioTrack? = null
        try {
            t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT), burst * 8 * 4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (t.state != AudioTrack.STATE_INITIALIZED || !t.setPreferredDevice(dev)) { t.release(); return null }
            t.play()
            return t
        } catch (e: Exception) {
            try { t?.release() } catch (e2: Exception) {}
            return null
        }
    }

    /** the play thread lets go of it first (two blocks on), then it is released */
    private fun closePhones() {
        val ph = phones ?: return
        phones = null
        cueRoute = null
        val b = blocks
        val t0 = System.nanoTime()
        while (playAlive && blocks - b < 2 && System.nanoTime() - t0 < 2_000_000_000L)
            try { Thread.sleep(5) } catch (e: InterruptedException) {}
        try { ph.pause(); ph.flush() } catch (e: Exception) {}
        try { ph.release() } catch (e: Exception) {}
    }

    /** a USB output with four channels or more (null: none) */
    private fun usb4(): AudioDeviceInfo? {
        val outs = try { audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (e: Exception) { null } ?: return null
        for (d in outs) if ((d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET) &&
            d.channelCounts.any { it >= 4 }) return d
        return null
    }

    /** the headphones' level and limiter */
    private fun phonesOut(l: FloatArray, r: FloatArray, n: Int) {
        val g = phonesGain
        val ceil = 0.966f
        val rel = exp(-1.0 / (0.12 * rate)).toFloat()
        val lim = limiter
        for (k in 0 until n) {
            var a = l[k] * g
            var b = r[k] * g
            if (lim) {
                val m = maxOf(abs(a), abs(b))
                val need = if (m * limGainH > ceil) ceil / m else 1f
                limGainH = if (need < limGainH) need else limGainH * rel + (1f - rel) * minOf(1f, need)
                a *= limGainH
                b *= limGainH
            }
            l[k] = a.coerceIn(-1f, 1f)
            r[k] = b.coerceIn(-1f, 1f)
        }
    }

    /** REC: the armed track records from the play head; a bar of clicks first (count-in). */
    fun record(): Boolean = startRec(cal = false)

    private fun startRec(cal: Boolean): Boolean {
        if (playing) return false
        recTrack = if (cal) -1 else project.tracks.indexOfFirst { it.armed }
        if (!cal && recTrack < 0) return false
        recording4cal = cal
        punchPos = pos
        if (cal || (countIn && metronome)) pos = maxOf(0, pos) - beatSamples() * 4
        recLen = 0
        recBuf = ShortArray(rate * 30)
        synchronized(inTs) { inTs.clear() }
        synchronized(outTs) { outTs.clear() }
        ringR = ringW
        recording = true
        val t = Thread({ recLoop() }, "rec")
        recThread = t
        t.start()
        play()
        return true
    }

    /** Stop; a take is written into its track (on this thread: the caller runs it off the UI); true if one was. */
    fun stop(): Boolean {
        val wasRec = recording
        recording = false
        playing = false
        recThread?.join(1500)
        playThread?.join(1500)
        recThread = null
        playThread = null
        if (pos < 0) pos = 0
        if (!wasRec || recording4cal || recTrack < 0 || recLen <= 0) return false
        val first = songPosOfFrame0()                  // the song position recorded frame 0 belongs to
        val skip = (punchPos - first).toInt().coerceIn(0, recLen)   // (the count-in's bar is not written)
        val n = recLen - skip
        if (n > 0) project.write(recTrack, punchPos, recBuf.copyOfRange(skip, recLen), n)
        recBuf = ShortArray(0)
        return n > 0
    }

    /**
     * The song position of recorded frame 0: from the timestamps (median over the pairs), else the manual offset. The
     * fine offset either way. lastAlign says which.
     */
    private fun songPosOfFrame0(): Long {
        val fine = fineMs.toLong() * rate / 1000
        val start = playStartPos.toLong()
        if (autoAlign) {
            val ks = ArrayList<Long>()
            val outs = synchronized(outTs) { ArrayList(outTs) }
            val ins = synchronized(inTs) { ArrayList(inTs) }
            for (i in ins) {
                var best: LongArray? = null
                for (o in outs) if (best == null || abs(o[1] - i[1]) < abs(best[1] - i[1])) best = o
                val o = best ?: continue
                if (abs(o[1] - i[1]) > 2_000_000_000L) continue
                val heard = start + o[0] + (i[1] - o[1]) * rate / 1_000_000_000L   // the song position heard at i's time
                ks.add(heard - i[0])
            }
            if (ks.size >= 3) {
                ks.sort()
                val k = ks[ks.size / 2]
                lastAlign = "Auto: placed from the device's own timing (${ks.size} readings)"
                return k + fine
            }
        }
        val manual = latencyMs.toLong() * rate / 1000
        lastAlign = if (autoAlign) "Manual: this device gave no timing, ${latencyMs} ms used" else "Manual: ${latencyMs} ms"
        return start - manual + fine
    }

    // ---------------- calibration ----------------

    /**
     * Calibrate: two bars of clicks played and recorded (the mic must hear the speaker, or a cable from the output into
     * the input). Each click found in the recording is placed as a take would be, and its distance to its beat is the
     * error left; the median of them, in ms, is returned (null: no clicks heard) for the fine offset.
     */
    fun calibrate(): Int? {
        if (playing) return null
        val wasClick = metronome
        val keepFine = fineMs
        fineMs = 0
        metronome = true
        pos = 0
        if (!startRec(cal = true)) { metronome = wasClick; fineMs = keepFine; return null }
        val until = beatSamples() * 8
        val t0 = System.currentTimeMillis()
        while (pos < until && System.currentTimeMillis() - t0 < 15000) Thread.sleep(20)
        recording = false
        playing = false
        recThread?.join(1500)
        playThread?.join(1500)
        recThread = null
        playThread = null
        metronome = wasClick
        pos = 0
        val n = recLen
        val first = songPosOfFrame0()
        fineMs = keepFine
        if (n < rate) return null
        var peak = 0
        for (k in 0 until n) peak = maxOf(peak, abs(recBuf[k].toInt()))
        if (peak < 1500) return null                     // (nothing loud enough: the mic did not hear the clicks)
        val thr = peak / 3
        val beat = beatSamples()
        val errs = ArrayList<Long>()
        var k = 0
        while (k < n) {
            if (abs(recBuf[k].toInt()) >= thr) {
                val sp = first + k
                val nearest = Math.round(sp.toDouble() / beat) * beat
                errs.add(nearest - sp)
                k += beat / 2                            // (one onset a click)
            } else k++
        }
        recBuf = ShortArray(0)
        if (errs.size < 3) return null
        errs.sort()
        return (errs[errs.size / 2] * 1000 / rate).toInt()
    }

    // ---------------- the threads ----------------

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // 0.10 CUE: a track in CUE and a USB output with 4 channels: one 4-channel track (1-2 main, 3-4 headphones)
        mainRouteId = -1
        fourCh = false
        limGainH = 1f
        var ch = 2
        var usbId = -1
        var track4: AudioTrack? = null
        val usb = if (anyCue() && !splitCable && !(recording && recording4cal)) usb4() else null
        if (usb != null) try {
            val t4 = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelIndexMask(0xF).build())
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT), burst * 8 * 4) * 2)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (t4.state == AudioTrack.STATE_INITIALIZED && t4.channelCount == 4) {
                track4 = t4
                ch = 4
                usbId = usb.id
            } else t4.release()
        } catch (e: Exception) {}                        // (no 4 channels after all: the stereo track, as ever)
        val track = track4 ?: AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT), burst * 8 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        // the smallest buffer that does not underrun: two bursts to start, a burst more at each underrun
        var want = burst * 2
        track.bufferSizeInFrames = want
        bufferFrames = track.bufferSizeInFrames
        val n = burst.coerceIn(64, 1024)
        val l = FloatArray(n)
        val r = FloatArray(n)
        val out = FloatArray(n * ch)
        val l2 = FloatArray(n)                           // (0.8 LOOP: the part of a block after the loop's end)
        val r2 = FloatArray(n)
        val hl = FloatArray(n)                           // (0.10 CUE: the headphone mix, and its second track's block)
        val hr = FloatArray(n)
        val hl2 = FloatArray(n)
        val hr2 = FloatArray(n)
        val hout = FloatArray(n * 2)
        val ts = AudioTimestamp()
        var written = 0L
        var lastTs = 0L
        var lastUnder = track.underrunCount
        playStartPos = pos
        limGain = 1f
        track.play()
        try {
            while (playing) {
                // 0.8 LOOP (playing only, never while recording): bars loopFrom..loopTo round and round
                val pr = project
                val bar = beatSamples() * 4
                val ls = (pr.loopFrom - 1) * bar
                val le = pr.loopTo * bar
                val looping = pr.loopOn && !recording && le > ls
                if (looping && pos >= le) pos = ls              // (from before the loop: it plays into it)
                // 0.10 CUE: is there a headphone path for this block (hp), and which
                val cal = recording && recording4cal
                val any = !cal && anyCue()
                val split = splitCable && ch == 2 && !cal
                val four = ch == 4 && fourCh
                val ph = if (any && !split && !four && !cueFailed) phones else null
                val hp = split || (any && (four || ph != null))
                val st = if (split) 3 else if (!any) 0 else if (four) 1 else if (ph != null) 2 else if (cueFailed) 4 else 0
                if (cueState != st) cueState = st
                val xl = if (hp) hl else null
                val xr = if (hp) hr else null
                if (looping && pos + n > le) {
                    val m1 = (le - pos).coerceIn(0, n)
                    mix(pos, m1, l, r, true, xl, xr)
                    mix(ls, n - m1, l2, r2, true, if (hp) hl2 else null, if (hp) hr2 else null)
                    for (k in m1 until n) { l[k] = l2[k - m1]; r[k] = r2[k - m1] }
                    if (hp) for (k in m1 until n) { hl[k] = hl2[k - m1]; hr[k] = hr2[k - m1] }
                    pos = ls + (n - m1) - n                  // (+ n below: ls + the rest)
                } else {
                    mix(pos, n, l, r, true, xl, xr)
                }
                if (hp) {
                    // the headphones: the CUE tracks + CUE MIX x the main mix (before MASTER), the monitor, their level
                    val cm = cueMix
                    for (k in 0 until n) {
                        hl[k] += l[k] * cm
                        hr[k] += r[k] * cm
                    }
                    if (monitor && recording && !recording4cal) monitorInto(hl, hr, n)
                    master(l, r, n, true)
                    phonesOut(hl, hr, n)
                } else {
                    if (monitor && recording && !recording4cal) monitorInto(l, r, n)
                    master(l, r, n, true)
                }
                if (ch == 4) {
                    for (k in 0 until n) {
                        out[4 * k] = l[k]
                        out[4 * k + 1] = r[k]
                        out[4 * k + 2] = if (hp) hl[k] else 0f
                        out[4 * k + 3] = if (hp) hr[k] else 0f
                    }
                } else if (split) {
                    val sw = splitSwap
                    for (k in 0 until n) {
                        val m = (l[k] + r[k]) * 0.5f
                        val h = (hl[k] + hr[k]) * 0.5f
                        out[2 * k] = if (sw) h else m
                        out[2 * k + 1] = if (sw) m else h
                    }
                } else {
                    for (k in 0 until n) {
                        out[2 * k] = l[k]
                        out[2 * k + 1] = r[k]
                    }
                }
                if (ph != null) {                        // the second track: what fits, now; it never holds the main up
                    for (k in 0 until n) {
                        hout[2 * k] = hl[k]
                        hout[2 * k + 1] = hr[k]
                    }
                    ph.write(hout, 0, hout.size, AudioTrack.WRITE_NON_BLOCKING)
                }
                track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                blocks++
                written += n
                pos += n
                val now = System.nanoTime()
                if (now - lastTs > 100_000_000L) {               // ten times a second: the output's timing, the route
                    lastTs = now
                    if (track.getTimestamp(ts) && ts.framePosition > 0)
                        synchronized(outTs) { outTs.add(longArrayOf(ts.framePosition, ts.nanoTime)); if (outTs.size > 400) outTs.removeAt(0) }
                    val rd = track.routedDevice
                    outRoute = rd
                    val rid = if (rd != null) rd.id else -1
                    if (ch == 4) fourCh = rid != -1 && rid == usbId   // (3-4 only once it is known to play on the USB)
                    mainRouteId = rid
                    val u = track.underrunCount
                    if (u > lastUnder) {                         // it ran dry: a burst more of buffer
                        underruns += u - lastUnder
                        lastUnder = u
                        want = minOf(want + burst, burst * 8)
                        track.bufferSizeInFrames = want
                        bufferFrames = track.bufferSizeInFrames
                    }
                }
            }
        } finally {
            fourCh = false
            mainRouteId = -1
            cueState = 0
            track.stop()
            track.release()
        }
    }

    private fun recLoop() {
        try {
            recLoopInner()
        } catch (e: Exception) {                    // (no permission, no input): no take, the transport runs on
            recording = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun recLoopInner() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val dev = input
        val stereo = inputChannel != 0 && (dev?.channelCounts?.let { c -> c.isEmpty() || c.any { it >= 2 } } ?: false)
        val mask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val ch = if (stereo) 2 else 1
        val min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        val size = maxOf(min, burst * 4 * 4 * ch) * 2
        val sources = if (raw) intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC) else intArrayOf(MediaRecorder.AudioSource.MIC)
        var rec: AudioRecord? = null
        for (s in sources) {
            rec = try {
                AudioRecord.Builder().setAudioSource(s)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(mask).build())
                    .setBufferSizeInBytes(size).build()
            } catch (e: Exception) { null }
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) break
            rec?.release()
            rec = null
        }
        val ar = rec ?: throw IllegalStateException("no input")
        if (dev != null) ar.setPreferredDevice(dev)
        val buf = FloatArray(256 * ch)
        val ts = AudioTimestamp()
        var frames = 0L
        var lastTs = 0L
        ar.startRecording()
        try {
            while (recording) {
                val got = ar.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (got <= 0) continue
                val fr = got / ch
                if (recLen + fr > recBuf.size) recBuf = recBuf.copyOf(recBuf.size + rate * 30)
                val g = 10f.pow(inputGainDb / 20f)
                var ip = 0f
                var clip = false
                var w = ringW
                for (k in 0 until fr) {
                    val x = when {
                        !stereo -> buf[k]
                        inputChannel == 1 -> buf[2 * k]
                        else -> buf[2 * k + 1]
                    } * g
                    val a = abs(x)
                    if (a > ip) ip = a
                    if (a >= 0.999f) clip = true
                    val y = x.coerceIn(-1f, 1f)
                    recBuf[recLen + k] = (y * 32767f).toInt().toShort()
                    ring[(w and (ring.size - 1).toLong()).toInt()] = y
                    w++
                }
                ringW = w
                recLen += fr
                frames += fr
                if (ip > inputPeak) inputPeak = ip
                if (clip) inputClip = true
                val now = System.nanoTime()
                if (now - lastTs > 100_000_000L) {               // the input's timing
                    lastTs = now
                    if (ar.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS && ts.framePosition > 0)
                        synchronized(inTs) { inTs.add(longArrayOf(ts.framePosition, ts.nanoTime)); if (inTs.size > 400) inTs.removeAt(0) }
                }
            }
        } finally {
            ar.stop()
            ar.release()
        }
    }

    /** the input's latest samples into the mix (monitoring), through the armed track's fader and pan */
    private fun monitorInto(l: FloatArray, r: FloatArray, n: Int) {
        val t = project.tracks.getOrNull(recTrack) ?: return
        val w = ringW
        if (w - ringR > ring.size || w - ringR < n) ringR = maxOf(0L, w - n * 2)   // (keep close behind the writer)
        val gl = t.gain * (if (t.pan > 0) 1f - t.pan else 1f)
        val gr = t.gain * (if (t.pan < 0) 1f + t.pan else 1f)
        for (k in 0 until n) {
            if (ringR >= w) break
            val x = ring[(ringR and (ring.size - 1).toLong()).toInt()]
            ringR++
            l[k] += x * gl
            r[k] += x * gr
        }
    }

    /** MASTER and the limiter (and the output meters when live) */
    private fun master(l: FloatArray, r: FloatArray, n: Int, live: Boolean) {
        val mg = masterGain
        val ceil = 0.966f                                  // -0.3 dBFS
        val rel = exp(-1.0 / (0.12 * rate)).toFloat()      // 120 ms release
        var pl = 0f
        var pr = 0f
        for (k in 0 until n) {
            var a = l[k] * mg
            var b = r[k] * mg
            if (limiter) {
                val m = maxOf(abs(a), abs(b))
                val need = if (m * limGain > ceil) ceil / m else 1f
                limGain = if (need < limGain) need else limGain * rel + (1f - rel) * minOf(1f, need)
                a *= limGain
                b *= limGain
            }
            a = a.coerceIn(-1f, 1f)
            b = b.coerceIn(-1f, 1f)
            l[k] = a
            r[k] = b
            if (abs(a) > pl) pl = abs(a)
            if (abs(b) > pr) pr = abs(b)
        }
        if (live) {
            if (pl > peakL) peakL = pl
            if (pr > peakR) peakR = pr
        }
    }

    /** n samples of the tracks from sample p into l / r (the track meters too when live); the click while playing */
    private val tbufs = arrayOf(FloatArray(1024), FloatArray(1024))   // [0] playback, [1] export

    fun mix(p: Int, n: Int, l: FloatArray, r: FloatArray, live: Boolean, hl: FloatArray? = null, hr: FloatArray? = null) {
        java.util.Arrays.fill(l, 0f)
        java.util.Arrays.fill(r, 0f)
        // 0.10 CUE: with hl / hr (a headphone path, live only) the tracks in CUE go there, not into l / r
        val cueOut = hl != null && hr != null
        if (hl != null) java.util.Arrays.fill(hl, 0f)
        if (hr != null) java.util.Arrays.fill(hr, 0f)
        val tr = project.tracks
        val anySolo = tr.any { it.solo }
        for (t in tr) {
            val d = t.data ?: continue
            val cue = cueOut && t.cue                    // (in CUE: heard in the headphones whatever SOLO says)
            val audible = if (cue) !t.mute else if (anySolo) t.solo && !t.mute else !t.mute
            if (!audible || (live && recording && t.index == recTrack)) continue
            val len = minOf(t.length, d.limit())
            if (p + n <= 0 || p >= len) continue
            val g = t.gain
            val gl = g * (if (t.pan > 0) 1f - t.pan else 1f)
            val gr = g * (if (t.pan < 0) 1f + t.pan else 1f)
            val si = if (live) 0 else 1
            if (tbufs[si].size < n) tbufs[si] = FloatArray(n)
            val b = tbufs[si]
            for (k in 0 until n) {
                val q = p + k
                b[k] = if (q < 0 || q >= len) 0f else d.get(q) / 32768f
            }
            if (t.eq.ver != t.eqVer) t.eq.update(t, rate)
            if (t.eq.active) t.eq.run(b, n, si)
            var pk = 0f
            val tl = if (cue) hl!! else l
            val tr2 = if (cue) hr!! else r
            for (k in 0 until n) {
                val s = b[k]
                tl[k] += s * gl
                tr2[k] += s * gr
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

    // ---------------- export ----------------

    /** The whole song as heard (fader, pan, mute, solo, MASTER, the limiter; no click; CUE tracks in) into a stereo 16-bit WAV */
    fun mixdown(out: OutputStream) {
        val total = project.longest()
        Wav.header(out, rate, 2, total)
        val n = 4096
        val l = FloatArray(n)
        val r = FloatArray(n)
        val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        limGain = 1f
        for (t in project.tracks) t.eq.reset(1)
        var p = 0
        while (p < total) {
            val m = minOf(n, total - p)
            mix(p, m, l, r, false)
            master(l, r, m, false)
            bb.clear()
            for (k in 0 until m) {
                bb.putShort((l[k] * 32767f).toInt().toShort())
                bb.putShort((r[k] * 32767f).toInt().toShort())
            }
            out.write(bb.array(), 0, m * 4)
            p += m
        }
    }

    /** One track's audio as a mono 16-bit WAV (a stem, as recorded) */
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
