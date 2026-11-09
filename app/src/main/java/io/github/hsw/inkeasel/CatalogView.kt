package io.github.hsw.inkeasel

/** What the Catalog tab shows: its grid, the text that the catalog has no picture yet, or that it is loading. */
enum class CatalogView {
    GRID,
    NONE,
    LOADING;

    companion object {
        /**
         * Over a paged list of [itemCount] rows (placeholders included), with the store's [empty] (the starter
         * step ran and left the database empty, [io.github.hsw.inkeasel.catalog.CatalogStore.empty]) and
         * whether the list is [refreshing]: any row shows the grid; an empty database not being read says the
         * catalog has none yet; anything else is still loading (the starter set is on its way). Pure.
         */
        fun of(itemCount: Int, empty: Boolean, refreshing: Boolean): CatalogView = when {
            itemCount > 0 -> GRID
            empty && !refreshing -> NONE
            else -> LOADING
        }
    }
}
