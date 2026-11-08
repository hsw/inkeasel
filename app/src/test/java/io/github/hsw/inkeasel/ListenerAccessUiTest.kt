package io.github.hsw.inkeasel

import io.github.hsw.inkeasel.ListenerAccessUi.GrantScreen
import io.github.hsw.inkeasel.ListenerAccessUi.MenuHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerAccessUiTest {
    private fun of(
        enabled: Boolean = false,
        direct: Boolean = true,
        apps: Boolean = true,
        sdk: Int = 35,
        tv: Boolean = false,
        lowRam: Boolean = false,
        sideloaded: Boolean = true,
    ) = ListenerAccessUi.of(enabled, direct, apps, sdk, tv, lowRam, sideloaded)

    @Test
    fun enabled_statusOnly() {
        for (direct in listOf(true, false)) for (tv in listOf(true, false)) for (sdk in listOf(26, 30, 33, 35, 36)) {
            val ui = of(enabled = true, direct = direct, sdk = sdk, tv = tv)
            assertEquals(ListenerAccessUi(Txt(R.string.access_granted), null, null, adbHint = false, restrictedHint = false), ui)
            assertFalse(ui.openButton)
        }
    }

    @Test
    fun phone_offWithAScreen_opensItDirectly() {
        val ui = of(sdk = 30)
        assertEquals(Txt(R.string.access_missing), ui.status)
        assertEquals(GrantScreen.DIRECT, ui.grant)
        assertTrue(ui.openButton)
        assertNull(ui.menuHint) // the menu path is a TV thing
        assertFalse(ui.adbHint)
        assertFalse(ui.restrictedHint) // Android 11-12: no restricted settings yet
    }

    @Test
    fun phone_offWithoutAScreen_adbHint_noMenuRoute() {
        val ui = of(direct = false, sdk = 30)
        assertNull(ui.grant)
        assertFalse(ui.openButton)
        assertNull(ui.menuHint)
        assertTrue(ui.adbHint)
        assertFalse(ui.restrictedHint)
    }

    @Test
    fun tv_withoutTheDirectScreen_opensApps_withTheMenuPath() {
        for (sdk in listOf(28, 30)) { // Android TV 9-11 (the Shield)
            val ui = of(direct = false, sdk = sdk, tv = true)
            assertEquals(Txt(R.string.access_missing), ui.status)
            assertEquals(GrantScreen.APPS, ui.grant)
            assertTrue(ui.openButton)
            assertEquals(MenuHint.ONLY, ui.menuHint)
            assertFalse(ui.adbHint)
            assertFalse(ui.restrictedHint)
        }
    }

    @Test
    fun tv_withTheDirectScreen_opensIt_menuPathAsFallback() {
        val ui = of(sdk = 34, tv = true) // Android TV 14, Google TV 14
        assertEquals(GrantScreen.DIRECT, ui.grant)
        assertEquals(MenuHint.FALLBACK, ui.menuHint)
        assertFalse(ui.adbHint)
        assertFalse(ui.restrictedHint)
    }

    @Test
    fun tv8_noMenuItem_adbHintOnly() {
        for (sdk in listOf(26, 27)) {
            val ui = of(direct = false, sdk = sdk, tv = true) // the Apps screen opens, but holds no such switch
            assertNull(ui.grant)
            assertNull(ui.menuHint)
            assertTrue(ui.adbHint)
        }
    }

    @Test
    fun tv_noSettingsScreenAtAll_adbHint() {
        val ui = of(direct = false, apps = false, sdk = 30, tv = true)
        assertNull(ui.grant)
        assertNull(ui.menuHint)
        assertTrue(ui.adbHint)
    }

    @Test
    fun tv_lowRam_hidesTheMenuItem() {
        val withoutDirect = of(direct = false, sdk = 30, tv = true, lowRam = true)
        assertNull(withoutDirect.grant)
        assertTrue(withoutDirect.adbHint)
        val withDirect = of(sdk = 34, tv = true, lowRam = true)
        assertEquals(GrantScreen.DIRECT, withDirect.grant)
        assertNull(withDirect.menuHint)
        assertFalse(withDirect.adbHint)
    }

    @Test
    fun restrictedHint_fromAndroid13_onlyWhileOffWithAScreen() {
        assertFalse(of(sdk = 32).restrictedHint)
        for (sdk in listOf(33, 34, 35, 36)) {
            val ui = of(sdk = sdk)
            assertTrue(ui.restrictedHint)
            assertTrue(ui.openButton)
        }
        assertFalse(of(enabled = true).restrictedHint)
        assertFalse(of(direct = false).restrictedHint)
    }

    @Test
    fun restrictedHint_notForAStoreInstall() {
        val ui = of(sideloaded = false)
        assertFalse(ui.restrictedHint)
        assertTrue(ui.openButton)
    }

    @Test
    fun restrictedHint_notOnTv() {
        assertFalse(of(sdk = 34, tv = true).restrictedHint)
        assertFalse(of(direct = false, sdk = 34, tv = true).restrictedHint)
    }

    @Test
    fun menuText_namesThePathEndingInTheApp() {
        val path = Txt(R.string.access_menu_path, Txt(R.string.app_name))
        assertEquals(path, ListenerAccessUi.menuPath())
        assertEquals(Txt(R.string.access_menu_only, path), ListenerAccessUi.menuText(MenuHint.ONLY))
        assertEquals(Txt(R.string.access_menu_fallback, path), ListenerAccessUi.menuText(MenuHint.FALLBACK))
    }

    @Test
    fun rowMenuHint_onlyAfterTheDisclosureWasConfirmed() {
        // the menu path is a way to the grant: before the disclosure it appears only inside it
        for (ui in listOf(of(direct = false, sdk = 30, tv = true), of(sdk = 34, tv = true))) {
            assertNull(ui.rowMenuHint(pending = false))
            assertEquals(ui.menuHint, ui.rowMenuHint(pending = true))
        }
        assertNull(of(sdk = 34).rowMenuHint(pending = true)) // no menu route on a phone
        assertNull(of(enabled = true, tv = true).rowMenuHint(pending = true)) // granted: nothing to find
    }

    @Test
    fun withoutAccess_theAdbHintStandsExactlyForAMissingGrantScreen() {
        // the disclosure offers "Continue" (which keeps the poster wish pending) only with a screen to open,
        // and the adb command in its place otherwise
        for (direct in listOf(true, false)) for (apps in listOf(true, false)) for (tv in listOf(true, false))
            for (lowRam in listOf(true, false)) for (sdk in listOf(26, 28, 30, 33, 34, 36)) {
                val ui = of(direct = direct, apps = apps, sdk = sdk, tv = tv, lowRam = lowRam)
                assertEquals(ui.grant == null && !ui.unavailable, ui.adbHint)
            }
    }

    @Test
    fun lowRam_android10AndOlder_posterUnavailable() {
        // the system neither grants nor binds a listener there: no way to the grant at all, not even adb
        for (sdk in listOf(26, 28, 29)) for (tv in listOf(true, false)) for (direct in listOf(true, false))
            for (apps in listOf(true, false)) for (enabled in listOf(true, false)) for (sideloaded in listOf(true, false)) {
                val ui = of(enabled = enabled, direct = direct, apps = apps, sdk = sdk, tv = tv, lowRam = true, sideloaded = sideloaded)
                assertEquals(
                    ListenerAccessUi(Txt(R.string.access_unavailable), null, null, adbHint = false, restrictedHint = false, unavailable = true),
                    ui,
                )
                assertFalse(ui.openButton)
                assertNull(ui.rowMenuHint(pending = true))
                assertFalse(ui.switchEnabled)
                assertFalse(ui.switchChecked(poster = true))
                assertFalse(ui.switchChecked(poster = false))
            }
    }

    @Test
    fun lowRam_android11_keepsTheAdbFallback() {
        // the listener works there; only the TV menu item is missing
        val tv = of(direct = false, sdk = 30, tv = true, lowRam = true)
        assertFalse(tv.unavailable)
        assertEquals(Txt(R.string.access_missing), tv.status)
        assertTrue(tv.adbHint)
        assertTrue(tv.switchEnabled)
        val phone = of(sdk = 30, lowRam = true)
        assertFalse(phone.unavailable)
        assertEquals(GrantScreen.DIRECT, phone.grant)
        assertEquals(Txt(R.string.access_granted), of(enabled = true, sdk = 30, lowRam = true).status)
    }

    @Test
    fun notLowRam_android10AndOlder_available() {
        for (sdk in listOf(26, 28, 29)) for (tv in listOf(true, false)) {
            val ui = of(direct = false, sdk = sdk, tv = tv)
            assertFalse(ui.unavailable)
            assertEquals(Txt(R.string.access_missing), ui.status)
            assertTrue(ui.switchEnabled)
            assertTrue(ui.grant != null || ui.adbHint)
        }
        assertEquals(Txt(R.string.access_granted), of(enabled = true, sdk = 29).status)
    }

    @Test
    fun switchChecked_followsThePosterWhereAvailable() {
        for (ui in listOf(of(), of(enabled = true), of(direct = false, sdk = 30, tv = true, lowRam = true))) {
            assertTrue(ui.switchChecked(poster = true))
            assertFalse(ui.switchChecked(poster = false))
        }
    }
}
