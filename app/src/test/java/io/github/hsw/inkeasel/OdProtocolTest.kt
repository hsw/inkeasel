package io.github.hsw.inkeasel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Inflater
import kotlin.random.Random

class OdProtocolTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /** A display record (config packet 0x20, 46 bytes) like a reTerminal E1001's. */
    private fun display(panel: Int = 60, w: Int = 800, h: Int = 480, rotation: Int = 0, scheme: Int = 5) = ByteArray(46).also {
        it[2] = panel.toByte(); it[3] = (panel shr 8).toByte()
        it[4] = w.toByte(); it[5] = (w shr 8).toByte()
        it[6] = h.toByte(); it[7] = (h shr 8).toByte()
        it[14] = rotation.toByte()
        it[21] = scheme.toByte()
        it[22] = 25
    }

    /** `[length:2][version][packets][crc:2]` with a system packet, then the display. */
    private fun config(d: ByteArray, firstType: Int = 0x01): ByteArray {
        val packets = bytes(0, firstType) + ByteArray(22) + bytes(1, 0x20) + d
        return bytes(packets.size + 1, 0, 1) + packets + bytes(0xAB, 0xCD)
    }

    @Test
    fun requests() {
        assertArrayEquals(bytes(0x00, 0x40), Od.request(Od.READ_CONFIG))
        assertArrayEquals(bytes(0x00, 0x70), Od.start())
        assertArrayEquals(bytes(0x00, 0x71, 1, 2, 3), Od.data(bytes(1, 2, 3)))
        assertArrayEquals(bytes(0x00, 0x72, 0x00), Od.end())
    }

    @Test
    fun replies() {
        val ack = Od.Reply.parse(bytes(0x00, 0x71))!!
        assertTrue(ack.ok)
        assertEquals(0x71, ack.op)
        val nack = Od.Reply.parse(bytes(0xFF, 0x70))!!
        assertFalse(nack.ok)
        assertEquals(0xFF, nack.status)
        assertTrue(Od.Reply.parse(bytes(0x00, 0x40, 0xFE))!!.authRequired)
        assertTrue(Od.Reply.parse(bytes(0xFE, 0x40))!!.authRequired)
        assertFalse(Od.Reply.parse(bytes(0x00, 0x40, 0xFE))!!.ok)
        assertNull(Od.Reply.parse(bytes(0x00)))
    }

    @Test
    fun configReader_assemblesChunks() {
        val r = Od.ConfigReader()
        assertNull(r.add(bytes(0, 0, 5, 0, 1, 2)))
        assertNull(r.add(bytes(1, 0, 3)))
        assertArrayEquals(bytes(1, 2, 3, 4, 5), r.add(bytes(2, 0, 4, 5)))
    }

    @Test
    fun display_isFoundAfterOtherPackets() {
        val d = Od.display(config(display()))!!
        assertEquals(60, d.panel)
        assertEquals(800, d.width)
        assertEquals(480, d.height)
        assertEquals(0, d.rotation)
        assertEquals(ColourScheme.G4, d.colours)
        assertNull(d.rejection)
    }

    @Test
    fun display_unknownPacketBeforeIt_isNone() {
        assertNull(Od.display(config(display(), firstType = 0x7E)))
        assertNull(Od.display(bytes(1, 0, 1)))
    }

    @Test
    fun rotation_indexOrDegrees() {
        assertEquals(90, OdDisplay(display(rotation = 1)).rotation)
        assertEquals(180, OdDisplay(display(rotation = 180)).rotation)
        assertNull(OdDisplay(display(rotation = 7)).rotation)
    }

    @Test
    fun fourGreys_isVerified_twoPlanesRowPadded() {
        val f = OdDisplay.lookup(OdDisplay(display()))
        val fmt = f.formatOrNull!!
        assertEquals(800, fmt.width)
        assertEquals(480, fmt.height)
        assertEquals(RowPlanes(2), fmt.packing)
        assertEquals(96_000, fmt.payloadSize)
        assertTrue(fmt.verified)
    }

    @Test
    fun mono_isOnePlane_notVerified() {
        val fmt = OdDisplay.lookup(OdDisplay(display(panel = 59, scheme = 0))).formatOrNull!!
        assertEquals(FrameFormats.OD_BW, fmt.palette)
        assertEquals(48_000, fmt.payloadSize)
        assertFalse(fmt.verified)
    }

    @Test
    fun reasons() {
        assertEquals(Txt(R.string.reason_od_scheme, 6), OdDisplay.lookup(OdDisplay(display(scheme = 6))).reasonOrNull)
        assertEquals(Txt(R.string.reason_od_panel, 0x28), OdDisplay.lookup(OdDisplay(display(panel = 0x28))).reasonOrNull)
        assertEquals(Txt(R.string.reason_colours_size, "E6", 801, 480), OdDisplay.lookup(OdDisplay(display(w = 801, scheme = 4))).reasonOrNull)
        assertEquals(Txt(R.string.reason_no_caps), OdDisplay.lookup(null).reasonOrNull)
        assertTrue(OdDisplay.lookup(OdDisplay(display(rotation = 1))).reasonOrNull!!.id == R.string.reason_rotation)
    }

    @Test
    fun nowMeanFrames_neverGetOpenDisplayFormats() {
        assertEquals(Txt(R.string.reason_colours, "G4"), FrameFormats.find(FrameCaps(800, 480, ColourScheme.G4, 0)).reasonOrNull)
    }

    @Test
    fun rowPlanes_greyCodesSplitIntoPlanes_rowsPadded() {
        // black, dark grey, light grey, white, then white: codes 3, 1, 2, 0, 0; each 5-pixel row takes one byte
        val idx = bytes(0, 1, 2, 3, 3, 3, 3, 3, 3, 0)
        val out = RowPlanes(2).pack(idx, FrameFormats.OD_G4, 5, 2)
        // plane 0 (code bit 0): 1 1 0 0 0 | 0 0 0 0 1; plane 1 (bit 1): 1 0 1 0 0 | 0 0 0 0 1
        assertArrayEquals(bytes(0xC0, 0x08, 0xA0, 0x08), out)
    }

    @Test
    fun rowPlanes_monoAndBwr() {
        assertArrayEquals(bytes(0x40), RowPlanes(1).pack(bytes(0, 1, 0), FrameFormats.OD_BW, 3, 1))
        // white, black, red: first plane white or red, second red
        assertArrayEquals(bytes(0xA0, 0x20), RowPlanes(2).pack(bytes(0, 1, 2), FrameFormats.OD_BWR, 3, 1))
    }

    @Test
    fun advert_batteryAndTemperature() {
        val a = OdAdvert.parse(bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 130, 0x9C, 0x01))!!
        assertEquals(4120, a.batteryMv)
        assertEquals(25.0, a.temperatureC, 0.0)
        assertEquals(Txt(R.string.od_battery, 4, 12), a.describe()[1])
        assertNull(OdAdvert.parse(bytes(1, 2, 3)))
    }

    @Test
    fun selectedFrame_roundTripsTheDisplay() {
        val d = OdDisplay(display())
        val f = SelectedFrame("OD000001", "AA:BB:CC:DD:EE:01", null, 1_000L, FrameKind.OPENDISPLAY, d)
        val m = f.encode()
        assertEquals("opendisplay", m[SelectedFrame.KIND])
        assertEquals("G4", m[SelectedFrame.COLOUR])
        assertEquals(800, m[SelectedFrame.WIDTH])
        assertEquals(f, SelectedFrame.decode(m))
        assertTrue(SelectedFrame.decode(m)!!.lookup.formatOrNull!!.verified)
    }

    @Test
    fun seenFrame_openDisplayIsAskedOnceChosen() {
        assertNull(SeenFrame("OD000001", "AA:BB:CC:DD:EE:01", -60, null, FrameKind.OPENDISPLAY).lookup)
    }

    /** A 4-grey-like payload: noise in the middle, long white runs around it. */
    private fun sample(): ByteArray {
        val r = Random(7)
        return ByteArray(96_000) { i -> if (i in 30_000 until 60_000) r.nextInt(256).toByte() else 0 }
    }

    private fun inflate(z: ByteArray, nowrap: Boolean, size: Int): ByteArray {
        val inf = Inflater(nowrap)
        inf.setInput(z)
        val out = ByteArray(size)
        var n = 0
        while (n < size && !inf.needsInput()) n += inf.inflate(out, n, size - n)
        inf.end()
        return out.copyOf(n)
    }

    @Test
    fun zlib_inflatesBack_withA512ByteWindowHeader() {
        val data = sample()
        val z = Od.zlib(data)
        assertEquals(0x18, z[0].toInt() and 0xFF) // deflate, 2^9 window
        assertEquals(0, ((z[0].toInt() and 0xFF) * 256 + (z[1].toInt() and 0xFF)) % 31)
        assertTrue(z.size < data.size / 2)
        val inf = Inflater()
        inf.setInput(z)
        val out = ByteArray(data.size)
        assertEquals(data.size, inf.inflate(out))
        assertTrue(inf.finished()) // the Adler-32 trailer matched
        assertArrayEquals(data, out)
    }

    @Test
    fun zlib_eachPartInflatesWithoutTheOnesBefore() {
        // a match reaching further back than 512 bytes would need the earlier parts' bytes
        val data = sample()
        val (z, starts) = Od.zlibWithFlushes(data)
        val raw = z.copyOfRange(2, z.size - 4)
        assertEquals(data.size / Od.WINDOW + 1, starts.size)
        for ((k, at) in starts.withIndex()) {
            val part = data.copyOfRange(k * Od.WINDOW, minOf(data.size, (k + 1) * Od.WINDOW))
            assertArrayEquals("part $k", part, inflate(raw.copyOfRange(at, raw.size), true, part.size))
        }
    }

    @Test
    fun startCompressed_sizeLittleEndianThenHead() {
        assertArrayEquals(bytes(0x00, 0x70, 0x00, 0x77, 0x01, 0x00, 0x18, 0xDA), Od.startCompressed(96_000, bytes(0x18, 0xDA)))
        assertEquals(200, Od.startCompressed(1, ByteArray(Od.START_DATA)).size)
    }

    @Test
    fun takesZlib_byTransmissionModes() {
        val streaming = OdDisplay(display()) // modes 25: streaming inflate
        assertTrue(streaming.takesZlib(90_000))
        val buffered = OdDisplay(display().also { it[22] = 0x02 })
        assertTrue(buffered.takesZlib(10_000))
        assertFalse(buffered.takesZlib(60_000))
        assertFalse(OdDisplay(display().also { it[22] = 0x08 }).takesZlib(10))
    }
}
