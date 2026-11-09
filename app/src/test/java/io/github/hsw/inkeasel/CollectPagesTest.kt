package io.github.hsw.inkeasel

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class CollectPagesTest {
    /** Pages of [perPage] pictures, numbered on; page [last] has no next one. */
    private class Pages(val perPage: Int, val last: Int = Int.MAX_VALUE, val delayMs: Long = 0, val failOn: Int = 0) {
        val asked = mutableListOf<String?>()
        suspend fun fetch(token: String?): Page<Picture> {
            asked += token
            val n = asked.size
            delay(delayMs)
            if (n == failOn) throw IOException("page $n failed")
            val items = (1..perPage).map { Picture("p:$n:$it", "", "", "t", "f") }
            return Page(items, if (n < last) "after-$n" else null)
        }
    }

    @Test
    fun pages_untilNoNextOne() = runTest {
        val p = Pages(perPage = 10, last = 2)
        val got = collectPages(maxPages = 3, target = 120, deadlineMs = 30_000, fetch = p::fetch)
        assertEquals(20, got.items.size)
        assertTrue(got.complete)
        assertEquals(listOf(null, "after-1"), p.asked)
    }

    @Test
    fun pages_stopAtTheLimitOrTheTarget() = runTest {
        val byPages = Pages(perPage = 10)
        assertEquals(30, collectPages(3, 120, 30_000, fetch = byPages::fetch).items.size)
        assertEquals(3, byPages.asked.size)
        val byTarget = Pages(perPage = 70)
        val got = collectPages(3, 120, 30_000, fetch = byTarget::fetch)
        assertEquals(140, got.items.size)
        assertTrue(got.complete)
        assertEquals(2, byTarget.asked.size)
    }

    @Test
    fun failedLaterPage_keepsWhatCame_partial() = runTest {
        val logs = mutableListOf<String>()
        val got = collectPages(3, 120, 30_000, log = { logs += it }, fetch = Pages(perPage = 10, failOn = 2)::fetch)
        assertEquals(10, got.items.size)
        assertFalse(got.complete)
        assertEquals(1, logs.size)
    }

    @Test
    fun failureWithNothingCollected_rethrown() = runTest {
        try {
            collectPages(3, 120, 30_000, fetch = Pages(perPage = 10, failOn = 1)::fetch)
            fail("no exception")
        } catch (e: IOException) {
            assertEquals("page 1 failed", e.message)
        }
        try { // a first page with nothing usable, then a failure
            collectPages(3, 120, 30_000, fetch = Pages(perPage = 0, failOn = 2)::fetch)
            fail("no exception")
        } catch (e: IOException) {
            assertEquals("page 2 failed", e.message)
        }
    }

    @Test
    fun deadline_returnsWhatCame_partial() = runTest {
        val got = collectPages(3, 120, 30_000, fetch = Pages(perPage = 10, delayMs = 12_000)::fetch)
        assertEquals(20, got.items.size) // two pages by 24 s, the third would end at 36 s
        assertFalse(got.complete)
        assertEquals(30_000L, currentTime)
    }

    @Test
    fun deadlineWithNothingCollected_timeout() = runTest {
        try {
            collectPages(3, 120, 30_000, fetch = Pages(perPage = 10, delayMs = 31_000)::fetch)
            fail("no exception")
        } catch (e: SocketTimeoutException) {
            assertEquals(30_000L, currentTime)
        }
    }
}
