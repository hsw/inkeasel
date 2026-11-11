package io.github.hsw.inkeasel

import android.app.Application
import android.content.ComponentName
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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import io.github.hsw.inkeasel.catalog.NudityFilter
import org.junit.Assert.assertEquals
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

    private fun show() {
        compose.setContent {
            FrameSettingsContent(
                selected = selected,
                ble = BleAccess(permitted = true, blocked = false, request = {}),
                poster = false,
                access = false,
                pending = false,
                nudity = NudityFilter.HIDE_NUDES,
                nudityLock = null,
                onNudity = {},
                onPoster = { PosterSwitch.Press.TURN_ON },
                onDisclosureConfirmed = {},
                sidePadding = 48,
                probing = probing,
                frames = frames,
                scanError = scanError,
                btProblem = btProblem,
                locationOff = locationOff,
                scanRun = scanRun,
                onRetry = {},
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
    private fun nudityRow(): SemanticsNodeInteraction = compose.onNodeWithText(str(R.string.settings_nudity))
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
        compose.onNodeWithText(str(R.string.settings_nudity)).assertIsFocused()
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
                nudity = NudityFilter.HIDE_NUDES,
                nudityLock = null,
                onNudity = {},
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
        // back on Close, a frame showing up later leaves the focus there
        row(supported).press(Key.DirectionUp)
        compose.waitForIdle()
        close().assertIsFocused()
        frames = listOf(supported, unsupported)
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
}
