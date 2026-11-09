package io.github.hsw.inkeasel.catalog

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogDaoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: CatalogDb
    private lateinit var dao: CatalogDao

    private val ru = CatalogRow.chainKey(LanguageChain.of("ru-RU"))

    @Before
    fun open() {
        db = CatalogDb.configure(Room.inMemoryDatabaseBuilder(context, CatalogDb::class.java)).build()
        dao = db.catalog()
    }

    @After
    fun close() = db.close()

    /** A Wikidata row at line [line] of `core`, inserted with a temporary rank until [CatalogDao.rerank]. */
    private fun wd(line: Int, fame: Int = 3, file: String = "Painting $line.jpg", ref: String? = null) =
        PictureEntity(
            id = "wd:Q${line + 1}", source = CatalogRank.WIKIDATA, rank = -1L - line, wikidataId = "Q${line + 1}",
            commonsFile = file, museumRef = ref, fame = fame, sourceRank = line, width = 800, height = 600,
        )

    private fun live(source: String, n: Int, file: String? = null, ref: String? = null) = PictureEntity(
        id = "$source:$n", source = source, rank = -100_000L - n - (if (source == "cma") 0 else 50_000),
        commonsFile = file, museumRef = ref, sourceRank = n, title = "$source title $n", artist = "$source artist $n",
        collection = "$source museum", thumbUrl = "https://example.org/$n.jpg",
    )

    private fun pager(chain: String = ru) =
        Pager(PagingConfig(pageSize = 20, prefetchDistance = 5, initialLoadSize = 40)) { dao.pages(chain) }.flow

    private suspend fun everything(chain: String = ru): List<CatalogRow> = pager(chain).asSnapshot {
        appendScrollWhile { true }
    }

    @Test
    fun pagesKeepOneStableOrderAcrossFameTiesWithNoDuplicatesOrGaps() = runTest {
        // 250 Wikidata rows in fame order, 20 at each fame value (the export orders ties by numeric id),
        // inserted shuffled; 15 Cleveland and 15 Commons rows, 5 of them duplicates of Wikidata rows
        val wds = (0 until 250).map { wd(it, fame = 100 - it / 20, ref = "ref$it") }
        val lives = (0 until 15).map { live("cma", it, ref = if (it < 3) "ref${it * 10}" else "cma$it") } +
            (0 until 15).map { live("commons", it, file = if (it < 2) "Painting_${it + 40}.jpg" else "C $it.jpg") }
        dao.insertPictures((wds + lives).shuffled(java.util.Random(7)))
        dao.rerank()

        val rows = everything()
        assertEquals(dao.count(), rows.size)
        assertEquals(275, rows.size) // 280 minus 5 hidden
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        // the Wikidata rows keep the line order through the ties
        assertEquals(wds.map { it.id }, rows.filter { it.source == CatalogRank.WIKIDATA }.map { it.id })
        // every 10th card is a live one, Cleveland and Commons taking turns
        val liveIds = rows.withIndex().filter { it.value.source != CatalogRank.WIKIDATA }
        assertEquals((0 until 25).map { 9 + 10 * it }, liveIds.map { it.index })
        assertEquals(listOf("cma:3", "commons:2", "cma:4", "commons:3"), liveIds.take(4).map { it.value.id })
        assertTrue(rows.zipWithNext().all { (a, b) -> a.rank < b.rank })
        // the order is the same on a second read
        assertEquals(rows.map { it.id }, everything().map { it.id })
    }

    @Test
    fun hiddenRowsAreLeftOutButStillStored() = runTest {
        dao.insertPictures(
            (0 until 3).map { wd(it) } + live("commons", 0, file = "Painting_1.jpg") + live("cma", 0),
        )
        dao.rerank()
        assertEquals(listOf("wd:Q1", "wd:Q2", "wd:Q3", "cma:0"), everything().map { it.id })
        assertEquals(4, dao.count())
        assertEquals(true, dao.picture("commons:0")?.hidden)
        assertNull(dao.row("commons:0", ru))
        assertEquals("cma:0", dao.row("cma:0", ru)?.id)
    }

    @Test
    fun labelsFollowTheChainThenAnyLanguage() = runTest {
        dao.insertPictures((0 until 5).map { wd(it) })
        dao.rerank()
        dao.insertLabels(
            listOf(
                LabelEntity("Q1", "de", "Mona Lisa (de)"),
                LabelEntity("Q1", "mul", "Mona Lisa (mul)"),
                LabelEntity("Q1", "en", "Mona Lisa"),
                LabelEntity("Q1", "ru", "Мона Лиза"),
                LabelEntity("Q2", "mul", "Two (mul)"),
                LabelEntity("Q2", "en", "Two"),
                LabelEntity("Q3", "mul", "Three (mul)"),
                LabelEntity("Q4", "fr", "Quatre"),
                LabelEntity("Q4", "de", "Vier"),
                LabelEntity("Q5", "ru", null, aliases = listOf("Пять")), // no label in ru: the next language
                LabelEntity("Q5", "en", "Five"),
            ),
        )
        val titles = everything().associate { it.id to it.title }
        assertEquals("Мона Лиза", titles["wd:Q1"])
        assertEquals("Two", titles["wd:Q2"])
        assertEquals("Three (mul)", titles["wd:Q3"])
        assertEquals("Vier", titles["wd:Q4"]) // outside the chain: the lowest code
        assertEquals("Five", titles["wd:Q5"])

        val en = CatalogRow.chainKey(LanguageChain.of("en"))
        assertEquals("Mona Lisa", dao.row("wd:Q1", en)?.title)
        val de = CatalogRow.chainKey(LanguageChain.of("de-AT"))
        assertEquals("Mona Lisa (de)", dao.row("wd:Q1", de)?.title)
    }

    @Test
    fun artistsInReleaseOrderAndTheFirstCollection() = runTest {
        dao.insertPictures(listOf(wd(0)))
        dao.rerank()
        dao.insertLinks(
            listOf(
                PictureEntityLink("wd:Q1", EntityKind.CREATOR, 2, "Q12"),
                PictureEntityLink("wd:Q1", EntityKind.CREATOR, 0, "Q10"),
                PictureEntityLink("wd:Q1", EntityKind.CREATOR, 1, "Q11"),
                PictureEntityLink("wd:Q1", EntityKind.CREATOR, 3, "Q13"),
                PictureEntityLink("wd:Q1", EntityKind.COLLECTION, 1, "Q21"),
                PictureEntityLink("wd:Q1", EntityKind.COLLECTION, 0, "Q20"),
                PictureEntityLink("wd:Q1", EntityKind.GENRE, 0, "Q30"),
            ),
        )
        dao.insertLabels(
            listOf(
                LabelEntity("Q10", "ru", "Леонардо"),
                LabelEntity("Q10", "en", "Leonardo"),
                LabelEntity("Q11", "en", "Salaì"),
                // Q12 has no label: left out
                LabelEntity("Q13", "it", "Bottega"),
                LabelEntity("Q20", "en", "Louvre"),
                LabelEntity("Q20", "ru", "Лувр"),
                LabelEntity("Q21", "en", "Second museum"),
                LabelEntity("Q30", "en", "portrait"),
            ),
        )
        val row = dao.row("wd:Q1", ru)!!
        assertEquals(listOf("Леонардо", "Salaì", "Bottega"), row.artists)
        assertEquals("Лувр", row.collection)
    }

    @Test
    fun liveRowsCarryTheirOwnTexts() = runTest {
        dao.insertPictures(listOf(live("cma", 4).copy(date = "c. 1665", pageUrl = "https://example.org/4")))
        dao.rerank()
        val row = dao.row("cma:4", ru)!!
        assertEquals("cma title 4", row.title)
        assertEquals(listOf("cma artist 4"), row.artists)
        assertEquals("cma museum", row.collection)
        assertEquals("c. 1665", row.date)
        assertEquals("https://example.org/4", row.pageUrl)
        assertEquals("https://example.org/4.jpg", row.thumbUrl)
    }

    @Test
    fun aPictureWithNoLabelShowsItsFileName() = runTest {
        dao.insertPictures(listOf(wd(0, file = "Mona_Lisa, by Leonardo da Vinci.tif")))
        dao.rerank()
        val row = everything().single()
        assertNull(row.title)
        assertEquals(emptyList<String>(), row.artists)
        assertNull(row.collection)
        assertEquals("Mona Lisa, by Leonardo da Vinci", row.shownTitle)

        assertEquals("No extension", CatalogRow.fileTitle("No_extension"))
        assertEquals(".hidden", CatalogRow.fileTitle(".hidden"))
        assertEquals("v1.2 sketch", CatalogRow.fileTitle("v1.2 sketch.jpeg"))
    }

    @Test
    fun neighboursAndPositionSkipHiddenRows() = runTest {
        dao.insertPictures(
            (0 until 12).map { wd(it) } + live("commons", 0, file = "Painting 3.jpg") + live("cma", 0),
        )
        dao.rerank()
        val rows = everything()
        assertEquals(13, rows.size)
        rows.forEachIndexed { i, row ->
            assertEquals(i, dao.position(row.rank))
            assertEquals(rows.getOrNull(i + 1)?.id, dao.next(row.rank, ru)?.id)
            assertEquals(rows.getOrNull(i - 1)?.id, dao.previous(row.rank, ru)?.id)
        }
        // from a hidden row's rank (past the end) there is nothing after, and the last shown row before
        val hidden = dao.picture("commons:0")!!
        assertTrue(hidden.hidden)
        assertNull(dao.next(hidden.rank, ru))
        assertEquals(rows.last().id, dao.previous(hidden.rank, ru)?.id)
    }

    @Test
    fun rerankMovesOnlyWhatChangesAndKeepsRanksUnique() = runTest {
        dao.insertPictures((0 until 20).map { wd(it) } + live("cma", 0))
        dao.rerank()
        assertEquals(85L, dao.picture("cma:0")?.rank)
        // new rows arrive with temporary ranks; a Commons row takes the next slot, a duplicate hides
        dao.insertPictures(listOf(live("commons", 0), live("commons", 1, file = "Painting 0.jpg")))
        dao.rerank()
        assertEquals(85L, dao.picture("cma:0")?.rank)
        assertEquals(175L, dao.picture("commons:0")?.rank)
        assertEquals(true, dao.picture("commons:1")?.hidden)
        assertEquals(10L, dao.picture("wd:Q2")?.rank)
        // nothing to change: no row is written (SQLite counts every row a statement changes)
        val changes = { db.openHelper.writableDatabase.query("SELECT total_changes()").use { it.moveToFirst(); it.getLong(0) } }
        val before = changes()
        dao.rerank()
        assertEquals(before, changes())
        assertEquals(22, everything().size)
    }

    @Test
    fun anEmptyDatabase() = runTest {
        dao.rerank()
        assertEquals(0, dao.count())
        assertEquals(emptyList<CatalogRow>(), everything())
        assertNull(dao.next(-1, ru))
        assertNull(dao.previous(Long.MAX_VALUE, ru))
        assertNull(dao.row("wd:Q1", ru))
        assertEquals(0, dao.position(1000))
    }

    @Test
    fun aliasesRoundTripThroughTheConverter() {
        val c = CatalogConverters()
        assertEquals(listOf("Джоконда", "La Gioconda"), c.toTexts(c.fromTexts(listOf("Джоконда", "", "La Gioconda"))))
        assertEquals(listOf("ab"), c.toTexts(c.fromTexts(listOf("a\u001Fb"))))
        assertEquals(emptyList<String>(), c.toTexts(c.fromTexts(emptyList())))
        assertEquals(emptyList<String>(), c.toTexts(null))
    }

    @Test
    fun anotherSchemaVersionIsDroppedAndCreatedAgain() = runTest {
        val name = "catalog-migration-test.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        // a database from a newer app version (a downgrade), with a table of the same name in another shape
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE picture (id TEXT PRIMARY KEY, something TEXT)")
            old.execSQL("INSERT INTO picture VALUES ('wd:Q1', 'x')")
            old.version = 2
        }
        val reopened = CatalogDb.open(context, name)
        try {
            val d = reopened.catalog()
            assertEquals(0, d.count())
            d.insertPictures(listOf(wd(0)))
            d.rerank()
            assertEquals(0L, d.picture("wd:Q1")?.rank)
            assertEquals(1, reopened.openHelper.readableDatabase.version)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
