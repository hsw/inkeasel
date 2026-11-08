package io.github.hsw.inkeasel

import io.github.hsw.inkeasel.PosterSwitch.Press
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun press_withoutAccess_startsTheGrant_whateverIsWanted() {
        assertEquals(Press.GRANT, PosterSwitch.press(wanted = false, access = false))
        assertEquals(Press.GRANT, PosterSwitch.press(wanted = true, access = false)) // shown off, so a press means "on"
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
