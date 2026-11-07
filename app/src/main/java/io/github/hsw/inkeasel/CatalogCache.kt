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
 * File: `{"savedAt": ms, "items": [{"id", "title", "credit", "thumbUrl", "fullUrl"}]}`.
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
     * list intact. Returns whether it was saved.
     */
    suspend fun write(sourceId: String, items: List<Picture>, now: Long): Boolean = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext false
        val f = file(sourceId)
        val tmp = File(dir, "${f.name}.tmp")
        try {
            dir.mkdirs()
            tmp.writeText(encode(CachedCatalog(now, items)))
            if (!tmp.renameTo(f)) throw IOException("cannot rename $tmp")
            true
        } catch (e: IOException) {
            tmp.delete()
            false
        }
    }

    private fun file(sourceId: String) = File(dir, "$sourceId.json")

    companion object {
        fun encode(c: CachedCatalog): String = JSONObject()
            .put("savedAt", c.savedAt)
            .put(
                "items",
                JSONArray().apply {
                    c.items.forEach { p ->
                        put(
                            JSONObject()
                                .put("id", p.id)
                                .put("title", p.title)
                                .put("credit", p.credit)
                                .put("thumbUrl", p.thumbUrl)
                                .put("fullUrl", p.fullUrl)
                        )
                    }
                },
            )
            .toString()

        /**
         * The list in [json]; `null` when it is not a cache file, has no `savedAt`, or keeps no usable item.
         * An item without `id`, `thumbUrl` or `fullUrl` is skipped; a missing title or credit is empty.
         */
        fun decode(json: String): CachedCatalog? = try {
            val root = JSONObject(json)
            val savedAt = root.optLong("savedAt", -1L)
            val arr = root.optJSONArray("items")
            if (savedAt < 0 || arr == null) {
                null
            } else {
                val items = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id")
                    val thumb = o.optString("thumbUrl")
                    val full = o.optString("fullUrl")
                    if (id.isEmpty() || thumb.isEmpty() || full.isEmpty()) return@mapNotNull null
                    Picture(id, o.optString("title"), o.optString("credit"), thumb, full)
                }
                if (items.isEmpty()) null else CachedCatalog(savedAt, items)
            }
        } catch (e: JSONException) {
            null
        }
    }
}
