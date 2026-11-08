package io.github.hsw.inkeasel.nowplaying

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoUploaderTest {
    private val art = Art.Url("https://cdn.example/film1-wide.jpg")
    private val key = "https://cdn.example/film1-wide.jpg"

    /** A request in the engine: its key and whether it was submitted as held work. */
    private data class Req(val key: String, val held: Boolean)

    /**
     * Test rig: [AutoUploader] in front of the real [UploadEngineCore]; every call it makes is recorded in
     * [calls], in order, and every report of the engine in [reports]. An upload waits until [finish].
     */
    private class Rig(ts: TestScope, var permitted: Boolean = true, var bound: Boolean = true) {
        val calls = mutableListOf<String>()
        val reports = mutableListOf<String>()
        val logs = mutableListOf<String>()
        var holdRefused = false
        var idle = 0
        private var pending: CompletableDeferred<Boolean>? = null
        private val runCurrent = { ts.runCurrent() }

        val engine = UploadEngineCore<Req>(
            scope = ts.backgroundScope,
            isAuto = { true },
            awaitIdle = {},
            upload = { if (!CompletableDeferred<Boolean>().also { pending = it }.await()) error("upload failed") },
            isStale = { false },
            onDone = { r, ok, _ -> reports += "${r.key} ${if (ok) "ok" else "failed"}" },
            onDropped = { r, why -> reports += "${r.key} $why" },
            onIdle = { idle++ },
        )

        val uploader = AutoUploader(
            permitted = { permitted },
            listenerBound = { bound },
            submit = { _, _, key, hold ->
                calls += "submit $key hold=$hold"
                engine.submit(Req(key, hold), hold)
            },
            startHold = {
                calls += "hold"
                if (holdRefused) throw IllegalStateException("background start not allowed")
            },
            refuse = { key -> reports += "$key refused" },
            log = { msg, _ -> logs += msg },
        )

        fun upload(art: Art, key: String) {
            uploader.upload(art, "Film", key)
            runCurrent()
        }

        fun finish(ok: Boolean = true) {
            checkNotNull(pending).complete(ok)
            pending = null
            runCurrent()
        }
    }

    @Test
    fun noPermission_reportedFailedAtOnce_nothingSubmitted() = runTest {
        val r = Rig(this, permitted = false, bound = false)
        r.upload(art, key)
        assertEquals(emptyList<String>(), r.calls)
        assertEquals(listOf("$key refused"), r.reports)
    }

    @Test
    fun listenerBound_submittedUnheld_withoutTheHoldService() = runTest {
        val r = Rig(this)
        r.upload(art, key)
        assertEquals(listOf("submit $key hold=false"), r.calls)
        assertEquals(0, r.engine.heldCount)
        r.finish()
        assertEquals(listOf("$key ok"), r.reports)
        assertEquals(0, r.idle) // nothing was held
    }

    @Test
    fun listenerNotBound_submittedHeld_thenTheHoldServiceStarted() = runTest {
        val r = Rig(this, bound = false)
        r.upload(art, key)
        // submitted first, so the service finds the held work when it starts
        assertEquals(listOf("submit $key hold=true", "hold"), r.calls)
        assertEquals(1, r.engine.heldCount)
        r.finish()
        assertEquals(listOf("$key ok"), r.reports)
        assertEquals(1, r.idle) // the service stops on this
    }

    @Test
    fun refusedHoldStart_isLoggedOnly_andTheEngineReportsTheRequestOnce() = runTest {
        val r = Rig(this, bound = false)
        r.holdRefused = true // a background start refused on Android 12+
        r.upload(art, key) // does not throw
        assertEquals(listOf("submit $key hold=true", "hold"), r.calls)
        assertTrue(r.reports.isEmpty()) // no failure reported for the refused start
        assertTrue(r.logs.single().startsWith("listener not bound and the hold service not started"))
        r.finish(ok = false)
        assertEquals(listOf("$key failed"), r.reports) // once, by the engine
        assertEquals(0, r.engine.heldCount)
    }
}
