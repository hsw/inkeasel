package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerAccessUiTest {
    private fun of(enabled: Boolean = false, canOpen: Boolean = true, sdk: Int = 35, tv: Boolean = false, sideloaded: Boolean = true) =
        ListenerAccessUi.of(enabled, canOpen, sdk, tv, sideloaded)

    @Test
    fun enabled_statusOnly() {
        for (canOpen in listOf(true, false)) for (sdk in listOf(26, 30, 33, 35)) {
            assertEquals(
                ListenerAccessUi(Txt(R.string.access_granted), openButton = false, adbHint = false, restrictedHint = false),
                of(enabled = true, canOpen = canOpen, sdk = sdk),
            )
        }
    }

    @Test
    fun offWithAScreen_button_noAdbHint() {
        val ui = of(sdk = 30)
        assertEquals(Txt(R.string.access_missing), ui.status)
        assertTrue(ui.openButton)
        assertFalse(ui.adbHint)
        assertFalse(ui.restrictedHint) // Android 11-12: no restricted settings yet
    }

    @Test
    fun offWithoutAScreen_adbHint_noButton() {
        for (sdk in listOf(30, 34)) { // Android TV 11 (Shield), or a TV build without the screen
            val ui = of(canOpen = false, sdk = sdk, tv = true)
            assertEquals(Txt(R.string.access_missing), ui.status)
            assertFalse(ui.openButton)
            assertTrue(ui.adbHint)
            assertFalse(ui.restrictedHint) // adb grants are not restricted
        }
        assertFalse(of(canOpen = false, sdk = 34).restrictedHint)
    }

    @Test
    fun restrictedHint_fromAndroid13_onlyWhileOffWithAScreen() {
        assertFalse(of(sdk = 32).restrictedHint)
        for (sdk in listOf(33, 34, 35)) {
            val ui = of(sdk = sdk)
            assertTrue(ui.restrictedHint)
            assertTrue(ui.openButton)
        }
        assertFalse(of(enabled = true).restrictedHint)
    }

    @Test
    fun restrictedHint_notForAStoreInstall() {
        val ui = of(sideloaded = false)
        assertFalse(ui.restrictedHint)
        assertTrue(ui.openButton)
    }

    @Test
    fun androidTv_listScreen_buttonWithoutRestrictedHint() {
        val ui = of(sdk = 34, tv = true) // Android TV 14
        assertTrue(ui.openButton)
        assertFalse(ui.adbHint)
        assertFalse(ui.restrictedHint)
    }
}
