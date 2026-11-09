package io.github.hsw.inkeasel.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The committed starter set (`assets/catalog/`, cut from the release by `tools/catalog-export.py
 * --starter-from`) reads with [CatalogRows] without a bad line, is in the release's order and carries labels
 * for every UI language the app has, one `labels-<lang>` file per language. Unit tests run in the module
 * directory.
 */
class StarterAssetsTest {
    private val dir = File("src/main/assets/catalog")

    private fun asset(name: String): File =
        File(dir, name).also { assertTrue("$it not found (tests run in the app module)", it.isFile) }

    private fun <T : Any> read(name: String, parse: (String) -> T): List<T> =
        JsonLinesReader(asset(name).inputStream(), parse).use { reader ->
            val rows = ArrayList<T>()
            reader.forEachBatch(500) { rows += it }
            assertEquals("$name: bad lines", 0, reader.badLines)
            rows
        }

    private val core by lazy { read("core.jsonl.gz", CatalogRows::core) }

    /** The labels by language, from the file names (`labels-<lang>.jsonl.gz`). */
    private val labels: Map<String, List<LabelRow>> by lazy {
        dir.list()!!.filter { it.startsWith("labels-") }.sorted().associate { name ->
            val lang = name.removePrefix("labels-").removeSuffix(".jsonl.gz")
            assertEquals(name, CatalogStore.assetLanguage(name.removeSuffix(".gz")), lang)
            lang to read(name, CatalogRows::label)
        }
    }

    @Test
    fun theFolderHoldsOnlyTheStarterFiles() {
        dir.list()!!.forEach { name ->
            assertTrue(name, name == "core.jsonl.gz" || name.startsWith("labels-") && name.endsWith(".jsonl.gz"))
        }
    }

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
    fun labels_belongToStarterEntitiesInPrefixOrder() {
        assertTrue(labels.isNotEmpty())
        val entities = core.flatMap { listOf(it.id) + it.creators + it.collections + it.movements + it.genres }
        val order = entities.distinct().withIndex().associate { (i, id) -> id to i }
        labels.forEach { (lang, rows) ->
            assertTrue("$lang: empty file", rows.isNotEmpty())
            // rows as in the release's labels-<lang>: the language is the file's
            rows.forEach { assertNull("$lang: ${it.id} carries a language", it.lang) }
            // every label is of a starter painting or an entity one references, in the order a prefix import needs
            val positions = rows.map { order[it.id] ?: error("$lang: label of ${it.id}, not in the starter core") }
            assertEquals(lang, positions.sorted(), positions)
            assertEquals(lang, rows.size, rows.map { it.id }.toSet().size)
        }
    }

    @Test
    fun labels_coverEveryAppLanguageWithEnglishAndMul() {
        val langs = labels.keys
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
        assertEquals("Mona Lisa", labels.getValue("en").first { it.id == "Q12418" }.label)
        assertTrue(labels.getValue("ru").first { it.id == "Q12418" }.label.orEmpty().isNotEmpty())
    }
}
