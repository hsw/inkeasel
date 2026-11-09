package io.github.hsw.inkeasel.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The built-in set against `tools/catalog-nsfw.txt`, read as the export tool reads it (unit tests run in the
 * app module, so the file is one directory up).
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
    fun theListHasBothLevels() {
        assertTrue("Q334138" in Nudity.EXPLICIT) // L'Origine du monde
        assertEquals("nude", listed["Q151047"]) // The Birth of Venus: a nude, not explicit
        assertTrue(listed.values.count { it == "nude" } > listed.values.count { it == "explicit" })
    }

    @Test
    fun matchesOnlyTheBareId() {
        assertTrue(Nudity.isExplicit("Q334138"))
        assertFalse(Nudity.isExplicit("wd:Q334138"))
        assertFalse(Nudity.isExplicit("Q3341380"))
        assertFalse(Nudity.isExplicit("Q151047"))
    }
}
