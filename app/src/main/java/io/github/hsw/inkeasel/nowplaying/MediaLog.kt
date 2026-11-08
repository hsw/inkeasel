package io.github.hsw.inkeasel.nowplaying

import io.github.hsw.inkeasel.BuildConfig

/**
 * What plays, as the log may name it. A debug build logs the titles and artwork keys of now-playing media (a
 * key is the art URL or the SHA-1 of the artwork pixels) as they are, for development; a release build logs a
 * placeholder instead ([details] off), since logcat is readable over adb and in bug reports. Every log line that
 * carries a now-playing title, a poster key (also as `onFrame`) or an artwork address goes through here.
 *
 * Package names, session ids and the user's own picks (`default:` and `manual:` keys, catalog titles, shared
 * file names) are not what plays: [key] keeps those keys, and the callers log the rest as is. Free of Android
 * types; [BuildConfig] is a generated constant.
 */
object MediaLog {
    /** Log what plays as it is: on in debug builds (and by default in the debug unit tests), off in release. */
    @Volatile
    var details: Boolean = BuildConfig.DEBUG

    const val TITLE = "<title>"
    const val KEY = "<key>"

    /** A now-playing [title], or [TITLE]. */
    fun title(title: String): String = if (details) title else TITLE

    /** An `onFrame` or request key: a poster key ([FrameController.isAuto]) becomes [KEY], any other stays. */
    fun key(key: String?): String? = if (details || !FrameController.isAuto(key)) key else KEY

    /**
     * The error of a poster upload, for the log: as it is, or a copy whose messages (its own and its causes')
     * have every one of [hide] (the key, the art URL or path) replaced by [KEY]. The copy prints as the
     * original class and keeps its stack trace, so the log still tells what failed and where.
     */
    fun error(e: Throwable?, vararg hide: String?): Throwable? {
        if (details || e == null) return e
        val words = hide.filterNotNull().filter { it.isNotBlank() }.sortedByDescending { it.length }
        return redacted(e, words, 0)
    }

    private fun redacted(e: Throwable, words: List<String>, depth: Int): Throwable {
        val msg = e.message?.let { m -> words.fold(m) { s, w -> s.replace(w, KEY) } }
        val cause = e.cause?.takeIf { it !== e && depth < MAX_CAUSES }?.let { redacted(it, words, depth + 1) }
        return Redacted(e.javaClass.name, msg, cause).also { it.stackTrace = e.stackTrace }
    }

    /** Causes copied at most, against a cycle. */
    private const val MAX_CAUSES = 8

    private class Redacted(private val type: String, message: String?, cause: Throwable?) : Exception(message, cause) {
        override fun toString(): String = message?.let { "$type: $it" } ?: type
    }
}
