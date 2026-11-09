package io.github.hsw.inkeasel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.hsw.inkeasel.catalog.NudityFilter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogFilterStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun nudity_defaultsToHideExplicit() {
        assertEquals(NudityFilter.HIDE_EXPLICIT, CatalogFilterStore(context).nudity)
    }

    @Test
    fun nudity_persistsAcrossInstances() {
        for (f in NudityFilter.entries) {
            CatalogFilterStore(context).nudity = f
            assertEquals(f, CatalogFilterStore(context).nudity)
        }
    }

    @Test
    fun nudity_unknownStoredValue_isTheDefault() {
        context.getSharedPreferences("catalog", Context.MODE_PRIVATE).edit().putString("nudity", "SOMETHING_ELSE").commit()
        assertEquals(NudityFilter.DEFAULT, CatalogFilterStore(context).nudity)
    }
}
