package io.github.hsw.inkeasel.catalog

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/*
 * The rows of the `catalog` release files written by `tools/catalog-export.py` (its docstring is the format):
 * pure (org.json only), so the parsing runs as JVM tests against rows copied from a real release. Ids are
 * Wikidata ids as the files carry them (`Q12418`); the app's `wd:` prefix is added where rows become pictures.
 */

/** One painting of `core.jsonl.gz`; the lists hold Wikidata ids, in the file's order. */
data class CoreRow(
    val id: String,
    /** Sitelinks: the number of Wikipedia and other Wikimedia articles about the painting. */
    val fame: Int,
    /** The Commons file name (`img`), spaces as in the file. */
    val commonsFile: String,
    val width: Int,
    val height: Int,
    /** The licence's short name (`lic`), e.g. `Public domain`, `CC BY-SA 4.0`. */
    val licence: String?,
    /** The licence's deed URL (`licUrl`), when the release carries it. */
    val licenceUrl: String?,
    /** `attr`: the licence asks for attribution wherever the picture is shown. */
    val attributionRequired: Boolean,
    /** `credit`: the author or credit text from Commons, plain. */
    val credit: String?,
    val creators: List<String>,
    /** `inception`: the year the painting was made (negative before the common era). */
    val inception: Int?,
    val collections: List<String>,
    val movements: List<String>,
    val genres: List<String>,
    /** `cma`: the Cleveland Museum of Art ID (Wikidata P11110), an accession number. */
    val cma: String?,
    /** `nsfw`: the release's nudity level (`nude`, `explicit`), as written; [Nudity.levelOf] reads it. */
    val nsfw: String?,
)

/**
 * One row of a `labels-<lang>` file: an entity's label, aliases and Wikipedia article title in that file's
 * language (a `lang` key, which only the tool's `labels-all` file carries, is ignored).
 */
data class LabelRow(
    val id: String,
    val label: String?,
    val aliases: List<String>,
    val wikiTitle: String?,
)

/**
 * Parses one line of a release file; a line that is not a valid row throws [JSONException] or
 * [IllegalArgumentException]. The checks of ids and language codes are plain loops, not regular expressions:
 * an import checks tens of thousands of them, and a regex match allocates a matcher each time.
 */
object CatalogRows {
    /**
     * A `core.jsonl.gz` row. Keys the app does not know are ignored; an optional key of the wrong type counts
     * as absent.
     */
    fun core(line: String): CoreRow {
        val o = JSONObject(line)
        return CoreRow(
            id = item(o),
            fame = o.int("fame")?.takeIf { it >= 0 } ?: bad("fame"),
            commonsFile = o.text("img") ?: bad("img"),
            width = o.int("w")?.takeIf { it > 0 } ?: bad("w"),
            height = o.int("h")?.takeIf { it > 0 } ?: bad("h"),
            licence = o.text("lic"),
            licenceUrl = o.text("licUrl"),
            attributionRequired = o.opt("attr") == true,
            credit = o.text("credit"),
            creators = o.items("creators"),
            inception = o.int("inception"),
            collections = o.items("collections"),
            movements = o.items("movements"),
            genres = o.items("genres"),
            cma = o.text("cma"),
            nsfw = o.text("nsfw"),
        )
    }

    /** A labels row. */
    fun label(line: String): LabelRow {
        val o = JSONObject(line)
        return LabelRow(
            id = item(o),
            label = o.text("l"),
            aliases = o.texts("a"),
            wikiTitle = o.text("w"),
        )
    }

    /** A Wikidata item id: `Q` and a number without leading zeros (`Q12418`). */
    fun isItem(s: String): Boolean {
        if (s.length < 2 || s[0] != 'Q' || s[1] !in '1'..'9') return false
        for (i in 2 until s.length) if (s[i] !in '0'..'9') return false
        return true
    }

    /** A Wikidata language code: 2 or 3 lowercase letters, then any `-` parts of lowercase letters and digits. */
    fun isLang(s: String): Boolean {
        var i = 0
        while (i < s.length && s[i] in 'a'..'z') i++
        if (i !in 2..3) return false
        while (i < s.length) {
            if (s[i] != '-') return false
            val start = ++i
            while (i < s.length && (s[i] in 'a'..'z' || s[i] in '0'..'9')) i++
            if (i == start) return false
        }
        return true
    }

    private fun item(o: JSONObject): String = o.text("id")?.takeIf(::isItem) ?: bad("id")

    private fun bad(key: String): Nothing = throw IllegalArgumentException("bad or missing \"$key\"")

    private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotEmpty() }

    /** An integral JSON number in the `Int` range; anything else (a fraction, a string, null) is `null`. */
    private fun JSONObject.int(key: String): Int? = when (val v = opt(key)) {
        is Int -> v
        is Long -> v.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }

    private fun JSONObject.texts(key: String): List<String> {
        val a = opt(key) as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.takeIf { s -> s.isNotEmpty() } }
    }

    private fun JSONObject.items(key: String): List<String> = texts(key).filter(::isItem)
}

/**
 * Reads a JSON Lines file, gzip-compressed or plain (told by its first two bytes), in batches of parsed rows,
 * so an import holds one batch at a time. A line that does not parse is skipped and counted in [badLines],
 * never aborting the file; blank lines are skipped. A broken stream (a truncated gzip, a failed download)
 * throws its [java.io.IOException]. It owns [input]: closed by [close], or at once when the reader cannot be
 * built (a broken gzip header).
 */
class JsonLinesReader<T : Any>(input: InputStream, private val parse: (String) -> T) : Closeable {
    private val lines: BufferedReader = try {
        val raw = BufferedInputStream(input)
        raw.mark(2)
        val gzip = raw.read() == 0x1f && raw.read() == 0x8b
        raw.reset()
        BufferedReader(InputStreamReader(if (gzip) GZIPInputStream(raw) else raw, Charsets.UTF_8))
    } catch (e: Throwable) {
        runCatching { input.close() }
        throw e
    }

    /** Rows parsed so far. */
    var rows = 0
        private set

    /** Lines skipped because they did not parse. */
    var badLines = 0
        private set

    /** Up to [max] rows; an empty list at the end of the file. */
    fun nextBatch(max: Int): List<T> {
        require(max > 0) { "batch size $max" }
        val batch = ArrayList<T>(minOf(max, 1024))
        while (batch.size < max) {
            val line = lines.readLine() ?: break
            if (line.isBlank()) continue
            try {
                batch += parse(line)
                rows++
            } catch (_: JSONException) {
                badLines++
            } catch (_: IllegalArgumentException) {
                badLines++
            }
        }
        return batch
    }

    /** Runs [block] on every batch of up to [max] rows until the end of the file. */
    inline fun forEachBatch(max: Int, block: (List<T>) -> Unit) {
        while (true) {
            val batch = nextBatch(max)
            if (batch.isEmpty()) return
            block(batch)
        }
    }

    override fun close() = lines.close()
}
