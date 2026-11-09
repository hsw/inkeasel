package io.github.hsw.inkeasel.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSourceRowsTest {
    private val week = CatalogSyncPlan.CHECK_EVERY_MS

    private fun state(at: Long?) = CatalogFileEntity(LiveSourceRows.stateName("cma"), checkedAt = at, importedAt = at)

    @Test
    fun stateNamesAreApartFromTheReleaseFiles() {
        assertEquals("live-cma", LiveSourceRows.stateName("cma"))
        assertEquals("live-commons", LiveSourceRows.stateName("commons"))
        // never taken for a labels file, so a chain change never forgets it
        assertNull(CatalogSyncPlan.langOf("live-cma"))
        assertEquals(emptyList<String>(), CatalogSyncPlan.outside(listOf("ru"), listOf("live-cma", "live-commons")))
        assertTrue(CatalogSyncPlan.files(listOf("ru")).none { it.name.startsWith("live-") })
    }

    @Test
    fun dueWhenNeverRefreshedAWeekLaterOrWhenTheClockWentBack() {
        assertTrue(LiveSourceRows.due(null, 1_000))
        assertTrue(LiveSourceRows.due(state(null), 1_000))
        assertFalse(LiveSourceRows.due(state(1_000), 1_000)) // fresh
        assertFalse(LiveSourceRows.due(state(1_000), 1_000 + week - 1))
        assertTrue(LiveSourceRows.due(state(1_000), 1_000 + week)) // stale
        assertTrue(LiveSourceRows.due(state(5_000), 1_000)) // refreshed "in the future"
    }

    @Test
    fun aWholeListReplacesAnEmptyOneNeverDoes() {
        assertEquals(LiveOutcome.REPLACED, LiveSourceRows.decide(got = 3, complete = true, stored = 0))
        assertEquals(LiveOutcome.REPLACED, LiveSourceRows.decide(got = 1, complete = true, stored = 50)) // even smaller
        assertEquals(LiveOutcome.KEPT, LiveSourceRows.decide(got = 0, complete = true, stored = 50))
        assertEquals(LiveOutcome.KEPT, LiveSourceRows.decide(got = 0, complete = true, stored = 0))
        assertEquals(LiveOutcome.KEPT, LiveSourceRows.decide(got = 0, complete = false, stored = 0))
    }

    @Test
    fun aListCutShortReplacesOnlyWhenNotSmaller() {
        assertEquals(LiveOutcome.PARTIAL, LiveSourceRows.decide(got = 3, complete = false, stored = 0))
        assertEquals(LiveOutcome.PARTIAL, LiveSourceRows.decide(got = 5, complete = false, stored = 5))
        assertEquals(LiveOutcome.KEPT, LiveSourceRows.decide(got = 4, complete = false, stored = 5))
    }

    @Test
    fun rowsKeepTheListsOrderEveryFieldAndAFreeRankEach() {
        val rows = listOf(
            LiveRow(
                id = "cma:1", title = "Fields", artists = listOf("A Painter", "Another"), date = "c. 1880",
                collection = "Cleveland Museum of Art", pageUrl = "https://example.org/1", licence = "CC0",
                width = 1200, height = 800, museumRef = "1915.534", thumbUrl = "https://example.org/1_web.jpg",
                fullUrl = "https://example.org/1_print.jpg",
            ),
            LiveRow(id = "cma:2", title = null),
        )
        val out = LiveSourceRows.entities("cma", rows, free = -10)
        assertEquals(
            PictureEntity(
                id = "cma:1", source = "cma", rank = -10, museumRef = "1915.534", sourceRank = 0, width = 1200,
                height = 800, licence = "CC0", title = "Fields", artist = "A Painter${CatalogConverters.SEPARATOR}Another",
                date = "c. 1880", collection = "Cleveland Museum of Art", pageUrl = "https://example.org/1",
                thumbUrl = "https://example.org/1_web.jpg", fullUrl = "https://example.org/1_print.jpg",
            ),
            out[0],
        )
        // the list query reads the artists back as a list
        assertEquals(listOf("A Painter", "Another"), CatalogConverters().toTexts(out[0].artist))
        assertEquals(PictureEntity(id = "cma:2", source = "cma", rank = -11, sourceRank = 1), out[1])
        assertFalse(out.any { it.hidden || it.wikidataId != null || it.attr })
    }

    @Test
    fun aRepeatedIdKeepsItsFirstPlaceAndAnotherSourcesIdIsLeftOut() {
        val rows = listOf(
            LiveRow("commons:A.jpg", "first", commonsFile = "A.jpg"),
            LiveRow("cma:9", "not this source's"),
            LiveRow("commons:A.jpg", "again", commonsFile = "A.jpg"),
            LiveRow("commons:B.jpg", "second", commonsFile = "B.jpg"),
            LiveRow("commonsX:C.jpg", "a lookalike prefix"),
        )
        val out = LiveSourceRows.entities("commons", rows, free = -1)
        assertEquals(listOf("commons:A.jpg", "commons:B.jpg"), out.map { it.id })
        assertEquals(listOf("first", "second"), out.map { it.title })
        assertEquals(listOf(0, 1), out.map { it.sourceRank })
        assertEquals(listOf(-1L, -2L), out.map { it.rank })
        assertEquals(listOf("A.jpg", "B.jpg"), out.map { it.commonsFile })
        assertEquals(emptyList<PictureEntity>(), LiveSourceRows.entities("commons", emptyList(), free = -1))
    }
}
