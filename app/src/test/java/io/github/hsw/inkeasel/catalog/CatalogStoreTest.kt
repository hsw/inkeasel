package io.github.hsw.inkeasel.catalog

import android.content.Context
import android.content.res.Configuration
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.hsw.inkeasel.Http
import io.github.hsw.inkeasel.catalog.CatalogSyncPlan.CORE
import io.github.hsw.inkeasel.catalog.ReleaseFixtures.core
import io.github.hsw.inkeasel.catalog.ReleaseFixtures.gzip
import io.github.hsw.inkeasel.catalog.ReleaseFixtures.label
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
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
    private val starter = StarterSet(
        core = { ByteArrayInputStream(gzip(listOf(core(1, fame = 50), core(2, fame = 40)))) },
        labels = {
            ByteArrayInputStream(
                gzip(listOf(label(1, "Старт", "ru"), label(1, "Start", "en"), label(2, "Zwei", "de"))),
            )
        },
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
    fun aLanguageTheStarterLacksIsLookedForOncePerProcess() {
        release.server.shutdown()
        store.start(ruChain)
        awaitPasses(1)
        val fr = LanguageChain.of("fr")

        store.start(fr)
        awaitPasses(2)
        store.start(fr)
        awaitPasses(3)

        assertEquals(1, logged.count { it.startsWith("starter labels of [fr]") })
        assertTrue(logged.any { it == "starter labels of [fr]: none" })
        // nothing was swapped, so the Russian labels stay until a French import
        assertEquals(listOf("en", "ru"), query("SELECT DISTINCT lang FROM label ORDER BY lang"))
    }

    @Test
    fun theChainComesFromTheConfigurationsFirstLocale() {
        val config = Configuration().apply { setLocales(android.os.LocaleList(Locale.forLanguageTag("pt-BR"), Locale.US)) }
        assertEquals(listOf("pt-br", "pt", "en", "mul"), CatalogStore.chainOf(config))
    }

    @Test
    fun theStoreIsOnePerProcess() {
        val a = CatalogStore.get(context)
        assertNotNull(a)
        assertTrue(a === CatalogStore.get(context.applicationContext))
    }
}
