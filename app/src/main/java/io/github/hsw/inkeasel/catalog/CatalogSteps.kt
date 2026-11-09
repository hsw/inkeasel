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

    /**
     * The viewer on the picture [id] at its place in the list; `null` when the list does not show it (gone,
     * or hidden as a duplicate since the grid loaded it): the viewer then shows it alone.
     */
    suspend fun open(id: String): ViewerState? = dao.row(id, key)?.let { state(it) }

    /**
     * The neighbour of the picture shown, by its rank as stored now (an import or a live refresh may have
     * moved it since it was read); by the rank it was read with if it is gone or hidden since. A picture shown
     * alone ([ViewerState.rank] `null`) has no neighbours.
     */
    override suspend fun step(from: ViewerState, delta: Int): ViewerState? {
        val read = from.rank ?: return null
        val rank = dao.picture(from.picture.id)?.takeIf { !it.hidden }?.rank ?: read
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
