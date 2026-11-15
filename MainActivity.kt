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
import android.widget.ProgressBar
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
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        const val REQ_IMPORT = 10
        const val REQ_EXPORT = 11
        const val PAD_COUNT = 8
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

    // Library
    private lateinit var listBox: LinearLayout
    private lateinit var searchBox: EditText

    // Record
    private lateinit var srcSpinner: Spinner
    private var inputDevs: List<AudioDeviceInfo> = emptyList()
    private lateinit var gainText: TextView
    private lateinit var meter: ProgressBar
    private lateinit var clipWarn: TextView
    private lateinit var recStatus: TextView
    private lateinit var editLastBtn: Button
    private var lastRecording: File? = null
    @Volatile private var recording = false
    @Volatile private var softGain = 1f
    @Volatile private var autoOn = true
    private var lastMeterNs = 0L

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

    // Sync
    private lateinit var bpmBar: SeekBar
    private lateinit var bpmText: TextView
    private lateinit var offsetText: TextView
    private lateinit var syncStatus: TextView
    private lateinit var syncStartBtn: Button
    private val syncSpinners = arrayOfNulls<Spinner>(2)
    private val syncBeatSpinners = arrayOfNulls<Spinner>(2)
    private val syncTracks = arrayOfNulls<AudioTrack>(2)
    private var sampleFiles: List<File> = emptyList()
    private val beatLabels = listOf("1 beat", "2 beats", "4 beats (1 bar)", "8 beats", "16 beats")
    private val beatValues = listOf(1, 2, 4, 8, 16)
    private val syncBeats = intArrayOf(4, 4)
    private val taps = ArrayList<Long>()
    @Volatile private var masterBpm = 120
    @Volatile private var offsetMs = 0
    @Volatile private var appIsMaster = true
    @Volatile private var syncRunning = false
    @Volatile private var syncGen = 0

    // Follower (the FM-1 is the master clock)
    private val tickIntervals = LongArray(24)
    private var ti = 0
    private var tiFilled = 0
    private var lastTick = 0L
    private var avgTickNs = 0L
    private var tickCount = 0L
    private var lastUiNs = 0L
    @Volatile private var followBpm = 0.0

    // ---------------- lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences("fm1bridge", Context.MODE_PRIVATE)
        samplesDir = File(filesDir, "samples")
        samplesDir.mkdirs()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val tabBar = hbox()
        val names = listOf("Library", "Record", "Edit", "Pads", "Sync")
        names.forEachIndexed { i, n ->
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
            scroll(buildLibrary()),
            scroll(buildRecord()),
            scroll(buildEdit()),
            scroll(buildPads()),
            scroll(buildSync())
        )
        for (v in tabViews) content.addView(v)
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        reloadPads()
        showTab(0)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onDestroy() {
        recording = false
        syncRunning = false
        syncGen++
        previewTrack?.release()
        edPlay?.release()
        for (t in syncTracks) t?.release()
        for (t in padTracks) t?.release()
        disconnectMidi()
        super.onDestroy()
    }

    private fun showTab(i: Int) {
        tabViews.forEachIndexed { idx, v ->
            v.visibility = if (idx == i) View.VISIBLE else View.GONE
        }
        if (i == 0) refreshLibrary()
        if (i == 1 && !recording) scanInputs()
        if (i == 2) refreshEditor()
        if (i == 4) refreshSyncLists()
    }

    // ---------------- small UI helpers ----------------

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun hbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        return l
    }

    private fun btn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setOnClickListener { onClick() }
        return b
    }

    private fun label(text: String, size: Float = 16f): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        return t
    }

    private fun column(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(16), dp(16), dp(16), dp(16))
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

    private fun fill(sp: Spinner, labels: List<String>) {
        val prev = sp.selectedItem as? String
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = ad
        val idx = labels.indexOf(prev)
        if (idx >= 0) sp.setSelection(idx)
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

    // ---------------- LIBRARY ----------------

    private fun buildLibrary(): View {
        val col = column()
        col.addView(label("Sample library", 20f))
        col.addView(btn("Import WAV file") { startImport() })
        searchBox = EditText(this)
        searchBox.hint = "Search by name or tag..."
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
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(12), 0, dp(12))
        val name = label(f.name, 17f)
        name.setTypeface(name.typeface, Typeface.BOLD)
        box.addView(name)
        box.addView(label(describe(f), 13f))
        val tag = tagOf(f)
        if (tag.isNotEmpty()) box.addView(label("Tag: $tag", 13f))

        val a1 = hbox()
        a1.addView(btn("Play") { preview(f) }, weight())
        a1.addView(btn("Edit") { openEditor(f) }, weight())
        a1.addView(btn("Rename") { renameDialog(f) }, weight())
        a1.addView(btn("Tag") { tagDialog(f) }, weight())
        box.addView(a1)

        val a2 = hbox()
        a2.addView(btn("Export") { exportOriginal(f) }, weight())
        a2.addView(btn("Export for FM-1") { fm1ExportDialog(f) }, weight())
        a2.addView(btn("Delete") { deleteDialog(f) }, weight())
        box.addView(a2)
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
                        moveRefs(f.name, target.name)
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

    // Keep tags and pad assignments in sync when a file is renamed or deleted
    private fun moveRefs(oldName: String, newName: String) {
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
        reloadPads()
    }

    private fun dropRefs(name: String) {
        val ed = prefs.edit()
        ed.remove("tag:$name")
        for (i in 0 until PAD_COUNT) {
            if (prefs.getString("pad$i", "") == name) ed.remove("pad$i")
        }
        ed.apply()
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

    private fun importUri(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.size > 20 * 1024 * 1024) {
                toast("File unreadable or too large (max 20 MB)")
                return
            }
            if (parseWav(bytes, bytes.size.toLong()) == null) {
                toast("Only 16-bit PCM WAV files are supported for now")
                return
            }
            var base = safeName(displayName(uri).removeSuffix(".wav").removeSuffix(".WAV"))
            if (base.isEmpty()) base = "Imported"
            val target = uniqueFile(base)
            target.writeBytes(bytes)
            refreshLibrary()
            toast("Imported ${target.name}")
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
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

    // ---------------- RECORD ----------------

    private fun buildRecord(): View {
        val col = column()
        col.addView(label("Record", 20f))

        val tip = label(
            "Tip: before recording, lower the volume on your instrument a little " +
                "so the signal does not clip.", 14f
        )
        tip.setBackgroundColor(Color.parseColor("#FFF4CC"))
        tip.setTextColor(Color.parseColor("#5A4500"))
        tip.setPadding(dp(12), dp(10), dp(12), dp(10))
        col.addView(tip)

        col.addView(label("Input source"))
        srcSpinner = Spinner(this)
        col.addView(srcSpinner)
        col.addView(btn("Rescan inputs") { scanInputs() })

        gainText = label("Gain: 100%")
        col.addView(gainText)
        val gainBar = SeekBar(this)
        gainBar.max = 200
        gainBar.progress = 100
        gainBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                gainText.text = "Gain: $p%"
                softGain = p / 100f
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(gainBar)

        val auto = CheckBox(this)
        auto.text = "Auto level after recording (target 70%)"
        auto.isChecked = true
        auto.setOnCheckedChangeListener { _, c -> autoOn = c }
        col.addView(auto)

        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        meter.max = 100
        col.addView(meter)

        clipWarn = label("", 15f)
        clipWarn.setTextColor(Color.RED)
        clipWarn.setTypeface(clipWarn.typeface, Typeface.BOLD)
        clipWarn.visibility = View.GONE
        col.addView(clipWarn)

        val buttons = hbox()
        buttons.addView(btn("Record") { startRecording() }, weight())
        buttons.addView(btn("Stop") { recording = false }, weight())
        col.addView(buttons)

        recStatus = label("Choose an input and press Record.", 15f)
        col.addView(recStatus)

        editLastBtn = btn("Edit last recording") {
            val f = lastRecording
            if (f != null) openEditor(f)
        }
        editLastBtn.visibility = View.GONE
        col.addView(editLastBtn)

        scanInputs()
        return col
    }

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
        val labels = inputDevs.map { inputLabel(it) }.ifEmpty { listOf("No input found") }
        fill(srcSpinner, labels)
    }

    private fun startRecording() {
        if (recording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recStatus.text = "Microphone permission needed"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        val dev = inputDevs.getOrNull(srcSpinner.selectedItemPosition)
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
        recording = true
        recStatus.text = "Recording... (max 30 s)"
        clipWarn.visibility = View.GONE

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
                        val shown = peakOf(buf, n) * 100 / 32768
                        val showClip = clipHit
                        val secs = pcm.size().toDouble() / (rate * 2 * nch)
                        runOnUiThread {
                            meter.progress = shown
                            recStatus.text = String.format(Locale.US, "Recording... %.1f s (max 30 s)", secs)
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
            finishRecording(pcm.toByteArray(), rate, nch, rawPeakAll)
        }.start()
    }

    private fun finishRecording(data: ByteArray, rate: Int, nch: Int, rawPeak: Int) {
        if (data.isEmpty()) {
            runOnUiThread {
                meter.progress = 0
                recStatus.text = "Nothing was recorded"
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
            runOnUiThread { recStatus.text = "Could not save: ${e.message}" }
            return
        }
        val finalPeak = peakOf(data, data.size) * 100 / 32768
        val secs = data.size.toDouble() / (rate * nch * 2)
        val clipped = rawPeak >= 32000
        runOnUiThread {
            meter.progress = 0
            var msg = String.format(Locale.US, "Saved %s - %.1f s, peak %d%%", file.name, secs, finalPeak)
            if (clipped) {
                msg += "\nThe input was clipping. Lower the volume on your instrument " +
                    "and record again for a clean sample."
            }
            recStatus.text = msg
            lastRecording = file
            editLastBtn.visibility = View.VISIBLE
            refreshLibrary()
        }
    }

    // ---------------- EDIT ----------------

    private fun buildEdit(): View {
        val col = column()
        col.addView(label("Edit", 20f))
        edTitle = label("", 15f)
        col.addView(edTitle)

        wave = WaveformView(this)
        col.addView(wave, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)))
        wave.onSelection = { _, _ -> updateEdInfo() }

        edInfo = label("", 13f)
        col.addView(edInfo)

        val r1 = hbox()
        r1.addView(btn("Play selection") { edPlaySel() }, weight())
        r1.addView(btn("Stop") { edPlay?.stop() }, weight())
        r1.addView(btn("Undo") { edDoUndo() }, weight())
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
            edTitle.text = "Open a sample from the Library with its Edit button, or use Edit last recording."
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
        } catch (e: Exception) {
            toast("Could not save: ${e.message}")
        }
    }

    // ---------------- PADS ----------------

    private fun buildPads(): View {
        val col = column()
        col.addView(label("Pads", 20f))
        col.addView(
            label(
                "Tap a pad to play it. Long-press a pad to choose its sample. " +
                    "MIDI notes from the FM-1 also play the pads (note number modulo 8). " +
                    "Use the MIDI button at the top to connect.", 14f
            )
        )
        for (r in 0 until 2) {
            val line = hbox()
            for (c in 0 until 4) {
                val i = r * 4 + c
                val b = Button(this)
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
            .setTitle("Sample for pad ${i + 1}")
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

    // ---------------- MIDI connection (shared by Pads and Sync) ----------------

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

    // Receives everything the FM-1 sends: notes play the pads, clock drives Sync
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

    // ---------------- SYNC ----------------

    private fun buildSync(): View {
        val col = column()
        col.addView(label("Sync", 20f))
        col.addView(label("Connect the FM-1 with the MIDI button at the top first.", 14f))

        col.addView(label("Clock source"))
        val rg = RadioGroup(this)
        rg.orientation = LinearLayout.VERTICAL
        val r1 = RadioButton(this)
        r1.text = "App is master (app sends MIDI clock to the FM-1)"
        r1.id = View.generateViewId()
        val r2 = RadioButton(this)
        r2.text = "FM-1 is master (app follows its MIDI clock)"
        r2.id = View.generateViewId()
        rg.addView(r1)
        rg.addView(r2)
        rg.check(r1.id)
        col.addView(rg)

        for (i in 0 until 2) {
            val letter = if (i == 0) "A" else "B"
            col.addView(label("Track $letter - sample"))
            val sp = Spinner(this)
            syncSpinners[i] = sp
            col.addView(sp)
            col.addView(label("Track $letter - play it again every"))
            val bs = Spinner(this)
            fill(bs, beatLabels)
            bs.setSelection(2)
            bs.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    syncBeats[i] = beatValues[pos]
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            syncBeatSpinners[i] = bs
            col.addView(bs)
        }

        bpmText = label("BPM: 120")
        col.addView(bpmText)
        bpmBar = SeekBar(this)
        bpmBar.max = 200
        bpmBar.progress = 80
        bpmBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                masterBpm = p + 40
                if (appIsMaster) bpmText.text = "BPM: ${p + 40}"
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(bpmBar)
        col.addView(btn("Tap tempo") { tapTempo() })

        offsetText = label("Audio delay compensation: 0 ms")
        col.addView(offsetText)
        val offsetBar = SeekBar(this)
        offsetBar.max = 200
        offsetBar.progress = 0
        offsetBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                offsetMs = p
                offsetText.text = "Audio delay compensation: $p ms"
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(offsetBar)

        syncStartBtn = btn("Start") { toggleSync() }
        col.addView(syncStartBtn)
        syncStatus = label("Pick a sample for Track A or B, then press Start.", 15f)
        col.addView(syncStatus)

        rg.setOnCheckedChangeListener { _, id ->
            if (syncRunning) stopSync()
            appIsMaster = (id == r1.id)
            bpmBar.isEnabled = appIsMaster
            if (appIsMaster) bpmText.text = "BPM: $masterBpm"
        }
        return col
    }

    private fun refreshSyncLists() {
        sampleFiles = listSamples()
        val names = listOf("(none)") + sampleFiles.map { it.name }
        for (i in 0 until 2) {
            val sp = syncSpinners[i]
            if (sp != null) fill(sp, names)
        }
    }

    private fun tapTempo() {
        val now = System.currentTimeMillis()
        if (taps.isNotEmpty() && now - taps[taps.size - 1] > 2000L) taps.clear()
        taps.add(now)
        if (taps.size > 5) taps.removeAt(0)
        if (taps.size >= 2) {
            val avg = (taps[taps.size - 1] - taps[0]).toDouble() / (taps.size - 1)
            val bpm = (60000.0 / avg).roundToInt().coerceIn(40, 240)
            bpmBar.progress = bpm - 40
        }
    }

    private fun toggleSync() {
        if (syncRunning) {
            stopSync()
            return
        }
        for (i in 0 until 2) {
            syncTracks[i]?.release()
            syncTracks[i] = null
            val pos = syncSpinners[i]?.selectedItemPosition ?: 0
            if (pos > 0) {
                val f = sampleFiles.getOrNull(pos - 1)
                if (f != null) syncTracks[i] = makeTrack(f)
            }
        }
        syncGen++
        syncRunning = true
        syncStartBtn.text = "Stop"
        if (appIsMaster) {
            syncStatus.text = "Master: sending clock at $masterBpm BPM"
            startMaster(syncGen)
        } else {
            tickCount = 0L
            lastTick = 0L
            ti = 0
            tiFilled = 0
            avgTickNs = 0L
            syncStatus.text = "Waiting for MIDI clock from the FM-1..."
        }
    }

    private fun stopSync() {
        syncRunning = false
        syncGen++
        syncStartBtn.text = "Start"
        for (t in syncTracks) {
            try {
                t?.stop()
            } catch (e: Exception) {
            }
        }
        syncStatus.text = "Stopped"
    }

    private fun waitUntil(t: Long, gen: Int) {
        while (syncRunning && gen == syncGen) {
            val d = t - System.nanoTime()
            if (d <= 0L) return
            if (d > 2_000_000L) LockSupport.parkNanos(d - 1_000_000L) else Thread.yield()
        }
    }

    // Plays Track A / B again every N beats, shifted earlier by offTicks clock ticks
    private fun triggerDue(tick: Long, offTicks: Int) {
        for (i in 0 until 2) {
            if (syncTracks[i] != null) {
                val every = syncBeats[i] * 24L
                if ((tick + offTicks) % every == 0L) restart(syncTracks[i])
            }
        }
    }

    // The app is the master: send MIDI clock (24 ticks per beat) and trigger the samples
    private fun startMaster(gen: Int) {
        val th = Thread {
            val clk = byteArrayOf(0xF8.toByte())
            val startMsg = byteArrayOf(0xFA.toByte())
            val stopMsg = byteArrayOf(0xFC.toByte())
            val firstTickNs = 60_000_000_000L / (masterBpm * 24L)
            val offTicks = (offsetMs * 1_000_000.0 / firstTickNs).roundToInt()
            var k = -offTicks.toLong()
            var tickTime = System.nanoTime() + 60_000_000L
            while (syncRunning && gen == syncGen) {
                val tickNs = 60_000_000_000L / (masterBpm * 24L)
                waitUntil(tickTime, gen)
                if (!(syncRunning && gen == syncGen)) break
                triggerDue(k, offTicks)
                if (k == 0L) sendMidi(startMsg)
                if (k >= 0L) sendMidi(clk)
                if (k >= 0L && k % 24L == 0L) {
                    val bpmNow = masterBpm
                    runOnUiThread {
                        if (syncRunning && appIsMaster) {
                            syncStatus.text = "Master: sending clock at $bpmNow BPM"
                        }
                    }
                }
                k++
                tickTime += tickNs
            }
            sendMidi(stopMsg)
        }
        th.isDaemon = true
        th.start()
    }

    // The FM-1 is the master: follow its MIDI clock
    private fun onClockStart() {
        tickCount = 0L
        lastTick = 0L
    }

    private fun onClockStop() {
        lastTick = 0L
        for (t in syncTracks) {
            try {
                t?.stop()
            } catch (e: Exception) {
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
        if (avgTickNs > 0L) followBpm = 60_000_000_000.0 / (avgTickNs * 24.0)

        if (syncRunning && !appIsMaster) {
            val offTicks =
                if (avgTickNs > 0L) (offsetMs * 1_000_000.0 / avgTickNs).roundToInt() else 0
            if (tickCount == 0L) {
                for (i in 0 until 2) restart(syncTracks[i])
            } else {
                triggerDue(tickCount, offTicks)
            }
            tickCount++
        }

        if (now - lastUiNs > 300_000_000L) {
            lastUiNs = now
            val bpmNow = followBpm
            runOnUiThread {
                if (!appIsMaster) {
                    bpmText.text = String.format(Locale.US, "BPM: %.1f (from FM-1)", bpmNow)
                    if (syncRunning) {
                        syncStatus.text = String.format(Locale.US, "Following the FM-1 clock: %.1f BPM", bpmNow)
                    }
                }
            }
        }
    }
}
