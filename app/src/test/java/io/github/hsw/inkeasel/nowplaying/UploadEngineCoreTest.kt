package io.github.hsw.inkeasel.nowplaying

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class UploadEngineCoreTest {
    private class R(val name: String, val auto: Boolean) {
        override fun toString() = name
    }

    /**
     * Test rig: each upload waits until the test finishes it ([finish]); the frame needs [drawMs] of drawing
     * time before every upload (the DrawGate wait). Everything the core reports is recorded in [events].
     */
    private class Rig(private val ts: TestScope, var drawMs: Long = 0) {
        val events = mutableListOf<String>()
        val started = mutableListOf<String>()
        val stale = mutableSetOf<String>()
        val failWith = mutableMapOf<String, Exception>()
        val reportFails = mutableSetOf<String>() // onDone / onDropped throw for these
        var idleFails = false // onIdle throws
        var drawFails = 0 // the next this many draw waits throw
        var draws = 0
        var running = 0
        var maxRunning = 0
        val idleAt = mutableListOf<Long>()
        private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()

        val core = UploadEngineCore<R>(
            scope = ts.backgroundScope,
            isAuto = { it.auto },
            awaitIdle = {
                draws++
                if (drawFails > 0) {
                    drawFails--
                    error("draw wait broke")
                }
                if (drawMs > 0) delay(drawMs)
            },
            upload = { r ->
                started += r.name
                running++
                maxRunning = maxOf(maxRunning, running)
                try {
                    failWith[r.name]?.let { throw it }
                    CompletableDeferred<Unit>().also { pending[r.name] = it }.await()
                } finally {
                    running--
                }
            },
            isStale = { it.name in stale },
            onDone = { r, ok, e ->
                events += "done ${r.name} ${if (ok) "ok" else "failed:${e?.javaClass?.simpleName}"}"
                if (r.name in reportFails) error("report broke")
            },
            onDropped = { r, why ->
                events += "dropped ${r.name} $why"
                if (r.name in reportFails) error("report broke")
            },
            onIdle = {
                events += "idle"
                idleAt += ts.testScheduler.currentTime
                if (idleFails) error("idle callback broke")
            },
        )

        fun submit(r: R, hold: Boolean = false): R? = core.submit(r, hold).also { ts.runCurrent() }

        fun finish(name: String) {
            checkNotNull(pending.remove(name)) { "$name is not uploading" }.complete(Unit)
            ts.runCurrent()
        }

        fun reports(name: String) = events.count { it.startsWith("done $name ") || it.startsWith("dropped $name ") }
    }

    private fun auto(name: String) = R(name, auto = true)
    private fun manual(name: String) = R(name, auto = false)

    // --- queue and worker ---

    @Test
    fun oneUploadAtATime_inOrder() = runTest {
        val r = Rig(this)
        r.submit(auto("a1"))
        r.submit(manual("m1"))
        assertEquals(listOf("a1"), r.started) // m1 waits even though it is manual: a1 is already uploading
        r.finish("a1")
        assertEquals(listOf("a1", "m1"), r.started)
        r.finish("m1")
        assertEquals(1, r.maxRunning)
        assertEquals(listOf("done a1 ok", "done m1 ok", "idle"), r.events)
    }

    @Test
    fun newerAutoReplacesQueuedAuto_duringTheDrawWait() = runTest {
        val r = Rig(this, drawMs = 30_000)
        r.submit(auto("a1"))
        advanceTimeBy(10_000)
        val a1Replaced = r.submit(auto("a2"))
        assertEquals("a1", a1Replaced?.name)
        assertEquals(listOf("dropped a1 REPLACED"), r.events)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf("a2"), r.started)
        r.finish("a2")
        assertEquals(1, r.reports("a1"))
        assertEquals(1, r.reports("a2"))
    }

    @Test
    fun manualOvertakesQueuedAuto_duringTheDrawWait() = runTest {
        val r = Rig(this, drawMs = 30_000)
        r.submit(auto("a1"))
        assertNull(r.submit(manual("m1"))) // an automatic request never replaces a manual one, nor the reverse
        advanceTimeBy(30_001)
        assertEquals(listOf("m1"), r.started)
        r.finish("m1")
        advanceTimeBy(30_001)
        assertEquals(listOf("m1", "a1"), r.started)
    }

    @Test
    fun staleRequest_droppedUnsent_andReportedOnce() = runTest {
        val r = Rig(this, drawMs = 1_000)
        r.submit(auto("a1"))
        r.stale += "a1" // e.g. the poster was switched off while the frame was drawing
        r.submit(manual("m1"))
        advanceTimeBy(1_001)
        assertEquals(listOf("m1"), r.started)
        r.finish("m1")
        advanceTimeBy(1_001)
        assertEquals(listOf("m1"), r.started) // a1 was never uploaded
        assertEquals(listOf("done m1 ok", "idle", "dropped a1 STALE"), r.events)
        assertEquals(1, r.reports("a1"))
    }

    @Test
    fun uploadException_reportedAsFailure_andTheWorkerGoesOn() = runTest {
        val r = Rig(this)
        r.failWith["a1"] = IllegalStateException("boom")
        r.failWith["a2"] = timeoutException()
        r.submit(auto("a1"))
        r.submit(auto("a2"))
        r.submit(auto("a3"))
        assertEquals(listOf("a1", "a2", "a3"), r.started)
        r.finish("a3")
        assertEquals(
            listOf("done a1 failed:IllegalStateException", "done a2 failed:TimeoutCancellationException", "done a3 ok"),
            r.events,
        )
    }

    @Test
    fun callbackException_doesNotStopTheWorker() = runTest {
        var calls = 0
        val started = mutableListOf<String>()
        val core = UploadEngineCore<R>(
            scope = backgroundScope,
            isAuto = { it.auto },
            awaitIdle = {},
            upload = { started += it.name },
            isStale = { if (it.name == "a1") error("stale check broke") else false },
            onDone = { _, _, _ -> if (calls++ == 0) error("report broke") },
            onDropped = { _, _ -> },
            onIdle = {},
        )
        core.submit(auto("a1"), hold = false)
        runCurrent()
        core.submit(auto("a2"), hold = false)
        runCurrent()
        assertEquals(listOf("a1", "a2"), started) // a broken stale check uploads rather than loses a1
        assertEquals(2, calls)
    }

    // --- held work ---

    @Test
    fun onIdle_onlyAfterHeldWorkSettles_notForUnheldWork() = runTest {
        val r = Rig(this)
        r.submit(auto("a1")) // unheld: e.g. a poster while the listener keeps the process up
        assertEquals(0, r.core.heldCount)
        r.finish("a1")
        assertEquals(listOf("done a1 ok"), r.events) // no idle: nothing was held
        r.submit(auto("h1"), hold = true)
        r.submit(auto("a2"))
        assertEquals(1, r.core.heldCount)
        r.finish("h1")
        assertEquals(listOf("done a1 ok", "done h1 ok", "idle"), r.events) // a2 still queued: unheld
        r.finish("a2")
        assertEquals(1, r.events.count { it == "idle" })
    }

    @Test
    fun manualDuringAnUnheldAutoUpload_isHeldUntilDone() = runTest {
        val r = Rig(this)
        r.submit(auto("a1"))
        r.submit(manual("m1"))
        assertEquals(1, r.core.heldCount)
        r.finish("a1")
        assertEquals(listOf("done a1 ok"), r.events)
        assertEquals(1, r.core.heldCount)
        r.finish("m1")
        assertEquals(0, r.core.heldCount)
        assertEquals(listOf("done a1 ok", "done m1 ok", "idle"), r.events)
    }

    @Test
    fun heldAndUnheldAutoMixedWithManual_idleOnlyAfterTheLastHeld() = runTest {
        // a poster while the listener is bound (unheld), a revert after it unbound (held), and a manual tap
        val r = Rig(this)
        r.submit(auto("a1"))
        r.submit(manual("m1"))
        assertEquals(1, r.core.heldCount)
        r.finish("a1")
        r.submit(auto("h1"), hold = true)
        assertEquals(2, r.core.heldCount)
        assertEquals(listOf("a1", "m1"), r.started) // the manual one goes ahead of the held automatic one
        r.finish("m1")
        assertEquals(listOf("done a1 ok", "done m1 ok"), r.events) // h1 is still held
        r.submit(auto("a2")) // unheld, queued behind h1
        r.finish("h1")
        assertEquals(listOf("done a1 ok", "done m1 ok", "done h1 ok", "idle"), r.events)
        r.finish("a2")
        assertEquals(1, r.events.count { it == "idle" })
        assertEquals(0, r.core.heldCount)
    }

    @Test
    fun onIdle_rightAfterTheLastReport_noLinger() = runTest {
        val r = Rig(this)
        r.submit(manual("m1"))
        advanceTimeBy(5_000)
        r.finish("m1")
        assertEquals(listOf(5_000L), r.idleAt)
        assertEquals("idle", r.events.last())
    }

    @Test
    fun heldRequestReplacedByAnUnheldOne_releasesTheHold() = runTest {
        val r = Rig(this, drawMs = 1_000)
        r.submit(auto("h1"), hold = true)
        val replaced = r.submit(auto("a1"))
        assertEquals("h1", replaced?.name)
        assertEquals(listOf("dropped h1 REPLACED", "idle"), r.events)
        assertEquals(0, r.core.heldCount)
    }

    @Test
    fun heldStaleRequest_releasesTheHoldOnce() = runTest {
        val r = Rig(this, drawMs = 1_000)
        r.submit(auto("h1"), hold = true)
        r.stale += "h1"
        advanceTimeBy(1_001)
        assertEquals(listOf("dropped h1 STALE", "idle"), r.events)
    }

    @Test
    fun replacedManual_keepsTheHold_andEachIsReportedOnce() = runTest {
        val r = Rig(this, drawMs = 1_000)
        val m1 = manual("m1")
        r.submit(m1)
        assertSame(m1, r.submit(manual("m2")))
        assertEquals(1, r.core.heldCount)
        advanceTimeBy(1_001)
        r.finish("m2")
        assertEquals(listOf("dropped m1 REPLACED", "done m2 ok", "idle"), r.events)
    }

    @Test
    fun submitFromACallback_isQueued() = runTest {
        // the controller may request the next upload from inside a report (e.g. a retry)
        val started = mutableListOf<String>()
        lateinit var core: UploadEngineCore<R>
        core = UploadEngineCore(
            scope = backgroundScope,
            isAuto = { it.auto },
            awaitIdle = {},
            upload = { started += it.name },
            isStale = { false },
            onDone = { r, _, _ -> if (r.name == "a1") core.submit(auto("a2"), hold = false) },
            onDropped = { _, _ -> },
            onIdle = {},
        )
        core.submit(auto("a1"), hold = false)
        runCurrent()
        assertEquals(listOf("a1", "a2"), started)
        assertEquals(0, core.heldCount)
    }

    // --- held work and errors ---

    @Test
    fun heldRequestWhoseReportThrows_isStillReleased() = runTest {
        val r = Rig(this)
        r.reportFails += "m1"
        r.submit(manual("m1"))
        r.finish("m1")
        assertEquals(listOf("done m1 ok", "idle"), r.events)
        assertEquals(0, r.core.heldCount)
    }

    @Test
    fun heldRequestDroppedWithAThrowingReport_isStillReleased() = runTest {
        val r = Rig(this, drawMs = 1_000)
        r.reportFails += "h1"
        r.submit(auto("h1"), hold = true)
        r.submit(auto("a1")) // replaces h1; its report throws inside this submit, which must not
        assertEquals(listOf("dropped h1 REPLACED", "idle"), r.events)
        assertEquals(0, r.core.heldCount)
        r.reportFails += "h2"
        r.submit(auto("h2"), hold = true) // replaces a1
        r.stale += "h2"
        advanceTimeBy(1_001)
        assertEquals(listOf("dropped h1 REPLACED", "idle", "dropped a1 REPLACED", "dropped h2 STALE", "idle"), r.events)
        assertEquals(0, r.core.heldCount)
    }

    @Test
    fun heldUploadThatFails_releasesTheHoldOnce() = runTest {
        val r = Rig(this)
        r.failWith["h1"] = IllegalStateException("boom")
        r.reportFails += "h1"
        r.submit(auto("h1"), hold = true)
        assertEquals(listOf("done h1 failed:IllegalStateException", "idle"), r.events)
        assertEquals(0, r.core.heldCount)
    }

    @Test
    fun throwingOnIdle_reachesNeitherTheSubmitterNorTheWorker() = runTest {
        val r = Rig(this, drawMs = 1_000)
        r.idleFails = true
        r.submit(auto("h1"), hold = true)
        r.submit(auto("a1")) // replaces held h1: onIdle runs inside this submit and throws
        assertEquals(listOf("dropped h1 REPLACED", "idle"), r.events)
        r.submit(manual("m1"))
        advanceTimeBy(1_001)
        r.finish("m1")
        advanceTimeBy(1_001)
        r.finish("a1")
        assertEquals(listOf("dropped h1 REPLACED", "idle", "done m1 ok", "idle", "done a1 ok"), r.events)
    }

    @Test
    fun drawWaitThatThrowsOnce_theUploadGoesAheadWithoutIt() = runTest {
        val r = Rig(this, drawMs = 1_000)
        r.drawFails = 1
        r.submit(manual("m1"))
        assertEquals(listOf("m1"), r.started) // at once: the broken wait is skipped
        r.finish("m1")
        r.submit(manual("m2"))
        assertEquals(listOf("m1"), r.started) // the next one waits again
        advanceTimeBy(1_001)
        assertEquals(listOf("m1", "m2"), r.started)
        r.finish("m2")
        assertEquals(listOf("done m1 ok", "idle", "done m2 ok", "idle"), r.events)
    }

    @Test
    fun drawWaitThatKeepsThrowing_everyRequestStillGoesOutOnce_noSpin() = runTest {
        val r = Rig(this)
        r.drawFails = Int.MAX_VALUE
        r.submit(auto("h1"), hold = true)
        r.submit(manual("m1"))
        r.finish("h1")
        r.finish("m1")
        assertEquals(listOf("done h1 ok", "done m1 ok", "idle"), r.events)
        assertEquals(2, r.draws) // one wait per request taken, then the worker sleeps
        advanceTimeBy(60_000)
        assertEquals(2, r.draws)
    }

    // --- promotion to held work ---

    @Test
    fun holdAll_makesQueuedAndInFlightWorkHeld_idleAfterTheirReports() = runTest {
        val r = Rig(this)
        r.submit(auto("a1")) // in flight
        r.submit(auto("a2")) // queued
        assertEquals(0, r.core.heldCount)
        assertTrue(r.core.holdAll())
        assertEquals(2, r.core.heldCount)
        r.finish("a1")
        assertEquals(listOf("done a1 ok"), r.events)
        r.finish("a2")
        assertEquals(listOf("done a1 ok", "done a2 ok", "idle"), r.events)
        assertEquals(0, r.core.heldCount)
    }

    @Test
    fun holdAll_falseWhenNothingIsUnheld() = runTest {
        val r = Rig(this)
        assertFalse(r.core.holdAll()) // nothing at all
        r.submit(manual("m1"))
        r.submit(auto("h1"), hold = true)
        assertFalse(r.core.holdAll()) // all held already
        assertEquals(2, r.core.heldCount)
        r.finish("m1")
        r.finish("h1")
        assertFalse(r.core.holdAll()) // everything reported
        assertEquals(listOf("done m1 ok", "done h1 ok", "idle"), r.events)
    }

    // --- real threads ---

    @Test
    fun concurrentSubmits_eachReportedOnce_heldCountBackToZero_idleAfterTheLastHeldReport() {
        repeat(20) { round ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val reported = ConcurrentHashMap<String, AtomicInteger>()
            val held = ConcurrentHashMap.newKeySet<String>()
            val lastHeldReport = AtomicLong()
            val lastIdle = AtomicLong()
            val clock = AtomicLong()
            val count = { name: String ->
                reported.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
                if (name in held) lastHeldReport.set(clock.incrementAndGet())
            }
            lateinit var core: UploadEngineCore<R>
            core = UploadEngineCore(
                scope = scope,
                isAuto = { it.auto },
                awaitIdle = { if (Random.nextInt(4) == 0) delay(1) },
                upload = {
                    when (Random.nextInt(8)) {
                        0 -> error("upload broke")
                        1, 2, 3 -> delay(1)
                        else -> Unit
                    }
                },
                isStale = { Random.nextInt(10) == 0 },
                onDone = { r, _, _ -> count(r.name) },
                onDropped = { r, _ -> count(r.name) },
                onIdle = { lastIdle.set(clock.incrementAndGet()) },
            )
            val names = mutableListOf<String>()
            val threads = (0 until 4).map { t ->
                val mine = (0 until 50).map { i -> "r$round-$t-$i" }
                synchronized(names) { names += mine }
                thread {
                    for ((i, name) in mine.withIndex()) {
                        val kind = i % 3 // held auto, unheld auto, manual
                        if (kind != 1) held += name
                        core.submit(R(name, auto = kind != 2), hold = kind == 0)
                        if (i % 7 == 0) Thread.sleep(1)
                    }
                }
            }
            threads.forEach { it.join() }
            val deadline = System.currentTimeMillis() + 10_000
            while (reported.size < names.size && System.currentTimeMillis() < deadline) Thread.sleep(5)
            Thread.sleep(20) // a late second report would show up meanwhile
            scope.cancel()
            assertEquals(names.toSet(), reported.keys)
            assertTrue(reported.values.all { it.get() == 1 })
            assertEquals(0, core.heldCount)
            assertTrue(lastIdle.get() > lastHeldReport.get()) // the last onIdle came after the last held report
        }
    }

    /** A [TimeoutCancellationException] as `withTimeout` throws it inside an upload. */
    private fun timeoutException(): Exception = try {
        runBlocking { withTimeout(1) { delay(1_000) } }
        error("no timeout")
    } catch (e: TimeoutCancellationException) {
        e
    }
}
