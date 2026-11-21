package com.makerhip.track16

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private val ui = Handler(Looper.getMainLooper())
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
        build()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        project.save()
    }

    override fun onDestroy() {
        super.onDestroy()
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
        playBtn = btn("▶", 30f) { if (engine.playing) stopAll() else startPlay() }
        playBtn.setPadding(0, 0, 0, 0)
        head.addView(playBtn, lp(dp(72), dp(72)))
        recBtn = btn("●", 28f) { if (engine.recording) stopAll() else startRec() }
        recBtn.setPadding(0, 0, 0, 0)
        head.addView(recBtn, lp(dp(64), dp(64), 0f, dp(8)))
        head.addView(btn("|◀", 18f) { seek(0) }, lp(dp(52), dp(52), 0f, dp(8)))
        val tc = LinearLayout(this)
        tc.orientation = LinearLayout.VERTICAL
        titleText = text(project.title, 12f, C.DIM, true)
        titleText.setOnClickListener { projectsDialog() }
        timeText = text("0:00.0   1.1", 22f, C.TEXT, true)
        tc.addView(titleText)
        tc.addView(timeText)
        head.addView(tc, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, dp(14)))
        val bpm = row()
        bpm.background = bg(C.PANEL2, 12, C.LINE)
        bpm.addView(btn("−", 18f) { setBpm(project.bpm - 1) }.also { it.background = null })
        bpmText = text("${project.bpm}", 18f, C.TEXT, true)
        bpmText.setOnClickListener { bpmDialog() }
        bpm.addView(bpmText)
        bpm.addView(btn("+", 18f) { setBpm(project.bpm + 1) }.also { it.background = null })
        head.addView(bpm, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
        clickBtn = btn("Click") { engine.metronome = !engine.metronome; prefs.edit().putBoolean("click", engine.metronome).apply(); paint() }
        head.addView(clickBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48), 0f, dp(8)))
        countBtn = btn("Count-in") { engine.countIn = !engine.countIn; prefs.edit().putBoolean("countIn", engine.countIn).apply(); paint() }
        head.addView(countBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48), 0f, dp(6)))
        viewBtn = btn("MIXER") { showMixer = !showMixer; refreshAll() }
        head.addView(viewBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48), 0f, dp(8)))
        head.addView(btn("☰", 18f) { menu() }, lp(dp(52), dp(48), 0f, dp(6)))
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
        muteBtn = btn("M") { val t = project.tracks[selected]; t.mute = !t.mute; refreshAll() }
        soloBtn = btn("S") { val t = project.tracks[selected]; t.solo = !t.solo; refreshAll() }
        sel.addView(armBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44), 0f, dp(8)))
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
        mixerScroll.addView(mixerRow, FrameLayout.LayoutParams(-2, -1))
        mixerScroll.isFillViewport = true
        body.addView(mixerScroll, FrameLayout.LayoutParams(-1, -1))
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
        val a = btn("R", 12f) { toggleArm(i) }
        val m = btn("M", 12f) { val t = project.tracks[i]; t.mute = !t.mute; refreshAll() }
        val s = btn("S", 12f) { val t = project.tracks[i]; t.solo = !t.solo; refreshAll() }
        for (b in listOf(a, m, s)) {
            b.setPadding(0, 0, 0, 0)
            col.addView(b, LinearLayout.LayoutParams(-1, dp(34)).also { it.topMargin = dp(4) })
        }
        stripBtns.add(Triple(a, m, s))
        col.addView(text("PAN", 9f, C.DIM).also { it.gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) })
        val pan = SeekBar(this)
        pan.max = 200
        pan.progress = ((project.tracks[i].pan + 1f) * 100).toInt()
        pan.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, user: Boolean) {
                if (user) project.tracks[i].pan = if (kotlin.math.abs(v - 100) < 6) 0f else v / 100f - 1f
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        pan.tag = "pan$i"
        col.addView(pan, LinearLayout.LayoutParams(-1, dp(30)))
        val f = FaderView(this, C.TRACK[i]) { v -> project.tracks[i].gain = v }
        f.value = project.tracks[i].gain
        faders.add(f)
        col.addView(f, LinearLayout.LayoutParams(-1, 0, 1f))
        return col
    }

    // ---------------- refresh ----------------

    fun refreshAll() {
        titleText.text = project.title
        bpmText.text = " ${project.bpm} BPM "
        timeline.visibility = if (showMixer) View.GONE else View.VISIBLE
        mixerScroll.visibility = if (showMixer) View.VISIBLE else View.GONE
        viewBtn.text = if (showMixer) "TRACKS" else "MIXER"
        for (i in 0 until Project.N) {
            val t = project.tracks[i]
            faders[i].value = t.gain
            mixerRow.findViewWithTag<SeekBar>("pan$i")?.progress = ((t.pan + 1f) * 100).toInt()
            val (a, m, s) = stripBtns[i]
            lit(a, t.armed, C.RED); lit(m, t.mute, C.DIM); lit(s, t.solo, C.YELLOW)
        }
        paint()
        timeline.invalidate()
    }

    private fun paint() {
        val t = project.tracks[selected]
        selName.text = "${selected + 1}  ${t.name}"
        selName.setTextColor(C.TRACK[selected])
        lit(armBtn, t.armed, C.RED)
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
            inMeter.background = bg(if (ip > 0.95f) C.RED else if (ip > 0.02f) C.GREEN else C.PANEL2, 3)
            engine.inputPeak *= 0.8f
            engine.masterPeak *= 0.85f
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
                        "Record up to 16 tracks one at a time over each other, mix them, and export the mix and the stems as WAV.")
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

    private fun settings() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(10), dp(20), dp(10))
        val lat = text("", 15f, C.TEXT, true)
        col.addView(text("Recording offset: moves each take earlier to make up for the delay of this device.", 13f, C.DIM))
        col.addView(lat)
        val sb = SeekBar(this)
        sb.max = 400
        sb.progress = engine.latencyMs
        lat.text = "${engine.latencyMs} ms"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { lat.text = "$v ms" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(sb)
        col.addView(text("Input", 15f, C.TEXT, true).also { it.setPadding(0, dp(14), 0, 0) })
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
        val sv = android.widget.ScrollView(this)
        sv.addView(col)
        AlertDialog.Builder(this).setTitle("Settings").setView(sv)
            .setPositiveButton("OK") { _, _ ->
                engine.latencyMs = sb.progress
                prefs.edit().putInt("latency", sb.progress).apply()
                engine.input = if (pick <= 0) null else ins[pick - 1]
            }.setNegativeButton("Cancel", null).show()
    }

    private fun devName(dv: AudioDeviceInfo): String {
        val kind = when (dv.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> "Input"
        }
        return "$kind: ${dv.productName}"
    }

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
