package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawGateTest {
    private class MemStore : DrawGate.Store {
        var time: Long? = null
        override fun load() = time
        override fun save(time: Long) {
            this.time = time
        }
    }

    private var now = 1_000_000L
    private val store = MemStore()
    private fun gate(store: DrawGate.Store = this.store) = DrawGate({ now }, store, gapMs = 35_000)

    @Test
    fun noUploadYet_noWait() {
        assertEquals(0, gate().waitMs())
    }

    @Test
    fun rightAfterEnd_waitsTheWholeGap() {
        val g = gate()
        g.ended()
        assertEquals(35_000, g.waitMs())
    }

    @Test
    fun waitShrinksWithTime_andEndsAtTheGap() {
        val g = gate()
        g.ended()
        now += 20_000
        assertEquals(15_000, g.waitMs())
        now += 14_999
        assertEquals(1, g.waitMs())
        now += 1
        assertEquals(0, g.waitMs())
        now += 60_000
        assertEquals(0, g.waitMs())
    }

    @Test
    fun laterEnd_restartsTheGap() {
        val g = gate()
        g.ended()
        now += 40_000
        g.ended()
        now += 5_000
        assertEquals(30_000, g.waitMs())
    }

    @Test
    fun anotherGateOnTheSameStore_seesTheEnd() {
        // a new service start or a new process: a new FrameClient and gate, the same prefs
        gate().ended()
        now += 10_000
        assertEquals(25_000, gate().waitMs())
    }

    @Test
    fun storedTimeAfterNow_rebootCountsAsNoUpload() {
        store.time = now + 5_000 // elapsedRealtime restarted from zero since that upload
        assertEquals(0, gate().waitMs())
    }

    @Test
    fun defaultGap_coversTheRefresh() {
        val g = DrawGate({ now }, store)
        g.ended()
        assertEquals(DrawGate.GAP_MS, g.waitMs())
        assertEquals(true, DrawGate.GAP_MS >= 30_000) // busy ~27 s after the link drop, docs/device-api.md §14.3
    }
}
