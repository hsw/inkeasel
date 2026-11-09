package io.github.hsw.inkeasel.catalog

import android.content.Context
import android.content.res.Configuration
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.hsw.inkeasel.Http
import io.github.hsw.inkeasel.Listing
import io.github.hsw.inkeasel.catalog.CatalogSyncPlan.CORE
import io.github.hsw.inkeasel.catalog.ReleaseFixtures.core
import io.github.hsw.inkeasel.catalog.ReleaseFixtures.gzip
import io.github.hsw.inkeasel.catalog.ReleaseFixtures.label
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class CatalogStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: CatalogDb
    private lateinit var release: FakeRelease
    private lateinit var temp: File
    private lateinit var store: CatalogStore

    private val ruChain = LanguageChain.of("ru-RU")
    private val deChain = LanguageChain.of("de-DE")
    private val logged = CopyOnWriteArrayList<String>()

    /** Q1, Q2 with Russian, English and German labels (the German one is imported only once German is wanted). */
    private val starterLabels = mapOf(
        "ru" to listOf(label(1, "Старт")),
        "en" to listOf(label(1, "Start")),
        "de" to listOf(label(2, "Zwei")),
    )
    private val opened = CopyOnWriteArrayList<String>()
    private val starter = StarterSet(
        core = { ByteArrayInputStream(gzip(listOf(core(1, fame = 50), core(2, fame = 40)))) },
        languages = { starterLabels.keys },
        labels = { lang -> opened += lang; ByteArrayInputStream(gzip(starterLabels.getValue(lang))) },
    )

    @Before
    fun open() {
        db = CatalogDb.configure(Room.inMemoryDatabaseBuilder(context, CatalogDb::class.java)).build()
        release = FakeRelease()
        temp = File(context.cacheDir, "store-test").apply { deleteRecursively() }
        val log: (String, Throwable?) -> Unit = { msg, e -> logged += if (e == null) msg else "$msg: $e" }
        store = CatalogStore(db, starter, CatalogSync(db, Http.client, temp, release.base, log = log), ruChain, log)
    }

    @After
    fun close() {
        store.close()
        db.close()
        release.server.shutdown()
        temp.deleteRecursively()
    }

    private fun awaitPasses(n: Int) = runBlocking { withTimeout(20_000) { store.passes.first { it >= n } } }

    private fun query(sql: String): List<String> = db.openHelper.readableDatabase.query(sql).use { c ->
        buildList {
            while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" })
        }
    }

    private fun title(id: String, chain: List<String>) =
        runBlocking { db.catalog().row(id, CatalogRow.chainKey(chain))?.title }

    @Test
    fun theFirstStartImportsTheStarterSetThenTheRelease() {
        release.serve(CORE, gzip(listOf(core(1, fame = 50), core(2, fame = 40), core(3))))
        release.serve("labels-ru.jsonl.gz", gzip(listOf(label(1, "Один"), label(3, "Три"))))

        store.start(ruChain)
        awaitPasses(1)

        assertTrue(logged.any { it.startsWith("starter set imported") })
        assertEquals(listOf("ru", "en"), opened) // only the chain's own files: the German one stays shut
        assertEquals(listOf("wd:Q1", "wd:Q2", "wd:Q3"), query("SELECT id FROM picture ORDER BY rank"))
        assertEquals("Один", title("wd:Q1", ruChain))
        assertEquals("Три", title("wd:Q3", ruChain))
        assertEquals(
            listOf(CORE, "labels-en.jsonl.gz", "labels-mul.jsonl.gz", "labels-ru.jsonl.gz"),
            query("SELECT name FROM catalog_file ORDER BY name"),
        )
    }

    @Test
    fun anOfflineFirstStartShowsTheStarterSet() {
        release.server.shutdown()

        store.start(ruChain)
        awaitPasses(1)

        assertEquals(listOf("wd:Q1", "wd:Q2"), query("SELECT id FROM picture ORDER BY rank"))
        assertEquals("Старт", title("wd:Q1", ruChain))
        // the German label is not in the chain
        assertEquals(listOf("en", "ru"), query("SELECT DISTINCT lang FROM label ORDER BY lang"))
        assertTrue(query("SELECT name FROM catalog_file").isEmpty())
        assertTrue(logged.any { it.contains("check failed") })
    }

    @Test
    fun aLaterStartRunsAnotherPassThatAsksOnlyForWhatIsDue() {
        release.serve(CORE, gzip(listOf(core(1, fame = 50))))
        store.start(ruChain)
        awaitPasses(1)
        val asked = release.requests.size
        assertEquals(4, asked) // core and three labels files, one 200 and three 404

        store.start(ruChain)
        awaitPasses(2)

        assertEquals(asked, release.requests.size)
        assertEquals(1, logged.count { it.startsWith("starter set imported") })
    }

    @Test
    fun aLanguageThatJoinsTheChainShowsItsStarterLabelsAndIsFetched() {
        release.server.shutdown() // offline: the starter set is the only source
        store.start(ruChain)
        awaitPasses(1)

        store.start(deChain)
        awaitPasses(2)

        assertEquals(deChain, store.chain.value)
        assertEquals("Zwei", title("wd:Q2", deChain))
        // Russian left the chain, so its labels went at the swap
        assertEquals(listOf("de", "en"), query("SELECT DISTINCT lang FROM label ORDER BY lang"))
        assertTrue(logged.any { it.startsWith("starter labels of [de]") })
        assertTrue(logged.any { it.startsWith("labels-de.jsonl.gz: check failed") })
    }

    @Test
    fun aLanguageTheStarterLacksImportsNothing() {
        release.server.shutdown()
        store.start(ruChain)
        awaitPasses(1)
        val fr = LanguageChain.of("fr")

        store.start(fr)
        awaitPasses(2)
        store.start(fr)
        awaitPasses(3)

        assertFalse(logged.any { it.startsWith("starter labels of") })
        assertFalse("fr" in opened)
        // nothing was swapped, so the Russian labels stay until a French import
        assertEquals(listOf("en", "ru"), query("SELECT DISTINCT lang FROM label ORDER BY lang"))
    }

    @Test
    fun aLanguageThatLeavesAndComesBackShowsItsStarterLabelsAgainOffline() {
        release.server.shutdown()
        store.start(ruChain)
        awaitPasses(1)
        store.start(deChain) // the swap drops the Russian labels
        awaitPasses(2)
        assertEquals(listOf("de", "en"), query("SELECT DISTINCT lang FROM label ORDER BY lang"))

        store.start(ruChain)
        awaitPasses(3)

        assertEquals("Старт", title("wd:Q1", ruChain))
        assertEquals(listOf("en", "ru"), query("SELECT DISTINCT lang FROM label ORDER BY lang"))
        assertTrue(logged.any { it.startsWith("starter labels of [ru]") })
    }

    @Test
    fun aUiLanguageWithNoLabelsFileFallsBackToEnglishThenTheFileName() {
        // Kazakh: neither the starter set nor the release has its labels (the release answers 404)
        val kk = LanguageChain.of("kk-KZ")
        assertEquals(listOf("kk", "en", "mul"), kk)
        release.serve(CORE, gzip(listOf(core(1, fame = 50), core(2, fame = 40), core(3))))
        release.serve("labels-en.jsonl.gz", gzip(listOf(label(1, "One"), label(3, "Three"))))

        store.start(kk)
        awaitPasses(1)

        assertEquals(listOf("en"), opened) // the starter set has no Kazakh file
        val rows = runBlocking { store.pages.asSnapshot() }
        assertEquals(listOf("wd:Q1", "wd:Q2", "wd:Q3"), rows.map { it.id })
        assertEquals(listOf("One", null, "Three"), rows.map { it.title })
        assertEquals("Painting 2", rows[1].shownTitle) // no label in any language: the file name
        assertEquals(
            listOf("labels-kk.jsonl.gz|1", "labels-mul.jsonl.gz|1"),
            query("SELECT name, missing FROM catalog_file WHERE missing = 1 ORDER BY name"),
        )

        // the 404 is not asked again within the week
        val asked = release.requests.size
        store.start(kk)
        awaitPasses(2)
        assertEquals(asked, release.requests.size)
    }

    @Test
    fun theListIsNotEmptyOnceTheStarterSetIsIn() {
        release.server.shutdown()
        assertFalse(store.empty.value) // before the starter step: still loading, not empty
        // and the list has not started: its first query waits for the starter set
        assertNull(runBlocking { withTimeoutOrNull(300) { store.pages.first() } })

        store.start(ruChain)
        awaitPasses(1)

        assertFalse(store.empty.value)
        val rows = runBlocking { store.pages.asSnapshot() }
        assertEquals(listOf("wd:Q1", "wd:Q2"), rows.map { it.id })
        assertEquals("Старт", rows.first().title)
    }

    @Test
    fun aFailedStarterImportWithNothingStoredIsEmpty() {
        release.server.shutdown()
        val broken = StarterSet({ ByteArrayInputStream("not json\n".toByteArray()) }, starter.languages, starter.labels)
        val log: (String, Throwable?) -> Unit = { msg, e -> logged += if (e == null) msg else "$msg: $e" }
        val store = CatalogStore(db, broken, CatalogSync(db, Http.client, temp, release.base, log = log), ruChain, log)
        try {
            store.start(ruChain)
            runBlocking { withTimeout(20_000) { store.passes.first { it >= 1 } } }
            assertTrue(store.empty.value)
            assertTrue(logged.any { it.startsWith("starter import failed") })
        } finally {
            store.close()
        }
    }

    @Test
    fun aFailedStarterImportIsNotEmptyOnceTheReleaseIsIn() {
        release.serve(CORE, gzip(listOf(core(1, fame = 50), core(2, fame = 40), core(3))))
        release.serve("labels-ru.jsonl.gz", gzip(listOf(label(3, "Три"))))
        val broken = StarterSet({ ByteArrayInputStream("not json\n".toByteArray()) }, starter.languages, starter.labels)
        val log: (String, Throwable?) -> Unit = { msg, e -> logged += if (e == null) msg else "$msg: $e" }
        val store = CatalogStore(db, broken, CatalogSync(db, Http.client, temp, release.base, log = log), ruChain, log)
        try {
            store.start(ruChain)
            runBlocking { withTimeout(20_000) { store.passes.first { it >= 1 } } }
            assertTrue(logged.any { it.startsWith("starter import failed") })
            // the pass's release import filled the database: the list shows it, and "none yet" goes
            assertFalse(store.empty.value)
            val rows = runBlocking { store.pages.asSnapshot() }
            assertEquals(listOf("wd:Q1", "wd:Q2", "wd:Q3"), rows.map { it.id })
            assertEquals("Три", rows.last().title)
        } finally {
            store.close()
        }
    }

    @Test
    fun aRestartOverAFilledDatabaseImportsNothingAndAsksForNothingWithinTheWeek() {
        release.serve(CORE, gzip(listOf(core(1, fame = 50), core(2, fame = 40), core(3))))
        release.serve("labels-ru.jsonl.gz", gzip(listOf(label(1, "Один"))))
        store.start(ruChain)
        awaitPasses(1)
        store.close()
        val labels = query("SELECT * FROM label ORDER BY entityId, lang")
        val asked = release.requests.size
        opened.clear()

        // the next process: a new store over the same database
        val later = CopyOnWriteArrayList<String>()
        val log: (String, Throwable?) -> Unit = { msg, e -> later += if (e == null) msg else "$msg: $e" }
        val next = CatalogStore(db, starter, CatalogSync(db, Http.client, temp, release.base, log = log), ruChain, log)
        try {
            next.start(ruChain)
            runBlocking { withTimeout(20_000) { next.passes.first { it >= 1 } } }

            assertFalse(later.any { it.startsWith("starter") })
            assertTrue(opened.isEmpty()) // no starter file read
            assertEquals(asked, release.requests.size)
            assertEquals(labels, query("SELECT * FROM label ORDER BY entityId, lang"))
            assertFalse(next.empty.value)
            assertEquals("Один", runBlocking { next.pages.asSnapshot() }.first().title)
        } finally {
            next.close()
        }
    }

    @Test
    fun scrollingThroughTheWholeListKeepsOnlyAWindowOfRowsLoaded() {
        release.server.shutdown()
        val n = 1000
        val big = StarterSet(
            core = { ByteArrayInputStream(gzip((1..n).map { core(it, fame = n - it) })) },
            languages = { emptySet() },
            labels = { error("no labels") },
        )
        val log: (String, Throwable?) -> Unit = { msg, e -> logged += if (e == null) msg else "$msg: $e" }
        val store = CatalogStore(db, big, CatalogSync(db, Http.client, temp, release.base, log = log), ruChain, log)
        try {
            store.start(ruChain)
            runBlocking { withTimeout(20_000) { store.passes.first { it >= 1 } } }

            val end = runBlocking { store.pages.asSnapshot { scrollTo(n - 1) } }
            assertTrue("${end.size} rows loaded", end.size <= CatalogStore.MAX_ROWS)
            assertEquals("wd:Q$n", end.last().id) // the window holds where the list was read
            assertFalse(end.any { it.id == "wd:Q1" }) // and the first pages were dropped

            // read from the top again, the list starts at its first row
            val start = runBlocking { store.pages.asSnapshot() }
            assertEquals("wd:Q1", start.first().id)
        } finally {
            store.close()
        }
    }

    @Test
    fun theListFollowsTheChain() {
        release.server.shutdown()
        store.start(ruChain)
        awaitPasses(1)
        assertEquals("Старт", runBlocking { store.pages.asSnapshot() }.first().title)

        store.start(LanguageChain.of("en"))
        awaitPasses(2)
        assertEquals("Start", runBlocking { store.pages.asSnapshot() }.first().title)
    }

    @Test
    fun aPassRefreshesTheLiveSourcesAfterTheSyncAndTheOldListCacheGoes() {
        release.server.shutdown()
        val legacy = File(context.filesDir, "catalog-legacy-test").apply { mkdirs() }
        File(legacy, "cma.json").writeText("{\"format\": 2, \"savedAt\": 1, \"items\": []}")
        var asked = 0
        val cma = object : LiveSource {
            override val id = "cma"
            override suspend fun fetch(): Listing<LiveRow> {
                asked++
                return Listing(listOf(LiveRow("cma:1", "Fields", museumRef = "1915.534")))
            }
        }
        val down = object : LiveSource {
            override val id = "commons"
            override suspend fun fetch(): Listing<LiveRow> = throw UnknownHostException("commons")
        }
        val log: (String, Throwable?) -> Unit = { msg, e -> logged += if (e == null) msg else "$msg: $e" }
        val store = CatalogStore(
            db, starter, CatalogSync(db, Http.client, temp, release.base, log = log), ruChain, log,
            live = LiveRefresh(db, listOf(cma, down), log = log), legacy = legacy,
        )
        try {
            store.start(ruChain)
            runBlocking { withTimeout(20_000) { store.passes.first { it >= 1 } } }
            assertFalse(legacy.exists())
            assertTrue(logged.any { it == "old list cache deleted" })
            // offline for the release, the starter set and the live row are listed; the source that is down is logged
            assertEquals(listOf("wd:Q1", "wd:Q2", "cma:1"), query("SELECT id FROM picture WHERE hidden = 0 ORDER BY rank"))
            assertTrue(logged.any { it.startsWith("commons: refresh failed") })

            store.start(ruChain)
            runBlocking { withTimeout(20_000) { store.passes.first { it >= 2 } } }
            assertEquals(1, asked) // refreshed a moment ago: not asked again
            assertEquals(1, logged.count { it == "old list cache deleted" })
        } finally {
            store.close()
            legacy.deleteRecursively()
        }
    }

    @Test
    fun theChainComesFromTheConfigurationsFirstLocale() {
        val config = Configuration().apply { setLocales(android.os.LocaleList(Locale.forLanguageTag("pt-BR"), Locale.US)) }
        assertEquals(listOf("pt-br", "pt", "en", "mul"), CatalogStore.chainOf(config))
    }

    @Test
    fun starterAssetNames() {
        assertEquals("catalog/labels-pt-br.jsonl", CatalogStore.labelsAsset("pt-br"))
        assertEquals("pt-br", CatalogStore.assetLanguage("labels-pt-br.jsonl"))
        assertEquals("mul", CatalogStore.assetLanguage("labels-mul.jsonl"))
        listOf("core.jsonl", "labels.jsonl", "labels-.jsonl", "labels-RU.jsonl", "labels-ru.jsonl.gz", "x-ru.jsonl")
            .forEach { assertEquals(it, null, CatalogStore.assetLanguage(it)) }
    }

    @Test
    fun theStoreIsOnePerProcess() {
        val a = CatalogStore.get(context)
        assertNotNull(a)
        assertTrue(a === CatalogStore.get(context.applicationContext))
    }
}
