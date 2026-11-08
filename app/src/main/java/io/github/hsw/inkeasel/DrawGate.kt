package io.github.hsw.inkeasel

/**
 * Keeps uploads apart while the frame draws. The frame acks the image end frame (op 0x04) within ~25 ms and
 * apparently draws the image once the link drops: for ~27 s after that it does not even advertise. Nothing
 * on the link says when the drawing is over (docs/device-api.md §14.3), and a picture sent meanwhile is not
 * drawn properly, so the next upload starts no earlier than [gapMs] after the last image was handed over.
 *
 * That time lives in a [Store] (shared prefs in the app), so the gap holds across uploads in one process
 * and across process restarts. Times come from [clock]
 * (`SystemClock.elapsedRealtime`); a stored time later than now means the TV rebooted since, and counts as
 * no upload. Free of Android types.
 */
class DrawGate(private val clock: () -> Long, private val store: Store, private val gapMs: Long = GAP_MS) {
    /** Where the time the last image was handed over is kept; `null` if none was. */
    interface Store {
        fun load(): Long?
        fun save(time: Long)
    }

    /** An image was handed over just now (end frame sent, link closed): the frame is drawing. */
    fun ended() = store.save(clock())

    /** How long to wait before the next upload, in ms; 0 if the frame is free. */
    fun waitMs(): Long {
        val last = store.load() ?: return 0
        val now = clock()
        if (last > now) return 0 // the clock restarted (reboot): that upload is long done
        return (last + gapMs - now).coerceAtLeast(0)
    }

    companion object {
        /** Busy time after an upload (~27 s from the link drop, docs/device-api.md §14.3) plus a margin. */
        const val GAP_MS = 35_000L
    }
}
