package io.github.hsw.inkeasel

import android.app.Application
import android.os.Looper
import android.os.Process
import android.os.UserManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.hsw.inkeasel.catalog.CatalogStore
import io.github.hsw.inkeasel.catalog.NudityFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The device's lock read again at an activity start ([MainViewModel.readNudityLock], what `MainActivity.onStart`
 * calls) and when the settings open: a lock switched on or off while the app was in the background puts its
 * filter in force on its return and takes away what shows a Catalog picture picked under the old one (the
 * viewer, the details panel, the Bluetooth panel's picture), but nothing of My pictures. The TV emulator
 * cannot switch parental controls from a shell, so this covers the return path; the restricted-profile
 * restriction stands in for either lock.
 */
@RunWith(RobolectricTestRunner::class)
class MainViewModelNudityTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val users: UserManager = app.getSystemService(UserManager::class.java)
    private val store = CatalogStore.get(app)
    private val owner = ViewModelStore()
    private lateinit var vm: MainViewModel

    // never in the catalog database: the viewer shows it alone
    private val picture = Picture("wd:Q0", "Title", "Credit", "", "")

    // a picture of My pictures (a file on the device)
    private val mine = Picture("local:a.png", "a.png", "", "/nowhere/a.png", "/nowhere/a.png", local = true)

    @Before
    fun setUp() {
        store.applyFilter(NudityFilter.DEFAULT)
        vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[MainViewModel::class.java]
        vm.readNudityLock() // the first start
        idle()
    }

    @After
    fun tearDown() {
        restricted(false)
        owner.clear()
        store.applyFilter(NudityFilter.DEFAULT) // the store is process-wide
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun restricted(on: Boolean) =
        shadowOf(users).setUserRestriction(Process.myUserHandle(), UserManager.DISALLOW_MODIFY_ACCOUNTS, on)

    private fun openCatalogViewer() {
        vm.openViewer(picture, catalogPicture = { error("no step expected") }, byPress = true)
        idle()
        assertNotNull(vm.viewer)
    }

    @Test
    fun aLockSwitchedOnWhileAway_appliesAtTheNextStart_andClosesTheCatalogViewer() {
        assertNull(vm.nudityLock)
        assertEquals(NudityFilter.HIDE_EXPLICIT.nudityBelow, store.nudityBelow.value)
        openCatalogViewer()

        restricted(true) // while the app is in the background
        vm.readNudityLock() // onStart
        idle()

        assertEquals(NudityLock.RESTRICTED_PROFILE, vm.nudityLock)
        assertEquals(NudityFilter.HIDE_NUDES, vm.nudityInForce)
        assertEquals(NudityFilter.HIDE_NUDES.nudityBelow, store.nudityBelow.value)
        assertNull(vm.viewer)
        // the choice itself is kept for when the lock goes
        assertEquals(NudityFilter.HIDE_EXPLICIT, vm.nudityChoice)
        assertEquals(NudityFilter.HIDE_EXPLICIT, CatalogFilterStore(app).nudity)
    }

    @Test
    fun aLockSwitchedOffWhileAway_bringsTheChoiceBack_andClosesTheCatalogViewer() {
        restricted(true)
        vm.readNudityLock()
        idle()
        assertEquals(NudityFilter.HIDE_NUDES.nudityBelow, store.nudityBelow.value)
        openCatalogViewer()

        restricted(false)
        vm.readNudityLock()
        idle()

        assertNull(vm.nudityLock)
        assertEquals(NudityFilter.HIDE_EXPLICIT.nudityBelow, store.nudityBelow.value)
        assertNull(vm.viewer)
    }

    @Test
    fun aStartWithTheSameLock_leavesTheViewerOpen() {
        openCatalogViewer()
        vm.readNudityLock()
        idle()
        assertNotNull(vm.viewer)
        assertEquals(NudityFilter.HIDE_EXPLICIT.nudityBelow, store.nudityBelow.value)
    }

    @Test
    fun aLockSwitchedOnWhileAway_closesTheDetailsOfACatalogPicture() {
        vm.openDetails(picture)
        restricted(true)
        vm.readNudityLock()
        idle()
        assertNull(vm.details)
    }

    @Test
    fun aFilterChange_leavesTheDetailsOfAPictureOfMine() {
        vm.openDetails(mine)
        restricted(true)
        vm.readNudityLock()
        idle()
        assertEquals(mine, vm.details)
    }

    @Test
    fun aFilterChange_leavesAViewerOnMyPicturesOpen() {
        vm.selectTab(MainViewModel.LOCAL_TAB)
        vm.openViewer(mine, catalogPicture = { error("not the Catalog") }, byPress = true)
        idle()
        assertNotNull(vm.viewer)

        restricted(true)
        vm.readNudityLock()
        idle()

        assertEquals(NudityFilter.HIDE_NUDES.nudityBelow, store.nudityBelow.value)
        assertEquals(mine.id, vm.viewer?.picture?.id)
    }

    @Test
    fun aFilterChange_takesTheCatalogPictureOutOfTheBluetoothPanel() {
        vm.showBlePanel(BleAsk.Stage.FIRST, picture)
        restricted(true)
        vm.readNudityLock()
        idle()
        // the panel stays for the permission, but sends nothing once it is granted
        assertEquals(BlePanelState(BleAsk.Stage.FIRST, null), vm.blePanel)

        vm.showBlePanel(BleAsk.Stage.FIRST, mine)
        restricted(false)
        vm.readNudityLock()
        idle()
        assertEquals(mine, vm.blePanel?.picture)
    }

    @Test
    fun openingTheSettings_readsTheLockAgain() {
        // switched on while the app stays started (the system settings beside it in a split screen)
        restricted(true)
        vm.openSettings()
        assertEquals(NudityLock.RESTRICTED_PROFILE, vm.nudityLock)
        assertEquals(NudityFilter.HIDE_NUDES.nudityBelow, store.nudityBelow.value)
    }

    @Test
    fun aPressWhileLocked_changesNothing() {
        restricted(true)
        vm.readNudityLock()
        vm.pressNudity()
        assertEquals(NudityFilter.HIDE_EXPLICIT, vm.nudityChoice)
        assertEquals(NudityFilter.HIDE_EXPLICIT, CatalogFilterStore(app).nudity)
        assertEquals(NudityFilter.HIDE_NUDES.nudityBelow, store.nudityBelow.value)
    }

    @Test
    fun aPressCyclesTheChoice_savesIt_andPutsItInForce() {
        val seen = mutableListOf<NudityFilter>()
        repeat(3) {
            vm.pressNudity()
            assertEquals(vm.nudityChoice.nudityBelow, store.nudityBelow.value)
            assertEquals(vm.nudityChoice, CatalogFilterStore(app).nudity)
            seen += vm.nudityChoice
        }
        assertEquals(listOf(NudityFilter.HIDE_NUDES, NudityFilter.SHOW_ALL, NudityFilter.HIDE_EXPLICIT), seen)
    }
}
