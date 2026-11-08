package io.github.hsw.inkeasel.nowplaying

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.hsw.inkeasel.R
import io.github.hsw.inkeasel.Txt
import io.github.hsw.inkeasel.nowplaying.Playing.State
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class FrameControllerTest {
    private val video = "com.example.video"
    private val yt = "com.google.android.youtube.tv"
    private val k1 = "https://cdn.example/film1-wide.jpg"
    private val k2 = "https://cdn.example/film2-wide.jpg"
    private val photo = "/sdcard/pictures/sea.jpg"
    private val s1 = "com.example.video#1"
    private val s2 = "com.example.video#2"

    private data class Upload(val at: Long, val art: Art, val title: String, val key: String)

    private class MemState(var value: String? = null) : FrameState {
        val saves = mutableListOf<String>()
        override fun load() = value
        override fun save(key: String) {
            value = key
            saves += key
        }
    }

    private class MemDefaults(var art: Art? = null) : DefaultStore {
        val saved = mutableListOf<Source>()
        override fun load() = art
        override fun save(source: Source) {
            saved += source
        }
    }

    /**
     * Test rig: uploads are recorded; by default each one reports success at once (`result = true`). With
     * [viaEngine] set, automatic uploads go instead through the production [AutoUploader] into the real
     * [UploadEngineCore] with the production report rules ([UploadReports]; one upload at a time, automatic
     * requests stamped with [FrameController.manualUploads] and dropped before upload once superseded), driven
     * step by step by [manual], [startNext] and [complete]: the worker waits at the DrawGate until [startNext]
     * lets it take the next request. [bound] is the listener's binding: while it is off, a request is held and
     * [holds] counts the hold-service starts ([holdRefused]: the start throws, as a refused background start
     * does); [loseListener] unbinds it. [reports] counts each key's reports to the controller, [toasts] the
     * toasts of manual uploads. [logs] has every log line of the controller, the uploader and the reports (with
     * its error as the log prints it); a failed upload throws [failure].
     */
    private inner class Rig(scope: TestScope, onFrame: String? = null, default: Art? = Art.File(photo)) {
        val ts = scope
        val state = MemState(onFrame)
        val defaults = MemDefaults(default)
        val uploads = mutableListOf<Upload>()
        val logs = mutableListOf<String>()
        var result: Boolean? = true
        var viaEngine = false
        var bound = true // NowPlayingListener.connected
        var holds = 0
        var holdRefused = false
        var failure: (UploadRequest) -> Exception = { IllegalStateException("upload failed") }
        val reports = mutableMapOf<String, Int>()
        val toasts = mutableListOf<Txt>()
        var inFlight: UploadRequest? = null
        val dropped = mutableListOf<String>()
        var frame = true // a frame is chosen in the settings
        var poster = true // the poster switch in the settings
        private val gate = Channel<Unit>() // startNext lets the worker past its DrawGate wait
        private var atGate = false
        private var finished: CompletableDeferred<Boolean>? = null
        private val auto = AutoUploader(
            permitted = { true },
            listenerBound = { bound },
            submit = { art, title, key, hold -> engine.submit(UploadRequest.auto(art, title, key, c.manualUploads), hold) },
            startHold = {
                holds++
                if (holdRefused) throw IllegalStateException("background start not allowed")
            },
            refuse = { key -> c.onUploadResult(key, false) },
            holdAll = { engine.holdAll() },
            log = ::log,
        )
        val c: FrameController = FrameController(scope.backgroundScope, { art, title, key ->
            uploads += Upload(scope.testScheduler.currentTime, art, title, key)
            if (viaEngine) {
                auto.upload(art, title, key)
            } else {
                result?.let { ok -> c.onUploadResult(key, ok) }
            }
        }, defaults, state, { logs += it }, frameSelected = { frame }, posterEnabled = { poster })
        private val engineReports = UploadReports(
            onUploadResult = { key, ok ->
                reports.merge(key, 1, Int::plus)
                c.onUploadResult(key, ok)
            },
            onManualUpload = { c.onManualUpload(it) },
            toast = { toasts += it },
            log = ::log,
        )
        val engine = UploadEngineCore<UploadRequest>(
            scope = scope.backgroundScope,
            isAuto = { it.auto },
            awaitIdle = {
                atGate = true
                try {
                    gate.receive()
                } finally {
                    atGate = false
                }
            },
            upload = { req ->
                inFlight = req
                val ok = CompletableDeferred<Boolean>().also { finished = it }.await()
                inFlight = null
                if (!ok) throw failure(req)
            },
            isStale = { c.isStale(it.auto, it.stamp, it.key) },
            onDone = engineReports::done,
            onDropped = { req, why ->
                if (why == UploadEngineCore.Drop.STALE) dropped += id(req)
                engineReports.dropped(req, why)
            },
            onIdle = {},
        )

        private fun log(msg: String, e: Throwable?) {
            logs += msg + (e?.let { "\n" + StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) } } ?: "")
        }

        /** A request's controller key; a manual one is named by its source. */
        private fun id(req: UploadRequest): String = req.key ?: req.source.manualKey()

        /** The user taps a picture: a manual request joins the engine's queue. */
        fun manual(src: Source) = assertNull(engine.submit(UploadRequest.manual(src, Art.File(src.value)), hold = true))

        /**
         * The engine takes the next request into flight, dropping superseded automatic ones on the way: its key,
         * or `null` if every queued request was dropped. Fails if nothing is queued ([assertNothingQueued]).
         */
        fun startNext(): String? {
            ts.runCurrent()
            check(inFlight == null) { "a request is still in flight" }
            check(atGate) { "nothing queued" }
            check(gate.trySend(Unit).isSuccess)
            ts.runCurrent()
            return inFlight?.let(::id)
        }

        /** Nothing is in flight or queued in the engine. */
        fun assertNothingQueued() {
            ts.runCurrent()
            assertNull(inFlight)
            assertFalse("a request is queued", atGate)
        }

        /** The listener unbinds: its flag is cleared, then the uploader holds what it left unheld. */
        fun loseListener() {
            bound = false
            auto.listenerLost()
            ts.runCurrent()
        }

        /** The request in flight finishes. */
        fun complete(ok: Boolean = true) {
            checkNotNull(inFlight)
            checkNotNull(finished).complete(ok)
            finished = null
            ts.runCurrent()
        }

        fun play(key: String = k1, pkg: String = video, title: String = "Film", session: String? = null) =
            c.onEvent(Playing(pkg, key, title, Art.Url(key), State.PLAYING, session))

        fun pause(pkg: String = video, key: String? = k1, session: String? = null) =
            c.onEvent(Playing(pkg, key, "Film", key?.let { Art.Url(it) }, State.PAUSED, session))

        fun stop(pkg: String = video, key: String? = null, session: String? = null) =
            c.onEvent(Playing(pkg, key, "", key?.let { Art.Url(it) }, State.STOPPED, session))

        /** Send what a listener session reports ([SessionView.update]), if anything. */
        fun report(p: Playing?) {
            p?.let { c.onEvent(it) }
        }

        fun advance(seconds: Long) {
            ts.advanceTimeBy(seconds * 1000)
            ts.runCurrent()
        }

        /** Play [key] long enough for the poster to go up (and succeed). */
        fun posterUp(key: String = k1) {
            play(key)
            advance(60)
            assertEquals(key, c.onFrame)
        }

        fun keys() = uploads.map { it.key }

        /** The user flips the poster switch in the settings. */
        fun switchPoster(on: Boolean) {
            poster = on
            c.onPosterSwitched()
        }
    }

    private val defaultKey = FrameController.DEFAULT_PREFIX + photo

    /** Listener session metadata with [art] as its art URI (none if `null`). */
    private fun meta(art: String? = k1, title: String = "Film") =
        SessionView.Meta(mapOf(MetaKeys.TITLE to title, MetaKeys.ART_URI to art), null, null)

    // --- no frame chosen ---

    @Test
    fun noFrame_posterNotScheduled_untilAFrameIsChosenAndPlaybackStartsAgain() = runTest {
        val r = Rig(this)
        r.frame = false
        r.play()
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
        assertTrue(r.logs.any { it.startsWith("no frame selected: poster not scheduled") })
        r.frame = true
        r.pause()
        r.play()
        r.advance(60)
        assertEquals(listOf(k1), r.keys())
    }

    @Test
    fun noFrame_noRevertScheduled() = runTest {
        val r = Rig(this, onFrame = k1) // a poster left on the frame
        r.frame = false
        r.stop()
        r.c.onSessionsSynced(emptyList())
        r.advance(600)
        assertTrue(r.uploads.isEmpty())
        assertTrue(r.logs.any { it.startsWith("no frame selected: revert from $k1 not scheduled") })
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun frameGoneWhileTimerRuns_notRequested_andNotLeftPending() = runTest {
        val r = Rig(this)
        r.play()
        r.advance(30)
        r.frame = false
        r.advance(40)
        assertTrue(r.uploads.isEmpty())
        assertTrue(r.logs.any { it.startsWith("no frame selected: upload poster not requested") })
        assertEquals(setOf(k1), r.c.artKeysInUse()) // only as what plays: no start, nothing pending
        r.frame = true
        r.pause()
        r.play()
        r.advance(60)
        assertEquals(listOf(k1), r.keys()) // not skipped as "still pending"
    }

    @Test
    fun noFrame_refusedRevertsAreBounded() = runTest {
        // the engine refuses (reports failure) if the frame disappears between scheduling and upload
        val r = Rig(this, onFrame = k1)
        r.result = false
        r.stop()
        r.advance(60 * 20)
        assertEquals(FrameController.MAX_REVERT_ATTEMPTS, r.keys().count { it == defaultKey })
    }

    // --- poster switched off in the settings ---

    @Test
    fun posterOff_noPoster_butEventsTracked() = runTest {
        val r = Rig(this)
        r.poster = false
        r.play()
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
        assertTrue(r.logs.any { it.startsWith("poster off: not scheduled: $k1") })
        assertEquals(setOf(k1), r.c.artKeysInUse()) // it still knows what plays
        r.stop()
        r.advance(300)
        assertTrue(r.uploads.isEmpty()) // nothing to revert: the default was never covered
    }

    @Test
    fun switchedOffMidPoster_revertsOnceAtOnce() = runTest {
        val r = Rig(this)
        r.posterUp(k1)
        r.switchPoster(false)
        assertEquals(listOf(k1, defaultKey), r.keys()) // at once, not after the revert delay
        assertEquals(defaultKey, r.c.onFrame)
        r.advance(600)
        r.pause()
        r.play()
        r.advance(600)
        r.stop()
        r.advance(600)
        assertEquals(listOf(k1, defaultKey), r.keys()) // once: no poster and no second revert later
    }

    @Test
    fun switchedOffDuringTheStartTimer_noPoster() = runTest {
        val r = Rig(this)
        r.play()
        r.advance(30)
        r.switchPoster(false)
        r.advance(600)
        assertTrue(r.uploads.isEmpty())
        assertEquals(setOf(k1), r.c.artKeysInUse()) // no start pending any more
    }

    @Test
    fun switchedOnDuringPlayback_posterAfterTheStartDelayFromTheSwitch() = runTest {
        val r = Rig(this)
        r.poster = false
        r.play()
        r.advance(300)
        r.switchPoster(true) // the film keeps playing: no new PLAYING event comes
        r.advance(59)
        assertTrue(r.uploads.isEmpty())
        r.advance(1)
        assertEquals(listOf(Upload(360_000, Art.Url(k1), "Film", k1)), r.uploads)
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun switchedOnAgainAfterARevert_posterReturns() = runTest {
        val r = Rig(this)
        r.posterUp(k1)
        r.switchPoster(false)
        r.switchPoster(true) // the film still plays: the key was the last attempt, but the switch counts
        r.advance(60)
        assertEquals(listOf(k1, defaultKey, k1), r.keys())
    }

    @Test
    fun switchedOnWhileNothingPlays_nothingHappens() = runTest {
        val r = Rig(this)
        r.poster = false
        r.play()
        r.stop()
        r.switchPoster(true)
        r.advance(600)
        assertTrue(r.uploads.isEmpty())
    }

    // --- listener binding: unheld or held automatic uploads ---

    @Test
    fun listenerBound_posterRunsUnheld_noHoldStart() = runTest {
        val r = Rig(this)
        r.viaEngine = true
        r.play()
        r.advance(60)
        assertEquals(0, r.engine.heldCount) // the bound listener keeps the process alive
        assertEquals(k1, r.startNext())
        r.complete()
        assertEquals(k1, r.c.onFrame)
        assertEquals(0, r.holds)
    }

    @Test
    fun listenerNotBound_posterHeldUntilReported() = runTest {
        val r = Rig(this, onFrame = k1)
        r.viaEngine = true
        r.bound = false // e.g. a revert timer firing after the binding was lost
        r.stop(session = null)
        r.c.onSessionsSynced(emptyList())
        r.advance(60)
        assertEquals(listOf(defaultKey), r.keys())
        assertEquals(1, r.holds)
        assertEquals(1, r.engine.heldCount)
        assertEquals(defaultKey, r.startNext())
        r.complete()
        assertEquals(0, r.engine.heldCount)
        assertEquals(defaultKey, r.c.onFrame)
    }

    @Test
    fun holdStartRefused_posterStillUploads_andIsReportedOnceByTheEngine() = runTest {
        val r = Rig(this)
        r.viaEngine = true
        r.bound = false
        r.holdRefused = true // a background start refused on Android 12+
        r.play()
        r.advance(60)
        assertEquals(1, r.holds)
        assertTrue(r.reports.isEmpty()) // the refused start is not reported as a failure
        assertEquals(k1, r.startNext()) // the request is in the engine all the same
        r.complete(ok = false)
        assertEquals(mapOf(k1 to 1), r.reports)
        assertNull(r.c.onFrame)
        assertEquals(0, r.engine.heldCount)
    }

    @Test
    fun listenerLostWhileThePosterUploadsUnheld_heldUntilItLands() = runTest {
        val r = Rig(this)
        r.viaEngine = true
        r.play()
        r.advance(60)
        assertEquals(k1, r.startNext()) // unheld: the listener is bound
        assertEquals(0, r.engine.heldCount)
        r.loseListener() // e.g. the access revoked mid-transfer
        assertEquals(1, r.holds)
        assertEquals(1, r.engine.heldCount)
        r.complete()
        assertEquals(k1, r.c.onFrame)
        assertEquals(0, r.engine.heldCount)
    }

    // --- what plays in the log: only with MediaLog.details (debug builds) ---

    @Test
    fun detailsOff_noTitleOrPosterKeyInAnyLogLine_defaultAndPackageStay() = runTest {
        MediaLog.details = false
        try {
            val r = Rig(this, onFrame = k2)
            r.viaEngine = true
            r.failure = { req -> IOException("download failed", IOException("HTTP 404: ${req.source.value}")) }
            r.c.onEvent(Playing(video, k1, "Secret Film", Art.Url(k1), State.PLAYING, s1))
            r.advance(60)
            assertEquals(k1, r.startNext())
            r.complete(ok = false)
            r.c.onEvent(Playing(video, k1, "Secret Film", Art.Url(k1), State.STOPPED, s1))
            r.advance(60)
            assertEquals(defaultKey, r.startNext())
            r.complete()
            val all = r.logs.joinToString("\n")
            for (secret in listOf("Secret Film", k1, k2, "film1", "film2")) assertFalse(secret, all.contains(secret))
            assertTrue(all, all.contains("upload poster: <key>"))
            assertTrue(all, all.contains("HTTP 404: <key>")) // the error's cause, redacted but still there
            assertTrue(all, all.contains("on frame: $defaultKey")) // the user's own pick stays
            assertTrue(all, all.contains(video))
        } finally {
            MediaLog.details = true
        }
    }

    @Test
    fun detailsOn_logNamesTitleAndKey() = runTest {
        MediaLog.details = true
        val r = Rig(this)
        r.viaEngine = true
        r.play(title = "Secret Film")
        r.advance(60)
        assertEquals(k1, r.startNext())
        r.complete()
        val all = r.logs.joinToString("\n")
        assertTrue(all, all.contains("on frame: $k1"))
        assertTrue(all, all.contains("Secret Film (key=$k1)"))
    }

    // --- an automatic request replaced in the engine's queue (reported at once, under the controller's lock) ---

    @Test
    fun revertReplacesAQueuedPoster_posterReportedOnceAsFailed_notRetried() = runTest {
        val k0 = "https://cdn.example/film0-wide.jpg"
        val r = Rig(this, onFrame = k0) // an earlier poster is on the frame
        r.viaEngine = true
        r.play(k1)
        r.advance(60) // k1 waits in the engine's queue (the frame is still drawing)
        r.stop()
        r.advance(60) // the revert fires and replaces the queued poster
        assertEquals(listOf(k1, defaultKey), r.keys())
        assertEquals(mapOf(k1 to 1), r.reports) // reported failed at once, from inside the revert's request
        assertEquals(defaultKey, r.startNext()) // only the revert runs
        r.complete()
        assertEquals(defaultKey, r.c.onFrame)
        r.advance(600) // the second revert timer the reentrant report armed finds the default on the frame
        assertEquals(listOf(k1, defaultKey), r.keys())
        assertEquals(mapOf(k1 to 1, defaultKey to 1), r.reports)
        r.assertNothingQueued()
    }

    @Test
    fun revertReplacedByANewFilm_revertReportedOnce_posterLands_laterRevertsStillRun() = runTest {
        val r = Rig(this)
        r.posterUp(k1)
        r.viaEngine = true
        r.stop()
        r.advance(60) // the revert waits in the engine's queue
        r.play(k2) // another film starts
        r.advance(60) // its poster replaces the queued revert
        assertEquals(listOf(k1, defaultKey, k2), r.keys())
        assertEquals(mapOf(defaultKey to 1), r.reports)
        assertEquals(k2, r.startNext())
        r.complete()
        assertEquals(k2, r.c.onFrame)
        // the replaced revert does not count toward the give-up bound of the next end of playback
        r.result = false
        r.viaEngine = false
        r.stop()
        r.advance(60L * (FrameController.MAX_REVERT_ATTEMPTS + 2))
        assertEquals(FrameController.MAX_REVERT_ATTEMPTS, r.keys().drop(3).count { it == defaultKey })
    }

    @Test
    fun posterLandsAfterTheSwitchWentOff_revertedAtOnce() = runTest {
        val r = Rig(this)
        r.viaEngine = true
        r.play()
        r.advance(60)
        assertEquals(k1, r.startNext()) // the poster's upload is under way
        r.switchPoster(false) // the default is on the frame: nothing to revert yet
        assertEquals(listOf(k1), r.keys())
        r.complete() // the poster lands anyway
        assertEquals(k1, r.c.onFrame)
        assertEquals(listOf(k1, defaultKey), r.keys())
        assertEquals(defaultKey, r.startNext())
        r.complete()
        assertEquals(defaultKey, r.c.onFrame)
        r.advance(600)
        assertEquals(listOf(k1, defaultKey), r.keys())
    }

    @Test
    fun posterQueuedWhenSwitchedOff_droppedAtTakeTime() = runTest {
        val r = Rig(this)
        r.viaEngine = true
        r.play()
        r.advance(60) // requested, still queued in the engine
        r.switchPoster(false)
        assertNull(r.startNext()) // dropped before upload, reported as failed
        assertEquals(listOf(k1), r.dropped)
        assertEquals(setOf(k1), r.c.artKeysInUse()) // only as what plays: nothing pending
        assertEquals(null, r.c.onFrame)
    }

    @Test
    fun posterOff_queuedRevertNotDropped() = runTest {
        val r = Rig(this, onFrame = k1) // a poster from before the switch
        r.viaEngine = true
        r.switchPoster(false)
        assertEquals(listOf(defaultKey), r.keys())
        assertEquals(defaultKey, r.startNext()) // a revert is not a poster: it goes through
        assertTrue(r.dropped.isEmpty())
    }

    @Test
    fun posterOff_failedRevertRetried_evenWhileAFilmPlays() = runTest {
        val r = Rig(this)
        r.posterUp(k1)
        r.result = false
        r.switchPoster(false)
        assertEquals(listOf(k1, defaultKey), r.keys())
        r.play(k1) // a PLAYING event while off must not cancel the retry
        r.advance(60)
        assertEquals(listOf(k1, defaultKey, defaultKey), r.keys())
        r.result = true
        r.advance(60)
        assertEquals(defaultKey, r.c.onFrame)
    }

    @Test
    fun switchedOnAgainWhileARevertRetryIsPending_noRevert() = runTest {
        val r = Rig(this)
        r.posterUp(k1)
        r.result = false
        r.switchPoster(false) // the revert fails: a retry is armed for 60 s later
        assertEquals(listOf(k1, defaultKey), r.keys())
        r.result = true
        r.switchPoster(true) // the film still plays and its poster is still on the frame
        r.advance(600)
        assertEquals(listOf(k1, defaultKey), r.keys()) // no default over the poster, no poster again
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun switchedOnAgainWhileTheRevertIsQueued_revertDropped_posterStays() = runTest {
        val r = Rig(this)
        r.posterUp(k1)
        r.viaEngine = true
        r.switchPoster(false) // the revert waits in the engine's queue
        assertEquals(listOf(k1, defaultKey), r.keys())
        r.switchPoster(true) // the film still plays and its poster is still on the frame
        assertNull(r.startNext()) // the revert is dropped before upload
        assertEquals(listOf(defaultKey), r.dropped)
        r.advance(600)
        assertEquals(listOf(k1, defaultKey), r.keys()) // no default over the poster, no poster again
        assertEquals(k1, r.c.onFrame)
        assertEquals(setOf(k1), r.c.artKeysInUse()) // nothing left pending
    }

    @Test
    fun switchedOnAgainWhileTheRevertUploads_posterOnceAfterItLands() = runTest {
        val r = Rig(this)
        r.posterUp(k1)
        r.viaEngine = true
        r.switchPoster(false)
        assertEquals(defaultKey, r.startNext()) // the revert is already uploading: it cannot be stopped
        r.switchPoster(true)
        r.advance(600)
        assertEquals(listOf(k1, defaultKey), r.keys()) // the poster is still on the frame: nothing armed
        r.complete() // the default lands over the playing film
        assertEquals(defaultKey, r.c.onFrame)
        r.advance(59)
        assertEquals(listOf(k1, defaultKey), r.keys())
        r.advance(1)
        assertEquals(listOf(k1, defaultKey, k1), r.keys()) // its poster comes back once
        assertEquals(k1, r.startNext())
        r.complete()
        r.advance(600)
        assertEquals(listOf(k1, defaultKey, k1), r.keys())
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun filmResumedWhileTheRevertIsQueued_revertDropped() = runTest {
        val r = Rig(this)
        r.posterUp(k1)
        r.viaEngine = true
        r.stop()
        r.advance(60) // the revert waits in the engine's queue
        r.play() // resumed: its poster is still on the frame
        assertNull(r.startNext())
        assertEquals(listOf(defaultKey), r.dropped)
        r.advance(600)
        assertEquals(listOf(k1, defaultKey), r.keys())
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun restartAfterASwitchOffRevertWasLost_syncedWhileItPlays_reverts() = runTest {
        val r = Rig(this, onFrame = k1) // switched off, then the process died before the revert ran
        r.poster = false
        r.play() // the snapshot of the session comes before the sync call
        r.c.onSessionsSynced(listOf(Playing(video, k1, "Film", Art.Url(k1), State.PLAYING)))
        assertEquals(listOf(defaultKey), r.keys()) // at once, as the switch would have
        assertEquals(defaultKey, r.c.onFrame)
        r.advance(600)
        assertEquals(listOf(defaultKey), r.keys())
    }

    @Test
    fun posterOff_reconnectWhileARevertRetryIsPending_retryKept() = runTest {
        val r = Rig(this)
        r.posterUp(k1)
        r.result = false
        r.switchPoster(false) // the revert fails: a retry is armed for 60 s later
        r.c.onSessionsSynced(listOf(Playing(video, k1, "Film", Art.Url(k1), State.PLAYING)))
        assertEquals(listOf(k1, defaultKey), r.keys()) // the retry timer stands; no extra request
        r.result = true
        r.advance(60)
        assertEquals(listOf(k1, defaultKey, defaultKey), r.keys())
        assertEquals(defaultKey, r.c.onFrame)
    }

    @Test
    fun isStale_revertWhileThePlayingFilmsPosterIsOnTheFrame() = runTest {
        val r = Rig(this)
        r.posterUp(k1)
        val now = r.c.manualUploads
        assertTrue(r.c.isStale(auto = true, stamp = now, key = defaultKey))
        r.poster = false
        assertFalse(r.c.isStale(auto = true, stamp = now, key = defaultKey)) // switched off: the revert is due
        r.poster = true
        r.play(k2) // another film: its own start follows the default
        assertFalse(r.c.isStale(auto = true, stamp = now, key = defaultKey))
        r.play(k1)
        r.pause()
        assertFalse(r.c.isStale(auto = true, stamp = now, key = defaultKey)) // ended: the revert is due
    }

    @Test
    fun switchedOffAfterTheFilmStopped_replacesThePendingRevert() = runTest {
        val r = Rig(this)
        r.posterUp(k1)
        r.stop() // the revert is due 60 s later
        r.advance(30)
        r.result = false
        r.switchPoster(false) // reverts at once and fails: its retry is 60 s from now, not the old timer
        assertEquals(listOf(k1, defaultKey), r.keys())
        r.advance(59)
        assertEquals(listOf(k1, defaultKey), r.keys())
        r.result = true
        r.advance(1)
        assertEquals(listOf(k1, defaultKey, defaultKey), r.keys())
        assertEquals(defaultKey, r.c.onFrame)
    }

    @Test
    fun switchedOffWithoutADefault_posterStays() = runTest {
        val r = Rig(this, default = null)
        r.posterUp(k1)
        r.switchPoster(false)
        r.advance(600)
        assertEquals(listOf(k1), r.keys())
        assertEquals(k1, r.c.onFrame)
        assertTrue(r.logs.any { it.startsWith("no default picture") })
    }

    @Test
    fun switchedOffWithoutAFrame_nothingRequestedOrCounted() = runTest {
        val r = Rig(this)
        r.posterUp(k1)
        r.frame = false
        r.switchPoster(false)
        assertEquals(listOf(k1), r.keys())
        assertEquals(setOf(k1), r.c.artKeysInUse()) // the refused revert is not left pending
        r.frame = true
        r.stop()
        r.advance(60) // the usual revert: nothing blocks it
        assertEquals(listOf(k1, defaultKey), r.keys())
        assertEquals(defaultKey, r.c.onFrame)
    }

    @Test
    fun isStale_posterOff_onlyAutomaticPosters() = runTest {
        val r = Rig(this)
        val now = r.c.manualUploads
        assertFalse(r.c.isStale(auto = true, stamp = now, key = k1))
        r.poster = false
        assertTrue(r.c.isStale(auto = true, stamp = now, key = k1))
        assertFalse(r.c.isStale(auto = true, stamp = now, key = defaultKey))
        assertFalse(r.c.isStale(auto = false, stamp = now, key = "manual:x"))
        assertFalse(r.c.isStale(auto = true, stamp = now, key = null)) // no key: only the stamp counts
    }

    // --- main scenarios ---

    @Test
    fun filmPlays60s_oneUpload() = runTest {
        val r = Rig(this)
        r.play()
        r.advance(59)
        assertTrue(r.uploads.isEmpty())
        r.advance(1)
        assertEquals(listOf(Upload(60_000, Art.Url(k1), "Film", k1)), r.uploads)
        assertEquals(k1, r.c.onFrame)
        assertEquals(k1, r.state.value)
    }

    @Test
    fun artKeysInUse_currentPendingStartAndOutstandingUploads() = runTest {
        val r = Rig(this)
        r.viaEngine = true
        assertEquals(emptySet<String>(), r.c.artKeysInUse())
        r.play(k1)
        assertEquals(setOf(k1), r.c.artKeysInUse()) // playing, start pending: its PNG must survive 60 s
        r.advance(60)
        assertEquals(setOf(k1), r.c.artKeysInUse()) // requested, queued in the engine
        r.play(k2) // the next film starts while k1's upload still waits
        assertEquals(setOf(k1, k2), r.c.artKeysInUse())
        assertEquals(k1, r.startNext())
        r.complete()
        assertEquals(setOf(k2), r.c.artKeysInUse())
        r.stop()
        assertEquals(emptySet<String>(), r.c.artKeysInUse()) // ended: current keeps no key, start cancelled
    }

    @Test
    fun repeatedPlayingEvery5sFor120s_exactlyOneUploadAt60s() = runTest {
        val r = Rig(this)
        for (t in 0 until 120 step 5) {
            r.play()
            r.advance(5)
        }
        assertEquals(listOf(60_000L), r.uploads.map { it.at })
        assertEquals(listOf(k1), r.keys())
    }

    @Test
    fun plays59sThenStops_noUploadEver() = runTest {
        val r = Rig(this)
        r.play()
        r.advance(59)
        r.stop()
        r.advance(180)
        assertTrue(r.uploads.isEmpty())
        assertNull(r.c.onFrame)
    }

    @Test
    fun stopAfterPoster_defaultAfter60s() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.stop()
        r.advance(59)
        assertEquals(listOf(k1), r.keys())
        r.advance(1)
        assertEquals(listOf(k1, defaultKey), r.keys())
        assertEquals(Art.File(photo), r.uploads.last().art)
        assertEquals("", r.uploads.last().title) // no translated text from here: the notification names it
        assertEquals(120_000L, r.uploads.last().at)
        assertEquals(defaultKey, r.c.onFrame)
    }

    @Test
    fun reEntryWithin60sWithSameKey_noUpload() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.stop()
        r.advance(30)
        r.play()
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun afterRevert_samePosterUploadsAgain() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.stop()
        r.advance(60)
        assertEquals(defaultKey, r.c.onFrame)
        r.play()
        r.advance(60)
        assertEquals(listOf(k1, defaultKey, k1), r.keys())
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun episodeAutoplay_newTitleSameKey_noUpload() = runTest {
        val r = Rig(this)
        r.play(title = "S01E01")
        r.advance(60)
        r.play(title = "S01E02")
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
    }

    @Test
    fun episodeChangeBeforeStart_uploadsLatestTitleOnce() = runTest {
        val r = Rig(this)
        r.play(title = "S01E01")
        r.advance(30)
        r.play(title = "S01E02")
        r.advance(30)
        assertEquals(listOf(Upload(60_000, Art.Url(k1), "S01E02", k1)), r.uploads)
    }

    @Test
    fun newKeyMidPlay_uploadAfter60s() = runTest {
        val r = Rig(this)
        r.posterUp(k1)
        r.play(k2)
        r.advance(59)
        assertEquals(listOf(k1), r.keys())
        r.advance(1)
        assertEquals(listOf(k1, k2), r.keys())
        assertEquals(120_000L, r.uploads.last().at)
    }

    @Test
    fun newKeyBeforeStart_restartsTimerForNewKey() = runTest {
        val r = Rig(this)
        r.play(k1)
        r.advance(30)
        r.play(k2)
        r.advance(59)
        assertTrue(r.uploads.isEmpty())
        r.advance(1)
        assertEquals(listOf(k2), r.keys())
    }

    @Test
    fun switchBackToKeyOnFrame_cancelsPendingStart() = runTest {
        val r = Rig(this)
        r.posterUp(k1)
        r.play(k2)
        r.advance(30)
        r.play(k1)
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
    }

    // --- edge cases ---

    @Test
    fun noDefaultSource_noRevert() = runTest {
        val r = Rig(this, default = null)
        r.posterUp()
        r.stop()
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun pauseWhileDefaultShown_noUploads() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.play(k1, pkg = yt)
        r.advance(10)
        r.pause(pkg = yt)
        r.advance(300)
        r.play(k1, pkg = yt)
        r.advance(10)
        r.pause(pkg = yt)
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
        assertEquals(defaultKey, r.c.onFrame)
    }

    @Test
    fun pauseWhileManualPhotoShown_noUploads() = runTest {
        val manual = Source(Source.Type.PHOTO, "sea").manualKey()
        val r = Rig(this, onFrame = manual)
        r.play()
        r.advance(30)
        r.pause()
        r.advance(300)
        r.stop()
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
        assertEquals(manual, r.c.onFrame)
    }

    @Test
    fun unknownOnFrame_stopDoesNotRevert() = runTest {
        val r = Rig(this, onFrame = null)
        r.play()
        r.advance(10)
        r.stop()
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
    }

    @Test
    fun pauseLongerThan60sAfterPoster_reverts() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.pause()
        r.advance(60)
        assertEquals(listOf(k1, defaultKey), r.keys())
    }

    @Test
    fun pauseShorterThan60sThenResume_noChange() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.pause()
        r.advance(40)
        r.play()
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun repeatedStops_oneRevert() = runTest {
        val r = Rig(this)
        r.posterUp()
        for (i in 0 until 10) {
            r.stop()
            r.advance(15)
        }
        r.c.onSessionGone(video)
        r.advance(300)
        assertEquals(listOf(k1, defaultKey), r.keys())
        assertEquals(120_000L, r.uploads.last().at) // the first stop's timer was not reset
    }

    @Test
    fun sessionGone_actsLikeStop() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.c.onSessionGone(video)
        r.advance(60)
        assertEquals(listOf(k1, defaultKey), r.keys())
    }

    @Test
    fun sessionGoneBeforeStart_cancelsStart() = runTest {
        val r = Rig(this)
        r.play()
        r.advance(30)
        r.c.onSessionGone(video)
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
    }

    @Test
    fun twoSessions_firstStopsAfterSecondStarts_firstStopIgnored() = runTest {
        val r = Rig(this)
        r.posterUp(k1) // video
        r.play(k2, pkg = yt)
        r.advance(10)
        r.stop(pkg = video)
        r.c.onSessionGone(video)
        r.advance(50)
        assertEquals(listOf(k1, k2), r.keys()) // yt's start was not cancelled, no revert
        r.advance(300)
        assertEquals(listOf(k1, k2), r.keys())
        assertEquals(k2, r.c.onFrame)
    }

    @Test
    fun endsBeforeAnyPlaying_defaultOnFrame_ignored() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.stop(pkg = yt)
        r.c.onSessionGone(yt)
        r.c.onSessionsSynced(emptyList())
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
    }

    @Test
    fun restoredPoster_endBeforeAnyPlaying_reverts() = runTest {
        val r = Rig(this, onFrame = k1) // e.g. the process restarted after the film ended
        r.stop(pkg = yt)
        r.advance(59)
        assertTrue(r.uploads.isEmpty())
        r.advance(1)
        assertEquals(listOf(defaultKey), r.keys())
        assertEquals(defaultKey, r.c.onFrame)
    }

    @Test
    fun restoredPoster_syncedWithNothingPlaying_reverts() = runTest {
        val r = Rig(this, onFrame = k1) // e.g. after a reboot: no session at all
        r.c.onSessionsSynced(emptyList())
        r.advance(60)
        assertEquals(listOf(defaultKey), r.keys())
    }

    @Test
    fun restoredPoster_syncedWhileItPlays_noRevert() = runTest {
        val r = Rig(this, onFrame = k1)
        r.play() // the snapshot of the session comes before the sync call
        r.c.onSessionsSynced(listOf(Playing(video, k1, "Film", Art.Url(k1), State.PLAYING)))
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
    }

    @Test
    fun reconnect_playingAppWentAwayWhileDisconnected_reverts() = runTest {
        val r = Rig(this)
        r.posterUp()
        // the listener was disconnected while video stopped and its session left: no end was reported, and
        // the reconnect snapshot has no video session (only another app, paused)
        r.c.onSessionsSynced(listOf(Playing(yt, null, "", null, State.PAUSED)))
        r.advance(59)
        assertEquals(listOf(k1), r.keys())
        r.advance(1)
        assertEquals(listOf(k1, defaultKey), r.keys())
        assertEquals(defaultKey, r.c.onFrame)
    }

    @Test
    fun reconnect_playingAppWentAwayBeforeStart_startCancelled() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.play()
        r.advance(30)
        r.c.onSessionsSynced(emptyList())
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
    }

    @Test
    fun reconnect_anotherSessionOfThePlayingAppPlays_noRevert() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.c.onSessionsSynced(listOf(Playing(video, k1, "Film", Art.Url(k1), State.PLAYING)))
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
    }

    @Test
    fun artClearedWhilePlaying_cancelsStartAndRevertsPoster() = runTest {
        val r = Rig(this)
        val v = SessionView(video, s1)
        r.report(v.update(meta(k1), PlaybackCodes.PLAYING))
        r.advance(60)
        assertEquals(k1, r.c.onFrame)
        r.report(v.update(meta(k2, "Next"))) // the next item, with art: its start is armed
        r.advance(30)
        r.report(v.update(meta(null, "Clip"))) // the item changed again, now without art
        assertNull(v.update(meta(null, "Clip 2"))) // later artless updates are not reported again
        r.advance(59)
        assertEquals(listOf(k1), r.keys()) // k2's start was cancelled
        r.advance(1)
        assertEquals(listOf(k1, defaultKey), r.keys()) // and k1 does not stay for an item without a picture
        r.advance(300)
        assertEquals(listOf(k1, defaultKey), r.keys())
    }

    @Test
    fun reconnectWhileBuffering_thenArtCleared_revertsPoster() = runTest {
        val r = Rig(this)
        r.report(SessionView(video, s1).update(meta(k1), PlaybackCodes.PLAYING))
        r.advance(60)
        assertEquals(k1, r.c.onFrame)
        // reconnect: the session is renumbered and its snapshot finds it rebuffering
        val v = SessionView(video, s2)
        r.report(v.update(meta(k1), 6))
        r.c.onSessionsSynced(listOfNotNull(v.event))
        r.report(v.update(meta(null, "Clip"))) // the next item has no art, before playback resumes
        r.advance(60)
        assertEquals(listOf(k1, defaultKey), r.keys())
    }

    @Test
    fun nullPlaybackStateAfterPlaying_revertsPoster() = runTest {
        val r = Rig(this)
        val v = SessionView(video, s1)
        r.report(v.update(meta(k1), PlaybackCodes.PLAYING))
        r.advance(60)
        assertEquals(k1, r.c.onFrame)
        r.report(v.update(state = null)) // the session cleared its playback state but stays active
        r.advance(60)
        assertEquals(listOf(k1, defaultKey), r.keys())
    }

    @Test
    fun nullPlaybackStateBeforeStart_cancelsStart() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        val v = SessionView(video, s1)
        r.report(v.update(meta(k1), PlaybackCodes.PLAYING))
        r.advance(30)
        r.report(v.update(state = null))
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
    }

    @Test
    fun restartWhileBuffering_restoredPosterStays() = runTest {
        val r = Rig(this, onFrame = k1) // the process restarted during the film
        val v = SessionView(video, s1)
        r.report(v.update(meta(k1), 6)) // the snapshot finds the player rebuffering
        r.c.onSessionsSynced(listOfNotNull(v.event))
        r.advance(90) // still rebuffering
        r.report(v.update(state = PlaybackCodes.PLAYING))
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun pauseResumeWhilePosterUploadOutstanding_noDuplicate() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.result = null // waits for the frame to finish drawing, then uploads: well over a minute
        r.play()
        r.advance(60)
        assertEquals(listOf(k1), r.keys())
        r.pause()
        r.advance(5)
        r.play() // a transition: the start is armed again
        r.advance(120)
        assertEquals(listOf(k1), r.keys()) // but the outstanding request is not duplicated
        assertTrue(r.logs.any { it.contains("still pending") })
        r.c.onUploadResult(k1, true)
        assertEquals(k1, r.c.onFrame)
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
    }

    @Test
    fun pauseResume_outstandingPosterFailsFirst_resumeRetries() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.result = null
        r.play()
        r.advance(60)
        r.pause()
        r.advance(5)
        r.play()
        r.advance(30)
        r.c.onUploadResult(k1, false) // the first request failed before the new start fired
        r.advance(30)
        assertEquals(listOf(k1, k1), r.keys())
    }

    /** Play k1 with the upload outstanding, pause/resume, and let the resumed start fire while it still is. */
    private fun Rig.startSkippedWhileOutstanding() {
        result = null
        play()
        advance(60)
        pause()
        advance(5)
        play()
        advance(60) // the resumed start fires while the first request is outstanding: skipped
        assertEquals(listOf(k1), keys())
        assertTrue(logs.any { it.contains("still pending") })
    }

    @Test
    fun startSkippedWhileOutstanding_thenItFails_retriesAtOnce() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.startSkippedWhileOutstanding()
        r.c.onUploadResult(k1, false)
        assertEquals(listOf(k1, k1), r.keys()) // the skipped start is not lost
        r.c.onUploadResult(k1, true)
        assertEquals(k1, r.c.onFrame)
        r.advance(300)
        assertEquals(listOf(k1, k1), r.keys())
    }

    @Test
    fun startSkippedWhileOutstanding_retryFailsToo_noLoop() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.startSkippedWhileOutstanding()
        r.result = false // the retry fails at once
        r.c.onUploadResult(k1, false)
        r.advance(300)
        assertEquals(listOf(k1, k1), r.keys()) // one retry, then the next transition as usual
    }

    @Test
    fun startSkippedWhileOutstanding_pausedBeforeItFails_noRetry() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.startSkippedWhileOutstanding()
        r.pause()
        r.c.onUploadResult(k1, false)
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
    }

    @Test
    fun startSkippedWhileOutstanding_manualUploadThenItFails_noRetry() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.startSkippedWhileOutstanding()
        r.c.onManualUpload(Source(Source.Type.PHOTO, "sea", "sea"))
        r.c.onUploadResult(k1, false) // e.g. dropped as stale
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
        assertEquals("manual:photo:sea", r.c.onFrame)
    }

    @Test
    fun secondEndWhileRevertOutstanding_noDuplicateRevert() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.result = null
        r.stop()
        r.advance(60) // the revert is requested and still outstanding
        assertEquals(listOf(k1, defaultKey), r.keys())
        r.play() // k1 is still on the frame: nothing to arm
        r.advance(5)
        r.stop() // arms another revert
        r.advance(120)
        assertEquals(listOf(k1, defaultKey), r.keys())
        r.c.onUploadResult(defaultKey, true)
        assertEquals(defaultKey, r.c.onFrame)
        r.advance(300)
        assertEquals(listOf(k1, defaultKey), r.keys())
    }

    @Test
    fun outstandingRevertFails_reconcileRetries() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.result = null
        r.stop()
        r.advance(60)
        r.play()
        r.advance(5)
        r.stop()
        r.advance(120) // the second revert fired while the first was outstanding: skipped
        r.c.onUploadResult(defaultKey, false)
        r.result = true
        r.advance(60)
        assertEquals(listOf(k1, defaultKey, defaultKey), r.keys())
        assertEquals(defaultKey, r.c.onFrame)
    }

    @Test
    fun manualUpload_cancelsPendingStart() = runTest {
        val r = Rig(this)
        r.play()
        r.advance(30)
        val src = Source(Source.Type.PHOTO, "sea", "sea")
        r.c.onManualUpload(src)
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
        assertEquals("manual:photo:sea", r.c.onFrame)
        assertEquals("manual:photo:sea", r.state.value)
        assertEquals(listOf(src), r.defaults.saved)
    }

    @Test
    fun manualUpload_cancelsPendingRevert() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.stop()
        r.advance(30)
        r.c.onManualUpload(Source(Source.Type.URL, "https://x/pic.jpg"))
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
        assertEquals("manual:url:https://x/pic.jpg", r.c.onFrame)
    }

    @Test
    fun manualUploadDuringPlay_repeatedPlayingDoesNotReplaceIt() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.c.onManualUpload(Source(Source.Type.PATH, photo))
        r.play()
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
        assertEquals("manual:path:$photo", r.c.onFrame)
    }

    @Test
    fun manualUploadDuringPlay_beforePoster_winsUntilNextTransition() = runTest {
        val r = Rig(this)
        r.play()
        r.advance(30)
        r.c.onManualUpload(Source(Source.Type.PHOTO, "sea"))
        for (i in 0 until 60) { // repeated PLAYING (seek, rebuffer, metadata refresh) for 300 s
            r.play()
            r.advance(5)
        }
        assertTrue(r.uploads.isEmpty())
        r.pause()
        r.advance(5)
        r.play()
        r.advance(60)
        assertEquals(listOf(k1), r.keys())
    }

    // --- a manual upload in flight (a Bluetooth upload takes tens of seconds) ---

    @Test
    fun manualInFlight_whenRevertFires_revertIsDroppedAndManualStays() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.viaEngine = true
        r.stop()
        r.advance(50)
        val m = Source(Source.Type.PHOTO, "sea")
        r.manual(m)
        assertEquals(m.manualKey(), r.startNext())
        r.advance(10) // the revert fires while the manual upload is in flight; the old default is queued
        assertEquals(listOf(k1, defaultKey), r.keys())
        r.complete()
        assertEquals(m.manualKey(), r.c.onFrame)
        assertEquals(listOf(Txt(R.string.upload_sent)), r.toasts)
        assertNull(r.startNext()) // the queued revert is dropped, never uploaded
        assertEquals(listOf(defaultKey), r.dropped)
        r.advance(600)
        assertEquals(listOf(k1, defaultKey), r.keys())
        r.assertNothingQueued()
        assertEquals(m.manualKey(), r.c.onFrame)
        assertEquals(listOf(m), r.defaults.saved)
    }

    @Test
    fun manualInFlightAndAnotherQueued_whenStartFires_posterIsDroppedUntilNextTransition() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.viaEngine = true
        r.play()
        r.advance(40)
        val m1 = Source(Source.Type.PHOTO, "sea")
        val m2 = Source(Source.Type.PHOTO, "lake")
        r.manual(m1)
        assertEquals(m1.manualKey(), r.startNext())
        r.manual(m2) // tapped again while the first is still uploading
        r.advance(20) // the start fires: the poster joins the queue behind both manual uploads
        assertEquals(listOf(k1), r.keys())
        r.complete()
        assertEquals(m2.manualKey(), r.startNext())
        r.complete()
        assertNull(r.startNext()) // the queued poster is dropped
        assertEquals(listOf(k1), r.dropped)
        assertEquals(m2.manualKey(), r.c.onFrame)
        for (i in 0 until 60) { // the film keeps playing: the manual picture wins until the next transition
            r.play()
            r.advance(5)
        }
        assertEquals(listOf(k1), r.keys())
        r.pause()
        r.advance(5)
        r.play()
        r.advance(60) // a new transition: the poster is decided after the manual upload, so it runs
        assertEquals(k1, r.startNext())
        r.complete()
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun autoQueuedBeforeManualTap_manualGoesFirstAndTheAutoIsDropped() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.viaEngine = true
        r.stop()
        r.advance(60) // the revert is queued (the engine is still busy with something else)
        val m = Source(Source.Type.PHOTO, "sea")
        r.manual(m)
        assertEquals(m.manualKey(), r.startNext())
        r.complete()
        assertNull(r.startNext())
        assertEquals(listOf(defaultKey), r.dropped)
        assertEquals(m.manualKey(), r.c.onFrame)
    }

    @Test
    fun manualFailsInFlight_queuedRevertStillRuns() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.viaEngine = true
        r.stop()
        r.advance(50)
        r.manual(Source(Source.Type.PHOTO, "sea"))
        r.startNext()
        r.advance(10)
        r.complete(ok = false)
        assertEquals(listOf(Txt(R.string.upload_failed, Txt(R.string.upload_err_text, "upload failed"))), r.toasts)
        assertEquals(defaultKey, r.startNext())
        r.complete()
        assertEquals(defaultKey, r.c.onFrame)
        assertTrue(r.dropped.isEmpty())
        assertTrue(r.defaults.saved.isEmpty())
    }

    @Test
    fun manualTappedWhileThePosterUploads_runsAfterIt_andStaysWhileTheFilmPlays() = runTest {
        val r = Rig(this)
        r.viaEngine = true
        r.play()
        r.advance(60)
        assertEquals(k1, r.startNext())
        val m = Source(Source.Type.PHOTO, "sea")
        r.manual(m)
        r.complete() // the poster lands first
        assertEquals(k1, r.c.onFrame)
        assertEquals(m.manualKey(), r.startNext())
        r.complete()
        assertEquals(m.manualKey(), r.c.onFrame)
        r.play() // repeated PLAYING does not bring the poster back over the manual pick
        r.advance(300)
        r.assertNothingQueued()
        assertEquals(listOf(k1), r.keys())
        assertEquals(0, r.engine.heldCount)
    }

    @Test
    fun stamp_autoIsStaleOnlyAfterALaterManualUpload() = runTest {
        val r = Rig(this)
        val before = r.c.manualUploads
        assertEquals(false, r.c.isStale(auto = true, stamp = before, key = defaultKey))
        r.c.onManualUpload(Source(Source.Type.PHOTO, "sea"))
        assertTrue(r.c.isStale(auto = true, stamp = before, key = defaultKey))
        assertEquals(false, r.c.isStale(auto = true, stamp = r.c.manualUploads, key = defaultKey))
    }

    @Test
    fun stamp_manualIsNeverStale() = runTest {
        val r = Rig(this)
        val before = r.c.manualUploads
        r.c.onManualUpload(Source(Source.Type.PHOTO, "sea"))
        assertEquals(false, r.c.isStale(auto = false, stamp = before, key = "manual:x"))
    }

    @Test
    fun lateEndOfAnotherSession_whilePlaying_isIgnored() = runTest {
        val r = Rig(this)
        r.play(k1, session = s1)
        r.advance(30)
        r.play(k2, session = s2) // a new player of the same app took over
        r.pause(key = k1, session = s1) // the old one reports its end afterwards
        r.stop(session = s1) // also without a key
        r.c.onSessionGone(video, session = s1) // and goes away
        r.advance(60)
        assertEquals(listOf(k2), r.keys())
        r.pause(key = k2, session = s2)
        r.advance(60)
        assertEquals(listOf(k2, defaultKey), r.keys())
    }

    @Test
    fun endOfCurrentSession_keyChangedDuringRebuffer_revertsPoster() = runTest {
        val r = Rig(this)
        r.play(k1, session = s1)
        r.advance(60)
        assertEquals(k1, r.c.onFrame)
        // the session moved on to k2 while buffering (no event), then fails or pauses on k2
        r.stop(key = k2, session = s1)
        r.advance(59)
        assertEquals(listOf(k1), r.keys())
        r.advance(1)
        assertEquals(listOf(k1, defaultKey), r.keys())
    }

    @Test
    fun endOfCurrentSession_keyChangedDuringRebuffer_cancelsStart() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.play(k1, session = s1)
        r.advance(30)
        r.pause(key = k2, session = s1)
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
    }

    @Test
    fun endOfArtlessHelperSession_whilePlayerPlays_isIgnored() = runTest {
        val r = Rig(this)
        r.play(k1, session = s1)
        r.advance(30)
        r.pause(key = null, session = s2) // a helper session of the same app, not the player
        r.advance(30)
        assertEquals(listOf(k1), r.keys())
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
    }

    @Test
    fun reconnect_currentSessionBuffering_keepsPosterAndTakesTheNewId() = runTest {
        val r = Rig(this)
        r.play(k1, session = s1)
        r.advance(60)
        // reconnect: the player is renumbered s2, and its snapshot was a rebuffer, so it reported nothing; its
        // effective state counts as playing (Playing.effectiveState)
        r.c.onSessionsSynced(listOf(Playing(video, k1, "Film", Art.Url(k1), State.PLAYING, s2)))
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
        r.pause(key = k1, session = s2) // its end under the new id counts
        r.advance(60)
        assertEquals(listOf(k1, defaultKey), r.keys())
    }

    @Test
    fun sessionGone_otherSessionOfAppStillPlaying_startKept() = runTest {
        val r = Rig(this)
        r.play(k1)
        r.advance(30)
        r.c.onSessionGone(video, listOf(Playing(video, k1, "Film", Art.Url(k1), State.PLAYING)))
        r.advance(30)
        assertEquals(listOf(k1), r.keys())
    }

    @Test
    fun sessionGone_otherSessionOfAppIdle_actsLikeStop() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.c.onSessionGone(video, listOf(Playing(video, null, "", null, State.PAUSED)))
        r.advance(60)
        assertEquals(listOf(k1, defaultKey), r.keys())
    }

    @Test
    fun endOfArtlessSibling_whileAnotherSessionOfAppPlays_startKept() = runTest {
        val r = Rig(this)
        r.play(k1)
        r.advance(30)
        // a helper session of the same app, without art, pauses next to the player
        r.c.onEvent(Playing(video, null, "", null, State.PAUSED),
            listOf(Playing(video, k1, "Film", Art.Url(k1), State.PLAYING)))
        r.advance(30)
        assertEquals(listOf(k1), r.keys())
        assertTrue(r.logs.any { it.contains("another session plays") })
    }

    @Test
    fun endOfArtlessSibling_whileAnotherSessionOfAppPlays_posterStays() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.c.onEvent(Playing(video, null, "", null, State.STOPPED),
            listOf(Playing(video, k1, "Film", Art.Url(k1), State.PLAYING)))
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
        assertEquals(k1, r.c.onFrame)
        r.pause() // the player itself pauses: an end as usual
        r.advance(60)
        assertEquals(listOf(k1, defaultKey), r.keys())
    }

    @Test
    fun endWithSiblingsIdleOrOfAnotherApp_actsLikeStop() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.c.onEvent(Playing(video, null, "", null, State.STOPPED), listOf(
            Playing(video, null, "", null, State.PAUSED),
            Playing(yt, k2, "Clip", Art.Url(k2), State.PLAYING),
        ))
        r.advance(60)
        assertEquals(listOf(k1, defaultKey), r.keys())
    }

    @Test
    fun endInAnotherApp_withItsSiblingPlaying_currentUntouched() = runTest {
        val r = Rig(this)
        r.play(k1)
        r.advance(30)
        // youtube has a session still playing next to the one that pauses / goes away: not video's business
        val ytPlays = listOf(Playing(yt, k2, "Clip", Art.Url(k2), State.PLAYING))
        r.c.onEvent(Playing(yt, null, "", null, State.PAUSED), ytPlays)
        r.c.onSessionGone(yt, ytPlays)
        r.advance(30)
        assertEquals(listOf(k1), r.keys()) // video's start was not cancelled, and k2 did not become current
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
    }

    @Test
    fun endInAnotherApp_withItsSiblingPlaying_posterStays() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.c.onSessionGone(yt, listOf(Playing(yt, k2, "Clip", Art.Url(k2), State.PLAYING)))
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun endWithSeveralSiblingsPlaying_currentKeyKept() = runTest {
        val r = Rig(this)
        r.play(k1)
        r.advance(30)
        r.c.onEvent(Playing(video, null, "", null, State.PAUSED), listOf(
            Playing(video, k2, "Other", Art.Url(k2), State.PLAYING),
            Playing(video, k1, "Film", Art.Url(k1), State.PLAYING),
        ))
        r.advance(30)
        assertEquals(listOf(k1), r.keys()) // the start for k1 kept its timer; k2 did not take over
    }

    @Test
    fun endAfterRestart_siblingPlaysPosterOnFrame_noRevert() = runTest {
        val r = Rig(this, onFrame = k1)
        r.c.onSessionGone(video, listOf(Playing(video, k1, "Film", Art.Url(k1), State.PLAYING)))
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
        r.pause()
        r.advance(60)
        assertEquals(listOf(defaultKey), r.keys())
    }

    @Test
    fun failedUpload_keepsOnFrame_noRetryOnRepeat_retryOnPauseResume() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.result = false
        r.play()
        r.advance(60)
        assertEquals(listOf(k1), r.keys())
        assertEquals(defaultKey, r.c.onFrame)
        assertTrue(r.state.saves.isEmpty())
        assertTrue(r.logs.any { it.contains("upload failed") })

        for (i in 0 until 24) { // repeated PLAYING for 120 s: no tight retry loop
            r.play()
            r.advance(5)
        }
        assertEquals(listOf(k1), r.keys())

        r.result = true
        r.pause()
        r.advance(5)
        r.play()
        r.advance(59)
        assertEquals(listOf(k1), r.keys())
        r.advance(1)
        assertEquals(listOf(k1, k1), r.keys())
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun failedRevert_keepsPoster_retriesAfter60sWithoutAnotherStop() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.result = false
        r.stop()
        r.advance(60)
        assertEquals(k1, r.c.onFrame)
        r.result = true
        r.advance(59)
        assertEquals(listOf(k1, defaultKey), r.keys())
        r.advance(1)
        assertEquals(listOf(k1, defaultKey, defaultKey), r.keys())
        assertEquals(180_000L, r.uploads.last().at)
        assertEquals(defaultKey, r.c.onFrame)
    }

    @Test
    fun failedRevert_givesUpAfterMaxAttempts_nextStopTriesAgain() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.result = false
        r.stop()
        r.advance(60L * 20)
        val attempts = FrameController.MAX_REVERT_ATTEMPTS
        assertEquals(listOf(k1) + List(attempts) { defaultKey }, r.keys())
        assertEquals(k1, r.c.onFrame)
        r.result = true
        r.stop()
        r.advance(60)
        assertEquals(attempts + 2, r.uploads.size)
        assertEquals(defaultKey, r.c.onFrame)
    }

    @Test
    fun posterLandsAfterPlaybackEnded_isReverted() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.result = null // the Bluetooth upload takes a while
        r.play()
        r.advance(60)
        assertEquals(listOf(k1), r.keys())
        r.stop() // exit during the upload: the default is still on the frame, nothing to revert yet
        r.advance(10)
        r.result = true
        r.c.onUploadResult(k1, true)
        assertEquals(k1, r.c.onFrame)
        r.advance(59)
        assertEquals(listOf(k1), r.keys())
        r.advance(1)
        assertEquals(listOf(k1, defaultKey), r.keys())
        assertEquals(defaultKey, r.c.onFrame)
    }

    @Test
    fun posterLandsDuringShortPause_resumeKeepsIt() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.result = null
        r.play()
        r.advance(60)
        r.pause()
        r.c.onUploadResult(k1, true)
        r.advance(20)
        r.play()
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun defaultLandsAfterFilmResumed_posterComesBack() = runTest {
        val r = Rig(this)
        r.posterUp()
        r.stop()
        r.result = null
        r.advance(60) // the revert upload starts
        assertEquals(listOf(k1, defaultKey), r.keys())
        r.play() // resumed before the default landed: k1 is still on the frame
        r.advance(10)
        r.result = true
        r.c.onUploadResult(defaultKey, true)
        assertEquals(defaultKey, r.c.onFrame)
        r.advance(59)
        assertEquals(listOf(k1, defaultKey), r.keys())
        r.advance(1)
        assertEquals(listOf(k1, defaultKey, k1), r.keys())
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun failedPosterWhilePlaying_noRetryTimer() = runTest {
        val r = Rig(this, onFrame = defaultKey)
        r.result = null
        r.play()
        r.advance(60)
        r.c.onUploadResult(k1, false)
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
    }

    @Test
    fun uploadInFlight_repeatedPlayingDoesNotStartAgain() = runTest {
        val r = Rig(this)
        r.result = null // no result yet
        r.play()
        r.advance(60)
        r.play()
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
        r.c.onUploadResult(k1, true)
        assertEquals(k1, r.c.onFrame)
    }

    @Test
    fun sameFilmRepeatedMetadata_oneUpload() = runTest {
        val r = Rig(this)
        // repeated metadata for the same poster with different titles (e.g. an episode title arriving later)
        r.c.onEvent(Playing.fromMetadata(video, mapOf(MetaKeys.ART_URI to k1, MetaKeys.TITLE to "Film"),
            null, null, PlaybackCodes.PLAYING)!!)
        r.advance(1)
        r.c.onEvent(Playing.fromMetadata(video, mapOf(MetaKeys.ART_URI to k1, MetaKeys.TITLE to "Film S01E01"),
            null, null, PlaybackCodes.PLAYING)!!)
        r.advance(30)
        r.c.onEvent(Playing.fromMetadata(video, mapOf(MetaKeys.ART_URI to k1), null, null, PlaybackCodes.PLAYING)!!)
        r.advance(300)
        assertEquals(listOf(k1), r.keys())
        assertEquals(60_000L, r.uploads.single().at) // the timer of the first event was not reset

        r.c.onEvent(Playing.fromMetadata(video, emptyMap(), null, null, PlaybackCodes.STOPPED)!!)
        r.advance(300)
        assertEquals(listOf(k1, defaultKey), r.keys())
    }

    @Test
    fun onFrame_isRestoredFromState() = runTest {
        val r = Rig(this, onFrame = k1)
        assertEquals(k1, r.c.onFrame)
        r.play() // e.g. the snapshot after a process restart during the film
        r.advance(300)
        assertTrue(r.uploads.isEmpty())
        r.stop()
        r.advance(60)
        assertEquals(listOf(defaultKey), r.keys())
    }

    @Test
    fun isAuto_classifiesKeys() {
        assertTrue(FrameController.isAuto(k1))
        assertTrue(FrameController.isAuto("0123abcd"))
        assertEquals(false, FrameController.isAuto(null))
        assertEquals(false, FrameController.isAuto(defaultKey))
        assertEquals(false, FrameController.isAuto("manual:photo:sea"))
    }

    @Test
    fun concurrentEvents_realThreads_oneUploadAndConsistentEnd() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val uploads = ConcurrentLinkedQueue<String>()
        lateinit var c: FrameController
        c = FrameController(scope, { _, _, key -> uploads += key; c.onUploadResult(key, true) },
            MemDefaults(Art.File(photo)), MemState(defaultKey),
            startDelay = 30.milliseconds, revertDelay = 30.milliseconds)
        try {
            // 8 threads send the same film's PLAYING (both channels, repeated metadata) at once
            val start = CountDownLatch(1)
            val threads = (0 until 8).map {
                thread {
                    start.await()
                    repeat(500) { c.onEvent(Playing(video, k1, "Film", Art.Url(k1), State.PLAYING)) }
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            Thread.sleep(300)
            assertEquals(listOf(k1), uploads.toList())
            assertEquals(k1, c.onFrame)

            // play/stop flapping from several threads, then a final stop: the frame ends on the default
            val flap = (0 until 4).map { t ->
                thread {
                    repeat(300) { i ->
                        val key = if ((i + t) % 3 == 0) k2 else k1
                        if (i % 2 == 0) c.onEvent(Playing(video, key, "Film", Art.Url(key), State.PLAYING))
                        else c.onEvent(Playing(video, null, "", null, State.STOPPED))
                    }
                }
            }
            flap.forEach { it.join() }
            c.onEvent(Playing(video, null, "", null, State.STOPPED))
            Thread.sleep(300)
            assertEquals(defaultKey, c.onFrame)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun delays_are60s() {
        assertEquals(60_000L, FrameController.START_DELAY.inWholeMilliseconds)
        assertEquals(60_000L, FrameController.REVERT_DELAY.inWholeMilliseconds)
    }
}
