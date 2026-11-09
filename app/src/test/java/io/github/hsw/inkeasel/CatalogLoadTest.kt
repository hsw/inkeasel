package io.github.hsw.inkeasel

import kotlinx.coroutines.CancellationException
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

class CatalogLoadTest {
    private fun pic(n: Int) = Picture("t:$n", "Picture $n", "", "https://example.org/$n-s.jpg", "https://example.org/$n.jpg")
    private fun pics(n: Int) = (1..n).map(::pic)

    /** A tab's source (as "My pictures") that answers [answer] (or throws it). */
    private class Fake(val answer: () -> Listing<Picture>) : Source {
        override val id = "fake"
        override val name = Txt(R.string.tab_local)
        override suspend fun load() = answer()
    }

    // --- loadCatalog ---

    @Test
    fun success_shown() = runTest {
        assertEquals(CatalogState.Loaded(pics(3)), loadCatalog(Fake { Listing(pics(3)) }))
        assertEquals(CatalogState.Loaded(emptyList()), loadCatalog(Fake { Listing(emptyList()) }))
    }

    @Test
    fun failure_speaksOfThePictures_notOfACatalog() = runTest {
        val logs = mutableListOf<String>()
        assertEquals(
            CatalogState.Failed(Txt(R.string.local_failed, "Permission denied")),
            loadCatalog(Fake { throw IOException("Permission denied") }) { logs += it },
        )
        assertEquals(1, logs.size)
        val own = Txt(R.string.share_err_save) // a text of its own is kept
        assertEquals(CatalogState.Failed(own), loadCatalog(Fake { throw TxtException(own) }))
    }

    @Test
    fun cancellation_isNotAFailure() = runTest {
        try {
            loadCatalog(Fake { throw CancellationException("gone") })
            fail("no exception")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
    }

    // --- needsLoad ---

    @Test
    fun needsLoad_neverLoadedOrFailed() {
        assertTrue((null as CatalogState?).needsLoad())
        assertTrue(CatalogState.Failed(Txt(R.string.local_failed, "x")).needsLoad())
        assertFalse(CatalogState.Loaded(pics(1)).needsLoad())
        assertFalse(CatalogState.Loaded(emptyList()).needsLoad())
        assertFalse(CatalogState.Loading.needsLoad())
    }

    // --- collectPages ---

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

    // --- what the Catalog tab shows ---

    @Test
    fun catalogView_anyRowShowsTheGrid() {
        assertEquals(CatalogView.GRID, CatalogView.of(1, empty = false, refreshing = false))
        // rows already there stay while a new pass reads the list again, even with a stale "empty"
        assertEquals(CatalogView.GRID, CatalogView.of(9_818, empty = true, refreshing = true))
    }

    @Test
    fun catalogView_noneOnlyWhenTheStarterStepLeftItEmptyAndNothingIsBeingRead() {
        assertEquals(CatalogView.NONE, CatalogView.of(0, empty = true, refreshing = false))
        // the list is being read: it may still bring rows
        assertEquals(CatalogView.LOADING, CatalogView.of(0, empty = true, refreshing = true))
        // the starter set is on its way (the store has not said it is empty)
        assertEquals(CatalogView.LOADING, CatalogView.of(0, empty = false, refreshing = false))
        assertEquals(CatalogView.LOADING, CatalogView.of(0, empty = false, refreshing = true))
    }
}
