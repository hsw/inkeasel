package io.github.hsw.inkeasel.catalog

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream

/** An in-memory catalog database with the app's builder settings ([CatalogDb.configure]) and a test's own [setup]. */
fun inMemoryCatalogDb(context: Context, setup: RoomDatabase.Builder<CatalogDb>.() -> Unit = {}): CatalogDb =
    CatalogDb.configure(Room.inMemoryDatabaseBuilder(context, CatalogDb::class.java).apply(setup)).build()

/** Release rows and files for the sync and store tests. */
object ReleaseFixtures {
    fun core(q: Int, fame: Int = 3, creators: List<Int> = emptyList()): String = JSONObject()
        .put("id", "Q$q").put("fame", fame).put("img", "Painting $q.jpg").put("w", 800).put("h", 600)
        .put("lic", "Public domain").put("attr", false)
        .put("creators", JSONArray(creators.map { "Q$it" }))
        .put("collections", JSONArray()).put("movements", JSONArray()).put("genres", JSONArray())
        .toString()

    fun label(q: Int, text: String, lang: String? = null): String = JSONObject()
        .apply { if (lang != null) put("lang", lang) }
        .put("id", "Q$q").put("l", text)
        .toString()

    fun gzip(lines: List<String>): ByteArray = gzip(lines.joinToString("\n", postfix = "\n").toByteArray())

    fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * A fake release host: [files] by name with their ETag, answering `If-None-Match` with 304 and an unknown
 * name with 404; [overrides] replace the answer for a name; with [redirect] every request under the release
 * path is first sent on to `/assets/<name>`, as github.com sends it to a signed address.
 */
class FakeRelease : Dispatcher() {
    class Served(val body: ByteArray, val etag: String)

    val server = MockWebServer().also { it.dispatcher = this }
    val files = ConcurrentHashMap<String, Served>()
    val overrides = ConcurrentHashMap<String, () -> MockResponse>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile
    var redirect = false

    val base get() = server.url(RELEASE_PATH)

    fun serve(name: String, body: ByteArray, etag: String = "\"${ReleaseFixtures.sha256(body).take(8)}-1\"") {
        files[name] = Served(body, etag)
    }

    /** The release files' names in the order they were asked for, each with its `If-None-Match`. */
    fun asked(): List<String> = requests.map { r ->
        val name = r.path!!.substringAfterLast('/')
        val prefix = if (r.path!!.startsWith(RELEASE_PATH)) "" else "assets/"
        val etag = r.getHeader("If-None-Match")
        if (etag == null) "$prefix$name" else "$prefix$name if $etag"
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.path!!
        val name = path.substringAfterLast('/')
        if (redirect && path.startsWith(RELEASE_PATH)) {
            return MockResponse().setResponseCode(302).setHeader("Location", "/assets/$name")
        }
        overrides[name]?.let { return it() }
        val file = files[name] ?: return MockResponse().setResponseCode(404)
        if (request.getHeader("If-None-Match") == file.etag) {
            return MockResponse().setResponseCode(304).setHeader("ETag", file.etag)
        }
        return MockResponse().setBody(Buffer().write(file.body)).setHeader("ETag", file.etag)
    }

    companion object {
        const val RELEASE_PATH = "/hsw/inkeasel/releases/download/catalog/"
    }
}
