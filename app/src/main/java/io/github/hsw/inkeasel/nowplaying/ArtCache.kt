package io.github.hsw.inkeasel.nowplaying

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Disk cache for MediaSession artwork bitmaps, in [dir] (`cacheDir/nowplaying` in production).
 *
 * The key of a bitmap is the SHA-1 of its raw pixels ([keyOf]); its file is `<sha1>.png` ([fileFor]),
 * written only when it is missing ([store]). At most [maxFiles] files are kept: storing a new one deletes
 * the least recently stored or reused ones, except the keys the caller still needs (a poster waiting for its
 * start or upload), which stay even if that leaves more than [maxFiles]. Free of Android types: the caller copies the pixels out of the
 * `Bitmap` and passes the PNG encoder as a lambda, so all of this runs as a plain JVM unit test.
 */
class ArtCache(private val dir: File, private val maxFiles: Int = MAX_FILES) {

    /** The cache file for [key]. */
    fun fileFor(key: String): File = File(dir, fileName(key))

    /**
     * Return the file for [key], calling [write] to create it only if it does not exist yet. The PNG goes
     * to a temporary file first and is renamed into place, so a crash or a failed encode never leaves a
     * truncated `<sha1>.png` behind. Returns `null` if [write] returns `false` or throws (an I/O error, or
     * e.g. an `IllegalStateException` from encoding a recycled bitmap). Pruning after a new file never
     * deletes the files of [inUse] (keys still referenced, e.g. by a pending start or a queued upload).
     */
    fun store(key: String, inUse: Set<String> = emptySet(), write: (OutputStream) -> Boolean): File? {
        val file = fileFor(key)
        if (file.isFile) {
            file.setLastModified(System.currentTimeMillis()) // reused: keep it longest
            return file
        }
        dir.mkdirs()
        val tmp = File(dir, "${fileName(key)}.tmp")
        val ok = try {
            tmp.outputStream().buffered().use(write)
        } catch (e: Exception) {
            false
        }
        // renameTo fails if a concurrent writer put the same file in place first; that file is as good
        if (ok && (tmp.renameTo(file) || file.isFile)) {
            tmp.delete()
            prune(keep = inUse.mapTo(HashSet()) { fileFor(it) } + file)
            return file
        }
        tmp.delete()
        return null
    }

    /** Delete the oldest `.png` files so that at most [maxFiles] stay, never one of [keep] (counted first). */
    private fun prune(keep: Set<File>) {
        val all = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".png") } ?: return
        val others = all.filter { it !in keep }
        val room = maxOf(0, maxFiles - (all.size - others.size))
        if (others.size <= room) return
        others.sortedByDescending { it.lastModified() }.drop(room).forEach { it.delete() }
    }

    companion object {
        /** Files kept by default: one per distinct artwork, up to about 1 MB each. */
        const val MAX_FILES = 20

        /** SHA-1 of [pixels] as 40 lower-case hex digits. */
        fun keyOf(pixels: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(pixels).joinToString("") { "%02x".format(it) }

        /** The file name for [key]. */
        fun fileName(key: String): String = "$key.png"
    }
}
