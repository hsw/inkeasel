package io.github.hsw.inkeasel.catalog

import io.github.hsw.inkeasel.Picture
import io.github.hsw.inkeasel.ViewerState
import io.github.hsw.inkeasel.ViewerSteps

/**
 * The viewer's steps through the catalog list, read from the database rather than from the pages the grid
 * has loaded: the shown neighbour by rank ([CatalogDao.next], [CatalogDao.previous]) with its place and the
 * list's size for "n / N", each step one statement, so an import or a live refresh that commits meanwhile
 * cannot mix two states of the list. Texts over the [chain] ([LanguageChain]), under the nudity filter in force
 * when the viewer opened ([nudityBelow]; a change closes the viewer); [picture] turns a row into what the
 * viewer shows (as the grid does, so the thumbnail it stands in with is the card's).
 */
class CatalogSteps(
    private val dao: CatalogDao,
    chain: List<String>,
    private val nudityBelow: Int,
    private val picture: (CatalogRow) -> Picture,
) : ViewerSteps {
    private val key = CatalogRow.chainKey(chain)

    /**
     * The viewer on the picture [id] at its place in the list; `null` when the list does not show it (gone,
     * hidden as a duplicate or filtered out since the grid loaded it): the viewer then shows it alone.
     */
    suspend fun open(id: String): ViewerState? = dao.placed(id, key, nudityBelow)?.let(::state)

    /**
     * The neighbour of the picture shown, by its rank as stored now (an import or a live refresh may have
     * moved it since it was read); by the rank it was read with if it is gone or hidden since. A picture shown
     * alone ([ViewerState.rank] `null`) has no neighbours.
     */
    override suspend fun step(from: ViewerState, delta: Int): ViewerState? {
        val read = from.rank ?: return null
        val id = from.picture.id
        val placed = when {
            delta > 0 -> dao.next(id, read, key, nudityBelow)
            delta < 0 -> dao.previous(id, read, key, nudityBelow)
            else -> null
        }
        return placed?.let(::state)
    }

    private fun state(p: PlacedRow) = ViewerState(picture(p.row), p.position, p.count, p.row.rank)
}
