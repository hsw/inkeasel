package io.github.hsw.inkeasel.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.hsw.inkeasel.Http
import io.github.hsw.inkeasel.HttpStatusException
import io.github.hsw.inkeasel.catalog.CatalogSyncPlan.CHECK_EVERY_MS
import io.github.hsw.inkeasel.catalog.CatalogSyncPlan.CORE
import io.github.hsw.inkeasel.catalog.ReleaseFixtures.core
import io.github.hsw.inkeasel.catalog.ReleaseFixtures.gzip
import io.github.hsw.inkeasel.catalog.ReleaseFixtures.label
import io.github.hsw.inkeasel.catalog.ReleaseFixtures.sha256
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CatalogSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: CatalogDb
    private lateinit var release: FakeRelease
    private lateinit var temp: File

    private val ruChain = LanguageChain.of("ru-RU")
    private val deChain = LanguageChain.of("de-DE")
    private val day = 24 * 60 * 60 * 1000L
    private var clock = 1_800_000_000_000L

    private val coreBytes = gzip(listOf(core(1, fame = 50, creators = listOf(100)), core(2, fame = 40), core(3)))
    private val ruBytes = gzip(listOf(label(1, "Один"), label(2, "Два"), label(100, "Художник")))
    private val enBytes = gzip(listOf(label(1, "One"), label(2, "Two"), label(3, "Three"), label(100, "Painter")))
    private val mulBytes = gzip(listOf(label(100, "Pintor")))
    private val deBytes = gzip(listOf(label(1, "Eins")))

    @Before
    fun open() {
        db = CatalogDb.configure(Room.inMemoryDatabaseBuilder(context, CatalogDb::class.java)).build()
        release = FakeRelease()
        release.serve(CORE, coreBytes)
        release.serve("labels-ru.jsonl.gz", ruBytes)
        release.serve("labels-en.jsonl.gz", enBytes)
        release.serve("labels-mul.jsonl.gz", mulBytes)
        release.serve("labels-de.jsonl.gz", deBytes)
        temp = File(context.cacheDir, "sync-test").apply { deleteRecursively() }
    }

    @After
    fun close() {
        db.close()
        release.server.shutdown()
        temp.deleteRecursively()
    }

    private fun sync() = CatalogSync(db, Http.client, temp, release.base, now = { clock })

    private suspend fun state(name: String): CatalogFileEntity? = db.imports().files().firstOrNull { it.name == name }

    private suspend fun states(): List<String> = db.imports().files().map { it.name }.sorted()

    private suspend fun title(id: String, chain: List<String> = ruChain): String? =
        db.catalog().row(id, CatalogRow.chainKey(chain))?.title

    private fun query(sql: String): List<String> = db.openHelper.readableDatabase.query(sql).use { c ->
        buildList {
            while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" })
        }
    }

    private fun visible(): List<String> =
        query("SELECT * FROM picture ORDER BY id") + query("SELECT * FROM label ORDER BY entityId, lang") +
            query("SELECT * FROM picture_entity ORDER BY pictureId, kind, pos")

    /** A starter set of Q1 and Q2 with Russian labels only. */
    private suspend fun starter() {
        CatalogImport(db).starter(
            ImportFile("core.jsonl") { ByteArrayInputStream(gzip(listOf(core(1, fame = 50), core(2, fame = 40)))) },
            listOf(ImportFile("labels-ru.jsonl", "ru") { ByteArrayInputStream(gzip(listOf(label(1, "Старт")))) }),
            ruChain,
        )!!
    }

    private fun kinds(report: SyncReport) = report.outcomes.map { "${it.check.name} ${it.javaClass.simpleName}" }

    // --- downloads ---

    @Test
    fun theFirstRunDownloadsCoreAndTheChainLabelsAndRecordsThemWithTheImport() = runTest {
        val report = sync().run(ruChain)

        assertEquals(listOf(CORE, "labels-ru.jsonl.gz", "labels-en.jsonl.gz", "labels-mul.jsonl.gz"), release.asked())
        assertTrue(release.requests.all { it.getHeader("User-Agent")!!.startsWith("InkEasel/") })
        assertTrue(report.outcomes.all { it is SyncOutcome.Downloaded })
        val import = report.import!!
        assertEquals(3, import.added)
        assertEquals("Один", title("wd:Q1"))
        assertEquals("Three", title("wd:Q3"))
        assertEquals(listOf("Художник"), db.catalog().row("wd:Q1", CatalogRow.chainKey(ruChain))!!.artists)

        val core = state(CORE)!!
        assertEquals(release.files[CORE]!!.etag, core.etag)
        assertEquals(sha256(coreBytes), core.sha256)
        assertEquals(coreBytes.size.toLong(), core.size)
        assertEquals(clock, core.checkedAt)
        assertEquals(clock, core.importedAt)
        assertEquals(sha256(mulBytes), state("labels-mul.jsonl.gz")!!.sha256)
        assertEquals(
            listOf(CORE, "labels-en.jsonl.gz", "labels-mul.jsonl.gz", "labels-ru.jsonl.gz"),
            states(),
        )
        // no download is left behind
        assertTrue(temp.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aRunWithinTheWeekMakesNoRequest() = runTest {
        sync().run(ruChain)
        release.requests.clear()
        clock += CHECK_EVERY_MS - 1
        val report = sync().run(ruChain)
        assertEquals(SyncReport(), report)
        assertTrue(release.requests.isEmpty())
    }

    @Test
    fun aWeekLaterEachFileIsAskedWithItsEtagAndA304ImportsNothing() = runTest {
        sync().run(ruChain)
        val first = clock
        val before = visible()
        release.requests.clear()
        clock += CHECK_EVERY_MS

        val report = sync().run(ruChain)

        assertEquals(
            listOf(CORE, "labels-ru.jsonl.gz", "labels-en.jsonl.gz", "labels-mul.jsonl.gz").map {
                "$it if ${release.files[it]!!.etag}"
            },
            release.asked(),
        )
        assertTrue(report.outcomes.all { it is SyncOutcome.NotModified })
        assertNull(report.import)
        assertEquals(before, visible())
        assertEquals(clock, state(CORE)!!.checkedAt)
        assertEquals(first, state(CORE)!!.importedAt)
    }

    @Test
    fun aRedirectKeepsTheEtagAndTheCheckAlwaysStartsAtTheReleaseAddress() = runTest {
        release.redirect = true
        sync().run(ruChain)
        assertEquals(
            listOf(CORE, "assets/$CORE", "labels-ru.jsonl.gz", "assets/labels-ru.jsonl.gz"),
            release.asked().take(4),
        )
        assertEquals(release.files[CORE]!!.etag, state(CORE)!!.etag)

        release.requests.clear()
        clock += CHECK_EVERY_MS
        val report = sync().run(ruChain)
        val etag = release.files[CORE]!!.etag
        assertEquals(listOf("$CORE if $etag", "assets/$CORE if $etag"), release.asked().take(2))
        assertTrue(report.outcomes.all { it is SyncOutcome.NotModified })
    }

    @Test
    fun theSameBytesUnderANewEtagAreNotImportedAgain() = runTest {
        sync().run(ruChain)
        val first = clock
        val before = visible()
        release.serve(CORE, coreBytes, etag = "\"re-uploaded\"")
        clock += CHECK_EVERY_MS

        val report = sync().run(ruChain)

        assertEquals("$CORE Downloaded", kinds(report).first())
        assertNull(report.import)
        assertEquals(before, visible())
        val core = state(CORE)!!
        assertEquals("\"re-uploaded\"", core.etag)
        assertEquals(sha256(coreBytes), core.sha256)
        assertEquals(clock, core.checkedAt)
        assertEquals(first, core.importedAt)
    }

    @Test
    fun aChangedFileIsImportedAndOnlyItsStateMoves() = runTest {
        sync().run(ruChain)
        val first = clock
        val newRu = gzip(listOf(label(1, "Первая"), label(2, "Два")))
        release.serve("labels-ru.jsonl.gz", newRu, etag = "\"ru-2\"")
        clock += CHECK_EVERY_MS

        val report = sync().run(ruChain)

        assertEquals(
            listOf(
                "$CORE NotModified", "labels-ru.jsonl.gz Downloaded", "labels-en.jsonl.gz NotModified",
                "labels-mul.jsonl.gz NotModified",
            ),
            kinds(report),
        )
        assertEquals(listOf("labels-ru.jsonl.gz"), report.import!!.files.map { it.name })
        assertEquals("Первая", title("wd:Q1"))
        val ru = state("labels-ru.jsonl.gz")!!
        assertEquals("\"ru-2\"", ru.etag)
        assertEquals(sha256(newRu), ru.sha256)
        assertEquals(clock, ru.importedAt)
        assertEquals(first, state(CORE)!!.importedAt)
    }

    @Test
    fun aLanguageThatLeavesTheChainLosesItsStateWithItsLabelsAndANewOneComesAtOnce() = runTest {
        sync().run(ruChain)
        clock += day
        release.requests.clear()

        val report = sync().run(deChain)

        assertEquals(listOf("labels-de.jsonl.gz"), release.asked())
        assertNotNull(report.import)
        assertEquals("Eins", title("wd:Q1", deChain))
        assertEquals(listOf("de", "en", "mul"), query("SELECT DISTINCT lang FROM label ORDER BY lang"))
        assertEquals(listOf(CORE, "labels-de.jsonl.gz", "labels-en.jsonl.gz", "labels-mul.jsonl.gz"), states())

        // back to Russian: its file downloads again, without an ETag, and its labels return
        clock += day
        release.requests.clear()
        sync().run(ruChain)
        assertEquals(listOf("labels-ru.jsonl.gz"), release.asked())
        assertEquals("Один", title("wd:Q1"))
        assertTrue("labels-de.jsonl.gz" !in states())
    }

    // --- failures ---

    @Test
    fun aNetworkFailureKeepsTheStarterSetAndIsTriedAgainAtTheNextStart() = runTest {
        starter()
        val before = visible()
        val sync = sync() // its address taken while the server runs (asking for it later would start it again)
        release.server.shutdown()

        val report = sync.run(ruChain)

        assertTrue(report.outcomes.isNotEmpty())
        assertTrue(report.outcomes.all { it is SyncOutcome.Failed })
        assertNull(report.import)
        assertEquals(before, visible())
        assertEquals("Старт", title("wd:Q1"))
        assertTrue(states().isEmpty())
        // nothing recorded, so every file is due again
        assertEquals(4, CatalogSyncPlan.due(ruChain, emptyMap(), clock).size)
        assertTrue(temp.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aBodyCutShortFailsOnlyThatFile() = runTest {
        starter()
        release.overrides[CORE] = {
            MockResponse().setBody(Buffer().write(coreBytes)).setHeader("ETag", "\"c\"")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        }

        val report = sync().run(ruChain)

        assertEquals("$CORE Failed", kinds(report).first())
        // the labels still came in, over the starter's rows
        assertEquals(
            listOf("labels-ru.jsonl.gz", "labels-en.jsonl.gz", "labels-mul.jsonl.gz"),
            report.import!!.files.map { it.name },
        )
        assertEquals("Один", title("wd:Q1"))
        assertNull(db.catalog().picture("wd:Q3"))
        assertNull(state(CORE))
        assertTrue(temp.listFiles().orEmpty().isEmpty())

        // the next start asks for core again, and only for it
        release.overrides.clear()
        release.requests.clear()
        sync().run(ruChain)
        assertEquals(listOf(CORE), release.asked())
        assertNotNull(db.catalog().picture("wd:Q3"))
    }

    @Test
    fun aBrokenFileFailsTheImportSoNoHashIsRecordedButChecksAre() = runTest {
        starter()
        val before = visible()
        val truncated = coreBytes.copyOf(coreBytes.size / 2) // a complete answer holding half a gzip
        release.serve(CORE, truncated)
        release.files.remove("labels-mul.jsonl.gz")

        val report = sync().run(ruChain)

        assertNotNull(report.importError)
        assertNull(report.import)
        assertEquals(before, visible())
        // the files of the failed import keep no state; the 404 is recorded
        assertEquals(listOf("labels-mul.jsonl.gz"), states())
        assertEquals(true, state("labels-mul.jsonl.gz")!!.missing)
        assertTrue(query("SELECT id FROM picture_stage").isEmpty())
        assertTrue(query("SELECT entityId FROM label_stage").isEmpty())
    }

    @Test
    fun a404OnOneLabelsFileBlocksNothingAndIsAskedAgainAWeekLater() = runTest {
        release.files.remove("labels-mul.jsonl.gz")

        val report = sync().run(ruChain)

        assertEquals("labels-mul.jsonl.gz Missing", kinds(report).last())
        assertEquals(3, report.import!!.files.size)
        assertEquals("Один", title("wd:Q1"))
        val mul = state("labels-mul.jsonl.gz")!!
        assertEquals(true, mul.missing)
        assertNull(mul.sha256)
        assertEquals(clock, mul.checkedAt)

        release.requests.clear()
        clock += day
        sync().run(ruChain)
        assertTrue(release.requests.isEmpty())

        release.serve("labels-mul.jsonl.gz", mulBytes)
        clock += CHECK_EVERY_MS - day
        val later = sync().run(ruChain)
        assertTrue("labels-mul.jsonl.gz" in release.asked())
        assertEquals(listOf("labels-mul.jsonl.gz"), later.import!!.files.map { it.name })
        assertEquals(false, state("labels-mul.jsonl.gz")!!.missing)
    }

    @Test
    fun anotherHttpErrorIsAFailureWithItsCode() = runTest {
        release.overrides["labels-en.jsonl.gz"] = { MockResponse().setResponseCode(500) }

        val report = sync().run(ruChain)

        val failed = report.outcomes.single { it is SyncOutcome.Failed } as SyncOutcome.Failed
        assertEquals("labels-en.jsonl.gz", failed.check.name)
        assertEquals(500, (failed.error as HttpStatusException).code)
        assertNull(state("labels-en.jsonl.gz"))
        assertNotNull(state(CORE))
        assertEquals(1, release.asked().count { it == "labels-en.jsonl.gz" })
    }

    @Test
    fun aTooManyRequestsAnswerIsAskedAgainFromTheReleaseAddress() = runTest {
        release.redirect = true
        var answers = 0
        release.overrides["labels-en.jsonl.gz"] = {
            if (answers++ == 0) {
                MockResponse().setResponseCode(429).setHeader("Retry-After", "0")
            } else {
                MockResponse().setBody(Buffer().write(enBytes)).setHeader("ETag", "\"en-1\"")
            }
        }

        val report = sync().run(ruChain)

        assertTrue(report.outcomes.none { it is SyncOutcome.Failed })
        assertEquals("One", title("wd:Q1", listOf("en")))
        assertEquals("\"en-1\"", state("labels-en.jsonl.gz")!!.etag)
        val en = release.asked().filter { it.endsWith("labels-en.jsonl.gz") }
        assertEquals(listOf("labels-en.jsonl.gz", "assets/labels-en.jsonl.gz").let { it + it }, en)
    }

    @Test
    fun tooManyRequestsUntilTheRetriesRunOutIsAFailure() = runTest {
        release.overrides["labels-en.jsonl.gz"] = { MockResponse().setResponseCode(429).setHeader("Retry-After", "0") }

        val report = sync().run(ruChain)

        val failed = report.outcomes.single { it is SyncOutcome.Failed } as SyncOutcome.Failed
        assertEquals(429, (failed.error as HttpStatusException).code)
        assertEquals(3, release.asked().count { it == "labels-en.jsonl.gz" })
        assertNull(state("labels-en.jsonl.gz"))
    }

    @Test
    fun leftoverDownloadsOfAKilledRunAreDeleted() = runTest {
        temp.mkdirs()
        val leftover = File(temp, "release-123.part").apply { writeText("half") }
        sync().run(ruChain)
        assertTrue(!leftover.exists())
    }
}
