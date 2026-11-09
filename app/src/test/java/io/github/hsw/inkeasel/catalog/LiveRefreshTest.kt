package io.github.hsw.inkeasel.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.hsw.inkeasel.ClevelandMapping
import io.github.hsw.inkeasel.CommonsMapping
import io.github.hsw.inkeasel.Listing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class LiveRefreshTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: CatalogDb
    private val lock = Mutex()
    private var clock = 1_000_000L
    private val logged = CopyOnWriteArrayList<String>()
    private val ru = CatalogRow.chainKey(LanguageChain.of("ru-RU"))

    /** A live source answering [answer] (or throwing it), counting the requests. */
    private class Fake(override val id: String, var answer: () -> Listing<LiveRow>) : LiveSource {
        var asked = 0
        override suspend fun fetch(): Listing<LiveRow> {
            asked++
            return answer()
        }
    }

    @Before
    fun open() {
        db = CatalogDb.configure(Room.inMemoryDatabaseBuilder(context, CatalogDb::class.java)).build()
    }

    @After
    fun close() = db.close()

    private fun refresh(vararg sources: LiveSource) =
        LiveRefresh(db, sources.toList(), now = { clock }, log = { msg, e -> logged += if (e == null) msg else "$msg: $e" })

    private fun LiveRefresh.go(): List<LiveResult> = runBlocking { this@go.run { block -> lock.withLock { block() } } }

    /** [n] Wikidata rows in `core` order, at their places; [files] and [refs] give some of them a Commons file or accession number. */
    private fun wikidata(n: Int, files: Map<Int, String> = emptyMap(), refs: Map<Int, String> = emptyMap()) = runBlocking {
        db.catalog().insertPictures(
            (0 until n).map { i ->
                PictureEntity(
                    id = "wd:Q${i + 1}", source = CatalogRank.WIKIDATA, rank = CatalogRank.wikidataRank(i),
                    wikidataId = "Q${i + 1}", commonsFile = files[i] ?: "Painting ${i + 1}.jpg", museumRef = refs[i],
                    sourceRank = i, width = 800, height = 600,
                )
            },
        )
    }

    private fun shown(): List<String> = query("SELECT id FROM picture WHERE hidden = 0 ORDER BY rank")

    private fun query(sql: String): List<String> = db.openHelper.readableDatabase.query(sql).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" }) }
    }

    private fun rows(source: String, vararg names: String) =
        names.map { LiveRow("$source:$it", "$source $it", commonsFile = if (source == "commons") it else null) }

    private fun fixture(name: String) = JSONObject(javaClass.getResource("/catalog/$name")!!.readText())

    @Test
    fun liveRowsTakeEveryTenthCardInTurnThenFollowTheLastWikidataRow() {
        wikidata(30)
        val cma = Fake("cma") { Listing(rows("cma", "1", "2", "3")) }
        val commons = Fake("commons") { Listing(rows("commons", "a.jpg", "b.jpg", "c.jpg")) }

        val results = refresh(cma, commons).go()

        assertEquals(listOf(LiveResult("cma", LiveOutcome.REPLACED, 3), LiveResult("commons", LiveOutcome.REPLACED, 3)), results)
        val list = shown()
        assertEquals(36, list.size)
        assertEquals("cma:1", list[9]) // the 10th card
        assertEquals("commons:a.jpg", list[19])
        assertEquals("cma:2", list[29])
        assertEquals(listOf("wd:Q30", "commons:b.jpg", "cma:3", "commons:c.jpg"), list.takeLast(4))
        assertEquals(30, list.count { it.startsWith("wd:") })
        // the Wikidata rows keep their places: only the live rows were written
        assertEquals(listOf("0", "10", "80", "90"), query("SELECT rank FROM picture WHERE id IN ('wd:Q1','wd:Q2','wd:Q9','wd:Q10') ORDER BY rank"))
    }

    @Test
    fun theSourcesAnswersBecomeRowsDeduplicatedAgainstTheWikidataRows() {
        // wd:Q2 is the Cleveland painting 1984.59 (P11110), wd:Q3 the Commons lake (a name with underscores)
        wikidata(20, files = mapOf(2 to "Example_lake.jpeg"), refs = mapOf(1 to "1984.59"))
        val cma = Fake("cma") { Listing(ClevelandMapping.rows(fixture("cleveland.json"))) }
        val commons = Fake("commons") { Listing(CommonsMapping.page(fixture("commons.json")).items) }

        refresh(cma, commons).go()

        val dao = db.catalog()
        assertTrue(runBlocking { dao.picture("cma:152006") }!!.hidden)
        assertTrue(runBlocking { dao.picture("commons:Example lake.jpeg") }!!.hidden)
        assertEquals(listOf("cma:99", "commons:Example valley at dawn - 06.jpg"), shown().filter { !it.startsWith("wd:") })
        assertEquals("cma:99", shown()[9])
        assertEquals("commons:Example valley at dawn - 06.jpg", shown()[19])

        // and they read back as pictures the way the grid, the details panel and the viewer take them
        val gainsborough = CatalogPicture.of(runBlocking { dao.row("cma:152006", ru) }!!, ", ")
        assertEquals("Rocky, Wooded Landscape with a Dell and Weir", gainsborough.title)
        assertEquals("Thomas Gainsborough, c. 1782–1783", gainsborough.credit)
        assertEquals("Thomas Gainsborough, Anonymous Landscape Painter", gainsborough.artist)
        assertEquals("Cleveland Museum of Art", gainsborough.collection)
        assertEquals("https://openaccess-cdn.clevelandart.org/1984.59/1984.59_web.jpg", gainsborough.thumbUrl)
        assertEquals("https://openaccess-cdn.clevelandart.org/1984.59/1984.59_print.jpg", gainsborough.fullUrl)
        assertEquals("https://creativecommons.org/publicdomain/zero/1.0/", gainsborough.licenceUrl) // the deed map
        assertEquals("cma", gainsborough.source)
        assertEquals(5500, gainsborough.width)

        val valley = CatalogPicture.of(runBlocking { dao.row("commons:Example valley at dawn - 06.jpg", ru) }!!, ", ", 330)
        assertEquals("Example valley at dawn - 06", valley.title)
        assertEquals("Example Photographer & Friend, 2026-09-21", valley.credit)
        assertEquals("Example valley at dawn - 06.jpg", valley.commonsFile)
        assertEquals(CommonsUrls.url("Example valley at dawn - 06.jpg", 330, 6744), valley.thumbUrl)
        assertEquals(CommonsUrls.url("Example valley at dawn - 06.jpg", 1920, 6744), valley.fullUrl)
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0", valley.licenceUrl)
        assertNull(valley.attribution) // the author is the artist, shown on the card already
        assertEquals("commons", valley.source)
    }

    @Test
    fun aRefreshedSourceIsAskedAgainAWeekLater() {
        wikidata(10)
        val cma = Fake("cma") { Listing(rows("cma", "1")) }
        val refresh = refresh(cma)
        refresh.go()
        assertEquals(1, cma.asked)

        clock += CatalogSyncPlan.CHECK_EVERY_MS - 1
        assertEquals(listOf(LiveResult("cma", LiveOutcome.FRESH, 1)), refresh.go())
        assertEquals(1, cma.asked)

        clock += 1
        cma.answer = { Listing(rows("cma", "2", "3")) }
        assertEquals(listOf(LiveResult("cma", LiveOutcome.REPLACED, 2)), refresh.go())
        assertEquals(2, cma.asked)
        assertEquals(listOf("cma:2", "cma:3"), query("SELECT id FROM picture WHERE source = 'cma' ORDER BY rank"))
        assertEquals(listOf("live-cma|$clock|$clock"), query("SELECT name, checkedAt, importedAt FROM catalog_file"))
    }

    @Test
    fun aSourceThatIsDownKeepsItsRowsAndIsAskedAgainAtTheNextStart() {
        wikidata(10)
        val cma = Fake("cma") { Listing(rows("cma", "1", "2")) }
        val refresh = refresh(cma)
        refresh.go()
        val refreshedAt = clock

        clock += CatalogSyncPlan.CHECK_EVERY_MS
        cma.answer = { throw UnknownHostException("openaccess-api.clevelandart.org") }
        assertEquals(listOf(LiveResult("cma", LiveOutcome.FAILED, 2)), refresh.go())
        assertEquals(listOf("cma:1", "cma:2"), query("SELECT id FROM picture WHERE source = 'cma' ORDER BY rank"))
        assertTrue(logged.any { it.startsWith("cma: refresh failed") })
        // nothing recorded: the next start asks again
        assertEquals(listOf("$refreshedAt"), query("SELECT checkedAt FROM catalog_file"))
        refresh.go()
        assertEquals(3, cma.asked)
    }

    @Test
    fun aSourceThatIsDownWithNoRowsChangesNothing() {
        wikidata(10)
        val cma = Fake("cma") { throw UnknownHostException("openaccess-api.clevelandart.org") }
        val commons = Fake("commons") { Listing(rows("commons", "a.jpg")) }

        val results = refresh(cma, commons).go()

        // one source down does not stop the other
        assertEquals(listOf(LiveResult("cma", LiveOutcome.FAILED, 0), LiveResult("commons", LiveOutcome.REPLACED, 1)), results)
        assertEquals(11, shown().size)
        assertEquals(listOf("live-commons"), query("SELECT name FROM catalog_file"))
    }

    @Test
    fun anEmptyAnswerNeverReplacesTheRows() {
        wikidata(10)
        val cma = Fake("cma") { Listing(rows("cma", "1", "2")) }
        val refresh = refresh(cma)
        refresh.go()

        clock += CatalogSyncPlan.CHECK_EVERY_MS
        cma.answer = { Listing(emptyList()) }
        assertEquals(listOf(LiveResult("cma", LiveOutcome.KEPT, 2)), refresh.go())
        assertEquals(2, query("SELECT id FROM picture WHERE source = 'cma'").size)
    }

    @Test
    fun aListCutShortIsKeptOnlyWhenNotSmallerAndAskedForAgain() {
        wikidata(10)
        val commons = Fake("commons") { Listing(rows("commons", "a.jpg", "b.jpg", "c.jpg")) }
        val refresh = refresh(commons)
        refresh.go()
        clock += CatalogSyncPlan.CHECK_EVERY_MS
        val due = clock

        // smaller: the stored list stays
        commons.answer = { Listing(rows("commons", "d.jpg", "e.jpg"), complete = false) }
        assertEquals(listOf(LiveResult("commons", LiveOutcome.KEPT, 3)), refresh.go())
        assertEquals(listOf("commons:a.jpg", "commons:b.jpg", "commons:c.jpg"), query("SELECT id FROM picture WHERE source = 'commons' ORDER BY rank"))

        // not smaller: it replaces them, but the source is asked again at the next start
        commons.answer = { Listing(rows("commons", "d.jpg", "e.jpg", "f.jpg", "g.jpg"), complete = false) }
        assertEquals(listOf(LiveResult("commons", LiveOutcome.PARTIAL, 4)), refresh.go())
        assertEquals(4, query("SELECT id FROM picture WHERE source = 'commons'").size)
        assertFalse(query("SELECT checkedAt FROM catalog_file").contains("$due"))
        refresh.go()
        assertEquals(4, commons.asked)
    }

    @Test
    fun aRepeatedFileKeepsItsFirstPlace() {
        wikidata(10)
        val commons = Fake("commons") { Listing(rows("commons", "a.jpg", "b.jpg", "a.jpg")) }
        assertEquals(listOf(LiveResult("commons", LiveOutcome.REPLACED, 2)), refresh(commons).go())
        assertEquals(listOf("commons:a.jpg", "commons:b.jpg"), query("SELECT id FROM picture WHERE source = 'commons' ORDER BY rank"))
    }

    @Test
    fun aNewListRedoesTheDedupe() {
        wikidata(20, files = mapOf(0 to "a.jpg"))
        val commons = Fake("commons") { Listing(rows("commons", "a.jpg", "b.jpg")) }
        val refresh = refresh(commons)
        refresh.go()
        assertEquals(listOf("commons:b.jpg"), shown().filter { it.startsWith("commons:") })

        clock += CatalogSyncPlan.CHECK_EVERY_MS
        commons.answer = { Listing(rows("commons", "c.jpg", "a.jpg")) }
        refresh.go()
        assertEquals(listOf("commons:c.jpg"), shown().filter { it.startsWith("commons:") })
        assertEquals(listOf("commons:a.jpg|1"), query("SELECT id, hidden FROM picture WHERE hidden = 1"))
        assertEquals(0, query("SELECT id FROM picture WHERE id = 'commons:b.jpg'").size) // gone with its list
    }

    @Test
    fun theSourceIsAskedOutsideTheWriteLock() = runBlocking {
        wikidata(10)
        val asked = CompletableDeferred<Unit>()
        val cma = Fake("cma") {
            asked.complete(Unit)
            Listing(rows("cma", "1"))
        }
        lock.lock() // an import holds the lock
        val run = async(Dispatchers.IO) { refresh(cma).run { block -> lock.withLock { block() } } }
        withTimeout(10_000) { asked.await() }
        assertEquals(0, query("SELECT id FROM picture WHERE source = 'cma'").size) // the write waits for the lock
        lock.unlock()
        assertEquals(listOf(LiveResult("cma", LiveOutcome.REPLACED, 1)), withTimeout(10_000) { run.await() })
        assertEquals(1, query("SELECT id FROM picture WHERE source = 'cma'").size)
    }
}
