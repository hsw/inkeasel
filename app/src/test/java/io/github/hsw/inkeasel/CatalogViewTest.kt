package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogViewTest {
    @Test
    fun catalogView_anyRowShowsTheGrid() {
        assertEquals(CatalogView.GRID, CatalogView.of(1, empty = false, refreshing = false))
        // rows already there stay while a new pass reads the list again, even with a stale "empty"
        assertEquals(CatalogView.GRID, CatalogView.of(9_818, empty = true, refreshing = true))
        assertEquals(CatalogView.GRID, CatalogView.of(3, empty = false, refreshing = false, endReached = true))
    }

    @Test
    fun catalogView_noneOnlyWhenTheStarterStepLeftItEmptyAndNothingIsBeingRead() {
        assertEquals(CatalogView.NONE, CatalogView.of(0, empty = true, refreshing = false))
        assertEquals(CatalogView.NONE, CatalogView.of(0, empty = true, refreshing = false, endReached = true))
        // the list is being read: it may still bring rows
        assertEquals(CatalogView.LOADING, CatalogView.of(0, empty = true, refreshing = true))
        // the starter set is on its way (the store has not said it is empty)
        assertEquals(CatalogView.LOADING, CatalogView.of(0, empty = false, refreshing = false))
        assertEquals(CatalogView.LOADING, CatalogView.of(0, empty = false, refreshing = true))
    }

    @Test
    fun catalogView_filteredWhenTheListWasReadToItsEndWithNoRowWhileTheDatabaseHasPictures() {
        assertEquals(CatalogView.FILTERED, CatalogView.of(0, empty = false, refreshing = false, endReached = true))
        // a new filter's list is being read: it may still bring rows
        assertEquals(CatalogView.LOADING, CatalogView.of(0, empty = false, refreshing = true, endReached = true))
    }
}
