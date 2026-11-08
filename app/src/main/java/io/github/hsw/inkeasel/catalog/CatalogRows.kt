package io.github.hsw.inkeasel.catalog

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
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
)

/**
 * One row of a labels file: an entity's label, aliases and Wikipedia article title in one language.
 * [lang] is set only in the files that hold several languages (`labels-all`, the starter's `labels`); in a
 * `labels-<lang>` file it comes from the file name.
 */
data class LabelRow(
    val id: String,
    val lang: String?,
    val label: String?,
    val aliases: List<String>,
    val wikiTitle: String?,
)

/** Parses one line of a release file; a line that is not a valid row throws [JSONException] or [IllegalArgumentException]. */
object CatalogRows {
    private val ITEM = Regex("Q[1-9][0-9]*")
    private val LANG = Regex("[a-z]{2,3}(-[a-z0-9]+)*")

    /** A `core.jsonl.gz` row. Keys the app does not know are ignored; an optional key of the wrong type counts as absent. */
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
        )
    }

    /** A labels row, with or without `lang`. */
    fun label(line: String): LabelRow {
        val o = JSONObject(line)
        val lang = o.opt("lang")?.takeUnless { it == JSONObject.NULL }
            ?.let { it as? String ?: bad("lang") }
            ?.also { if (!LANG.matches(it)) bad("lang") }
        return LabelRow(
            id = item(o),
            lang = lang,
            label = o.text("l"),
            aliases = o.texts("a"),
            wikiTitle = o.text("w"),
        )
    }

    private fun item(o: JSONObject): String = o.text("id")?.takeIf { ITEM.matches(it) } ?: bad("id")

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

    private fun JSONObject.items(key: String): List<String> = texts(key).filter { ITEM.matches(it) }
}

/**
 * Reads a JSON Lines file, gzip-compressed or plain (told by its first two bytes), in batches of parsed rows,
 * so an import holds one batch at a time. A line that does not parse is skipped and counted in [badLines],
 * never aborting the file; blank lines are skipped. A broken stream (a truncated gzip, a failed download)
 * throws its [java.io.IOException]. [sha256] covers the bytes as read, compressed, so it is the hash of the
 * downloaded file.
 */
class JsonLinesReader<T : Any>(input: InputStream, private val parse: (String) -> T) : Closeable {
    private val hashing = HashingInputStream(input)
    private val raw = BufferedInputStream(hashing)
    private val lines: BufferedReader = run {
        raw.mark(2)
        val gzip = raw.read() == 0x1f && raw.read() == 0x8b
        raw.reset()
        BufferedReader(InputStreamReader(if (gzip) GZIPInputStream(raw) else raw, Charsets.UTF_8))
    }
    /** The hash, set once the last line was read. */
    private var hash: String? = null

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
            val line = lines.readLine()
            if (line == null) {
                finish()
                break
            }
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

    /**
     * The lowercase hex SHA-256 of the whole input (bytes after the last line, such as a gzip trailer,
     * included). Only after the last batch; it stays readable after [close].
     */
    fun sha256(): String = checkNotNull(hash) { "the file is not read to its end" }

    private fun finish() {
        if (hash != null) return
        val buf = ByteArray(8192)
        while (raw.read(buf) >= 0) Unit
        hash = hashing.digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Bytes of the input read so far: all of it once the last batch was read. */
    val bytes: Long get() = hashing.count

    override fun close() = lines.close()
}

/** Hashes and counts every byte read through it. */
private class HashingInputStream(input: InputStream) : FilterInputStream(input) {
    val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    var count = 0L
        private set

    override fun read(): Int = super.read().also {
        if (it >= 0) {
            digest.update(it.toByte())
            count++
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
        if (it > 0) {
            digest.update(b, off, it)
            count += it
        }
    }

    /** Skipped bytes are read, so they are hashed too. */
    override fun skip(n: Long): Long {
        val buf = ByteArray(minOf(n, 8192L).toInt().coerceAtLeast(1))
        var left = n
        while (left > 0) {
            val r = read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) break
            left -= r
        }
        return n - left
    }

    override fun markSupported() = false
}
