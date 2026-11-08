package io.github.hsw.inkeasel.nowplaying

import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

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
        var running = 0
        var maxRunning = 0
        val idleAt = mutableListOf<Long>()
        private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()

        val core = UploadEngineCore<R>(
            scope = ts.backgroundScope,
            isAuto = { it.auto },
            awaitIdle = { if (drawMs > 0) delay(drawMs) },
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
            onDone = { r, ok, e -> events += "done ${r.name} ${if (ok) "ok" else "failed:${e?.javaClass?.simpleName}"}" },
            onDropped = { r, why -> events += "dropped ${r.name} $why" },
            onIdle = {
                events += "idle"
                idleAt += ts.testScheduler.currentTime
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

    /** A [TimeoutCancellationException] as `withTimeout` throws it inside an upload. */
    private fun timeoutException(): Exception = try {
        runBlocking { withTimeout(1) { delay(1_000) } }
        error("no timeout")
    } catch (e: TimeoutCancellationException) {
        e
    }
}
