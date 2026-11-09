package io.github.hsw.inkeasel

import android.content.Context
import android.media.tv.TvInputManager
import android.os.Process
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NudityLockReaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val users: UserManager = context.getSystemService(UserManager::class.java)

    @Test
    fun read_eachRead_andParentalControlsFirst() {
        assertNull(NudityLockReader.read({ false }, { false }))
        assertEquals(NudityLock.PARENTAL_CONTROLS, NudityLockReader.read({ true }, { false }))
        assertEquals(NudityLock.PARENTAL_CONTROLS, NudityLockReader.read({ true }, { true }))
        assertEquals(NudityLock.RESTRICTED_PROFILE, NudityLockReader.read({ false }, { true }))
    }

    @Test
    fun read_aFailingRead_countsAsOff_andTheOtherStillCounts() {
        val gone = { throw IllegalStateException("system service gone") }
        assertNull(NudityLockReader.read(gone, gone))
        assertEquals(NudityLock.RESTRICTED_PROFILE, NudityLockReader.read(gone, { true }))
        assertEquals(NudityLock.PARENTAL_CONTROLS, NudityLockReader.read({ true }, gone))
    }

    @Test
    fun read_deviceWithoutLiveTv_isNotLocked() {
        // Robolectric publishes no TvInputManager: a phone or tablet without android.software.live_tv
        assertNull(context.getSystemService(TvInputManager::class.java))
        assertNull(NudityLockReader.read(context))
    }

    @Test
    fun read_accountsRestricted_isTheProfileLock() {
        shadowOf(users).setUserRestriction(Process.myUserHandle(), UserManager.DISALLOW_MODIFY_ACCOUNTS, true)
        assertEquals(NudityLock.RESTRICTED_PROFILE, NudityLockReader.read(context))
        shadowOf(users).setUserRestriction(Process.myUserHandle(), UserManager.DISALLOW_MODIFY_ACCOUNTS, false)
        assertNull(NudityLockReader.read(context))
    }

    @Test
    fun read_otherRestriction_isNotLocked() {
        shadowOf(users).setUserRestriction(Process.myUserHandle(), UserManager.DISALLOW_INSTALL_APPS, true)
        assertNull(NudityLockReader.read(context))
    }
}
