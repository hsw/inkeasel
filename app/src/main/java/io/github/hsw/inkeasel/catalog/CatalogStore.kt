package io.github.hsw.inkeasel.catalog

import android.content.Context
import android.content.res.Configuration
import io.github.hsw.inkeasel.Http
import io.github.hsw.inkeasel.logTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** The starter set in the APK: its `core` and its labels file (rows with their language). */
class StarterSet(val core: () -> InputStream, val labels: () -> InputStream) {
    fun coreFile() = ImportFile(CatalogStore.CORE_ASSET, open = core)

    fun labelsFile() = ImportFile(CatalogStore.LABELS_ASSET, open = labels)
}

/**
 * The process-wide owner of the catalog ([get]): the database, a scope of its own (not tied to a screen) and
 * the one write lock that every write to the catalog's tables takes ([write]). [start] begins the work once
 * per process: the starter set goes into an empty database, then each [start] (every app start, and a new
 * UI language, which re-creates the activity) runs a pass: the starter's labels of a language that joined the
 * [chain] since, then the release sync, which fetches only the files that are due ([CatalogSyncPlan]), so a
 * pass with nothing due makes no request. Passes run one after another; a [start] during a pass asks for one
 * more. A failed step is logged and the next pass tries again.
 */
class CatalogStore internal constructor(
    val db: CatalogDb,
    private val starter: StarterSet,
    private val sync: CatalogSync,
    chain: List<String>,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val writeLock = Mutex()
    private val import = CatalogImport(db)
    private val started = AtomicBoolean()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Languages whose starter labels were looked for in this process (only under the write lock). */
    private val starterLangs = HashSet<String>()

    private val _chain = MutableStateFlow(chain)

    /** The label languages of the UI language ([LanguageChain]); the list's queries follow it. */
    val chain: StateFlow<List<String>> = _chain.asStateFlow()

    private val _passes = MutableStateFlow(0)

    /** Passes finished in this process. */
    val passes: StateFlow<Int> = _passes.asStateFlow()

    /** At app start, with the UI language's [chain]: starts the work once, and asks for a pass. */
    fun start(chain: List<String>) {
        _chain.value = chain
        if (started.compareAndSet(false, true)) scope.launch { work() }
        wake.trySend(Unit)
    }

    /** Runs [block] under the write lock: writes to the catalog never overlap. */
    suspend fun <T> write(block: suspend () -> T): T = writeLock.withLock { block() }

    /** Stops the work (tests; the process-wide store lives as long as the process). */
    internal fun close() {
        scope.cancel()
        wake.close()
    }

    private suspend fun work() {
        step("starter import") { starter(chain.value) }
        for (signal in wake) {
            val chain = chain.value
            step("starter labels") { starterLabels(chain) }
            step("release sync") { sync.run(chain) }
            _passes.update { it + 1 }
        }
    }

    private suspend fun step(what: String, block: suspend () -> Unit) {
        try {
            write { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$what failed", e)
        }
    }

    /** The starter set into a database with no Wikidata row (the first start, or after a schema change). */
    private suspend fun starter(chain: List<String>) {
        val result = import.starter(starter.coreFile(), starter.labelsFile(), chain) ?: return
        starterLangs += CatalogImport.languages(chain)
        log("starter set imported: $result", null)
    }

    /**
     * The starter's labels of each language the [chain] stores but the database has none of (it joined the
     * chain after the first start), so the list shows it while its release file downloads, also offline. Each
     * language is looked for once per process.
     */
    private suspend fun starterLabels(chain: List<String>) {
        val fresh = CatalogImport.languages(chain).filter { starterLangs.add(it) }
        val dao = db.imports()
        val missing = fresh.filter { !dao.hasLabels(it) }
        if (missing.isEmpty()) return
        val result = import.addLabels(starter.labelsFile(), missing, chain) { sync.forgetOutside(chain) }
        log("starter labels of $missing: ${result ?: "none"}", null)
    }

    companion object {
        private const val TAG = "CatalogStore"

        /** The starter set's files as the APK holds them: AGP's asset merge stores `.gz` assets decompressed. */
        const val CORE_ASSET = "catalog/core.jsonl"
        const val LABELS_ASSET = "catalog/labels.jsonl"

        @Volatile
        private var instance: CatalogStore? = null

        fun get(context: Context): CatalogStore =
            instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }

        /** The chain of the UI language: the app's own locale where one is set, else the system's. */
        fun chainOf(config: Configuration): List<String> = LanguageChain.of(config.locales[0].toLanguageTag())

        private fun create(app: Context): CatalogStore {
            val db = CatalogDb.open(app)
            return CatalogStore(
                db,
                StarterSet({ app.assets.open(CORE_ASSET) }, { app.assets.open(LABELS_ASSET) }),
                CatalogSync(db, Http.client, File(app.cacheDir, "catalog-sync"), log = logTo("CatalogSync")),
                chainOf(app.resources.configuration),
                logTo(TAG),
            )
        }
    }
}
