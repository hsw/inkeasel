package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerAccessUiTest {
    private val adb = "adb shell cmd notification allow_listener app/app.Listener"

    @Test
    fun enabled_statusOnly() {
        for (canOpen in listOf(true, false)) for (sdk in listOf(26, 30, 33, 35)) {
            val ui = ListenerAccessUi.of(enabled = true, canOpen = canOpen, sdk = sdk, tv = false, adbCommand = adb)
            assertEquals(ListenerAccessUi(Txt(R.string.access_granted), openButton = false, adbHint = null, restrictedHint = null), ui)
        }
    }

    @Test
    fun offWithAScreen_button_noAdbHint() {
        val ui = ListenerAccessUi.of(enabled = false, canOpen = true, sdk = 30, tv = false, adbCommand = adb)
        assertEquals(Txt(R.string.access_missing), ui.status)
        assertTrue(ui.openButton)
        assertNull(ui.adbHint)
        assertNull(ui.restrictedHint) // Android 11-12: no restricted settings yet
    }

    @Test
    fun offWithoutAScreen_adbHint_noButton() {
        for (sdk in listOf(30, 34)) { // Android TV 11 (Shield), or a TV build without the screen
            val ui = ListenerAccessUi.of(enabled = false, canOpen = false, sdk = sdk, tv = true, adbCommand = adb)
            assertEquals(Txt(R.string.access_missing), ui.status)
            assertFalse(ui.openButton)
            assertEquals(txt(R.string.access_adb, adb), ui.adbHint)
            assertNull(ui.restrictedHint) // adb grants are not restricted
        }
        assertNull(ListenerAccessUi.of(enabled = false, canOpen = false, sdk = 34, tv = false, adbCommand = adb).restrictedHint)
    }

    @Test
    fun restrictedHint_fromAndroid13_onlyWhileOffWithAScreen() {
        assertNull(ListenerAccessUi.of(enabled = false, canOpen = true, sdk = 32, tv = false, adbCommand = adb).restrictedHint)
        for (sdk in listOf(33, 34, 35)) {
            val ui = ListenerAccessUi.of(enabled = false, canOpen = true, sdk = sdk, tv = false, adbCommand = adb)
            assertEquals(Txt(R.string.access_restricted), ui.restrictedHint)
            assertTrue(ui.openButton)
        }
        assertNull(ListenerAccessUi.of(enabled = true, canOpen = true, sdk = 35, tv = false, adbCommand = adb).restrictedHint)
    }

    @Test
    fun androidTv_listScreen_buttonWithoutRestrictedHint() {
        val ui = ListenerAccessUi.of(enabled = false, canOpen = true, sdk = 34, tv = true, adbCommand = adb) // Android TV 14
        assertTrue(ui.openButton)
        assertNull(ui.adbHint)
        assertNull(ui.restrictedHint)
    }
}
