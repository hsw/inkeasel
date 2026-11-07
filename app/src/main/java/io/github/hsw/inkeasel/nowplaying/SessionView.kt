package io.github.hsw.inkeasel.nowplaying

/**
 * One MediaSession as the controller sees it: `NowPlayingListener` feeds it the session's metadata and
 * playback state, and [update] derives the session's one effective event, [Playing.fromSession] of the
 * [Playing.effectiveState] (a seek or rebuffer keeps the last real state, no playback state is STOPPED,
 * PLAYING without art is STOPPED without a key). Every path (the snapshot, a metadata change, a playback
 * change, a `null` playback state, the snapshot after a reconnect) goes through [update], which returns the
 * event only when it changed, so the controller hears each change once and nothing else. [event] is the same
 * value, for the sibling and reconnect checks.
 *
 * Free of Android types so it runs as a plain JVM unit test.
 */
class SessionView(val pkg: String, val id: String) {
    /** The text and bitmap part of one metadata, ready for [Playing.fromMetadata]. */
    data class Meta(val strings: Map<String, String?>, val bitmapKey: String?, val bitmapPath: String?) {
        companion object {
            val EMPTY = Meta(emptyMap(), null, null)
        }
    }

    var meta = Meta.EMPTY
        private set
    private var state: Int? = null // the raw PlaybackState code; null: none
    private var effective: Int? = null

    /** The session's effective event, stamped with [id]; `null` before the first [update]. */
    var event: Playing? = null
        private set

    /**
     * The session has new [meta] and/or playback [state] (a raw `PlaybackState` code, `null`: none); what is
     * not passed stays. Returns the new effective event if it is a change worth reporting (the first update,
     * another state, or PLAYING another key), else `null`: a seek or a rebuffer, a title-only change, a
     * repeated artless update or an end whose key changed are not reported again (a repeated end would restart
     * the revert retry bound).
     */
    fun update(meta: Meta = this.meta, state: Int? = this.state): Playing? {
        this.meta = meta
        this.state = state
        effective = Playing.effectiveState(effective, state)
        val p = Playing.fromSession(pkg, meta.strings, meta.bitmapKey, meta.bitmapPath, effective).copy(session = id)
        val last = event
        event = p
        val changed = last == null || p.state != last.state || (p.state == Playing.State.PLAYING && p.key != last.key)
        return p.takeIf { changed }
    }
}
