package io.github.hsw.inkeasel.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The built-in sets against `tools/catalog-nsfw.txt`, read as the export tool reads it (unit tests run in the
 * app module, so the file is one directory up), and the level and filter rules.
 */
class NudityTest {
    private val levels = listOf("nude", "explicit")
    private val line = Regex("""^(Q[1-9][0-9]*)\s+([a-z]+)\s*(?:#.*)?$""")

    /** The list's ids by level; every line either blank, a `#` comment or `<Q-id> <level>  # <title>`. */
    private val listed: Map<String, String> by lazy {
        val file = File("../tools/catalog-nsfw.txt")
        assertTrue("$file not found (tests run in the app module)", file.isFile)
        val out = LinkedHashMap<String, String>()
        file.readLines().forEachIndexed { i, raw ->
            val text = raw.trim()
            if (text.isEmpty() || text.startsWith("#")) return@forEachIndexed
            val m = line.matchEntire(text) ?: error("${file.name}:${i + 1}: malformed: $text")
            val (id, level) = m.destructured
            assertTrue("${file.name}:${i + 1}: level $level", level in levels)
            assertTrue("${file.name}:${i + 1}: $id listed twice", out.put(id, level) == null)
        }
        out
    }

    @Test
    fun theBuiltInExplicitSetIsTheListsExplicitLines() {
        assertEquals(listed.filterValues { it == "explicit" }.keys, Nudity.EXPLICIT)
    }

    @Test
    fun theBuiltInNudeSetIsTheListsNudeLines() {
        assertEquals(listed.filterValues { it == "nude" }.keys, Nudity.NUDE)
        assertTrue(Nudity.NUDE.none { it in Nudity.EXPLICIT })
    }

    @Test
    fun everyListedIdHasTheFilesLevel() {
        for ((id, level) in listed) assertEquals(id, level, Nudity.listed(id).name.lowercase())
    }

    @Test
    fun theListHasBothLevels() {
        assertTrue("Q334138" in Nudity.EXPLICIT) // L'Origine du monde
        assertEquals("nude", listed["Q151047"]) // The Birth of Venus: a nude, not explicit
        assertTrue(listed.values.count { it == "nude" } > listed.values.count { it == "explicit" })
    }

    @Test
    fun matchesOnlyTheBareId() {
        assertEquals(NudityLevel.EXPLICIT, Nudity.listed("Q334138"))
        assertEquals(NudityLevel.NONE, Nudity.listed("wd:Q334138"))
        assertEquals(NudityLevel.NONE, Nudity.listed("Q3341380"))
        assertEquals(NudityLevel.NUDE, Nudity.listed("Q151047"))
        assertEquals(NudityLevel.NONE, Nudity.listed("wd:Q151047"))
        assertEquals(NudityLevel.NONE, Nudity.listed("Q1510470"))
    }

    @Test
    fun levelFromEachSource() {
        assertEquals(NudityLevel.NONE, Nudity.levelOf(null, emptyList(), "Q12418"))
        assertEquals(NudityLevel.NUDE, Nudity.levelOf("nude", emptyList(), "Q12418"))
        assertEquals(NudityLevel.EXPLICIT, Nudity.levelOf("explicit", emptyList(), "Q12418"))
        assertEquals(NudityLevel.NUDE, Nudity.levelOf(null, emptyList(), "Q151047")) // listed nude
        assertEquals(NudityLevel.EXPLICIT, Nudity.levelOf(null, emptyList(), "Q334138")) // listed explicit
        assertEquals(NudityLevel.NUDE, Nudity.levelOf(null, listOf("Q134307", Nudity.NUDE_GENRE), "Q12418"))
        assertEquals(NudityLevel.NONE, Nudity.levelOf(null, listOf("Q134307"), "Q12418")) // portrait genre
    }

    @Test
    fun levelIsTheStrictestSource() {
        assertEquals(NudityLevel.EXPLICIT, Nudity.levelOf("nude", emptyList(), "Q334138"))
        assertEquals(NudityLevel.EXPLICIT, Nudity.levelOf("explicit", listOf(Nudity.NUDE_GENRE), "Q151047"))
        assertEquals(NudityLevel.EXPLICIT, Nudity.levelOf("explicit", emptyList(), "Q151047"))
        assertEquals(NudityLevel.NUDE, Nudity.levelOf("nude", listOf(Nudity.NUDE_GENRE), "Q151047"))
        assertEquals(NudityLevel.EXPLICIT, Nudity.levelOf(null, listOf(Nudity.NUDE_GENRE), "Q334138"))
    }

    @Test
    fun aBlankNsfwIsNone() {
        for (value in listOf("", " ")) {
            assertEquals(NudityLevel.NONE, Nudity.levelOf(value, emptyList(), "Q12418"))
            assertEquals(NudityLevel.NUDE, Nudity.levelOf(value, emptyList(), "Q151047"))
        }
    }

    @Test
    fun anUnknownNsfwCountsAsNude_neverLessStrict() {
        for (value in listOf("none", "Explicit", "NUDE", "sex", "suggestive")) {
            assertEquals(value, NudityLevel.NUDE, Nudity.levelOf(value, emptyList(), "Q12418"))
            assertEquals(value, NudityLevel.EXPLICIT, Nudity.levelOf(value, emptyList(), "Q334138"))
        }
    }

    @Test
    fun levelsAreStoredByOrdinal() {
        assertEquals(listOf(0, 1, 2), NudityLevel.entries.map { it.ordinal })
        assertEquals(listOf(NudityLevel.NONE, NudityLevel.NUDE, NudityLevel.EXPLICIT), NudityLevel.entries)
    }

    @Test
    fun filterBounds() {
        assertEquals(3, NudityFilter.SHOW_ALL.nudityBelow)
        assertEquals(2, NudityFilter.HIDE_EXPLICIT.nudityBelow)
        assertEquals(1, NudityFilter.HIDE_NUDES.nudityBelow)
        assertEquals(NudityFilter.HIDE_EXPLICIT, NudityFilter.DEFAULT)
    }

    @Test
    fun eachFilterShowsTheRightLevels() {
        // the list's queries keep a row when its level's ordinal is below the bound (`nudity < :nudityBelow`)
        fun shown(f: NudityFilter) = NudityLevel.entries.filter { it.ordinal < f.nudityBelow }
        assertEquals(NudityLevel.entries, shown(NudityFilter.SHOW_ALL))
        assertEquals(listOf(NudityLevel.NONE, NudityLevel.NUDE), shown(NudityFilter.HIDE_EXPLICIT))
        assertEquals(listOf(NudityLevel.NONE), shown(NudityFilter.HIDE_NUDES))
    }

    @Test
    fun theLockForcesHideAllNudes() {
        for (choice in NudityFilter.entries) {
            assertEquals(choice, NudityFilter.effective(choice, locked = false))
            assertEquals(NudityFilter.HIDE_NUDES, NudityFilter.effective(choice, locked = true))
        }
    }
}
