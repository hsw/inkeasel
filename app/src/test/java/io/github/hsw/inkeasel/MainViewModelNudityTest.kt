package io.github.hsw.inkeasel

import android.app.Application
import android.os.Handler
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
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
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
        // a fast read could return before the gate suspends and run the open at once: an open's read here
        // always waits for the main looper, so whatever a test does right after an open happens while it waits
        // (a direct runBlocking { allowed() } on the main thread reads at once: the looper is blocked)
        val read = vm.storedNudity
        vm.storedNudity = { id ->
            if (currentCoroutineContext()[ContinuationInterceptor] is MainCoroutineDispatcher) {
                suspendCancellableCoroutine<Unit> { c -> Handler(Looper.getMainLooper()).post { c.resume(Unit) {} } }
            }
            read(id)
        }
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

    /** Runs the main looper until no open waits for the gate ([MainViewModel.allowed] reads the database). */
    private fun opened() = idleUntil { !vm.openPending }

    private fun openCatalogViewer() {
        vm.openViewer(picture, catalogPicture = { error("no step expected") }, byPress = true)
        opened()
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
        opened()
        assertEquals(picture, vm.details)
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
        assertEquals(true, runBlocking { vm.allowed(nude) })
        restricted(true)
        vm.readNudityLock()
        idle()

        assertEquals(false, runBlocking { vm.allowed(nude) })
        var sent = false
        vm.openViewer(nude, catalogPicture = { error("no step expected") }, byPress = true) { sent = true }
        opened()
        assertNull(vm.viewer)
        assertEquals(false, sent) // the press's send path follows only an open viewer
        vm.openDetails(nude)
        opened()
        assertNull(vm.details)
        // a picture the filter lets through, and one of mine at any level, still open
        vm.openDetails(picture)
        opened()
        assertEquals(picture, vm.details)
        assertEquals(true, runBlocking { vm.allowed(mine.copy(nudity = NudityLevel.EXPLICIT.ordinal)) })
    }

    @Test
    fun theGate_checksTheLevelStoredNow_notTheOneReadWithThePicture() {
        // what "Send to frame" and the Bluetooth panel's later send check before the upload
        val read = storedPicture("wd:Q0-raised")
        assertEquals(true, runBlocking { vm.allowed(read) })
        raise(read.id, NudityLevel.EXPLICIT)
        assertEquals(true, read.shownUnder(store.nudityBelow.value)) // the level it was read with
        assertEquals(false, runBlocking { vm.allowed(read) })
        // a picture gone from the database keeps the level it was read with; one of mine is never read
        assertEquals(true, runBlocking { vm.allowed(picture) })
        assertEquals(false, runBlocking { vm.allowed(picture.copy(nudity = NudityLevel.EXPLICIT.ordinal)) })
        assertEquals(true, runBlocking { vm.allowed(mine.copy(id = read.id)) })
    }

    /** [read]'s card of the old list stays on the grid after an import raised its level and the re-check ran. */
    private fun raisedAfterTheRecheck(read: Picture) {
        raise(read.id, NudityLevel.EXPLICIT)
        idle()
        assertEquals(true, read.shownUnder(store.nudityBelow.value)) // the card's level still passes
    }

    @Test
    fun aLongPressOnARaisedCard_opensNoDetails() {
        val read = storedPicture("wd:Q0-long")
        raisedAfterTheRecheck(read)
        vm.openDetails(read)
        opened()
        assertNull(vm.details)
    }

    @Test
    fun aPressOnARaisedCard_opensNoViewer_andSendsNothing() {
        val read = storedPicture("wd:Q0-press")
        raisedAfterTheRecheck(read)
        var sent = false
        vm.openViewer(read, catalogPicture = { CatalogPicture.of(it, " ") }, byPress = true) { sent = true }
        opened()
        assertNull(vm.viewer)
        assertEquals(false, sent)
    }

    @Test
    fun viewFullScreenOfARaisedPicture_opensNoViewer_andLeavesTheDetails() {
        val shown = storedPicture("wd:Q0-shown")
        val read = storedPicture("wd:Q0-full")
        vm.openDetails(shown)
        opened()
        raisedAfterTheRecheck(read)
        vm.openViewer(read, catalogPicture = { CatalogPicture.of(it, " ") })
        opened()
        assertNull(vm.viewer)
        assertEquals(shown, vm.details)
    }

    @Test
    fun theViewerStepsThroughTheGate_toAPictureItLetsThrough() {
        val first = storedPicture("wd:Q0-step1")
        val before = storedPicture("wd:Q0-step2") // ranked just before the first
        vm.openViewer(first, catalogPicture = { CatalogPicture.of(it, " ") }, byPress = true)
        idleUntil { vm.viewer?.rank != null }
        vm.stepViewer(-1)
        idleUntil { vm.viewer?.picture?.id == before.id }
        assertEquals(before.id, vm.viewer?.picture?.id)
    }

    @Test
    fun anOpenStillAtTheGate_isDroppedByTheSettingsAndTheBluetoothPanel() {
        val read = storedPicture("wd:Q0-dropped")
        vm.openDetails(read)
        vm.openSettings()
        opened()
        assertNull(vm.details)
        vm.closeSettings()

        vm.openViewer(read, catalogPicture = { CatalogPicture.of(it, " ") }, byPress = true)
        vm.showBlePanel(BleAsk.Stage.FIRST, null)
        opened()
        assertNull(vm.viewer)
    }

    @Test
    fun aTabSwitchWhileAnOpenWaitsAtTheGate_dropsIt() {
        val read = storedPicture("wd:Q0-tab")
        var sent = false
        vm.openViewer(read, catalogPicture = { CatalogPicture.of(it, " ") }, byPress = true) { sent = true }
        assertEquals(true, vm.gatePending)
        vm.selectTab(MainViewModel.LOCAL_TAB)
        assertEquals(false, vm.gatePending)
        opened()
        assertNull(vm.viewer)
        assertEquals(false, sent)
    }

    @Test
    fun theSameTabSelectedAgain_keepsTheOpen() {
        // the tab's focus again (Back from the content moves it there) is no switch
        val read = storedPicture("wd:Q0-sametab")
        vm.openViewer(read, catalogPicture = { CatalogPicture.of(it, " ") }, byPress = true)
        vm.selectTab(vm.tab)
        opened()
        assertEquals(read.id, vm.viewer?.picture?.id)
        assertEquals(false, vm.gatePending)
    }

    @Test
    fun aCatalogPicture_opensACatalogViewer_whateverTheTab() {
        // whether it is a Catalog viewer comes from the picture, not from the tab when the gate returns
        val read = storedPicture("wd:Q0-anytab")
        vm.selectTab(MainViewModel.LOCAL_TAB)
        vm.openViewer(read, catalogPicture = { CatalogPicture.of(it, " ") }, byPress = true)
        idleUntil { vm.viewer?.rank != null }
        assertEquals(read.id, vm.viewer?.picture?.id)

        raise(read.id, NudityLevel.EXPLICIT) // an import: the Catalog viewer goes
        idleUntil { vm.viewer == null }
        assertNull(vm.viewer)
    }

    @Test
    fun aNewFilterWhileAnOpenWaitsAtTheGate_dropsIt() {
        // the open read its steps under the old filter
        val read = storedPicture("wd:Q0-newfilter")
        vm.openDetails(read)
        restricted(true)
        vm.readNudityLock()
        opened()
        assertNull(vm.details)
    }

    @Test
    fun backWhileAnOpenWaitsAtTheGate_dropsIt() {
        val read = storedPicture("wd:Q0-back")
        var sent = false
        vm.openViewer(read, catalogPicture = { CatalogPicture.of(it, " ") }, byPress = true) { sent = true }
        vm.dropPending() // the Back handler while gatePending
        opened()
        assertNull(vm.viewer)
        assertEquals(false, sent)
    }

    @Test
    fun aSendAtTheGate_runsOnceLetThrough_andIsDroppedByTheSettings() {
        val read = storedPicture("wd:Q0-send")
        var sent = 0
        vm.whenAllowed(read) { sent++ } // "Send to frame"
        opened()
        assertEquals(1, sent)

        vm.whenAllowed(read) { sent++ }
        vm.openSettings()
        opened()
        assertEquals(1, sent)
        vm.closeSettings()

        raisedAfterTheRecheck(read)
        vm.whenAllowed(read) { sent++ }
        opened()
        assertEquals(1, sent)
    }

    @Test
    fun aNewerOpenOrSend_dropsTheOneAtTheGate() {
        val first = storedPicture("wd:Q0-older")
        val second = storedPicture("wd:Q0-newer")
        var sent = false
        vm.whenAllowed(first) { sent = true }
        vm.openDetails(second)
        opened()
        assertEquals(false, sent)
        assertEquals(second, vm.details)
    }

    @Test
    fun detailsDismissedWhileViewFullScreenWaits_opensNoViewer() {
        val read = storedPicture("wd:Q0-dismissed")
        vm.openDetails(read)
        opened()
        vm.openViewer(read, catalogPicture = { CatalogPicture.of(it, " ") })
        vm.closeDetails()
        opened()
        assertNull(vm.viewer)
        assertNull(vm.details)
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
        vm.showBlePanel(BleAsk.Stage.FIRST, raised)
        vm.openDetails(kept)
        opened()
        assertEquals(kept, vm.details)

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
        assertEquals(false, runBlocking { vm.allowed(explicit) }) // the default hides explicit works
        vm.pressNudity() // hide all nudes
        assertEquals(false, runBlocking { vm.allowed(explicit) })
        vm.pressNudity() // show all
        assertEquals(true, runBlocking { vm.allowed(explicit) })
        vm.openViewer(explicit, catalogPicture = { error("no step expected") }, byPress = true)
        opened()
        assertEquals(explicit.id, vm.viewer?.picture?.id)
        vm.pressNudity() // back to the default: the viewer closes, and the card no longer opens it
        assertNull(vm.viewer)
        vm.openViewer(explicit, catalogPicture = { error("no step expected") }, byPress = true)
        opened()
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
