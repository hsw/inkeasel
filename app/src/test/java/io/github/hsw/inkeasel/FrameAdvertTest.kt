package io.github.hsw.inkeasel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.hsw.inkeasel.FrameAdvert.Panel

class FrameAdvertTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun realFrame() {
        // the advertisement of a real frame (docs/device-api.md §14)
        val a = FrameAdvert.parse(bytes(0x43, 0x13, 0x64, 0x15))!!
        assertEquals(ColourScheme.E6, a.colour)
        assertEquals(Panel.TA, a.panel)
        assertEquals(0x13, a.resolutionCode)
        assertEquals(1024, a.width)
        assertEquals(600, a.height)
        assertEquals(0, a.rotation)
        assertFalse(a.safeMode)
        assertEquals(100, a.battery)
        assertEquals(21, a.firmware)
        assertEquals("43136415", a.hex)
        assertArrayEquals(bytes(0x43, 0x13, 0x64, 0x15), a.raw)
        assertEquals(
            listOf(
                Txt(R.string.advert_colour_panel, "E6", "TA"),
                Txt(R.string.advert_size_rotation, Txt(R.string.advert_size, 1024, 600), Txt(R.string.advert_degrees, 0)),
                Txt(R.string.advert_battery, 100),
                Txt(R.string.advert_firmware, 21),
            ),
            a.describe(),
        )
    }

    @Test
    fun docExample_panelAB() {
        val a = FrameAdvert.parse(bytes(0x42, 0x13, 0x64, 0x13))!!
        assertEquals(Panel.AB, a.panel)
        assertEquals(19, a.firmware)
    }

    @Test
    fun otherResolutions() {
        val sizes = mapOf(
            0x01 to (200 to 200), 0x02 to (122 to 250), 0x08 to (800 to 480), 0x09 to (960 to 640),
            0x10 to (960 to 672), 0x11 to (1440 to 600), 0x12 to (1360 to 480), 0x14 to (1200 to 1600),
        )
        for ((code, wh) in sizes) {
            val a = FrameAdvert.parse(bytes(0x43, code, 0x50))!!
            assertEquals("code $code", wh.first, a.width)
            assertEquals("code $code", wh.second, a.height)
        }
    }

    @Test
    fun rotations() {
        assertEquals(0, FrameAdvert.parse(bytes(0x43, 0x13, 0x64))!!.rotation)
        assertEquals(90, FrameAdvert.parse(bytes(0x43, 0x33, 0x64))!!.rotation)
        assertEquals(180, FrameAdvert.parse(bytes(0x43, 0x53, 0x64))!!.rotation)
        assertEquals(270, FrameAdvert.parse(bytes(0x43, 0x73, 0x64))!!.rotation)
        // the rotation bits do not leak into the resolution code
        assertEquals(1024, FrameAdvert.parse(bytes(0x43, 0x73, 0x64))!!.width)
        assertNull(FrameAdvert.parse(bytes(0x43, 0x93, 0x64))!!.rotation) // 100: not in the table
        assertEquals(
            Txt(R.string.advert_size_rotation, Txt(R.string.advert_size, 1024, 600), Txt(R.string.advert_rotation_unknown)),
            FrameAdvert.parse(bytes(0x43, 0x93, 0x64))!!.describe()[1],
        )
    }

    @Test
    fun missingB3_noFirmware() {
        val a = FrameAdvert.parse(bytes(0x43, 0x13, 0x64))!!
        assertNull(a.firmware)
        assertEquals(
            listOf(
                Txt(R.string.advert_colour_panel, "E6", "TA"),
                Txt(R.string.advert_size_rotation, Txt(R.string.advert_size, 1024, 600), Txt(R.string.advert_degrees, 0)),
                Txt(R.string.advert_battery, 100),
            ),
            a.describe(),
        )
    }

    @Test
    fun tooShort() {
        assertNull(FrameAdvert.parse(bytes(0x43, 0x13)))
        assertNull(FrameAdvert.parse(ByteArray(0)))
        assertNull(FrameAdvert.parse(null))
    }

    @Test
    fun safeModeAndBattery() {
        val a = FrameAdvert.parse(bytes(0x43, 0x13, 0x80 or 17, 0x80 or 5))!!
        assertTrue(a.safeMode)
        assertEquals(17, a.battery)
        assertEquals(5, a.firmware) // bit 7 of b3 is not part of the version
        assertEquals(Txt(R.string.advert_safe_mode), a.describe().last())
        assertEquals(Txt(R.string.advert_battery, 17), a.describe()[2])
    }

    @Test
    fun allColours() {
        val expected = listOf(ColourScheme.BW, ColourScheme.BWR, ColourScheme.BWY, ColourScheme.E4, ColourScheme.E6, ColourScheme.E7, ColourScheme.BP)
        expected.forEachIndexed { i, c -> assertEquals(c, FrameAdvert.parse(bytes((i shl 4) or 3, 0x13, 0x64))!!.colour) }
        assertEquals(Panel.A, FrameAdvert.parse(bytes(0x41, 0x13, 0x64))!!.panel)
    }

    @Test
    fun unknownCodes_decodeLikeTheVendorApp() {
        val a = FrameAdvert.parse(bytes(0xF0, 0x1F, 0x64))!!
        assertEquals(ColourScheme.BWR, a.colour) // other colour codes → BWR
        assertEquals(Panel.AB, a.panel) // other panel codes → AB
        assertEquals(0x1F, a.resolutionCode)
        assertNull(a.width)
        assertNull(a.height)
        assertEquals(Txt(R.string.advert_size_code, 0x1F), a.size)
        assertEquals(Txt(R.string.advert_size_rotation, Txt(R.string.advert_size_code, 0x1F), Txt(R.string.advert_degrees, 0)), a.describe()[1])
        assertEquals(Panel.AB, FrameAdvert.parse(bytes(0x4F, 0x13, 0x64))!!.panel)
    }

    @Test
    fun caps() {
        assertEquals(FrameCaps(1024, 600, ColourScheme.E6, 0), FrameAdvert.parse(bytes(0x43, 0x13, 0x64, 0x15))!!.caps)
        assertEquals(FrameCaps(null, null, ColourScheme.E6, 90), FrameAdvert.parse(bytes(0x43, 0x3F, 0x64))!!.caps)
    }

    @Test
    fun hexRoundTrip() {
        val a = FrameAdvert.parse(bytes(0x43, 0x13, 0x64, 0x15))!!
        assertEquals(a, FrameAdvert.parseHex(a.hex))
        assertEquals(a, FrameAdvert.parseHex("43136415"))
        assertNull(FrameAdvert.parseHex("431364x5"))
        assertNull(FrameAdvert.parseHex("4313641"))
        assertNull(FrameAdvert.parseHex("4313")) // too short
        assertNull(FrameAdvert.parseHex(null))
    }

    @Test
    fun keepsACopyOfTheBytes() {
        val data = bytes(0x43, 0x13, 0x64, 0x15)
        val a = FrameAdvert.parse(data)!!
        data[0] = 0
        assertEquals("43136415", a.hex)
    }
}
