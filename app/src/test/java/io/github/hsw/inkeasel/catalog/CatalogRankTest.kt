package io.github.hsw.inkeasel.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRankTest {
    private fun wd(n: Int, file: String = "File $n.jpg", ref: String? = null) =
        RankKey("wd:Q$n", CatalogRank.WIKIDATA, n, file, ref, rank = 0, hidden = false)

    private fun live(source: String, n: Int, file: String? = null, ref: String? = null) =
        RankKey("$source:$n", source, n, file, ref, rank = 0, hidden = false)

    /** Visible ids in list order. */
    private fun order(rows: List<RankKey>): List<String> =
        CatalogRank.place(rows).filter { !it.hidden }.sortedBy { it.rank }.map { it.id }

    private fun ranks(rows: List<RankKey>): Map<String, Long> = CatalogRank.place(rows).associate { it.id to it.rank }

    @Test
    fun wikidataRowsTakeTheirLinePositionTimesTen() {
        val rows = listOf(wd(3), wd(1), wd(2))
        assertEquals(mapOf("wd:Q1" to 0L, "wd:Q2" to 10L, "wd:Q3" to 20L), ranks(rows))
    }

    @Test
    fun aRowWithoutAPositionGoesLastAndTiesGoById() {
        val rows = listOf(
            wd(5).copy(id = "wd:Q9", sourceRank = null),
            wd(5).copy(id = "wd:Q7"),
            wd(5).copy(id = "wd:Q6"),
            wd(1),
        )
        assertEquals(listOf("wd:Q1", "wd:Q6", "wd:Q7", "wd:Q9"), order(rows))
    }

    @Test
    fun everyTenthCardIsALiveOneAlternatingSourcesThenTheRestFollow() {
        val rows = (0 until 20).map { wd(it) } +
            (0 until 3).map { live("cma", it) } +
            (0 until 2).map { live("commons", it) }
        val r = ranks(rows)
        assertEquals(85L, r["cma:0"]) // after the 9th Wikidata row: the 10th card
        assertEquals(175L, r["commons:0"]) // after the 18th: the 20th card
        assertEquals(listOf(205L, 215L, 225L), listOf(r["cma:1"], r["commons:1"], r["cma:2"]))
        val o = order(rows)
        assertEquals("cma:0", o[9])
        assertEquals("commons:0", o[19])
        assertEquals(listOf("wd:Q18", "wd:Q19", "cma:1", "commons:1", "cma:2"), o.takeLast(5))
    }

    @Test
    fun aLongerSourceFinishesAfterTheOtherRunsOut() {
        val rows = (0 until 100).map { wd(it) } + live("cma", 0) + (0 until 3).map { live("commons", it) }
        val liveOrder = order(rows).filter { !it.startsWith("wd:") }
        assertEquals(listOf("cma:0", "commons:0", "commons:1", "commons:2"), liveOrder)
    }

    @Test
    fun liveRowsInTheirSourceOrder() {
        val rows = listOf(live("commons", 7), live("commons", 2), live("commons", 4))
        assertEquals(listOf("commons:2", "commons:4", "commons:7"), order(rows))
    }

    @Test
    fun withNoWikidataRowsTheLiveRowsFormTheList() {
        val rows = listOf(live("commons", 0), live("cma", 0), live("cma", 1))
        assertEquals(mapOf("cma:0" to 5L, "commons:0" to 15L, "cma:1" to 25L), ranks(rows))
    }

    @Test
    fun liveDuplicatesAreHiddenByCommonsFileOrMuseumReference() {
        val rows = (0 until 10).map { wd(it) } +
            wd(10, file = "Mona Lisa.jpg", ref = "1958.31") +
            live("commons", 0, file = "Mona_Lisa.jpg") + // underscores: the same Commons page
            live("cma", 0, ref = " 1958.31 ") +
            live("cma", 1, ref = "1958.32") +
            live("commons", 1, file = "mona lisa.jpg") // a different file
        val placed = CatalogRank.place(rows).associateBy { it.id }
        assertTrue(placed.getValue("commons:0").hidden)
        assertTrue(placed.getValue("cma:0").hidden)
        assertEquals(false, placed.getValue("cma:1").hidden)
        assertEquals(false, placed.getValue("commons:1").hidden)
        // a hidden row takes no interleave slot: the first shown live row is the 10th card
        assertEquals("cma:1", order(rows)[9])
        // hidden rows go after everything shown
        val maxShown = placed.values.filter { !it.hidden }.maxOf { it.rank }
        assertTrue(placed.values.filter { it.hidden }.all { it.rank > maxShown })
    }

    @Test
    fun aLiveRowNeverHidesAnotherLiveRow() {
        val rows = listOf(live("commons", 0, file = "A.jpg"), live("cma", 0, ref = "1"), live("cma", 1, ref = "1"))
        assertTrue(CatalogRank.place(rows).none { it.hidden })
    }

    @Test
    fun ranksAreUniqueForEveryMix() {
        for (wds in listOf(0, 1, 8, 9, 10, 95)) {
            for (lives in listOf(0, 1, 5, 23)) {
                val rows = (0 until wds).map { wd(it, ref = "r$it") } +
                    (0 until lives).map { live(if (it % 3 == 0) "cma" else "commons", it, ref = "r${it * 7}") }
                val placed = CatalogRank.place(rows)
                assertEquals(rows.size, placed.size)
                assertEquals("$wds/$lives", placed.size, placed.map { it.rank }.toSet().size)
                assertTrue(placed.all { it.rank >= 0 })
            }
        }
    }

    @Test
    fun changesListOnlyTheRowsThatMove() {
        val rows = (0 until 12).map { wd(it) } + live("cma", 0)
        val placed = CatalogRank.place(rows).associateBy { it.id }
        val settled = rows.map { r -> placed.getValue(r.id).let { r.copy(rank = it.rank, hidden = it.hidden) } }
        assertEquals(emptyList<Placement>(), CatalogRank.changes(settled))

        // a new Commons row: it takes the second slot; nothing else moves
        val more = settled + live("commons", 0).copy(rank = -1)
        assertEquals(listOf(Placement("commons:0", 125, false)), CatalogRank.changes(more))

        // a Wikidata row now holding the Cleveland row's accession number hides it
        val dup = settled.map { if (it.id == "wd:Q11") it.copy(museumRef = "X") else it }
            .map { if (it.id == "cma:0") it.copy(museumRef = "X") else it }
        assertEquals(listOf(Placement("cma:0", 125, true)), CatalogRank.changes(dup))
    }

    @Test
    fun clash_onlyWhenANewRankIsHeldByAnotherRow() {
        // the starter's rows at temporary negative ranks: every new rank is free, so each row moves once
        val fresh = (0 until 3).map { wd(it).copy(rank = -1L - it) }
        assertEquals(false, CatalogRank.clash(fresh, CatalogRank.changes(fresh)))

        // a new Wikidata row ahead of the others: their new ranks are held by each other
        val settled = (1 until 4).map { wd(it).copy(rank = 10L * (it - 1)) }
        val shifted = settled + wd(0).copy(rank = -1)
        assertEquals(true, CatalogRank.clash(shifted, CatalogRank.changes(shifted)))

        // a row that only turns hidden keeps its own rank: no clash
        val own = listOf(Placement("wd:Q1", 0, true))
        assertEquals(false, CatalogRank.clash(listOf(wd(1).copy(rank = 0)), own))
    }

    @Test
    fun commonsFileKey() {
        assertEquals("Mona Lisa.jpg", CatalogRank.fileKey(" Mona_Lisa.jpg "))
    }
}
