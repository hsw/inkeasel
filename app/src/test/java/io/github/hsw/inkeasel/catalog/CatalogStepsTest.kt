package io.github.hsw.inkeasel.catalog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.hsw.inkeasel.ViewerState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogStepsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: CatalogDb
    private lateinit var dao: CatalogDao

    private val ru = LanguageChain.of("ru-RU")

    @Before
    fun open() {
        db = inMemoryCatalogDb(context)
        dao = db.catalog()
    }

    @After
    fun close() = db.close()

    private fun wd(line: Int, file: String = "Painting $line.jpg") = PictureEntity(
        id = "wd:Q${line + 1}", source = CatalogRank.WIKIDATA, rank = -1L - line, wikidataId = "Q${line + 1}",
        commonsFile = file, fame = 3, sourceRank = line, width = 800, height = 600,
    )

    private fun live(source: String, n: Int, file: String? = null) = PictureEntity(
        id = "$source:$n", source = source, rank = -10_000L - n - (if (source == "cma") 0 else 5_000),
        commonsFile = file, sourceRank = n, title = "$source $n", thumbUrl = "https://example.org/$n.jpg",
        fullUrl = "https://example.org/$n-full.jpg",
    )

    /**
     * 20 Wikidata rows, then a Cleveland row (the 10th card) and a Commons row that duplicates `wd:Q3`
     * (hidden): 21 cards.
     */
    private suspend fun fill() {
        dao.insertPictures((0 until 20).map { wd(it) } + live("cma", 0) + live("commons", 0, file = "Painting_2.jpg"))
        dao.rerank()
        dao.insertLabels(listOf(LabelEntity("Q1", "ru", "Первая"), LabelEntity("Q1", "en", "First")))
    }

    private fun steps(chain: List<String> = ru, filter: NudityFilter = NudityFilter.DEFAULT) =
        CatalogSteps(dao, chain, filter.nudityBelow) { CatalogPicture.of(it, ", ", 330) }

    /** Steps [n] times by [delta] from [from], `null` once past an end. */
    private suspend fun CatalogSteps.walk(from: ViewerState, delta: Int, n: Int): ViewerState? {
        var s: ViewerState? = from
        repeat(n) { s = s?.let { step(it, delta) } }
        return s
    }

    @Test
    fun openReadsThePlaceAndTheSizeOfTheList() = runTest {
        fill()
        val first = steps().open("wd:Q1")!!
        assertEquals(0, first.index)
        assertEquals(21, first.count)
        assertEquals("Первая", first.picture.title)
        assertEquals(dao.picture("wd:Q1")!!.rank, first.rank)
        // the same picture the grid makes of the row
        assertEquals(CatalogPicture.of(dao.row("wd:Q1", CatalogRow.chainKey(ru))!!, ", ", 330), first.picture)

        val live = steps().open("cma:0")!!
        assertEquals(9, live.index) // every 10th card is a live one
        assertEquals("cma 0", live.picture.title)
        assertEquals("https://example.org/0-full.jpg", live.picture.fullUrl)

        assertEquals("First", steps(LanguageChain.of("en")).open("wd:Q1")!!.picture.title)
        assertNull(steps().open("wd:Q999"))
    }

    @Test
    fun stepsFollowTheListOrderWithItsPositionAndStopAtTheEnds() = runTest {
        fill()
        val steps = steps()
        val first = steps.open("wd:Q1")!!
        assertNull(steps.step(first, -1))

        // forward through every card: the ids in list order, positions 0…20, the hidden duplicate skipped
        val seen = ArrayList<ViewerState>()
        var s: ViewerState? = first
        while (s != null) {
            seen += s
            s = steps.step(s, 1)
        }
        val ids = seen.map { it.picture.id }
        assertEquals(21, ids.size)
        assertEquals((0 until 21).toList(), seen.map { it.index })
        assertEquals("cma:0", ids[9])
        assertEquals(false, "commons:0" in ids)
        assertEquals(setOf(21), seen.map { it.count }.toSet())

        // and back from the last card to the first
        val last = seen.last()
        assertEquals("wd:Q20", last.picture.id)
        assertEquals(first, steps.walk(last, -1, 20))
        assertNull(steps.walk(last, -1, 21))
        assertNull(steps.step(last, 0))
    }

    @Test
    fun aPictureShownAloneHasNoSteps() = runTest {
        fill()
        val alone = ViewerState.alone(CatalogPicture.of(dao.row("wd:Q5", CatalogRow.chainKey(ru))!!, ", ", 330))
        assertNull(steps().step(alone, 1))
    }

    @Test
    fun theCountFollowsTheDatabase() = runTest {
        fill()
        val steps = steps()
        val first = steps.open("wd:Q1")!!
        dao.insertPictures(listOf(wd(20)))
        dao.rerank()
        val next = steps.step(first, 1)!!
        assertEquals(1, next.index)
        assertEquals(22, next.count)
    }

    @Test
    fun aStepStartsFromWhereThePictureIsNowAfterARerank() = runTest {
        fill()
        val steps = steps()
        val third = steps.walk(steps.open("wd:Q1")!!, 1, 2)!!
        assertEquals("wd:Q3", third.picture.id)
        // a new import puts a row ahead of every other: the ranks the viewer read are all one place off
        dao.insertPictures(listOf(wd(-1).copy(id = "wd:Q0", wikidataId = "Q0", rank = -50_000)))
        dao.rerank()
        assertEquals(dao.picture("wd:Q2")!!.rank, third.rank) // Q3's old rank is Q2's now

        val next = steps.step(third, 1)!!
        assertEquals("wd:Q4", next.picture.id)
        assertEquals(4, next.index) // after Q0 … Q3
        assertEquals("wd:Q2", steps.step(third, -1)!!.picture.id)
    }

    @Test
    fun stepsAndTheCountFollowTheFilterTheViewerOpenedWith() = runTest {
        fill()
        // Q2 nude, Q4 explicit
        db.openHelper.writableDatabase.execSQL("UPDATE picture SET nudity = 1 WHERE id = 'wd:Q2'")
        db.openHelper.writableDatabase.execSQL("UPDATE picture SET nudity = 2 WHERE id = 'wd:Q4'")
        // the first five cards and the list's size under each filter
        val expected = mapOf(
            NudityFilter.SHOW_ALL to (listOf("wd:Q1", "wd:Q2", "wd:Q3", "wd:Q4", "wd:Q5") to 21),
            NudityFilter.HIDE_EXPLICIT to (listOf("wd:Q1", "wd:Q2", "wd:Q3", "wd:Q5", "wd:Q6") to 20),
            NudityFilter.HIDE_NUDES to (listOf("wd:Q1", "wd:Q3", "wd:Q5", "wd:Q6", "wd:Q7") to 19),
        )
        for ((filter, want) in expected) {
            val steps = steps(filter = filter)
            val seen = ArrayList<ViewerState>()
            var s = steps.open("wd:Q1")
            while (s != null && seen.size < 5) {
                seen += s
                s = steps.step(s, 1)
            }
            assertEquals(filter.name, want.first, seen.map { it.picture.id })
            assertEquals(filter.name, (0 until 5).toList(), seen.map { it.index })
            assertEquals(filter.name, setOf(want.second), seen.map { it.count }.toSet())
        }
        // a picture the filter leaves out opens alone
        assertNull(steps(filter = NudityFilter.HIDE_NUDES).open("wd:Q2"))
        assertNull(steps().open("wd:Q4"))
    }

    @Test
    fun aPictureHiddenSinceTheGridReadItOpensAlone() = runTest {
        fill()
        // the Commons row that duplicates wd:Q3 is hidden: the list does not show it, so it has no place there
        assertEquals(true, dao.picture("commons:0")!!.hidden)
        assertNull(steps().open("commons:0"))
    }
}
