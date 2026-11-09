package io.github.hsw.inkeasel.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

/** Rows copied from the real `catalog` release (the `.jsonl` files in the test resources' `catalog` folder). */
class CatalogRowsTest {
    private fun fixture(name: String): ByteArray = javaClass.getResource("/catalog/$name")!!.readBytes()

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun <T : Any> readAll(bytes: ByteArray, parse: (String) -> T, batch: Int = 100): Pair<List<List<T>>, JsonLinesReader<T>> {
        val reader = JsonLinesReader(ByteArrayInputStream(bytes), parse)
        val batches = mutableListOf<List<T>>()
        reader.use { it.forEachBatch(batch) { b -> batches += b } }
        return batches to reader
    }

    private fun core() = readAll(fixture("core.jsonl"), CatalogRows::core).first.flatten()

    @Test
    fun core_monaLisaHasEveryField() {
        assertEquals(
            CoreRow(
                id = "Q12418",
                fame = 146,
                commonsFile = "Mona Lisa, by Leonardo da Vinci, from C2RMF retouched.jpg",
                width = 7479,
                height = 11146,
                licence = "Public domain",
                licenceUrl = null,
                attributionRequired = false,
                credit = "Leonardo da Vinci",
                creators = listOf("Q762"),
                inception = 1503,
                collections = listOf("Q3044768"),
                movements = listOf("Q1404472"),
                genres = listOf("Q134307"),
                cma = null,
                nsfw = null,
            ),
            core()[0],
        )
    }

    @Test
    fun core_keepsTheFileOrderAndOddFileNames() {
        val rows = core()
        assertEquals(listOf("Q12418", "Q45585", "Q609572", "Q595707", "Q3724913"), rows.map { it.id })
        assertEquals(listOf(146, 77, 18, 14, 13), rows.map { it.fame })
        // a TIFF with non-ASCII characters
        assertEquals(
            "Paul Gauguin - Manaò tupapaú (Spirit of the Dead Watching) - 1965-1 - Albright–Knox Art Gallery.tiff",
            rows[2].commonsFile,
        )
        assertEquals(listOf("Q40446", "Q1047337", "Q134307"), rows[2].genres)
        // a name over 160 bytes, with commas and quotes
        assertTrue(rows[3].commonsFile.toByteArray().size > 160)
        assertTrue(rows[3].commonsFile.contains("(\"Laura\")"))
        assertEquals(listOf("Q610687", "Q1474884"), rows[3].movements)
    }

    @Test
    fun core_nullInceptionEmptyListsAndAttribution() {
        val row = core()[4]
        assertNull(row.inception)
        assertEquals(emptyList<String>(), row.collections)
        assertEquals(emptyList<String>(), row.movements)
        assertEquals(emptyList<String>(), row.genres)
        assertTrue(row.attributionRequired)
        assertEquals("CC BY-SA 4.0", row.licence)
        assertEquals("Françoise Foliot", row.credit)
    }

    @Test
    fun core_optionalClevelandIdAndLicenceUrl() {
        val row = CatalogRows.core(
            """{"id":"Q1","fame":3,"img":"A.jpg","w":10,"h":20,"lic":"CC BY 4.0","licUrl":"https://creativecommons.org/licenses/by/4.0/","attr":true,"cma":"1915.534","creators":[],"inception":-200}""",
        )
        assertEquals("https://creativecommons.org/licenses/by/4.0/", row.licenceUrl)
        assertEquals("1915.534", row.cma)
        assertEquals(-200, row.inception)
        assertNull(row.credit)
    }

    @Test
    fun core_nsfwIsReadAsWritten() {
        fun nsfw(value: String) = CatalogRows.core("""{"id":"Q1","fame":3,"img":"A.jpg","w":10,"h":20,"nsfw":$value}""").nsfw
        assertEquals("explicit", nsfw("\"explicit\""))
        assertEquals("nude", nsfw("\"nude\""))
        assertEquals("later", nsfw("\"later\"")) // an unknown value is kept; Nudity.levelOf ignores it
        assertNull(nsfw("\"\""))
        assertNull(nsfw("null"))
        assertNull(core()[0].nsfw) // the published release has no such field yet
    }

    @Test
    fun core_unknownKeysAndWrongOptionalTypesAreIgnored() {
        val row = CatalogRows.core(
            """{"new":{"x":[1]},"id":"Q1","fame":3,"img":"A.jpg","w":10,"h":20,"lic":null,"attr":"yes","credit":7,"creators":"Q2","inception":"1500","collections":["Q3",5,"","x"],"cma":1915,"nsfw":2}""",
        )
        assertEquals(
            CoreRow("Q1", 3, "A.jpg", 10, 20, null, null, false, null, emptyList(), null, listOf("Q3"), emptyList(), emptyList(), null, null),
            row,
        )
    }

    @Test
    fun core_missingOrBadRequiredFieldsAreBadRows() {
        val good = """"id":"Q1","fame":3,"img":"A.jpg","w":10,"h":20"""
        CatalogRows.core("{$good}")
        for (line in listOf(
            """{"fame":3,"img":"A.jpg","w":10,"h":20}""",
            """{"id":"L1","fame":3,"img":"A.jpg","w":10,"h":20}""",
            """{"id":"Q01","fame":3,"img":"A.jpg","w":10,"h":20}""",
            """{"id":"Q1","img":"A.jpg","w":10,"h":20}""",
            """{"id":"Q1","fame":-1,"img":"A.jpg","w":10,"h":20}""",
            """{"id":"Q1","fame":3.5,"img":"A.jpg","w":10,"h":20}""",
            """{"id":"Q1","fame":3,"img":"","w":10,"h":20}""",
            """{"id":"Q1","fame":3,"img":"A.jpg","w":0,"h":20}""",
            """{"id":"Q1","fame":3,"img":"A.jpg","w":10,"h":99999999999}""",
        )) {
            val (batches, reader) = readAll(line.toByteArray(), CatalogRows::core)
            assertEquals(line, emptyList<CoreRow>(), batches.flatten())
            assertEquals(line, 1, reader.badLines)
        }
    }

    @Test
    fun labels_ruAndEnWithEntities() {
        val ru = readAll(fixture("labels-ru.jsonl"), CatalogRows::label).first.flatten()
        assertEquals(LabelRow("Q12418", "Мона Лиза", listOf("Джоконда"), "Мона Лиза"), ru[0])
        assertEquals(LabelRow("Q762", "Леонардо да Винчи", emptyList(), "Леонардо да Винчи"), ru[1])
        // an entity label (a collection) with neither aliases nor an article
        assertEquals(LabelRow("Q3044768", "Отдел живописи Лувра", emptyList(), null), ru[2])
        val en = readAll(fixture("labels-en.jsonl"), CatalogRows::label).first.flatten()
        assertEquals(listOf("Q12418", "Q762", "Q3044768", "Q609572"), en.map { it.id })
        assertEquals(
            LabelRow(
                "Q3044768", "Department of Paintings of the Louvre",
                listOf("Louvre paintings department", "Louvre department of paintings", "Louvre Department of Paintings"),
                null,
            ),
            en[2],
        )
    }

    @Test
    fun labels_aLangKeyIsIgnored() {
        // the tool's labels-all file carries one; a labels-<lang> file takes its language from its name
        val all = readAll(fixture("labels-all.jsonl"), CatalogRows::label).first.flatten()
        assertEquals(LabelRow("Q12418", "Mona Lisa", listOf("La Joconde", "La Gioconda", "Monna Lisa"), null), all[0])
        assertEquals(LabelRow("Q1", "x", emptyList(), null), CatalogRows.label("""{"lang":5,"id":"Q1","l":"x"}"""))
    }

    @Test
    fun labels_onlyAnIdIsStillARow() {
        assertEquals(LabelRow("Q5", null, emptyList(), null), CatalogRows.label("""{"id":"Q5","l":"","x":1}"""))
    }

    @Test
    fun labels_badIdIsABadRow() {
        for (line in listOf(
            """{"l":"x"}""",
            """{"id":"P31","l":"x"}""",
        )) {
            val (batches, reader) = readAll(line.toByteArray(), CatalogRows::label)
            assertEquals(line, 0, batches.flatten().size)
            assertEquals(line, 1, reader.badLines)
        }
    }

    @Test
    fun reader_batchesOfN() {
        val (batches, reader) = readAll(fixture("core.jsonl"), CatalogRows::core, batch = 2)
        assertEquals(listOf(2, 2, 1), batches.map { it.size })
        assertEquals(5, reader.rows)
        assertEquals(0, reader.badLines)
    }

    @Test
    fun reader_exactMultipleEndsWithoutAnEmptyBatch() {
        val (batches, _) = readAll(fixture("labels-ru.jsonl"), CatalogRows::label, batch = 2)
        assertEquals(listOf(2, 2), batches.map { it.size })
    }

    @Test
    fun reader_gzipGivesTheSameRows() {
        val plain = fixture("core.jsonl")
        val (fromPlain, _) = readAll(plain, CatalogRows::core)
        val (fromGzip, gzipReader) = readAll(gzip(plain), CatalogRows::core, batch = 3)
        assertEquals(fromPlain.flatten(), fromGzip.flatten())
        assertEquals(5, gzipReader.rows)
    }

    @Test
    fun reader_malformedLinesAreSkippedAndCounted() {
        val lines = fixture("labels-en.jsonl").decodeToString().lines().filter { it.isNotEmpty() }
        val mixed = listOf(
            lines[0],
            "{\"id\":\"Q762\",\"l\":",
            "not json",
            "",
            "   ",
            "[1,2]",
            lines[1],
            "null",
            lines[2],
        ).joinToString("\n")
        val (batches, reader) = readAll(gzip(mixed.toByteArray()), CatalogRows::label, batch = 2)
        assertEquals(listOf("Q12418", "Q762", "Q3044768"), batches.flatten().map { it.id })
        assertEquals(3, reader.rows)
        // blank lines are not counted as bad
        assertEquals(4, reader.badLines)
    }

    @Test
    fun reader_emptyFile() {
        for (bytes in listOf(ByteArray(0), gzip(ByteArray(0)), "\n\n".toByteArray())) {
            val (batches, reader) = readAll(bytes, CatalogRows::core)
            assertEquals(emptyList<List<CoreRow>>(), batches)
            assertEquals(0, reader.rows)
            assertEquals(0, reader.badLines)
        }
    }

    @Test
    fun reader_withoutTrailingNewlineAndWithCrLf() {
        val text = fixture("labels-ru.jsonl").decodeToString().trimEnd().replace("\n", "\r\n")
        val (batches, reader) = readAll(text.toByteArray(), CatalogRows::label)
        assertEquals(4, batches.flatten().size)
        assertEquals(0, reader.badLines)
    }

    @Test
    fun reader_truncatedGzipThrows() {
        val gz = gzip(fixture("core.jsonl"))
        val reader = JsonLinesReader(ByteArrayInputStream(gz.copyOf(gz.size / 2)), CatalogRows::core)
        val thrown = runCatching { reader.use { it.forEachBatch(100) {} } }.exceptionOrNull()
        assertTrue(thrown.toString(), thrown is IOException)
    }

    @Test
    fun reader_closesItsInputWhenItCannotBeBuilt() {
        // a gzip magic followed by a broken header: the constructor throws, and the stream is closed all the same
        var closed = false
        val input = object : ByteArrayInputStream(byteArrayOf(0x1f, 0x8b.toByte(), 0, 0)) {
            override fun close() {
                closed = true
            }
        }
        assertTrue(runCatching { JsonLinesReader(input, CatalogRows::core) }.exceptionOrNull() is IOException)
        assertTrue(closed)
    }

    @Test
    fun itemAndLanguageChecks() {
        listOf("Q1", "Q12418", "Q9999999999").forEach { assertTrue(it, CatalogRows.isItem(it)) }
        listOf("", "Q", "Q0", "Q01", "q1", "P31", "Q1a", " Q1").forEach { assertFalse(it, CatalogRows.isItem(it)) }
        listOf("ru", "mul", "pt-br", "zh-hant", "de-1901", "be-tarask").forEach { assertTrue(it, CatalogRows.isLang(it)) }
        listOf("", "r", "abcd", "RU", "ru-", "ru--x", "-ru", "ru_RU", "pt-BR", "1a").forEach { assertFalse(it, CatalogRows.isLang(it)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun reader_batchSizeMustBePositive() {
        JsonLinesReader(ByteArrayInputStream(ByteArray(0)), CatalogRows::core).use { it.nextBatch(0) }
    }
}
