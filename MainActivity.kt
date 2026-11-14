
package com.example.fm1bridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------
// WAV and PCM helpers (16-bit PCM only)
// ---------------------------------------------------------------

class WavInfo(val sampleRate: Int, val channels: Int, val dataOffset: Int, val dataLen: Int)

private fun le16(b: ByteArray, p: Int): Int =
    (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

private fun le32(b: ByteArray, p: Int): Int = le16(b, p) or (le16(b, p + 2) shl 16)

fun parseWav(b: ByteArray, total: Long): WavInfo? {
    if (b.size < 44) return null
    if (String(b, 0, 4, Charsets.US_ASCII) != "RIFF") return null
    if (String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
    var pos = 12
    var fmt = 0
    var ch = 0
    var rate = 0
    var bits = 0
    while (pos + 8 <= b.size) {
        val id = String(b, pos, 4, Charsets.US_ASCII)
        val size = le32(b, pos + 4).toLong() and 0xFFFFFFFFL
        if (id == "fmt " && pos + 24 <= b.size) {
            fmt = le16(b, pos + 8)
            ch = le16(b, pos + 10)
            rate = le32(b, pos + 12)
            bits = le16(b, pos + 22)
        } else if (id == "data") {
            if ((fmt != 1 && fmt != 0xFFFE) || bits != 16 || ch < 1 || ch > 2 || rate <= 0) return null
            val avail = (total - (pos + 8)).coerceAtLeast(0L)
            var len = minOf(size, avail).toInt()
            len -= len % (ch * 2)
            return WavInfo(rate, ch, pos + 8, len)
        }
        val next = pos.toLong() + 8L + size + (size and 1L)
        if (next > Int.MAX_VALUE) return null
        pos = next.toInt()
    }
    return null
}

fun writeWav(file: File, pcm: ByteArray, rate: Int, ch: Int) {
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray(Charsets.US_ASCII))
    h.putInt(36 + pcm.size)
    h.put("WAVE".toByteArray(Charsets.US_ASCII))
    h.put("fmt ".toByteArray(Charsets.US_ASCII))
    h.putInt(16)
    h.putShort(1.toShort())
    h.putShort(ch.toShort())
    h.putInt(rate)
    h.putInt(rate * ch * 2)
    h.putShort((ch * 2).toShort())
    h.putShort(16.toShort())
    h.put("data".toByteArray(Charsets.US_ASCII))
    h.putInt(pcm.size)
    file.outputStream().use {
        it.write(h.array())
        it.write(pcm)
    }
}

fun peakOf(d: ByteArray, len: Int): Int {
    var p = 0
    var i = 0
    while (i + 1 < len) {
        val s = ((d[i + 1].toInt() shl 8) or (d[i].toInt() and 0xFF)).toShort().toInt()
        val a = abs(s)
        if (a > p) p = a
        i += 2
    }
    return p
}

fun scalePcm(d: ByteArray, len: Int, gain: Float) {
    var i = 0
    while (i + 1 < len) {
        val s = ((d[i + 1].toInt() shl 8) or (d[i].toInt() and 0xFF)).toShort().toInt()
        val v = (s * gain).toInt().coerceIn(-32768, 32767)
        d[i] = (v and 0xFF).toByte()
        d[i + 1] = ((v shr 8) and 0xFF).toByte()
        i += 2
    }
}

// ---------------------------------------------------------------
// App
// ---------------------------------------------------------------

class MainActivity : Activity() {

    companion object {
        const val REQ_IMPORT = 10
        const val REQ_EXPORT = 11
    }

    private lateinit var audioManager: AudioManager
    private lateinit var samplesDir: File
    private lateinit var tabViews: List<View>
    private var pendingExport: File? = null

    // Library
    private lateinit var listBox: LinearLayout
    private lateinit var searchBox: EditText
    private var previewTrack: AudioTrack? = null

    // Record
    private lateinit var srcSpinner: Spinner
    private var inputDevs: List<AudioDeviceInfo> = emptyList()
    private lateinit var gainText: TextView
    private lateinit var meter: ProgressBar
    private lateinit var clipWarn: TextView
    private lateinit var recStatus: TextView
    @Volatile private var recording = false
    @Volatile private var softGain = 1f
    @Volatile private var autoOn = true
    private var lastMeterNs = 0L

    // Sync
    private lateinit var midiSpinner: Spinner
    private lateinit var sampleSpinner: Spinner
    private lateinit var beatsSpinner: Spinner
    private lateinit var bpmBar: SeekBar
    private lateinit var bpmText: TextView
    private lateinit var offsetText: TextView
    private lateinit var syncStatus: TextView
    private lateinit var syncStartBtn: Button
    private var midiInfos: List<MidiDeviceInfo> = emptyList()
    private var sampleFiles: List<File> = emptyList()
    private var midiDevice: MidiDevice? = null
    private var sendPort: MidiInputPort? = null
    private var recvPort: MidiOutputPort? = null
    private var syncTrack: AudioTrack? = null
    private val beatLabels = listOf("1 beat", "2 beats", "4 beats (1 bar)", "8 beats", "16 beats")
    private val beatValues = listOf(1, 2, 4, 8, 16)
    @Volatile private var masterBpm = 120
    @Volatile private var offsetMs = 0
    @Volatile private var beatsPerTrig = 4
    @Volatile private var appIsMaster = true
    @Volatile private var syncRunning = false
    @Volatile private var syncGen = 0

    // Follower (FM-1 is the master clock)
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
        samplesDir = File(filesDir, "samples")
        samplesDir.mkdirs()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val tabBar = LinearLayout(this)
        tabBar.orientation = LinearLayout.HORIZONTAL
        val content = FrameLayout(this)

        tabViews = listOf(scroll(buildLibrary()), scroll(buildRecord()), scroll(buildSync()))
        for (v in tabViews) content.addView(v)

        val names = listOf("Library", "Record", "Sync")
        names.forEachIndexed { i, n ->
            tabBar.addView(btn(n) { showTab(i) }, weight())
        }
        root.addView(tabBar)
        root.addView(
            content,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
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
        syncTrack?.release()
        disconnectMidi()
        super.onDestroy()
    }

    private fun showTab(i: Int) {
        tabViews.forEachIndexed { idx, v ->
            v.visibility = if (idx == i) View.VISIBLE else View.GONE
        }
        if (i == 0) refreshLibrary()
        if (i == 1 && !recording) scanInputs()
        if (i == 2) refreshSyncLists()
    }

    // ---------------- small UI helpers ----------------

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

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

    // ---------------- LIBRARY ----------------

    private fun buildLibrary(): View {
        val col = column()
        col.addView(label("Sample library", 20f))
        col.addView(btn("Import WAV file") { startImport() })
        searchBox = EditText(this)
        searchBox.hint = "Search samples..."
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

    private fun refreshLibrary() {
        listBox.removeAllViews()
        val q = searchBox.text.toString().trim().lowercase(Locale.ROOT)
        val files = (samplesDir.listFiles() ?: emptyArray())
            .filter {
                it.extension.lowercase(Locale.ROOT) == "wav" &&
                    it.name.lowercase(Locale.ROOT).contains(q)
            }
            .sortedByDescending { it.lastModified() }
        if (files.isEmpty()) {
            listBox.addView(label("No samples yet. Record or import one."))
            return
        }
        for (f in files) listBox.addView(sampleRow(f))
    }

    private fun sampleRow(f: File): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(12), 0, dp(12))
        val name = label(f.name, 17f)
        name.setTypeface(name.typeface, Typeface.BOLD)
        row.addView(name)
        row.addView(label(describe(f), 13f))
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.addView(btn("Play") { preview(f) }, weight())
        actions.addView(btn("Rename") { renameDialog(f) }, weight())
        actions.addView(btn("Export") { startExport(f) }, weight())
        actions.addView(btn("Delete") { deleteDialog(f) }, weight())
        row.addView(actions)
        return row
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
        return String.format(
            Locale.US, "%.1f kHz · %s · %.1f s · %d KB",
            info.sampleRate / 1000.0, ch, secs, f.length() / 1024
        )
    }

    private fun makeTrack(f: File): AudioTrack? {
        return try {
            val bytes = f.readBytes()
            val info = parseWav(bytes, bytes.size.toLong())
            if (info == null || info.dataLen <= 0) {
                null
            } else {
                val mask = if (info.channels == 1) AudioFormat.CHANNEL_OUT_MONO
                else AudioFormat.CHANNEL_OUT_STEREO
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(info.sampleRate)
                            .setChannelMask(mask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(info.dataLen)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                t.write(bytes, info.dataOffset, info.dataLen)
                t
            }
        } catch (e: Exception) {
            null
        }
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
                    } else {
                        f.renameTo(target)
                        refreshLibrary()
                    }
                }
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
                refreshLibrary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, REQ_IMPORT)
    }

    private fun startExport(f: File) {
        pendingExport = f
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "audio/wav"
        i.putExtra(Intent.EXTRA_TITLE, f.name)
        startActivityForResult(i, REQ_EXPORT)
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
            var target = File(samplesDir, "$base.wav")
            var n = 1
            while (target.exists()) {
                target = File(samplesDir, "${base}_$n.wav")
                n++
            }
            target.writeBytes(bytes)
            refreshLibrary()
            toast("Imported ${target.name}")
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
    }

    private fun exportTo(uri: Uri) {
        val f = pendingExport ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(f.readBytes()) }
            toast("Exported ${f.name}")
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

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(btn("Record") { startRecording() }, weight())
        row.addView(btn("Stop") { recording = false }, weight())
        col.addView(row)

        recStatus = label("Choose an input and press Record.", 15f)
        col.addView(recStatus)

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
                        runOnUiThread {
                            meter.progress = shown
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
        val name = "Rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
        try {
            writeWav(File(samplesDir, name), data, rate, nch)
        } catch (e: Exception) {
            runOnUiThread { recStatus.text = "Could not save: ${e.message}" }
            return
        }
        val finalPeak = peakOf(data, data.size) * 100 / 32768
        val secs = data.size.toDouble() / (rate * nch * 2)
        val clipped = rawPeak >= 32000
        runOnUiThread {
            meter.progress = 0
            var msg = String.format(Locale.US, "Saved %s - %.1f s, peak %d%%", name, secs, finalPeak)
            if (clipped) {
                msg += "\nThe input was clipping. Lower the volume on your instrument " +
                    "and record again for a clean sample."
            }
            recStatus.text = msg
            refreshLibrary()
        }
    }

    // ---------------- SYNC ----------------

    private fun buildSync(): View {
        val col = column()
        col.addView(label("Sync", 20f))

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

        col.addView(label("MIDI device"))
        midiSpinner = Spinner(this)
        col.addView(midiSpinner)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(btn("Rescan") { scanMidi() }, weight())
        row.addView(btn("Connect") { connectMidi() }, weight())
        row.addView(btn("Disconnect") {
            disconnectMidi()
            syncStatus.text = "Disconnected"
        }, weight())
        col.addView(row)

        col.addView(label("Sample to play"))
        sampleSpinner = Spinner(this)
        col.addView(sampleSpinner)

        col.addView(label("Trigger the sample every"))
        beatsSpinner = Spinner(this)
        fill(beatsSpinner, beatLabels)
        beatsSpinner.setSelection(2)
        beatsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                beatsPerTrig = beatValues[pos]
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        col.addView(beatsSpinner)

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
        syncStatus = label("Connect the FM-1, pick a sample, then press Start.", 15f)
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
        sampleFiles = (samplesDir.listFiles() ?: emptyArray())
            .filter { it.extension.lowercase(Locale.ROOT) == "wav" }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        fill(sampleSpinner, sampleFiles.map { it.name }.ifEmpty { listOf("No samples in library") })
        scanMidi()
    }

    @Suppress("DEPRECATION")
    private fun scanMidi() {
        val mm = getSystemService(Context.MIDI_SERVICE) as? MidiManager
        if (mm == null) {
            midiInfos = emptyList()
            fill(midiSpinner, listOf("MIDI is not supported on this device"))
            return
        }
        midiInfos = mm.devices.toList()
        val labels = midiInfos
            .map { it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device" }
            .ifEmpty { listOf("No MIDI device found") }
        fill(midiSpinner, labels)
    }

    private fun connectMidi() {
        val info = midiInfos.getOrNull(midiSpinner.selectedItemPosition)
        if (info == null) {
            syncStatus.text = "No MIDI device selected"
            return
        }
        disconnectMidi()
        val mm = getSystemService(Context.MIDI_SERVICE) as? MidiManager
        if (mm == null) {
            syncStatus.text = "MIDI is not supported on this device"
            return
        }
        mm.openDevice(info, MidiManager.OnDeviceOpenedListener { dev ->
            if (dev == null) {
                syncStatus.text = "Could not open the MIDI device"
            } else {
                midiDevice = dev
                if (info.inputPortCount > 0) sendPort = dev.openInputPort(0)
                if (info.outputPortCount > 0) {
                    val out = dev.openOutputPort(0)
                    recvPort = out
                    out?.connect(clockReceiver)
                }
                syncStatus.text = "Connected to the MIDI device"
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun disconnectMidi() {
        try {
            recvPort?.disconnect(clockReceiver)
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

    private fun toggleSync() {
        if (syncRunning) {
            stopSync()
            return
        }
        val f = sampleFiles.getOrNull(sampleSpinner.selectedItemPosition)
        syncTrack?.release()
        syncTrack = if (f != null) makeTrack(f) else null
        syncGen++
        syncRunning = true
        syncStartBtn.text = "Stop"
        if (appIsMaster) {
            syncStatus.text = "Master: sending clock at $masterBpm BPM"
            startMaster(syncGen)
        } else {
            tickCount = 0
            lastTick = 0
            ti = 0
            tiFilled = 0
            avgTickNs = 0
            syncStatus.text = "Waiting for MIDI clock from the FM-1..."
        }
    }

    private fun stopSync() {
        syncRunning = false
        syncGen++
        syncStartBtn.text = "Start"
        try {
            syncTrack?.stop()
        } catch (e: Exception) {
        }
        syncStatus.text = "Stopped"
    }

    private fun triggerSync() {
        val t = syncTrack ?: return
        try {
            t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: Exception) {
        }
    }

    private fun sendMidi(b: ByteArray) {
        try {
            sendPort?.send(b, 0, b.size)
        } catch (e: Exception) {
        }
    }

    private fun waitUntil(t: Long, gen: Int) {
        while (syncRunning && gen == syncGen) {
            val d = t - System.nanoTime()
            if (d <= 0) return
            if (d > 2_000_000L) LockSupport.parkNanos(d - 1_000_000L) else Thread.yield()
        }
    }

    // App is the master: send MIDI clock (24 per beat) and trigger the sample
    private fun startMaster(gen: Int) {
        val th = Thread {
            val clk = byteArrayOf(0xF8.toByte())
            val startMsg = byteArrayOf(0xFA.toByte())
            val stopMsg = byteArrayOf(0xFC.toByte())
            var tickTime = System.nanoTime() + offsetMs * 1_000_000L + 60_000_000L
            var k = 0L
            var nextTrig = 0L
            while (syncRunning && gen == syncGen) {
                val tickNs = 60_000_000_000L / (masterBpm * 24L)
                val offNs = offsetMs * 1_000_000L
                val trigTicks = beatsPerTrig * 24L
                val trigTime = tickTime + (nextTrig - k) * tickNs - offNs
                if (trigTime <= tickTime) {
                    waitUntil(trigTime, gen)
                    if (!(syncRunning && gen == syncGen)) break
                    triggerSync()
                    nextTrig += trigTicks
                    continue
                }
                waitUntil(tickTime, gen)
                if (!(syncRunning && gen == syncGen)) break
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

    // FM-1 is the master: follow incoming MIDI clock
    private val clockReceiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val now = System.nanoTime()
            for (i in offset until offset + count) {
                when (msg[i].toInt() and 0xFF) {
                    0xF8 -> onClockTick(now)
                    0xFA -> {
                        tickCount = 0
                    }
                    0xFC -> {
                        lastTick = 0
                        try {
                            syncTrack?.stop()
                        } catch (e: Exception) {
                        }
                    }
                }
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
        if (avgTickNs > 0) followBpm = 60_000_000_000.0 / (avgTickNs * 24.0)

        if (syncRunning && !appIsMaster) {
            val trigTicks = beatsPerTrig * 24L
            val offTicks =
                if (avgTickNs > 0) (offsetMs * 1_000_000.0 / avgTickNs).roundToInt() else 0
            if ((tickCount + offTicks) % trigTicks == 0L) triggerSync()
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
