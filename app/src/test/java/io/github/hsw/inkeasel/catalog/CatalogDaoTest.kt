package io.github.hsw.inkeasel.catalog

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.testing.asSnapshot
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
    private val all = NudityFilter.SHOW_ALL.nudityBelow

    @Before
    fun open() {
        db = inMemoryCatalogDb(context)
        dao = db.catalog()
    }

    @After
    fun close() = db.close()

    /** A Wikidata row at line [line] of `core`, inserted with a temporary rank until [CatalogDao.rerank]. */
    private fun wd(
        line: Int,
        fame: Int = 3,
        file: String = "Painting $line.jpg",
        ref: String? = null,
        nudity: NudityLevel = NudityLevel.NONE,
    ) = PictureEntity(
        id = "wd:Q${line + 1}", source = CatalogRank.WIKIDATA, rank = -1L - line, nudity = nudity.ordinal,
        wikidataId = "Q${line + 1}", commonsFile = file, museumRef = ref, fame = fame, sourceRank = line, width = 800,
        height = 600,
    )

    private fun live(source: String, n: Int, file: String? = null, ref: String? = null) = PictureEntity(
        id = "$source:$n", source = source, rank = -100_000L - n - (if (source == "cma") 0 else 50_000),
        commonsFile = file, museumRef = ref, sourceRank = n, title = "$source title $n", artists = listOf("$source artist $n"),
        collection = "$source museum", thumbUrl = "https://example.org/$n.jpg",
    )

    private fun pager(chain: String = ru, nudityBelow: Int = all) =
        Pager(PagingConfig(pageSize = 20, prefetchDistance = 5, initialLoadSize = 40)) { dao.pages(chain, nudityBelow) }.flow

    private suspend fun everything(chain: String = ru, nudityBelow: Int = all): List<CatalogRow> =
        pager(chain, nudityBelow).asSnapshot { appendScrollWhile { true } }

    /**
     * Q1…Q12, of which Q3 is nude, Q5 explicit, Q8 nude; a Cleveland row (the 10th card, after Q9) and a
     * Commons row that is Q5 (hidden as its duplicate, whatever the filter).
     */
    private suspend fun fillWithLevels() {
        val levels = mapOf(2 to NudityLevel.NUDE, 4 to NudityLevel.EXPLICIT, 7 to NudityLevel.NUDE)
        dao.insertPictures(
            (0 until 12).map { wd(it, nudity = levels[it] ?: NudityLevel.NONE) } +
                live("cma", 0) + live("commons", 0, file = "Painting_4.jpg"),
        )
        dao.rerank()
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
        assertEquals(dao.count(all), rows.size)
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
        assertEquals(4, dao.count(all))
        assertEquals(true, dao.picture("commons:0")?.hidden)
        assertNull(dao.row("commons:0", ru))
        assertEquals("cma:0", dao.row("cma:0", ru)?.id)
    }

    @Test
    fun eachFilterShowsTheLevelsBelowItsBound() = runTest {
        fillWithLevels()
        val plain = listOf("wd:Q1", "wd:Q2", "wd:Q4", "wd:Q6", "wd:Q7", "wd:Q9", "wd:Q10", "wd:Q11", "wd:Q12")
        val expected = mapOf(
            NudityFilter.SHOW_ALL to (1..9).map { "wd:Q$it" } + "cma:0" + (10..12).map { "wd:Q$it" },
            NudityFilter.HIDE_EXPLICIT to listOf("wd:Q1", "wd:Q2", "wd:Q3", "wd:Q4", "wd:Q6", "wd:Q7", "wd:Q8", "wd:Q9", "cma:0") +
                (10..12).map { "wd:Q$it" },
            NudityFilter.HIDE_NUDES to plain.take(6) + "cma:0" + plain.drop(6),
        )
        for ((filter, ids) in expected) {
            assertEquals(filter.name, ids, everything(nudityBelow = filter.nudityBelow).map { it.id })
            assertEquals(filter.name, ids.size, dao.count(filter.nudityBelow))
        }
        // a filtered row is still stored, and the duplicate stays hidden under every filter
        assertEquals(NudityLevel.EXPLICIT.ordinal, dao.picture("wd:Q5")?.nudity)
        assertEquals(true, dao.picture("commons:0")?.hidden)
        assertEquals(0, dao.picture("cma:0")?.nudity)
        // row() (tests) reads it whatever its level
        assertEquals("wd:Q5", dao.row("wd:Q5", ru)?.id)
        // a row carries its level, so a card read under one filter can be checked against another
        assertEquals(NudityLevel.EXPLICIT.ordinal, dao.row("wd:Q5", ru)?.nudity)
        assertEquals(
            listOf(NudityLevel.NUDE.ordinal, NudityLevel.NONE.ordinal, NudityLevel.EXPLICIT.ordinal),
            everything().filter { it.id in setOf("wd:Q3", "wd:Q4", "wd:Q5") }.map { it.nudity },
        )
        assertEquals(NudityLevel.NUDE.ordinal, dao.placed("wd:Q3", ru, all)?.row?.nudity)
    }

    @Test
    fun placeCountAndNeighboursFollowTheFilter() = runTest {
        fillWithLevels()
        for (filter in NudityFilter.entries) {
            val below = filter.nudityBelow
            val rows = everything(nudityBelow = below)
            rows.forEachIndexed { i, row ->
                assertEquals(filter.name, PlacedRow(row, i, rows.size), dao.placed(row.id, ru, below))
                assertEquals(filter.name, rows.getOrNull(i + 1)?.let { PlacedRow(it, i + 1, rows.size) }, dao.next(row.id, row.rank, ru, below))
                assertEquals(filter.name, rows.getOrNull(i - 1)?.let { PlacedRow(it, i - 1, rows.size) }, dao.previous(row.id, row.rank, ru, below))
            }
        }
        // a row the filter leaves out is not found, and a step from it goes from the rank it was read with
        val q5 = dao.picture("wd:Q5")!!
        val q3 = dao.picture("wd:Q3")!!
        val hideNudes = NudityFilter.HIDE_NUDES.nudityBelow
        assertNull(dao.placed(q5.id, ru, hideNudes))
        assertNull(dao.placed(q3.id, ru, hideNudes))
        assertEquals("wd:Q6", dao.next(q5.id, q5.rank, ru, hideNudes)?.row?.id)
        assertEquals("wd:Q4", dao.previous(q5.id, q5.rank, ru, hideNudes)?.row?.id)
        val q4 = dao.next(q3.id, q3.rank, ru, hideNudes)!!
        assertEquals(2 to 10, q4.position to q4.count) // after Q1 and Q2 (Q3 out); 10 shown
        // the viewer steps over a filtered row
        assertEquals("wd:Q4", dao.next("wd:Q2", dao.picture("wd:Q2")!!.rank, ru, hideNudes)?.row?.id)
        assertEquals("wd:Q6", dao.next("wd:Q4", dao.picture("wd:Q4")!!.rank, ru, NudityFilter.HIDE_EXPLICIT.nudityBelow)?.row?.id)
    }

    @Test
    fun markListedRaisesStoredRowsToTheBuiltInLevelsAndNeverLowersOne() = runTest {
        fun listed(q: String, line: Int, nudity: NudityLevel) =
            wd(line, nudity = nudity).copy(id = "wd:$q", wikidataId = q)
        dao.insertPictures(
            listOf(
                listed("Q334138", 0, NudityLevel.NONE), // L'Origine du monde, listed explicit, stored by an older list
                listed("Q151047", 1, NudityLevel.NONE), // listed nude
                listed("Q12418", 2, NudityLevel.NONE), // the Mona Lisa, not listed
                listed(Nudity.NUDE.first(), 3, NudityLevel.EXPLICIT), // listed nude, the release says explicit
                live("commons", 0).copy(id = "commons:Q334138"), // a live id is never a listed one
            ),
        )
        dao.rerank()
        assertEquals(2, dao.markListed())
        assertEquals(NudityLevel.EXPLICIT.ordinal, dao.picture("wd:Q334138")?.nudity)
        assertEquals(NudityLevel.NUDE.ordinal, dao.picture("wd:Q151047")?.nudity)
        assertEquals(NudityLevel.NONE.ordinal, dao.picture("wd:Q12418")?.nudity)
        assertEquals(NudityLevel.EXPLICIT.ordinal, dao.picture("wd:${Nudity.NUDE.first()}")?.nudity)
        assertEquals(0, dao.picture("commons:Q334138")?.nudity)
        // nothing left to raise: no row is written
        val changes = { db.openHelper.writableDatabase.query("SELECT total_changes()").use { it.moveToFirst(); it.getLong(0) } }
        val before = changes()
        assertEquals(0, dao.markListed())
        assertEquals(before, changes())
    }

    @Test
    fun markListedReachesEveryListedId_overManyStatements() = runTest {
        // each level's ids go in statements of at most `chunk` (SQLite before 3.32 binds 999 at most, and
        // Robolectric's binds far more, so a small chunk makes several, a last short one included): every id
        // is raised, none twice
        val ids = (Nudity.EXPLICIT + Nudity.NUDE).toList()
        val chunk = 7
        assertTrue(Nudity.EXPLICIT.size > chunk && Nudity.NUDE.size % chunk != 0)
        assertTrue(CatalogDao.LISTED_CHUNK < 999)
        dao.insertPictures(ids.mapIndexed { i, q -> wd(i).copy(id = "wd:$q", wikidataId = q) })
        assertEquals(ids.size, dao.markListed(chunk))
        assertEquals(
            ids.map { Nudity.listed(it).ordinal },
            ids.map { dao.picture("wd:$it")!!.nudity },
        )
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
            assertEquals(PlacedRow(row, i, 13), dao.placed(row.id, ru, all))
            assertEquals(rows.getOrNull(i + 1)?.let { PlacedRow(it, i + 1, 13) }, dao.next(row.id, row.rank, ru, all))
            assertEquals(rows.getOrNull(i - 1)?.let { PlacedRow(it, i - 1, 13) }, dao.previous(row.id, row.rank, ru, all))
        }
        // from a hidden row (past the end) there is nothing after, and the last shown row before
        val hidden = dao.picture("commons:0")!!
        assertTrue(hidden.hidden)
        assertNull(dao.placed(hidden.id, ru, all))
        assertNull(dao.next(hidden.id, hidden.rank, ru, all))
        assertEquals(PlacedRow(rows.last(), 12, 13), dao.previous(hidden.id, hidden.rank, ru, all))
    }

    @Test
    fun aNeighbourIsReadFromWhereThePictureIsNowOrFromTheRankReadWithIt() = runTest {
        dao.insertPictures((0 until 5).map { wd(it) })
        dao.rerank()
        val rows = everything()
        // a stale rank of a picture still shown: its rank as stored now counts
        assertEquals(rows[3].id, dao.next(rows[2].id, rows[0].rank, ru, all)?.row?.id)
        assertEquals(rows[1].id, dao.previous(rows[2].id, rows[4].rank, ru, all)?.row?.id)
        // a picture gone since: the rank it was read with
        assertEquals(PlacedRow(rows[2], 2, 5), dao.next("wd:gone", rows[1].rank, ru, all))
        assertEquals(PlacedRow(rows[0], 0, 5), dao.previous("wd:gone", rows[1].rank, ru, all))
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
        assertEquals(0, dao.count(all))
        assertEquals(emptyList<CatalogRow>(), everything())
        assertNull(dao.next("wd:Q1", -1, ru, all))
        assertNull(dao.previous("wd:Q1", Long.MAX_VALUE, ru, all))
        assertNull(dao.row("wd:Q1", ru))
        assertNull(dao.placed("wd:Q1", ru, all))
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
        // version 1 (before the nudity column: an upgrade) and a newer one (a downgrade, with a table of the same
        // name in another shape)
        val old = mapOf(
            1 to "CREATE TABLE picture (id TEXT NOT NULL, source TEXT NOT NULL, rank INTEGER NOT NULL, " +
                "hidden INTEGER NOT NULL, PRIMARY KEY(id))",
            3 to "CREATE TABLE picture (id TEXT PRIMARY KEY, something TEXT)",
        )
        for ((version, table) in old) {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL(table)
                db.execSQL(if (version == 1) "INSERT INTO picture VALUES ('wd:Q1', 'wd', 0, 0)" else "INSERT INTO picture (id) VALUES ('wd:Q1')")
                db.version = version
            }
            val reopened = CatalogDb.open(context, name)
            try {
                val d = reopened.catalog()
                assertEquals(0, d.count(all))
                d.insertPictures(listOf(wd(0, nudity = NudityLevel.NUDE)))
                d.rerank()
                assertEquals(0L, d.picture("wd:Q1")?.rank)
                assertEquals(1, d.picture("wd:Q1")?.nudity)
                assertEquals(2, reopened.openHelper.readableDatabase.version)
            } finally {
                reopened.close()
                context.deleteDatabase(name)
            }
        }
    }
}
