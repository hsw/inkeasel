package io.github.hsw.inkeasel.catalog

import io.github.hsw.inkeasel.HttpStatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** What one sync run did: each file's [outcomes], the [import] if one ran, or why it failed ([importError]). */
data class SyncReport(
    val outcomes: List<SyncOutcome> = emptyList(),
    val import: ImportResult? = null,
    val importError: Throwable? = null,
)

/**
 * Brings the catalog database up to the published release ([CatalogSyncPlan] decides which files): each due
 * file is fetched from [base] (github.com, which redirects to a signed address that is never stored) with
 * `If-None-Match` and streamed to a temporary file in [tempDir] while its SHA-256 is computed; files whose
 * hash differs from the one imported last are imported together in one swap ([CatalogImport.release]), and
 * their ETag and hash are recorded inside that swap. Requests run one at a time, outside the store's write
 * lock; the import and the state writes run under it. A failed request (no network, a body cut short)
 * changes nothing and is tried again at the next start; a failed import a day later; a 404 a week later.
 * The [client] should limit a whole call ([CALL_TIMEOUT_MIN]): a link that trickles bytes would otherwise keep
 * the pass from ever finishing.
 */
class CatalogSync(
    private val db: CatalogDb,
    private val client: OkHttpClient,
    private val tempDir: File,
    private val base: HttpUrl = RELEASE.toHttpUrl(),
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val import = CatalogImport(db)
    private val dao = db.imports()

    /**
     * Checks the files the [chain] has due, fetching them outside the lock, then imports and records them
     * through [writer] (the store's write lock).
     */
    suspend fun run(
        chain: List<String>,
        writer: CatalogWriter = CatalogWriter.DIRECT,
    ): SyncReport = withContext(Dispatchers.IO) {
        clearTemp()
        val checks = CatalogSyncPlan.due(chain, dao.files().associateBy { it.name }, now())
        if (checks.isEmpty()) return@withContext SyncReport()
        val outcomes = ArrayList<SyncOutcome>(checks.size)
        try {
            for (check in checks) outcomes += fetch(check)
            writer.write { apply(outcomes, chain) }
        } finally {
            outcomes.forEach { if (it is SyncOutcome.Downloaded) it.file.delete() }
        }
    }

    /** Imports the changed downloads in one swap and records what each check found; under the write lock. */
    private suspend fun apply(outcomes: List<SyncOutcome>, chain: List<String>): SyncReport {
        // read again under the lock: the state the import is decided on is the one it writes over
        val stored = dao.files().associateBy { it.name }
        val changed = outcomes.filterIsInstance<SyncOutcome.Downloaded>()
            .filter { CatalogSyncPlan.changed(it, stored[it.check.name]) }
        var result: ImportResult? = null
        var error: Throwable? = null
        if (changed.isNotEmpty()) {
            val core = changed.firstOrNull { it.check.lang == null }?.let(::importFile)
            val labels = changed.filter { it.check.lang != null }.map(::importFile)
            try {
                result = import.release(core, labels, chain) { commit(changed, chain) }
                log("imported ${changed.joinToString { it.check.name }}: $result", null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                log("import of ${changed.joinToString { it.check.name }} failed", e)
            }
        }
        val at = now()
        val checked = outcomes.mapNotNull { CatalogSyncPlan.checked(stored[it.check.name], it, at) }
        val failed = if (error == null) emptyList() else {
            changed.map { CatalogSyncPlan.importFailed(stored[it.check.name], it, at) }
        }
        if (checked.isNotEmpty() || failed.isNotEmpty()) dao.saveFiles(checked + failed)
        return SyncReport(outcomes, result, error)
    }

    /**
     * Forgets the state of labels files whose language the [chain] no longer stores; runs inside a swap, which
     * drops those labels ([CatalogSyncPlan.outside]).
     */
    suspend fun forgetOutside(chain: List<String>) {
        val gone = CatalogSyncPlan.outside(chain, dao.fileNames())
        if (gone.isNotEmpty()) dao.forgetFiles(gone)
    }

    private suspend fun commit(changed: List<SyncOutcome.Downloaded>, chain: List<String>) {
        val at = now()
        dao.saveFiles(changed.map { CatalogSyncPlan.imported(it, at) })
        forgetOutside(chain)
    }

    private fun importFile(file: SyncOutcome.Downloaded) =
        ImportFile(file.check.name, file.check.lang) { file.file.inputStream() }

    private suspend fun fetch(check: SyncCheck): SyncOutcome {
        val url = checkNotNull(base.resolve(check.name)) { "bad file name ${check.name}" }
        val request = Request.Builder().url(url).apply { check.etag?.let { header("If-None-Match", it) } }.build()
        return try {
            client.newCall(request).await().use { response ->
                when (response.code) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> SyncOutcome.NotModified(check)
                    HttpURLConnection.HTTP_NOT_FOUND -> SyncOutcome.Missing(check)
                    else -> {
                        if (!response.isSuccessful) throw HttpStatusException(response.code, url.toString())
                        save(check, response)
                    }
                }
            }.also { log("${check.name}: ${it.javaClass.simpleName}", null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("${check.name}: check failed", e)
            SyncOutcome.Failed(check, e)
        }
    }

    /** Streams the body to a temporary file, hashing it; the file is deleted if anything fails. */
    private suspend fun save(check: SyncCheck, response: Response): SyncOutcome.Downloaded {
        tempDir.mkdirs()
        val file = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, tempDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val body = response.body ?: throw IOException("no body")
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        size += n
                    }
                }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            return SyncOutcome.Downloaded(check, response.header("ETag"), sha256, size, file)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    /** Leftovers of a run the process did not finish. */
    private fun clearTemp() {
        tempDir.listFiles()?.forEach { if (it.name.startsWith(TEMP_PREFIX)) it.delete() }
    }

    companion object {
        /** Its log tag. */
        const val TAG = "CatalogSync"

        /** The release's address; never `releases/latest`, which is the newest APK release. */
        const val RELEASE = "https://github.com/hsw/inkeasel/releases/download/catalog/"

        /** The longest one release file may take, from connect to the last byte (a few MB on a slow link). */
        const val CALL_TIMEOUT_MIN = 10L

        private const val TEMP_PREFIX = "release-"
        private const val TEMP_SUFFIX = ".part"
        private const val BUFFER = 64 * 1024
    }
}

/** The response of the call; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWith(Result.failure(e))

            override fun onResponse(call: Call, response: Response) {
                // a response that arrives after the coroutine was cancelled is closed, never leaked
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
