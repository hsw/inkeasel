package io.github.hsw.inkeasel

import android.content.Context
import android.media.tv.TvInputManager
import android.os.Process
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import io.github.hsw.inkeasel.catalog.NudityFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CatalogFilterStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val users: UserManager = context.getSystemService(UserManager::class.java)

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

    @Test
    fun readLock_eachRead_andParentalControlsFirst() {
        assertNull(CatalogFilterStore.readLock({ false }, { false }))
        assertEquals(NudityLock.PARENTAL_CONTROLS, CatalogFilterStore.readLock({ true }, { false }))
        assertEquals(NudityLock.PARENTAL_CONTROLS, CatalogFilterStore.readLock({ true }, { true }))
        assertEquals(NudityLock.RESTRICTED_PROFILE, CatalogFilterStore.readLock({ false }, { true }))
    }

    @Test
    fun readLock_aFailingRead_countsAsOff_andTheOtherStillCounts() {
        val gone = { throw IllegalStateException("system service gone") }
        assertNull(CatalogFilterStore.readLock(gone, gone))
        assertEquals(NudityLock.RESTRICTED_PROFILE, CatalogFilterStore.readLock(gone, { true }))
        assertEquals(NudityLock.PARENTAL_CONTROLS, CatalogFilterStore.readLock({ true }, gone))
    }

    @Test
    fun readLock_deviceWithoutLiveTv_isNotLocked() {
        // Robolectric publishes no TvInputManager: a phone or tablet without android.software.live_tv
        assertNull(context.getSystemService(TvInputManager::class.java))
        assertNull(CatalogFilterStore.readLock(context))
    }

    @Test
    fun readLock_accountsRestricted_isTheProfileLock() {
        shadowOf(users).setUserRestriction(Process.myUserHandle(), UserManager.DISALLOW_MODIFY_ACCOUNTS, true)
        assertEquals(NudityLock.RESTRICTED_PROFILE, CatalogFilterStore.readLock(context))
        shadowOf(users).setUserRestriction(Process.myUserHandle(), UserManager.DISALLOW_MODIFY_ACCOUNTS, false)
        assertNull(CatalogFilterStore.readLock(context))
    }

    @Test
    fun readLock_otherRestriction_isNotLocked() {
        shadowOf(users).setUserRestriction(Process.myUserHandle(), UserManager.DISALLOW_INSTALL_APPS, true)
        assertNull(CatalogFilterStore.readLock(context))
    }
}
