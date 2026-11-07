package io.github.hsw.inkeasel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlePermissionsTest {
    private val scan = "android.permission.BLUETOOTH_SCAN"
    private val connect = "android.permission.BLUETOOTH_CONNECT"
    private val both = listOf(scan, connect)

    private fun blocked(granted: Boolean, deniedBefore: Boolean, vararg withRationale: String) =
        BlePermissions.blocked(both, granted, deniedBefore) { it in withRationale }

    @Test
    fun granted_neverBlocked() {
        assertFalse(blocked(granted = true, deniedBefore = true))
    }

    @Test
    fun deniedOnce_withRationale_canBeAskedAgain() {
        assertFalse(blocked(granted = false, deniedBefore = true, scan, connect))
    }

    @Test
    fun dialogDismissedOnFirstRequest_notBlocked() {
        // no rationale yet: nothing was ever denied
        assertFalse(blocked(granted = false, deniedBefore = false))
    }

    @Test
    fun noRationaleAfterEarlierDenial_blocked() {
        assertTrue(blocked(granted = false, deniedBefore = true))
    }

    @Test
    fun onePermissionOfTheSet_canStillBeAsked() {
        assertFalse(blocked(granted = false, deniedBefore = true, connect))
        assertFalse(blocked(granted = false, deniedBefore = true, scan))
    }

    @Test
    fun singlePermissionSet_followsItsRationale() {
        val location = listOf("android.permission.ACCESS_FINE_LOCATION")
        assertTrue(BlePermissions.blocked(location, granted = false, deniedBefore = true) { false })
        assertFalse(BlePermissions.blocked(location, granted = false, deniedBefore = true) { true })
    }
}
