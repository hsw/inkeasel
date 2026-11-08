package io.github.hsw.inkeasel

import org.json.JSONArray
import org.json.JSONObject

/*
 * How each remote source's JSON becomes [Picture]s: pure (org.json only), so the mapping runs as JVM tests
 * against fixture answers. A field the source does not give stays null.
 */

/** Landscape orientation that survives the crop to a landscape frame (e.g. 1024×600, 800×480) without losing most of the picture. */
internal fun wide(w: Int, h: Int) = h > 0 && w.toFloat() / h in 1.25f..2.2f

/** HTML from a source as one line of plain text: tags dropped, the common entities decoded ([oneLine]). */
internal fun plainText(html: String): String = oneLine(
    html.replace(Regex("<[^>]*>"), "")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"),
)

/** An `https:` URL from what a source gives (`//host/…` protocol-relative links included); `null` for anything else. */
internal fun httpsUrl(s: String?): String? = when {
    s.isNullOrBlank() -> null
    s.startsWith("https://") -> s
    s.startsWith("//") -> "https:$s"
    else -> null
}

/** A positive pixel count from a source (Cleveland sends them as strings); `null` when missing or not one. */
private fun JSONObject.size(key: String): Int? = optInt(key, 0).takeIf { it > 0 }

/** Cleveland Museum of Art Open Access API, `api/artworks/` ([ClevelandPaintings]). */
object ClevelandMapping {
    /** The fields [picture] reads: the `fields` query parameter. */
    const val FIELDS = "id,title,creators,creation_date,images,url,share_license_status"

    /** The museum: the collection of every picture of this source. A proper name, stored as data. */
    const val COLLECTION = "Cleveland Museum of Art"

    private const val CC0_URL = "https://creativecommons.org/publicdomain/zero/1.0/"

    /** The pictures of one answer (`{"data": [...]}`); artworks without a usable landscape image are left out. */
    fun pictures(root: JSONObject): List<Picture> {
        val data = root.getJSONArray("data")
        return (0 until data.length()).mapNotNull { picture(data.getJSONObject(it)) }
    }

    /**
     * One artwork, or `null` without a `web` image or when that is not [wide]. The frame gets `print`
     * (~3400 px wide), else `web` (~1100 px); the original size is the `full` master's, else the largest
     * rendition's. The card's credit stays the first creator and the date; [Picture.artist] names all creators.
     */
    fun picture(a: JSONObject): Picture? {
        val images = a.optJSONObject("images") ?: return null
        val web = images.optJSONObject("web") ?: return null
        val webUrl = web.optString("url").takeIf { it.isNotEmpty() } ?: return null
        if (!wide(web.optInt("width"), web.optInt("height"))) return null
        val print = images.optJSONObject("print")
        val full = print?.optString("url")?.takeIf { it.isNotEmpty() } ?: webUrl
        val largest = listOfNotNull(images.optJSONObject("full"), print, web).firstOrNull { it.size("width") != null && it.size("height") != null }
        val names = creators(a.optJSONArray("creators"))
        val date = oneLine(a.optString("creation_date")).ifEmpty { null }
        val licence = a.optString("share_license_status").trim().ifEmpty { null }
        return Picture(
            id = "cma:${a.get("id")}",
            title = oneLine(a.optString("title")),
            credit = listOfNotNull(names.firstOrNull(), date).joinToString(", "),
            thumbUrl = webUrl,
            fullUrl = full,
            artist = names.joinToString(", ").ifEmpty { null },
            date = date,
            collection = COLLECTION,
            pageUrl = httpsUrl(a.optString("url")),
            licence = licence,
            licenceUrl = if (licence == "CC0") CC0_URL else null,
            width = largest?.size("width"),
            height = largest?.size("height"),
        )
    }

    /** "Claude Monet (French, 1840–1926)" → "Claude Monet", in the order given. */
    private fun creators(arr: JSONArray?): List<String> =
        if (arr == null) emptyList()
        else (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("description")?.substringBefore(" (")?.let(::oneLine)?.ifEmpty { null }
        }
}

/** Wikimedia Commons `action=query` with `prop=imageinfo` and `extmetadata` ([CommonsLandscapes]). */
object CommonsMapping {
    /** The `extmetadata` fields [picture] reads: the `iiextmetadatafilter` query parameter. */
    const val EXTMETADATA = "ObjectName|Artist|LicenseShortName|LicenseUrl|DateTimeOriginal"

    /** The width of the rendition the frame gets (`iiurlwidth`); the card gets the 500 px one. */
    const val FULL_WIDTH = 1920

    /** One page of a `generator=categorymembers` answer and its continuation token. */
    fun page(root: JSONObject): Page {
        val out = mutableListOf<Picture>()
        val pages = root.optJSONObject("query")?.optJSONArray("pages")
        if (pages != null) for (i in 0 until pages.length()) picture(pages.getJSONObject(i))?.let(out::add)
        return Page(out, root.optJSONObject("continue")?.optString("gcmcontinue")?.takeIf { it.isNotEmpty() })
    }

    /**
     * One file page, or `null` without image info or when the file is not [wide]. The title is the
     * `ObjectName`, else the file name; the date is `DateTimeOriginal` without its time of day; the web page
     * is the short `curid` link (easy to type from a TV). Commons files belong to no collection.
     */
    fun picture(p: JSONObject): Picture? {
        val info = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: return null
        val w = info.optInt("width")
        val h = info.optInt("height")
        if (!wide(w, h)) return null
        val full = info.optString("thumburl").ifEmpty { info.optString("url") }.ifEmpty { return null }
        val meta = info.optJSONObject("extmetadata")
        fun m(k: String) = plainText(meta?.optJSONObject(k)?.optString("value").orEmpty())
        val title = m("ObjectName").ifEmpty { p.optString("title").removePrefix("File:").substringBeforeLast('.') }
        val artist = m("Artist").ifEmpty { null }
        val licence = m("LicenseShortName").ifEmpty { null }
        return Picture(
            id = "commons:${p.optInt("pageid")}",
            title = title,
            credit = listOfNotNull(artist, licence).joinToString(", "),
            // Commons serves standard thumbnail widths; 500 px is plenty for a grid card
            thumbUrl = full.replace("/${FULL_WIDTH}px-", "/500px-"),
            fullUrl = full,
            artist = artist,
            date = date(m("DateTimeOriginal")),
            pageUrl = httpsUrl(info.optString("descriptionshorturl")) ?: httpsUrl(info.optString("descriptionurl")),
            licence = licence,
            licenceUrl = httpsUrl(m("LicenseUrl")),
            width = w,
            height = h,
        )
    }

    private val DATE_TIME = Regex("^(\\d{4}-\\d{2}-\\d{2})[ T]\\d{1,2}:\\d{2}.*")

    /** "2026-09-21 11:26:06" → "2026-09-21"; any other text as is; `null` when empty. */
    internal fun date(s: String): String? = s.ifEmpty { null }?.let { DATE_TIME.matchEntire(it)?.groupValues?.get(1) ?: it }
}
