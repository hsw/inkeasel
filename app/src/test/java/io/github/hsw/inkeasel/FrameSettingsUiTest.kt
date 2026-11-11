package io.github.hsw.inkeasel

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import io.github.hsw.inkeasel.catalog.NudityFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * `createComposeRule()` launches a bare `ComponentActivity` to host the content. The app's manifest does not
 * declare one, and `ui-test-manifest` would merge an exported one into the debug APK, so this registers it with
 * Robolectric's package manager before the compose rule starts it (docs/building.md).
 */
class ComposeHostRule : ExternalResource() {
    override fun before() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }
}

/**
 * The settings content ([FrameSettingsContent]) on the real composables at TV width, driven with fake frames
 * and states. Each test says how it presses: "D-pad" moves the focus and presses with key events (what a TV
 * remote sends; tv-material reacts to these only), "touch" taps the node (what reaches `Modifier.onTap`).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp")
class FrameSettingsUiTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(compose)

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private val e6 = FrameAdvert.parse(byteArrayOf(0x43, 0x13, 0x64, 0x15))
    private val supported = SeenFrame("NM000001", "AA:BB:CC:DD:EE:01", -60, e6)
    private val unsupported = SeenFrame("NM000002", "AA:BB:CC:DD:EE:02", -70, null) // no advert: no caps
    private fun chosen(f: SeenFrame) = SelectedFrame(f.name, f.address, f.advert, seenAt = 0)

    private var cancelled = 0
    private val chosenFrames = mutableListOf<SeenFrame>()

    private var frames by mutableStateOf(emptyList<SeenFrame>())
    private var selected by mutableStateOf<SelectedFrame?>(null)
    private var probing by mutableStateOf<String?>(null)
    private var btProblem by mutableStateOf<BtProblem?>(null)
    private var scanError by mutableStateOf<Txt?>(null)
    private var scanRun by mutableStateOf(1)
    private var locationOff by mutableStateOf(false)
    private var nudityChoice by mutableStateOf(NudityFilter.HIDE_EXPLICIT)
    private var nudityLock by mutableStateOf<NudityLock?>(null)
    private val nudityChosen = mutableListOf<NudityFilter>()
    private var bleRequests = 0
    private var retries = 0
    private var ble by mutableStateOf(BleAccess(permitted = true, blocked = false, request = { bleRequests++ }))

    private fun show() {
        compose.setContent {
            FrameSettingsContent(
                selected = selected,
                ble = ble,
                poster = false,
                access = false,
                pending = false,
                nudityChoice = nudityChoice,
                nudityLock = nudityLock,
                onNudity = { nudityChosen += it },
                onPoster = { PosterSwitch.Press.TURN_ON },
                onDisclosureConfirmed = {},
                sidePadding = 48,
                probing = probing,
                frames = frames,
                scanError = scanError,
                btProblem = btProblem,
                locationOff = locationOff,
                scanRun = scanRun,
                onRetry = { retries++ },
                onSelect = { chosenFrames += it },
                onCancel = { cancelled++ },
            )
        }
        compose.waitForIdle()
    }

    private fun close(): SemanticsNodeInteraction = compose.onNodeWithText(str(R.string.close))
    /** A frame's row: the clickable node with its name (the header line "Selected: …" names it too). */
    private fun row(f: SeenFrame): SemanticsNodeInteraction = compose.onNode(hasText(f.name, substring = true) and hasClickAction())
    private fun SemanticsNodeInteraction.press(key: Key) = performKeyInput { pressKey(key) }
    /** The radio row of a nudity value (by default the first, "Hide all nudity"). */
    private fun nudityRow(f: NudityFilter = NudityFilter.HIDE_NUDES): SemanticsNodeInteraction =
        compose.onNode(hasText(NuditySetting.valueText(f).resolve(app)) and radio)
    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private fun posterRow(): SemanticsNodeInteraction = compose.onNodeWithText(str(R.string.feature_poster))

    /** Presses Down on whatever has the focus until [to] has it (D-pad). */
    private fun moveDownTo(to: SemanticsNodeInteraction) {
        repeat(10) {
            if (runCatching { to.assertIsFocused() }.isSuccess) return
            compose.onNode(isFocused()).press(Key.DirectionDown)
            compose.waitForIdle()
        }
        to.assertIsFocused()
    }

    @Test
    fun open_closeHasTheFocus() { // D-pad
        show()
        close().assertIsFocused()
    }

    @Test
    fun down_movesFromCloseIntoTheContent() { // D-pad
        show()
        close().press(Key.DirectionDown)
        nudityRow().assertIsFocused()
    }

    @Test
    fun close_dpadCenter_cancels() { // D-pad
        show()
        close().press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(1, cancelled)
    }

    @Test
    fun close_tap_cancels() { // touch
        show()
        close().performClick()
        compose.waitForIdle()
        assertEquals(1, cancelled)
    }

    @Test
    fun frameRow_dpadCenter_selectsIt() { // D-pad
        frames = listOf(supported, unsupported)
        selected = chosen(supported)
        show()
        row(supported).assertIsFocused()
        row(supported).press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(listOf(supported), chosenFrames)
    }

    @Test
    fun frameRow_tap_selectsIt() { // touch
        frames = listOf(supported, unsupported)
        show()
        row(supported).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(supported), chosenFrames)
    }

    @Test
    fun unsupportedFrame_dpadCenter_onlyToastsTheReason() { // D-pad
        frames = listOf(supported, unsupported)
        selected = chosen(supported)
        show()
        row(supported).press(Key.DirectionDown)
        row(unsupported).assertIsFocused()
        row(unsupported).press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(emptyList<SeenFrame>(), chosenFrames)
        val reason = Txt(R.string.frame_toast_unsupported, unsupported.name, Txt(R.string.reason_no_caps)).resolve(app)
        assertEquals(reason, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun unsupportedFrame_tap_isNotChosen() { // touch
        frames = listOf(supported, unsupported)
        show()
        row(unsupported).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(emptyList<SeenFrame>(), chosenFrames)
        val reason = Txt(R.string.frame_toast_unsupported, unsupported.name, Txt(R.string.reason_no_caps)).resolve(app)
        assertEquals(reason, ShadowToast.getTextOfLatestToast())
    }

    /** Back on the dialog itself (its `onDismissRequest`) leaves the frame as it was: it only cancels. */
    @Test
    fun dialog_back_cancels() { // D-pad (Back)
        compose.setContent {
            FrameSettingsDialog(
                selected = null,
                ble = BleAccess(permitted = false, blocked = false, request = {}), // no scan
                poster = false,
                access = false,
                pending = false,
                nudityChoice = NudityFilter.HIDE_EXPLICIT,
                nudityLock = null,
                onNudity = { nudityChosen += it },
                onPoster = { PosterSwitch.Press.TURN_ON },
                onDisclosureConfirmed = {},
                probing = null,
                onSelect = { chosenFrames += it },
                onCancel = { cancelled++ },
            )
        }
        compose.waitForIdle()
        close().assertIsFocused()
        // Back as the remote sends it, to the dialog's window (Espresso is not on the test classpath)
        val dialog = ShadowDialog.getLatestDialog()
        compose.runOnUiThread {
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) dialog.dispatchKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()
        assertEquals(1, cancelled)
        assertEquals(emptyList<SeenFrame>(), chosenFrames)
    }

    // Focus while frames show up: the chosen frame only, and only while the focus is still on Close

    @Test
    fun noFrameChosen_framesShowUp_focusStaysOnClose() { // D-pad
        show()
        frames = listOf(supported)
        compose.waitForIdle()
        frames = listOf(supported, unsupported)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        close().assertIsFocused()
    }

    @Test
    fun chosenFrameShowsUp_focusMovesToItOnce() { // D-pad
        selected = chosen(supported)
        show()
        close().assertIsFocused()
        frames = listOf(supported)
        compose.waitForIdle()
        row(supported).assertIsFocused()
        // back on Close, the chosen frame gone and showing up again leaves the focus there
        row(supported).press(Key.DirectionUp)
        compose.waitForIdle()
        close().assertIsFocused()
        frames = emptyList()
        compose.waitForIdle()
        frames = listOf(supported, unsupported)
        compose.waitForIdle()
        close().assertIsFocused()
    }

    @Test
    fun leftCloseAndBack_chosenFrameShowingUpLater_staysOnClose() { // D-pad
        selected = chosen(supported)
        show()
        close().press(Key.DirectionDown)
        nudityRow().assertIsFocused()
        nudityRow().press(Key.DirectionUp)
        compose.waitForIdle()
        close().assertIsFocused()
        frames = listOf(supported)
        compose.waitForIdle()
        close().assertIsFocused()
    }

    @Test
    fun chosenFrameNowUnsupported_stillTakesTheFocus() { // D-pad
        selected = chosen(unsupported) // the user's own frame: its row says why it cannot be used now
        show()
        frames = listOf(supported, unsupported)
        compose.waitForIdle()
        row(unsupported).assertIsFocused()
    }

    // Touch moves no focus (tv-material ignores it, `onTap` takes the tap): a tap or a scroll in the content
    // counts as leaving Close all the same

    @Test
    fun tappedANudityRow_chosenFrameShowingUpLater_doesNotMoveTheFocus() { // touch
        selected = chosen(supported)
        show()
        nudityRow(NudityFilter.SHOW_ALL).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(NudityFilter.SHOW_ALL), nudityChosen)
        close().assertIsFocused() // the tap moved no focus
        frames = listOf(supported)
        compose.waitForIdle()
        close().assertIsFocused()
    }

    @Test
    fun scrolledTheContent_chosenFrameShowingUpLater_doesNotMoveTheFocus() { // touch
        selected = chosen(supported)
        show()
        posterRow().performTouchInput { swipeUp() }
        compose.waitForIdle()
        close().assertIsFocused()
        frames = listOf(supported)
        compose.waitForIdle()
        close().assertIsFocused()
    }

    // A problem's button had the focus and the problem went: nothing is focused, so the focus goes back to Close,
    // and the chosen frame takes it from there once again

    @Test
    fun retryFocused_theErrorGoes_closeTakesTheFocus_thenTheChosenFrame() { // D-pad
        selected = chosen(supported)
        scanError = Txt(R.string.reason_no_caps)
        show()
        moveDownTo(problemButton(str(R.string.retry)))
        problemButton(str(R.string.retry)).press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(1, retries)
        scanError = null // what the wrapper's onRetry does
        compose.waitForIdle()
        close().assertIsFocused()
        frames = listOf(supported)
        compose.waitForIdle()
        row(supported).assertIsFocused()
    }

    @Test
    fun allowFocused_thePermissionGranted_theChosenFrameAlreadyHeardTakesTheFocus() { // D-pad
        selected = chosen(supported)
        frames = listOf(supported, unsupported)
        ble = BleAccess(permitted = false, blocked = false, request = { bleRequests++ })
        show()
        close().assertIsFocused() // the list is hidden: the chosen frame cannot take it yet
        moveDownTo(problemButton(str(R.string.allow)))
        ble = BleAccess(permitted = true, blocked = false, request = { bleRequests++ })
        compose.waitForIdle()
        row(supported).assertIsFocused()
    }

    @Test
    fun bluetoothSettingsFocused_bluetoothBackOn_noFrameChosen_closeTakesTheFocus() { // D-pad
        btProblem = BtProblem.OFF
        show()
        moveDownTo(problemButton(str(R.string.bt_settings)))
        btProblem = null
        frames = listOf(supported)
        compose.waitForIdle()
        close().assertIsFocused()
    }

    @Test
    fun movedToAFrameRow_chosenFrameShowingUpLater_doesNotMoveTheFocus() { // D-pad
        frames = listOf(unsupported)
        selected = chosen(supported)
        show()
        close().assertIsFocused()
        moveDownTo(row(unsupported))
        frames = listOf(supported, unsupported)
        compose.waitForIdle()
        row(unsupported).assertIsFocused()
    }

    @Test
    fun movedToTheNudityRow_chosenFrameShowingUpLater_doesNotMoveTheFocus() { // D-pad
        selected = chosen(supported)
        show()
        moveDownTo(nudityRow())
        frames = listOf(supported)
        compose.waitForIdle()
        nudityRow().assertIsFocused()
    }

    @Test
    fun movedToThePosterRow_chosenFrameShowingUpLater_doesNotMoveTheFocus() { // D-pad
        selected = chosen(supported)
        show()
        moveDownTo(posterRow())
        frames = listOf(supported)
        compose.waitForIdle()
        posterRow().assertIsFocused()
    }

    @Test
    fun onTheChosenFrame_aNewFrameShowingUp_doesNotMoveTheFocus() { // D-pad
        frames = listOf(supported)
        selected = chosen(supported)
        show()
        row(supported).assertIsFocused()
        frames = listOf(unsupported, supported) // a new frame ahead of it in the list
        compose.waitForIdle()
        row(supported).assertIsFocused()
    }

    // The chosen frame's radio mark and the panel reading in its row

    @Test
    fun chosenFrame_rowIsTheSelectedRadio_theOthersAreNot() { // no input
        frames = listOf(supported, unsupported)
        selected = chosen(supported)
        show()
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        row(supported).assertIsSelected().assert(radio)
        row(unsupported).assertIsNotSelected().assert(radio)
        // the bare name: no "— selected" after it
        compose.onNodeWithText(supported.name).assertExists()
        // and the choice moved: the mark follows
        selected = chosen(unsupported)
        compose.waitForIdle()
        row(supported).assertIsNotSelected()
        row(unsupported).assertIsSelected()
    }

    @Test
    fun noFrameChosen_noRowIsSelected() { // no input
        frames = listOf(supported, unsupported)
        show()
        row(supported).assertIsNotSelected()
        row(unsupported).assertIsNotSelected()
    }

    @Test
    fun probedFrame_readingShowsInItsRow_andTheFocusStaysThere() { // D-pad
        frames = listOf(supported, unsupported)
        show()
        moveDownTo(row(supported))
        row(supported).press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(listOf(supported), chosenFrames)
        probing = supported.name // the view model reads the panel before choosing it
        compose.waitForIdle()
        row(supported).assertTextContains(str(R.string.frame_row_reading)).assertIsFocused()
        compose.onAllNodesWithText(str(R.string.frame_row_reading)).assertCountEquals(1)
        row(unsupported).assert(!hasText(str(R.string.frame_row_reading)))
        compose.onAllNodesWithText(str(R.string.frame_reading_panel, supported.name)).assertCountEquals(0)
    }

    @Test
    fun probing_bluetoothOff_theHeaderLineShowsIt() { // no input
        frames = listOf(supported)
        probing = supported.name
        btProblem = BtProblem.OFF
        show()
        compose.onNodeWithText(str(R.string.frame_reading_panel, supported.name)).assertExists()
        compose.onAllNodesWithText(str(R.string.frame_row_reading)).assertCountEquals(0)
        // Bluetooth back on: the list shows again, and the reading moves back into the row
        btProblem = null
        compose.waitForIdle()
        compose.onAllNodesWithText(str(R.string.frame_reading_panel, supported.name)).assertCountEquals(0)
        row(supported).assertTextContains(str(R.string.frame_row_reading))
    }

    @Test
    fun probing_frameNotInTheList_theHeaderLineShowsIt() { // no input
        frames = listOf(supported)
        probing = "OD000001" // chosen from an earlier scan, not heard by this one yet
        show()
        compose.onNodeWithText(str(R.string.frame_reading_panel, "OD000001")).assertExists()
        compose.onAllNodesWithText(str(R.string.frame_row_reading)).assertCountEquals(0)
        frames = emptyList()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.frame_reading_panel, "OD000001")).assertExists()
    }

    @Test
    fun probing_scanError_theHeaderLineShowsIt() { // no input
        frames = listOf(supported)
        probing = supported.name
        scanError = Txt(R.string.reason_no_caps)
        show()
        compose.onNodeWithText(str(R.string.frame_reading_panel, supported.name)).assertExists()
        compose.onAllNodesWithText(str(R.string.frame_row_reading)).assertCountEquals(0)
    }

    // The problems that hide the frame list (no permission, Bluetooth off, a scan error): the frames seen are not
    // listed, nothing waits for "No frames seen yet", and the problem's button does what it names

    /** The problem's button: the clickable node with only [text] (the Bluetooth row may name the same action). */
    private fun problemButton(text: String): SemanticsNodeInteraction =
        compose.onNode(hasText(text) and hasClickAction() and !hasText(str(R.string.perm_bt)))

    /** No frame row, no scan status, even after the 25 s wait. */
    private fun listHidden(f: SeenFrame) {
        advance(ScanStatus.NONE_SEEN_MS + 1_000)
        compose.onAllNodes(hasText(f.name, substring = true) and hasClickAction()).assertCountEquals(0)
        compose.onAllNodesWithText(str(R.string.scanning)).assertCountEquals(0)
        compose.onAllNodesWithText(str(R.string.scan_none_seen)).assertCountEquals(0)
    }

    @Test
    fun noPermission_hidesTheList_andItsButtonAsks() { // D-pad and touch; clock
        frames = listOf(supported)
        probing = supported.name
        ble = BleAccess(permitted = false, blocked = false, request = { bleRequests++ })
        show()
        compose.onNodeWithText(BlePermissions.neededText.resolve(app)).assertExists()
        listHidden(supported)
        compose.onNodeWithText(str(R.string.frame_reading_panel, supported.name)).assertExists()
        close().assertIsFocused() // no frame chosen: nothing takes the focus from Close
        moveDownTo(problemButton(str(R.string.allow)))
        problemButton(str(R.string.allow)).press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(1, bleRequests)
        problemButton(str(R.string.allow)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(2, bleRequests)
    }

    @Test
    fun noPermission_blocked_theButtonNamesAppInfo() { // touch
        ble = BleAccess(permitted = false, blocked = true, request = { bleRequests++ })
        show()
        compose.onNodeWithText(Txt(R.string.perm_bt_blocked, Txt(R.string.app_name), BlePermissions.settingsName).resolve(app)).assertExists()
        problemButton(str(R.string.app_info)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, bleRequests)
    }

    @Test
    fun bluetoothOff_hidesTheList_andItsButtonOpensBluetoothSettings() { // D-pad and touch; clock
        frames = listOf(supported)
        btProblem = BtProblem.OFF
        show()
        listHidden(supported)
        moveDownTo(problemButton(str(R.string.bt_settings)))
        problemButton(str(R.string.bt_settings)).press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, shadowOf(app).nextStartedActivity.action)
        problemButton(str(R.string.bt_settings)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }

    @Test
    fun scanError_hidesTheList_andRetryCallsIt() { // D-pad and touch; clock
        frames = listOf(supported)
        scanError = Txt(R.string.reason_no_caps)
        show()
        compose.onNodeWithText(Txt(R.string.scan_failed, Txt(R.string.reason_no_caps)).resolve(app)).assertExists()
        listHidden(supported)
        moveDownTo(problemButton(str(R.string.retry)))
        problemButton(str(R.string.retry)).press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(1, retries)
        problemButton(str(R.string.retry)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(2, retries)
    }

    // The scan status: "Searching for frames…", then "No frames seen yet" after 25 s of a running scan with none

    private fun searchingShows() {
        compose.onNodeWithText(str(R.string.scanning)).assertExists()
        compose.onNodeWithText(str(R.string.scanning_hint)).assertExists() // location is not named
        compose.onAllNodesWithText(str(R.string.scan_none_seen)).assertCountEquals(0)
    }

    private fun noneSeenShows() {
        compose.onNodeWithText(str(R.string.scan_none_seen)).assertExists()
        compose.onNodeWithText(str(R.string.scan_none_seen_hint)).assertExists()
        compose.onAllNodesWithText(str(R.string.scanning)).assertCountEquals(0)
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    @Test
    fun noFrameHeard_searching_thenNoneSeenAfter25s() { // no input; clock
        show()
        searchingShows()
        advance(ScanStatus.NONE_SEEN_MS - 1_000)
        searchingShows()
        advance(2_000)
        noneSeenShows()
    }

    @Test
    fun noneSeen_aFrameShowsUp_theListReplacesIt() { // no input; clock
        show()
        advance(ScanStatus.NONE_SEEN_MS + 1_000)
        noneSeenShows()
        frames = listOf(supported)
        compose.waitForIdle()
        row(supported).assertExists()
        compose.onAllNodesWithText(str(R.string.scan_none_seen)).assertCountEquals(0)
        compose.onAllNodesWithText(str(R.string.scanning)).assertCountEquals(0)
    }

    @Test
    fun aNewScanRun_startsTheWaitAgain() { // no input; clock
        show()
        advance(ScanStatus.NONE_SEEN_MS - 5_000)
        scanRun++ // e.g. the app back in the foreground
        compose.waitForIdle()
        advance(10_000) // 30 s since the open, 10 s since the new start
        searchingShows()
        advance(ScanStatus.NONE_SEEN_MS)
        noneSeenShows()
        scanRun++ // e.g. "Retry": searching again
        compose.waitForIdle()
        searchingShows()
    }

    @Test
    fun bluetoothOff_noWait_untilTheScanRunsAgain() { // no input; clock
        btProblem = BtProblem.OFF
        show()
        advance(ScanStatus.NONE_SEEN_MS + 1_000)
        btProblem = null // Bluetooth back on: the wrapper starts the scan
        scanRun++
        compose.waitForIdle()
        searchingShows()
        advance(ScanStatus.NONE_SEEN_MS + 1_000)
        noneSeenShows()
    }

    @Test
    fun whileAProblemHidesTheList_nothingIsCounted() { // no input; clock
        btProblem = BtProblem.OFF
        show()
        advance(ScanStatus.NONE_SEEN_MS + 1_000)
        btProblem = null // the list shows again, before any new scan start
        compose.waitForIdle()
        searchingShows()
        advance(ScanStatus.NONE_SEEN_MS - 1_000)
        searchingShows()
        advance(2_000)
        noneSeenShows()
    }

    // The location hint (before Android 12 a BLE scan needs location on): named only while location is off. The
    // flag comes from the wrapper (always off on Android 12+), whose re-read (resume, MODE_CHANGED_ACTION) is left
    // to the emulator check.

    @Test
    fun locationOff_theHintNamesLocation() { // no input; clock
        locationOff = true
        show()
        compose.onNodeWithText(str(R.string.scanning_hint_location)).assertExists()
        compose.onAllNodesWithText(str(R.string.scanning_hint)).assertCountEquals(0)
        advance(ScanStatus.NONE_SEEN_MS + 1_000)
        compose.onNodeWithText(str(R.string.scan_none_seen_hint_location)).assertExists()
        compose.onAllNodesWithText(str(R.string.scan_none_seen_hint)).assertCountEquals(0)
    }

    @Test
    fun locationOn_theHintDoesNotNameIt() { // no input; clock
        show()
        searchingShows()
        compose.onAllNodesWithText(str(R.string.scanning_hint_location)).assertCountEquals(0)
        advance(ScanStatus.NONE_SEEN_MS + 1_000)
        noneSeenShows()
        compose.onAllNodesWithText(str(R.string.scan_none_seen_hint_location)).assertCountEquals(0)
    }

    @Test
    fun locationSwitchedOn_theHintFollows() { // no input
        locationOff = true
        show()
        compose.onNodeWithText(str(R.string.scanning_hint_location)).assertExists()
        locationOff = false
        compose.waitForIdle()
        searchingShows()
        compose.onAllNodesWithText(str(R.string.scanning_hint_location)).assertCountEquals(0)
    }

    // One row style in "Permissions and features": every row a ListItem, no button inside a row

    private fun bluetoothRow(): SemanticsNodeInteraction = compose.onNode(hasText(str(R.string.perm_bt)) and hasClickAction())
    private fun appInfoRow(): SemanticsNodeInteraction =
        compose.onNode(hasText(str(R.string.access_restricted)) and hasClickAction())
    private fun privacyRow(): SemanticsNodeInteraction = compose.onNodeWithText(str(R.string.settings_privacy))

    /** The restricted-settings row shows on a phone (Android 13+) for a sideloaded app with the app's own access screen. */
    private fun withAccessDetailScreen() {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.settings"
                name = "com.android.settings.NotificationAccessDetails"
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS), info)
    }

    @Test
    fun featureRows_shareOneLeftEdge() { // no input
        withAccessDetailScreen()
        ble = BleAccess(permitted = false, blocked = false, request = { bleRequests++ })
        show()
        val rows = listOf(nudityRow(), bluetoothRow(), posterRow(), appInfoRow(), privacyRow())
        // unclipped: the column scrolls, and a row out of view has empty clipped bounds
        val lefts = rows.map { it.getUnclippedBoundsInRoot().left }
        assertEquals(List(rows.size) { lefts[0] }, lefts)
        // and so do their headlines (a plain Row put its text at the container's edge); a nudity row's headline
        // follows its radio mark
        val heads = listOf(R.string.perm_bt, R.string.feature_poster, R.string.app_info, R.string.settings_privacy)
            .map { compose.onNodeWithText(str(it), useUnmergedTree = true).getUnclippedBoundsInRoot().left }
        assertEquals(List(heads.size) { heads[0] }, heads)
    }

    @Test
    fun bluetoothRow_hasNoButtonInside_andNamesTheAction() { // no input
        ble = BleAccess(permitted = false, blocked = false, request = { bleRequests++ })
        show()
        bluetoothRow().assertTextContains(str(R.string.perm_bt_missing)).assertTextContains(str(R.string.allow))
        compose.onAllNodes(hasAnyAncestor(hasText(str(R.string.perm_bt)) and hasClickAction()) and hasClickAction(), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun bluetoothRow_missing_dpadCenter_asks() { // D-pad
        ble = BleAccess(permitted = false, blocked = false, request = { bleRequests++ })
        show()
        moveDownTo(bluetoothRow())
        bluetoothRow().press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(1, bleRequests)
    }

    @Test
    fun bluetoothRow_missing_tap_asks() { // touch
        ble = BleAccess(permitted = false, blocked = false, request = { bleRequests++ })
        show()
        bluetoothRow().performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, bleRequests)
    }

    @Test
    fun bluetoothRow_blocked_namesAppInfo_andItsPressCallsTheRequest() { // D-pad and touch
        ble = BleAccess(permitted = false, blocked = true, request = { bleRequests++ }) // the request opens App info then
        show()
        bluetoothRow().assertTextContains(str(R.string.app_info))
        moveDownTo(bluetoothRow())
        bluetoothRow().press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(1, bleRequests)
        bluetoothRow().performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(2, bleRequests)
    }

    @Test
    fun bluetoothRow_granted_staysFocusable_andAPressDoesNothing() { // D-pad and touch
        show()
        bluetoothRow().assertTextContains(str(R.string.perm_bt_granted))
        bluetoothRow().assert(!hasText(str(R.string.allow))).assert(!hasText(str(R.string.app_info)))
        moveDownTo(bluetoothRow())
        bluetoothRow().press(Key.DirectionCenter)
        compose.waitForIdle()
        bluetoothRow().performClick()
        compose.waitForIdle()
        assertEquals(0, bleRequests)
        bluetoothRow().press(Key.DirectionDown) // the D-pad moves on from it as from any row
        compose.waitForIdle()
        posterRow().assertIsFocused()
    }

    @Test
    fun restrictedRow_pressOpensAppInfoOnly() { // D-pad and touch
        withAccessDetailScreen()
        show()
        moveDownTo(appInfoRow())
        appInfoRow().press(Key.DirectionCenter)
        compose.waitForIdle()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:${app.packageName}", started.dataString)
        appInfoRow().performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(app).nextStartedActivity.action)
        assertEquals(null, shadowOf(app).nextStartedActivity) // nothing else: no grant screen, no disclosure step skipped
        compose.onAllNodesWithText(str(R.string.access_disclosure_title)).assertCountEquals(0)
    }

    @Test
    fun restrictedRow_hiddenWithoutTheRestriction() { // no input
        show() // no access screen resolves here: no restricted setting to tell
        compose.onAllNodesWithText(str(R.string.access_restricted)).assertCountEquals(0)
    }

    // The nudity filter as a radio group: one row per value, strictest first, the mark on the filter in force

    private val strictestFirst = listOf(NudityFilter.HIDE_NUDES, NudityFilter.HIDE_EXPLICIT, NudityFilter.SHOW_ALL)

    /** Asserts that the row of [marked] is the selected radio and the others are not. */
    private fun assertMarked(marked: NudityFilter) {
        for (f in strictestFirst) {
            nudityRow(f).assert(radio)
            if (f == marked) nudityRow(f).assertIsSelected() else nudityRow(f).assertIsNotSelected()
        }
    }

    @Test
    fun nudityRows_strictestFirst_underTheirHeader_theAboutTextAfter() { // no input
        show()
        val header = compose.onNodeWithText(str(R.string.settings_nudity)).getUnclippedBoundsInRoot()
        val tops = strictestFirst.map { nudityRow(it).getUnclippedBoundsInRoot().top }
        assertEquals(tops.sorted(), tops)
        assertTrue(header.bottom <= tops[0])
        val last = nudityRow(NudityFilter.SHOW_ALL).getUnclippedBoundsInRoot()
        assertTrue(last.bottom <= compose.onNodeWithText(str(R.string.settings_nudity_about)).getUnclippedBoundsInRoot().top)
    }

    @Test
    fun nudityRows_dpadCenter_choosesItsValue() { // D-pad
        show()
        close().press(Key.DirectionDown)
        for (f in strictestFirst) {
            moveDownTo(nudityRow(f))
            nudityRow(f).press(Key.DirectionCenter)
            compose.waitForIdle()
        }
        assertEquals(strictestFirst, nudityChosen)
    }

    @Test
    fun nudityRows_tap_choosesItsValue() { // touch
        show()
        for (f in strictestFirst.reversed()) nudityRow(f).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(strictestFirst.reversed(), nudityChosen)
    }

    @Test
    fun nudityMark_followsTheValue() { // no input
        show()
        assertMarked(NudityFilter.HIDE_EXPLICIT)
        nudityChoice = NudityFilter.SHOW_ALL
        compose.waitForIdle()
        assertMarked(NudityFilter.SHOW_ALL)
        nudityChoice = NudityFilter.HIDE_NUDES
        compose.waitForIdle()
        assertMarked(NudityFilter.HIDE_NUDES)
        compose.onAllNodesWithText(str(R.string.settings_nudity_locked_tv)).assertCountEquals(0)
    }

    @Test
    fun nudityLocked_marksTheLocksValue_andSaysWhyInThatRow() { // no input
        nudityChoice = NudityFilter.SHOW_ALL // the stored choice stays, the lock's value is in force
        nudityLock = NudityLock.PARENTAL_CONTROLS
        show()
        assertMarked(NudityFilter.HIDE_NUDES)
        nudityRow(NudityFilter.HIDE_NUDES).assertTextContains(str(R.string.settings_nudity_locked_tv))
        compose.onAllNodesWithText(str(R.string.settings_nudity_locked_tv)).assertCountEquals(1)
        nudityLock = NudityLock.RESTRICTED_PROFILE
        compose.waitForIdle()
        nudityRow(NudityFilter.HIDE_NUDES).assertTextContains(str(R.string.settings_nudity_locked_profile))
    }

    @Test
    fun nudityLocked_rowsAreFocusable_butAPressDoesNothing() { // D-pad and touch
        nudityLock = NudityLock.RESTRICTED_PROFILE
        show()
        close().press(Key.DirectionDown)
        for (f in strictestFirst) {
            moveDownTo(nudityRow(f))
            nudityRow(f).press(Key.DirectionCenter)
            compose.waitForIdle()
        }
        for (f in strictestFirst) nudityRow(f).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(emptyList<NudityFilter>(), nudityChosen)
        assertMarked(NudityFilter.HIDE_NUDES)
    }
}
