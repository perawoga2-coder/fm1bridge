package com.example.fm1bridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        const val REQ_IMPORT = 10
        const val REQ_EXPORT = 11
        const val REQ_IMPORT_TRACK = 12
        const val PAD_COUNT = 8
        const val TRACKS = 4
    }

    // Widgets of one track card
    private class TrackUi {
        lateinit var name: TextView
        lateinit var src: TextView
        lateinit var steps: StepBarView
        lateinit var meter: MeterView
        lateinit var recBtn: Button
        lateinit var muteBtn: Button
        lateinit var soloBtn: Button
        lateinit var modeBtn: Button
        val beatBtns = arrayOfNulls<Button>(5)
        var file: File? = null
        var srcLabel = "Internal mic"
    }

    // ---------------- shared state ----------------

    private lateinit var audioManager: AudioManager
    private lateinit var samplesDir: File
    private lateinit var prefs: SharedPreferences
    private lateinit var tabViews: List<View>
    private lateinit var midiStatusText: TextView
    private lateinit var midiButton: Button
    private var pendingExportBytes: ByteArray? = null
    private var previewTrack: AudioTrack? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    // Mixer
    private val trackStates = Array(TRACKS) { TrackState(ENGINE_RATE) }
    private val engine = MixerEngine(ENGINE_RATE, trackStates)
    private val tui = Array(TRACKS) { TrackUi() }
    private val trackColors = intArrayOf(
        Color.parseColor("#FA5A1E"),
        Color.parseColor("#7A4DFF"),
        Color.parseColor("#2A5CFF"),
        Color.parseColor("#F5D018")
    )
    private val beatValues = listOf(1, 2, 4, 8, 16)
    private lateinit var playBtn: Button
    private lateinit var bpmText: TextView
    private lateinit var bpmBar: SeekBar
    private lateinit var masterMeter: MeterView
    private var lastPlayShown = -1
    private val taps = ArrayList<Long>()
    @Volatile private var clockMode = 0 // 0 = internal, 1 = send to FM-1, 2 = follow FM-1
    @Volatile private var clockDelayMs = 0
    @Volatile private var clockGen = 0

    // Recording input
    private var inputDevs: List<AudioDeviceInfo> = emptyList()
    private lateinit var gainText: TextView
    private lateinit var recMeter: MeterView
    private lateinit var clipWarn: TextView
    private lateinit var recStatus: TextView
    @Volatile private var recording = false
    @Volatile private var recTrack = -1
    @Volatile private var softGain = 1f
    @Volatile private var autoOn = true
    private var lastMeterNs = 0L
    private var pendingTrack = -1

    // Library
    private lateinit var listBox: LinearLayout
    private lateinit var searchBox: EditText

    // Editor
    private lateinit var edTitle: TextView
    private lateinit var edInfo: TextView
    private lateinit var wave: WaveformView
    private var edFile: File? = null
    private var edSample: Sample? = null
    private var edUndo: Sample? = null
    private var edPlay: AudioTrack? = null

    // Pads
    private val padTracks = arrayOfNulls<AudioTrack>(PAD_COUNT)
    private val padButtons = arrayOfNulls<Button>(PAD_COUNT)
    private lateinit var padInfo: TextView
    private var lastNoteUiNs = 0L

    // MIDI connection and parser
    private var midiDevice: MidiDevice? = null
    private var sendPort: MidiInputPort? = null
    private var recvPort: MidiOutputPort? = null
    private var midiRunning = 0
    private var midiData1 = -1
    private val tickIntervals = LongArray(24)
    private var ti = 0
    private var tiFilled = 0
    private var lastTick = 0L
    private var avgTickNs = 0L

    // ---------------- lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences("fm1bridge", Context.MODE_PRIVATE)
        samplesDir = File(filesDir, "samples")
        samplesDir.mkdirs()
        restoreState()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0E0E0E"))

        val tabBar = hbox()
        val tabNames = listOf("🎛 Tracks", "📚 Library", "✂ Edit", "🥁 Pads")
        tabNames.forEachIndexed { i, n ->
            tabBar.addView(btn(n) { showTab(i) }, weight())
        }
        root.addView(tabBar)

        val midiBar = hbox()
        midiBar.gravity = Gravity.CENTER_VERTICAL
        midiBar.setPadding(dp(12), 0, dp(12), 0)
        midiStatusText = label("MIDI: not connected", 14f)
        midiBar.addView(midiStatusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        midiButton = btn("Connect MIDI") { onMidiButton() }
        midiBar.addView(midiButton)
        root.addView(midiBar)

        val content = FrameLayout(this)
        tabViews = listOf(
            scroll(buildTracks()),
            scroll(buildLibrary()),
            scroll(buildEdit()),
            scroll(buildPads())
        )
        for (v in tabViews) content.addView(v)
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        reloadPads()
        for (i in 0 until TRACKS) refreshTrackUi(i)
        setBpm(engine.bpm.roundToInt())
        showTab(0)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private val uiTick = object : Runnable {
        override fun run() {
            updateLive()
            uiHandler.postDelayed(this, 50)
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(uiTick)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(uiTick)
        super.onPause()
    }

    override fun onDestroy() {
        recording = false
        engine.stop()
        clockGen++
        previewTrack?.release()
        edPlay?.release()
        for (t in padTracks) t?.release()
        disconnectMidi()
        super.onDestroy()
    }

    private fun showTab(i: Int) {
        tabViews.forEachIndexed { idx, v ->
            v.visibility = if (idx == i) View.VISIBLE else View.GONE
        }
        if (i == 0 && !recording) {
            scanInputs()
            for (k in 0 until TRACKS) refreshTrackUi(k)
        }
        if (i == 1) refreshLibrary()
        if (i == 2) refreshEditor()
    }

    // ---------------- small UI helpers ----------------

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun weight(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(2), dp(2), dp(2), dp(2))
        return lp
    }

    private fun hbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        return l
    }

    private fun styleBtn(b: Button, bg: String, fg: String) {
        b.setBackgroundColor(Color.parseColor(bg))
        b.setTextColor(Color.parseColor(fg))
    }

    private fun btn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        styleBtn(b, "#2F3133", "#FFFFFF")
        b.setOnClickListener { onClick() }
        return b
    }

    private fun setToggle(b: Button, on: Boolean, onBg: String, onFg: String) {
        if (on) styleBtn(b, onBg, onFg) else styleBtn(b, "#2F3133", "#FFFFFF")
    }

    private fun label(text: String, size: Float = 16f): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(Color.WHITE)
        return t
    }

    private fun column(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(14), dp(14), dp(14), dp(14))
        return l
    }

    private fun card(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#1B1B1B"))
        bg.cornerRadius = dp(14).toFloat()
        l.background = bg
        l.setPadding(dp(12), dp(12), dp(12), dp(12))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, dp(12))
        l.layoutParams = lp
        return l
    }

    private fun scroll(v: View): ScrollView {
        val s = ScrollView(this)
        s.addView(v)
        return s
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun seek(max: Int, value: Int, onChange: (Int) -> Unit): SeekBar {
        val s = SeekBar(this)
        s.max = max
        s.progress = value
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        return s
    }

    private fun safeName(s: String): String =
        s.trim().replace(Regex("[^\\p{L}\\p{M}\\p{N}._ -]"), "_").take(60)

    private fun listSamples(): List<File> =
        (samplesDir.listFiles() ?: arrayOf<File>())
            .filter { it.extension.lowercase(Locale.ROOT) == "wav" }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    private fun uniqueFile(base: String): File {
        var t = File(samplesDir, "$base.wav")
        var n = 1
        while (t.exists()) {
            t = File(samplesDir, "${base}_$n.wav")
            n++
        }
        return t
    }

    // ---------------- audio playback helpers ----------------

    private fun makeTrackFromShorts(d: ShortArray, rate: Int, ch: Int): AudioTrack? {
        if (d.isEmpty()) return null
        return try {
            val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(d.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(d, 0, d.size)
            t
        } catch (e: Exception) {
            null
        }
    }

    private fun makeTrack(f: File): AudioTrack? {
        val s = loadSample(f) ?: return null
        return makeTrackFromShorts(s.data, s.rate, s.channels)
    }

    private fun restart(t: AudioTrack?) {
        if (t == null) return
        try {
            t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: Exception) {
        }
    }

    // =====================================================================
    // TRACKS (the mixer)
    // =====================================================================

    private fun buildTracks(): View {
        val col = column()
        col.addView(buildTransportCard())
        col.addView(buildInputCard())
        for (i in 0 until TRACKS) col.addView(buildTrackCard(i))
        return col
    }

    private fun buildTransportCard(): View {
        val c = card()

        val row1 = hbox()
        row1.gravity = Gravity.CENTER_VERTICAL
        playBtn = Button(this)
        playBtn.textSize = 28f
        playBtn.isAllCaps = false
        playBtn.setOnClickListener { togglePlay() }
        row1.addView(playBtn, LinearLayout.LayoutParams(dp(84), dp(64)))

        val bpmBox = hbox()
        bpmBox.gravity = Gravity.CENTER_VERTICAL
        bpmBox.addView(btn("−") { setBpm(engine.bpm.roundToInt() - 1) }, LinearLayout.LayoutParams(dp(52), dp(48)))
        bpmText = label("96 BPM", 26f)
        bpmText.gravity = Gravity.CENTER
        bpmBox.addView(bpmText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bpmBox.addView(btn("+") { setBpm(engine.bpm.roundToInt() + 1) }, LinearLayout.LayoutParams(dp(52), dp(48)))
        bpmBox.addView(btn("TAP") { tapTempo() }, LinearLayout.LayoutParams(dp(64), dp(48)))
        row1.addView(bpmBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(row1)

        bpmBar = SeekBar(this)
        bpmBar.max = 200
        bpmBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) setBpm(p + 40)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                refreshHitBars()
            }
        })
        c.addView(bpmBar)

        val mrow = hbox()
        mrow.gravity = Gravity.CENTER_VERTICAL
        mrow.addView(label("MASTER", 13f), LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT))
        val masterBar = seek(100, (engine.masterVol * 100).toInt()) {
            engine.masterVol = it / 100f
            prefs.edit().putFloat("master", it / 100f).apply()
        }
        mrow.addView(masterBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        masterMeter = MeterView(this)
        mrow.addView(masterMeter, LinearLayout.LayoutParams(dp(90), dp(14)))
        c.addView(mrow)

        c.addView(label("Tempo clock", 13f))
        val rg = RadioGroup(this)
        rg.orientation = LinearLayout.VERTICAL
        val clockLabels = listOf(
            "Internal (this app sets the tempo)",
            "Send to FM-1 (the FM-1 follows this app)",
            "Follow FM-1 (this app follows the FM-1)"
        )
        val ids = IntArray(3)
        for (k in 0 until 3) {
            val rb = RadioButton(this)
            rb.text = clockLabels[k]
            rb.setTextColor(Color.WHITE)
            rb.id = View.generateViewId()
            ids[k] = rb.id
            rg.addView(rb)
        }
        rg.check(ids[0])
        rg.setOnCheckedChangeListener { _, id ->
            val m = ids.indexOf(id)
            clockMode = if (m < 0) 0 else m
            if (clockMode == 1 && engine.playing) startClockOut()
        }
        c.addView(rg)

        val delayText = label("Clock delay: 0 ms (only for Send to FM-1)", 12f)
        c.addView(delayText)
        c.addView(seek(300, 0) {
            clockDelayMs = it
            delayText.text = "Clock delay: $it ms (only for Send to FM-1)"
        })
        return c
    }

    private fun buildInputCard(): View {
        val c = card()
        c.addView(label("🎤 Recording", 16f))

        val tip = label(
            "Tip: before recording, lower the volume on your instrument a little " +
                "so the signal does not clip. Choose the input of each track with its Input button.", 13f
        )
        tip.setBackgroundColor(Color.parseColor("#FFF4CC"))
        tip.setTextColor(Color.parseColor("#5A4500"))
        tip.setPadding(dp(10), dp(8), dp(10), dp(8))
        c.addView(tip)

        gainText = label("Input gain: 100%", 13f)
        c.addView(gainText)
        c.addView(seek(200, 100) {
            gainText.text = "Input gain: $it%"
            softGain = it / 100f
        })

        val auto = CheckBox(this)
        auto.text = "Auto level after recording (target 70%)"
        auto.setTextColor(Color.WHITE)
        auto.isChecked = true
        auto.setOnCheckedChangeListener { _, checked -> autoOn = checked }
        c.addView(auto)

        recMeter = MeterView(this)
        c.addView(recMeter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)))

        clipWarn = label("", 14f)
        clipWarn.setTextColor(Color.parseColor("#FF5252"))
        clipWarn.setTypeface(clipWarn.typeface, Typeface.BOLD)
        clipWarn.visibility = View.GONE
        c.addView(clipWarn)

        recStatus = label("Press ● Rec on a track to record into it.", 13f)
        c.addView(recStatus)
        return c
    }

    private fun buildTrackCard(i: Int): View {
        val ui = tui[i]
        val t = trackStates[i]
        val col = trackColors[i]
        val c = card()

        // Header: big colored number, name, level meter
        val head = hbox()
        head.gravity = Gravity.CENTER_VERTICAL
        val num = TextView(this)
        num.text = "${i + 1}"
        num.textSize = 30f
        num.setTypeface(num.typeface, Typeface.BOLD)
        num.setTextColor(Color.BLACK)
        num.gravity = Gravity.CENTER
        num.setBackgroundColor(col)
        head.addView(num, LinearLayout.LayoutParams(dp(56), dp(56)))

        val names = LinearLayout(this)
        names.orientation = LinearLayout.VERTICAL
        names.setPadding(dp(12), 0, dp(8), 0)
        ui.name = label("", 17f)
        ui.src = label("", 12f)
        ui.src.setTextColor(Color.parseColor("#9AA0A6"))
        names.addView(ui.name)
        names.addView(ui.src)
        head.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        ui.meter = MeterView(this)
        head.addView(ui.meter, LinearLayout.LayoutParams(dp(90), dp(14)))
        c.addView(head)

        // 16 step bars with a moving playhead
        ui.steps = StepBarView(this)
        ui.steps.color = col
        val stepLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
        stepLp.topMargin = dp(10)
        stepLp.bottomMargin = dp(6)
        c.addView(ui.steps, stepLp)

        // Actions
        val acts = hbox()
        ui.recBtn = btn("● Rec") { onRecButton(i) }
        acts.addView(ui.recBtn, weight())
        acts.addView(btn("📂 Load") { loadDialog(i) }, weight())
        acts.addView(btn("🎤 Input") { sourceDialog(i) }, weight())
        acts.addView(btn("✂ Edit") { editTrack(i) }, weight())
        acts.addView(btn("✕ Clear") { clearTrack(i) }, weight())
        c.addView(acts)

        // Mute, solo, volume, pan
        val mix = hbox()
        mix.gravity = Gravity.CENTER_VERTICAL
        ui.muteBtn = btn("M") {
            t.mute = !t.mute
            refreshTrackUi(i)
            saveTrackPrefs(i)
        }
        ui.soloBtn = btn("S") {
            t.solo = !t.solo
            refreshTrackUi(i)
            saveTrackPrefs(i)
        }
        mix.addView(ui.muteBtn, LinearLayout.LayoutParams(dp(56), dp(48)))
        mix.addView(ui.soloBtn, LinearLayout.LayoutParams(dp(56), dp(48)))
        val faders = LinearLayout(this)
        faders.orientation = LinearLayout.VERTICAL
        faders.setPadding(dp(8), 0, 0, 0)
        faders.addView(label("Volume", 12f))
        faders.addView(seek(100, (t.volume * 100).toInt()) {
            t.volume = it / 100f
            saveTrackPrefs(i)
        })
        faders.addView(label("Pan (left - right)", 12f))
        faders.addView(seek(200, ((t.pan + 1f) * 100).toInt()) {
            t.pan = (it - 100) / 100f
            saveTrackPrefs(i)
        })
        mix.addView(faders, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(mix)

        // Loop length in beats and loop/hit mode
        c.addView(label("Loop length (beats)", 12f))
        val bm = hbox()
        for (k in 0 until 5) {
            val b = beatValues[k]
            val bb = btn("$b") {
                t.beats = b
                refreshTrackUi(i)
                saveTrackPrefs(i)
            }
            ui.beatBtns[k] = bb
            bm.addView(bb, weight())
        }
        ui.modeBtn = btn("⟲ LOOP") {
            t.fit = !t.fit
            refreshTrackUi(i)
            saveTrackPrefs(i)
        }
        bm.addView(ui.modeBtn, weight())
        c.addView(bm)

        // Effects: two slots, any effect in each
        val fxPanel = LinearLayout(this)
        fxPanel.orientation = LinearLayout.VERTICAL
        fxPanel.visibility = View.GONE
        for (s in 0 until 2) {
            fxPanel.addView(label("Effect ${s + 1}", 12f))
            val line = hbox()
            line.gravity = Gravity.CENTER_VERTICAL
            val sp = Spinner(this)
            val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, FX_NAMES)
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            sp.adapter = ad
            sp.setSelection(t.fx[s].type)
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val f = t.fx[s]
                    if (f.type != pos) {
                        f.type = pos
                        f.reset()
                        saveTrackPrefs(i)
                    }
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            line.addView(sp, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT))
            val amt = seek(100, (t.fx[s].amount * 100).toInt()) {
                t.fx[s].amount = it / 100f
                saveTrackPrefs(i)
            }
            line.addView(amt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fxPanel.addView(line)
        }
        val fxBtn = btn("🎛 Effects  ▾") {
            fxPanel.visibility = if (fxPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        c.addView(fxBtn)
        c.addView(fxPanel)
        return c
    }

    private fun refreshTrackUi(i: Int) {
        val ui = tui[i]
        val t = trackStates[i]
        val f = ui.file
        ui.name.text = if (f == null) "(empty) record or load a sound" else f.nameWithoutExtension
        ui.src.text = "🎤 " + ui.srcLabel
        ui.recBtn.text = if (recording && recTrack == i) "■ Stop" else "● Rec"
        if (recording && recTrack == i) styleBtn(ui.recBtn, "#C62828", "#FFFFFF") else styleBtn(ui.recBtn, "#2F3133", "#FF6B6B")
        setToggle(ui.muteBtn, t.mute, "#E53935", "#FFFFFF")
        setToggle(ui.soloBtn, t.solo, "#F9A825", "#000000")
        for (k in 0 until 5) {
            val b = ui.beatBtns[k]
            if (b != null) setToggle(b, beatValues[k] == t.beats, trackColorHex(i), "#000000")
        }
        ui.modeBtn.text = if (t.fit) "⟲ LOOP" else "• HIT"
        ui.steps.bars = computeBars(i)
    }

    private fun trackColorHex(i: Int): String = String.format("#%06X", trackColors[i] and 0xFFFFFF)

    private fun computeBars(i: Int): FloatArray {
        val out = FloatArray(16)
        val t = trackStates[i]
        val d = t.data ?: return out
        val total = if (t.fit) d.size.toDouble() else t.beats * 60.0 / engine.bpm * ENGINE_RATE
        for (b in 0 until 16) {
            val a = (b / 16.0 * total).toInt()
            val e = ((b + 1) / 16.0 * total).toInt().coerceAtLeast(a + 1)
            val end = minOf(e, d.size)
            var m = 0
            var k = a
            while (k < end) {
                val v = abs(d[k].toInt())
                if (v > m) m = v
                k++
            }
            out[b] = (m / 32768f * 1.6f).coerceAtMost(1f)
        }
        return out
    }

    private fun refreshHitBars() {
        for (i in 0 until TRACKS) {
            if (!trackStates[i].fit) tui[i].steps.bars = computeBars(i)
        }
    }

    private fun updateLive() {
        val playing = engine.playing
        for (i in 0 until TRACKS) {
            val ui = tui[i]
            val t = trackStates[i]
            ui.meter.level = t.peak
            ui.steps.active =
                if (playing && t.data != null) (t.loopPhase * 16f).toInt().coerceIn(0, 15) else -1
        }
        masterMeter.level = engine.masterPeak
        if (clockMode == 2) bpmText.text = "${engine.bpm.roundToInt()} BPM"
        updatePlayButton()
    }

    private fun updatePlayButton() {
        val s = if (engine.playing) 1 else 0
        if (s == lastPlayShown) return
        lastPlayShown = s
        if (s == 1) {
            playBtn.text = "■"
            styleBtn(playBtn, "#C62828", "#FFFFFF")
        } else {
            playBtn.text = "▶"
            styleBtn(playBtn, "#1FA55B", "#FFFFFF")
        }
    }

    private fun setBpm(v: Int) {
        val b = v.coerceIn(40, 240)
        engine.bpm = b.toFloat()
        bpmText.text = "$b BPM"
        bpmBar.progress = b - 40
        prefs.edit().putFloat("bpm", b.toFloat()).apply()
    }

    private fun tapTempo() {
        val now = System.currentTimeMillis()
        if (taps.isNotEmpty() && now - taps[taps.size - 1] > 2000L) taps.clear()
        taps.add(now)
        if (taps.size > 5) taps.removeAt(0)
        if (taps.size >= 2) {
            val avg = (taps[taps.size - 1] - taps[0]).toDouble() / (taps.size - 1)
            setBpm((60000.0 / avg).roundToInt())
            refreshHitBars()
        }
    }

    private fun togglePlay() {
        if (engine.playing) stopAll() else startAll()
    }

    private fun startAll() {
        engine.start()
        if (clockMode == 1) startClockOut()
        updatePlayButton()
    }

    private fun stopAll() {
        engine.stop()
        updatePlayButton()
    }

    // The app is the clock master: send MIDI Start and 24 clock ticks per beat to the FM-1
    private fun startClockOut() {
        clockGen++
        val g = clockGen
        val th = Thread {
            val clk = byteArrayOf(0xF8.toByte())
            val startMsg = byteArrayOf(0xFA.toByte())
            val stopMsg = byteArrayOf(0xFC.toByte())
            var tickTime = engine.startNs + clockDelayMs * 1_000_000L
            var k = 0L
            while (engine.playing && g == clockGen && clockMode == 1) {
                val tickNs = (60_000_000_000.0 / (engine.bpm * 24.0)).toLong()
                while (engine.playing && g == clockGen) {
                    val d = tickTime - System.nanoTime()
                    if (d <= 0L) break
                    if (d > 2_000_000L) LockSupport.parkNanos(d - 1_000_000L) else Thread.yield()
                }
                if (!(engine.playing && g == clockGen && clockMode == 1)) break
                if (k == 0L) sendMidi(startMsg)
                sendMidi(clk)
                k++
                tickTime += tickNs
            }
            sendMidi(stopMsg)
        }
        th.isDaemon = true
        th.start()
    }

    // ---------------- loading sounds into tracks ----------------

    private fun loadTrackData(i: Int, f: File): Boolean {
        val s = loadSample(f) ?: return false
        val mono = mixToMono(s.data, s.channels)
        trackStates[i].data = resampleMono(mono, s.rate, ENGINE_RATE)
        tui[i].file = f
        return true
    }

    // Puts a sound on a track. With autoSetup the loop length is chosen from the sound length.
    private fun assignFileToTrack(i: Int, f: File, autoSetup: Boolean) {
        if (!loadTrackData(i, f)) {
            toast("Cannot read this file")
            return
        }
        val t = trackStates[i]
        if (autoSetup) {
            val d = t.data
            if (d != null) {
                val beatSec = 60.0 / engine.bpm
                val ratio = (d.size.toDouble() / ENGINE_RATE) / beatSec
                if (ratio < 0.75) {
                    t.fit = false
                    t.beats = 1
                } else {
                    t.fit = true
                    var best = 4
                    var bestErr = Double.MAX_VALUE
                    for (b in beatValues) {
                        val err = abs(ln(ratio / b))
                        if (err < bestErr) {
                            bestErr = err
                            best = b
                        }
                    }
                    t.beats = best
                }
            }
        }
        saveTrackPrefs(i)
        refreshTrackUi(i)
    }

    private fun clearTrack(i: Int) {
        trackStates[i].data = null
        tui[i].file = null
        saveTrackPrefs(i)
        refreshTrackUi(i)
    }

    private fun editTrack(i: Int) {
        val f = tui[i].file
        if (f == null) {
            toast("This track is empty")
            return
        }
        openEditor(f)
    }

    private fun loadDialog(i: Int) {
        val files = listSamples()
        val items = (listOf("📁 Import a WAV file from the device...") + files.map { it.name }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Load into track ${i + 1}")
            .setItems(items) { _, which ->
                if (which == 0) {
                    pendingTrack = i
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    intent.type = "*/*"
                    startActivityForResult(intent, REQ_IMPORT_TRACK)
                } else {
                    assignFileToTrack(i, files[which - 1], true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toTrackDialog(f: File) {
        val items = arrayOf("Track 1", "Track 2", "Track 3", "Track 4")
        AlertDialog.Builder(this)
            .setTitle("Put ${f.name} on...")
            .setItems(items) { _, which ->
                assignFileToTrack(which, f, true)
                showTab(0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sourceDialog(i: Int) {
        scanInputs()
        val labels = inputDevs.map { inputLabel(it) }
        if (labels.isEmpty()) {
            toast("No input found")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Input for track ${i + 1}")
            .setItems(labels.toTypedArray()) { _, which ->
                tui[i].srcLabel = labels[which]
                saveTrackPrefs(i)
                refreshTrackUi(i)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // After a file was edited and replaced, reload it on the tracks that use it
    private fun reloadTracksUsing(f: File) {
        for (i in 0 until TRACKS) {
            val tf = tui[i].file
            if (tf != null && tf.name == f.name) {
                loadTrackData(i, f)
                refreshTrackUi(i)
            }
        }
    }

    // ---------------- saving and restoring the mixer ----------------

    private fun saveTrackPrefs(i: Int) {
        val t = trackStates[i]
        val ed = prefs.edit()
        ed.putString("t$i.file", tui[i].file?.name ?: "")
        ed.putString("t$i.src", tui[i].srcLabel)
        ed.putInt("t$i.beats", t.beats)
        ed.putBoolean("t$i.fit", t.fit)
        ed.putFloat("t$i.vol", t.volume)
        ed.putFloat("t$i.pan", t.pan)
        ed.putBoolean("t$i.mute", t.mute)
        ed.putBoolean("t$i.solo", t.solo)
        for (s in 0 until 2) {
            ed.putInt("t$i.fx$s.type", t.fx[s].type)
            ed.putFloat("t$i.fx$s.amt", t.fx[s].amount)
        }
        ed.apply()
    }

    private fun restoreState() {
        engine.bpm = prefs.getFloat("bpm", 96f)
        engine.masterVol = prefs.getFloat("master", 0.8f)
        for (i in 0 until TRACKS) {
            val t = trackStates[i]
            t.beats = prefs.getInt("t$i.beats", 4)
            t.fit = prefs.getBoolean("t$i.fit", true)
            t.volume = prefs.getFloat("t$i.vol", 0.8f)
            t.pan = prefs.getFloat("t$i.pan", 0f)
            t.mute = prefs.getBoolean("t$i.mute", false)
            t.solo = prefs.getBoolean("t$i.solo", false)
            for (s in 0 until 2) {
                t.fx[s].type = prefs.getInt("t$i.fx$s.type", 0)
                t.fx[s].amount = prefs.getFloat("t$i.fx$s.amt", 0.5f)
            }
            tui[i].srcLabel = prefs.getString("t$i.src", "Internal mic") ?: "Internal mic"
            val fname = prefs.getString("t$i.file", "") ?: ""
            if (fname.isNotEmpty()) {
                val f = File(samplesDir, fname)
                if (f.exists()) loadTrackData(i, f)
            }
        }
    }

    // ---------------- recording into a track ----------------

    private fun inputLabel(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Internal mic"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB: ${d.productName}"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "3.5 mm headset input"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line in"
        else -> "Other: ${d.productName}"
    }

    private fun scanInputs() {
        val allowed = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG
        )
        inputDevs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type in allowed }
            .sortedBy { if (it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) 0 else 1 }
    }

    private fun onRecButton(i: Int) {
        if (recording) {
            if (recTrack == i) recording = false else toast("Stop the current recording first")
            return
        }
        startRecording(i)
    }

    private fun startRecording(trackIdx: Int) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recStatus.text = "Microphone permission needed"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        scanInputs()
        val wanted = tui[trackIdx].srcLabel
        val dev = inputDevs.firstOrNull { inputLabel(it) == wanted }
            ?: inputDevs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val isUsbLike = dev != null && (
            dev.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_LINE_ANALOG
            )
        val pref = if (isUsbLike) 48000 else 44100
        val rates = dev?.sampleRates ?: IntArray(0)
        val rate = when {
            rates.isEmpty() || pref in rates -> pref
            44100 in rates -> 44100
            48000 in rates -> 48000
            else -> rates.maxOrNull() ?: 44100
        }
        val chs = dev?.channelCounts ?: IntArray(0)
        val stereo = !(chs.isEmpty() || 1 in chs)
        val mask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val nch = if (stereo) 2 else 1

        val minBuf = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            recStatus.text = "This input does not support recording at $rate Hz"
            return
        }
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        } catch (e: Exception) {
            recStatus.text = "Cannot open input: ${e.message}"
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            recStatus.text = "Input could not be opened"
            return
        }
        if (dev != null) rec.setPreferredDevice(dev)

        val pcm = ByteArrayOutputStream()
        val maxBytes = 30 * rate * 2 * nch
        recTrack = trackIdx
        recording = true
        recStatus.text = "Recording track ${trackIdx + 1}... (max 30 s)"
        clipWarn.visibility = View.GONE
        refreshTrackUi(trackIdx)

        Thread {
            val buf = ByteArray(minBuf)
            var rawPeakAll = 0
            var clipHit = false
            try {
                rec.startRecording()
                while (recording && pcm.size() < maxBytes) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    val rawPeak = peakOf(buf, n)
                    if (rawPeak > rawPeakAll) rawPeakAll = rawPeak
                    if (rawPeak >= 32000) clipHit = true
                    val g = softGain
                    if (g != 1f) scalePcm(buf, n, g)
                    pcm.write(buf, 0, n)
                    val now = System.nanoTime()
                    if (now - lastMeterNs > 60_000_000L) {
                        lastMeterNs = now
                        val shown = peakOf(buf, n) / 32768f
                        val showClip = clipHit
                        val secs = pcm.size().toDouble() / (rate * 2 * nch)
                        runOnUiThread {
                            recMeter.level = shown
                            recStatus.text = String.format(
                                Locale.US, "Recording track %d... %.1f s (max 30 s)", trackIdx + 1, secs
                            )
                            if (showClip) {
                                clipWarn.text = "CLIPPING - lower the volume on your instrument and record again."
                                clipWarn.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
            try {
                rec.stop()
            } catch (e: Exception) {
            }
            rec.release()
            recording = false
            finishRecording(trackIdx, pcm.toByteArray(), rate, nch, rawPeakAll)
        }.start()
    }

    private fun finishRecording(trackIdx: Int, data: ByteArray, rate: Int, nch: Int, rawPeak: Int) {
        if (data.isEmpty()) {
            runOnUiThread {
                recMeter.level = 0f
                recStatus.text = "Nothing was recorded"
                recTrack = -1
                refreshTrackUi(trackIdx)
            }
            return
        }
        if (autoOn) {
            val p = peakOf(data, data.size)
            if (p > 0) scalePcm(data, data.size, 0.7f * 32768f / p)
        }
        val name = "Rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = uniqueFile(name)
        try {
            writeWav(file, data, rate, nch)
        } catch (e: Exception) {
            runOnUiThread {
                recStatus.text = "Could not save: ${e.message}"
                recTrack = -1
                refreshTrackUi(trackIdx)
            }
            return
        }
        val finalPeak = peakOf(data, data.size) * 100 / 32768
        val secs = data.size.toDouble() / (rate * nch * 2)
        val clipped = rawPeak >= 32000
        runOnUiThread {
            recMeter.level = 0f
            var msg = String.format(
                Locale.US, "Saved %s on track %d - %.1f s, peak %d%%", file.name, trackIdx + 1, secs, finalPeak
            )
            if (clipped) {
                msg += "\nThe input was clipping. Lower the volume on your instrument " +
                    "and record again for a clean sound."
            }
            recStatus.text = msg
            recTrack = -1
            assignFileToTrack(trackIdx, file, true)
        }
    }

    // =====================================================================
    // LIBRARY
    // =====================================================================

    private fun buildLibrary(): View {
        val col = column()
        col.addView(label("📚 Sample library", 20f))
        col.addView(btn("Import WAV file") { startImport() })
        searchBox = EditText(this)
        searchBox.hint = "Search by name or tag..."
        searchBox.setHintTextColor(Color.GRAY)
        searchBox.setTextColor(Color.WHITE)
        searchBox.setSingleLine(true)
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                refreshLibrary()
            }

            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        col.addView(searchBox)
        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        col.addView(listBox)
        return col
    }

    private fun tagOf(f: File): String = prefs.getString("tag:" + f.name, "") ?: ""

    private fun refreshLibrary() {
        listBox.removeAllViews()
        val q = searchBox.text.toString().trim().lowercase(Locale.ROOT)
        val files = listSamples()
            .filter {
                it.name.lowercase(Locale.ROOT).contains(q) ||
                    tagOf(it).lowercase(Locale.ROOT).contains(q)
            }
            .sortedByDescending { it.lastModified() }
        if (files.isEmpty()) {
            listBox.addView(label("No samples yet. Record or import one."))
            return
        }
        for (f in files) listBox.addView(sampleRow(f))
    }

    private fun sampleRow(f: File): View {
        val box = card()
        val name = label(f.name, 17f)
        name.setTypeface(name.typeface, Typeface.BOLD)
        box.addView(name)
        val info = label(describe(f), 13f)
        info.setTextColor(Color.parseColor("#9AA0A6"))
        box.addView(info)
        val tag = tagOf(f)
        if (tag.isNotEmpty()) box.addView(label("Tag: $tag", 13f))

        val a1 = hbox()
        a1.addView(btn("▶ Play") { preview(f) }, weight())
        a1.addView(btn("🎛 To track") { toTrackDialog(f) }, weight())
        a1.addView(btn("✂ Edit") { openEditor(f) }, weight())
        box.addView(a1)

        val a2 = hbox()
        a2.addView(btn("Rename") { renameDialog(f) }, weight())
        a2.addView(btn("Tag") { tagDialog(f) }, weight())
        a2.addView(btn("Delete") { deleteDialog(f) }, weight())
        box.addView(a2)

        val a3 = hbox()
        a3.addView(btn("Export") { exportOriginal(f) }, weight())
        a3.addView(btn("Export for FM-1") { fm1ExportDialog(f) }, weight())
        box.addView(a3)
        return box
    }

    private fun readInfo(f: File): WavInfo? {
        return try {
            val buf = ByteArray(65536)
            val n = f.inputStream().use { it.read(buf) }
            if (n <= 0) null else parseWav(buf.copyOf(n), f.length())
        } catch (e: Exception) {
            null
        }
    }

    private fun describe(f: File): String {
        val info = readInfo(f) ?: return "Unsupported file"
        val secs = info.dataLen.toDouble() / (info.sampleRate * info.channels * 2)
        val ch = if (info.channels == 1) "mono" else "stereo"
        val date = SimpleDateFormat("d MMM yyyy HH:mm", Locale.US).format(Date(f.lastModified()))
        return String.format(
            Locale.US, "%.1f kHz · %s · %.2f s · %d KB · %s",
            info.sampleRate / 1000.0, ch, secs, f.length() / 1024, date
        )
    }

    private fun preview(f: File) {
        previewTrack?.release()
        previewTrack = null
        val t = makeTrack(f)
        if (t == null) {
            toast("Cannot play this file")
            return
        }
        t.play()
        previewTrack = t
    }

    private fun renameDialog(f: File) {
        val edit = EditText(this)
        edit.setText(f.nameWithoutExtension)
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(edit)
            .setPositiveButton("OK") { _, _ ->
                val n = safeName(edit.text.toString())
                if (n.isNotEmpty()) {
                    val target = File(samplesDir, "$n.wav")
                    if (target.exists()) {
                        toast("That name already exists")
                    } else if (f.renameTo(target)) {
                        moveRefs(f.name, target)
                        refreshLibrary()
                    } else {
                        toast("Could not rename")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun tagDialog(f: File) {
        val edit = EditText(this)
        edit.setText(tagOf(f))
        edit.hint = "e.g. vocal, kick, loop"
        AlertDialog.Builder(this)
            .setTitle("Tag for ${f.name}")
            .setView(edit)
            .setPositiveButton("OK") { _, _ ->
                prefs.edit().putString("tag:" + f.name, edit.text.toString().trim()).apply()
                refreshLibrary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteDialog(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Delete ${f.name}? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                f.delete()
                dropRefs(f.name)
                refreshLibrary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Keep tags, pads and tracks in sync when a file is renamed or deleted
    private fun moveRefs(oldName: String, target: File) {
        val newName = target.name
        val ed = prefs.edit()
        val tag = prefs.getString("tag:$oldName", null)
        if (tag != null) {
            ed.putString("tag:$newName", tag)
            ed.remove("tag:$oldName")
        }
        for (i in 0 until PAD_COUNT) {
            if (prefs.getString("pad$i", "") == oldName) ed.putString("pad$i", newName)
        }
        ed.apply()
        for (i in 0 until TRACKS) {
            val tf = tui[i].file
            if (tf != null && tf.name == oldName) {
                tui[i].file = target
                saveTrackPrefs(i)
            }
        }
        reloadPads()
    }

    private fun dropRefs(name: String) {
        val ed = prefs.edit()
        ed.remove("tag:$name")
        for (i in 0 until PAD_COUNT) {
            if (prefs.getString("pad$i", "") == name) ed.remove("pad$i")
        }
        ed.apply()
        for (i in 0 until TRACKS) {
            val tf = tui[i].file
            if (tf != null && tf.name == name) clearTrack(i)
        }
        reloadPads()
    }

    // ---------------- import and export ----------------

    private fun startImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, REQ_IMPORT)
    }

    private fun exportOriginal(f: File) {
        try {
            startExportBytes(f.readBytes(), f.name)
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    private fun startExportBytes(bytes: ByteArray, name: String) {
        pendingExportBytes = bytes
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "audio/wav"
        i.putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun fm1ExportDialog(f: File) {
        val s = loadSample(f)
        if (s == null) {
            toast("Cannot read this file")
            return
        }
        val items = FM1_PRESETS.map {
            val size = fm1SizeBytes(s.frames, s.rate, it.rate)
            val fits = if (size <= FM1_BUDGET_BYTES) "fits" else "too big"
            "${it.label}: ${size / 1024} KB ($fits in 496 KB)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Export for FM-1")
            .setItems(items) { _, which ->
                val p = FM1_PRESETS[which]
                val data = convertForFm1(s, p.rate)
                startExportBytes(data, "${f.nameWithoutExtension}_${p.rate / 1000}k.wav")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_IMPORT -> importUri(uri)
            REQ_EXPORT -> exportTo(uri)
            REQ_IMPORT_TRACK -> {
                val f = importUri(uri)
                if (f != null && pendingTrack in 0 until TRACKS) assignFileToTrack(pendingTrack, f, true)
            }
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i)
            }
        } catch (e: Exception) {
        }
        return "Imported.wav"
    }

    private fun importUri(uri: Uri): File? {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.size > 20 * 1024 * 1024) {
                toast("File unreadable or too large (max 20 MB)")
                return null
            }
            if (parseWav(bytes, bytes.size.toLong()) == null) {
                toast("Only 16-bit PCM WAV files are supported for now")
                return null
            }
            var base = safeName(displayName(uri).removeSuffix(".wav").removeSuffix(".WAV"))
            if (base.isEmpty()) base = "Imported"
            val target = uniqueFile(base)
            target.writeBytes(bytes)
            refreshLibrary()
            toast("Imported ${target.name}")
            return target
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
            return null
        }
    }

    private fun exportTo(uri: Uri) {
        val bytes = pendingExportBytes ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            toast("Exported")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    // =====================================================================
    // EDIT
    // =====================================================================

    private fun buildEdit(): View {
        val col = column()
        col.addView(label("✂ Edit", 20f))
        edTitle = label("", 15f)
        col.addView(edTitle)

        wave = WaveformView(this)
        col.addView(wave, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)))
        wave.onSelection = { _, _ -> updateEdInfo() }

        edInfo = label("", 13f)
        col.addView(edInfo)

        val r1 = hbox()
        r1.addView(btn("▶ Play selection") { edPlaySel() }, weight())
        r1.addView(btn("■ Stop") { edPlay?.stop() }, weight())
        r1.addView(btn("↶ Undo") { edDoUndo() }, weight())
        col.addView(r1)

        val r2 = hbox()
        r2.addView(btn("Trim") { edTrim() }, weight())
        r2.addView(btn("Normalize") { edNormalize() }, weight())
        r2.addView(btn("Fade in") { edFade(true) }, weight())
        r2.addView(btn("Fade out") { edFade(false) }, weight())
        r2.addView(btn("Reverse") { edReverse() }, weight())
        col.addView(r2)

        val r3 = hbox()
        r3.addView(btn("Save as new") { edSave(false) }, weight())
        r3.addView(btn("Replace original") { edReplaceConfirm() }, weight())
        col.addView(r3)

        col.addView(
            label(
                "Drag the two orange handles to choose a part of the sound. " +
                    "Trim keeps only the selected part. Normalize raises the level to 90%.", 13f
            )
        )
        return col
    }

    private fun openEditor(f: File) {
        val s = loadSample(f)
        if (s == null) {
            toast("Cannot open this file for editing")
            return
        }
        edFile = f
        edSample = s
        edUndo = null
        wave.setSelection(0f, 1f)
        showTab(2)
    }

    private fun computePeaks(s: Sample, bins: Int): FloatArray {
        val out = FloatArray(bins)
        val frames = s.frames
        if (frames == 0) return out
        for (b in 0 until bins) {
            val a = (b.toLong() * frames / bins).toInt()
            val e = (((b + 1).toLong() * frames / bins).toInt()).coerceAtLeast(a + 1).coerceAtMost(frames)
            var m = 0
            for (fr in a until e) {
                for (c in 0 until s.channels) {
                    val v = abs(s.data[fr * s.channels + c].toInt())
                    if (v > m) m = v
                }
            }
            out[b] = m / 32768f
        }
        return out
    }

    private fun refreshEditor() {
        val s = edSample
        val f = edFile
        if (s == null || f == null) {
            edTitle.text = "Open a sound with its Edit button (in a track or in the Library)."
            wave.peaks = FloatArray(0)
            edInfo.text = ""
            return
        }
        edTitle.text = "Editing: ${f.name}"
        wave.peaks = computePeaks(s, 800)
        updateEdInfo()
    }

    private fun selFrames(s: Sample): Pair<Int, Int> {
        val a = (wave.selStart * s.frames).roundToInt().coerceIn(0, s.frames)
        val b = (wave.selEnd * s.frames).roundToInt().coerceIn(a, s.frames)
        return Pair(a, b)
    }

    private fun updateEdInfo() {
        val s = edSample ?: return
        val (a, b) = selFrames(s)
        val len = b - a
        val kb = fm1SizeBytes(len, s.rate, 16000) / 1024
        edInfo.text = String.format(
            Locale.US,
            "Length %.2f s. Selection %.2f to %.2f s (%.2f s). As 16 kHz mono it needs about %d KB of the 496 KB FM-1 budget.",
            s.frames.toDouble() / s.rate, a.toDouble() / s.rate, b.toDouble() / s.rate,
            len.toDouble() / s.rate, kb
        )
    }

    private fun pushUndo() {
        val s = edSample
        edUndo = if (s == null) null else Sample(s.data.copyOf(), s.rate, s.channels)
    }

    private fun edDoUndo() {
        val u = edUndo
        if (u == null) {
            toast("Nothing to undo")
            return
        }
        edSample = u
        edUndo = null
        wave.setSelection(0f, 1f)
        refreshEditor()
    }

    private fun edPlaySel() {
        val s = edSample ?: return
        val (a, b) = selFrames(s)
        if (b - a < 1) return
        edPlay?.release()
        val seg = s.data.copyOfRange(a * s.channels, b * s.channels)
        val t = makeTrackFromShorts(seg, s.rate, s.channels)
        edPlay = t
        t?.play()
    }

    private fun edTrim() {
        val s = edSample ?: return
        val (a, b) = selFrames(s)
        if (b - a < 2) return
        pushUndo()
        edSample = Sample(s.data.copyOfRange(a * s.channels, b * s.channels), s.rate, s.channels)
        wave.setSelection(0f, 1f)
        refreshEditor()
    }

    private fun edNormalize() {
        val s = edSample ?: return
        var peak = 0
        for (v in s.data) {
            val a = abs(v.toInt())
            if (a > peak) peak = a
        }
        if (peak == 0) return
        pushUndo()
        val g = 0.9f * 32768f / peak
        val d = s.data.copyOf()
        for (i in d.indices) {
            d[i] = (d[i] * g).toInt().coerceIn(-32768, 32767).toShort()
        }
        edSample = Sample(d, s.rate, s.channels)
        refreshEditor()
    }

    private fun edFade(fadeIn: Boolean) {
        val s = edSample ?: return
        val (a, b) = selFrames(s)
        val len = b - a
        if (len < 4) return
        pushUndo()
        val d = s.data.copyOf()
        val n = minOf(len / 2, (s.rate * 0.1).toInt()).coerceAtLeast(1)
        for (i in 0 until n) {
            val gain = i.toFloat() / n
            val fr = if (fadeIn) a + i else b - 1 - i
            for (c in 0 until s.channels) {
                val idx = fr * s.channels + c
                d[idx] = (d[idx] * gain).toInt().toShort()
            }
        }
        edSample = Sample(d, s.rate, s.channels)
        refreshEditor()
    }

    private fun edReverse() {
        val s = edSample ?: return
        val (a, b) = selFrames(s)
        if (b - a < 2) return
        pushUndo()
        val d = s.data.copyOf()
        val ch = s.channels
        var i = a
        var j = b - 1
        while (i < j) {
            for (c in 0 until ch) {
                val t = d[i * ch + c]
                d[i * ch + c] = d[j * ch + c]
                d[j * ch + c] = t
            }
            i++
            j--
        }
        edSample = Sample(d, s.rate, s.channels)
        refreshEditor()
    }

    private fun edReplaceConfirm() {
        val f = edFile ?: return
        AlertDialog.Builder(this)
            .setTitle("Replace original")
            .setMessage("Overwrite ${f.name} with the edited sound? You can also use Save as new to keep both.")
            .setPositiveButton("Replace") { _, _ -> edSave(true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun edSave(replace: Boolean) {
        val s = edSample ?: return
        val f = edFile ?: return
        val target = if (replace) f else uniqueFile(f.nameWithoutExtension + "_edit")
        try {
            writeWav(target, shortsToBytes(s.data), s.rate, s.channels)
            edFile = target
            toast("Saved ${target.name}")
            refreshEditor()
            refreshLibrary()
            reloadPads()
            if (replace) reloadTracksUsing(target)
        } catch (e: Exception) {
            toast("Could not save: ${e.message}")
        }
    }

    // =====================================================================
    // PADS
    // =====================================================================

    private fun buildPads(): View {
        val col = column()
        col.addView(label("🥁 Pads", 20f))
        col.addView(
            label(
                "Tap a pad to play it. Long-press a pad to choose its sound. " +
                    "MIDI notes from the FM-1 also play the pads (note number modulo 8). " +
                    "Use the MIDI button at the top to connect.", 14f
            )
        )
        for (r in 0 until 2) {
            val line = hbox()
            for (c in 0 until 4) {
                val i = r * 4 + c
                val b = Button(this)
                b.isAllCaps = false
                styleBtn(b, "#2F3133", "#FFFFFF")
                b.setOnClickListener { playPad(i) }
                b.setOnLongClickListener {
                    assignPadDialog(i)
                    true
                }
                padButtons[i] = b
                line.addView(b, LinearLayout.LayoutParams(0, dp(110), 1f))
            }
            col.addView(line)
        }
        padInfo = label("Last MIDI note: none", 14f)
        col.addView(padInfo)
        return col
    }

    private fun reloadPads() {
        for (i in 0 until PAD_COUNT) {
            padTracks[i]?.release()
            padTracks[i] = null
            val name = prefs.getString("pad$i", "") ?: ""
            if (name.isNotEmpty()) {
                val f = File(samplesDir, name)
                if (f.exists()) padTracks[i] = makeTrack(f)
            }
            val shown = if (name.isEmpty()) "(empty)" else name
            padButtons[i]?.text = "Pad ${i + 1}\n$shown"
        }
    }

    private fun playPad(i: Int) {
        restart(padTracks[i])
    }

    private fun assignPadDialog(i: Int) {
        val files = listSamples()
        val names = (listOf("(empty)") + files.map { it.name }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Sound for pad ${i + 1}")
            .setItems(names) { _, which ->
                val ed = prefs.edit()
                if (which == 0) {
                    ed.remove("pad$i")
                } else {
                    ed.putString("pad$i", files[which - 1].name)
                }
                ed.apply()
                reloadPads()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =====================================================================
    // MIDI connection (used by Pads and by the tempo clock)
    // =====================================================================

    @Suppress("DEPRECATION")
    private fun onMidiButton() {
        if (midiDevice != null) {
            disconnectMidi()
            midiStatusText.text = "MIDI: not connected"
            midiButton.text = "Connect MIDI"
            return
        }
        val mm = getSystemService(Context.MIDI_SERVICE) as? MidiManager
        if (mm == null) {
            toast("MIDI is not supported on this device")
            return
        }
        val infos = mm.devices.toList()
        if (infos.isEmpty()) {
            toast("No MIDI device found. Connect the FM-1 with a USB cable and try again.")
            return
        }
        val names = infos
            .map { it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device" }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose MIDI device")
            .setItems(names) { _, which -> connectMidi(infos[which], names[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectMidi(info: MidiDeviceInfo, name: String) {
        val mm = getSystemService(Context.MIDI_SERVICE) as? MidiManager
        if (mm == null) {
            toast("MIDI is not supported on this device")
            return
        }
        disconnectMidi()
        mm.openDevice(info, MidiManager.OnDeviceOpenedListener { dev ->
            if (dev == null) {
                toast("Could not open the MIDI device")
            } else {
                midiDevice = dev
                if (info.inputPortCount > 0) sendPort = dev.openInputPort(0)
                if (info.outputPortCount > 0) {
                    val out = dev.openOutputPort(0)
                    recvPort = out
                    out?.connect(midiReceiver)
                }
                midiStatusText.text = "MIDI: connected to $name"
                midiButton.text = "Disconnect MIDI"
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun disconnectMidi() {
        try {
            recvPort?.disconnect(midiReceiver)
        } catch (e: Exception) {
        }
        try {
            recvPort?.close()
        } catch (e: Exception) {
        }
        try {
            sendPort?.close()
        } catch (e: Exception) {
        }
        try {
            midiDevice?.close()
        } catch (e: Exception) {
        }
        recvPort = null
        sendPort = null
        midiDevice = null
    }

    private fun sendMidi(b: ByteArray) {
        try {
            sendPort?.send(b, 0, b.size)
        } catch (e: Exception) {
        }
    }

    // Receives everything the FM-1 sends: notes play the pads, clock drives the tempo
    private val midiReceiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val now = System.nanoTime()
            for (i in offset until offset + count) {
                handleMidiByte(msg[i].toInt() and 0xFF, now)
            }
        }
    }

    private fun handleMidiByte(b: Int, now: Long) {
        if (b >= 0xF8) {
            when (b) {
                0xF8 -> onClockTick(now)
                0xFA -> onClockStart()
                0xFC -> onClockStop()
            }
            return
        }
        if (b >= 0xF0) {
            midiRunning = 0
            midiData1 = -1
            return
        }
        if (b >= 0x80) {
            midiRunning = b
            midiData1 = -1
            return
        }
        val type = midiRunning and 0xF0
        if (type == 0x90 || type == 0x80) {
            if (midiData1 < 0) {
                midiData1 = b
            } else {
                val note = midiData1
                midiData1 = -1
                if (type == 0x90 && b > 0) onNoteOn(note, b)
            }
        } else if (type == 0xA0 || type == 0xB0 || type == 0xE0) {
            midiData1 = if (midiData1 < 0) b else -1
        }
    }

    private fun onNoteOn(note: Int, vel: Int) {
        val pad = note % PAD_COUNT
        playPad(pad)
        val now = System.nanoTime()
        if (now - lastNoteUiNs > 80_000_000L) {
            lastNoteUiNs = now
            runOnUiThread {
                padInfo.text = "Last MIDI note: $note (velocity $vel) -> pad ${pad + 1}"
            }
        }
    }

    // Follow mode: the FM-1 sets the tempo and starts or stops the tracks
    private fun onClockStart() {
        lastTick = 0L
        ti = 0
        tiFilled = 0
        if (clockMode == 2) {
            runOnUiThread {
                if (!engine.playing) startAll()
            }
        }
    }

    private fun onClockStop() {
        lastTick = 0L
        if (clockMode == 2) {
            runOnUiThread {
                if (engine.playing) stopAll()
            }
        }
    }

    private fun onClockTick(now: Long) {
        if (lastTick != 0L) {
            tickIntervals[ti] = now - lastTick
            ti = (ti + 1) % 24
            if (tiFilled < 24) tiFilled++
            if (tiFilled >= 6) {
                var sum = 0L
                for (j in 0 until tiFilled) sum += tickIntervals[j]
                avgTickNs = sum / tiFilled
            }
        }
        lastTick = now
        if (clockMode == 2 && avgTickNs > 0L) {
            val b = 60_000_000_000.0 / (avgTickNs * 24.0)
            engine.bpm = b.toFloat().coerceIn(40f, 240f)
        }
    }
}
