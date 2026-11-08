package io.github.hsw.inkeasel.nowplaying

/**
 * The pending requests of the upload engine ([UploadEngineCore]), manual and automatic alike, uploaded one at
 * a time. A newer request replaces a queued one of the same kind (manual or automatic), so rapid taps send
 * only the latest picture and only the controller's latest decision is uploaded; an automatic request never
 * replaces a manual one. A manual request goes ahead of a queued automatic one (the user waits for it);
 * otherwise first in, first out.
 *
 * A polled request stays in flight until [settle]; [takeAll] hands over every request still owed a result
 * (in flight and queued) to an owner that gives the queue up (the process-wide engine never does). Each
 * request is settled exactly once, by [settle] or by [takeAll], whichever comes first, so its result is
 * reported once even when the two race.
 * Thread-safe. Free of Android types.
 */
class UploadQueue<T>(private val isAuto: (T) -> Boolean) {
    private val lock = Any()
    private val items = ArrayDeque<T>()
    private var inFlight: T? = null

    /** Queue [req]; returns the queued request of the same kind it replaced, or `null`. */
    fun offer(req: T): T? = synchronized(lock) {
        val auto = isAuto(req)
        val i = items.indexOfFirst { isAuto(it) == auto }
        val replaced = if (i >= 0) items.removeAt(i) else null
        if (auto) items.addLast(req) else items.add(items.indexOfFirst(isAuto).takeIf { it >= 0 } ?: items.size, req)
        replaced
    }

    /** The next request to upload, now in flight until [settle], or `null` if none is queued. */
    fun poll(): T? = synchronized(lock) { items.removeFirstOrNull()?.also { inFlight = it } }

    /**
     * The in-flight [req] is finished: `true` if the caller now owns its result, `false` if [takeAll]
     * already took it (its result was reported as failed then).
     */
    fun settle(req: T): Boolean = synchronized(lock) {
        if (inFlight !== req) return false
        inFlight = null
        true
    }

    /** Remove and return every request still owed a result: the one in flight first, then the queued ones. */
    fun takeAll(): List<T> = synchronized(lock) {
        val all = listOfNotNull(inFlight) + items
        inFlight = null
        items.clear()
        all
    }

    /**
     * The next request to upload that is not [stale], or `null`. Each stale request on the way is removed and
     * handed to [onStale] (outside the queue's lock), unless [takeAll] took it first.
     */
    fun poll(stale: (T) -> Boolean, onStale: (T) -> Unit): T? {
        while (true) {
            val req = poll() ?: return null
            if (!stale(req)) return req
            if (settle(req)) onStale(req)
        }
    }

    /** No request is queued (one may still be in flight). */
    fun isEmpty(): Boolean = synchronized(lock) { items.isEmpty() }
}
