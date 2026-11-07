package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogLayoutTest {
    private fun layout(w: Int) = CatalogLayout.forWidth(w)

    @Test
    fun phonePortrait_twoColumns_narrowPadding_splitHeader() {
        assertEquals(CatalogLayout(columns = 2, sidePadding = 16, gap = 12, splitHeader = true), layout(411))
        assertEquals(CatalogLayout(columns = 2, sidePadding = 16, gap = 12, splitHeader = true), layout(599))
    }

    @Test
    fun from600dp_threeColumns_splitBelow720dp() {
        assertEquals(CatalogLayout(columns = 3, sidePadding = 32, gap = 16, splitHeader = true), layout(600))
        assertEquals(CatalogLayout(columns = 3, sidePadding = 32, gap = 16, splitHeader = true), layout(719))
    }

    @Test
    fun from720dp_oneHeaderRow_threeColumnsUntil840dp() {
        assertEquals(CatalogLayout(columns = 3, sidePadding = 32, gap = 16, splitHeader = false), layout(720))
        assertEquals(CatalogLayout(columns = 3, sidePadding = 32, gap = 16, splitHeader = false), layout(839))
    }

    @Test
    fun from840dp_fourColumns_tvPadding() {
        assertEquals(CatalogLayout(columns = 4, sidePadding = 48, gap = 20, splitHeader = false), layout(840))
    }

    @Test
    fun tv_fourColumns_48dpPadding_oneHeaderRow() {
        assertEquals(CatalogLayout(columns = 4, sidePadding = 48, gap = 20, splitHeader = false), layout(960))
    }

    @Test
    fun degenerateWidths_stillUsable() {
        assertEquals(2, layout(0).columns)
        assertEquals(4, layout(4000).columns)
    }
}
