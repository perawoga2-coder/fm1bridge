package com.makerhip.track16

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 0.11: BACKUP EVERYTHING and RESTORE (as Burger Studio 3.65). Everything the app keeps is in two folders of its own: files/ (the songs,
 * their tracks, the INBOX) and shared_prefs/
 * (its settings, under the name track16: no name of the package's, so a backup goes
 * into another build of the app too). A backup is one .zip of both, with BACKUP_TAG first. A restore reads the whole
 * zip into a folder beside them first, and only when all of it came puts it in their place: a bad file leaves the app
 * as it was. Then the app must close at once without saving (MainActivity), so nothing it holds is written over it.
 */
object Backup {
    const val TAG_NAME = "16track-backup.txt"
    const val BACKUP_TAG = "16 Track backup 1"

    class Result(val files: Int, val bytes: Long)

    /** Both folders into one zip on [out] (not closed). */
    fun write(filesDir: File, prefsDir: File, out: OutputStream, version: String): Result {
        var n = 0
        var total = 0L
        val z = ZipOutputStream(out)
        z.putNextEntry(ZipEntry(TAG_NAME))
        z.write("$BACKUP_TAG\n$version\n".toByteArray())
        z.closeEntry()
        fun add(root: File, prefix: String) {
            if (!root.isDirectory) return
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                z.putNextEntry(ZipEntry("$prefix/$rel"))
                f.inputStream().use { total += it.copyTo(z, 65536) }
                z.closeEntry()
                n++
            }
        }
        add(filesDir, "files")
        add(prefsDir, "shared_prefs")
        z.finish()
        return Result(n, total)
    }

    /** The zip on [inp] in place of both folders. Throws (and changes nothing) when it is not a whole backup. */
    fun restore(filesDir: File, prefsDir: File, inp: InputStream): Result {
        val stage = File(filesDir.parentFile, "restore_stage")
        stage.deleteRecursively()
        stage.mkdirs()
        var tagged = false
        var n = 0
        var total = 0L
        try {
            ZipInputStream(inp).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name
                    if (name == TAG_NAME) {
                        tagged = z.readBytes().toString(Charsets.UTF_8).startsWith(BACKUP_TAG)
                        continue
                    }
                    if (e.isDirectory) continue
                    if (!(name.startsWith("files/") || name.startsWith("shared_prefs/")))
                        throw IllegalArgumentException("not a 16 Track backup ($name)")
                    val dst = File(stage, name)
                    // (a name with ".." would land outside: refused)
                    if (!dst.canonicalPath.startsWith(stage.canonicalPath + File.separator))
                        throw IllegalArgumentException("a bad name in the backup ($name)")
                    dst.parentFile?.mkdirs()
                    dst.outputStream().use { total += z.copyTo(it, 65536) }
                    n++
                }
            }
            if (!tagged) throw IllegalArgumentException("not a 16 Track backup")
            // all of it came: now in place of what is there
            for ((dir, sub) in listOf(filesDir to "files", prefsDir to "shared_prefs")) {
                val from = File(stage, sub)
                dir.deleteRecursively()
                if (from.isDirectory) {
                    if (!from.renameTo(dir)) {
                        from.copyRecursively(dir, overwrite = true)
                    }
                } else {
                    dir.mkdirs()
                }
            }
        } finally {
            stage.deleteRecursively()
        }
        return Result(n, total)
    }
}
