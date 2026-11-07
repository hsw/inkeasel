package io.github.hsw.inkeasel

/**
 * How the catalog screen (and the settings dialog's side padding) adapts to the window width, in dp, measured
 * outside the layout padding: grid [columns], [sidePadding] and the [gap] between cards; [splitHeader] puts the
 * tabs on their own row under the title and the frame button (one row needs about 700 dp with the frame button).
 * A TV (960 dp wide) keeps 4 columns, 48 dp padding and a single header row.
 */
data class CatalogLayout(val columns: Int, val sidePadding: Int, val gap: Int, val splitHeader: Boolean) {
    companion object {
        private const val MEDIUM = 600
        private const val SPLIT_BELOW = 720
        private const val WIDE = 840

        fun forWidth(widthDp: Int): CatalogLayout = when {
            widthDp < MEDIUM -> CatalogLayout(columns = 2, sidePadding = 16, gap = 12, splitHeader = true)
            widthDp < WIDE -> CatalogLayout(columns = 3, sidePadding = 32, gap = 16, splitHeader = widthDp < SPLIT_BELOW)
            else -> CatalogLayout(columns = 4, sidePadding = 48, gap = 20, splitHeader = false)
        }
    }
}
