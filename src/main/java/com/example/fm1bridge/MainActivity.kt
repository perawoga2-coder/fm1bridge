package com.example.fm1bridge

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.midi.MidiManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var audioManager: AudioManager
    private var usbInput: AudioDeviceInfo? = null
    private var sampleRate = 48000
    private var channelMask = AudioFormat.CHANNEL_IN_MONO
    private var track: AudioTrack? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var recording = false

    private val channels get() = if (channelMask == AudioFormat.CHANNEL_IN_MONO) 1 else 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        status = TextView(this).apply { textSize = 16f; text = "Press Scan" }
        root.addView(status)

        fun addButton(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }
        addButton("1. Scan for FM-1") { scan() }
        addButton("2. Record") { startRecording() }
        addButton("3. Stop") { recording = false }
        addButton("4. Play") { play() }
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    // Find a USB audio input and list USB/MIDI devices
    private fun scan() {
        usbInput = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val midi = getSystemService(Context.MIDI_SERVICE) as MidiManager
        val midiNames = midi.devices.map { it.properties.getString("name") ?: "unknown" }

        usbInput?.let { dev ->
            val rates = dev.sampleRates
            sampleRate = when {
                rates.isEmpty() || 48000 in rates -> 48000
                44100 in rates -> 44100
                else -> rates.maxOrNull() ?: 44100
            }
            val ch = dev.channelCounts
            channelMask = if (ch.isEmpty() || 1 in ch) {
                AudioFormat.CHANNEL_IN_MONO
            } else {
                AudioFormat.CHANNEL_IN_STEREO
            }
        }

        status.text = buildString {
            append("USB audio input: ")
            append(usbInput?.productName ?: "NOT FOUND (will use built-in mic)")
            append("\nSample rate: $sampleRate Hz, channels: $channels")
            append("\nMIDI devices: ")
            append(if (midiNames.isEmpty()) "none" else midiNames.joinToString())
        }
    }

    private fun startRecording() {
        if (recording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Microphone permission needed"
            return
        }
        pcm.reset()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .build()
        usbInput?.let { rec.setPreferredDevice(it) }

        recording = true
        status.text = "Recording..."
        thread {
            val buf = ByteArray(minBuf)
            rec.startRecording()
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
            }
            rec.stop()
            rec.release()
            val data = synchronized(pcm) { pcm.toByteArray() }
            val seconds = data.size / (2.0 * channels * sampleRate)
            runOnUiThread {
                status.text = "Recorded %.1f s, peak level %d%%".format(seconds, peakPercent(data))
            }
        }
    }

    // 0% means silence: a quick check that audio really arrived
    private fun peakPercent(data: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < data.size) {
            val s = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            peak = maxOf(peak, abs(s))
            i += 2
        }
        return peak * 100 / 32768
    }

    private fun play() {
        if (recording) return
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.isEmpty()) return
        track?.release()
        val outMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(outMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(data.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(data, 0, data.size)
        t.play()
        track = t
    }

    override fun onDestroy() {
        recording = false
        track?.release()
        super.onDestroy()
    }
}
