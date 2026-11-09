package io.github.hsw.inkeasel

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Looper
import android.util.Log
import io.github.hsw.inkeasel.catalog.LiveRow
import io.github.hsw.inkeasel.catalog.LiveSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
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
 * [title] and [credit] are the card's two lines. The rest is what the source tells about the picture, each
 * `null` when it does not (the details panel, [PictureDetails]); the names follow the planned one-catalog
 * schema (docs/decisions.md, "One Catalog section"): [artist] (all creators, as one text), [date] (as the
 * source writes it), [collection] (the museum that holds it), [pageUrl] (the picture's page at its source),
 * [licence] (short name, e.g. "CC0", "CC BY-SA 4.0") and [licenceUrl], and the original's [width] × [height]
 * in pixels. [commonsFile] is the Commons file name its renditions are built from
 * ([io.github.hsw.inkeasel.catalog.CommonsUrls]), [attribution] the credit its licence asks to show wherever
 * it is shown (`null` when it asks for none). Never translated text.
 */
data class Picture(
    val id: String,
    val title: String,
    val credit: String,
    val thumbUrl: String,
    val fullUrl: String,
    val local: Boolean = false,
    val artist: String? = null,
    val date: String? = null,
    val collection: String? = null,
    val pageUrl: String? = null,
    val licence: String? = null,
    val licenceUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val commonsFile: String? = null,
    val attribution: String? = null,
) {
    /** The source the picture came from: the prefix of its [id] ("wd", "cma", "commons", "local"). */
    val source: String get() = id.substringBefore(':')

    /** What to hand Coil for the grid card: a [File] for local pictures, the URL string otherwise. */
    fun thumbModel(): Any = if (local) File(thumbUrl) else thumbUrl
}

/**
 * What a source returned: its [items], and whether that is its whole list ([complete] false: cut short by a
 * deadline or a failed later page, see [collectPages]).
 */
data class Listing<T>(val items: List<T>, val complete: Boolean = true)

/**
 * A tab that loads its whole list ([readLocal]): "My pictures" ([LocalImages]). The live sources are rows of
 * the Catalog ([liveSources]).
 */
interface Source {
    /** Stable key of the source; never shown. */
    val id: String

    /** The tab title. */
    val name: Txt

    suspend fun load(): Listing<Picture>
}

/** A non-2xx HTTP answer. [message] stays `HTTP <code>: <url>`: a URL upload toasts it as is. */
class HttpStatusException(val code: Int, val url: String) : IOException("HTTP $code: $url")

object Http {
    // Wikimedia asks every client for a descriptive User-Agent
    private const val UA = "InkEasel/0.1 (+https://github.com/hsw/inkeasel)"

    /**
     * Longest wait for one list request of a live source, from connect to the last byte: generous, since the
     * lists load in the background while the catalog is already on screen ([LiveSource]).
     */
    private const val LIST_TIMEOUT_S = 60L

    /** At most this many requests to one host at a time (OkHttp's default is 5): Wikimedia asks for few. */
    const val MAX_REQUESTS_PER_HOST = 4

    /**
     * Shared by Coil, full-image downloads, the release sync and the live lists: big files on slow links may
     * take long. A 429 or 503 answer is asked again after a short wait ([RetryInterceptor]), never on the
     * main thread.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", UA).build()) }
        .addInterceptor(RetryInterceptor(mayWait = { !Looper.getMainLooper().isCurrentThread }))
        .build()

    /** Catalog lists: the same pool and User-Agent, but the whole call gives up after [LIST_TIMEOUT_S]. */
    private val listClient: OkHttpClient = client.newBuilder().callTimeout(LIST_TIMEOUT_S, TimeUnit.SECONDS).build()

    /** The body of [url]; [HttpStatusException] on a non-2xx answer. Cancelling the coroutine cancels the call. */
    suspend fun bytes(url: String): ByteArray = fetch(client, url)

    /** A live source's list as JSON, with the [LIST_TIMEOUT_S] limit. */
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

/** One page of a paged source: its [items] and the token of the next page (`null`: the last one). */
class Page<T>(val items: List<T>, val next: String?)

/**
 * Collect the pages of a paged source: [fetch] gets one (`null` token: the first) until there is no next page,
 * [maxPages] are in or [target] pictures are collected, all within [deadlineMs]. A list cut short is still
 * returned, marked incomplete ([Listing.complete]): by the deadline, or by a failure on a later page once some
 * pictures came. With nothing collected, the deadline is a [SocketTimeoutException] and a failure is rethrown.
 * Pure: [log] is injected.
 */
suspend fun <T> collectPages(
    maxPages: Int,
    target: Int,
    deadlineMs: Long,
    log: (String) -> Unit = {},
    fetch: suspend (String?) -> Page<T>,
): Listing<T> {
    val out = mutableListOf<T>()
    val complete = withTimeoutOrNull(deadlineMs) {
        var next: String? = null
        var pages = 0
        do {
            val page = try {
                fetch(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (out.isEmpty()) throw e
                log("page ${pages + 1} failed, ${out.size} pictures so far: $e")
                return@withTimeoutOrNull false
            }
            out += page.items
            pages++
            next = page.next
        } while (next != null && pages < maxPages && out.size < target)
        true
    }
    if (complete == null) {
        log("no full answer in ${deadlineMs / 1000} s, ${out.size} pictures so far")
        if (out.isEmpty()) throw SocketTimeoutException("no answer in ${deadlineMs / 1000} s")
    }
    return Listing(out, complete == true)
}

/** Source text as one line: line breaks and runs of whitespace (the Cleveland API sends titles ending in "\r\n") become one space. */
fun oneLine(s: String): String = s.trim().replace(Regex("\\s+"), " ")

/**
 * Cleveland Museum of Art Open Access: CC0 landscape paintings, no API key. A live source of the catalog
 * ([LiveSource]): its rows are `cma:<api id>` with the accession number as [LiveRow.museumRef].
 */
object ClevelandPaintings : LiveSource {
    override val id = "cma"

    override suspend fun fetch(): Listing<LiveRow> {
        val url = "https://openaccess-api.clevelandart.org/api/artworks/".toHttpUrl().newBuilder()
            .addQueryParameter("q", "landscape")
            .addQueryParameter("type", "Painting")
            .addQueryParameter("has_image", "1")
            .addQueryParameter("cc0", "1")
            .addQueryParameter("limit", "200")
            .addQueryParameter("fields", ClevelandMapping.FIELDS)
            .build().toString()
        return Listing(ClevelandMapping.rows(Http.json(url)))
    }
}

/**
 * Wikimedia Commons "Featured pictures of landscapes": photos under CC BY-SA and similar. A live source of the
 * catalog ([LiveSource]): its rows are `commons:<file name>`.
 */
object CommonsLandscapes : LiveSource {
    override val id = "commons"

    private const val TAG = "CommonsLandscapes"

    // most featured landscapes are wide, so one page of 100 usually gives ~60–80 pictures
    private const val MAX_PAGES = 3
    private const val TARGET = 120

    /** The whole load, all pages together, in the background; what came in by then is kept if not smaller. */
    private const val DEADLINE_MS = 120_000L

    override suspend fun fetch(): Listing<LiveRow> =
        collectPages(MAX_PAGES, TARGET, DEADLINE_MS, log = { Log.w(TAG, it) }, fetch = ::page)

    private suspend fun page(cont: String?): Page<LiveRow> {
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
            .addQueryParameter("iiextmetadatafilter", CommonsMapping.EXTMETADATA)
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
        cont?.let { b.addQueryParameter("gcmcontinue", it).addQueryParameter("continue", "gcmcontinue||") }
        return CommonsMapping.page(Http.json(b.build().toString()))
    }
}

/**
 * Pictures the user copied onto the device. Reads the `pictures` folder in the app's own external files
 * directory, which needs no storage permission on Android 11 and which `adb push` can write to ([adbDir]).
 * Any JPEG/PNG/WebP/BMP/GIF/HEIC dropped there shows up, newest first.
 */
class LocalImages(private val app: Application) : Source {
    override val id = ID
    override val name = Txt(R.string.tab_local)

    companion object {
        /** The tab's id and its pictures' [Picture.source]. */
        const val ID = "local"

        private const val DIR = "pictures"

        /** Extensions of the pictures the folder lists (and `UploadService` resolves `photo` names with). */
        val EXTENSIONS: List<String> = listOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic")

        /** The on-device folder; created on first access so `adb push` always has a target. */
        fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null), DIR).apply { mkdirs() }

        /** [dir] as `adb push` sees it, for the hint shown while the folder is empty. */
        fun adbDir(ctx: Context): String = "/sdcard/Android/data/${ctx.packageName}/files/$DIR/"
    }

    fun dir(): File = dir(app)

    override suspend fun load(): Listing<Picture> = withContext(Dispatchers.IO) {
        Listing(dir().listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                val size = pixelSize(f)
                Picture(
                    id = "$ID:${f.name}",
                    title = f.nameWithoutExtension,
                    credit = "",
                    thumbUrl = f.absolutePath,
                    fullUrl = f.absolutePath,
                    local = true,
                    width = size?.first,
                    height = size?.second,
                )
            } ?: emptyList())
    }

    /** The pixel size of [f] from its header alone (nothing is decoded); `null` if it cannot be read. */
    private fun pixelSize(f: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }
}

/** The live sources whose lists the catalog database keeps as its rows ([LiveRefresh]). */
val liveSources: List<LiveSource> = listOf(ClevelandPaintings, CommonsLandscapes)
