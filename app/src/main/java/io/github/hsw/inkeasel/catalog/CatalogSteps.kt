package io.github.hsw.inkeasel.catalog

import io.github.hsw.inkeasel.Picture
import io.github.hsw.inkeasel.ViewerState
import io.github.hsw.inkeasel.ViewerSteps

/**
 * The viewer's steps through the catalog list, read from the database rather than from the pages the grid
 * has loaded: the shown neighbour by rank ([CatalogDao.next], [CatalogDao.previous]), with its place
 * ([CatalogDao.position]) and the list's size ([CatalogDao.count]) for "n / N". Texts over the [chain]
 * ([LanguageChain]); [picture] turns a row into what the viewer shows (as the grid does, so the thumbnail it
 * stands in with is the card's).
 */
class CatalogSteps(
    private val dao: CatalogDao,
    chain: List<String>,
    private val picture: (CatalogRow) -> Picture,
) : ViewerSteps {
    private val key = CatalogRow.chainKey(chain)

    /** The viewer on the picture [id] at its place in the list; `null` when the catalog no longer has it. */
    suspend fun open(id: String): ViewerState? = dao.row(id, key)?.let { state(it) }

    override suspend fun step(from: ViewerState, delta: Int): ViewerState? {
        val rank = from.rank ?: return null
        val row = when {
            delta > 0 -> dao.next(rank, key)
            delta < 0 -> dao.previous(rank, key)
            else -> null
        }
        return row?.let { state(it) }
    }

    private suspend fun state(row: CatalogRow) =
        ViewerState(picture(row), dao.position(row.rank), dao.count(), row.rank)
}
