package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStoreTest {
    private val e6 = FrameAdvert.parse(byteArrayOf(0x43, 0x13, 0x64, 0x15))
    private val bw = FrameAdvert.parse(byteArrayOf(0x03, 0x13, 0x64))

    @Test
    fun encode_writesNameAddressAdvertAndItsProperties() {
        val m = SelectedFrame("NM000001", "AA:BB:CC:DD:EE:01", e6, 1_000L).encode()
        assertEquals("nowmean", m[SelectedFrame.KIND])
        assertEquals("NM000001", m[SelectedFrame.NAME])
        assertEquals("AA:BB:CC:DD:EE:01", m[SelectedFrame.ADDRESS])
        assertEquals(1_000L, m[SelectedFrame.SEEN_AT])
        assertEquals("43136415", m[SelectedFrame.ADVERT])
        assertEquals("E6", m[SelectedFrame.COLOUR])
        assertEquals("TA", m[SelectedFrame.PANEL])
        assertEquals(1024, m[SelectedFrame.WIDTH])
        assertEquals(600, m[SelectedFrame.HEIGHT])
        assertEquals(0, m[SelectedFrame.ROTATION])
        assertEquals(100, m[SelectedFrame.BATTERY])
        assertEquals(21, m[SelectedFrame.FIRMWARE])
        assertEquals(false, m[SelectedFrame.SAFE_MODE])
        assertTrue(m.keys.all(SelectedFrame::isKey))
    }

    @Test
    fun roundTrip() {
        val f = SelectedFrame("NM000001", "AA:BB:CC:DD:EE:01", e6, 1_000L)
        assertEquals(f, SelectedFrame.decode(f.encode()))
        assertEquals(FrameFormats.DEFAULT, SelectedFrame.decode(f.encode())!!.lookup.formatOrNull)
    }

    @Test
    fun decode_noAdvert() {
        val f = SelectedFrame("NM000001", "AA:BB:CC:DD:EE:01", null, 0L)
        val back = SelectedFrame.decode(f.encode())!!
        assertNull(back.advert)
        assertTrue(back.lookup is FormatLookup.Unsupported)
    }

    @Test
    fun decode_legacyKeysAreNotASelection() {
        // what the app wrote before the picker: the first frame a scan found
        assertNull(SelectedFrame.decode(mapOf("name" to "NM000001", "address" to "AA:BB:CC:DD:EE:01", "endedAt" to 5L)))
        assertNull(SelectedFrame.decode(emptyMap<String, Any>()))
    }

    @Test
    fun decode_kind() {
        val m = SelectedFrame("NM000001", "AA:BB:CC:DD:EE:01", e6, 1_000L).encode()
        assertEquals(FrameKind.NOWMEAN, SelectedFrame.decode(m)!!.kind)
        assertNull(SelectedFrame.decode(m - SelectedFrame.KIND)) // no kind: not written by this build
        assertNull(SelectedFrame.decode(m + (SelectedFrame.KIND to "other"))) // a kind this build does not know
    }

    @Test
    fun decode_corrupt() {
        val kind = SelectedFrame.KIND to "nowmean"
        assertNull(SelectedFrame.decode(mapOf(kind, SelectedFrame.NAME to "", SelectedFrame.ADDRESS to "AA:BB:CC:DD:EE:01")))
        assertNull(SelectedFrame.decode(mapOf(kind, SelectedFrame.NAME to "NM000001")))
        val f = SelectedFrame.decode(
            mapOf(kind, SelectedFrame.NAME to "NM000001", SelectedFrame.ADDRESS to "AA:BB:CC:DD:EE:01", SelectedFrame.ADVERT to "zz")
        )!!
        assertNull(f.advert)
        assertEquals(0L, f.seenAt)
    }

    @Test
    fun otherKeysOfTheFile_areNotOurs() {
        assertFalse(SelectedFrame.isKey("endedAt"))
        assertFalse(SelectedFrame.isKey("onFrame"))
        assertFalse(SelectedFrame.isKey("name"))
    }

    @Test
    fun merge_dedupByName_latestWins_sortedByName() {
        var list = emptyList<SeenFrame>()
        list = SeenFrame.merge(list, SeenFrame("NM2", "AA:BB:CC:DD:EE:02", -70, e6))
        list = SeenFrame.merge(list, SeenFrame("NM1", "AA:BB:CC:DD:EE:01", -60, e6))
        list = SeenFrame.merge(list, SeenFrame("NM2", "AA:BB:CC:DD:EE:03", -50, bw)) // new MAC, same frame
        assertEquals(listOf("NM1", "NM2"), list.map { it.name })
        assertEquals("AA:BB:CC:DD:EE:03", list[1].address)
        assertEquals(-50, list[1].rssi)
        assertEquals(Txt(R.string.reason_untested_format), list[1].lookup.reasonOrNull)
        assertTrue(list[0].lookup is FormatLookup.Supported)
        assertEquals(FrameKind.NOWMEAN, list[0].kind)
    }
}
