package io.github.hsw.inkeasel.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadQueueTest {
    private data class R(val name: String, val auto: Boolean)

    private val m1 = R("m1", auto = false)
    private val m2 = R("m2", auto = false)
    private val a1 = R("a1", auto = true)
    private val a2 = R("a2", auto = true)

    private fun queue() = UploadQueue<R> { it.auto }

    private fun UploadQueue<R>.drain() = generateSequence { poll() }.toList()

    @Test
    fun emptyQueue_pollsNull() {
        val q = queue()
        assertTrue(q.isEmpty())
        assertNull(q.poll())
    }

    @Test
    fun newerManualReplacesQueuedManual() {
        val q = queue()
        assertNull(q.offer(m1))
        assertEquals(m1, q.offer(m2))
        assertEquals(listOf(m2), q.drain())
    }

    @Test
    fun newerAutoReplacesQueuedAuto_andReturnsIt() {
        val q = queue()
        q.offer(a1)
        assertEquals(a1, q.offer(a2))
        assertEquals(listOf(a2), q.drain())
    }

    @Test
    fun autoNeverReplacesManual_bothUploadInArrivalOrder() {
        val q = queue()
        assertNull(q.offer(m1))
        assertNull(q.offer(a1))
        assertEquals(listOf(m1, a1), q.drain())
        assertTrue(q.isEmpty())
    }

    @Test
    fun manualNeverReplacesAuto_andGoesAheadOfIt() {
        val q = queue()
        q.offer(a1)
        assertNull(q.offer(m1))
        assertEquals(listOf(m1, a1), q.drain())
    }

    @Test
    fun newerManualReplacingAQueuedOne_staysAheadOfAuto() {
        val q = queue()
        q.offer(m1)
        q.offer(a1)
        assertEquals(m1, q.offer(m2))
        assertEquals(listOf(m2, a1), q.drain())
    }

    @Test
    fun newerAutoReplacingAQueuedOne_staysBehindManual() {
        val q = queue()
        q.offer(a1)
        q.offer(m1)
        assertEquals(a1, q.offer(a2))
        assertEquals(listOf(m1, a2), q.drain())
    }

    @Test
    fun pollSkippingStale_handsStaleOnesOverAndReturnsTheNextLiveOne() {
        val q = queue()
        q.offer(m1)
        q.offer(a1)
        val dropped = mutableListOf<R>()
        assertEquals(m1, q.poll(stale = { it.auto }) { dropped += it })
        assertNull(q.poll(stale = { it.auto }) { dropped += it })
        assertEquals(listOf(a1), dropped)
        assertTrue(q.isEmpty())
    }

    @Test
    fun pollSkippingStale_nothingStale_isPlainPoll() {
        val q = queue()
        q.offer(a1)
        assertEquals(a1, q.poll(stale = { false }) { error("not stale") })
        assertNull(q.poll(stale = { false }) { error("not stale") })
    }

    @Test
    fun polledRequest_leavesTheQueue() {
        val q = queue()
        q.offer(a1)
        assertEquals(a1, q.poll())
        assertTrue(q.isEmpty())
        assertNull(q.poll())
    }

    @Test
    fun staleRequest_leavesTheQueue_andIsHandedOverOnce() {
        val q = queue()
        q.offer(a1)
        val dropped = mutableListOf<R>()
        assertNull(q.poll(stale = { true }) { dropped += it })
        assertEquals(listOf(a1), dropped)
        assertTrue(q.isEmpty())
    }
}
