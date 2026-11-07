package io.github.hsw.inkeasel

import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The rules of "Share → InkEasel" ([ShareActivity]), free of Android types: which shared URIs are taken, the
 * file name a shared picture gets in the pictures folder, the size cap of the copy, what happens to the saved
 * picture ([step]), and when a share that waited for the Bluetooth permission is still sent.
 */
object ShareRules {
    /** The largest shared picture copied; a bigger stream is cut off and the copy dropped. */
    const val MAX_BYTES: Long = 50L * 1024 * 1024

    /** A share that waits for the permissions (and a frame) longer than this is not sent any more. */
    const val PENDING_MAX_AGE_MS: Long = 60L * 60 * 1000

    /**
     * Only a `content://` URI of another app is taken: a `file://` one would make the app read a path of the
     * sender's choosing with its own rights, and a URI of this app's own provider would hand it its own files.
     */
    fun acceptShare(scheme: String?, authority: String?, ownPackage: String): Boolean {
        if (!scheme.equals("content", ignoreCase = true) || authority.isNullOrBlank()) return false
        return authority.split(';').none { it == ownPackage || it.startsWith("$ownPackage.") }
    }

    /**
     * The file name a shared picture is saved under: the sender's display name, made safe for a file name and
     * without its extension, or `shared-yyyyMMdd-HHmmss` (in [zone]) when it has none; the extension comes
     * from the [mime] type (`ContentResolver.getType` first, then the intent's type), else from the display
     * name, else `jpg`. A name that [exists] already gets `-2`, `-3`, … before the extension.
     */
    fun sharedFileName(
        displayName: String?,
        mime: String?,
        nowMs: Long,
        exists: (String) -> Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val leaf = displayName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        val dot = leaf.lastIndexOf('.')
        val (stem, nameExt) = if (dot > 0) leaf.substring(0, dot) to leaf.substring(dot + 1) else leaf to null
        val ext = extForMime(mime) ?: normalizeExt(nameExt) ?: "jpg"
        val base = sanitize(stem).ifEmpty {
            "shared-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(zone).format(Instant.ofEpochMilli(nowMs))
        }
        var name = "$base.$ext"
        var n = 2
        while (exists(name)) name = "$base-${n++}.$ext"
        return name
    }

    /** The extension for an image MIME type the app can show; `null` for a generic or unknown one. */
    fun extForMime(mime: String?): String? = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
        "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp", "image/x-ms-bmp" -> "bmp"
        "image/heic", "image/heif" -> "heic"
        else -> null
    }

    private fun normalizeExt(ext: String?): String? = when (val e = ext?.lowercase()) {
        "jpeg", "jpg" -> "jpg"
        "heif", "heic" -> "heic"
        "png", "webp", "gif", "bmp" -> e
        else -> null
    }

    /** No path separators, reserved or control characters, no leading/trailing dots or spaces, at most 80 chars. */
    private fun sanitize(stem: String): String =
        stem.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim(' ', '.').take(80).trim(' ', '.')

    /**
     * Copy [input] to [output], at most [cap] bytes; [check] runs before every block (it throws to cancel).
     * A longer stream fails with a [TxtException] (`share_too_big`) once it passes the cap. Returns the size.
     */
    fun copyCapped(input: InputStream, output: OutputStream, cap: Long = MAX_BYTES, check: () -> Unit = {}): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            check()
            val n = input.read(buf)
            if (n < 0) return total
            total += n
            if (total > cap) throw TxtException(txt(R.string.share_too_big, (cap / (1024 * 1024)).toInt()))
            output.write(buf, 0, n)
        }
    }

    /**
     * Whether a share left waiting for the Bluetooth permission ([savedAtMs]) is sent now: only while its file
     * still exists and it is at most [PENDING_MAX_AGE_MS] old (a clock set back counts as stale). Otherwise
     * it is dropped; the picture stays in "My pictures" either way.
     */
    fun sendPending(savedAtMs: Long, nowMs: Long, fileExists: Boolean): Boolean =
        fileExists && nowMs - savedAtMs in 0..PENDING_MAX_AGE_MS

    /** What [ShareActivity] does with a picture it has saved. */
    enum class Step {
        /** Hand it to the upload service. */
        SEND,

        /** Remember it as the pending share and open the app, which asks for the permissions. */
        WAIT_IN_APP,

        /**
         * Keep it in "My pictures" and say that no frame is chosen. Said by the share itself, while it is in
         * the foreground: the upload service's own refusal toast comes after the share has finished, when
         * Android 13+ suppresses toasts of an app without notification permission.
         */
        NO_FRAME,
    }

    /** The [Step] for a saved share: the permissions first (that path also waits for a frame), then a frame. */
    fun step(permitted: Boolean, frameSelected: Boolean): Step = when {
        !permitted -> Step.WAIT_IN_APP
        !frameSelected -> Step.NO_FRAME
        else -> Step.SEND
    }
}
