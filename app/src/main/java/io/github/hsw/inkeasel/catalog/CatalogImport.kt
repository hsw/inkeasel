package io.github.hsw.inkeasel.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * A release file to import: [name] as the release (or the APK's assets) names it, its bytes from [open]
 * (called once, on an IO thread; the stream is closed after reading). [lang] is the language of a
 * `labels-<lang>` file; `null` for `core` and for a labels file whose rows carry their language
 * (`labels-all`, the starter's `labels`).
 */
class ImportFile(val name: String, val lang: String? = null, val open: () -> InputStream)

/** What was read from one file: valid [rows], skipped [badLines], the file's [sha256] and size in [bytes]. */
data class FileRead(val name: String, val rows: Int, val badLines: Int, val sha256: String, val bytes: Long)

/**
 * What an import changed: Wikidata pictures [added], [replaced] (present before, written again) and
 * [removed] (absent from the new `core`); [labels] written; [labelsDropped], labels of languages outside the
 * chain removed. [files] lists what was read, for the caller to record each file's hash.
 */
data class ImportResult(
    val files: List<FileRead>,
    val added: Int = 0,
    val replaced: Int = 0,
    val removed: Int = 0,
    val labels: Int = 0,
    val labelsDropped: Int = 0,
)

/**
 * Imports the catalog's JSON Lines files ([CatalogRows], [JsonLinesReader]) into [CatalogDb]:
 * - [starter]: the starter set from the APK into a database with no Wikidata row yet, written straight into
 *   the live tables in one transaction;
 * - [release]: release files streamed into the `_stage` tables in batch transactions (one batch in memory at
 *   a time), then one swap transaction that replaces the Wikidata rows, their links and the labels of the
 *   imported languages, sweeps the rows absent from the new `core`, drops labels outside the chain and
 *   recomputes rank and dedupe ([CatalogDao.rerank]). A failure before the swap changes nothing visible, and
 *   the paging source, which reads only live tables, is invalidated once, by the swap.
 *
 * Imports must not overlap: the caller holds the store's write lock. In a `core` the first row of an id wins
 * (a repeat is skipped); a row's place in the list is its position among the file's valid rows.
 */
class CatalogImport(private val db: CatalogDb, private val batch: Int = BATCH) {
    private val dao = db.imports()
    private val catalog = db.catalog()

    init {
        require(batch > 0) { "batch size $batch" }
    }

    /**
     * Imports the starter set ([core] and a [labels] file whose rows carry their language) into a database
     * that has no Wikidata row, keeping only labels in the [chain]'s languages, English and `mul`. All or
     * nothing (one transaction). `null` when the database already has Wikidata rows: nothing is read.
     */
    suspend fun starter(core: ImportFile, labels: ImportFile, chain: List<String>): ImportResult? =
        withContext(Dispatchers.IO) {
            val langs = languages(chain)
            db.withTransaction {
                if (dao.hasWikidata()) return@withTransaction null
                val base = minOf(0L, dao.lowestRank() ?: 0L) - 1
                var added = 0
                val coreRead = readCore(core) { pictures, links ->
                    catalog.insertPictures(pictures.map { it.live(rank = base - it.sourceRank) })
                    catalog.insertLinks(links.map { it.live() })
                    added += pictures.size
                }
                if (added == 0) throw IOException("${core.name}: no valid row")
                var written = 0
                val labelsRead = readLabels(labels, langs) { rows ->
                    catalog.insertLabels(rows.map { it.live() })
                    written += rows.size
                }
                catalog.rerank()
                ImportResult(listOf(coreRead, labelsRead), added = added, labels = written)
            }
        }

    /**
     * Imports release files: a new [core] (or `null` to keep the stored rows) and [labels] files. A
     * `labels-<lang>` file replaces that language's labels, a file whose rows carry their language replaces
     * every language of the [chain]; a single-language file outside the chain is not read. Labels outside the
     * chain (and English and `mul`) are dropped at the swap. Throws on a broken file (an [IOException]) or a
     * `core` with no valid row, leaving the live tables as they were; the staging tables are cleared then
     * and again at the start of the next import. [commit] runs last inside the swap transaction, so what it
     * writes (the files' state) is stored together with the rows or not at all.
     */
    suspend fun release(
        core: ImportFile?,
        labels: List<ImportFile>,
        chain: List<String>,
        commit: suspend (ImportResult) -> Unit = {},
    ): ImportResult =
        withContext(Dispatchers.IO) {
            require(core != null || labels.isNotEmpty()) { "nothing to import" }
            val langs = languages(chain)
            dao.clearStage()
            try {
                val files = ArrayList<FileRead>()
                if (core != null) {
                    var staged = 0
                    files += readCore(core) { pictures, links ->
                        dao.stageCore(pictures, links)
                        staged += pictures.size
                    }
                    if (staged == 0) throw IOException("${core.name}: no valid row")
                }
                val replacedLangs = LinkedHashSet<String>()
                for (file in labels) {
                    if (file.lang != null && file.lang !in langs) continue
                    files += readLabels(file, langs) { rows -> dao.stageLabels(rows) }
                    if (file.lang != null) replacedLangs += file.lang else replacedLangs += langs
                }
                swap(files, core != null, replacedLangs, langs, commit)
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { dao.clearStage() } }
                throw e
            }
        }

    /**
     * Adds the labels of the languages [add] (those of them the [chain] stores) from [labels], a file whose
     * rows carry their language (the starter's), each replacing that language: the starter set as the offline
     * source of a language that joined the chain later. Staged and swapped like [release] (labels outside the
     * chain are dropped, [commit] runs inside the swap); `null` when the file has no row in those languages,
     * and then nothing is swapped.
     */
    suspend fun addLabels(
        labels: ImportFile,
        add: Collection<String>,
        chain: List<String>,
        commit: suspend (ImportResult) -> Unit = {},
    ): ImportResult? = withContext(Dispatchers.IO) {
        val langs = languages(chain)
        val wanted = add.filter { it in langs }.toSet()
        if (wanted.isEmpty()) return@withContext null
        dao.clearStage()
        try {
            val read = readLabels(labels, wanted) { rows -> dao.stageLabels(rows) }
            if (dao.stagedLabels() == 0) {
                dao.clearStage()
                return@withContext null
            }
            swap(listOf(read), false, wanted, langs, commit)
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { dao.clearStage() } }
            throw e
        }
    }

    private suspend fun swap(
        files: List<FileRead>,
        newCore: Boolean,
        replacedLangs: Set<String>,
        langs: List<String>,
        commit: suspend (ImportResult) -> Unit,
    ): ImportResult = db.withTransaction {
        var added = 0
        var replaced = 0
        var removed = 0
        if (newCore) {
            added = dao.newPictures()
            replaced = dao.stagedPictures() - added
            dao.movePictures(free = minOf(0L, dao.lowestRank() ?: 0L) - 1)
            removed = dao.sweepPictures()
            dao.clearLinks()
            dao.moveLinks()
        }
        if (replacedLangs.isNotEmpty()) dao.dropLabels(CatalogRow.chainKey(replacedLangs.toList()))
        val labels = dao.stagedLabels()
        dao.moveLabels()
        val dropped = dao.dropLabelsOutside(CatalogRow.chainKey(langs))
        catalog.rerank()
        dao.clearStage()
        ImportResult(files, added, replaced, removed, labels, dropped).also { commit(it) }
    }

    /**
     * Reads a `core` file in batches, the first row of an id winning, and hands each batch to [each] as staged
     * rows (positions numbered over the valid rows) with their entity links.
     */
    private suspend fun readCore(
        file: ImportFile,
        each: suspend (List<PictureStage>, List<PictureEntityStage>) -> Unit,
    ): FileRead {
        val seen = HashSet<String>()
        var pos = 0
        return read(file, CatalogRows::core) { rows ->
            val pictures = ArrayList<PictureStage>(rows.size)
            val links = ArrayList<PictureEntityStage>()
            for (row in rows) {
                if (!seen.add(row.id)) continue
                val picture = row.stage(pos++)
                pictures += picture
                links += row.links(picture.id)
            }
            if (pictures.isNotEmpty()) each(pictures, links)
        }
    }

    /** Reads a labels file in batches, keeping only rows in [langs]; the file's language wins over the row's. */
    private suspend fun readLabels(
        file: ImportFile,
        langs: Collection<String>,
        each: suspend (List<LabelStage>) -> Unit,
    ): FileRead = read(file, CatalogRows::label) { rows ->
        val kept = rows.mapNotNull { row ->
            val lang = file.lang ?: row.lang
            if (lang != null && lang in langs) row.stage(lang) else null
        }
        if (kept.isNotEmpty()) each(kept)
    }

    private suspend fun <T : Any> read(
        file: ImportFile,
        parse: (String) -> T,
        each: suspend (List<T>) -> Unit,
    ): FileRead = JsonLinesReader(file.open(), parse).use { reader ->
        reader.forEachBatch(batch) { rows ->
            coroutineContext.ensureActive()
            each(rows)
        }
        FileRead(file.name, reader.rows, reader.badLines, reader.sha256(), reader.bytes)
    }

    companion object {
        /** Rows per staging transaction. */
        const val BATCH = 500

        /** The languages whose labels are stored: the chain, then English and `mul` if it lacks them. */
        fun languages(chain: List<String>): List<String> =
            (chain + LanguageChain.ENGLISH + LanguageChain.MULTILINGUAL).distinct()

        private fun CoreRow.stage(pos: Int) = PictureStage(
            id = "${CatalogRank.WIKIDATA}:$id",
            wikidataId = id,
            commonsFile = commonsFile,
            museumRef = cma,
            fame = fame,
            sourceRank = pos,
            width = width,
            height = height,
            licence = licence,
            licenceUrl = licenceUrl,
            attr = attributionRequired,
            attribution = credit,
            inception = inception,
        )

        private fun CoreRow.links(pictureId: String): List<PictureEntityStage> =
            listOf(
                EntityKind.CREATOR to creators,
                EntityKind.COLLECTION to collections,
                EntityKind.MOVEMENT to movements,
                EntityKind.GENRE to genres,
            ).flatMap { (kind, ids) -> ids.mapIndexed { pos, entity -> PictureEntityStage(pictureId, kind, pos, entity) } }

        private fun LabelRow.stage(lang: String) = LabelStage(id, lang, label, aliases, wikiTitle)

        private fun PictureStage.live(rank: Long) = PictureEntity(
            id = id, source = CatalogRank.WIKIDATA, rank = rank, wikidataId = wikidataId, commonsFile = commonsFile,
            museumRef = museumRef, fame = fame, sourceRank = sourceRank, width = width, height = height,
            licence = licence, licenceUrl = licenceUrl, attr = attr, attribution = attribution, inception = inception,
        )

        private fun PictureEntityStage.live() = PictureEntityLink(pictureId, kind, pos, entityId)

        private fun LabelStage.live() = LabelEntity(entityId, lang, label, aliases, wikiTitle)
    }
}

private const val WD = "'${CatalogRank.WIKIDATA}'"

/** The staging tables, the swap's statements and the release files' state ([CatalogImport], [CatalogSync]). */
@Dao
abstract class ImportDao {
    @Query("SELECT EXISTS(SELECT 1 FROM picture WHERE source = $WD)")
    abstract suspend fun hasWikidata(): Boolean

    @Query("SELECT MIN(rank) FROM picture")
    abstract suspend fun lowestRank(): Long?

    /** Whether any label in [lang] is stored. */
    @Query("SELECT EXISTS(SELECT 1 FROM label WHERE lang = :lang)")
    abstract suspend fun hasLabels(lang: String): Boolean

    /** The state of every release file checked so far ([CatalogSync]). */
    @Query("SELECT * FROM catalog_file")
    abstract suspend fun files(): List<CatalogFileEntity>

    @Query("SELECT name FROM catalog_file")
    abstract suspend fun fileNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveFiles(files: List<CatalogFileEntity>)

    @Query("DELETE FROM catalog_file WHERE name IN (:names)")
    abstract suspend fun forgetFiles(names: List<String>)

    @Query("DELETE FROM picture_stage")
    protected abstract suspend fun clearPictureStage()

    @Query("DELETE FROM picture_entity_stage")
    protected abstract suspend fun clearLinkStage()

    @Query("DELETE FROM label_stage")
    protected abstract suspend fun clearLabelStage()

    @Transaction
    open suspend fun clearStage() {
        clearPictureStage()
        clearLinkStage()
        clearLabelStage()
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun stagePictures(pictures: List<PictureStage>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun stageLinks(links: List<PictureEntityStage>)

    /** One batch of `core` rows with their links, in one transaction. */
    @Transaction
    open suspend fun stageCore(pictures: List<PictureStage>, links: List<PictureEntityStage>) {
        stagePictures(pictures)
        stageLinks(links)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun stageLabels(labels: List<LabelStage>)

    @Query("SELECT COUNT(*) FROM picture_stage")
    abstract suspend fun stagedPictures(): Int

    @Query("SELECT COUNT(*) FROM label_stage")
    abstract suspend fun stagedLabels(): Int

    /** Staged pictures not yet in the live table. */
    @Query("SELECT COUNT(*) FROM picture_stage WHERE id NOT IN (SELECT id FROM picture)")
    abstract suspend fun newPictures(): Int

    /**
     * Writes every staged picture into the live table: a picture already there keeps its rank, a new one gets
     * `free - position`, below every rank in use (unique, [CatalogDao.rerank] places it). No UPSERT: SQLite
     * 3.18 lacks it, and REPLACE removes the old row by id, the same row that holds its rank.
     */
    @Query(
        "INSERT OR REPLACE INTO picture (id, source, rank, hidden, wikidataId, commonsFile, museumRef, fame, " +
            "sourceRank, width, height, licence, licenceUrl, attr, attribution, inception) " +
            "SELECT s.id, $WD, COALESCE((SELECT p.rank FROM picture p WHERE p.id = s.id), :free - s.sourceRank), " +
            "0, s.wikidataId, s.commonsFile, s.museumRef, s.fame, s.sourceRank, s.width, s.height, s.licence, " +
            "s.licenceUrl, s.attr, s.attribution, s.inception FROM picture_stage s",
    )
    abstract suspend fun movePictures(free: Long)

    /** Removes the Wikidata pictures the new `core` no longer has. */
    @Query("DELETE FROM picture WHERE source = $WD AND id NOT IN (SELECT id FROM picture_stage)")
    abstract suspend fun sweepPictures(): Int

    /** Entity links belong only to Wikidata pictures, so a new `core` replaces all of them. */
    @Query("DELETE FROM picture_entity")
    abstract suspend fun clearLinks()

    @Query(
        "INSERT INTO picture_entity (pictureId, kind, pos, entityId) " +
            "SELECT pictureId, kind, pos, entityId FROM picture_entity_stage",
    )
    abstract suspend fun moveLinks()

    /** Removes the labels of the languages in [langs] (`,ru,en,`), which the staged files replace. */
    @Query("DELETE FROM label WHERE instr(:langs, ',' || lang || ',') > 0")
    abstract suspend fun dropLabels(langs: String): Int

    @Query(
        "INSERT OR REPLACE INTO label (entityId, lang, label, aliases, wikiTitle) " +
            "SELECT entityId, lang, label, aliases, wikiTitle FROM label_stage",
    )
    abstract suspend fun moveLabels()

    /** Removes the labels of languages outside [chain] (`,ru,en,mul,`). */
    @Query("DELETE FROM label WHERE instr(:chain, ',' || lang || ',') = 0")
    abstract suspend fun dropLabelsOutside(chain: String): Int
}
