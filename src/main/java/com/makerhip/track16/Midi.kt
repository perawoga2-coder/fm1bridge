package com.makerhip.track16

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.ParcelUuid
import java.util.UUID

/** One MIDI message: status (with channel), d1, d2; realtime messages have d1 = d2 = 0 */
class Msg(val status: Int, val d1: Int, val d2: Int) {
    val type get() = if (status >= 0xF0) status else status and 0xF0
    val ch get() = status and 0x0F
}

/** A connected MIDI device (Bluetooth or USB): what it sends comes in through [out], what we send goes to [inp] */
class Conn(val name: String, val bt: Boolean, val dev: MidiDevice, val id: Int) {
    var inp: MidiInputPort? = null
    var out: MidiOutputPort? = null
    val smc get() = name.contains("SMC", ignoreCase = true)
    // the parser's state (the MIDI thread only)
    var run = 0
    var need = 0
    var have = 0
    var b1 = 0
    var sysex = false
}

/** Bluetooth LE MIDI and USB MIDI: scan, connect, receive (parsed, delivered on the UI thread), send, the clock. */
class MidiHub(private val ctx: Context, private val ui: Handler, private val onMsg: (Conn, Msg) -> Unit,
              private val onChange: () -> Unit) {
    private val mm = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager?
    val conns = ArrayList<Conn>()
    val found = LinkedHashMap<String, Pair<String, BluetoothDevice>>()   // address -> name, device (the last scan)
    var scanning = false
        private set
    private var scanCb: ScanCallback? = null

    val available get() = mm != null

    init {
        mm?.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) {
                if (info.type == MidiDeviceInfo.TYPE_USB) openUsb(info)
            }
            override fun onDeviceRemoved(info: MidiDeviceInfo) {
                val c = conns.firstOrNull { it.id == info.id } ?: return
                close(c)
            }
        }, ui)
        mm?.devices?.filter { it.type == MidiDeviceInfo.TYPE_USB }?.forEach { openUsb(it) }
    }

    private fun nameOf(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device"

    private fun openUsb(info: MidiDeviceInfo) {
        if (conns.any { it.id == info.id }) return
        mm?.openDevice(info, { dev -> if (dev != null) attach(dev, false) }, ui)
    }

    /** Connects a Bluetooth MIDI device (found by a scan or remembered) */
    @SuppressLint("MissingPermission")
    fun connectBt(device: BluetoothDevice) {
        mm?.openBluetoothDevice(device, { dev -> if (dev != null) attach(dev, true) else onChange() }, ui)
    }

    @SuppressLint("MissingPermission")
    fun connectBt(address: String): Boolean {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager? ?: return false
        val ad = bm.adapter ?: return false
        if (!ad.isEnabled) return false
        connectBt(ad.getRemoteDevice(address))
        return true
    }

    private fun attach(dev: MidiDevice, bt: Boolean) {
        val info = dev.info
        if (conns.any { it.id == info.id }) { dev.close(); return }
        val c = Conn(nameOf(info), bt, dev, info.id)
        val outIdx = info.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }?.portNumber
        val inIdx = info.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }?.portNumber
        if (inIdx != null) c.inp = dev.openInputPort(inIdx)
        if (outIdx != null) {
            c.out = dev.openOutputPort(outIdx)
            c.out?.connect(object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) = parse(c, msg, offset, count)
            })
        }
        conns.add(c)
        onChange()
    }

    fun close(c: Conn) {
        try { c.out?.close() } catch (_: Exception) {}
        try { c.inp?.close() } catch (_: Exception) {}
        try { c.dev.close() } catch (_: Exception) {}
        conns.remove(c)
        onChange()
    }

    fun closeAll() {
        stopScan()
        for (c in ArrayList(conns)) close(c)
    }

    /** bytes -> messages (running status, realtime inside messages, SysEx skipped) -> the UI thread */
    private fun parse(c: Conn, b: ByteArray, off: Int, n: Int) {
        for (k in off until off + n) {
            val v = b[k].toInt() and 0xFF
            if (v >= 0xF8) { val m = Msg(v, 0, 0); ui.post { onMsg(c, m) }; continue }
            if (v == 0xF0) { c.sysex = true; continue }
            if (v == 0xF7) { c.sysex = false; continue }
            if (v >= 0x80) {
                c.sysex = false
                if (v >= 0xF0) { c.run = 0; continue }            // (song position, MTC ...: not used)
                c.run = v
                c.need = if ((v and 0xF0) == 0xC0 || (v and 0xF0) == 0xD0) 1 else 2
                c.have = 0
                continue
            }
            if (c.sysex || c.run == 0) continue
            if (c.have == 0) {
                c.b1 = v
                c.have = 1
                if (c.need == 1) { val m = Msg(c.run, v, 0); c.have = 0; ui.post { onMsg(c, m) } }
            } else {
                val m = Msg(c.run, c.b1, v)
                c.have = 0
                ui.post { onMsg(c, m) }
            }
        }
    }

    /** sends one message to every device, or every device but [skip] */
    fun send(status: Int, d1: Int = -1, d2: Int = -1, skip: Conn? = null) {
        val n = if (d1 < 0) 1 else if (d2 < 0) 2 else 3
        val b = byteArrayOf(status.toByte(), d1.toByte(), d2.toByte())
        for (c in conns) if (c !== skip) try { c.inp?.send(b, 0, n) } catch (_: Exception) {}
    }

    fun sendTo(c: Conn, status: Int, d1: Int, d2: Int) {
        try { c.inp?.send(byteArrayOf(status.toByte(), d1.toByte(), d2.toByte()), 0, 3) } catch (_: Exception) {}
    }

    // ---------------- the Bluetooth scan ----------------

    @SuppressLint("MissingPermission")
    fun startScan(): String? {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager? ?: return "No Bluetooth on this device"
        val ad = bm.adapter ?: return "No Bluetooth on this device"
        if (!ad.isEnabled) return "off"
        val sc = ad.bluetoothLeScanner ?: return "off"
        stopScan()
        found.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val a = r.device.address
                val name = r.scanRecord?.deviceName ?: r.device.name ?: a
                ui.post { found[a] = Pair(name, r.device); onChange() }
            }
            override fun onScanFailed(errorCode: Int) { ui.post { scanning = false; onChange() } }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BLE_MIDI)).build()
        sc.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        scanCb = cb
        scanning = true
        ui.postDelayed({ stopScan(); onChange() }, 12000)
        onChange()
        return null
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = scanCb ?: return
        scanCb = null
        scanning = false
        try {
            val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
            bm?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {}
    }

    // ---------------- the clock (24 a quarter note, Start / Continue / Stop) ----------------

    @Volatile private var clockRun = false
    private var clockThread: Thread? = null

    /** starts sending the clock at the song's tempo from [pos16] (sixteenths from the start) */
    fun clockStart(bpm: () -> Int, pos16: Int) {
        clockStop(false)
        if (pos16 <= 0) send(0xFA) else {
            send(0xF2, pos16 and 0x7F, (pos16 shr 7) and 0x7F)
            send(0xFB)
        }
        clockRun = true
        clockThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            var next = System.nanoTime()
            while (clockRun) {
                send(0xF8)
                next += 60_000_000_000L / (bpm().coerceIn(30, 300) * 24L)
                val wait = next - System.nanoTime()
                if (wait > 0) try { Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) } catch (_: InterruptedException) { break }
                else if (wait < -100_000_000L) next = System.nanoTime()      // (far behind: no burst)
            }
        }.also { it.start() }
    }

    fun clockStop(sendStop: Boolean = true) {
        clockRun = false
        clockThread?.interrupt()
        clockThread = null
        if (sendStop) send(0xFC)
    }

    val clocking get() = clockRun

    companion object {
        val BLE_MIDI: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    }
}
