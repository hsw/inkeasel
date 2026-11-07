package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogLayoutTest {
    private fun layout(w: Int) = CatalogLayout.forWidth(w)

    @Test
    fun `a phone in portrait gets two columns, narrow padding and a split header`() {
        assertEquals(CatalogLayout(columns = 2, sidePadding = 16, gap = 12, splitHeader = true), layout(411))
        assertEquals(CatalogLayout(columns = 2, sidePadding = 16, gap = 12, splitHeader = true), layout(599))
    }

    @Test
    fun `from 600 dp three columns, still split below 720 dp`() {
        assertEquals(CatalogLayout(columns = 3, sidePadding = 32, gap = 16, splitHeader = true), layout(600))
        assertEquals(CatalogLayout(columns = 3, sidePadding = 32, gap = 16, splitHeader = true), layout(719))
    }

    @Test
    fun `from 720 dp the header is one row, three columns until 840 dp`() {
        assertEquals(CatalogLayout(columns = 3, sidePadding = 32, gap = 16, splitHeader = false), layout(720))
        assertEquals(CatalogLayout(columns = 3, sidePadding = 32, gap = 16, splitHeader = false), layout(839))
    }

    @Test
    fun `from 840 dp four columns and the TV padding`() {
        assertEquals(CatalogLayout(columns = 4, sidePadding = 48, gap = 20, splitHeader = false), layout(840))
    }

    @Test
    fun `a TV keeps four columns, 48 dp padding and a single header row`() {
        assertEquals(CatalogLayout(columns = 4, sidePadding = 48, gap = 20, splitHeader = false), layout(960))
    }

    @Test
    fun `degenerate widths still give a usable layout`() {
        assertEquals(2, layout(0).columns)
        assertEquals(4, layout(4000).columns)
    }
}
