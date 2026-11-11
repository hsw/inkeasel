package io.github.hsw.inkeasel

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import io.github.hsw.inkeasel.catalog.NudityFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The settings' stateful wrapper (`FrameSettings`) inside the real dialog, on Robolectric's Bluetooth shadows: the
 * scan it starts and stops, a scan error and "Retry", the Bluetooth state followed live, and the "No frames seen
 * yet" wait begun again at every scan start (it counts the starts the content's timer is keyed on). What the
 * content draws is tested with fake states in [FrameSettingsUiTest]. The location switch is read only before
 * Android 12; these tests run on SDK 36, so the location test composes the wrapper itself with `locationMatters`
 * set, on Robolectric's location shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp")
class FrameSettingsDialogTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(compose)

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val adapter: BluetoothAdapter get() = app.getSystemService(BluetoothManager::class.java).adapter

    /** The scans running now (Robolectric's LE scanner keeps the callback of each until it is stopped). */
    private fun scans(): Set<ScanCallback> = shadowOf(adapter.bluetoothLeScanner).scanCallbacks

    @Before
    fun bluetoothOn() {
        shadowOf(adapter).setEnabled(true)
    }

    private fun show() {
        compose.setContent {
            FrameSettingsDialog(
                selected = null,
                ble = BleAccess(permitted = true, blocked = false, request = {}),
                poster = false,
                access = false,
                pending = false,
                nudityChoice = NudityFilter.HIDE_EXPLICIT,
                nudityLock = null,
                onNudity = {},
                onPoster = { PosterSwitch.Press.TURN_ON },
                onDisclosureConfirmed = {},
                probing = null,
                onSelect = {},
                onCancel = {},
            )
        }
        compose.waitForIdle()
    }

    /** The wrapper alone, as before Android 12 (the scan needs location on). */
    private fun showLocationMatters() {
        compose.setContent {
            FrameSettings(
                selected = null,
                ble = BleAccess(permitted = true, blocked = false, request = {}),
                poster = false,
                access = false,
                pending = false,
                nudityChoice = NudityFilter.HIDE_EXPLICIT,
                nudityLock = null,
                onNudity = {},
                onPoster = { PosterSwitch.Press.TURN_ON },
                onDisclosureConfirmed = {},
                sidePadding = 48,
                probing = null,
                onSelect = {},
                onCancel = {},
                locationMatters = true,
            )
        }
        compose.waitForIdle()
    }

    private fun locationSwitched(on: Boolean) {
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(on)
        app.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun advance(ms: Long) = compose.advance(ms)
    private fun searchingShows() = compose.searchingShows()
    private fun noneSeenShows() = compose.noneSeenShows()

    private fun bluetoothSwitched(on: Boolean) {
        shadowOf(adapter).setEnabled(on)
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    @Test
    fun open_startsOneScan_noneSeenAfter25s() { // no input; clock
        show()
        assertEquals(1, scans().size)
        searchingShows()
        advance(ScanStatus.NONE_SEEN_MS + 1_000)
        noneSeenShows()
        assertEquals(1, scans().size) // the search goes on
    }

    @Test
    fun scanError_retry_startsANewScan_andTheWaitAgain() { // touch; clock
        show()
        advance(ScanStatus.NONE_SEEN_MS + 1_000)
        noneSeenShows()
        compose.runOnUiThread { scans().single().onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR) }
        compose.waitForIdle()
        val failed = Txt(R.string.scan_failed, Txt(R.string.scan_err_code, ScanCallback.SCAN_FAILED_INTERNAL_ERROR)).resolve(app)
        compose.onNodeWithText(failed).assertExists()
        assertEquals(0, scans().size) // the failed scan is stopped
        compose.onNode(hasText(str(R.string.retry)) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(failed).assertCountEquals(0) // "Retry" cleared the error
        assertEquals(1, scans().size)
        searchingShows()
        advance(ScanStatus.NONE_SEEN_MS - 1_000)
        searchingShows()
        advance(2_000)
        noneSeenShows()
    }

    @Test
    fun backFromTheBackground_startsANewScan_andTheWaitAgain() { // no input; clock
        show()
        advance(ScanStatus.NONE_SEEN_MS - 5_000)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        assertEquals(0, scans().size) // stopped in the background
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(1, scans().size)
        advance(10_000) // 30 s since the open, 10 s since the new start
        searchingShows()
        advance(ScanStatus.NONE_SEEN_MS)
        noneSeenShows()
    }

    @Test
    fun bluetoothOffAndOn_followedLive() { // no input
        show()
        bluetoothSwitched(on = false)
        compose.onNodeWithText(BtProblem.OFF.sentence.resolve(app)).assertExists()
        assertEquals(0, scans().size)
        bluetoothSwitched(on = true)
        compose.onAllNodesWithText(BtProblem.OFF.sentence.resolve(app)).assertCountEquals(0)
        assertEquals(1, scans().size)
        searchingShows()
    }

    @Test
    fun locationSwitchedOn_startsANewScan_andTheWaitAgain() { // no input; clock
        locationSwitched(on = false)
        showLocationMatters()
        val first = scans().single()
        compose.onNodeWithText(str(R.string.scanning_hint_location)).assertExists()
        advance(ScanStatus.NONE_SEEN_MS - 5_000)
        locationSwitched(on = true)
        compose.onAllNodesWithText(str(R.string.scanning_hint_location)).assertCountEquals(0)
        val second = scans().single() // the scan started while location was off is stopped
        assertNotSame(first, second)
        advance(10_000) // 30 s since the open, 10 s since the new start
        searchingShows()
        advance(ScanStatus.NONE_SEEN_MS)
        noneSeenShows()
    }
}
