package io.github.hsw.inkeasel

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One picture in the catalog. [thumbUrl] is for the grid, [fullUrl] is loaded for the frame.
 * When [local] is true both are absolute file paths on the device, read from disk instead of HTTP.
 */
data class Picture(
    val id: String,
    val title: String,
    val credit: String,
    val thumbUrl: String,
    val fullUrl: String,
    val local: Boolean = false,
) {
    /** What to hand Coil for the grid card: a [File] for local pictures, the URL string otherwise. */
    fun thumbModel(): Any = if (local) File(thumbUrl) else thumbUrl

    /** The full-resolution bytes for the frame. */
    suspend fun fullBytes(): ByteArray =
        if (local) withContext(Dispatchers.IO) { File(fullUrl).readBytes() } else Http.bytes(fullUrl)
}

interface Source {
    val name: String
    suspend fun load(): List<Picture>
}

object Http {
    // Wikimedia asks every client for a descriptive User-Agent
    private const val UA = "InkEasel/0.1 (+https://github.com/hsw/inkeasel)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", UA).build()) }
        .build()

    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: $url")
            r.body!!.bytes()
        }
    }

    suspend fun json(url: String): JSONObject = JSONObject(String(bytes(url)))
}

/** Landscape orientation that survives the crop to a landscape frame (e.g. 1024×600, 800×480) without losing most of the picture. */
private fun wide(w: Int, h: Int) = h > 0 && w.toFloat() / h in 1.25f..2.2f

/** Cleveland Museum of Art Open Access: CC0 paintings, no API key. */
object ClevelandPaintings : Source {
    override val name = "Картины"

    override suspend fun load(): List<Picture> {
        val url = "https://openaccess-api.clevelandart.org/api/artworks/".toHttpUrl().newBuilder()
            .addQueryParameter("q", "landscape")
            .addQueryParameter("type", "Painting")
            .addQueryParameter("has_image", "1")
            .addQueryParameter("cc0", "1")
            .addQueryParameter("limit", "200")
            .addQueryParameter("fields", "id,title,creators,creation_date,images")
            .build().toString()
        val data = Http.json(url).getJSONArray("data")
        return (0 until data.length()).mapNotNull { i ->
            val a = data.getJSONObject(i)
            val images = a.optJSONObject("images") ?: return@mapNotNull null
            val web = images.optJSONObject("web") ?: return@mapNotNull null
            if (!wide(web.optInt("width"), web.optInt("height"))) return@mapNotNull null
            // "print" is ~3400 px wide; fall back to "web" (~1100 px) when it is missing
            val full = images.optJSONObject("print")?.optString("url")?.takeIf { it.isNotEmpty() } ?: web.getString("url")
            val creators = a.optJSONArray("creators")
            val author = if (creators != null && creators.length() > 0) {
                creators.getJSONObject(0).optString("description").substringBefore(" (")
            } else ""
            val date = a.optString("creation_date")
            Picture(
                id = "cma:${a.get("id")}",
                title = a.optString("title"),
                credit = listOf(author, date).filter { it.isNotBlank() }.joinToString(", "),
                thumbUrl = web.getString("url"),
                fullUrl = full,
            )
        }
    }
}

/** Wikimedia Commons "Featured pictures of landscapes": photos under CC BY-SA and similar. */
object CommonsLandscapes : Source {
    override val name = "Пейзажи"

    override suspend fun load(): List<Picture> {
        val out = mutableListOf<Picture>()
        var cont: String? = null
        do {
            val b = "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("generator", "categorymembers")
                .addQueryParameter("gcmtitle", "Category:Featured_pictures_of_landscapes")
                .addQueryParameter("gcmtype", "file")
                .addQueryParameter("gcmlimit", "100")
                .addQueryParameter("gcmsort", "timestamp")
                .addQueryParameter("gcmdir", "desc")
                .addQueryParameter("prop", "imageinfo")
                .addQueryParameter("iiprop", "url|size|extmetadata")
                .addQueryParameter("iiextmetadatafilter", "ObjectName|Artist|LicenseShortName")
                .addQueryParameter("iiurlwidth", "1920")
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
            cont?.let { b.addQueryParameter("gcmcontinue", it).addQueryParameter("continue", "gcmcontinue||") }
            val root = Http.json(b.build().toString())
            val pages = root.optJSONObject("query")?.optJSONArray("pages")
            if (pages != null) for (i in 0 until pages.length()) {
                val p = pages.getJSONObject(i)
                val info = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                if (!wide(info.optInt("width"), info.optInt("height"))) continue
                val full = info.optString("thumburl").ifEmpty { info.getString("url") }
                val meta = info.optJSONObject("extmetadata")
                fun m(k: String) = meta?.optJSONObject(k)?.optString("value").orEmpty().replace(Regex("<[^>]*>"), "").trim()
                val title = m("ObjectName").ifEmpty { p.getString("title").removePrefix("File:").substringBeforeLast('.') }
                out += Picture(
                    id = "commons:${p.optInt("pageid")}",
                    title = title,
                    credit = listOf(m("Artist"), m("LicenseShortName")).filter { it.isNotBlank() }.joinToString(", "),
                    // Commons serves standard thumbnail widths; 500 px is plenty for a grid card
                    thumbUrl = full.replace("/1920px-", "/500px-"),
                    fullUrl = full,
                )
            }
            cont = root.optJSONObject("continue")?.optString("gcmcontinue")?.takeIf { it.isNotEmpty() }
        } while (cont != null && out.size < 120)
        return out
    }
}

/**
 * Pictures the user copied onto the device. Reads the app's own external files directory, which needs
 * no storage permission on Android 11 and which `adb push` can write to:
 *   /sdcard/Android/data/io.github.hsw.inkeasel/files/pictures/
 * Any JPEG/PNG/WebP/BMP/GIF dropped there shows up, newest first.
 */
class LocalImages(private val app: Application) : Source {
    override val name = "Мои картинки"

    /** The on-device folder; created on first access so `adb push` always has a target. */
    fun dir(): File = File(app.getExternalFilesDir(null), "pictures").apply { mkdirs() }

    override suspend fun load(): List<Picture> = withContext(Dispatchers.IO) {
        val exts = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
        dir().listFiles { f -> f.isFile && f.extension.lowercase() in exts }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                Picture(
                    id = "local:${f.name}",
                    title = f.nameWithoutExtension,
                    credit = "",
                    thumbUrl = f.absolutePath,
                    fullUrl = f.absolutePath,
                    local = true,
                )
            } ?: emptyList()
    }
}

/** The catalog tabs, in order. [LocalImages] needs a context, so the list is built per-app. */
fun buildSources(app: Application): List<Source> =
    listOf(LocalImages(app), ClevelandPaintings, CommonsLandscapes)
