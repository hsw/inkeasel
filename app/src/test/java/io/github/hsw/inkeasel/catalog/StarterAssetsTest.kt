package io.github.hsw.inkeasel.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The committed starter set (`assets/catalog/`, cut from the release by `tools/catalog-export.py
 * --starter-from`) reads with [CatalogRows] without a bad line, is in the release's order and carries labels
 * for every UI language the app has. Unit tests run in the module directory.
 */
class StarterAssetsTest {
    private fun asset(name: String): File =
        File("src/main/assets/catalog/$name").also { assertTrue("$it not found (tests run in the app module)", it.isFile) }

    private fun <T : Any> read(name: String, parse: (String) -> T): List<T> =
        JsonLinesReader(asset(name).inputStream(), parse).use { reader ->
            val rows = ArrayList<T>()
            reader.forEachBatch(500) { rows += it }
            assertEquals("$name: bad lines", 0, reader.badLines)
            rows
        }

    private val core by lazy { read("core.jsonl.gz", CatalogRows::core) }
    private val labels by lazy { read("labels.jsonl.gz", CatalogRows::label) }

    @Test
    fun core_isTheTopThousandInFameOrder() {
        assertEquals(1000, core.size)
        assertEquals(core.size, core.map { it.id }.toSet().size)
        assertEquals("Q12418", core[0].id)
        core.zipWithNext().forEach { (a, b) ->
            // the release order: more sitelinks first, ties by the numeric id
            assertTrue(
                "${a.id} before ${b.id}",
                a.fame > b.fame || a.fame == b.fame && a.id.drop(1).toLong() < b.id.drop(1).toLong(),
            )
        }
    }

    @Test
    fun labels_carryTheirLanguageAndBelongToStarterEntities() {
        assertTrue(labels.isNotEmpty())
        assertTrue(labels.all { it.lang != null })
        val entities = core.flatMap { listOf(it.id) + it.creators + it.collections + it.movements + it.genres }
        val order = entities.distinct().withIndex().associate { (i, id) -> id to i }
        // every label is of a starter painting or an entity one references, in the order a prefix import needs
        val positions = labels.map { order[it.id] ?: error("label of ${it.id}, not in the starter core") }
        assertEquals(positions.sorted(), positions)
        assertEquals(labels.size, labels.map { it.id to it.lang }.toSet().size)
    }

    @Test
    fun labels_coverEveryAppLanguageWithEnglishAndMul() {
        val langs = labels.mapNotNull { it.lang }.toSet()
        assertTrue("en" in langs)
        assertTrue("mul" in langs)
        val appLocales = File("src/main/res").list()!!
            .filter { it.startsWith("values-") && File("src/main/res/$it/strings.xml").isFile }
            .map { it.removePrefix("values-").replace("-r", "-") }
        assertTrue("no translated string table found", appLocales.isNotEmpty())
        for (tag in appLocales) {
            // the chain's own part, before English and mul
            val own = LanguageChain.of(tag).dropLast(2)
            assertTrue("$tag: no starter labels in $own", own.any { it in langs })
        }
        val monaLisa = labels.filter { it.id == "Q12418" }.associateBy { it.lang }
        assertEquals("Mona Lisa", monaLisa["en"]?.label)
        assertTrue(monaLisa["ru"]?.label.orEmpty().isNotEmpty())
    }
}
