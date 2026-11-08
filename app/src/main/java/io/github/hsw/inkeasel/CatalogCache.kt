package io.github.hsw.inkeasel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** The last good list of a remote source, and when it was saved (wall clock, ms). */
data class CachedCatalog(val savedAt: Long, val items: List<Picture>)

/**
 * The last good (non-empty) list per remote catalog source, one JSON file per [Source.id] in [dir]
 * (`filesDir/catalog/`), so a source that is down still shows what it showed last time. Free of Android
 * types; file I/O runs on [Dispatchers.IO].
 *
 * File (format 2): `{"format": 2, "savedAt": ms, "items": [{"id", "title", "credit", "thumbUrl", "fullUrl",
 * "artist"?, "date"?, "collection"?, "pageUrl"?, "licence"?, "licenceUrl"?, "width"?, "height"?}]}`, a field
 * the picture does not have left out. Format 1 (no `format`, only the first five fields) still reads, with the
 * rest `null`: it is only a fallback, and the next good load of the source rewrites it in format 2. A newer
 * format than [FORMAT] (after a downgrade) reads as no cache.
 */
class CatalogCache(private val dir: File) {
    /** The saved list of [sourceId]; `null` when there is none or the file cannot be read. */
    suspend fun read(sourceId: String): CachedCatalog? = withContext(Dispatchers.IO) {
        val f = file(sourceId)
        if (!f.isFile) return@withContext null
        try {
            decode(f.readText())
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Save [items] as the list of [sourceId]. An empty list is not saved, so it never replaces a good one.
     * Written to a temporary file first and renamed over the old one, so a crash mid-write leaves the old
     * list intact; a failed write leaves it too.
     */
    suspend fun write(sourceId: String, items: List<Picture>, now: Long): Unit = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        val f = file(sourceId)
        val tmp = File(dir, "${f.name}.tmp")
        try {
            dir.mkdirs()
            tmp.writeText(encode(CachedCatalog(now, items)))
            if (!tmp.renameTo(f)) throw IOException("cannot rename $tmp")
        } catch (e: IOException) {
            tmp.delete()
        }
    }

    private fun file(sourceId: String) = File(dir, "$sourceId.json")

    companion object {
        /** The format [encode] writes; [decode] reads it and every older one. */
        const val FORMAT = 2

        fun encode(c: CachedCatalog): String = JSONObject()
            .put("format", FORMAT)
            .put("savedAt", c.savedAt)
            .put("items", JSONArray().apply { c.items.forEach { put(item(it)) } })
            .toString()

        private fun item(p: Picture): JSONObject = JSONObject()
            .put("id", p.id)
            .put("title", p.title)
            .put("credit", p.credit)
            .put("thumbUrl", p.thumbUrl)
            .put("fullUrl", p.fullUrl)
            .apply {
                p.artist?.let { put("artist", it) }
                p.date?.let { put("date", it) }
                p.collection?.let { put("collection", it) }
                p.pageUrl?.let { put("pageUrl", it) }
                p.licence?.let { put("licence", it) }
                p.licenceUrl?.let { put("licenceUrl", it) }
                p.width?.let { put("width", it) }
                p.height?.let { put("height", it) }
            }

        /**
         * The list in [json] as [encode] (or an older format) wrote it; `null` when it is anything else, a newer
         * format, or has no item.
         */
        fun decode(json: String): CachedCatalog? = try {
            val root = JSONObject(json)
            if (root.optInt("format", 1) !in 1..FORMAT) null
            else {
                val arr = root.getJSONArray("items")
                val items = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    fun str(k: String) = if (o.has(k)) o.getString(k) else null
                    fun int(k: String) = if (o.has(k)) o.getInt(k) else null
                    Picture(
                        o.getString("id"), o.getString("title"), o.getString("credit"), o.getString("thumbUrl"), o.getString("fullUrl"),
                        artist = str("artist"),
                        date = str("date"),
                        collection = str("collection"),
                        pageUrl = str("pageUrl"),
                        licence = str("licence"),
                        licenceUrl = str("licenceUrl"),
                        width = int("width"),
                        height = int("height"),
                    )
                }
                if (items.isEmpty()) null else CachedCatalog(root.getLong("savedAt"), items)
            }
        } catch (e: JSONException) {
            null
        }
    }
}
