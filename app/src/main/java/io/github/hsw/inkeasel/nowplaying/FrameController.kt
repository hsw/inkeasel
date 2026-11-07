package io.github.hsw.inkeasel.nowplaying

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Sends a picture to the frame (in production: starts `UploadService` with `auto=true`). */
fun interface Uploader {
    /** Upload [art]; the outcome comes back through [FrameController.onUploadResult] with the same [key]. */
    fun upload(art: Art, title: String, key: String)
}

/**
 * Decides what the frame shows while something plays: the poster of the playing film after [START_DELAY]
 * of continuous playback, and the default picture (the last manual upload) [REVERT_DELAY] after playback
 * ends. Free of Android types: the scope, the uploader, the default source, the persisted frame key and
 * the logger are injected.
 *
 * Rules (also in README.md, "Now playing"):
 * - PLAYING K (any pkg, which becomes [current]): cancel the revert; nothing if K is already on the frame,
 *   if a start for K is pending (repeated events do not reset the timer), or if K was the last attempt and
 *   this is not a transition into PLAYING (no tight retry loop after a failed upload, and a manual upload
 *   during the film wins until the next transition); otherwise upload K after [START_DELAY] if it is still
 *   playing then.
 * - PAUSED / STOPPED / session gone of [current]'s session ([Playing.session]): cancel the start; if an
 *   auto poster is on the frame and no revert is pending, revert to the default after [REVERT_DELAY]. An
 *   end of any other session is ignored (a late stop of an earlier session, an artless helper session next
 *   to the player, another app), whatever its key: the session's own end counts even if its key changed
 *   meanwhile (an item change during a rebuffer). An end of the current session while another session of
 *   the same app still plays is not an end of the app: that session's PLAYING is replayed instead. With
 *   no [current] at all (a process restart), any end reverts a poster restored from [FrameState], unless
 *   another session of its app plays.
 * - An upload result is checked against what plays by then (a Bluetooth upload takes tens of seconds): a
 *   poster that lands after playback ended is reverted, a default that lands after the film resumed is
 *   followed by the film's start timer, and a failed revert is retried, at most [MAX_REVERT_ATTEMPTS] times.
 * - `onFrame` changes only on a successful upload, or on a manual upload.
 * - A key is not requested again while its earlier request has no result yet ([pending]): an upload may wait
 *   for the frame to finish drawing and then take tens of seconds, so a pause/resume or a second end could
 *   otherwise re-arm a timer that fires while the first request is still outstanding and draw it twice.
 *   If such a skipped poster's outstanding request then fails while the poster still plays, it is requested
 *   again at once ([retryPoster]): the skipped start had already seen its 60 s of playback.
 * - An automatic upload decided before a manual upload succeeded must not run after it (it would cover the
 *   user's pick, e.g. a revert or a poster queued behind a manual upload still in flight): the uploader
 *   stamps it with [manualUploads] when it is requested and drops it, reported as failed, if [isStale].
 * - While no frame is chosen in the settings ([frameSelected]), nothing is scheduled or requested: no start
 *   timer, no revert, no upload. The uploader would only refuse it, and a refused revert would count as a
 *   failure. Events are still tracked, so the next transition after a frame is chosen works as usual.
 *
 * Thread-safe: every entry point and every timer body runs under one lock.
 */
class FrameController(
    private val scope: CoroutineScope,
    private val uploader: Uploader,
    private val defaults: DefaultStore,
    private val state: FrameState,
    private val log: (String) -> Unit = {},
    private val startDelay: Duration = START_DELAY,
    private val revertDelay: Duration = REVERT_DELAY,
    private val frameSelected: () -> Boolean = { true },
) {
    private val lock = Any()

    /** The key confirmed on the frame: a poster key, `default:…`, `manual:…`, or `null` (unknown). */
    var onFrame: String? = state.load()
        private set

    private var current: Playing? = null
    private var lastAttempt: String? = null
    private var startJob: Job? = null
    private var startKey: String? = null
    private var revertJob: Job? = null
    private var revertFailures = 0 // failed revert uploads in a row, since the last end of playback
    private var manualCount = 0L
    private val pending = HashSet<String>() // keys requested from the uploader whose result has not come back
    private var retryPoster: String? = null // a start fired while this key was pending: retry if that fails

    /** Successful manual uploads in this process: the stamp of an automatic upload requested now. */
    val manualUploads: Long
        get() = synchronized(lock) { manualCount }

    /**
     * True if the request is automatic ([auto]) and a manual upload succeeded after it was decided ([stamp] =
     * its [manualUploads] when requested): the uploader must drop it and report it as failed. A manual
     * request is never stale.
     */
    fun isStale(auto: Boolean, stamp: Long): Boolean = auto && synchronized(lock) { stamp < manualCount }

    /**
     * The art keys a later upload may still read: what plays ([current]), a pending start, and every request
     * without a result yet. The listener's artwork cache never prunes their files, so a poster's PNG is not
     * evicted by newer artwork while its start timer runs or its upload waits in the queue.
     */
    fun artKeysInUse(): Set<String> = synchronized(lock) { setOfNotNull(current?.key, startKey) + pending }

    /**
     * An event from a session's MediaSession metadata. [siblings] are the current
     * events of the app's other sessions, if any: an end of the current session while one of them plays is
     * not an end of the app, so a PLAYING sibling is replayed instead.
     */
    fun onEvent(p: Playing, siblings: List<Playing> = emptyList()) = synchronized(lock) {
        when (p.state) {
            Playing.State.PLAYING -> playing(p)
            Playing.State.PAUSED, Playing.State.STOPPED -> ended(p.pkg, p.session, p, siblings)
        }
    }

    /**
     * The MediaSession [session] of [pkg] went away (the controller left the active list; `null`: unknown,
     * any session of [pkg]). [remaining] are the current events of that app's other sessions, if any: they
     * report again only when their state changes, so a PLAYING one is replayed; if none plays, the app has
     * stopped playing.
     */
    fun onSessionGone(pkg: String, remaining: List<Playing> = emptyList(), session: String? = null) =
        synchronized(lock) { ended(pkg, session, null, remaining) }

    /**
     * The listener has (re)connected and reported every active session once (their events came before this
     * call); [active] are the current events of all those sessions. If [current] plays, it is checked
     * against them as if its session had gone: a PLAYING session of its app is replayed (so [current] takes
     * that session's new identity, also when its snapshot was a seek or a rebuffer and reported nothing);
     * with none (it stopped or went away while the listener was disconnected, so no end was reported), it
     * counts as ended. If nothing plays and an auto poster is on the frame (restored after a process restart
     * or a reboot that ended the film), revert it.
     */
    fun onSessionsSynced(active: List<Playing>) = synchronized(lock) {
        val cur = current
        if (cur?.state == Playing.State.PLAYING) {
            if (active.none { it.pkg == cur.pkg && it.state == Playing.State.PLAYING }) {
                log("${cur.pkg} no longer plays after the listener reconnected")
            }
            ended(cur.pkg, cur.session, null, active)
            return@synchronized
        }
        revertFailures = 0
        scheduleRevert()
    }

    /** The outcome of an [Uploader.upload] with [key]. */
    fun onUploadResult(key: String, ok: Boolean) = synchronized(lock) {
        pending.remove(key)
        val retry = retryPoster == key
        if (retry) retryPoster = null
        if (ok) {
            log("on frame: $key")
            setOnFrame(key)
        } else {
            log("upload failed: $key (on frame stays $onFrame)")
        }
        if (key.startsWith(DEFAULT_PREFIX)) revertFailures = if (ok) 0 else revertFailures + 1
        reconcile(key, ok, retry)
    }

    /** A manual upload succeeded: it wins over pending timers and becomes the default. */
    fun onManualUpload(source: Source) = synchronized(lock) {
        manualCount++
        cancelStart()
        cancelRevert()
        revertFailures = 0
        retryPoster = null
        // the film playing now does not replace the manual picture until the next transition into PLAYING
        current?.takeIf { it.state == Playing.State.PLAYING }?.key?.let { lastAttempt = it }
        setOnFrame(source.manualKey())
        defaults.save(source)
        log("manual upload: ${source.manualKey()}")
    }

    private fun playing(p: Playing) {
        val key = p.key ?: return // the mappers never produce PLAYING without a key; guard anyway
        val prev = current
        val transition = prev == null || prev.state != Playing.State.PLAYING || prev.pkg != p.pkg || prev.key != key
        current = p
        cancelRevert()
        revertFailures = 0
        if (startJob != null && startKey == key) return
        cancelStart()
        if (key == onFrame) return
        if (key == lastAttempt && !transition) return
        armStart(key, p.pkg)
    }

    /** Upload [key] after [startDelay] if it still plays then. */
    private fun armStart(key: String, pkg: String) {
        if (!frameSelected()) {
            log("no frame selected: poster not scheduled: $key ($pkg)")
            return
        }
        log("start in $startDelay: $key ($pkg)")
        startKey = key
        startJob = scope.launch {
            delay(startDelay)
            synchronized(lock) {
                ensureActive() // cancelled while waiting for the lock
                startJob = null
                startKey = null
                val cur = current
                if (cur == null || cur.state != Playing.State.PLAYING || cur.key != key) return@launch
                val art = cur.art ?: return@launch
                if (key == onFrame) return@launch
                lastAttempt = key
                if (key in pending) retryPoster = key // retried if the outstanding request fails
                request(art, cur.title, key, "poster")
            }
        }
    }

    /**
     * End of [session] of [pkg]: its event [p], or `null` for a session gone or a stale [current]. A no-op
     * unless it is [current]'s session (a `null` session on either side matches any session of the app). If
     * one of [siblings] (the app's other sessions) still plays, the app has not stopped: that PLAYING is
     * replayed instead (the one with [current]'s key if any, so the poster stays).
     */
    private fun ended(pkg: String, session: String?, p: Playing?, siblings: List<Playing> = emptyList()) {
        val cur = current
        if (cur != null) {
            if (cur.pkg != pkg) return
            if (cur.session != null && session != null && cur.session != session) {
                log("end of $session ignored: the current session of $pkg is ${cur.session}")
                return
            }
        }
        val stillPlaying = siblings.filter { it.pkg == pkg && it.state == Playing.State.PLAYING }
        if (stillPlaying.isNotEmpty()) {
            log("end of ${session ?: "a session"} of $pkg ignored: another session plays")
            playing(stillPlaying.firstOrNull { it.key == cur?.key } ?: stillPlaying.first())
            return
        }
        // cur == null: nothing seen playing since the process started, so a poster restored from the state
        // is left over from a film that ended while the process was gone
        if (cur != null) current = p ?: cur.copy(state = Playing.State.STOPPED)
        cancelStart()
        revertFailures = 0
        scheduleRevert()
    }

    /**
     * Bring the frame in line with what plays after an upload result for [key]: if another film plays and no
     * start is pending (a revert's default landed after the film resumed), arm its start; if the poster that
     * plays failed and a start for it was skipped while it was outstanding ([retry]), request it again; if
     * nothing plays, revert an auto poster (a poster that landed after playback ended, or a failed revert).
     */
    private fun reconcile(key: String, ok: Boolean, retry: Boolean) {
        val cur = current
        if (cur?.state == Playing.State.PLAYING) {
            val playingKey = cur.key ?: return
            if (ok && key != playingKey && playingKey != onFrame && startJob == null) armStart(playingKey, cur.pkg)
            if (!ok && retry && key == playingKey && key != onFrame && startJob == null) {
                val art = cur.art ?: return
                request(art, cur.title, key, "poster (retry: a start was skipped while it was outstanding)")
            }
        } else {
            scheduleRevert()
        }
    }

    /** Revert an auto poster to the default after [revertDelay], unless a revert is already pending. */
    private fun scheduleRevert() {
        if (!isAuto(onFrame) || revertJob != null) return
        if (!frameSelected()) {
            log("no frame selected: revert from $onFrame not scheduled")
            return
        }
        if (revertFailures >= MAX_REVERT_ATTEMPTS) {
            log("revert failed $revertFailures times; $onFrame stays until playback ends again")
            return
        }
        log("revert in $revertDelay from $onFrame")
        revertJob = scope.launch {
            delay(revertDelay)
            synchronized(lock) {
                ensureActive()
                revertJob = null
                if (!isAuto(onFrame)) return@launch
                val art = defaults.load()
                if (art == null) {
                    log("no default picture; $onFrame stays")
                    return@launch
                }
                request(art, DEFAULT_TITLE, defaultKey(art), "default")
            }
        }
    }

    /**
     * Ask the uploader for [key] ([what] is for the log), unless an earlier request for it has no result
     * yet: its result settles the frame and [reconcile] follows up, so a second request would only draw the
     * same picture twice.
     */
    private fun request(art: Art, title: String, key: String, what: String) {
        if (!frameSelected()) { // chosen frame gone between scheduling and now: the uploader would refuse it
            log("no frame selected: upload $what not requested: $key")
            return
        }
        if (!pending.add(key)) {
            log("upload $what: $key still pending, not requested again")
            return
        }
        log("upload $what: $key")
        uploader.upload(art, title, key) // may report a failure at once (onUploadResult under this lock)
    }

    private fun cancelStart() {
        startJob?.cancel()
        startJob = null
        startKey = null
    }

    private fun cancelRevert() {
        revertJob?.cancel()
        revertJob = null
    }

    private fun setOnFrame(key: String) {
        onFrame = key
        state.save(key)
    }

    companion object {
        const val TAG = "FrameController"

        /** Continuous playback needed before the poster goes up (skips trailers, clips, channel surfing). */
        val START_DELAY = 60.seconds

        /** Time after playback ends before the frame returns to the default picture (and between retries). */
        val REVERT_DELAY = 60.seconds

        /** Revert uploads tried in a row before giving up until playback ends again. */
        const val MAX_REVERT_ATTEMPTS = 5

        const val DEFAULT_PREFIX = "default:"
        const val MANUAL_PREFIX = "manual:"
        const val DEFAULT_TITLE = "фото по умолчанию"

        /** An auto poster key: anything on the frame except the default, a manual upload, or unknown. */
        fun isAuto(key: String?): Boolean =
            key != null && !key.startsWith(DEFAULT_PREFIX) && !key.startsWith(MANUAL_PREFIX)

        fun defaultKey(art: Art): String = DEFAULT_PREFIX + when (art) {
            is Art.Url -> art.url
            is Art.File -> art.path
        }
    }
}
