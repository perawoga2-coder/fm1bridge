package com.makerhip.track16

import android.content.Context
import android.net.Uri

/** The Maker Hip INBOX (INBOX.md): boxes of stems that Burger Studio keeps for 16 Track to SPLIT into its tracks. */
object Inbox {
    const val AUTH = "com.makerhip.burger.inbox"
    private val BOXES: Uri = Uri.parse("content://$AUTH/boxes")

    class Box(val id: String, val title: String, val kind: String, val bpm: Int, val bars: Int, val meter: String,
              val rate: Int, val created: Long, val tracks: Int)

    class Stem(val idx: Int, val name: String, val color: Int, val gain: Float, val pan: Float)

    /** Burger Studio is installed and answers (its provider is visible to this app) */
    fun available(ctx: Context): Boolean = try {
        ctx.packageManager.resolveContentProvider(AUTH, 0) != null
    } catch (e: Exception) { false }

    fun boxes(ctx: Context): List<Box> {
        val l = ArrayList<Box>()
        if (!available(ctx)) return l
        try {
            ctx.contentResolver.query(BOXES, null, null, null, null)?.use { c ->
                fun s(n: String) = c.getColumnIndex(n).let { if (it < 0) "" else c.getString(it) ?: "" }
                fun i(n: String) = c.getColumnIndex(n).let { if (it < 0) 0 else c.getInt(it) }
                fun lg(n: String) = c.getColumnIndex(n).let { if (it < 0) 0L else c.getLong(it) }
                while (c.moveToNext()) l.add(Box(s("_id"), s("title"), s("kind"), i("bpm"), i("bars"), s("meter"),
                    i("rate"), lg("created"), i("tracks")))
            }
        } catch (e: Exception) {}           // (no permission: an older Burger Studio, or not the Maker Hip key)
        return l
    }

    fun stems(ctx: Context, box: Box): List<Stem> {
        val l = ArrayList<Stem>()
        try {
            ctx.contentResolver.query(Uri.parse("content://$AUTH/boxes/${box.id}/tracks"), null, null, null, null)?.use { c ->
                fun s(n: String) = c.getColumnIndex(n).let { if (it < 0) "" else c.getString(it) ?: "" }
                fun i(n: String) = c.getColumnIndex(n).let { if (it < 0) 0 else c.getInt(it) }
                fun f(n: String, d: Float) = c.getColumnIndex(n).let { if (it < 0) d else c.getFloat(it) }
                while (c.moveToNext()) l.add(Stem(i("idx"), s("name"), i("color"), f("gain", 0.8f), f("pan", 0f)))
            }
        } catch (e: Exception) {}
        return l
    }

    /** one stem's audio, mono at `rate` (resampled), or null */
    fun audio(ctx: Context, box: Box, stem: Stem, rate: Int): ShortArray? = try {
        ctx.contentResolver.openInputStream(Uri.parse("content://$AUTH/boxes/${box.id}/tracks/${stem.idx}"))?.use { Wav.readMono(it, rate) }
    } catch (e: Exception) { null }

    fun delete(ctx: Context, box: Box): Boolean = try {
        ctx.contentResolver.delete(Uri.parse("content://$AUTH/boxes/${box.id}"), null, null) > 0
    } catch (e: Exception) { false }
}
