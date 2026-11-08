package io.github.hsw.inkeasel

import io.github.hsw.inkeasel.PosterSwitch.Confirm
import io.github.hsw.inkeasel.PosterSwitch.Press
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterSwitchTest {
    @Test
    fun checked_onlyWhenWantedAndGranted() {
        assertTrue(PosterSwitch.checked(wanted = true, access = true))
        assertFalse(PosterSwitch.checked(wanted = true, access = false)) // never "on" without the access
        assertFalse(PosterSwitch.checked(wanted = false, access = true))
        assertFalse(PosterSwitch.checked(wanted = false, access = false))
    }

    @Test
    fun press_whileOn_turnsOff() {
        assertEquals(Press.TURN_OFF, PosterSwitch.press(wanted = true, access = true))
    }

    @Test
    fun press_whileOffWithAccess_turnsOn() {
        assertEquals(Press.TURN_ON, PosterSwitch.press(wanted = false, access = true))
    }

    @Test
    fun press_withoutAccess_disclosesFirst_whateverIsWanted() {
        assertEquals(Press.DISCLOSE, PosterSwitch.press(wanted = false, access = false))
        assertEquals(Press.DISCLOSE, PosterSwitch.press(wanted = true, access = false)) // shown off, so a press means "on"
    }

    @Test
    fun press_withAccess_isNotDisclosed() {
        for (wanted in listOf(true, false)) {
            assertNotEquals(Press.DISCLOSE, PosterSwitch.press(wanted, access = true))
        }
    }

    @Test
    fun confirm_withAGrantScreen_setsPendingAndGrants() {
        assertEquals(Confirm.SET_PENDING_AND_GRANT, PosterSwitch.confirm(grantAvailable = true))
    }

    @Test
    fun confirm_withoutAGrantScreen_showsTheAdbHint() {
        assertEquals(Confirm.SHOW_ADB_HINT, PosterSwitch.confirm(grantAvailable = false))
    }

    @Test
    fun confirm_followsTheSettingsGrantScreen() {
        // what the settings pass: whether ListenerAccessUi has a screen to open
        val adbOnly = ListenerAccessUi.of(false, direct = false, apps = false, sdk = 26, tv = true, lowRam = false, sideloaded = true)
        val phone = ListenerAccessUi.of(false, direct = true, apps = false, sdk = 35, tv = false, lowRam = false, sideloaded = true)
        assertEquals(Confirm.SHOW_ADB_HINT, PosterSwitch.confirm(adbOnly.grant != null))
        assertEquals(Confirm.SET_PENDING_AND_GRANT, PosterSwitch.confirm(phone.grant != null))
    }

    @Test
    fun enableOnResume_onlyAPendingWishOnceGranted() {
        assertTrue(PosterSwitch.enableOnResume(access = true, pending = true))
        assertFalse(PosterSwitch.enableOnResume(access = false, pending = true)) // still waiting: stays pending
        assertFalse(PosterSwitch.enableOnResume(access = true, pending = false))
        assertFalse(PosterSwitch.enableOnResume(access = false, pending = false))
    }

    @Test
    fun revokedAccess_showsOff_andGrantingAgainRestoresTheWish() {
        assertFalse(PosterSwitch.checked(wanted = true, access = false))
        assertTrue(PosterSwitch.checked(wanted = true, access = true))
    }
}
