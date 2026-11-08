package io.github.hsw.inkeasel.nowplaying

/**
 * One "what is playing" event, from the MediaSession metadata of any app (`NowPlayingListener`).
 *
 * [key] identifies the picture, not the title: episodes of a series share one poster, so autoplay to the
 * next episode yields the same key and the frame is not redrawn. PLAYING always carries [art] and [key];
 * PAUSED and STOPPED may come without them.
 *
 * [session] identifies the session that reported the event (a MediaSession, as tracked by the listener), so
 * an end is matched to the playback it ends whatever its key. `null` means no session identity, which
 * matches any session of its app.
 *
 * Everything here is free of Android types so it runs as a plain JVM unit test.
 */
data class Playing(
    val pkg: String,
    val key: String?,
    val title: String,
    val art: Art?,
    val state: State,
    val session: String? = null,
) {
    enum class State { PLAYING, PAUSED, STOPPED }

    companion object {
        /**
         * Map a `PlaybackState` code. Transient states (fast-forward, rewind, buffering, connecting, skip)
         * return `null`: they produce no event, so a seek or a rebuffer does not stop the start timer.
         */
        fun mapState(code: Int): State? = when (code) {
            PlaybackCodes.PLAYING -> State.PLAYING
            PlaybackCodes.PAUSED -> State.PAUSED
            PlaybackCodes.NONE, PlaybackCodes.STOPPED, PlaybackCodes.ERROR -> State.STOPPED
            else -> null
        }

        /**
         * The last state code that maps to an event, after a session's state became [code] ([prev] is the
         * previous result): a transient state (seek, rebuffer) keeps [prev], so a seeking player still counts
         * as playing when a sibling session of its app ends; with no [prev] (a session first seen seeking or
         * buffering, e.g. in the snapshot after a reconnect) it counts as PLAYING: a transient state means
         * playback is under way or about to resume (ExoPlayer reports a buffer while paused as PAUSED). No
         * state at all (`null`) clears it.
         */
        fun effectiveState(prev: Int?, code: Int?): Int? = when {
            code == null -> null
            mapState(code) != null -> code
            else -> prev ?: PlaybackCodes.PLAYING
        }

        /**
         * Build an event from MediaSession metadata.
         *
         * [strings] holds the text values of the metadata, keyed by `android.media.metadata.*` names
         * ([MetaKeys]). [bitmapKey] and [bitmapPath] describe the bitmap the caller picked in
         * [MetaKeys.BITMAPS] order and cached (null if the metadata has none). Art priority: `ART_URI` →
         * `ALBUM_ART_URI` → `DISPLAY_ICON_URI` → bitmap. Only https URIs are used: another app's
         * `content://` (or `file://`, `android.resource://`) URI is not readable here, and a plain
         * `http://` one cannot be fetched (the manifest refuses cleartext traffic on every version),
         * so they fall through to the bitmap, which the framework has already downsized to about 640 px.
         *
         * Returns `null` for a transient [state] and for PLAYING without usable art.
         */
        fun fromMetadata(
            pkg: String,
            strings: Map<String, String?>,
            bitmapKey: String?,
            bitmapPath: String?,
            state: Int,
        ): Playing? {
            val st = mapState(state) ?: return null
            val uri = httpsArtUri(strings)
            val bmKey = bitmapKey.orNull()
            val bmPath = bitmapPath.orNull()
            val (key, art) = when {
                uri != null -> uri to Art.Url(uri)
                bmKey != null && bmPath != null -> bmKey to Art.File(bmPath)
                else -> null to null
            }
            if (st == State.PLAYING && art == null) return null
            return Playing(pkg, key, title(strings), art, st)
        }

        /**
         * The effective event of a session ([SessionView]) for its metadata and effective [state]
         * ([effectiveState], so never transient): [fromMetadata], except that no playback state (`null`) is
         * STOPPED, and PLAYING without usable art is STOPPED without a key. The session moved on to something
         * without a picture, so the previous film's start must be cancelled and its poster reverted; no event
         * would leave the old film playing as far as the controller knows.
         */
        fun fromSession(
            pkg: String,
            strings: Map<String, String?>,
            bitmapKey: String?,
            bitmapPath: String?,
            state: Int?,
        ): Playing = fromMetadata(pkg, strings, bitmapKey, bitmapPath, state ?: PlaybackCodes.NONE)
            ?: Playing(pkg, null, title(strings), null, State.STOPPED)

        /**
         * Whether [fromMetadata] would fall back to a bitmap, i.e. [strings] carry no usable https art
         * URI. The listener skips the costly bitmap hash when this is false.
         */
        fun needsBitmap(strings: Map<String, String?>): Boolean = httpsArtUri(strings) == null

        private fun httpsArtUri(strings: Map<String, String?>): String? =
            MetaKeys.URIS.firstNotNullOfOrNull { k -> strings[k].orNull()?.takeIf(::isHttps) }

        private fun title(strings: Map<String, String?>): String =
            strings[MetaKeys.DISPLAY_TITLE].orNull() ?: strings[MetaKeys.TITLE].orNull() ?: ""


        private fun String?.orNull(): String? = this?.takeIf { it.isNotBlank() }

        private fun isHttps(uri: String): Boolean = uri.startsWith("https://", ignoreCase = true)
    }
}

/** Where the picture comes from: a remote image or a local file (a cached bitmap, a photo). */
sealed interface Art {
    data class Url(val url: String) : Art
    data class File(val path: String) : Art
}

/** `android.media.metadata.*` keys, as strings so the mappers need no Android classes. */
object MetaKeys {
    const val TITLE = "android.media.metadata.TITLE"
    const val DISPLAY_TITLE = "android.media.metadata.DISPLAY_TITLE"
    const val ART_URI = "android.media.metadata.ART_URI"
    const val ALBUM_ART_URI = "android.media.metadata.ALBUM_ART_URI"
    const val DISPLAY_ICON_URI = "android.media.metadata.DISPLAY_ICON_URI"
    const val ART = "android.media.metadata.ART"
    const val ALBUM_ART = "android.media.metadata.ALBUM_ART"
    const val DISPLAY_ICON = "android.media.metadata.DISPLAY_ICON"

    /** URI keys in priority order. */
    val URIS = listOf(ART_URI, ALBUM_ART_URI, DISPLAY_ICON_URI)

    /** Bitmap keys in priority order; the listener uses the first present one. */
    val BITMAPS = listOf(ART, ALBUM_ART, DISPLAY_ICON)
}

/** `android.media.session.PlaybackState` codes, as plain ints. */
object PlaybackCodes {
    const val NONE = 0
    const val STOPPED = 1
    const val PAUSED = 2
    const val PLAYING = 3
    const val ERROR = 7
}
