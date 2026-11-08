package io.github.hsw.inkeasel.nowplaying

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
 * failure is the caller's business. A replaced request is reported synchronously, from inside the [submit]
 * that replaced it (on the submitter's thread, under the submitter's lock if it holds one); every other report
 * comes from the worker. An exception, from an upload, from [awaitIdle] (the upload then goes ahead without
 * the wait) or from a callback, never stops the worker or reaches the submitter.
 *
 * **Held work** is what keeps a foreground service up: every manual request, and an automatic one submitted
 * with `hold = true` or promoted later by [holdAll]. [onIdle] fires when the last held request is reported,
 * right after its report (no linger); unheld automatic work still queued or in flight does not count.
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
    private val owed: MutableSet<R> = Collections.newSetFromMap(IdentityHashMap()) // not reported yet; guarded by lock
    private val held: MutableSet<R> = Collections.newSetFromMap(IdentityHashMap()) // a subset of owed; guarded by lock
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
        synchronized(lock) {
            owed += r
            if (hold || !isAuto(r)) held += r
        }
        val replaced = queue.offer(r)
        wake.trySend(Unit)
        replaced?.let { drop(it, Drop.REPLACED) }
        return replaced
    }

    /**
     * Make every request not reported yet (queued or in flight) held work, e.g. when what kept the process
     * alive for the unheld ones went away. True if any was unheld, so the caller should start holding.
     */
    fun holdAll(): Boolean = synchronized(lock) {
        val before = held.size
        held += owed
        held.size > before
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
            } catch (e: Exception) { // not expected: every step guards its own calls
                log("upload worker: unexpected error", e)
                delay(ERROR_PAUSE_MS) // a fault that repeats must not spin
            }
        }
    }

    /** Wait out the frame's drawing, then upload the next request that is not stale, if any. */
    private suspend fun step() {
        try {
            awaitIdle()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            log("draw wait cancelled from inside; uploading without it", e)
        } catch (e: Exception) { // the queued requests still go out and are reported
            log("draw wait failed; uploading without it", e)
        }
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
        report(r) { onDone(r, error == null, error) }
    }

    /** [isStale], with an error counting as not stale: the request is uploaded rather than lost unreported. */
    private fun staleSafe(r: R): Boolean = try {
        isStale(r)
    } catch (e: Exception) {
        log("stale check failed", e)
        false
    }

    private fun drop(r: R, why: Drop) = report(r) { onDropped(r, why) }

    /** Call [callback] (the one report [r] gets), then release it from the owed and held work. */
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
        val idle = synchronized(lock) {
            owed -= r
            held.remove(r) && held.isEmpty()
        }
        if (!idle) return
        try {
            onIdle()
        } catch (e: Exception) {
            log("idle callback failed", e)
        }
    }

    private companion object {
        /** Pause of the worker after an error it did not expect. */
        const val ERROR_PAUSE_MS = 1_000L
    }
}
