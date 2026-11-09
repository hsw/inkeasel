package io.github.hsw.inkeasel

/**
 * What the Catalog tab shows: its grid, the text that the catalog has no picture yet, that the nudity filter
 * leaves none, or that it is loading.
 */
enum class CatalogView {
    GRID,
    NONE,
    FILTERED,
    LOADING;

    companion object {
        /**
         * Over a paged list of [itemCount] rows (placeholders included), with the store's [empty] (the starter
         * step ran and left the database empty, [io.github.hsw.inkeasel.catalog.CatalogStore.empty]), whether
         * the list is [refreshing] and whether its read reached the end ([endReached]: the pager's append
         * state, false before the list has been read at all): any row shows the grid; an empty database not
         * being read says the catalog has none yet; a list read to its end with no row while the database has
         * pictures says that the nudity filter leaves none (the store counts the database without it);
         * anything else is still loading (the starter set is on its way). Pure.
         */
        fun of(itemCount: Int, empty: Boolean, refreshing: Boolean, endReached: Boolean = false): CatalogView = when {
            itemCount > 0 -> GRID
            empty && !refreshing -> NONE
            !refreshing && endReached -> FILTERED
            else -> LOADING
        }
    }
}
