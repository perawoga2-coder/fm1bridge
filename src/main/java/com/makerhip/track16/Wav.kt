package com.makerhip.track16

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV in and out: 16-bit PCM written; 8 / 16 / 24 / 32-bit PCM and 32-bit float read, any rate, mixed to mono. */
object Wav {
    fun header(out: OutputStream, rate: Int, channels: Int, frames: Int) {
        val data = frames * channels * 2
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(channels.toShort())
        b.putInt(rate); b.putInt(rate * channels * 2); b.putShort((channels * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        out.write(b.array())
    }

    /** -> mono samples at `rate` (linear resampling), or null if it is not a WAV this reads */
    fun readMono(input: InputStream, rate: Int): ShortArray? {
        val all = input.readBytes()
        val b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
        if (all.size < 12 || String(all, 0, 4) != "RIFF" || String(all, 8, 4) != "WAVE") return null
        var p = 12
        var fmt = 0; var ch = 1; var sr = 0; var bits = 16
        var dataOff = -1; var dataLen = 0
        while (p + 8 <= all.size) {
            val id = String(all, p, 4)
            val len = b.getInt(p + 4)
            if (id == "fmt ") {
                fmt = b.getShort(p + 8).toInt() and 0xFFFF
                ch = b.getShort(p + 10).toInt()
                sr = b.getInt(p + 12)
                bits = b.getShort(p + 22).toInt()
                if (fmt == 0xFFFE && len >= 40) fmt = b.getShort(p + 32).toInt() and 0xFFFF   // (WAVE_FORMAT_EXTENSIBLE)
            } else if (id == "data") {
                dataOff = p + 8
                dataLen = minOf(len, all.size - dataOff)
                break
            }
            p += 8 + len + (len and 1)
        }
        if (dataOff < 0 || sr <= 0 || ch <= 0) return null
        val bps = bits / 8
        val frames = dataLen / (bps * ch)
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var s = 0f
            for (c in 0 until ch) {
                val o = dataOff + (f * ch + c) * bps
                s += when {
                    fmt == 3 && bits == 32 -> b.getFloat(o)
                    bits == 16 -> b.getShort(o) / 32768f
                    bits == 24 -> ((all[o].toInt() and 0xFF) or ((all[o + 1].toInt() and 0xFF) shl 8) or (all[o + 2].toInt() shl 16)) / 8388608f
                    bits == 32 -> b.getInt(o) / 2147483648f
                    bits == 8 -> ((all[o].toInt() and 0xFF) - 128) / 128f
                    else -> return null
                }
            }
            mono[f] = s / ch
        }
        val n = (frames.toLong() * rate / sr).toInt()
        val out = ShortArray(n)
        for (k in 0 until n) {
            val x = k.toDouble() * sr / rate
            val i = x.toInt()
            val fr = (x - i).toFloat()
            val a = mono[minOf(i, frames - 1)]
            val c = mono[minOf(i + 1, frames - 1)]
            out[k] = ((a + (c - a) * fr).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        return out
    }
}
