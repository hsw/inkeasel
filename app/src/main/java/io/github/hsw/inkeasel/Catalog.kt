package io.github.hsw.inkeasel

import android.app.Application
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
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
    /** Stable key of the source, e.g. the [CatalogCache] file name; never shown. */
    val id: String

    /** The tab title. */
    val name: Txt
    suspend fun load(): List<Picture>
}

/** A non-2xx HTTP answer. [message] stays `HTTP <code>: <url>`: a URL upload toasts it as is. */
class HttpStatusException(val code: Int, val url: String) : IOException("HTTP $code: $url")

object Http {
    // Wikimedia asks every client for a descriptive User-Agent
    private const val UA = "InkEasel/0.1 (+https://github.com/hsw/inkeasel)"

    /** Longest wait for one catalog list request, from connect to the last byte. */
    const val LIST_TIMEOUT_S = 20L

    /** Shared by Coil and full-image downloads: big files on slow links may take long. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", UA).build()) }
        .build()

    /** Catalog lists: the same pool and User-Agent, but the whole call gives up after [LIST_TIMEOUT_S]. */
    private val listClient: OkHttpClient = client.newBuilder().callTimeout(LIST_TIMEOUT_S, TimeUnit.SECONDS).build()

    /** The body of [url]; [HttpStatusException] on a non-2xx answer. Cancelling the coroutine cancels the call. */
    suspend fun bytes(url: String): ByteArray = fetch(client, url)

    /** A catalog list as JSON, with the [LIST_TIMEOUT_S] limit. */
    suspend fun json(url: String): JSONObject {
        val body = fetch(listClient, url)
        return withContext(Dispatchers.Default) { JSONObject(String(body)) }
    }

    // enqueue instead of a blocking execute: a cancelled coroutine (a deadline, a closed screen) cancels the
    // call at once rather than waiting for its own timeout
    private suspend fun fetch(c: OkHttpClient, url: String): ByteArray = suspendCancellableCoroutine { cont ->
        val call = c.newCall(Request.Builder().url(url).build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWith(Result.failure(e))

            override fun onResponse(call: Call, response: Response) = cont.resumeWith(runCatching {
                response.use { r ->
                    if (!r.isSuccessful) throw HttpStatusException(r.code, url)
                    r.body!!.bytes()
                }
            })
        })
    }
}

/**
 * When a paged source stops asking for more: after [maxPages] pages, once [target] pictures are collected,
 * or when the source has no next page.
 */
class PageBudget(private val maxPages: Int, private val target: Int) {
    fun more(pagesDone: Int, collected: Int, hasNext: Boolean): Boolean =
        hasNext && pagesDone < maxPages && collected < target
}

/** Source text as one line: line breaks and runs of whitespace (the Cleveland API sends titles ending in "\r\n") become one space. */
fun oneLine(s: String): String = s.trim().replace(Regex("\\s+"), " ")

/** Landscape orientation that survives the crop to a landscape frame (e.g. 1024×600, 800×480) without losing most of the picture. */
private fun wide(w: Int, h: Int) = h > 0 && w.toFloat() / h in 1.25f..2.2f

/** Cleveland Museum of Art Open Access: CC0 paintings, no API key. */
object ClevelandPaintings : Source {
    override val id = "cma"
    override val name = Txt(R.string.tab_paintings)

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
                oneLine(creators.getJSONObject(0).optString("description").substringBefore(" ("))
            } else ""
            val date = oneLine(a.optString("creation_date"))
            Picture(
                id = "cma:${a.get("id")}",
                title = oneLine(a.optString("title")),
                credit = listOf(author, date).filter { it.isNotBlank() }.joinToString(", "),
                thumbUrl = web.getString("url"),
                fullUrl = full,
            )
        }
    }
}

/** Wikimedia Commons "Featured pictures of landscapes": photos under CC BY-SA and similar. */
object CommonsLandscapes : Source {
    override val id = "commons"
    override val name = Txt(R.string.tab_landscapes)

    private const val TAG = "CommonsLandscapes"

    // most featured landscapes are wide, so one page of 100 usually gives ~60–80 pictures
    private val budget = PageBudget(maxPages = 3, target = 120)

    /** The whole load, all pages together; what came in by then is shown. */
    private const val DEADLINE_MS = 30_000L

    override suspend fun load(): List<Picture> {
        val out = mutableListOf<Picture>()
        val done = withTimeoutOrNull(DEADLINE_MS) { loadPages(out) }
        if (done == null) {
            Log.w(TAG, "no full answer in ${DEADLINE_MS / 1000} s, ${out.size} pictures so far")
            if (out.isEmpty()) throw SocketTimeoutException("no answer in ${DEADLINE_MS / 1000} s")
        }
        return out
    }

    private suspend fun loadPages(out: MutableList<Picture>) {
        var cont: String? = null
        var fetched = 0
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
                fun m(k: String) = meta?.optJSONObject(k)?.optString("value").orEmpty().replace(Regex("<[^>]*>"), "").let(::oneLine)
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
            fetched++
            cont = root.optJSONObject("continue")?.optString("gcmcontinue")?.takeIf { it.isNotEmpty() }
        } while (budget.more(fetched, out.size, cont != null))
    }
}

/**
 * Pictures the user copied onto the device. Reads the app's own external files directory, which needs
 * no storage permission on Android 11 and which `adb push` can write to:
 *   /sdcard/Android/data/io.github.hsw.inkeasel/files/pictures/
 * Any JPEG/PNG/WebP/BMP/GIF dropped there shows up, newest first.
 */
class LocalImages(private val app: Application) : Source {
    override val id = "local"
    override val name = Txt(R.string.tab_local)

    companion object {
        /** [dir] as `adb push` sees it, for the hint shown while the folder is empty. */
        const val ADB_DIR = "/sdcard/Android/data/io.github.hsw.inkeasel/files/pictures/"

        /** Extensions of the pictures the folder lists (and `UploadService` resolves `photo` names with). */
        val EXTENSIONS: List<String> = listOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic")
    }

    /** The on-device folder; created on first access so `adb push` always has a target. */
    fun dir(): File = File(app.getExternalFilesDir(null), "pictures").apply { mkdirs() }

    override suspend fun load(): List<Picture> = withContext(Dispatchers.IO) {
        dir().listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }
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
