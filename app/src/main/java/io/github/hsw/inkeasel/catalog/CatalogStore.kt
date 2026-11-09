package io.github.hsw.inkeasel.catalog

import android.content.Context
import android.content.res.Configuration
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.hsw.inkeasel.Http
import io.github.hsw.inkeasel.liveSources
import io.github.hsw.inkeasel.logTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The starter set in the APK: its `core` and one labels file per language, as in the release ([languages]
 * lists them, [labels] opens one), so a start reads only the files of its own languages.
 */
class StarterSet(
    val core: () -> InputStream,
    val languages: () -> Set<String>,
    val labels: (String) -> InputStream,
) {
    fun coreFile() = ImportFile(CatalogStore.CORE_ASSET, open = core)

    /** The labels files of those of [langs] the starter set has. */
    fun labelsFiles(langs: Collection<String>): List<ImportFile> {
        val have = languages()
        return langs.filter { it in have }.map { lang -> ImportFile(CatalogStore.labelsAsset(lang), lang) { labels(lang) } }
    }
}

/**
 * The process-wide owner of the catalog ([get]): the database, a scope of its own (not tied to a screen) and
 * the one write lock that every write to the catalog's tables takes ([write]). [start] begins the work once
 * per process: the starter set goes into an empty database (and the old list cache, [legacy], is deleted),
 * then each [start] (every app start, and a new UI language, which re-creates the activity) runs a pass: the
 * starter's labels of a language that joined the [chain] since, the release sync, which fetches only the
 * files that are due ([CatalogSyncPlan]), then the live sources that are due ([LiveRefresh], asked outside
 * the lock), so a pass with nothing due makes no request. Passes run one after another; a [start] during a
 * pass asks for one more. A failed step is logged and the next pass tries again. The Catalog tab reads the
 * list through [pages].
 */
class CatalogStore internal constructor(
    val db: CatalogDb,
    private val starter: StarterSet,
    private val sync: CatalogSync,
    chain: List<String>,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val live: LiveRefresh? = null,
    private val legacy: File? = null,
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

    private val _empty = MutableStateFlow(false)

    /**
     * The list has no picture although the starter step has run (its import failed, which a sound APK never
     * does); read again after each pass. Until then an empty list is one still loading: the starter set is on
     * its way.
     */
    val empty: StateFlow<Boolean> = _empty.asStateFlow()

    /** The starter step of this process has run. */
    private val ready = MutableStateFlow(false)

    /**
     * The list ([CatalogDao.pages]) over the current [chain]: a new chain starts a new pager. It starts once
     * the starter step has run, so its first query neither waits for the starter's transaction nor holds it
     * up; until then it gives nothing, and the list shows that it is loading. At most [MAX_ROWS] rows stay
     * loaded: pages far from where the list is read are dropped (they show as placeholders and load again
     * when scrolled back to), so scrolling through the whole catalog never holds all of it in memory.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pages: Flow<PagingData<CatalogRow>> = ready.filter { it }.flatMapLatest { _chain }.flatMapLatest { chain ->
        Pager(PagingConfig(pageSize = PAGE, prefetchDistance = PAGE, initialLoadSize = 2 * PAGE, maxSize = MAX_ROWS)) {
            db.catalog().pages(CatalogRow.chainKey(chain))
        }.flow
    }

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
        checkEmpty()
        ready.value = true
        legacy?.let(::dropLegacy)
        for (signal in wake) {
            val chain = chain.value
            step("starter labels") { starterLabels(chain) }
            step("release sync") { sync.run(chain) }
            live?.let { refreshLive(it) }
            checkEmpty()
            _passes.update { it + 1 }
        }
    }

    private suspend fun checkEmpty() {
        try {
            _empty.value = db.catalog().count() == 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("count failed", e)
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

    /** The live sources' lists: asked without the lock, each written under it ([LiveRefresh]). */
    private suspend fun refreshLive(live: LiveRefresh) {
        try {
            live.run { block -> write(block) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("live refresh failed", e)
        }
    }

    /**
     * The list cache of older versions (`files/catalog/`, a file per live source): the database holds those
     * lists now. Deleted at every process start, which costs nothing once it is gone.
     */
    private fun dropLegacy(dir: File) {
        try {
            if (dir.exists() && dir.deleteRecursively()) log("old list cache deleted", null)
        } catch (e: Exception) {
            log("old list cache not deleted", e)
        }
    }

    /** The starter set into a database with no Wikidata row (the first start, or after a schema change). */
    private suspend fun starter(chain: List<String>) {
        val start = System.nanoTime()
        val langs = CatalogImport.languages(chain)
        val result = import.starter(starter.coreFile(), starter.labelsFiles(langs), chain) ?: return
        starterLangs += langs
        log("starter set imported in ${(System.nanoTime() - start) / 1_000_000} ms: $result", null)
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
        val result = import.addLabels(starter.labelsFiles(missing), missing, chain) { sync.forgetOutside(chain) }
        log("starter labels of $missing: ${result ?: "none"}", null)
    }

    companion object {
        private const val TAG = "CatalogStore"

        /** The list cache of older versions, in the app's files ([dropLegacy]). */
        private const val LEGACY_DIR = "catalog"

        /** Rows per page of the list: about 15 rows of 4 cards on a TV. */
        const val PAGE = 60

        /** Rows the list keeps loaded at most: six pages, about 90 rows of 4 cards on a TV. */
        const val MAX_ROWS = 6 * PAGE

        /**
         * The starter set's files as the APK holds them (`catalog/core.jsonl`, `catalog/labels-<lang>.jsonl`):
         * AGP's asset merge stores `.gz` assets decompressed.
         */
        private const val ASSETS = "catalog"
        const val CORE_ASSET = "$ASSETS/core.jsonl"
        private const val LABELS_PREFIX = "labels-"
        private const val LABELS_SUFFIX = ".jsonl"

        fun labelsAsset(lang: String) = "$ASSETS/$LABELS_PREFIX$lang$LABELS_SUFFIX"

        /** The language of a starter labels file named [name] (in the assets' `catalog` folder); `null` for another file. */
        fun assetLanguage(name: String): String? =
            name.takeIf { it.startsWith(LABELS_PREFIX) && it.endsWith(LABELS_SUFFIX) }
                ?.substring(LABELS_PREFIX.length, name.length - LABELS_SUFFIX.length)
                ?.takeIf(CatalogRows::isLang)

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
                StarterSet(
                    core = { app.assets.open(CORE_ASSET) },
                    languages = { app.assets.list(ASSETS).orEmpty().mapNotNullTo(HashSet(), ::assetLanguage) },
                    labels = { lang -> app.assets.open(labelsAsset(lang)) },
                ),
                CatalogSync(db, Http.client, File(app.cacheDir, "catalog-sync"), log = logTo("CatalogSync")),
                chainOf(app.resources.configuration),
                logTo(TAG),
                live = LiveRefresh(db, liveSources, log = logTo("LiveRefresh")),
                legacy = File(app.filesDir, LEGACY_DIR),
            )
        }
    }
}
