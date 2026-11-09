package io.github.hsw.inkeasel

import android.app.Application
import android.os.Looper
import android.os.Process
import android.os.UserManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.hsw.inkeasel.catalog.CatalogPicture
import io.github.hsw.inkeasel.catalog.CatalogRank
import io.github.hsw.inkeasel.catalog.CatalogStore
import io.github.hsw.inkeasel.catalog.NudityFilter
import io.github.hsw.inkeasel.catalog.NudityLevel
import io.github.hsw.inkeasel.catalog.PictureEntity
import kotlinx.coroutines.runBlocking
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

    // never in the catalog database (nor the ids of its copies below): the viewer shows it alone
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
        if (stored.isNotEmpty()) {
            runBlocking { store.write { sql("DELETE FROM picture WHERE id IN (${stored.joinToString { "'$it'" }})") } }
        }
        owner.clear()
        store.applyFilter(NudityFilter.DEFAULT) // the store is process-wide
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Runs the main looper until [done] (a read of the catalog database resumes on it from Room's executor). */
    private fun idleUntil(done: () -> Boolean) {
        repeat(500) {
            idle()
            if (done()) return
            Thread.sleep(10)
        }
    }

    // pictures this test put into the process-wide catalog database
    private val stored = mutableListOf<String>()

    private fun sql(statement: String) = store.db.openHelper.writableDatabase.execSQL(statement)

    /** A Catalog picture stored at [level], as the grid would have read it. */
    private fun storedPicture(id: String, level: NudityLevel = NudityLevel.NONE): Picture {
        stored += id
        runBlocking {
            store.write {
                store.db.catalog().insertPictures(
                    listOf(PictureEntity(id = id, source = CatalogRank.WIKIDATA, rank = -900_000L - stored.size, nudity = level.ordinal)),
                )
            }
        }
        return picture.copy(id = id, nudity = level.ordinal)
    }

    /** An import raising the stored level of [id] (the release, or the built-in list at a start). */
    private fun raise(id: String, level: NudityLevel) =
        runBlocking { store.write { sql("UPDATE picture SET nudity = ${level.ordinal} WHERE id = '$id'") } }

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
    fun aCardOfTheOldListThatTheFilterInForceHides_opensNothing() {
        // a nude card still on the grid while the stricter filter's first page loads
        val nude = picture.copy(id = "wd:Q0-nude", nudity = NudityLevel.NUDE.ordinal)
        assertEquals(true, vm.passes(nude))
        restricted(true)
        vm.readNudityLock()
        idle()

        assertEquals(false, vm.passes(nude))
        vm.openViewer(nude, catalogPicture = { error("no step expected") }, byPress = true)
        idle()
        assertNull(vm.viewer)
        vm.openDetails(nude)
        assertNull(vm.details)
        // a picture the filter lets through, and one of mine at any level, still open
        assertEquals(true, vm.passes(picture))
        vm.openDetails(picture)
        assertEquals(picture, vm.details)
        assertEquals(true, vm.passes(mine.copy(nudity = NudityLevel.EXPLICIT.ordinal)))
    }

    @Test
    fun theSendPath_checksTheLevelStoredNow_notTheOneReadWithThePicture() {
        val read = storedPicture("wd:Q0-raised")
        assertEquals(true, runBlocking { vm.passesStored(read) })
        raise(read.id, NudityLevel.EXPLICIT)
        assertEquals(true, vm.passes(read)) // the level it was read with
        assertEquals(false, runBlocking { vm.passesStored(read) })
        // a picture gone from the database keeps the level it was read with; one of mine is never read
        assertEquals(true, runBlocking { vm.passesStored(picture) })
        assertEquals(false, runBlocking { vm.passesStored(picture.copy(nudity = NudityLevel.EXPLICIT.ordinal)) })
        assertEquals(true, runBlocking { vm.passesStored(mine.copy(id = read.id)) })
    }

    @Test
    fun aLevelRaisedByAnImport_closesTheCatalogViewerOnThatPicture() {
        val read = storedPicture("wd:Q0-viewed")
        vm.openViewer(read, catalogPicture = { CatalogPicture.of(it, " ") }, byPress = true)
        idleUntil { vm.viewer?.rank != null } // its place in the list read
        assertNotNull(vm.viewer?.rank)
        assertEquals(read.id, vm.viewer?.picture?.id)

        raise(read.id, NudityLevel.EXPLICIT)
        idleUntil { vm.viewer == null }
        assertNull(vm.viewer)
    }

    @Test
    fun aLevelRaisedByAnImport_closesItsDetails_andEmptiesTheBluetoothPanel_butNotOthers() {
        val kept = storedPicture("wd:Q0-kept")
        val raised = storedPicture("wd:Q0-panel")
        vm.openDetails(kept)
        vm.showBlePanel(BleAsk.Stage.FIRST, raised) // closes the details
        vm.openDetails(kept)

        raise(raised.id, NudityLevel.NUDE) // still let through by the default filter
        raise(kept.id, NudityLevel.NUDE)
        idle()
        assertEquals(raised, vm.blePanel?.picture)

        raise(raised.id, NudityLevel.EXPLICIT)
        idleUntil { vm.blePanel?.picture == null }
        assertEquals(BlePanelState(BleAsk.Stage.FIRST, null), vm.blePanel)
        assertEquals(kept, vm.details) // checked before the panel, and let through

        raise(kept.id, NudityLevel.EXPLICIT)
        idleUntil { vm.details == null }
        assertNull(vm.details)
    }

    @Test
    fun anExplicitCard_passesOnlyWhenTheChoiceShowsAll() {
        val explicit = picture.copy(id = "wd:Q0-explicit", nudity = NudityLevel.EXPLICIT.ordinal)
        assertEquals(false, vm.passes(explicit)) // the default hides explicit works
        vm.pressNudity() // hide all nudes
        assertEquals(false, vm.passes(explicit))
        vm.pressNudity() // show all
        assertEquals(true, vm.passes(explicit))
        vm.openViewer(explicit, catalogPicture = { error("no step expected") }, byPress = true)
        idle()
        assertEquals(explicit.id, vm.viewer?.picture?.id)
        vm.pressNudity() // back to the default: the viewer closes, and the card no longer opens it
        assertNull(vm.viewer)
        vm.openViewer(explicit, catalogPicture = { error("no step expected") }, byPress = true)
        idle()
        assertNull(vm.viewer)
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
