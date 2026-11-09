package io.github.hsw.inkeasel.catalog

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/*
 * The catalog database: every picture of the catalog (the Wikidata release's rows and the live sources'), the
 * labels of the Wikidata entities in the stored languages, and the state of the release files. It holds only
 * data that can be downloaded again, so a schema change drops and re-creates it (docs/decisions.md).
 * The live tables are read by the paging query; the `_stage` tables only by an import's swap. The list's
 * queries take the nudity filter as `:nudityBelow` ([NudityFilter.nudityBelow]): a row shows when its
 * [PictureEntity.nudity] is below it.
 */

/**
 * One picture. [id] keeps its source prefix (`wd:Q12418`, `cma:<api id>`, `commons:<file name>`), [source] is
 * that prefix. [rank] is the list order (unique; [CatalogRank]), [hidden] marks a live row that duplicates a
 * Wikidata row. Wikidata rows get their title, artists and collection from [LabelEntity] through
 * [PictureEntityLink]; live rows carry them as the source writes them ([title] … [fullUrl]). [nudity] is the
 * row's [NudityLevel] ordinal, which the list filters on.
 */
@Entity(
    tableName = "picture",
    indices = [
        Index(value = ["rank"], unique = true),
        Index(value = ["hidden", "rank"]),
    ],
)
data class PictureEntity(
    @PrimaryKey val id: String,
    val source: String,
    val rank: Long,
    val hidden: Boolean = false,
    /**
     * The [NudityLevel] ordinal: a Wikidata row's [Nudity.levelOf] at its import, raised by the built-in list
     * at each process start ([CatalogDao.markListed]); 0 for a live row, whose source gives no such data.
     */
    @ColumnInfo(defaultValue = "0")
    val nudity: Int = 0,
    /** The Wikidata item (`Q12418`), without the `wd:` prefix. */
    val wikidataId: String? = null,
    /** The Commons file name, spaces as in a page title. */
    val commonsFile: String? = null,
    /** The museum's accession number: Cleveland's from its API, or Wikidata P11110 for a Wikidata row. */
    val museumRef: String? = null,
    /** Sitelinks of the Wikidata item. */
    val fame: Int? = null,
    /** The position in its source: the line in `core`, or the place in a live source's list. */
    val sourceRank: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val licence: String? = null,
    val licenceUrl: String? = null,
    /** The licence asks for attribution wherever the picture is shown. */
    val attr: Boolean = false,
    /** The credit text to show where [attr] is set. */
    val attribution: String? = null,
    /** The year the painting was made. */
    val inception: Int? = null,
    val title: String? = null,
    /** A live row's artists in its source's order (Wikidata rows' come from their links). */
    val artists: List<String>? = null,
    val date: String? = null,
    val collection: String? = null,
    val pageUrl: String? = null,
    val thumbUrl: String? = null,
    val fullUrl: String? = null,
)

/** A Wikidata entity's label, aliases and Wikipedia article title in one language (a Wikidata code). */
@Entity(tableName = "label", primaryKeys = ["entityId", "lang"])
data class LabelEntity(
    val entityId: String,
    val lang: String,
    val label: String?,
    val aliases: List<String> = emptyList(),
    val wikiTitle: String? = null,
)

/** A Wikidata picture's link to an entity of one [kind] ([EntityKind]), at [pos] in the release's list. */
@Entity(tableName = "picture_entity", primaryKeys = ["pictureId", "kind", "pos"])
data class PictureEntityLink(
    val pictureId: String,
    val kind: String,
    val pos: Int,
    val entityId: String,
)

/** The kinds of [PictureEntityLink]: the lists of a `core` row. */
object EntityKind {
    const val CREATOR = "creator"
    const val COLLECTION = "collection"
    const val MOVEMENT = "movement"
    const val GENRE = "genre"
}

/**
 * A release file's state: the [etag] and [sha256] of the content last imported, when it was checked and
 * imported, [missing] on a 404, [importFailed] when its last download could not be imported (checked again
 * sooner, [CatalogSyncPlan.isDue]). A live source's refresh is kept here too, as `live-<source>`
 * ([LiveSourceRows.stateName]) with the time of its last whole list in [checkedAt] and [importedAt].
 */
@Entity(tableName = "catalog_file")
data class CatalogFileEntity(
    @PrimaryKey val name: String,
    val etag: String? = null,
    val sha256: String? = null,
    val size: Long? = null,
    val checkedAt: Long? = null,
    val importedAt: Long? = null,
    val missing: Boolean = false,
    val importFailed: Boolean = false,
)

/** A Wikidata picture of an import, staged before the swap; the columns of [PictureEntity] the release sets. */
@Entity(tableName = "picture_stage")
data class PictureStage(
    @PrimaryKey val id: String,
    val wikidataId: String,
    val commonsFile: String,
    val museumRef: String?,
    val fame: Int,
    val sourceRank: Int,
    val width: Int,
    val height: Int,
    val licence: String?,
    val licenceUrl: String?,
    val attr: Boolean,
    val attribution: String?,
    val inception: Int?,
    /** The row's [NudityLevel] ordinal ([Nudity.levelOf]). */
    @ColumnInfo(defaultValue = "0")
    val nudity: Int,
)

/** A label of an import, staged before the swap. */
@Entity(tableName = "label_stage", primaryKeys = ["entityId", "lang"])
data class LabelStage(
    val entityId: String,
    val lang: String,
    val label: String?,
    val aliases: List<String> = emptyList(),
    val wikiTitle: String? = null,
)

/** An entity link of an import, staged before the swap. */
@Entity(tableName = "picture_entity_stage", primaryKeys = ["pictureId", "kind", "pos"])
data class PictureEntityStage(
    val pictureId: String,
    val kind: String,
    val pos: Int,
    val entityId: String,
)

/**
 * A picture as the list shows it, its texts resolved over the language chain: [title] (a Wikidata row's
 * label, a live row's title), [artists] (in the release's order; a live row's in its source's) and
 * [collection] (the first one's label). A text with no label in any stored language is `null` (an artist
 * without one is left out). Lists are joined only at the edge (`list_names_separator`).
 */
data class CatalogRow(
    val id: String,
    val source: String,
    val rank: Long,
    val wikidataId: String?,
    val commonsFile: String?,
    val museumRef: String?,
    val fame: Int?,
    val width: Int?,
    val height: Int?,
    val licence: String?,
    val licenceUrl: String?,
    val attr: Boolean,
    val attribution: String?,
    val inception: Int?,
    val title: String?,
    val artists: List<String>,
    val collection: String?,
    val date: String?,
    val pageUrl: String?,
    val thumbUrl: String?,
    val fullUrl: String?,
) {
    /** [title], or with no label the Commons file name without its extension, or the id. */
    val shownTitle: String
        get() = title ?: commonsFile?.let(::fileTitle) ?: id

    companion object {
        /** `Mona_Lisa, by Leonardo.jpg` → `Mona Lisa, by Leonardo`. */
        fun fileTitle(name: String): String {
            val spaced = name.replace('_', ' ').trim()
            val dot = spaced.lastIndexOf('.')
            return if (dot > 0) spaced.substring(0, dot).trimEnd() else spaced
        }

        /** The chain as the queries take it: `,ru,en,mul,` (a language's place in it is its preference). */
        fun chainKey(chain: List<String>): String = chain.joinToString(",", prefix = ",", postfix = ",")
    }
}

/** Lists of texts in one column, apart by the unit separator, which no label holds (it is dropped). */
class CatalogConverters {
    @TypeConverter
    fun fromTexts(texts: List<String>): String =
        texts.map { it.replace(SEPARATOR, "") }.filter { it.isNotEmpty() }.joinToString(SEPARATOR)

    @TypeConverter
    fun toTexts(column: String?): List<String> =
        column?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()

    companion object {
        /** SQL's `char(31)`: the queries join a picture's artists with it. */
        const val SEPARATOR = "\u001F"
    }
}

/*
 * The best label of an entity over the chain `:chain` (`,ru,en,mul,`, [CatalogRow.chainKey]): the chain's
 * languages in its order, then any other, lowest code first. `instr` is 0 for a language outside the chain.
 * Artists are joined with the unit separator over a subquery ordered by position (SQLite never flattens an
 * ordered subquery into an aggregate, so `group_concat` keeps that order); [CatalogConverters] splits them.
 * Only SQL that SQLite 3.18 (minSdk 26) has: `instr`, `char`, correlated subqueries.
 */
private const val LABEL_ORDER =
    "ORDER BY instr(:chain, ',' || l.lang || ',') = 0, instr(:chain, ',' || l.lang || ','), l.lang LIMIT 1"
private const val TITLE =
    "(SELECT l.label FROM label l WHERE l.entityId = p.wikidataId AND l.label IS NOT NULL $LABEL_ORDER)"
private const val ARTISTS =
    "(SELECT group_concat(name, char(31)) FROM (SELECT (SELECT l.label FROM label l " +
        "WHERE l.entityId = c.entityId AND l.label IS NOT NULL $LABEL_ORDER) AS name " +
        "FROM picture_entity c WHERE c.pictureId = p.id AND c.kind = '${EntityKind.CREATOR}' ORDER BY c.pos))"
private const val COLLECTION =
    "(SELECT l.label FROM label l WHERE l.entityId = (SELECT c.entityId FROM picture_entity c " +
        "WHERE c.pictureId = p.id AND c.kind = '${EntityKind.COLLECTION}' ORDER BY c.pos LIMIT 1) " +
        "AND l.label IS NOT NULL $LABEL_ORDER)"
private const val COLUMNS =
    "p.id, p.source, p.rank, p.wikidataId, p.commonsFile, p.museumRef, p.fame, p.width, p.height, " +
        "p.licence, p.licenceUrl, p.attr, p.attribution, p.inception, " +
        "COALESCE($TITLE, p.title) AS title, COALESCE($ARTISTS, p.artists) AS artists, " +
        "COALESCE($COLLECTION, p.collection) AS collection, p.date, p.pageUrl, p.thumbUrl, p.fullUrl"
private const val ROW = "SELECT $COLUMNS FROM picture p"

/*
 * A row with its place in the list and the list's size ([PlacedRow]), all in one statement: SQLite reads a
 * statement, subqueries included, from one state of the database, so an import or a live refresh that commits
 * meanwhile cannot give a place or a size of another list than the row's.
 */
private const val PLACED_ROW =
    "SELECT $COLUMNS, " +
        "(SELECT COUNT(*) FROM picture q WHERE q.hidden = 0 AND q.nudity < :nudityBelow AND q.rank < p.rank) AS position, " +
        "(SELECT COUNT(*) FROM picture q WHERE q.hidden = 0 AND q.nudity < :nudityBelow) AS count FROM picture p"

/** A row the list shows: not hidden as a duplicate, and let through by the nudity filter. */
private const val SHOWN = "p.hidden = 0 AND p.nudity < :nudityBelow"

/**
 * The rank of the shown picture `:id` as stored now; `:rank` (the one read with it) if gone, hidden or
 * filtered out.
 */
private const val FROM_RANK =
    "COALESCE((SELECT f.rank FROM picture f WHERE f.id = :id AND f.hidden = 0 AND f.nudity < :nudityBelow), :rank)"

/** A shown [row] with its 0-based [position] in the list and the list's size ([count]), read at once. */
data class PlacedRow(
    @Embedded val row: CatalogRow,
    val position: Int,
    val count: Int,
)

@Dao
abstract class CatalogDao {
    /**
     * The list: shown pictures in rank order, texts over the chain ([CatalogRow.chainKey]), those of a nudity
     * level below [nudityBelow] ([NudityFilter.nudityBelow]).
     */
    @Query("$ROW WHERE $SHOWN ORDER BY p.rank")
    abstract fun pages(chain: String, nudityBelow: Int): PagingSource<Int, CatalogRow>

    /** How many pictures the list shows under the filter [nudityBelow]. */
    @Query("SELECT COUNT(*) FROM picture p WHERE $SHOWN")
    abstract suspend fun count(nudityBelow: Int): Int

    /**
     * The shown picture after the picture [id] in the list, with its place and the list's size, in one
     * statement: from the rank [id] has now, or from [rank] (the one read with it) if it is gone, hidden or
     * filtered out. Under the filter [nudityBelow], as the list.
     */
    @Query("$PLACED_ROW WHERE $SHOWN AND p.rank > $FROM_RANK ORDER BY p.rank LIMIT 1")
    abstract suspend fun next(id: String, rank: Long, chain: String, nudityBelow: Int): PlacedRow?

    /** The shown picture before the picture [id] in the list, as [next] reads the one after it. */
    @Query("$PLACED_ROW WHERE $SHOWN AND p.rank < $FROM_RANK ORDER BY p.rank DESC LIMIT 1")
    abstract suspend fun previous(id: String, rank: Long, chain: String, nudityBelow: Int): PlacedRow?

    /**
     * A picture by id if the list shows it under the filter [nudityBelow] (a hidden or filtered one is not
     * found), with its place and the list size.
     */
    @Query("$PLACED_ROW WHERE p.id = :id AND $SHOWN")
    abstract suspend fun placed(id: String, chain: String, nudityBelow: Int): PlacedRow?

    /** A picture by id unless hidden as a duplicate, whatever its nudity level (tests). */
    @Query("$ROW WHERE p.id = :id AND p.hidden = 0")
    abstract suspend fun row(id: String, chain: String): CatalogRow?

    @Query("SELECT * FROM picture WHERE id = :id")
    abstract suspend fun picture(id: String): PictureEntity?

    /** New pictures; each needs a rank no other row has (a negative one until [rerank]). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPictures(pictures: List<PictureEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertLabels(labels: List<LabelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertLinks(links: List<PictureEntityLink>)

    @Query("SELECT id, source, sourceRank, commonsFile, museumRef, rank, hidden FROM picture")
    protected abstract suspend fun rankKeys(): List<RankKey>

    /** The lowest rank in use; `null` for an empty table. */
    @Query("SELECT MIN(rank) FROM picture")
    abstract suspend fun lowestRank(): Long?

    @Query("UPDATE picture SET rank = :rank, hidden = :hidden WHERE id = :id")
    protected abstract suspend fun place(id: String, rank: Long, hidden: Boolean)

    /** Raises the nudity of the pictures [ids] to [level], leaving a stricter one as it is. */
    @Query("UPDATE picture SET nudity = :level WHERE id IN (:ids) AND nudity < :level")
    protected abstract suspend fun raiseNudity(ids: List<String>, level: Int): Int

    /**
     * Raises each stored Wikidata row to at least the level the built-in list gives it ([Nudity.listed]), in
     * one transaction: the import computes the level ([Nudity.levelOf]), but a release already imported is not
     * read again when a newer app version brings a longer list (an unchanged file is a 304). Never lowers a
     * level (the release or the genre may set a stricter one); writes no row whose level is already there.
     */
    @Transaction
    open suspend fun markListed(): Int {
        var raised = 0
        for ((level, ids) in listOf(NudityLevel.EXPLICIT to Nudity.EXPLICIT, NudityLevel.NUDE to Nudity.NUDE)) {
            ids.map { "${CatalogRank.WIKIDATA}:$it" }.chunked(LISTED_CHUNK).forEach { raised += raiseNudity(it, level.ordinal) }
        }
        return raised
    }

    /**
     * Recomputes every rank and the dedupe ([CatalogRank]) in one transaction, writing only the rows that
     * change (none for the starter set going into an empty database, which is written at its places). The
     * moving rows go to free negative ranks first, then to their new ones, so the unique index holds at every
     * step.
     */
    @Transaction
    open suspend fun rerank() {
        val changes = CatalogRank.changes(rankKeys())
        if (changes.isEmpty()) return
        val free = CatalogRank.freeBelow(lowestRank())
        changes.forEachIndexed { i, p -> place(p.id, free - i, p.hidden) }
        changes.forEach { p -> place(p.id, p.rank, p.hidden) }
    }

    private companion object {
        /** Ids per statement of [markListed]: below the 999 bound parameters of SQLite before 3.32. */
        const val LISTED_CHUNK = 900
    }
}

@Database(
    entities = [
        PictureEntity::class, LabelEntity::class, PictureEntityLink::class, CatalogFileEntity::class,
        PictureStage::class, LabelStage::class, PictureEntityStage::class,
    ],
    version = 2,
)
@TypeConverters(CatalogConverters::class)
abstract class CatalogDb : RoomDatabase() {
    abstract fun catalog(): CatalogDao

    /** The staging tables and the swap ([CatalogImport]). */
    abstract fun imports(): ImportDao

    companion object {
        const val NAME = "catalog.db"

        /**
         * The database file [name]. Any other schema version, older or newer, is dropped and created empty:
         * the catalog downloads and the starter set imports again.
         */
        fun open(context: Context, name: String = NAME): CatalogDb =
            configure(Room.databaseBuilder(context, CatalogDb::class.java, name)).build()

        /**
         * The builder settings every catalog database gets, tests included. `fallbackToDestructiveMigration`
         * alone covers an upgrade and a downgrade; adding `fallbackToDestructiveMigrationOnDowngrade` after it
         * would make an upgrade ask for a migration again (Room 2.6 sets `requireMigration` back to true there).
         */
        fun configure(builder: Builder<CatalogDb>): Builder<CatalogDb> = builder
            .fallbackToDestructiveMigration()
    }
}
