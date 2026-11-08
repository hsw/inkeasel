package io.github.hsw.inkeasel.nowplaying

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The upload queue and its one worker, for the whole process: uploads run one at a time, in [scope], and
 * every effect is injected, so the rules run as JVM tests. Free of Android types.
 *
 * Queue rules ([UploadQueue]): a newer request replaces a queued one of the same kind ([isAuto]), an automatic
 * request never replaces a manual one, and a manual one goes first. Before taking a request the worker waits
 * until the frame has finished drawing ([awaitIdle], the `DrawGate`), so a newer request can still replace the
 * queued one, or a manual one overtake it, meanwhile. A request that [isStale] when taken is dropped unsent.
 *
 * Every request is reported exactly once: [onDone] after its upload (`ok`, or the error it failed with), or
 * [onDropped] if it never ran (replaced by a newer one, or stale). Whether a dropped request counts as a
 * failure is the caller's business. An exception, from an upload or from a callback, never stops the worker.
 *
 * **Held work** is what keeps a foreground service up: every manual request, and an automatic one submitted
 * with `hold = true`. [onIdle] fires when the last held request is reported, right after its report (no
 * linger); unheld automatic work still queued or in flight does not count.
 *
 * Lock order: callers may [submit] under their own lock (the controller's), and [isStale] may take it. The
 * core's locks are never held while it calls back, so a callback may take the caller's lock or submit again.
 */
class UploadEngineCore<R : Any>(
    scope: CoroutineScope,
    private val isAuto: (R) -> Boolean,
    private val awaitIdle: suspend () -> Unit,
    private val upload: suspend (R) -> Unit,
    private val isStale: (R) -> Boolean,
    private val onDone: (R, ok: Boolean, error: Throwable?) -> Unit,
    private val onDropped: (R, Drop) -> Unit,
    private val onIdle: () -> Unit,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    /** Why a request was dropped unsent. */
    enum class Drop {
        /** A newer request of its kind took its place in the queue. */
        REPLACED,

        /** It [isStale] when the worker took it. */
        STALE,
    }

    private val lock = Any()
    private val queue = UploadQueue(isAuto)
    private val held: MutableSet<R> = Collections.newSetFromMap(IdentityHashMap()) // guarded by lock
    private val wake = Channel<Unit>(Channel.CONFLATED) // a request was queued

    init {
        scope.launch { work() }
    }

    /** Held requests not yet reported (queued or in flight). */
    val heldCount: Int
        get() = synchronized(lock) { held.size }

    /**
     * Queue [r]; a manual request is always held, an automatic one when [hold] is set. Returns the queued
     * request it replaced, already reported through [onDropped], or `null`.
     */
    fun submit(r: R, hold: Boolean): R? {
        if (hold || !isAuto(r)) synchronized(lock) { held += r }
        val replaced = queue.offer(r)
        wake.trySend(Unit)
        replaced?.let { drop(it, Drop.REPLACED) }
        return replaced
    }

    private suspend fun work() {
        while (true) {
            if (queue.isEmpty()) {
                wake.receive()
                continue // also a wake left over from a request already taken
            }
            try {
                step()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive() // the scope is gone: stop; else it was a stray one
                log("upload worker: cancelled from inside", e)
            } catch (e: Exception) {
                log("upload worker: unexpected error", e)
            }
        }
    }

    /** Wait out the frame's drawing, then upload the next request that is not stale, if any. */
    private suspend fun step() {
        awaitIdle()
        val r = queue.poll(stale = ::staleSafe) { drop(it, Drop.STALE) } ?: return
        var error: Throwable? = null
        try {
            upload(r)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // e.g. a timeout inside the upload: a failure, not a stop
            error = e
        } catch (e: Exception) {
            error = e
        }
        if (queue.settle(r)) report(r) { onDone(r, error == null, error) }
    }

    /** [isStale], with an error counting as not stale: the request is uploaded rather than lost unreported. */
    private fun staleSafe(r: R): Boolean = try {
        isStale(r)
    } catch (e: Exception) {
        log("stale check failed", e)
        false
    }

    private fun drop(r: R, why: Drop) = report(r) { onDropped(r, why) }

    /** Call [callback] (the one report [r] gets), then release it from the held work. */
    private inline fun report(r: R, callback: () -> Unit) {
        try {
            callback()
        } catch (e: Exception) {
            log("upload report failed", e)
        } finally {
            release(r)
        }
    }

    private fun release(r: R) {
        val idle = synchronized(lock) { held.remove(r) && held.isEmpty() }
        if (idle) onIdle()
    }
}
