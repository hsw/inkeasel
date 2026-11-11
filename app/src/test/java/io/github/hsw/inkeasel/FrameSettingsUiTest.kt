package io.github.hsw.inkeasel

import android.app.Application
import android.content.ComponentName
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
                probing = null,
                frames = frames,
                scanError = null,
                btProblem = null,
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
}
