package io.github.hsw.inkeasel

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Choosing an OpenDisplay frame ([MainViewModel.select]): its display is read first ([MainViewModel.readDisplay],
 * scripted here), and only a frame this app can draw on is chosen. One read at a time, and the newest press
 * wins: another frame chosen meanwhile, or closing the settings, cancels the read without a toast.
 */
@RunWith(RobolectricTestRunner::class)
class MainViewModelOdTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val owner = ViewModelStore()
    private lateinit var vm: MainViewModel

    private val frame = SeenFrame("OD000001", "AA:BB:CC:DD:EE:01", -60, null, FrameKind.OPENDISPLAY)
    private val other = SeenFrame("OD000002", "AA:BB:CC:DD:EE:02", -60, null, FrameKind.OPENDISPLAY)
    private val nowMean = SeenFrame("NM000001", "AA:BB:CC:DD:EE:03", -60, null, FrameKind.NOWMEAN)

    /** The display [frame] reports, once the test completes it. */
    private lateinit var reported: CompletableDeferred<OdDisplay>

    /** The display [other] reports. */
    private val reportedByOther = CompletableDeferred<OdDisplay>()
    private var reads = 0
    private var selectedCalls = 0
    private val toasts = mutableListOf<Txt>()

    /** A display record of [scheme] at 800×480 (5: four greys, verified; 6: sixteen greys, refused). */
    private fun display(scheme: Int) = OdDisplay(OdFixtures.display(scheme = scheme))

    @Before
    fun setUp() {
        app.getSharedPreferences("frame", Context.MODE_PRIVATE).edit().clear().commit()
        vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[MainViewModel::class.java]
        reported = CompletableDeferred()
        vm.readDisplay = { name ->
            reads++
            when (name) {
                frame.name -> reported.await()
                other.name -> reportedByOther.await()
                else -> error("read of $name")
            }
        }
        vm.toast = { toasts += it }
        vm.openSettings()
        idle()
    }

    @After
    fun tearDown() {
        owner.clear()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun select(f: SeenFrame = frame) = vm.select(f) { selectedCalls++ }

    @Test
    fun aSupportedDisplay_isChosen() {
        select()
        idle()
        assertEquals(frame.name, vm.probing)
        reported.complete(display(5))
        idle()
        assertNull(vm.probing)
        assertEquals(1, selectedCalls)
        assertEquals(display(5), vm.selected!!.display)
        assertEquals(FrameKind.OPENDISPLAY, vm.selected!!.kind)
        assertEquals(display(5), FrameStore(app).selected()!!.display)
        assertFalse(vm.settingsOpen)
        assertEquals(emptyList<Txt>(), toasts) // verified: no "not verified" toast
    }

    @Test
    fun aSecondPressWhileReading_isIgnored() {
        select()
        idle()
        select()
        idle()
        assertEquals(1, reads)
        reported.complete(display(5))
        idle()
        assertEquals(1, selectedCalls)
    }

    @Test
    fun anUnsupportedDisplay_isNotChosen_andSaysWhy() {
        select()
        reported.complete(display(6))
        idle()
        assertNull(vm.probing)
        assertNull(vm.selected)
        assertEquals(0, selectedCalls)
        assertTrue(vm.settingsOpen)
        assertEquals(listOf(Txt(R.string.frame_toast_unsupported, frame.name, Txt(R.string.reason_colour_scheme, 6))), toasts)
    }

    @Test
    fun aFailedRead_isNotChosen_andSaysWhy() {
        select()
        reported.completeExceptionally(TxtException(Txt(R.string.frame_err_encrypted)))
        idle()
        assertNull(vm.probing)
        assertNull(vm.selected)
        assertEquals(0, selectedCalls)
        assertEquals(listOf(Txt(R.string.frame_toast_reason, frame.name, Txt(R.string.frame_err_encrypted))), toasts)
        // the next press reads again
        reported = CompletableDeferred()
        select()
        idle()
        assertEquals(2, reads)
    }

    @Test
    fun closingTheSettings_cancelsTheRead_withoutAToast() {
        select()
        idle()
        vm.closeSettings()
        idle()
        assertNull(vm.probing)
        assertNull(vm.selected)
        assertEquals(0, selectedCalls)
        assertEquals(emptyList<Txt>(), toasts)
        reported.complete(display(5)) // too late: nothing happens
        idle()
        assertNull(vm.selected)
    }

    @Test
    fun aNowMeanFrameChosenWhileReading_staysChosen() {
        select()
        idle()
        select(nowMean)
        idle()
        assertNull(vm.probing)
        assertEquals(1, selectedCalls)
        assertEquals(nowMean.name, vm.selected!!.name)
        reported.complete(display(5)) // the cancelled read finishes anyway: it chooses nothing
        idle()
        assertEquals(nowMean.name, vm.selected!!.name)
        assertEquals(FrameKind.NOWMEAN, vm.selected!!.kind)
        assertEquals(nowMean.name, FrameStore(app).selected()!!.name)
        assertEquals(1, selectedCalls)
        assertEquals(emptyList<Txt>(), toasts)
    }

    @Test
    fun anotherOpenDisplayFramePressedWhileReading_isReadInstead() {
        select()
        idle()
        select(other)
        idle()
        assertEquals(other.name, vm.probing)
        assertEquals(2, reads)
        reported.complete(display(6)) // the first read ends late, refused: no toast, no change
        idle()
        assertEquals(other.name, vm.probing)
        reportedByOther.complete(display(5))
        idle()
        assertNull(vm.probing)
        assertEquals(other.name, vm.selected!!.name)
        assertEquals(other.name, FrameStore(app).selected()!!.name)
        assertEquals(1, selectedCalls)
        assertEquals(emptyList<Txt>(), toasts)
    }

    @Test
    fun aFailureOfACancelledRead_saysNothing() {
        select()
        idle()
        select(other)
        idle()
        reported.completeExceptionally(TxtException(Txt(R.string.frame_err_encrypted)))
        idle()
        assertEquals(other.name, vm.probing)
        assertEquals(emptyList<Txt>(), toasts)
    }

    @Test
    fun reopenedSettings_showNoStaleRead_andReadAgain() {
        select()
        idle()
        vm.closeSettings()
        vm.openSettings()
        idle()
        assertNull(vm.probing)
        reported.complete(display(5))
        idle()
        assertNull(vm.selected)
        assertNull(vm.probing)
        // a press after reopening reads again (the old read no longer blocks it)
        reported = CompletableDeferred()
        select()
        idle()
        assertEquals(frame.name, vm.probing)
        assertEquals(2, reads)
        reported.complete(display(5))
        idle()
        assertEquals(frame.name, vm.selected!!.name)
        assertEquals(1, selectedCalls)
    }

    @Test
    fun theBluetoothPanel_cancelsTheRead() {
        select()
        idle()
        vm.showBlePanel(BleAsk.Stage.FIRST, null)
        idle()
        assertNull(vm.probing)
        reported.complete(display(5))
        idle()
        assertNull(vm.selected)
        assertEquals(0, selectedCalls)
    }
}
