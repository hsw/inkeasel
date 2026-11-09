package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogLayoutTest {
    private fun layout(w: Int) = CatalogLayout.forWidth(w)

    @Test
    fun phonePortrait_twoColumns_narrowPadding_splitHeader() {
        val phone = CatalogLayout(columns = 2, sidePadding = 16, verticalPadding = 24, gap = 12, splitHeader = true)
        assertEquals(phone, layout(411))
        assertEquals(phone, layout(599))
    }

    @Test
    fun from600dp_threeColumns_splitBelow720dp() {
        val split = CatalogLayout(columns = 3, sidePadding = 32, verticalPadding = 24, gap = 16, splitHeader = true)
        assertEquals(split, layout(600))
        assertEquals(split, layout(719))
    }

    @Test
    fun from720dp_oneHeaderRow_threeColumnsUntil840dp() {
        val oneRow = CatalogLayout(columns = 3, sidePadding = 32, verticalPadding = 24, gap = 16, splitHeader = false)
        assertEquals(oneRow, layout(720))
        assertEquals(oneRow, layout(839))
    }

    @Test
    fun from840dp_fourColumns_tvPadding() {
        assertEquals(CatalogLayout(columns = 4, sidePadding = 48, verticalPadding = 27, gap = 20, splitHeader = false), layout(840))
    }

    @Test
    fun tv_fourColumns_tvOverscanMargins_oneHeaderRow() {
        assertEquals(CatalogLayout(columns = 4, sidePadding = 48, verticalPadding = 27, gap = 20, splitHeader = false), layout(960))
    }

    @Test
    fun verticalPadding_24dpBelow840dp_27dpFrom840dp() {
        listOf(0, 411, 599, 600, 719, 720, 839).forEach { assertEquals("width $it", 24, layout(it).verticalPadding) }
        listOf(840, 960, 1280, 4000).forEach { assertEquals("width $it", 27, layout(it).verticalPadding) }
    }

    @Test
    fun degenerateWidths_stillUsable() {
        assertEquals(2, layout(0).columns)
        assertEquals(4, layout(4000).columns)
        assertEquals(0, layout(0).cardWidth(0))
    }

    @Test
    fun cardWidth_whatIsLeftAfterPaddingAndGaps() {
        assertEquals(201, layout(960).cardWidth(960)) // TV: (960 - 2·48 - 3·20) / 4
        assertEquals(183, layout(411).cardWidth(411)) // phone: (411 - 2·16 - 12) / 2
        assertEquals(234, layout(800).cardWidth(800)) // (800 - 2·32 - 2·16) / 3
    }
}
