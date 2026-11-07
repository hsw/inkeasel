package io.github.hsw.inkeasel

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class CatalogLoadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun pic(n: Int) = Picture("t:$n", "Picture $n", "", "https://example.org/$n-s.jpg", "https://example.org/$n.jpg")
    private fun pics(n: Int) = (1..n).map(::pic)

    /** A source that answers [answer] (or throws it). */
    private class Fake(val answer: () -> Listing) : Source {
        override val id = "fake"
        override val name = Txt(R.string.tab_paintings)
        override suspend fun load() = answer()
    }

    private val offline = Txt(R.string.catalog_err_offline)

    // --- loadCatalog ---

    @Test
    fun remoteSuccess_shownAndSaved() = runTest {
        val cache = CatalogCache(tmp.root)
        assertEquals(CatalogState.Loaded(pics(3)), loadCatalog(Fake { Listing(pics(3)) }, cache, { 100 }))
        assertEquals(CachedCatalog(100, pics(3)), cache.read("fake"))
    }

    @Test
    fun localTab_neverSavedNorRead() = runTest {
        val cache = CatalogCache(tmp.root)
        cache.write("fake", pics(2), 50) // even if a file of that name were there
        assertEquals(CatalogState.Loaded(pics(1)), loadCatalog(Fake { Listing(pics(1)) }, null, { 100 }))
        assertEquals(CachedCatalog(50, pics(2)), cache.read("fake"))
        assertEquals(
            CatalogState.Failed(offline),
            loadCatalog(Fake { throw UnknownHostException("example.org") }, null, { 100 }),
        )
    }

    @Test
    fun emptyAnswer_shown_neverReplacesTheSavedList() = runTest {
        val cache = CatalogCache(tmp.root)
        cache.write("fake", pics(2), 50)
        assertEquals(CatalogState.Loaded(emptyList()), loadCatalog(Fake { Listing(emptyList()) }, cache, { 100 }))
        assertEquals(CachedCatalog(50, pics(2)), cache.read("fake"))
    }

    @Test
    fun failure_showsTheSavedListWithTheReason_orTheFailure() = runTest {
        val cache = CatalogCache(tmp.root)
        val down = Fake { throw UnknownHostException("example.org") }
        assertEquals(CatalogState.Failed(offline), loadCatalog(down, cache, { 100 }))
        cache.write("fake", pics(2), 50)
        assertEquals(
            CatalogState.Loaded(pics(2), staleSince = 50, staleReason = offline),
            loadCatalog(down, cache, { 100 }),
        )
    }

    @Test
    fun partialList_neverShrinksTheSavedOne() = runTest {
        val cache = CatalogCache(tmp.root)
        cache.write("fake", pics(5), 50)
        val partial = loadCatalog(Fake { Listing(pics(3), complete = false) }, cache, { 100 })
        assertEquals(CatalogState.Loaded(pics(3)), partial) // shown all the same
        assertEquals(CachedCatalog(50, pics(5)), cache.read("fake"))
        loadCatalog(Fake { Listing(pics(5), complete = false) }, cache, { 200 }) // not smaller: saved
        assertEquals(200L, cache.read("fake")?.savedAt)
        loadCatalog(Fake { Listing(pics(2)) }, cache, { 300 }) // a complete list is saved whatever its size
        assertEquals(CachedCatalog(300, pics(2)), cache.read("fake"))
    }

    @Test
    fun partialList_savedWhenNothingIs() = runTest {
        val cache = CatalogCache(tmp.root)
        loadCatalog(Fake { Listing(pics(3), complete = false) }, cache, { 100 })
        assertEquals(CachedCatalog(100, pics(3)), cache.read("fake"))
    }

    // --- needsLoad ---

    @Test
    fun needsLoad_neverLoadedFailedOrStale_notWhileRetrying() {
        val stale = CatalogState.Loaded(pics(1), staleSince = 50, staleReason = offline)
        assertTrue((null as CatalogState?).needsLoad())
        assertTrue(CatalogState.Failed(offline).needsLoad())
        assertTrue(stale.needsLoad())
        assertFalse(stale.copy(retrying = true).needsLoad())
        assertFalse(CatalogState.Loaded(pics(1)).needsLoad())
        assertFalse(CatalogState.Loaded(emptyList()).needsLoad())
        assertFalse(CatalogState.Loading.needsLoad())
    }

    // --- collectPages ---

    /** Pages of [perPage] pictures, numbered on; page [last] has no next one. */
    private class Pages(val perPage: Int, val last: Int = Int.MAX_VALUE, val delayMs: Long = 0, val failOn: Int = 0) {
        val asked = mutableListOf<String?>()
        suspend fun fetch(token: String?): Page {
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
