package com.makerhip.track16

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

class MainActivity : Activity() {
    lateinit var project: Project
    lateinit var engine: Engine
    var selected = 0
    val ui = Handler(Looper.getMainLooper())
    lateinit var midi: MidiControl
    private lateinit var midiBtn: Button
    private lateinit var learnBtn: Button
    private lateinit var clockBtn: Button
    private lateinit var thruBtn: Button
    private lateinit var midiText: TextView
    private lateinit var mixerPane: LinearLayout
    private val midiLabels = ArrayList<TextView>()
    private val eqBtns = ArrayList<Button>()
    private var eqRefresh: (() -> Unit)? = null
    private lateinit var masterLabel: TextView
    private var midiDialog: AlertDialog? = null
    private lateinit var inboxBtn: Button
    private lateinit var timeline: TimelineView
    private lateinit var mixerScroll: HorizontalScrollView
    private lateinit var mixerRow: LinearLayout
    private lateinit var playBtn: Button
    private lateinit var recBtn: Button
    private lateinit var timeText: TextView
    private lateinit var bpmText: TextView
    private lateinit var titleText: TextView
    private lateinit var clickBtn: Button
    private lateinit var countBtn: Button
    private lateinit var viewBtn: Button
    private lateinit var selName: TextView
    private lateinit var armBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var soloBtn: Button
    private lateinit var inMeter: View
    private lateinit var monBtn: Button
    private lateinit var outMeter: StereoMeter
    private lateinit var routeText: TextView
    private lateinit var masterFader: FaderView
    private lateinit var limBtn: Button
    private var usbHintShown = false
    private val faders = ArrayList<FaderView>()
    private val stripBtns = ArrayList<Triple<Button, Button, Button>>()
    private var showMixer = false
    private var busy = false
    private val d by lazy { resources.displayMetrics.density }
    private val prefs by lazy { getSharedPreferences("track16", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * d + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.BG
        project = openProject(prefs.getString("last", null) ?: newProjectName())
        engine = Engine(project)
        engine.latencyMs = prefs.getInt("latency", 120)
        engine.metronome = prefs.getBoolean("click", true)
        engine.countIn = prefs.getBoolean("countIn", true)
        engine.autoAlign = prefs.getBoolean("autoAlign", true)
        engine.fineMs = prefs.getInt("fine", 0)
        engine.inputGainDb = prefs.getFloat("inGain", 0f)
        engine.inputChannel = prefs.getInt("inCh", 0)
        engine.raw = prefs.getBoolean("raw", true)
        engine.masterGain = prefs.getFloat("master", 1f)
        engine.limiter = prefs.getBoolean("limiter", true)
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        engine.burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.coerceIn(64, 1024) ?: 192
        val inId = prefs.getInt("inDev", -1)
        engine.input = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == inId }
        am.registerAudioDeviceCallback(devices, ui)
        midi = MidiControl(this, prefs)
        build()
        reconnectBt()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        checkInbox()
    }

    override fun onPause() {
        super.onPause()
        project.save()
    }

    override fun onDestroy() {
        super.onDestroy()
        (getSystemService(AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(devices)
        midi.hub.clockStop(midi.hub.clocking)
        midi.hub.closeAll()
        ui.removeCallbacksAndMessages(null)
        if (engine.playing) engine.stop()
    }

    // ---------------- projects ----------------

    private fun projectsDir() = File(filesDir, "projects").also { it.mkdirs() }

    private fun newProjectName(): String {
        var k = 1
        while (File(projectsDir(), "Song $k").exists()) k++
        return "Song $k"
    }

    private fun openProject(name: String): Project {
        val dir = File(projectsDir(), name)
        dir.mkdirs()
        val p = Project(dir)
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val native = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        if (!File(dir, "project.json").exists()) p.rate = native
        p.load()
        if (!File(dir, "project.json").exists()) p.save()
        prefs.edit().putString("last", name).apply()
        return p
    }

    private fun switchTo(name: String) {
        if (engine.playing) stopAll()
        project.save()
        project = openProject(name)
        engine.project = project
        selected = 0
        refreshAll()
    }

    // ---------------- the layout ----------------

    private fun bg(color: Int, r: Int = 12, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = r * d
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    private fun btn(text: String, size: Float = 14f, act: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = size
        b.isAllCaps = false
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(C.TEXT)
        b.background = bg(C.PANEL2, 12, C.LINE)
        b.minWidth = 0; b.minimumWidth = 0; b.minHeight = 0; b.minimumHeight = 0
        b.setPadding(dp(14), dp(8), dp(14), dp(8))
        b.stateListAnimator = null
        b.setOnClickListener { act() }
        return b
    }

    private fun lit(b: Button, on: Boolean, color: Int) {
        b.background = if (on) bg(color, 12) else bg(C.PANEL2, 12, C.LINE)
        b.setTextColor(if (on) C.BG else C.TEXT)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun row(): LinearLayout = LinearLayout(this).also {
        it.orientation = LinearLayout.HORIZONTAL
        it.gravity = Gravity.CENTER_VERTICAL
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, ml: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).also { it.marginStart = ml }

    private fun build() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C.BG)

        // header: PLAY, REC, back to the start, the time, BPM, click, count-in, the view, the menu
        val head = row()
        head.setBackgroundColor(C.PANEL)
        head.setPadding(dp(10), dp(6), dp(10), dp(6))
        playBtn = btn("▶", 30f) { if (!midi.learnTap("play")) { if (engine.playing) stopAll() else startPlay() } }
        playBtn.setPadding(0, 0, 0, 0)
        head.addView(playBtn, lp(dp(72), dp(72)))
        recBtn = btn("●", 28f) { if (!midi.learnTap("rec")) { if (engine.recording) stopAll() else startRec() } }
        recBtn.setPadding(0, 0, 0, 0)
        head.addView(recBtn, lp(dp(64), dp(64), 0f, dp(8)))
        head.addView(btn("|◀", 18f) { if (!midi.learnTap("rtz")) seek(0) }, lp(dp(52), dp(52), 0f, dp(8)))
        val tc = LinearLayout(this)
        tc.orientation = LinearLayout.VERTICAL
        titleText = text(project.title, 12f, C.DIM, true)
        titleText.setOnClickListener { projectsDialog() }
        timeText = text("0:00.0   1.1", 22f, C.TEXT, true)
        routeText = text("", 11f, C.DIM)
        tc.addView(titleText)
        tc.addView(timeText)
        tc.addView(routeText)
        head.addView(tc, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, dp(14)))
        // the MIXER / TRACKS switch: big, in the middle of the bar
        viewBtn = btn("MIXER", 22f) { showMixer = !showMixer; refreshAll() }
        viewBtn.minWidth = dp(150)
        viewBtn.setPadding(dp(22), 0, dp(22), 0)
        head.addView(viewBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(64), 0f, dp(10)))
        val right = row()
        right.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        head.addView(right, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, dp(10)))
        outMeter = StereoMeter(this)
        right.addView(outMeter, lp(dp(14), dp(56), 0f, dp(6)))
        val bpm = row()
        bpm.background = bg(C.PANEL2, 12, C.LINE)
        bpm.addView(btn("−", 18f) { setBpm(project.bpm - 1) }.also { it.background = null })
        bpmText = text("${project.bpm}", 18f, C.TEXT, true)
        bpmText.setOnClickListener { bpmDialog() }
        bpm.addView(bpmText)
        bpm.addView(btn("+", 18f) { setBpm(project.bpm + 1) }.also { it.background = null })
        right.addView(bpm, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48), 0f, dp(8)))
        clickBtn = btn("Click") { engine.metronome = !engine.metronome; prefs.edit().putBoolean("click", engine.metronome).apply(); paint() }
        right.addView(clickBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48), 0f, dp(8)))
        countBtn = btn("Count-in") { engine.countIn = !engine.countIn; prefs.edit().putBoolean("countIn", engine.countIn).apply(); paint() }
        right.addView(countBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48), 0f, dp(6)))
        inboxBtn = btn("INBOX", 14f) { inboxDialog() }
        inboxBtn.visibility = View.GONE
        right.addView(inboxBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48), 0f, dp(8)))
        right.addView(btn("☰", 18f) { menu() }, lp(dp(52), dp(48), 0f, dp(6)))
        root.addView(head)

        // the selected track: its name, ARM, M, S, import, clear, the input meter
        val sel = row()
        sel.setPadding(dp(10), dp(4), dp(10), dp(4))
        selName = text("", 16f, C.TEXT, true)
        selName.setOnClickListener { renameTrack() }
        sel.addView(selName, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inMeter = View(this)
        sel.addView(inMeter, lp(dp(6), dp(36), 0f, dp(6)))
        armBtn = btn("ARM") { toggleArm(selected) }
        monBtn = btn("MON") { toggleMonitor() }
        muteBtn = btn("M") { val t = project.tracks[selected]; t.mute = !t.mute; refreshAll() }
        soloBtn = btn("S") { val t = project.tracks[selected]; t.solo = !t.solo; refreshAll() }
        sel.addView(armBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44), 0f, dp(8)))
        sel.addView(monBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44), 0f, dp(6)))
        sel.addView(muteBtn, lp(dp(48), dp(44), 0f, dp(6)))
        sel.addView(soloBtn, lp(dp(48), dp(44), 0f, dp(6)))
        sel.addView(btn("Import WAV") { pickWav() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44), 0f, dp(8)))
        sel.addView(btn("Clear") { clearTrack() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44), 0f, dp(6)))
        root.addView(sel)

        // the body: the timeline, or the mixer's 16 strips
        val body = FrameLayout(this)
        timeline = TimelineView(this, this)
        body.addView(timeline, FrameLayout.LayoutParams(-1, -1))
        mixerScroll = HorizontalScrollView(this)
        mixerRow = row()
        mixerRow.gravity = Gravity.TOP
        mixerRow.setPadding(dp(6), dp(6), dp(6), dp(6))
        for (i in 0 until Project.N) mixerRow.addView(strip(i), lp(dp(76), -1, 0f, if (i == 0) 0 else dp(4)))
        mixerRow.addView(masterStrip(), lp(dp(92), -1, 0f, dp(10)))
        mixerScroll.addView(mixerRow, FrameLayout.LayoutParams(-2, -1))
        mixerScroll.isFillViewport = true
        mixerPane = LinearLayout(this)
        mixerPane.orientation = LinearLayout.VERTICAL
        mixerPane.addView(midiBar(), LinearLayout.LayoutParams(-1, -2))
        mixerPane.addView(mixerScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(mixerPane, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, lp(-1, 0, 1f))

        // zoom
        val bottom = row()
        bottom.setPadding(dp(10), dp(4), dp(10), dp(6))
        bottom.addView(text("16 Track Mixer Recorder · Maker Hip", 11f, C.DIM), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(btn("−  zoom", 12f) { timeline.secondsPerScreen = minOf(600f, timeline.secondsPerScreen * 1.5f); timeline.invalidate() })
        bottom.addView(btn("zoom  +", 12f) { timeline.secondsPerScreen = maxOf(2f, timeline.secondsPerScreen / 1.5f); timeline.invalidate() },
            lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0f, dp(6)))
        root.addView(bottom)
        setContentView(root)
        refreshAll()
    }

    /** MASTER: the fader for the whole mix (before the speakers / the export), the limiter, the output meter */
    private fun masterStrip(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.background = bg(C.PANEL2, 12, C.ORANGE)
        col.setPadding(dp(4), dp(6), dp(4), dp(6))
        col.addView(text("MASTER", 13f, C.ORANGE, true).also { it.gravity = Gravity.CENTER })
        limBtn = btn("LIMIT", 12f) {
            engine.limiter = !engine.limiter
            prefs.edit().putBoolean("limiter", engine.limiter).apply()
            refreshAll()
        }
        limBtn.setPadding(0, 0, 0, 0)
        col.addView(limBtn, LinearLayout.LayoutParams(-1, dp(34)).also { it.topMargin = dp(4) })
        masterFader = FaderView(this, C.ORANGE) { v ->
            if (!midi.learnTap("master")) setMaster(v)
        }
        masterFader.value = engine.masterGain
        col.addView(masterFader, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = dp(8) })
        masterLabel = text("", 10f, C.YELLOW).also { it.gravity = Gravity.CENTER }
        col.addView(masterLabel, LinearLayout.LayoutParams(-1, -2))
        return col
    }

    /** one mixer strip: number, name, ARM, M, S, PAN, the fader with its meter */
    private fun strip(i: Int): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.background = bg(C.PANEL, 12)
        col.setPadding(dp(4), dp(6), dp(4), dp(6))
        val num = text("${i + 1}", 16f, C.TRACK[i], true)
        num.gravity = Gravity.CENTER
        num.setOnClickListener { selectTrack(i) }
        col.addView(num)
        val a = btn("R", 12f) { if (!midi.learnTap("arm:$i")) toggleArm(i) }
        val m = btn("M", 12f) { if (!midi.learnTap("mute:$i")) { val t = project.tracks[i]; t.mute = !t.mute; refreshAll() } }
        val s = btn("S", 12f) { if (!midi.learnTap("solo:$i")) { val t = project.tracks[i]; t.solo = !t.solo; refreshAll() } }
        for (b in listOf(a, m, s)) {
            b.setPadding(0, 0, 0, 0)
            col.addView(b, LinearLayout.LayoutParams(-1, dp(34)).also { it.topMargin = dp(4) })
        }
        stripBtns.add(Triple(a, m, s))
        val eqb = btn("EQ", 12f) { eqDialog(i) }
        eqb.setPadding(0, 0, 0, 0)
        eqBtns.add(eqb)
        col.addView(eqb, LinearLayout.LayoutParams(-1, dp(34)).also { it.topMargin = dp(4) })
        col.addView(text("PAN", 9f, C.DIM).also { it.gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) })
        val pan = SeekBar(this)
        pan.max = 200
        pan.progress = ((project.tracks[i].pan + 1f) * 100).toInt()
        pan.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, user: Boolean) {
                if (user) project.tracks[i].pan = if (kotlin.math.abs(v - 100) < 6) 0f else v / 100f - 1f
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { midi.learnTap("pan:$i") }
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        pan.tag = "pan$i"
        col.addView(pan, LinearLayout.LayoutParams(-1, dp(30)))
        val f = FaderView(this, C.TRACK[i]) { v -> midi.learnTap("fader:$i"); project.tracks[i].gain = v }
        f.value = project.tracks[i].gain
        faders.add(f)
        col.addView(f, LinearLayout.LayoutParams(-1, 0, 1f))
        val ml = text("", 10f, C.YELLOW).also { it.gravity = Gravity.CENTER }
        midiLabels.add(ml)
        col.addView(ml, LinearLayout.LayoutParams(-1, -2))
        return col
    }

    // ---------------- refresh ----------------

    fun refreshAll() {
        titleText.text = project.title
        bpmText.text = " ${project.bpm} BPM "
        timeline.visibility = if (showMixer) View.GONE else View.VISIBLE
        mixerPane.visibility = if (showMixer) View.VISIBLE else View.GONE
        viewBtn.text = if (showMixer) "◀ TRACKS" else "MIXER ▶"
        lit(viewBtn, true, if (showMixer) C.BLUE else C.ORANGE)
        for (i in 0 until Project.N) {
            val t = project.tracks[i]
            faders[i].value = t.gain
            mixerRow.findViewWithTag<SeekBar>("pan$i")?.progress = ((t.pan + 1f) * 100).toInt()
            val (a, m, s) = stripBtns[i]
            lit(a, t.armed, C.RED); lit(m, t.mute, C.DIM); lit(s, t.solo, C.YELLOW)
            lit(eqBtns[i], t.eqUsed, C.TRACK[i])
        }
        masterFader.value = engine.masterGain
        lit(limBtn, engine.limiter, C.ORANGE)
        midiLabelsPaint()
        paint()
        timeline.invalidate()
    }

    private fun paint() {
        val t = project.tracks[selected]
        selName.text = "${selected + 1}  ${t.name}"
        selName.setTextColor(C.TRACK[selected])
        lit(armBtn, t.armed, C.RED)
        lit(monBtn, engine.monitor, C.YELLOW)
        lit(muteBtn, t.mute, C.DIM)
        lit(soloBtn, t.solo, C.YELLOW)
        lit(clickBtn, engine.metronome, C.BLUE)
        lit(countBtn, engine.countIn, C.BLUE)
        lit(playBtn, true, if (engine.playing && !engine.recording) C.RED else C.GREEN)
        playBtn.text = if (engine.playing) "■" else "▶"
        lit(recBtn, true, if (engine.recording) C.RED else C.PANEL2)
        recBtn.setTextColor(if (engine.recording) C.BG else C.RED)
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = engine.pos
            val sec = maxOf(0, p) / project.rate.toFloat()
            val beat = engine.beatSamples()
            val b = Math.floorDiv(p, beat)
            timeText.text = String.format(Locale.US, "%d:%04.1f   %d.%d", (sec / 60).toInt(), sec % 60,
                Math.floorDiv(b, 4) + 1, Math.floorMod(b, 4) + 1)
            if (engine.playing) timeline.follow()
            timeline.invalidate()
            for (i in 0 until Project.N) {
                val t = project.tracks[i]
                faders[i].meter = t.peak
                if (showMixer) faders[i].invalidate()
                t.peak *= 0.85f
            }
            val ip = engine.inputPeak
            inMeter.background = bg(if (engine.inputClip || ip > 0.95f) C.RED else if (ip > 0.02f) C.GREEN else C.PANEL2, 3)
            engine.inputPeak *= 0.8f
            outMeter.l = engine.peakL
            outMeter.r = engine.peakR
            outMeter.invalidate()
            masterFader.meter = maxOf(engine.peakL, engine.peakR)
            if (showMixer) masterFader.invalidate()
            engine.peakL *= 0.85f
            engine.peakR *= 0.85f
            routeText.text = "OUT: " + routeName(engine.outRoute ?: defaultOut()) + "   IN: " + (engine.input?.let { devName(it) } ?: "Built-in mic")
            midiTick()
            ui.postDelayed(this, 40)
        }
    }

    // ---------------- actions ----------------

    fun selectTrack(i: Int) {
        selected = i
        paint()
        timeline.invalidate()
    }

    fun seek(p: Int) {
        engine.pos = maxOf(0, p)
        timeline.invalidate()
    }

    private fun setBpm(v: Int) {
        if (engine.playing) return
        project.bpm = v.coerceIn(30, 300)
        refreshAll()
    }

    private fun toggleArm(i: Int) {
        if (engine.recording) return
        val on = !project.tracks[i].armed
        for (t in project.tracks) t.armed = false            // (one track records at a time in 0.1)
        project.tracks[i].armed = on
        selected = i
        refreshAll()
    }

    private fun startPlay() {
        if (busy) return
        engine.play()
        paint()
    }

    private fun startRec() {
        if (busy) return
        engine.inputClip = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (engine.playing) stopAll()
        if (project.tracks.none { it.armed }) toggleArm(selected)
        if (!engine.record()) {
            toast("Arm a track first (ARM)")
            return
        }
        paint()
    }

    private fun stopAll() {
        if (busy) return
        val wasRec = engine.recording
        busy = true
        Thread {
            val wrote = engine.stop()
            ui.post {
                busy = false
                if (wasRec) {
                    project.save()
                    toast(if (wrote) "Take recorded on track ${project.tracks.indexOfFirst { it.armed } + 1}" else "Nothing recorded")
                }
                refreshAll()
            }
        }.start()
        paint()
    }

    // ---------------- MIDI (the MIXER page) ----------------

    fun setMaster(v: Float) {
        engine.masterGain = v.coerceIn(0f, 1.2f)
        prefs.edit().putFloat("master", engine.masterGain).apply()
    }

    fun midiArm(i: Int) = toggleArm(i)
    fun midiPlay() { if (engine.playing) stopAll() else startPlay() }
    fun midiRec() { if (engine.recording) stopAll() else startRec() }
    fun midiStop() { if (engine.playing) stopAll() }

    /** the bar above the strips: MIDI (devices), LEARN, CLOCK, THRU, what is connected */
    private fun midiBar(): View {
        val r = row()
        r.setPadding(dp(8), dp(6), dp(8), 0)
        midiBtn = btn("MIDI", 13f) { midiDialog() }
        learnBtn = btn("LEARN", 13f) {
            midi.learn = !midi.learn
            midi.pending = null
            midiChanged()
        }
        learnBtn.setOnLongClickListener {
            AlertDialog.Builder(this).setTitle("Clear MIDI LEARN?")
                .setMessage("Every control learned is forgotten (the SMC-Mixer's own layout stays).")
                .setPositiveButton("Clear all") { _, _ -> midi.clearAll(); midiChanged() }
                .setNegativeButton("Cancel", null).show()
            true
        }
        clockBtn = btn("CLOCK", 13f) {
            midi.clockOn = !midi.clockOn
            midi.saveFlags()
            if (!midi.clockOn && midi.hub.clocking) midi.hub.clockStop()
            midiChanged()
        }
        thruBtn = btn("THRU", 13f) { midi.thruOn = !midi.thruOn; midi.saveFlags(); midiChanged() }
        midiText = text("", 12f, C.DIM)
        val back = btn("◀ BACK", 14f) { showMixer = false; refreshAll() }
        lit(back, true, C.BLUE)
        r.addView(back, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        r.addView(midiBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40), 0f, dp(12)))
        r.addView(learnBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40), 0f, dp(6)))
        r.addView(clockBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40), 0f, dp(6)))
        r.addView(thruBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40), 0f, dp(6)))
        r.addView(midiText, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, dp(12)))
        return r
    }

    /** the MIDI state changed (a device, LEARN, CLOCK, THRU, the SMC bank): the bar, the labels, the dialog */
    fun midiChanged() {
        if (!::midiText.isInitialized) return
        val cs = midi.hub.conns
        lit(midiBtn, cs.isNotEmpty(), C.BLUE)
        lit(learnBtn, midi.learn, C.YELLOW)
        lit(clockBtn, midi.clockOn, C.GREEN)
        lit(thruBtn, midi.thruOn, C.GREEN)
        midiText.text = when {
            midi.learn && midi.pending != null -> "LEARN: now move a knob, fader or button on the controller (" + targetName(midi.pending!!) + ")"
            midi.learn -> "LEARN: tap a fader, PAN, R / M / S, MASTER, PLAY / REC or an EQ slider, then move the controller. Hold LEARN to clear all."
            cs.isEmpty() -> if (midi.hub.available) "No MIDI device. Tap MIDI to connect Bluetooth; USB connects by itself." else "This device has no MIDI."
            else -> cs.joinToString("   ") { (if (it.bt) "BT: " else "USB: ") + it.name + if (it.smc) " (tracks ${midi.bank * 8 + 1}-${midi.bank * 8 + 8})" else "" }
        }
        midiText.setTextColor(if (midi.learn) C.YELLOW else C.DIM)
        midiLabelsPaint()
        if (midiDialog?.isShowing == true) fillMidiDialog()
    }

    private fun targetName(t: String): String = when {
        t == "master" -> "MASTER"
        t == "play" -> "PLAY"
        t == "rec" -> "REC"
        t == "rtz" -> "to the start"
        t.startsWith("eq") -> "EQ " + mapOf("eqcut" to "LOW-CUT", "eqlow" to "LOW", "eqmid" to "MID", "eqfreq" to "MID FREQ",
            "eqhigh" to "HIGH")[t.substringBefore(':')] + " " + (t.substringAfter(':').toInt() + 1)
        else -> t.substringBefore(':').let { if (it == "arm") "R" else it.uppercase() } + " " + (t.substringAfter(':').toInt() + 1)
    }

    private fun midiLabelsPaint() {
        if (midiLabels.size < Project.N) return
        val p = midi.pending
        for (i in 0 until Project.N) {
            val parts = listOf("fader", "pan", "arm", "mute", "solo").mapNotNull { k ->
                val l = midi.label("$k:$i")
                if (l.isEmpty()) null else (if (k == "fader") "" else (if (k == "arm") "R " else k[0].uppercase() + " ")) + l
            }
            midiLabels[i].text = if (p != null && p.endsWith(":$i")) "◉ ${targetName(p)}" else parts.joinToString("\n")
        }
        masterLabel.text = if (p == "master") "◉ MASTER" else midi.label("master")
        val strips = mixerRow
        for (i in 0 until Project.N) {
            val sel = p != null && p.endsWith(":$i")
            val smc = midi.hub.conns.any { it.smc } && i / 8 == midi.bank
            strips.getChildAt(i).background = bg(C.PANEL, 12, if (sel) C.YELLOW else if (smc) C.BLUE else 0)
        }
    }

    private fun midiTick() {
        midi.feedback()
        if (midi.clockOn && engine.playing && !midi.hub.clocking && midi.hub.conns.isNotEmpty()) {
            val q = maxOf(1, engine.beatSamples() / 4)
            midi.hub.clockStart({ project.bpm }, maxOf(0, engine.pos) / q)
        } else if (midi.hub.clocking && (!engine.playing || !midi.clockOn)) {
            midi.hub.clockStop()
        }
    }

    private fun btPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBtPerms() = btPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun reconnectBt() {
        val a = prefs.getString("midiBt", null) ?: return
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        try { midi.hub.connectBt(a) } catch (_: Exception) {}
    }

    private fun scanBt() {
        if (!hasBtPerms()) { requestPermissions(btPerms(), 2); return }
        when (val r = try { midi.hub.startScan() } catch (e: SecurityException) { "Bluetooth permission needed" }) {
            null -> {}
            "off" -> try { startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (e: Exception) { toast("Turn Bluetooth on first") }
            else -> toast(r)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (hasBtPerms()) scanBt() else toast("Bluetooth MIDI needs the Nearby devices permission")
        }
    }

    private lateinit var midiList: LinearLayout

    private fun midiDialog() {
        if (midi.learn) { midi.learn = false; midi.pending = null }
        midiList = LinearLayout(this)
        midiList.orientation = LinearLayout.VERTICAL
        midiList.setPadding(dp(20), dp(8), dp(20), dp(8))
        val sv = android.widget.ScrollView(this)
        sv.addView(midiList)
        midiDialog = AlertDialog.Builder(this).setTitle("MIDI devices").setView(sv)
            .setPositiveButton("Done", null)
            .setOnDismissListener { midi.hub.stopScan() }.show()
        fillMidiDialog()
        if (midi.hub.conns.none { it.bt } && hasBtPerms()) scanBt()
    }

    private fun fillMidiDialog() {
        val l = midiList
        l.removeAllViews()
        l.addView(text("CONNECTED", 13f, C.ORANGE, true))
        if (midi.hub.conns.isEmpty()) l.addView(text("Nothing yet.", 13f, C.DIM))
        for (c in ArrayList(midi.hub.conns)) {
            val b = btn((if (c.bt) "BT  " else "USB  ") + c.name + "   (tap to disconnect)", 13f) {
                if (c.bt) prefs.edit().remove("midiBt").apply()
                midi.forgetConn(c)
                midi.hub.close(c)
            }
            lit(b, true, C.BLUE)
            l.addView(b, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(4) })
        }
        l.addView(text("BLUETOOTH", 13f, C.ORANGE, true).also { it.setPadding(0, dp(14), 0, 0) })
        val sb = btn(if (midi.hub.scanning) "Searching..." else "SEARCH FOR BLUETOOTH MIDI", 13f) { scanBt() }
        sb.isEnabled = !midi.hub.scanning
        l.addView(sb, LinearLayout.LayoutParams(-1, dp(46)).also { it.topMargin = dp(4) })
        val ids = midi.hub.conns.map { it.name }
        for ((addr, nd) in midi.hub.found) {
            if (nd.first in ids) continue
            val b = btn(nd.first + "   (tap to connect)", 13f) {
                prefs.edit().putString("midiBt", addr).apply()
                midi.hub.stopScan()
                try { midi.hub.connectBt(nd.second) } catch (e: SecurityException) { toast("Bluetooth permission needed") }
                toast("Connecting to ${nd.first}...")
            }
            l.addView(b, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(4) })
        }
        l.addView(text("Turn the device on (an SMC-Mixer: its DAW mode for the built-in layout, or CC mode for LEARN). " +
            "Bluetooth MIDI devices are found here, not in the phone's Bluetooth settings. A USB MIDI device (the FM-1 " +
            "with an OTG cable) connects by itself.", 12f, C.DIM).also { it.setPadding(0, dp(12), 0, 0) })
    }

    // ---------------- EQ ----------------

    /** an EQ value moved from MIDI: the strip's light, the open EQ panel */
    fun eqChanged(i: Int) {
        lit(eqBtns[i], project.tracks[i].eqUsed, C.TRACK[i])
        eqRefresh?.invoke()
    }

    private fun eqDialog(i: Int) {
        val t = project.tracks[i]
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(8), dp(20), dp(8))
        val view = EqView(this, C.TRACK[i])
        view.rate = project.rate
        if (t.eq.ver != t.eqVer) t.eq.update(t, project.rate)
        view.eq = t.eq
        col.addView(view, LinearLayout.LayoutParams(-1, dp(130)))
        val sliders = ArrayList<() -> Unit>()
        fun changed() {
            t.eqVer++
            t.eq.update(t, project.rate)
            view.on = t.eqOn
            view.invalidate()
            lit(eqBtns[i], t.eqUsed, C.TRACK[i])
        }
        fun slider(target: String, label: (Int) -> String, max: Int, get: () -> Int, set: (Int) -> Unit) {
            val tv = text(label(get()), 14f, C.TEXT, true)
            tv.setPadding(0, dp(10), 0, 0)
            col.addView(tv)
            val sb = SeekBar(this)
            sb.max = max
            sb.progress = get()
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, user: Boolean) {
                    tv.text = label(v)
                    if (user) { set(v); changed() }
                }
                override fun onStartTrackingTouch(s: SeekBar?) { midi.learnTap(target) }
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            col.addView(sb)
            sliders.add { sb.progress = get(); tv.text = label(get()) }
        }
        fun dbText(name: String, v: Int) = "$name  " + (v - 24).let { if (it == 0) "0 dB" else String.format(Locale.US, "%+.1f dB", it / 2f) }
        slider("eqcut:$i", { if (it < 4) "LOW-CUT  off" else "LOW-CUT  ${it * 10} Hz" }, 30,
            { t.lowCut / 10 }, { t.lowCut = if (it < 4) 0 else it * 10 })
        slider("eqlow:$i", { dbText("LOW (100 Hz)", it) }, 48, { (t.lowDb * 2).toInt() + 24 }, { t.lowDb = (it - 24) / 2f })
        slider("eqfreq:$i", { "MID frequency  " + midHzOf(it).let { f -> if (f >= 1000) String.format(Locale.US, "%.1f kHz", f / 1000f) else "$f Hz" } }, 100,
            { (kotlin.math.ln(t.midHz / 200.0) / kotlin.math.ln(25.0) * 100).toInt() }, { t.midHz = midHzOf(it) })
        slider("eqmid:$i", { dbText("MID", it) }, 48, { (t.midDb * 2).toInt() + 24 }, { t.midDb = (it - 24) / 2f })
        slider("eqhigh:$i", { dbText("HIGH (8 kHz)", it) }, 48, { (t.highDb * 2).toInt() + 24 }, { t.highDb = (it - 24) / 2f })
        val r = row()
        r.setPadding(0, dp(12), 0, 0)
        lateinit var onBtn: Button
        onBtn = btn("EQ ON", 14f) { t.eqOn = !t.eqOn; lit(onBtn, t.eqOn, C.GREEN); changed() }
        lit(onBtn, t.eqOn, C.GREEN)
        r.addView(onBtn, lp(0, dp(46), 1f))
        r.addView(btn("RESET", 14f) { t.eqReset(); sliders.forEach { it() }; lit(onBtn, t.eqOn, C.GREEN); changed() }, lp(0, dp(46), 1f, dp(8)))
        col.addView(r)
        col.addView(text("EQ ON off: hear the track without its EQ (to compare). Plays live and goes into the exported mix. " +
            "With MIDI LEARN on, touch a slider here, then move the controller.", 12f, C.DIM).also { it.setPadding(0, dp(10), 0, 0) })
        view.on = t.eqOn
        eqRefresh = { sliders.forEach { it() }; lit(onBtn, t.eqOn, C.GREEN); view.on = t.eqOn; view.invalidate() }
        val sv = android.widget.ScrollView(this)
        sv.addView(col)
        AlertDialog.Builder(this).setTitle("EQ  ·  track ${i + 1}  ${t.name}").setView(sv)
            .setPositiveButton("Done", null)
            .setOnDismissListener { eqRefresh = null; project.save() }.show()
    }

    private fun midHzOf(p: Int): Int = (200 * Math.pow(25.0, p / 100.0)).toInt().let { f ->
        if (f >= 1000) (f + 50) / 100 * 100 else (f + 5) / 10 * 10 }.coerceIn(200, 5000)

    // ---------------- the Maker Hip INBOX (INBOX.md): boxes of stems from Burger Studio ----------------

    /** the INBOX button shows when Burger Studio holds boxes for us */
    private fun checkInbox() {
        Thread {
            val n = Inbox.boxes(this).size
            ui.post {
                if (!::inboxBtn.isInitialized) return@post
                inboxBtn.visibility = if (n > 0) View.VISIBLE else View.GONE
                inboxBtn.text = "INBOX $n"
                lit(inboxBtn, n > 0, C.PINK)
            }
        }.start()
    }

    private fun inboxDialog() {
        Thread {
            val boxes = Inbox.boxes(this)
            ui.post {
                if (boxes.isEmpty()) { toast("The inbox is empty"); checkInbox(); return@post }
                val items = boxes.map { b ->
                    "${b.title}\n   ${b.tracks} tracks · ${b.bars} bars · ${b.bpm} BPM ${b.meter}  ·  " +
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(b.created))
                }.toTypedArray()
                AlertDialog.Builder(this).setTitle("INBOX · from Burger Studio")
                    .setItems(items) { _, w -> boxDialog(boxes[w]) }
                    .setNegativeButton("Close", null).show()
            }
        }.start()
    }

    private fun boxDialog(b: Inbox.Box) {
        Thread {
            val stems = Inbox.stems(this, b)
            ui.post {
                val list = stems.take(Project.N).joinToString("\n") { "${it.idx + 1}.  ${it.name}" } +
                    if (stems.size > Project.N) "\n\n${stems.size - Project.N} more left out (16 tracks at most)" else ""
                AlertDialog.Builder(this).setTitle(b.title)
                    .setMessage("$list\n\nSPLIT puts each stem on its own track (name, fader, pan) and sets ${b.bpm} BPM.")
                    .setPositiveButton("SPLIT → new song") { _, _ -> split(b, stems, true) }
                    .setNeutralButton("SPLIT → this song") { _, _ ->
                        AlertDialog.Builder(this).setTitle("Replace tracks 1-${minOf(stems.size, Project.N)} of ${project.title}?")
                            .setMessage("Their audio is replaced by the stems.")
                            .setPositiveButton("Replace") { _, _ -> split(b, stems, false) }
                            .setNegativeButton("Cancel", null).show()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }.start()
    }

    private fun split(b: Inbox.Box, stems: List<Inbox.Stem>, newSong: Boolean) {
        if (busy || engine.playing) { toast("Stop first"); return }
        if (newSong) {
            val base = b.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(36).trim().ifBlank { "Inbox song" }
            var name = base
            var k = 2
            while (File(projectsDir(), name).exists()) name = "$base ${k++}"
            switchTo(name)
        }
        busy = true
        toast("Splitting ${stems.size} stems...")
        val p = project
        Thread {
            var done = 0
            for ((i, st) in stems.take(Project.N).withIndex()) {
                val a = Inbox.audio(this, b, st, p.rate) ?: continue
                p.clear(i)
                p.write(i, 0, a, a.size)
                val t = p.tracks[i]
                t.name = st.name.ifBlank { "Track ${i + 1}" }
                t.gain = st.gain.coerceIn(0f, 1.2f)
                t.pan = st.pan.coerceIn(-1f, 1f)
                t.mute = false; t.solo = false
                done++
            }
            if (b.bpm in 30..300) p.bpm = b.bpm
            p.save()
            ui.post {
                busy = false
                refreshAll()
                AlertDialog.Builder(this).setTitle("Split: $done of ${stems.size} stems")
                    .setMessage("Remove \"${b.title}\" from the inbox?")
                    .setPositiveButton("Remove") { _, _ -> Thread { Inbox.delete(this, b); ui.post { checkInbox() } }.start() }
                    .setNegativeButton("Keep it") { _, _ -> checkInbox() }.show()
            }
        }.start()
    }

    private fun clearTrack() {
        if (engine.playing) return
        AlertDialog.Builder(this).setTitle("Clear track ${selected + 1}?")
            .setMessage("Its audio is deleted.")
            .setPositiveButton("Clear") { _, _ -> project.clear(selected); refreshAll() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun renameTrack() {
        val e = EditText(this)
        e.setText(project.tracks[selected].name)
        AlertDialog.Builder(this).setTitle("Track ${selected + 1} name").setView(e)
            .setPositiveButton("OK") { _, _ -> project.tracks[selected].name = e.text.toString().ifBlank { "Track ${selected + 1}" }; project.save(); paint() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun bpmDialog() {
        val e = EditText(this)
        e.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        e.setText("${project.bpm}")
        AlertDialog.Builder(this).setTitle("Tempo (BPM)").setView(e)
            .setPositiveButton("OK") { _, _ -> e.text.toString().toIntOrNull()?.let { setBpm(it) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun menu() {
        val items = arrayOf("New song", "Open a song", "Rename this song", "Export: mix and stems (WAV)", "Settings: input and latency", "About")
        AlertDialog.Builder(this).setTitle(project.title).setItems(items) { _, w ->
            when (w) {
                0 -> switchTo(newProjectName())
                1 -> projectsDialog()
                2 -> renameProject()
                3 -> exportAll()
                4 -> settings()
                5 -> AlertDialog.Builder(this).setTitle("16 Track Mixer Recorder")
                    .setMessage("Version ${packageManager.getPackageInfo(packageName, 0).versionName} · a Maker Hip app.\n\n" +
                        "Record up to 16 tracks one at a time over each other, mix them, and export the mix and the stems as WAV.\n\n" +
                        "INBOX: with Burger Studio installed, its → MIX boxes (a Q or a whole song, one stem per track) wait here; SPLIT puts them on the tracks.")
                    .setPositiveButton("OK", null).show()
            }
        }.show()
    }

    private fun projectsDialog() {
        val names = projectsDir().listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        AlertDialog.Builder(this).setTitle("Open a song").setItems(names.toTypedArray()) { _, w -> switchTo(names[w]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun renameProject() {
        val e = EditText(this)
        e.setText(project.title)
        AlertDialog.Builder(this).setTitle("Song name").setView(e)
            .setPositiveButton("OK") { _, _ ->
                val n = e.text.toString().trim().replace("/", "-")
                if (n.isNotEmpty() && n != project.dir.name && !File(projectsDir(), n).exists()) {
                    project.save()
                    val to = File(projectsDir(), n)
                    if (project.dir.renameTo(to)) {
                        prefs.edit().putString("last", n).apply()
                        project = openProject(n)
                        project.title = n
                        project.save()
                        engine.project = project
                        refreshAll()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    /** Settings: everything about the sound in and out, in one scrolling page */
    private fun settings() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(10), dp(20), dp(10))
        fun head(t: String) = col.addView(text(t, 15f, C.ORANGE, true).also { it.setPadding(0, dp(16), 0, dp(2)) })
        fun note(t: String) = col.addView(text(t, 12f, C.DIM))
        fun slider(max: Int, v: Int, label: (Int) -> String): SeekBar {
            val tv = text(label(v), 14f, C.TEXT, true)
            col.addView(tv)
            val sb = SeekBar(this)
            sb.max = max
            sb.progress = v
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, x: Int, u: Boolean) { tv.text = label(x) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            col.addView(sb)
            return sb
        }
        fun toggle(label: String, on: Boolean, color: Int = C.ORANGE): () -> Boolean {
            var v = on
            lateinit var b: Button
            b = btn(label, 13f) { v = !v; lit(b, v, color) }
            lit(b, v, color)
            col.addView(b, LinearLayout.LayoutParams(-1, dp(44)).also { it.topMargin = dp(4) })
            return { v }
        }

        // --- INPUT
        head("INPUT")
        note("Where the sound comes from. A mic or a cable in the 3.5 mm jack shows as Headset; an audio interface as USB.")
        val names = listOf("Default (built-in mic)") + ins.map { devName(it) }
        var pick = ins.indexOfFirst { it.id == engine.input?.id } + 1
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        fun paintList() {
            list.removeAllViews()
            names.forEachIndexed { k, n ->
                val b = btn(n, 13f) { pick = k; paintList() }
                lit(b, k == pick, C.ORANGE)
                list.addView(b, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(4) })
            }
        }
        paintList()
        col.addView(list)
        note("Channel (for a stereo input such as a 2-input USB interface):")
        var ch = engine.inputChannel
        val chRow = row()
        val chBtns = ArrayList<Button>()
        listOf("MIX", "LEFT / 1", "RIGHT / 2").forEachIndexed { k, n ->
            val b = btn(n, 13f) { ch = k; chBtns.forEachIndexed { j, x -> lit(x, j == ch, C.ORANGE) } }
            chBtns.add(b)
            chRow.addView(b, lp(0, dp(44), 1f, if (k == 0) 0 else dp(4)))
        }
        chBtns.forEachIndexed { j, x -> lit(x, j == ch, C.ORANGE) }
        col.addView(chRow)
        val gain = slider(48, (engine.inputGainDb + 24).toInt()) { "Input gain: ${if (it >= 24) "+" else ""}${it - 24} dB" }
        note("Turn it down if the input light goes red while you play.")
        val raw = toggle("RAW INPUT (no phone voice processing)", engine.raw)
        note("On: the cleanest sound for instruments. Off: the phone's own noise reduction (for speech).")

        // --- TIMING
        head("TIMING (where a take lands)")
        val auto = toggle("AUTO ALIGN (from the device's own timing)", engine.autoAlign, C.GREEN)
        note("Auto measures the real delay of the speaker and the input on every take. Off: the manual offset below.")
        val lat = slider(400, engine.latencyMs) { "Manual offset: $it ms" }
        val fine = slider(200, engine.fineMs + 100) { "Fine adjust: ${if (it >= 100) "+" else ""}${it - 100} ms (later +, earlier -)" }
        if (engine.lastAlign.isNotEmpty()) note("Last take: ${engine.lastAlign}")
        val calRes = text("", 13f, C.YELLOW)
        val calBtn = btn("CALIBRATE", 14f) {}
        calBtn.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                return@setOnClickListener
            }
            if (engine.playing || engine.recording || busy) return@setOnClickListener
            busy = true
            calBtn.isEnabled = false
            calRes.text = "Listening to 8 clicks... keep quiet"
            Thread {
                val r = engine.calibrate()
                ui.post {
                    busy = false
                    calBtn.isEnabled = true
                    if (r == null) calRes.text = "No clicks heard. Turn the volume up, or connect the output to the input with a cable, and try again."
                    else {
                        fine.progress = (engine.fineMs + 100).coerceIn(0, 200)
                        prefs.edit().putInt("fine", engine.fineMs).apply()
                        calRes.text = "Done: fine adjust set to ${engine.fineMs} ms"
                    }
                }
            }.start()
        }
        col.addView(calBtn, LinearLayout.LayoutParams(-1, dp(48)).also { it.topMargin = dp(8) })
        note("Calibrate plays clicks and records them, then sets the fine adjust. The input must hear the output: the phone's speaker to its mic, or a cable from the output to the input.")
        col.addView(calRes)

        // --- OUTPUT
        head("OUTPUT")
        val lim = toggle("LIMITER on the master (no clipping)", engine.limiter)
        note("Now: " + routeName(engine.outRoute ?: defaultOut()) +
            "   Buffer: ${engine.bufferFrames} frames (${engine.bufferFrames * 1000 / maxOf(1, project.rate)} ms)   Dropouts: ${engine.underruns}")
        note("Speaker, 3.5 mm (to headphones or to a speaker / mixer with a cable), Bluetooth or USB: the phone picks the newest one plugged in. Bluetooth adds a big delay: use a cable for recording.")

        val sv = android.widget.ScrollView(this)
        sv.addView(col)
        AlertDialog.Builder(this).setTitle("Settings").setView(sv)
            .setPositiveButton("OK") { _, _ ->
                engine.input = if (pick <= 0) null else ins[pick - 1]
                engine.inputChannel = ch
                engine.inputGainDb = (gain.progress - 24).toFloat()
                engine.raw = raw()
                engine.autoAlign = auto()
                engine.latencyMs = lat.progress
                engine.fineMs = fine.progress - 100
                engine.limiter = lim()
                prefs.edit().putInt("inDev", engine.input?.id ?: -1).putInt("inCh", ch)
                    .putFloat("inGain", engine.inputGainDb).putBoolean("raw", engine.raw)
                    .putBoolean("autoAlign", engine.autoAlign).putInt("latency", engine.latencyMs)
                    .putInt("fine", engine.fineMs).putBoolean("limiter", engine.limiter).apply()
                refreshAll()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun devName(dv: AudioDeviceInfo): String {
        val kind = when (dv.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "3.5 mm (Headset)"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line in"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> "Input"
        }
        return "$kind: ${dv.productName}"
    }

    /** the output the phone uses now when nothing is playing (the newest one plugged in) */
    private fun defaultOut(): AudioDeviceInfo? {
        val outs = (getSystemService(AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val order = intArrayOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        for (t in order) outs.firstOrNull { it.type == t }?.let { return it }
        return null
    }

    private fun routeKind(dv: AudioDeviceInfo?): Int = when (dv?.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> 1
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 2
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 3
        else -> 0
    }

    private fun routeName(dv: AudioDeviceInfo?): String = when (routeKind(dv)) {
        1 -> "3.5 mm"
        2 -> "Bluetooth (delay!)"
        3 -> "USB"
        else -> "Speaker"
    }

    /** MON: hear the input while recording. A mic with the sound going to a speaker howls: ask first. */
    private fun toggleMonitor() {
        if (engine.monitor) {
            engine.monitor = false
            paint()
            return
        }
        val out = routeKind(engine.outRoute ?: defaultOut())
        val inKind = engine.input?.type
        val mic = inKind == null || inKind == AudioDeviceInfo.TYPE_BUILTIN_MIC
        val warn = when {
            out == 2 -> "The output is Bluetooth: you will hear yourself late (a big delay). Use wired headphones to monitor."
            mic && out == 0 -> "The sound goes to the phone's speaker and the mic hears it: it can howl (feedback). Plug in headphones first."
            out == 1 && mic -> "If the 3.5 mm output goes to a speaker in the same room as the mic, it can howl (feedback). With headphones it is fine."
            else -> null
        }
        if (warn == null) {
            engine.monitor = true
            paint()
            return
        }
        AlertDialog.Builder(this).setTitle("Monitor").setMessage(warn)
            .setPositiveButton("Monitor anyway") { _, _ -> engine.monitor = true; paint() }
            .setNegativeButton("Cancel", null).show()
    }

    /** a device plugged in or out: USB -> how to let the app have it; Bluetooth -> the delay */
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (!started) { started = true; return }        // (the first call lists what was already there)
            val usb = added.any { routeKind(it) == 3 }
            val bt = added.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && it.isSink }
            if (usb && !usbHintShown) {
                usbHintShown = true
                AlertDialog.Builder(this@MainActivity).setTitle("USB audio connected")
                    .setMessage("If the USB device is a sound card / interface, pick it as the input in Settings.\n\n" +
                        "If the phone sends all its sound to USB and you don't want that (for example while charging or " +
                        "connecting another device), turn on \"Disable USB audio routing\" in Developer options. " +
                        "(Developer options: Settings > About phone > tap Build number 7 times.)")
                    .setPositiveButton("Developer options") { _, _ ->
                        try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                        catch (e: Exception) { toast("Developer options are not turned on yet") }
                    }
                    .setNeutralButton("Settings") { _, _ -> settings() }
                    .setNegativeButton("OK", null).show()
            }
            if (bt) toast("Bluetooth output: it adds a delay. Use a cable or the phone's jack for recording.")
            if (engine.monitor && routeKind(defaultOut()) == 0) engine.monitor = false
            paint()
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (engine.input != null && removed.any { it.id == engine.input?.id }) {
                engine.input = null
                toast("The input was unplugged: back to the built-in mic")
            }
            if (engine.monitor && routeKind(defaultOut()) == 0) {      // headphones out, the speaker now: no howl
                engine.monitor = false
                toast("Monitor off (headphones unplugged)")
            }
            paint()
        }
    }
    private var started = false

    // ---------------- import / export ----------------

    private fun pickWav() {
        if (engine.playing) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "audio/*"
        startActivityForResult(i, 7)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (requestCode != 7 || resultCode != RESULT_OK) return
        val at = engine.pos
        val tr = selected
        busy = true
        toast("Importing…")
        Thread {
            val s = try { contentResolver.openInputStream(uri)?.use { Wav.readMono(it, project.rate) } } catch (e: Exception) { null }
            if (s != null) project.write(tr, at, s, s.size)
            ui.post {
                busy = false
                toast(if (s == null) "Not a WAV file this can read (WAV only in 0.1)" else "Imported into track ${tr + 1}")
                refreshAll()
            }
        }.start()
    }

    /** the mix and every stem as WAV into Music/MakerHip/16 Track/<song>/ */
    private fun exportAll() {
        if (engine.playing) return
        if (project.longest() == 0) { toast("Nothing recorded yet"); return }
        busy = true
        toast("Exporting…")
        Thread {
            val folder = "MakerHip/16 Track/${project.title}"
            var n = 0
            try {
                save("${project.title} - mix.wav", folder) { engine.mixdown(it) }
                n++
                for (i in 0 until Project.N) if (project.tracks[i].length > 0) {
                    save(String.format(Locale.US, "%02d %s.wav", i + 1, project.tracks[i].name.replace("/", "-")), folder) { engine.stem(i, it) }
                    n++
                }
            } catch (e: Exception) {
                ui.post { toast("Export failed: ${e.message}") }
            }
            ui.post {
                busy = false
                toast("$n files in Music/$folder")
            }
        }.start()
    }

    private fun save(name: String, folder: String, write: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues()
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$folder")
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("no storage")
            contentResolver.openOutputStream(uri)?.use { o ->
                val b = o.buffered()
                write(b)
                b.flush()
            }
        } else {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), folder)
            dir.mkdirs()
            FileOutputStream(File(dir, name)).buffered().use { write(it) }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
