package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogBackTest {
    @Test
    fun deepInTheGrid_scrollsToTheTopAndFocusesTheTab() {
        val back = CatalogBack.of(focusInContent = true, gridItems = 120, canScrollBack = true)
        assertEquals(CatalogBack(scrollToTop = true, focusTab = true), back)
        assertTrue(back.taken)
    }

    @Test
    fun inTheFirstRowOrOnRetry_focusesTheTabOnly() {
        val back = CatalogBack.of(focusInContent = true, gridItems = 120, canScrollBack = false)
        assertEquals(CatalogBack(scrollToTop = false, focusTab = true), back)
        assertTrue(back.taken)
    }

    @Test
    fun scrolledByTouch_focusElsewhere_scrollsToTheTopOnly() {
        val back = CatalogBack.of(focusInContent = false, gridItems = 120, canScrollBack = true)
        assertEquals(CatalogBack(scrollToTop = true, focusTab = false), back)
        assertTrue(back.taken)
    }

    @Test
    fun onTheHeaderWithTheGridAtTheTop_leavesTheApp() {
        assertFalse(CatalogBack.of(focusInContent = false, gridItems = 120, canScrollBack = false).taken)
    }

    @Test
    fun pagedList_placeholdersCountAsCards() {
        // a paged Catalog counts the cards not loaded yet: scrolled far down, Back still scrolls to the top
        val back = CatalogBack.of(focusInContent = true, gridItems = 9602, canScrollBack = true)
        assertEquals(CatalogBack(scrollToTop = true, focusTab = true), back)
    }

    @Test
    fun noGridShown_nothingToScroll() {
        // the Catalog while its starter set imports (no item yet), or a tab that failed: the grid state of an
        // earlier list does not make Back scroll
        assertEquals(
            CatalogBack(scrollToTop = false, focusTab = false),
            CatalogBack.of(focusInContent = false, gridItems = 0, canScrollBack = true),
        )
        assertEquals(
            CatalogBack(scrollToTop = false, focusTab = true),
            CatalogBack.of(focusInContent = true, gridItems = 0, canScrollBack = true),
        )
    }
}
