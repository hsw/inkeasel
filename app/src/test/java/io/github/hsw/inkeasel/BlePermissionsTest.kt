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
    fun `granted is never blocked`() {
        assertFalse(blocked(granted = true, deniedBefore = true))
    }

    @Test
    fun `denied once, with a rationale, can be asked again`() {
        assertFalse(blocked(granted = false, deniedBefore = true, scan, connect))
    }

    @Test
    fun `dialog dismissed on the first request is not blocked`() {
        // no rationale yet: nothing was ever denied
        assertFalse(blocked(granted = false, deniedBefore = false))
    }

    @Test
    fun `no rationale after an earlier denial is blocked`() {
        assertTrue(blocked(granted = false, deniedBefore = true))
    }

    @Test
    fun `one permission of the set can still be asked`() {
        assertFalse(blocked(granted = false, deniedBefore = true, connect))
        assertFalse(blocked(granted = false, deniedBefore = true, scan))
    }

    @Test
    fun `a single-permission set follows its one rationale`() {
        val location = listOf("android.permission.ACCESS_FINE_LOCATION")
        assertTrue(BlePermissions.blocked(location, granted = false, deniedBefore = true) { false })
        assertFalse(BlePermissions.blocked(location, granted = false, deniedBefore = true) { true })
    }
}
