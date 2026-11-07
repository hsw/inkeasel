package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The real string tables (`values/` English, `values-ru/` Russian) agree: the same translatable keys, and per
 * key the same format placeholders (position and conversion), so a [Txt] built for one locale formats in the
 * other. Unit tests run in the module directory.
 */
class StringResourcesTest {
    private class Entry(val text: String, val translatable: Boolean)

    private fun table(dir: String): Map<String, Entry> {
        val f = File("src/main/res/$dir/strings.xml")
        assertTrue("$f not found (tests run in the app module)", f.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to Entry(e.textContent, e.getAttribute("translatable") != "false")
        }
    }

    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?([a-zA-Z%])""")

    /** The placeholders of [text] as `position:conversion`, in position order; `%%` is not one. */
    private fun signature(text: String): List<String> {
        var next = 0
        return placeholder.findAll(text).mapNotNull { m ->
            val conv = m.groupValues[3]
            if (conv == "%") return@mapNotNull null
            val pos = m.groupValues[1].removeSuffix("$").toIntOrNull() ?: ++next
            "$pos:$conv"
        }.sorted().toList()
    }

    private val en = table("values")
    private val ru = table("values-ru")

    @Test
    fun `both locales have the same translatable keys`() {
        val translatable = en.filterValues { it.translatable }.keys
        assertEquals(emptySet<String>(), translatable - ru.keys) // missing in Russian
        assertEquals(emptySet<String>(), ru.keys - translatable) // not in English, or not to be translated
    }

    @Test
    fun `placeholders match per key`() {
        val mismatched = ru.keys.filter { signature(en.getValue(it).text) != signature(ru.getValue(it).text) }
            .map { "$it: ${signature(en.getValue(it).text)} vs ${signature(ru.getValue(it).text)}" }
        assertEquals(emptyList<String>(), mismatched)
    }

    @Test
    fun `placeholders are numbered and formats are known`() {
        for ((name, e) in en + ru.mapKeys { "ru/${it.key}" }) {
            for (m in placeholder.findAll(e.text)) {
                val conv = m.groupValues[3]
                if (conv == "%") continue
                assertTrue("$name: unnumbered placeholder ${m.value}", m.groupValues[1].isNotEmpty())
                assertTrue("$name: unexpected conversion ${m.value}", conv in setOf("s", "d", "x"))
            }
        }
    }

    @Test
    fun `the signature reader sees what it should`() {
        assertEquals(listOf("1:s", "2:d"), signature("%2\$d of %1\$s, 100 %%"))
        assertEquals(listOf("1:x"), signature("code 0x%1\$02x"))
        assertEquals(emptyList<String>(), signature("battery 50 %%"))
    }
}
