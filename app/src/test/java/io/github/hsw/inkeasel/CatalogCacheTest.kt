package io.github.hsw.inkeasel

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val a = Picture("cma:1", "Lake", "Someone, 1890", "https://example.org/a-web.jpg", "https://example.org/a-print.jpg")
    private val b = Picture("cma:2", "Hills", "", "https://example.org/b-web.jpg", "https://example.org/b-print.jpg")

    @Test
    fun encodeDecode_roundTrip() {
        val c = CachedCatalog(1_700_000_000_000L, listOf(a, b))
        assertEquals(c, CatalogCache.decode(CatalogCache.encode(c)))
    }

    @Test
    fun corruptOrForeignJson_noCache() {
        assertNull(CatalogCache.decode(""))
        assertNull(CatalogCache.decode("{\"savedAt\": 1, \"items\": [")) // cut off mid-write
        assertNull(CatalogCache.decode("[1, 2]"))
        assertNull(CatalogCache.decode("{\"items\": []}"))
    }

    @Test
    fun missingSavedAtOrItems_noCache() {
        val item = "{\"id\":\"x\",\"thumbUrl\":\"t\",\"fullUrl\":\"f\"}"
        assertNull(CatalogCache.decode("{\"items\": [$item]}"))
        assertNull(CatalogCache.decode("{\"savedAt\": 5}"))
    }

    @Test
    fun itemNotAsEncodeWritesIt_noCache() {
        val good = "{\"id\":\"x\",\"title\":\"\",\"credit\":\"\",\"thumbUrl\":\"t\",\"fullUrl\":\"f\"}"
        assertEquals(CachedCatalog(5, listOf(Picture("x", "", "", "t", "f"))), CatalogCache.decode("{\"savedAt\": 5, \"items\": [$good]}"))
        assertNull(CatalogCache.decode("{\"savedAt\": 5, \"items\": [$good, {\"id\": \"y\", \"fullUrl\": \"f\"}]}"))
        assertNull(CatalogCache.decode("{\"savedAt\": 5, \"items\": [$good, \"not an object\"]}"))
    }

    @Test
    fun emptyList_noCache() {
        assertNull(CatalogCache.decode("{\"savedAt\": 5, \"items\": []}"))
        assertNull(CatalogCache.decode(CatalogCache.encode(CachedCatalog(5, emptyList()))))
    }

    @Test
    fun writeThenRead_perSource() = runTest {
        val cache = CatalogCache(File(tmp.root, "catalog")) // created on the first write
        assertNull(cache.read("cma"))
        cache.write("cma", listOf(a, b), 100)
        cache.write("commons", listOf(b), 200)
        assertEquals(CachedCatalog(100, listOf(a, b)), cache.read("cma"))
        assertEquals(CachedCatalog(200, listOf(b)), cache.read("commons"))
        // a newer good list replaces the old one; no temporary file is left behind
        cache.write("cma", listOf(b), 300)
        assertEquals(CachedCatalog(300, listOf(b)), cache.read("cma"))
        assertEquals(setOf("cma.json", "commons.json"), File(tmp.root, "catalog").list()!!.toSet())
    }

    @Test
    fun emptyResult_doesNotReplaceANonEmptyCache() = runTest {
        val cache = CatalogCache(tmp.root)
        cache.write("cma", listOf(a), 100)
        cache.write("cma", emptyList(), 200)
        assertEquals(CachedCatalog(100, listOf(a)), cache.read("cma"))
        cache.write("commons", emptyList(), 200)
        assertFalse(File(tmp.root, "commons.json").exists())
    }

    @Test
    fun corruptFile_noCache_replacedByTheNextGoodList() = runTest {
        val cache = CatalogCache(tmp.root)
        File(tmp.root, "cma.json").writeText("{garbage")
        assertNull(cache.read("cma"))
        cache.write("cma", listOf(a), 100)
        assertEquals(CachedCatalog(100, listOf(a)), cache.read("cma"))
    }

    @Test
    fun failedWrite_keepsTheSavedList_noTemporaryFile() = runTest {
        val dir = File(tmp.root, "catalog")
        val cache = CatalogCache(dir)
        cache.write("cma", listOf(a), 100)
        assertTrue(dir.setWritable(false))
        try {
            cache.write("cma", listOf(b), 200) // the temporary file cannot be created
        } finally {
            dir.setWritable(true)
        }
        assertEquals(CachedCatalog(100, listOf(a)), cache.read("cma"))
        assertEquals(listOf("cma.json"), dir.list()!!.toList())
        // a source whose directory cannot be made at all: nothing saved, nothing thrown
        val blocked = CatalogCache(File(tmp.newFile("not-a-dir"), "catalog"))
        blocked.write("cma", listOf(a), 100)
        assertNull(blocked.read("cma"))
    }

    @Test
    fun failedLoad_showsTheSavedListWithTheReason_orTheFailure() {
        val reason = Txt(R.string.catalog_err_timeout)
        assertEquals(
            CatalogState.Loaded(listOf(a), staleSince = 100, staleReason = reason),
            CatalogState.failed(reason, CachedCatalog(100, listOf(a))),
        )
        assertEquals(CatalogState.Failed(reason), CatalogState.failed(reason, null))
    }

    @Test
    fun retry_keepsAStaleList_otherwiseLoading() {
        val stale = CatalogState.Loaded(listOf(a), staleSince = 100, staleReason = Txt(R.string.catalog_err_offline))
        assertEquals(stale.copy(retrying = true), CatalogState.reloading(stale))
        assertEquals(CatalogState.Loading, CatalogState.reloading(null))
        assertEquals(CatalogState.Loading, CatalogState.reloading(CatalogState.Failed(Txt(R.string.catalog_err_offline))))
        assertEquals(CatalogState.Loading, CatalogState.reloading(CatalogState.Loaded(listOf(a))))
    }
}
