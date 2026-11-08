package io.github.hsw.inkeasel.nowplaying

/**
 * The pending requests of the upload engine ([UploadEngineCore]), manual and automatic alike, uploaded one at
 * a time. A newer request replaces a queued one of the same kind (manual or automatic), so rapid taps send
 * only the latest picture and only the controller's latest decision is uploaded; an automatic request never
 * replaces a manual one. A manual request goes ahead of a queued automatic one (the user waits for it);
 * otherwise first in, first out. A polled request leaves the queue; the engine reports it.
 * Thread-safe. Free of Android types.
 */
class UploadQueue<T>(private val isAuto: (T) -> Boolean) {
    private val lock = Any()
    private val items = ArrayDeque<T>()

    /** Queue [req]; returns the queued request of the same kind it replaced, or `null`. */
    fun offer(req: T): T? = synchronized(lock) {
        val auto = isAuto(req)
        val i = items.indexOfFirst { isAuto(it) == auto }
        val replaced = if (i >= 0) items.removeAt(i) else null
        if (auto) items.addLast(req) else items.add(items.indexOfFirst(isAuto).takeIf { it >= 0 } ?: items.size, req)
        replaced
    }

    /** Remove and return the next request to upload, or `null` if none is queued. */
    fun poll(): T? = synchronized(lock) { items.removeFirstOrNull() }

    /**
     * The next request to upload that is not [stale], or `null`. Each stale request on the way is removed and
     * handed to [onStale] (outside the queue's lock).
     */
    fun poll(stale: (T) -> Boolean, onStale: (T) -> Unit): T? {
        while (true) {
            val req = poll() ?: return null
            if (!stale(req)) return req
            onStale(req)
        }
    }

    /** No request is queued (one may still be in flight). */
    fun isEmpty(): Boolean = synchronized(lock) { items.isEmpty() }
}
