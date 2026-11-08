package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogBackTest {
    @Test
    fun deepInTheGrid_scrollsToTheTopAndFocusesTheTab() {
        val back = CatalogBack.of(focusInContent = true, scrolledDown = true)
        assertEquals(CatalogBack(scrollToTop = true, focusTab = true), back)
        assertTrue(back.taken)
    }

    @Test
    fun inTheFirstRowOrOnRetry_focusesTheTabOnly() {
        val back = CatalogBack.of(focusInContent = true, scrolledDown = false)
        assertEquals(CatalogBack(scrollToTop = false, focusTab = true), back)
        assertTrue(back.taken)
    }

    @Test
    fun scrolledByTouch_focusElsewhere_scrollsToTheTopOnly() {
        val back = CatalogBack.of(focusInContent = false, scrolledDown = true)
        assertEquals(CatalogBack(scrollToTop = true, focusTab = false), back)
        assertTrue(back.taken)
    }

    @Test
    fun onTheHeaderWithTheGridAtTheTop_leavesTheApp() {
        assertFalse(CatalogBack.of(focusInContent = false, scrolledDown = false).taken)
    }
}
