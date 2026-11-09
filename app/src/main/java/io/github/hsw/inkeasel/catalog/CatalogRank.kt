package io.github.hsw.inkeasel.catalog

/**
 * What the rank rules need of one stored picture, and its current [rank] and [hidden] state. [source] is the
 * id's prefix ([CatalogRank.WIKIDATA] for the release's rows; `cma`, `commons` for the live sources);
 * [sourceRank] is the row's position in its source: the line in `core` for a Wikidata row, the place in the
 * source's list for a live one.
 */
data class RankKey(
    val id: String,
    val source: String,
    val sourceRank: Int?,
    val commonsFile: String?,
    val museumRef: String?,
    val rank: Long,
    val hidden: Boolean,
)

/** Where a picture goes: its unique [rank] (the list order) and whether it is [hidden] as a duplicate. */
data class Placement(val id: String, val rank: Long, val hidden: Boolean)

/**
 * The catalog's order and its dedupe, pure, computed in the write transaction after each import or live
 * refresh (never in the paging query):
 * - Wikidata rows in `core` order, at `10 · i` for the i-th of them;
 * - a live row that duplicates a Wikidata row is [Placement.hidden]: a Commons file of the same name, or a
 *   museum accession number equal to the Wikidata row's (Cleveland: Wikidata P11110);
 * - the other live rows take turns by source (sources by name, each in its own order) and every
 *   [LIVE_EVERY]-th card is one of them: the j-th goes at `10 · k + 5` right after Wikidata row
 *   `k = 9 · j + 8`; those past the last Wikidata row follow it, one after another;
 * - hidden rows come last, so every rank stays unique.
 */
object CatalogRank {
    const val WIKIDATA = "wd"

    /** Every this many cards one is a live picture, while Wikidata rows last. */
    const val LIVE_EVERY = 10

    private const val STEP = 10L
    private const val BETWEEN = 5L

    /**
     * The rank of the [line]-th Wikidata row while no other row is stored: the starter set going into an empty
     * database is written at its places at once, and [place] then moves nothing.
     */
    fun wikidataRank(line: Int): Long = STEP * line

    fun place(rows: List<RankKey>): List<Placement> {
        val wikidata = rows.filter { it.source == WIKIDATA }.sortedWith(ORDER)
        val files = wikidata.mapNotNullTo(HashSet()) { it.commonsFile?.let(::fileKey) }
        val refs = wikidata.mapNotNullTo(HashSet()) { it.museumRef?.let(::refKey) }
        val (hidden, shown) = rows.filter { it.source != WIKIDATA }.partition { live ->
            live.commonsFile?.let(::fileKey) in files || live.museumRef?.let(::refKey) in refs
        }
        val placed = ArrayList<Placement>(rows.size)
        wikidata.forEachIndexed { i, row -> placed += Placement(row.id, wikidataRank(i), false) }
        val n = wikidata.size.toLong()
        var tail = 0L
        alternate(shown).forEachIndexed { j, row ->
            val after = (LIVE_EVERY - 1L) * (j + 1) - 1
            val rank = if (after < n) STEP * after + BETWEEN else STEP * (n + tail++) + BETWEEN
            placed += Placement(row.id, rank, false)
        }
        hidden.sortedWith(compareBy<RankKey> { it.source }.then(ORDER)).forEach {
            placed += Placement(it.id, STEP * (n + tail++) + BETWEEN, true)
        }
        return placed
    }

    /** The placements of [rows] that differ from their current rank or hidden state. */
    fun changes(rows: List<RankKey>): List<Placement> {
        val now = rows.associateBy { it.id }
        return place(rows).filter { p -> now.getValue(p.id).let { it.rank != p.rank || it.hidden != p.hidden } }
    }

    /**
     * Whether writing [changes] straight over [rows] could break the unique rank: some row's new rank is held
     * now by another row. When none is, each row can move to its rank at once; otherwise the moving rows go
     * to free ranks first.
     */
    fun clash(rows: List<RankKey>, changes: List<Placement>): Boolean {
        val held = HashMap<Long, String>(rows.size * 2)
        rows.forEach { held[it.rank] = it.id }
        return changes.any { p -> held[p.rank].let { it != null && it != p.id } }
    }

    /** Commons file names as the same file: underscores are spaces in a page title. */
    fun fileKey(name: String): String = name.replace('_', ' ').trim()

    private fun refKey(ref: String): String = ref.trim()

    /** In the source's order; a row without a position after those with one; ties by id. */
    private val ORDER = compareBy<RankKey, Int?>(nullsLast<Int>()) { it.sourceRank }.thenBy { it.id }

    /** The rows of each source in its order, the sources taking turns (by name), the longest list finishing. */
    private fun alternate(rows: List<RankKey>): List<RankKey> {
        val lists = rows.groupBy { it.source }.toSortedMap().values.map { it.sortedWith(ORDER) }
        val out = ArrayList<RankKey>(rows.size)
        var i = 0
        while (out.size < rows.size) {
            lists.forEach { list -> list.getOrNull(i)?.let(out::add) }
            i++
        }
        return out
    }
}
