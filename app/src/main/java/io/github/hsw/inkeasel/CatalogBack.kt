package io.github.hsw.inkeasel

/**
 * What Back does on the catalog screen, before it leaves the app. Google's TV guidance for a top navigation:
 * Back from the content first returns to the top and the tab row, the next Back leaves. So with the focus in the
 * content below the header (a card, a Retry button) Back moves the focus to the selected tab ([focusTab]), and
 * a grid scrolled down (also by touch, with the focus elsewhere) goes back to its first row ([scrollToTop]).
 * With neither, Back is not [taken] and leaves the app. The settings dialog, its disclosure panel and the share
 * flow keep their own Back. Pure.
 */
data class CatalogBack(val scrollToTop: Boolean, val focusTab: Boolean) {
    /** Back is handled here; otherwise it goes on to the system and leaves the app. */
    val taken: Boolean get() = scrollToTop || focusTab

    companion object {
        fun of(focusInContent: Boolean, scrolledDown: Boolean): CatalogBack =
            CatalogBack(scrollToTop = scrolledDown, focusTab = focusInContent)
    }
}
