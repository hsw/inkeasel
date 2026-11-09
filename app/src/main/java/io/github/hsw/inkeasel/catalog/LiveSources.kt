package io.github.hsw.inkeasel.catalog

import androidx.room.withTransaction
import io.github.hsw.inkeasel.Listing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One picture of a live source as its list gives it ([io.github.hsw.inkeasel.ClevelandMapping],
 * [io.github.hsw.inkeasel.CommonsMapping]): [id] with the source's prefix (`cma:<api id>`,
 * `commons:<file name>`), the texts as the source writes them ([artists] in its order, joined only at the
 * edge), the museum's accession number ([museumRef]) or the Commons file ([commonsFile]) that the dedupe
 * matches against the Wikidata rows ([CatalogRank]), and the image URLs where they are not built from the
 * Commons file. A field the source does not give is `null`.
 */
data class LiveRow(
    val id: String,
    val title: String?,
    val artists: List<String> = emptyList(),
    val date: String? = null,
    val collection: String? = null,
    val pageUrl: String? = null,
    val licence: String? = null,
    val licenceUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val commonsFile: String? = null,
    val museumRef: String? = null,
    val thumbUrl: String? = null,
    val fullUrl: String? = null,
)

/** A source whose list the catalog keeps as its rows ([LiveRefresh]): the Cleveland museum, Commons. */
interface LiveSource {
    /** The prefix of its rows' ids and their `source` column. */
    val id: String

    /** Its list in its own order; [Listing.complete] false when cut short. Throws when nothing came. */
    suspend fun fetch(): Listing<LiveRow>
}

/** What a live source's refresh did ([LiveRefresh.run]). */
enum class LiveOutcome {
    /** Refreshed less than a week ago: not asked. */
    FRESH,

    /**
     * The request failed (no network, a timeout, an HTTP error), or writing its answer did: the rows stay,
     * asked again next start.
     */
    FAILED,

    /** The answer was empty, or cut short and smaller than the stored list: the rows stay, asked again next start. */
    KEPT,

    /** The whole list replaced the source's rows; the next check is a week later. */
    REPLACED,

    /** A list cut short, not smaller than the stored one, replaced the rows; asked again next start. */
    PARTIAL,
}

/** One source's refresh: its [outcome] and the rows it now has stored. */
data class LiveResult(val source: String, val outcome: LiveOutcome, val rows: Int)

/**
 * The rules of the live rows (pure): which source is due, whether an answer replaces the stored rows, and the
 * rows as the database stores them.
 */
object LiveSourceRows {
    private const val STATE_PREFIX = "live-"

    /** The name a source's refresh state is kept under in `catalog_file` (no release file is named so). */
    fun stateName(source: String): String = "$STATE_PREFIX$source"

    /**
     * Whether the source is asked again: never refreshed in full, a week ([CatalogSyncPlan.CHECK_EVERY_MS])
     * after its last full refresh, or when that is in the future (the clock was set back). A failure records
     * nothing, so the next start asks again.
     */
    fun due(state: CatalogFileEntity?, now: Long): Boolean = CatalogSyncPlan.isDue(state, now)

    /**
     * What an answer of [got] distinct rows ([complete] or cut short) does to the [stored] rows of its source:
     * an empty answer never replaces them; a whole list does; a list cut short does only when it is not
     * smaller, so a slow day never shrinks the source.
     */
    fun decide(got: Int, complete: Boolean, stored: Int): LiveOutcome = when {
        got == 0 -> LiveOutcome.KEPT
        complete -> LiveOutcome.REPLACED
        got >= stored -> LiveOutcome.PARTIAL
        else -> LiveOutcome.KEPT
    }

    /**
     * The [rows] of [source] as stored rows, in the list's order ([PictureEntity.sourceRank]), the first of a
     * repeated id winning; a row whose id lacks the source's prefix is left out. Each gets a rank below
     * [free] (`free`, `free - 1`, …), unique until [CatalogDao.rerank] places it. Artists are kept as a list
     * ([CatalogConverters]), as the list query reads them.
     */
    fun entities(source: String, rows: List<LiveRow>, free: Long): List<PictureEntity> {
        val seen = HashSet<String>()
        val texts = CatalogConverters()
        return rows.filter { it.id.startsWith("$source:") && seen.add(it.id) }.mapIndexed { i, r ->
            PictureEntity(
                id = r.id,
                source = source,
                rank = free - i,
                commonsFile = r.commonsFile,
                museumRef = r.museumRef,
                sourceRank = i,
                width = r.width,
                height = r.height,
                licence = r.licence,
                licenceUrl = r.licenceUrl,
                title = r.title,
                artist = texts.fromTexts(r.artists).ifEmpty { null },
                date = r.date,
                collection = r.collection,
                pageUrl = r.pageUrl,
                thumbUrl = r.thumbUrl,
                fullUrl = r.fullUrl,
            )
        }
    }
}

/**
 * Keeps the live sources' lists as rows of the catalog: a source that is due ([LiveSourceRows.due]) is asked
 * outside the write lock (a slow source holds up no import), and its answer replaces the source's rows in
 * one transaction under the lock, which also recomputes rank and dedupe ([CatalogDao.rerank]: interleaved
 * every 10th card, hidden where a Wikidata row is the same picture) and, after a whole list, records the
 * refresh. A failure (of the request or of the write) is logged and changes nothing for that source; the rows
 * stay, the other sources are still refreshed, and the next start asks again.
 */
class LiveRefresh(
    private val db: CatalogDb,
    private val sources: List<LiveSource>,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val dao = db.imports()
    private val catalog = db.catalog()

    /** Refreshes each due source in turn, writing through [write] (the store's write lock). */
    suspend fun run(write: suspend (suspend () -> Unit) -> Unit): List<LiveResult> = withContext(Dispatchers.IO) {
        val stored = dao.files().associateBy { it.name }
        sources.map { source ->
            if (!LiveSourceRows.due(stored[LiveSourceRows.stateName(source.id)], now())) {
                return@map LiveResult(source.id, LiveOutcome.FRESH, dao.sourceCount(source.id))
            }
            val got = try {
                source.fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("${source.id}: refresh failed", e)
                return@map LiveResult(source.id, LiveOutcome.FAILED, dao.sourceCount(source.id))
            }
            var result: LiveResult? = null
            try {
                write { result = store(source.id, got) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a database failure of one source leaves the others to be refreshed
                log("${source.id}: storing the list failed", e)
                return@map LiveResult(source.id, LiveOutcome.FAILED, dao.sourceCount(source.id))
            }
            checkNotNull(result).also { log("${source.id}: ${it.outcome}, ${it.rows} rows", null) }
        }
    }

    private suspend fun store(source: String, got: Listing<LiveRow>): LiveResult = db.withTransaction {
        val stored = dao.sourceCount(source)
        // below every rank in use now, so also once the source's old rows are gone
        val free = minOf(0L, dao.lowestRank() ?: 0L) - 1
        val rows = LiveSourceRows.entities(source, got.items, free)
        when (val outcome = LiveSourceRows.decide(rows.size, got.complete, stored)) {
            LiveOutcome.REPLACED, LiveOutcome.PARTIAL -> {
                dao.deleteSource(source)
                catalog.insertPictures(rows)
                catalog.rerank()
                if (outcome == LiveOutcome.REPLACED) {
                    val at = now()
                    dao.saveFiles(listOf(CatalogFileEntity(LiveSourceRows.stateName(source), checkedAt = at, importedAt = at)))
                }
                LiveResult(source, outcome, rows.size)
            }
            else -> LiveResult(source, outcome, stored)
        }
    }
}
