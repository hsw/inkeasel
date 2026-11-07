package io.github.hsw.inkeasel.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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
    fun polledRequest_isSettledOnce() {
        val q = queue()
        q.offer(a1)
        assertEquals(a1, q.poll())
        assertTrue(q.settle(a1))
        assertFalse(q.settle(a1))
        assertEquals(emptyList<R>(), q.takeAll())
    }

    @Test
    fun takeAll_returnsInFlightThenQueued_andEmptiesTheQueue() {
        val q = queue()
        q.offer(a1)
        assertEquals(a1, q.poll()) // uploading
        q.offer(m1)
        q.offer(a2)
        assertEquals(listOf(a1, m1, a2), q.takeAll())
        assertTrue(q.isEmpty())
        assertNull(q.poll())
        assertEquals(emptyList<R>(), q.takeAll())
    }

    @Test
    fun settleAfterTakeAll_isRefused_soTheResultIsNotReportedTwice() {
        // the service is destroyed while an upload is in flight: onDestroy reports it as failed, and the
        // worker, if it gets that far, must not report it again
        val q = queue()
        q.offer(a1)
        q.poll()
        assertEquals(listOf(a1), q.takeAll())
        assertFalse(q.settle(a1))
    }

    @Test
    fun settledRequest_isNotTakenAgain() {
        val q = queue()
        q.offer(a1)
        q.poll()
        assertTrue(q.settle(a1))
        q.offer(a2)
        assertEquals(listOf(a2), q.takeAll())
    }

    @Test
    fun settle_ofARequestNotInFlight_isRefused() {
        val q = queue()
        q.offer(a1)
        assertFalse(q.settle(a1)) // queued, not polled
        assertEquals(listOf(a1), q.takeAll())
    }

    @Test
    fun staleRequestTakenByTakeAll_isNotHandedToOnStale() {
        val q = queue()
        q.offer(a1)
        val dropped = mutableListOf<R>()
        val taken = mutableListOf<R>()
        // takeAll runs (as onDestroy would) while the stale check is in progress
        assertNull(q.poll(stale = { taken += q.takeAll(); true }) { dropped += it })
        assertEquals(listOf(a1), taken)
        assertEquals(emptyList<R>(), dropped)
    }

    @Test
    fun staleRequest_isNoLongerInFlight() {
        val q = queue()
        q.offer(a1)
        assertNull(q.poll(stale = { true }) { })
        assertEquals(emptyList<R>(), q.takeAll())
    }

    @Test
    fun settleRacingTakeAll_eachRequestReportedExactlyOnce() {
        repeat(2_000) {
            val q = queue()
            q.offer(a1)
            q.poll()
            q.offer(m1)
            val reports = AtomicInteger()
            val go = CountDownLatch(1)
            val worker = thread { go.await(); if (q.settle(a1)) reports.incrementAndGet() }
            val destroy = thread { go.await(); reports.addAndGet(q.takeAll().count { it == a1 }) }
            go.countDown()
            worker.join()
            destroy.join()
            assertEquals(1, reports.get())
        }
    }
}
