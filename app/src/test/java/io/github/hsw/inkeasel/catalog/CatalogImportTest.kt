package io.github.hsw.inkeasel.catalog

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
class CatalogImportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // Room refreshes its invalidation tracker on the query executor: one thread of our own, so a test can
    // wait for the refresh (and the observers it calls) to finish
    private lateinit var queries: ExecutorService
    private lateinit var transactions: ExecutorService
    private lateinit var db: CatalogDb
    private lateinit var dao: CatalogDao

    private val ruChain = LanguageChain.of("ru-RU")
    private val ru = CatalogRow.chainKey(ruChain)

    /** How many times the tables the paging query reads were reported changed. */
    private val invalidations = AtomicInteger()

    @Before
    fun open() {
        queries = Executors.newSingleThreadExecutor()
        transactions = Executors.newSingleThreadExecutor()
        db = CatalogDb.configure(
            Room.inMemoryDatabaseBuilder(context, CatalogDb::class.java)
                .setQueryExecutor(queries)
                .setTransactionExecutor(transactions),
        ).build()
        dao = db.catalog()
        db.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(arrayOf("picture", "label", "picture_entity")) {
                override fun onInvalidated(tables: Set<String>) {
                    invalidations.incrementAndGet()
                }
            },
        )
    }

    @After
    fun close() {
        db.close()
        queries.shutdown()
        transactions.shutdown()
    }

    /** Waits until Room has reported every change so far to its observers. */
    private fun settled() {
        queries.submit {}.get()
    }

    // --- files ---

    private fun core(
        q: Int,
        fame: Int = 3,
        file: String = "Painting $q.jpg",
        width: Int = 800,
        creators: List<Int> = emptyList(),
        collections: List<Int> = emptyList(),
        cma: String? = null,
    ): String = JSONObject()
        .put("id", "Q$q").put("fame", fame).put("img", file).put("w", width).put("h", 600)
        .put("lic", "Public domain").put("attr", false)
        .put("creators", JSONArray(creators.map { "Q$it" }))
        .put("collections", JSONArray(collections.map { "Q$it" }))
        .put("movements", JSONArray()).put("genres", JSONArray())
        .apply { if (cma != null) put("cma", cma) }
        .toString()

    private fun label(q: Int, text: String): String = JSONObject()
        .put("id", "Q$q").put("l", text)
        .toString()

    private fun gzip(lines: List<String>): ByteArray = gzip(lines.joinToString("\n", postfix = "\n").toByteArray())

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun file(name: String, bytes: ByteArray, lang: String? = null) =
        ImportFile(name, lang) { ByteArrayInputStream(bytes) }

    private fun coreFile(lines: List<String>) = file("core.jsonl.gz", gzip(lines))

    private fun labelsFile(lang: String, lines: List<String>) = file("labels-$lang.jsonl.gz", gzip(lines), lang)

    private fun fixture(name: String, lang: String? = null) =
        ImportFile(name, lang) { javaClass.getResourceAsStream("/catalog/$name")!! }

    // --- the database as rows of text ---

    private fun query(sql: String): List<String> = db.openHelper.readableDatabase.query(sql).use { c ->
        buildList {
            while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" })
        }
    }

    /** Everything the list can show: the live tables, sorted. */
    private fun visible(): List<String> =
        query("SELECT * FROM picture ORDER BY id").map { "picture $it" } +
            query("SELECT * FROM label ORDER BY entityId, lang").map { "label $it" } +
            query("SELECT * FROM picture_entity ORDER BY pictureId, kind, pos").map { "link $it" }

    private fun staged(): List<String> =
        query("SELECT id FROM picture_stage") + query("SELECT entityId FROM label_stage") +
            query("SELECT pictureId FROM picture_entity_stage")

    private fun langs(): List<String> = query("SELECT DISTINCT lang FROM label ORDER BY lang")

    private fun links(id: String): List<String> =
        query("SELECT kind, entityId FROM picture_entity WHERE pictureId = '$id' ORDER BY kind, pos")

    /** The shown pictures in list order, walked by the neighbour query. */
    private suspend fun ids(): List<String> {
        val out = ArrayList<String>()
        var row = dao.next(Long.MIN_VALUE, ru)
        while (row != null) {
            out += row.id
            row = dao.next(row.rank, ru)
        }
        return out
    }

    private suspend fun assertFails(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        fail("no exception")
        throw AssertionError()
    }

    /** A starter set of Q1…Q3 with Russian, English and German labels; Q100 is Q2's artist. */
    private suspend fun starter(): ImportResult = CatalogImport(db).starter(
        coreFile(listOf(core(1, fame = 50), core(2, fame = 40, creators = listOf(100)), core(3, fame = 30))),
        listOf(
            labelsFile("ru", listOf(label(1, "Один"), label(2, "Два"), label(100, "Художник"))),
            labelsFile("en", listOf(label(1, "One"), label(2, "Two"), label(3, "Three"))),
            labelsFile("de", listOf(label(1, "Eins"))),
            labelsFile("mul", listOf(label(100, "Painter"))),
        ),
        ruChain,
    )!!

    // --- the starter set ---

    @Test
    fun theStarterFillsAnEmptyDatabaseOnceWithTheChainLanguages() = runTest {
        val result = starter()
        assertEquals(3, result.added)
        assertEquals(7, result.labels) // the German label is left out
        assertEquals(listOf("en", "mul", "ru"), langs())
        assertEquals(listOf("wd:Q1", "wd:Q2", "wd:Q3"), ids())
        assertEquals(listOf(0L, 10L, 20L), ids().map { dao.picture(it)!!.rank })
        val two = dao.row("wd:Q2", ru)!!
        assertEquals("Два", two.title)
        assertEquals(listOf("Художник"), two.artists)
        assertEquals("Q2", two.wikidataId)
        assertEquals(1, dao.picture("wd:Q2")!!.sourceRank)
        assertEquals("Three", dao.row("wd:Q3", ru)!!.title)

        // a second run reads nothing and changes nothing
        val before = visible()
        val untouched = ImportFile("core.jsonl.gz") { throw AssertionError("read again") }
        assertNull(CatalogImport(db).starter(untouched, listOf(untouched), ruChain))
        assertEquals(before, visible())
    }

    @Test
    fun theStarterReadsTheRealReleaseRows() = runTest {
        val labels = listOf(fixture("labels-ru.jsonl", "ru"), fixture("labels-en.jsonl", "en"))
        val result = CatalogImport(db).starter(fixture("core.jsonl"), labels, ruChain)!!
        assertEquals(5, result.added)
        assertEquals(listOf("core.jsonl", "labels-ru.jsonl", "labels-en.jsonl"), result.files.map { it.name })
        val mona = dao.row("wd:Q12418", ru)!!
        assertEquals("Мона Лиза", mona.title)
        assertEquals("Mona Lisa, by Leonardo da Vinci, from C2RMF retouched.jpg", mona.commonsFile)
        assertEquals(1503, mona.inception)
        assertEquals(listOf("creator|Q762", "collection|Q3044768", "movement|Q1404472", "genre|Q134307").sorted(),
            links("wd:Q12418").sorted())
    }

    @Test
    fun theStarterReadsOnlyTheLabelsFilesOfItsLanguages() = runTest {
        val notOpened = ImportFile("labels-de.jsonl", "de") { throw AssertionError("a German file opened") }
        val result = CatalogImport(db).starter(
            coreFile(listOf(core(1), core(2))),
            listOf(
                labelsFile("ru", listOf(label(1, "Один"), "not json", label(2, "Два"))),
                notOpened,
                labelsFile("mul", listOf(label(2, "Two (mul)"))),
            ),
            ruChain,
        )!!
        assertEquals(listOf("core.jsonl.gz", "labels-ru.jsonl.gz", "labels-mul.jsonl.gz"), result.files.map { it.name })
        assertEquals(1, result.files[1].badLines)
        assertEquals(3, result.labels)
        assertEquals(
            listOf("Q1|ru|Один", "Q2|mul|Two (mul)", "Q2|ru|Два"),
            query("SELECT entityId, lang, label FROM label ORDER BY entityId, lang"),
        )
    }

    @Test
    fun aStarterBesideStoredLiveRowsIsPlacedByTheRankRules() = runTest {
        // live rows already stored (not the usual order, but the import must not depend on it)
        dao.insertPictures(
            listOf(
                PictureEntity("cma:7", "cma", 5, sourceRank = 0),
                PictureEntity("commons:Painting_2.jpg", "commons", 0, commonsFile = "Painting_2.jpg", sourceRank = 0),
            ),
        )
        CatalogImport(db).starter(coreFile((1..12).map { core(it) }), emptyList(), ruChain)!!
        val placed = query("SELECT id, rank, hidden FROM picture ORDER BY rank")
        assertEquals((0 until 9).map { "wd:Q${it + 1}|${it * 10}|0" }, placed.take(9))
        assertEquals("cma:7|85|0", placed[9]) // the 10th card
        assertEquals("commons:Painting_2.jpg|125|1", placed.last()) // a duplicate of wd:Q2, hidden
    }

    @Test
    fun aStarterWithNoValidRowWritesNothing() = runTest {
        val e = assertFails {
            CatalogImport(db).starter(coreFile(listOf("{}", "not json")), listOf(labelsFile("ru", emptyList())), ruChain)
        }
        assertTrue(e is IOException)
        assertEquals(emptyList<String>(), visible())
    }

    // --- the release swap ---

    @Test
    fun aReleaseReplacesTheStarterRowsKeepingTheirPlaces() = runTest {
        starter()
        val result = CatalogImport(db).release(
            coreFile((1..6).map { core(it, fame = 60 - it, width = if (it == 2) 1234 else 800) }),
            listOf(
                labelsFile("ru", (1..6).map { label(it, "Картина $it") }),
                labelsFile("en", (1..6).map { label(it, "Picture $it") }),
            ),
            ruChain,
        )
        assertEquals(3, result.added)
        assertEquals(3, result.replaced)
        assertEquals(0, result.removed)
        assertEquals(12, result.labels)
        assertEquals(listOf("wd:Q1", "wd:Q2", "wd:Q3", "wd:Q4", "wd:Q5", "wd:Q6"), ids())
        assertEquals((0L..50L step 10).toList(), ids().map { dao.picture(it)!!.rank })
        assertEquals(1234, dao.picture("wd:Q2")!!.width)
        assertEquals(58, dao.picture("wd:Q2")!!.fame)
        assertEquals("Картина 6", dao.row("wd:Q6", ru)!!.title)
        // a new core replaces the links: Q2 no longer names Q100, and the starter's mul label stays (mul was
        // not imported again) while ru and en are the new files'
        assertEquals(emptyList<String>(), links("wd:Q2"))
        assertEquals(listOf("Q100|mul|Painter"), query("SELECT entityId, lang, label FROM label WHERE entityId = 'Q100'"))
        assertEquals(listOf("core.jsonl.gz", "labels-ru.jsonl.gz", "labels-en.jsonl.gz"), result.files.map { it.name })
        assertEquals(emptyList<String>(), staged())
    }

    @Test
    fun aNewerCoreSweepsAbsentRowsStaleLinksAndLabels() = runTest {
        val import = CatalogImport(db)
        import.release(
            coreFile((1..4).map { core(it, creators = listOf(100 + it), collections = listOf(200)) }),
            listOf(labelsFile("ru", (1..4).map { label(it, "Картина $it") } + (101..104).map { label(it, "Автор $it") })),
            ruChain,
        )
        dao.insertLabels(listOf(LabelEntity("Q1", "de", "Bild"), LabelEntity("Q101", "fr", "Peintre")))

        val result = import.release(
            coreFile(listOf(core(1, creators = listOf(101)), core(2, creators = listOf(104, 101)), core(4))),
            listOf(labelsFile("ru", listOf(label(1, "Первая"), label(2, "Вторая"), label(4, "Четвёртая"), label(101, "А")))),
            ruChain,
        )
        assertEquals(0, result.added)
        assertEquals(3, result.replaced)
        assertEquals(1, result.removed)
        assertEquals(2, result.labelsDropped)
        assertEquals(listOf("wd:Q1", "wd:Q2", "wd:Q4"), ids())
        assertNull(dao.picture("wd:Q3"))
        assertEquals(emptyList<String>(), links("wd:Q3"))
        assertEquals(listOf("creator|Q104", "creator|Q101"), links("wd:Q2"))
        assertEquals(emptyList<String>(), links("wd:Q4")) // the collection link is gone with the old core
        assertEquals(listOf("ru"), langs())
        assertEquals(listOf("Q1", "Q101", "Q2", "Q4"), query("SELECT entityId FROM label ORDER BY entityId"))
        assertEquals(listOf("А"), dao.row("wd:Q2", ru)!!.artists) // Q104 lost its label with the old ru file
        assertEquals(10L, dao.picture("wd:Q2")!!.rank)
        assertEquals(20L, dao.picture("wd:Q4")!!.rank)
    }

    @Test
    fun labelsAloneKeepThePicturesAndTheOtherLanguages() = runTest {
        val import = CatalogImport(db)
        import.release(
            coreFile((1..3).map { core(it) }),
            listOf(
                labelsFile("ru", (1..3).map { label(it, "Картина $it") }),
                labelsFile("en", (1..3).map { label(it, "Picture $it") }),
            ),
            ruChain,
        )
        val pictures = query("SELECT * FROM picture ORDER BY id")
        val result = import.release(null, listOf(labelsFile("ru", listOf(label(2, "Новая")))), ruChain)
        assertEquals(0, result.added + result.replaced + result.removed)
        assertEquals(1, result.labels)
        assertEquals(pictures, query("SELECT * FROM picture ORDER BY id"))
        assertEquals(listOf("Picture 1", "Новая", "Picture 3"), ids().map { dao.row(it, ru)!!.title })
    }

    @Test
    fun aLabelsFileWithNoValidRowChangesNothing() = runTest {
        starter()
        val before = visible()
        val labels = query("SELECT entityId, lang, label FROM label ORDER BY entityId, lang")
        // a broken upload or a 200 page that is not the file: every line is bad, so it is not the language's labels
        val garbage = labelsFile("ru", listOf("<html>", "not json"))
        val thrown = assertFails { CatalogImport(db).release(coreFile(listOf(core(9))), listOf(garbage), ruChain) }
        assertTrue(thrown.toString(), thrown is IOException)
        assertEquals(before, visible())
        assertEquals(labels, query("SELECT entityId, lang, label FROM label ORDER BY entityId, lang"))
        assertEquals(emptyList<String>(), staged())

        // an empty file is a language with no label: it empties that language
        CatalogImport(db).release(null, listOf(labelsFile("ru", emptyList())), ruChain)
        assertEquals(emptyList<String>(), query("SELECT entityId FROM label WHERE lang = 'ru'"))
    }

    @Test
    fun aLabelsFileOutsideTheChainIsNotRead() = runTest {
        starter()
        val before = visible()
        val unread = ImportFile("labels-de.jsonl.gz", "de") { throw AssertionError("read") }
        val result = CatalogImport(db).release(null, listOf(unread), ruChain)
        assertEquals(emptyList<FileRead>(), result.files)
        assertEquals(before, visible())
    }

    @Test
    fun liveRowsAreDeduplicatedAgainstTheNewCore() = runTest {
        dao.insertPictures(
            listOf(
                PictureEntity("commons:Painting_2.jpg", "commons", -1, commonsFile = "Painting_2.jpg", sourceRank = 0),
                PictureEntity("cma:7", "cma", -2, museumRef = "1915.534", sourceRank = 0),
                PictureEntity("cma:8", "cma", -3, museumRef = "1920.1", sourceRank = 1),
            ),
        )
        dao.rerank()
        assertEquals(3, dao.count())
        CatalogImport(db).release(
            coreFile((1..10).map { core(it, cma = if (it == 5) "1915.534" else null) }),
            emptyList(),
            ruChain,
        )
        assertTrue(dao.picture("commons:Painting_2.jpg")!!.hidden)
        assertTrue(dao.picture("cma:7")!!.hidden)
        assertFalse(dao.picture("cma:8")!!.hidden)
        assertEquals(85L, dao.picture("cma:8")!!.rank)
        assertEquals(11, dao.count())
    }

    @Test
    fun aSwapInvalidatesThePagingSourceOnce() = runTest {
        starter()
        settled()
        val source = dao.pages(ru)
        source.load(PagingSource.LoadParams.Refresh(null, 10, false))
        settled()
        invalidations.set(0)

        // small batches: several staging transactions, none of which may reach the list
        CatalogImport(db, batch = 2).release(
            coreFile((1..9).map { core(it) }),
            listOf(labelsFile("ru", (1..9).map { label(it, "Картина $it") }), labelsFile("en", listOf(label(1, "One")))),
            ruChain,
        )
        settled()
        assertEquals(1, invalidations.get())
        assertTrue(source.invalid)
    }

    @Test
    fun repeatedIdsAndBadLinesAreSkippedAcrossBatches() = runTest {
        val bytes = gzip(listOf(core(1, file = "First.jpg"), "{\"id\":\"Q2\"}", core(2), core(1, file = "Again.jpg")))
        // one batch, then a batch per row: the first row of an id wins across batches too
        for (batch in listOf(CatalogImport.BATCH, 1)) {
            val result = CatalogImport(db, batch).release(file("core.jsonl.gz", bytes), emptyList(), ruChain)
            assertEquals("batch $batch", listOf("wd:Q1", "wd:Q2"), ids())
            assertEquals("batch $batch", "First.jpg", dao.picture("wd:Q1")!!.commonsFile)
            assertEquals("batch $batch", 1, dao.picture("wd:Q2")!!.sourceRank)
            assertEquals(FileRead("core.jsonl.gz", rows = 3, badLines = 1), result.files.single())
        }
    }

    // --- the commit hook and labels added from the starter ---

    @Test
    fun theCommitRunsInsideTheSwapSoItsFailureUndoesTheSwap() = runTest {
        starter()
        val before = visible()
        val e = assertFails {
            CatalogImport(db).release(coreFile(listOf(core(7))), emptyList(), ruChain) { result ->
                assertEquals(1, result.added)
                db.imports().saveFiles(listOf(CatalogFileEntity("core.jsonl.gz", sha256 = "abc")))
                throw IOException("commit failed")
            }
        }
        assertEquals("commit failed", e.message)
        assertEquals(before, visible())
        assertEquals(emptyList<String>(), query("SELECT * FROM catalog_file"))
        assertEquals(emptyList<String>(), staged())

        CatalogImport(db).release(coreFile(listOf(core(7))), emptyList(), ruChain) {
            db.imports().saveFiles(listOf(CatalogFileEntity("core.jsonl.gz", sha256 = "abc")))
        }
        assertEquals(listOf("wd:Q7"), ids())
        assertEquals(listOf("core.jsonl.gz"), query("SELECT name FROM catalog_file"))
    }

    @Test
    fun addedLabelsReplaceOnlyTheirLanguageAndDropLanguagesOutsideTheChain() = runTest {
        starter()
        val deChain = LanguageChain.of("de")
        val starterLabels = listOf(
            labelsFile("de", listOf(label(1, "Eins"), label(2, "Zwei"))),
            labelsFile("en", listOf(label(1, "One again"))),
            labelsFile("fr", listOf(label(3, "Trois"))),
        )
        var committed = 0
        val result = CatalogImport(db).addLabels(starterLabels, listOf("de", "fr"), deChain) { committed++ }!!

        assertEquals(1, committed)
        assertEquals(2, result.labels) // French is not in the chain, English was not asked for
        assertEquals(listOf("de", "en", "mul"), langs()) // Russian left the chain
        assertEquals("Eins", dao.row("wd:Q1", CatalogRow.chainKey(deChain))!!.title)
        assertEquals("One", query("SELECT label FROM label WHERE entityId = 'Q1' AND lang = 'en'").single())
        assertEquals(emptyList<String>(), staged())
    }

    @Test
    fun noAddedLabelMeansNoSwap() = runTest {
        starter()
        val before = visible()
        settled()
        invalidations.set(0)
        val none = listOf(labelsFile("de", emptyList()))

        assertNull(CatalogImport(db).addLabels(none, listOf("de"), LanguageChain.of("de")))
        assertNull(CatalogImport(db).addLabels(none, listOf("it"), ruChain)) // not in the chain: nothing read
        settled()
        assertEquals(0, invalidations.get())
        assertEquals(before, visible())
        assertEquals(emptyList<String>(), staged())
    }

    // --- failures change nothing visible ---

    @Test
    fun aFailedLabelsFileAfterTheCoreChangesNothing() = runTest {
        starter()
        settled()
        val before = visible()
        invalidations.set(0)
        val e = assertFails {
            CatalogImport(db, batch = 2).release(
                coreFile((1..9).map { core(it, fame = 1) }),
                listOf(
                    labelsFile("ru", (1..9).map { label(it, "Картина $it") }),
                    ImportFile("labels-en.jsonl.gz", "en") { throw IOException("connection reset") },
                ),
                ruChain,
            )
        }
        assertEquals("connection reset", e.message)
        settled()
        assertEquals(before, visible())
        assertEquals(0, invalidations.get())
        assertEquals(emptyList<String>(), staged())
    }

    @Test
    fun aTruncatedGzipChangesNothing() = runTest {
        starter()
        val before = visible()
        val whole = gzip((1..50).map { core(it) })
        val e = assertFails {
            CatalogImport(db).release(file("core.jsonl.gz", whole.copyOf(whole.size - 20)), emptyList(), ruChain)
        }
        assertTrue(e is IOException)
        assertEquals(before, visible())
    }

    @Test
    fun aStreamThatBreaksMidStagingChangesNothing() = runTest {
        starter()
        val before = visible()
        val bytes = (1..200).joinToString("\n") { core(it) }.toByteArray()
        val breaking = ImportFile("core.jsonl.gz") {
            object : FilterInputStream(ByteArrayInputStream(bytes)) {
                var read = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (read > bytes.size / 2) throw IOException("broken")
                    return super.read(b, off, minOf(len, 512)).also { if (it > 0) read += it }
                }
            }
        }
        val e = assertFails { CatalogImport(db, batch = 5).release(breaking, emptyList(), ruChain) }
        assertEquals("broken", e.message)
        assertEquals(before, visible())
        assertEquals(emptyList<String>(), staged())
    }

    @Test
    fun aCoreWithNoValidRowKeepsTheStoredRows() = runTest {
        starter()
        val before = visible()
        val e = assertFails { CatalogImport(db).release(coreFile(listOf("garbage", "{}")), emptyList(), ruChain) }
        assertTrue(e is IOException)
        assertEquals(before, visible())
    }

    @Test
    fun stagingLeftByAKilledImportIsClearedOnTheNextRun() = runTest {
        // rows a killed process left in the staging tables
        db.imports().stageCore(
            listOf(PictureStage("wd:Q99", "Q99", "Left.jpg", null, 1, 0, 10, 10, null, null, false, null, null)),
            listOf(PictureEntityStage("wd:Q99", EntityKind.CREATOR, 0, "Q98")),
        )
        db.imports().stageLabels(listOf(LabelStage("Q99", "ru", "Осталась")))

        CatalogImport(db).release(coreFile(listOf(core(1), core(2))), emptyList(), ruChain)
        assertEquals(listOf("wd:Q1", "wd:Q2"), ids())
        assertNull(dao.picture("wd:Q99"))
        assertEquals(emptyList<String>(), query("SELECT * FROM label"))
        assertEquals(emptyList<String>(), query("SELECT * FROM picture_entity"))
        assertEquals(emptyList<String>(), staged())
    }

    @Test
    fun theStoredLanguagesAreTheChainThenEnglishAndMul() {
        assertEquals(listOf("ru", "en", "mul"), CatalogImport.languages(listOf("ru", "en", "mul")))
        assertEquals(listOf("de", "en", "mul"), CatalogImport.languages(listOf("de")))
        assertEquals(listOf("en", "mul"), CatalogImport.languages(emptyList()))
    }

}
