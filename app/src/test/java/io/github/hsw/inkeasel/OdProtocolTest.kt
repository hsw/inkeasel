package io.github.hsw.inkeasel

import io.github.hsw.inkeasel.OdFixtures.config
import io.github.hsw.inkeasel.OdFixtures.display
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.random.Random

class OdProtocolTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

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
        assertEquals(Txt(R.string.reason_colour_scheme, 6), OdDisplay.lookup(OdDisplay(display(scheme = 6))).reasonOrNull)
        assertEquals(Txt(R.string.reason_greys_panel, 0x28), OdDisplay.lookup(OdDisplay(display(panel = 0x28))).reasonOrNull)
        assertEquals(Txt(R.string.reason_colours_size, ColourScheme.E6.name, 801, 480), OdDisplay.lookup(OdDisplay(display(w = 801, scheme = 4))).reasonOrNull)
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
        assertEquals(Txt(R.string.advert_battery_volts, 4, 12), a.describe()[1])
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

    /** Random 600-byte blocks, each repeated: matches the encoder may not take sit 600 bytes back. */
    private fun repeats(): ByteArray {
        val r = Random(11)
        val block = ByteArray(600)
        return ByteArray(96_000) { i ->
            if (i % 1200 == 0) r.nextBytes(block)
            block[i % 600]
        }
    }

    @Test
    fun zlib_inflatesBack_withA512ByteWindowHeader() {
        val data = sample()
        val z = Od.zlib(data)
        assertEquals(0x18, z[0].toInt() and 0xFF) // deflate, 2^9 window
        assertEquals(0, ((z[0].toInt() and 0xFF) * 256 + (z[1].toInt() and 0xFF)) % 31)
        assertEquals(0, z[1].toInt() and 0x20) // no preset dictionary
        assertTrue(z.size < data.size / 2)
        val inf = Inflater()
        inf.setInput(z)
        val out = ByteArray(data.size)
        assertEquals(data.size, inf.inflate(out))
        assertTrue(inf.finished()) // the Adler-32 trailer matched
        inf.end()
        assertArrayEquals(data, out)
    }

    @Test
    fun zlib_everyMatchStaysInTheWindow() {
        val payloads = mapOf(
            "sample" to sample(),
            "repeats" to repeats(),
            "white" to ByteArray(96_000) { 0xFF.toByte() },
            "black" to ByteArray(96_000),
        )
        for ((name, data) in payloads) {
            val r = TinyInflate.zlib(Od.zlib(data))
            assertArrayEquals(name, data, r.data)
            assertEquals(name, Od.WINDOW, r.window)
            assertTrue("$name: distance ${r.maxDistance}", r.maxDistance <= Od.WINDOW)
        }
    }

    @Test
    fun tinyInflate_seesMatchesBeyondTheWindow() {
        // the check above is not vacuous: a 32 KB-window stream of the same data reaches 600 bytes back
        val data = repeats()
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(data)
        d.finish()
        val buf = ByteArray(data.size + 1024)
        val n = d.deflate(buf)
        assertTrue(d.finished())
        d.end()
        val r = TinyInflate.zlib(buf.copyOf(n))
        assertArrayEquals(data, r.data)
        assertTrue("distance ${r.maxDistance}", r.maxDistance > Od.WINDOW)
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
        // buffered: under 50 KB compressed
        assertTrue(buffered.takesZlib(Od.BUFFERED_MAX - 1))
        assertFalse(buffered.takesZlib(Od.BUFFERED_MAX))
        assertTrue(streaming.takesAnyZlib)
        assertTrue(buffered.takesAnyZlib)
        assertFalse(OdDisplay(display().also { it[22] = 0x08 }).takesAnyZlib)
    }

    @Test
    fun zlibFor_onlyWhenTheFrameTakesItAndItIsSmaller() {
        val streaming = OdDisplay(display())
        val white = ByteArray(96_000)
        assertArrayEquals(Od.zlib(white), Od.zlibFor(streaming, white))
        // no zlib mode: sent uncompressed (and not compressed for nothing)
        assertNull(Od.zlibFor(OdDisplay(display().also { it[22] = 0x08 }), white))
        // noise does not get smaller
        val noise = Random(3).nextBytes(10_000)
        assertNull(Od.zlibFor(streaming, noise))
        // buffered inflate only: a stream of 50 KB or more does not fit the frame's buffer
        val buffered = OdDisplay(display().also { it[22] = 0x02 })
        val big = Random(5).nextBytes(80_000).also { r -> for (i in r.indices step 3) r[i] = 0 }
        val z = Od.zlib(big)
        assertTrue("${z.size} B", z.size >= Od.BUFFERED_MAX && z.size < big.size)
        assertNull(Od.zlibFor(buffered, big))
        assertArrayEquals(z, Od.zlibFor(streaming, big))
    }

    @Test
    fun everyScheme_hasItsFormatOrReason() {
        fun lookup(scheme: Int, panel: Int = 60, w: Int = 800) = OdDisplay.lookup(OdDisplay(display(panel = panel, w = w, scheme = scheme)))
        // scheme → palette, packing, payload at 800×480; only four greys verified on hardware
        val formats = mapOf(
            0 to Triple(FrameFormats.OD_BW, RowPlanes(1), 48_000),
            1 to Triple(FrameFormats.OD_BWR, RowPlanes(2), 96_000),
            2 to Triple(FrameFormats.OD_BWY, RowPlanes(2), 96_000),
            4 to Triple(FrameFormats.E6, Nibbles4(), 192_000),
            5 to Triple(FrameFormats.OD_G4, RowPlanes(2), 96_000),
        )
        for ((scheme, want) in formats) {
            val f = lookup(scheme).formatOrNull!!
            assertEquals("scheme $scheme", want.first, f.palette)
            assertEquals("scheme $scheme", want.second, f.packing)
            assertEquals("scheme $scheme", want.third, f.payloadSize)
            assertEquals("scheme $scheme", scheme == 5, f.verified)
        }
        // BWRY and the rest are refused
        for (scheme in listOf(3, 6, 7, 8, 9)) assertEquals(Txt(R.string.reason_colour_scheme, scheme), lookup(scheme).reasonOrNull)
        assertEquals(Txt(R.string.reason_greys_panel, 0x48), lookup(5, panel = 0x48).reasonOrNull)
        assertEquals(Txt(R.string.reason_unknown_size), lookup(5, w = 0).reasonOrNull)
    }

    @Test
    fun configReader_errors() {
        assertThrows(IllegalArgumentException::class.java) { Od.ConfigReader().add(bytes(0, 0, 5)) } // chunk 0 short
        val r = Od.ConfigReader()
        assertNull(r.add(bytes(0, 0, 5, 0, 1, 2)))
        assertThrows(IllegalArgumentException::class.java) { r.add(bytes(1, 0)) } // a later chunk with no data
        // more data than the total: cut to it
        assertArrayEquals(bytes(1, 2), Od.ConfigReader().add(bytes(0, 0, 2, 0, 1, 2, 3, 4)))
    }

    @Test
    fun display_cutMidPacket_isNone() {
        val c = config(display())
        // the display packet runs past the end (the CRC is taken off the last two bytes)
        assertNull(Od.display(c.copyOf(c.size - 10)))
    }

    @Test
    fun displayRecord_tooShortOrJunk() {
        assertThrows(IllegalArgumentException::class.java) { OdDisplay(ByteArray(22)) }
        assertNull(OdDisplay.parseHex("zz"))
        assertNull(OdDisplay.parseHex("0011"))
        assertNull(OdDisplay.parseHex(null))
        // a stored frame with a corrupt record: kept, but it cannot be drawn on
        val m = SelectedFrame("OD000001", "AA:BB:CC:DD:EE:01", null, 1_000L, FrameKind.OPENDISPLAY, OdDisplay(display())).encode()
            .toMutableMap().also { it[SelectedFrame.DISPLAY] = "zz" }
        val f = SelectedFrame.decode(m)!!
        assertNull(f.display)
        assertEquals(Txt(R.string.reason_no_caps), f.lookup.reasonOrNull)
    }

    @Test
    fun seenFrame_describe() {
        val a = OdAdvert.parse(bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 130, 0x9C, 0x01))
        assertEquals(listOf(Txt(R.string.advert_opendisplay), Txt(R.string.advert_battery_volts, 4, 12)), SeenFrame("OD000001", "A", -60, null, FrameKind.OPENDISPLAY, a).describe())
        // no advertisement this app reads: still named as an OpenDisplay frame
        assertEquals(listOf(Txt(R.string.advert_opendisplay)), SeenFrame("OD000001", "A", -60, null, FrameKind.OPENDISPLAY).describe())
    }
}
