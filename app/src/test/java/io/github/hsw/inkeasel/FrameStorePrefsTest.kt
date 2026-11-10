package io.github.hsw.inkeasel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [FrameStore] in its prefs: the chosen frame, and what an upload saw of it again ([FrameStore.seen]). */
@RunWith(RobolectricTestRunner::class)
class FrameStorePrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = FrameStore(context)

    /** A display record of [scheme] at 800×480 (5: four greys, 0: mono). */
    private fun display(scheme: Int) = OdDisplay(ByteArray(46).also {
        it[2] = 60; it[4] = 0x20; it[5] = 0x03; it[6] = 0xE0.toByte(); it[7] = 0x01; it[21] = scheme.toByte(); it[22] = 1
    })

    private val od = SelectedFrame("OD000001", "AA:BB:CC:DD:EE:01", null, 1_000L, FrameKind.OPENDISPLAY, display(5))

    @Before
    fun setUp() {
        context.getSharedPreferences("frame", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun seen_withoutADisplay_keepsTheStoredOne() {
        store.select(od)
        store.seen(od.name, "AA:BB:CC:DD:EE:02", null, 2_000L)
        val f = store.selected()!!
        assertEquals("AA:BB:CC:DD:EE:02", f.address)
        assertEquals(2_000L, f.seenAt)
        assertEquals(display(5), f.display)
    }

    @Test
    fun seen_withANewDisplay_storesIt() {
        // the frame was switched to mono since it was chosen: the next upload's lookup sees the new panel
        store.select(od)
        store.seen(od.name, od.address, null, 2_000L, display(0))
        assertEquals(display(0), store.selected()!!.display)
        assertEquals(FrameFormats.OD_BW, store.selected()!!.lookup.formatOrNull!!.palette)
    }

    @Test
    fun seen_ofAnotherFrame_isIgnored() {
        store.select(od)
        store.seen("OD000002", "AA:BB:CC:DD:EE:03", null, 2_000L, display(0))
        assertEquals(od, store.selected())
    }

    @Test
    fun seen_withNothingChosen_choosesNothing() {
        store.seen(od.name, od.address, null, 2_000L, display(5))
        assertNull(store.selected())
    }
}
