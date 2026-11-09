package io.github.hsw.inkeasel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameFormatTest {
    private fun advert(vararg b: Int) = FrameAdvert.parse(ByteArray(b.size) { b[it].toByte() })

    private fun format(vararg b: Int) = FrameAdvert.lookup(advert(*b)).formatOrNull

    private fun reason(vararg b: Int) = FrameAdvert.lookup(advert(*b)).reasonOrNull

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun unhex(s: String) = FrameAdvert.unhex(s)!!

    // advertised b0 (colour type in the high nibble, panel TA) per colour scheme
    private val e6 = 0x43
    private val bwr = 0x13
    private val e4 = 0x33

    @Test
    fun realFrame_isTheDefaultFormat() {
        val f = format(0x43, 0x13, 0x64, 0x15)!!
        assertEquals(FrameFormats.DEFAULT, f)
        assertEquals(1024, f.width)
        assertEquals(600, f.height)
        assertEquals(307_200, f.payloadSize)
        assertEquals(FrameFormats.E6, f.palette)
        assertEquals(Nibbles4(), f.packing)
        assertEquals(0, f.marker)
        assertTrue(f.verified)
    }

    @Test
    fun e6_800x480() {
        val f = format(0x42, 0x08, 0x64)!!
        assertEquals(800, f.width)
        assertEquals(480, f.height)
        assertEquals(192_000, f.payloadSize)
        assertEquals(192_000, f.pack(ByteArray(800 * 480)).size)
    }

    @Test
    fun e6_smallPanels() {
        assertEquals(20_000, format(0x43, 0x01, 0x64)!!.payloadSize) // 200×200
        val f = format(0x43, 0x02, 0x64)!! // 122×250: portrait as in the table
        assertEquals(122, f.width)
        assertEquals(250, f.height)
        assertEquals(15_250, f.pack(ByteArray(122 * 250)).size)
    }

    @Test
    fun e6_unsplitSizesAreVerified_wideAndSplitOnesAreNot() {
        for (code in listOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x10, 0x13)) {
            val f = format(e6, code, 0x64)!!
            assertTrue("code $code", f.verified)
            assertEquals("code $code", Nibbles4(), f.packing)
        }
        for (code in listOf(0x11, 0x12)) { // 1440×600, 1360×480: unsplit for E6, but never seen
            val f = format(e6, code, 0x64)!!
            assertFalse("code $code", f.verified)
            assertEquals("code $code", Nibbles4(), f.packing)
        }
        val split = format(e6, 0x14, 0x64)!! // 1200×1600
        assertEquals(Nibbles4(split = true), split.packing)
        assertEquals(960_000, split.payloadSize)
        assertFalse(split.verified)
    }

    /** docs/protocol.md#payload-size, columns "BWR", "E4" and "E6, E7", by resolution code. */
    private val docSizes = mapOf(
        0x01 to Triple(10_240, 10_000, 20_000),
        0x02 to Triple(8_192, 7_625, 15_250),
        0x03 to Triple(10_240, 9_472, 18_944),
        0x04 to Triple(11_264, 11_248, 22_496),
        0x05 to Triple(18_432, 17_664, 35_328),
        0x06 to Triple(30_720, 30_000, 60_000),
        0x07 to Triple(77_824, 77_760, 155_520),
        0x08 to Triple(96_256, 96_000, 192_000),
        0x09 to Triple(153_600, 153_600, 307_200),
        0x10 to Triple(161_792, 161_280, 322_560),
        0x11 to Triple(217_088, 216_000, 432_000),
        0x12 to Triple(163_840, 163_200, 326_400),
        0x13 to Triple(153_600, 153_600, 307_200),
        0x14 to Triple(480_256, 480_000, 960_000),
    )

    @Test
    fun payloadSizes_matchTheDocTable_forEveryEnabledFormat() {
        assertEquals(FrameAdvert.SIZES.keys, docSizes.keys)
        for ((code, sizes) in docSizes) {
            val (bwrSize, e4Size, e6Size) = sizes
            for ((b0, want) in listOf(bwr to bwrSize, e4 to e4Size, e6 to e6Size)) {
                val f = format(b0, code, 0x64) ?: error("b0 $b0 code $code unsupported")
                assertEquals("b0 $b0 code $code", want, f.payloadSize)
                assertEquals("b0 $b0 code $code", want, f.pack(ByteArray(f.width * f.height)).size)
                assertEquals(0, f.marker)
            }
        }
    }

    @Test
    fun bwr_wideSizesAreSplit_restAreNot() {
        assertEquals(163_840, format(bwr, 0x12, 0x64)!!.payloadSize) // 1360×480: 4 × P(40 800)
        assertEquals(217_088, format(bwr, 0x11, 0x64)!!.payloadSize) // 1440×600: 4 × P(54 000)
        assertEquals(BitPlanes(2, split = true), format(bwr, 0x12, 0x64)!!.packing)
        assertEquals(BitPlanes(2, split = true), format(bwr, 0x11, 0x64)!!.packing)
        assertEquals(BitPlanes(2), format(bwr, 0x14, 0x64)!!.packing) // 1200×1600 is not a BWR split
        assertEquals(FrameFormats.BWR, format(bwr, 0x13, 0x64)!!.palette)
        assertFalse(format(bwr, 0x13, 0x64)!!.verified)
        assertEquals(Crumbs2, format(e4, 0x13, 0x64)!!.packing)
        assertFalse(format(e4, 0x13, 0x64)!!.verified)
    }

    @Test
    fun unsupportedTypes_giveTheirReasons() {
        val untested = Txt(R.string.reason_untested_format)
        assertEquals(untested, reason(0x03, 0x13, 0x64)) // BW
        assertEquals(untested, reason(0x63, 0x13, 0x64)) // BP
        assertEquals(untested, reason(0x53, 0x13, 0x64)) // E7
        assertEquals(untested, reason(0x53, 0x14, 0x64)) // E7 split
        assertEquals(Txt(R.string.reason_vendor_cannot), reason(0x23, 0x13, 0x64)) // BWY
        assertEquals(Txt(R.string.reason_vendor_cannot), reason(0x23, 0x11, 0x64))
        for (b0 in listOf(0x03, 0x23, 0x53, 0x63)) for (code in FrameAdvert.SIZES.keys) {
            assertTrue("b0 $b0 code $code", FrameAdvert.lookup(advert(b0, code, 0x64)) is FormatLookup.Unsupported)
        }
    }

    @Test
    fun otherReasons() {
        assertEquals(Txt(R.string.reason_no_caps), FrameAdvert.lookup(null).reasonOrNull)
        assertEquals(Txt(R.string.reason_panel_a), reason(0x41, 0x13, 0x64))
        assertEquals(Txt(R.string.reason_rotation, Txt(R.string.advert_degrees, 90)), reason(0x43, 0x33, 0x64))
        assertEquals(Txt(R.string.reason_unknown_size_code, Txt(R.string.advert_size_code, 0x1F)), reason(0x43, 0x1F, 0x64))
        assertNull(FrameAdvert.lookup(advert(0x43, 0x13, 0x64)).reasonOrNull)
    }

    @Test
    fun findByCaps_protocolNeutral() {
        val f = FrameFormats.find(FrameCaps(800, 480, ColourScheme.E6, 0)).formatOrNull!!
        assertEquals(192_000, f.payloadSize)
        assertEquals(FrameFormats.DEFAULT, FrameFormats.find(FrameCaps(1024, 600, ColourScheme.E6, 0)).formatOrNull)
        assertEquals(Txt(R.string.reason_no_caps), FrameFormats.find(null).reasonOrNull)
        assertEquals(Txt(R.string.reason_unknown_size), FrameFormats.find(FrameCaps(null, null, ColourScheme.E6, 0)).reasonOrNull)
        assertEquals(Txt(R.string.reason_colours_size, "E6", 640, 384), FrameFormats.find(FrameCaps(640, 384, ColourScheme.E6, 0)).reasonOrNull)
        assertEquals(
            Txt(R.string.reason_vendor_cannot),
            FrameFormats.find(FrameCaps(1024, 600, ColourScheme.BWY, 0)).reasonOrNull,
        )
        assertEquals(Txt(R.string.reason_rotation, Txt(R.string.advert_degrees, 180)), FrameFormats.find(FrameCaps(1024, 600, ColourScheme.BWR, 180)).reasonOrNull)
        assertEquals(Txt(R.string.reason_rotation, Txt(R.string.advert_degrees_unknown)), FrameFormats.find(FrameCaps(1024, 600, ColourScheme.BWR, null)).reasonOrNull)
    }

    @Test
    fun nibbles4_e6Codes_highNibbleLeft() {
        // ink indices 0..5 → device codes 0, 1, 2, 3, 5, 6
        val f = FrameFormat(6, 2, FrameFormats.E6, Nibbles4())
        val packed = f.pack(byteArrayOf(0, 1, 2, 3, 4, 5, 5, 4, 3, 2, 1, 0))
        assertArrayEquals(bytes(0x01, 0x23, 0x56, 0x65, 0x32, 0x10), packed)
    }

    @Test
    fun nibbles4_split_leftHalvesThenRightHalves() {
        // codes: row 0 = 0 1 | 2 3, row 1 = 5 6 | 0 1
        val idx = byteArrayOf(0, 1, 2, 3, 4, 5, 0, 1)
        assertArrayEquals(bytes(0x01, 0x23, 0x56, 0x01), FrameFormat(4, 2, FrameFormats.E6, Nibbles4()).pack(idx))
        val split = FrameFormat(4, 2, FrameFormats.E6, Nibbles4(split = true))
        assertEquals(4, split.payloadSize)
        assertArrayEquals(bytes(0x01, 0x56, 0x23, 0x01), split.pack(idx))
        // two bytes per half row
        val wide = FrameFormat(8, 1, FrameFormats.E6, Nibbles4(split = true))
        assertArrayEquals(bytes(0x01, 0x23, 0x56, 0x10), wide.pack(byteArrayOf(0, 1, 2, 3, 4, 5, 1, 0)))
    }

    @Test
    fun crumbs2_e4Codes_firstPixelInTopBits() {
        // E4 ink indices 0 white, 1 black, 2 red, 3 yellow → codes 1, 0, 3, 2
        val f = FrameFormat(8, 1, FrameFormats.E4, Crumbs2)
        assertEquals(2, f.payloadSize)
        assertArrayEquals(bytes(0x1B, 0xF4), f.pack(byteArrayOf(1, 0, 3, 2, 2, 2, 0, 1)))
        // one stream over the whole image: at width 2 two rows share a byte
        assertArrayEquals(bytes(0x1B), FrameFormat(2, 2, FrameFormats.E4, Crumbs2).pack(byteArrayOf(1, 0, 3, 2)))
    }

    @Test
    fun bitPlanes_bwr_blackPlaneThenRedPlane_msbFirst_eachPaddedTo512() {
        // BWR ink indices 0 white, 1 black, 2 red
        val idx = ByteArray(32)
        idx[0] = 1; idx[1] = 2; idx[7] = 1; idx[15] = 2 // row 0
        idx[16 + 3] = 1; idx[16 + 8] = 2; idx[16 + 9] = 1 // row 1
        val f = FrameFormat(16, 2, FrameFormats.BWR, BitPlanes(2))
        assertEquals(1024, f.payloadSize)
        val out = f.pack(idx)
        assertEquals(1024, out.size)
        assertArrayEquals(bytes(0x81, 0x00, 0x10, 0x40), out.copyOfRange(0, 4))
        assertArrayEquals(ByteArray(508), out.copyOfRange(4, 512))
        assertArrayEquals(bytes(0x40, 0x01, 0x00, 0x80), out.copyOfRange(512, 516))
        assertArrayEquals(ByteArray(508), out.copyOfRange(516, 1024))
    }

    @Test
    fun bitPlanes_rowsAreNotByteAligned() {
        // 3×3: the 9 bits of a plane run on across rows; row 1 starts at bit 3
        val idx = byteArrayOf(1, 0, 0, 0, 2, 0, 0, 0, 1)
        val out = FrameFormat(3, 3, FrameFormats.BWR, BitPlanes(2)).pack(idx)
        assertEquals(1024, out.size)
        assertArrayEquals(bytes(0x80, 0x80), out.copyOfRange(0, 2))
        assertArrayEquals(bytes(0x08, 0x00), out.copyOfRange(512, 514))
    }

    @Test
    fun bitPlanes_padding() {
        // P(n): no extra block when a plane is already a multiple of 512
        assertEquals(512, BitPlanes(1).size(64, 64)) // 512 B
        assertEquals(1024, BitPlanes(1).size(65, 64)) // 520 B
        assertEquals(512, BitPlanes(1).size(1, 1))
        assertEquals(153_600, BitPlanes(2).size(1024, 600)) // 76 800 = 150 × 512
        // split parts are padded by 512 − (len mod 512), as the vendor code does: a full block at an exact multiple
        assertEquals(2 * 512, BitPlanes(1, split = true).size(32, 2)) // part 4 B
        assertEquals(2 * 1024, BitPlanes(1, split = true).size(32, 256)) // part 512 B
    }

    @Test
    fun bitPlanes_split_leftBlackLeftRedRightBlackRightRed() {
        val idx = ByteArray(64)
        idx[0] = 1; idx[16] = 2; idx[31] = 1 // row 0
        idx[32 + 15] = 2; idx[32 + 17] = 1 // row 1
        val f = FrameFormat(32, 2, FrameFormats.BWR, BitPlanes(2, split = true))
        assertEquals(2048, f.payloadSize)
        val out = f.pack(idx)
        assertEquals(2048, out.size)
        val parts = (0 until 4).map { out.copyOfRange(it * 512, it * 512 + 4) }
        assertArrayEquals(bytes(0x80, 0x00, 0x00, 0x00), parts[0]) // left black
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0x01), parts[1]) // left red
        assertArrayEquals(bytes(0x00, 0x01, 0x40, 0x00), parts[2]) // right black
        assertArrayEquals(bytes(0x80, 0x00, 0x00, 0x00), parts[3]) // right red
        for (k in 0 until 4) assertArrayEquals(ByteArray(508), out.copyOfRange(k * 512 + 4, k * 512 + 512))
    }

    @Test
    fun layoutsThatDoNotFit_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { FrameFormat(6, 2, FrameFormats.E6, Nibbles4(split = true)) }
        assertThrows(IllegalArgumentException::class.java) { FrameFormat(3, 1, FrameFormats.E4, Crumbs2) }
        assertThrows(IllegalArgumentException::class.java) { FrameFormat(24, 2, FrameFormats.BWR, BitPlanes(2, split = true)) }
    }

    @Test
    fun dither_solidInksStayExact() {
        val f = FrameFormat(4, 2, FrameFormats.E6, Nibbles4())
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val idx = f.dither(intArrayOf(white, white, black, black, black, white, white, black))
        assertArrayEquals(byteArrayOf(1, 1, 0, 0, 0, 1, 1, 0), idx)
        assertEquals(white, f.previewPixels(idx)[0])
    }

    @Test
    fun dither_usesThePanelsOwnPalette() {
        val red = 0xFFFF0000.toInt()
        val yellow = 0xFFFFFF00.toInt()
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val px = intArrayOf(red, yellow, white, black)
        // pure red and yellow are inks of E4 (indices 2, 3), red of BWR (index 2)
        val e4f = FrameFormat(4, 1, FrameFormats.E4, Crumbs2)
        assertArrayEquals(byteArrayOf(2, 3, 0, 1), e4f.dither(px))
        assertArrayEquals(bytes(0xE4), e4f.pack(e4f.dither(px))) // codes 3 2 1 0
        val bwrf = FrameFormat(16, 1, FrameFormats.BWR, BitPlanes(2))
        assertEquals(2, bwrf.dither(IntArray(16) { red })[0].toInt())
    }

    @Test
    fun fit_portraitOnLandscape_barsAtTheSides() {
        val f = FrameFormats.DEFAULT // 1024×600
        // the Mona Lisa's shape (about 2:3): the whole height, a third of the width
        assertEquals(Placement(312, 0, 400, 600), f.fit(600, 900))
        assertEquals(Placement(312, 0, 400, 600), f.fit(6000, 9000)) // scaled down the same
        assertEquals(Placement(312, 0, 400, 600), f.fit(60, 90)) // and up
    }

    @Test
    fun fit_widerThanThePanel_barsAtTopAndBottom() {
        val f = FrameFormats.DEFAULT
        assertEquals(Placement(0, 95, 1024, 410), f.fit(2000, 800)) // 600 × 2000/800 = 409.6, rounded
        assertEquals(Placement(0, 12, 1024, 576), f.fit(1920, 1080)) // 16:9 on 1024×600
    }

    @Test
    fun fit_sameShape_noBars() {
        val f = FrameFormats.DEFAULT
        assertEquals(Placement(0, 0, 1024, 600), f.fit(1024, 600))
        assertEquals(Placement(0, 0, 1024, 600), f.fit(2048, 1200))
        assertEquals(Placement(0, 0, 1024, 600), f.fit(512, 300))
        assertEquals(Placement(0, 0, 1024, 600), f.fit(1920, 1125))
    }

    @Test
    fun fit_extremeShapes_keepOnePixel() {
        val f = FrameFormats.DEFAULT
        assertEquals(Placement(0, 299, 1024, 1), f.fit(100_000, 1))
        assertEquals(Placement(511, 0, 1, 600), f.fit(1, 100_000))
    }

    @Test
    fun centred_placesAsIs() {
        val f = FrameFormats.DEFAULT
        assertEquals(Placement(312, 0, 400, 600), f.centred(400, 600))
        assertEquals(Placement(0, 0, 1024, 600), f.centred(1024, 600))
        assertThrows(IllegalArgumentException::class.java) { f.centred(1025, 600) }
    }

    @Test
    fun sampleSize_keepsThePictureAtLeastItsPlace() {
        val f = FrameFormats.DEFAULT
        assertEquals(1, f.sampleSize(1024, 600))
        assertEquals(1, f.sampleSize(2047, 1199))
        assertEquals(2, f.sampleSize(2048, 1200))
        // a portrait original needs only 600 px of height: 4800 / 8 = 600
        assertEquals(8, f.sampleSize(3200, 4800))
        assertEquals(4, f.sampleSize(3200, 4799))
        assertEquals(1, f.sampleSize(300, 200)) // smaller than the panel: decoded whole
    }

    @Test
    fun paper_isTheWhiteInk() {
        assertEquals(1, FrameFormats.DEFAULT.paper) // E6: black, white, …
        assertEquals(0, FrameFormat(4, 1, FrameFormats.E4, Crumbs2).paper)
        assertEquals(0, FrameFormat(16, 1, FrameFormats.BWR, BitPlanes(2)).paper)
        assertEquals(1, FrameFormat(16, 1, FrameFormats.BP, BitPlanes(1)).paper) // no white: pink is the lightest
    }

    @Test
    fun dither_placed_barsArePlainPaper() {
        val f = FrameFormat(8, 4, FrameFormats.E6, Nibbles4())
        val at = f.fit(2, 4) // a portrait picture in the middle: columns 3 and 4
        assertEquals(Placement(3, 0, 2, 4), at)
        // a mid grey that dithers to a mix of inks and spreads its error to the right and below
        val grey = 0xFF707070.toInt()
        val idx = f.dither(IntArray(at.width * at.height) { grey }, at)
        assertEquals(f.width * f.height, idx.size)
        for (y in 0 until f.height) for (x in 0 until f.width) {
            if (x !in 3..4) assertEquals("($x, $y)", f.paper, idx[y * f.width + x].toInt())
        }
        // the picture's pixels are what the same picture dithered alone gives
        val alone = FrameFormat(2, 4, FrameFormats.E6, Nibbles4()).dither(IntArray(8) { grey })
        for (y in 0 until 4) for (x in 0 until 2) assertEquals(alone[y * 2 + x], idx[y * f.width + 3 + x])
        assertEquals(0xFFFFFFFF.toInt(), f.previewPixels(idx)[0]) // the bars preview as white
    }

    @Test
    fun dither_placed_wholePanel_isThePlainDither() {
        val f = FrameFormat(4, 2, FrameFormats.E6, Nibbles4())
        val px = IntArray(8) { (0xFF shl 24) or (it * 30 shl 16) or (it * 20 shl 8) or (255 - it * 25) }
        assertArrayEquals(f.dither(px), f.dither(px, f.fit(4, 2)))
        assertThrows(IllegalArgumentException::class.java) { f.dither(IntArray(4), Placement(3, 0, 2, 2)) }
    }

    @Test
    fun preview_worksForEveryFormat() {
        for (b0 in listOf(e6, bwr, e4)) {
            val f = format(b0, 0x01, 0x64)!! // 200×200
            val px = IntArray(f.width * f.height) { i -> (0xFF shl 24) or ((i * 7) and 0xFF shl 16) or ((i * 3) and 0xFF shl 8) or (i and 0xFF) }
            val idx = f.dither(px)
            val preview = f.previewPixels(idx)
            assertEquals(px.size, preview.size)
            val inks = f.palette.inks.map { (0xFF shl 24) or (it.r shl 16) or (it.g shl 8) or it.b }.toSet()
            assertTrue(preview.all { it in inks })
            assertEquals(f.payloadSize, f.pack(idx).size)
        }
    }

    /**
     * E6 output must not change: a fixed 24×8 image dithered and packed, bytes taken from the implementation
     * before the formats were generalised.
     */
    @Test
    fun e6_regression_bytesUnchanged() {
        val w = 24
        val h = 8
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = (x * 11 + y * 3) and 0xFF
            val g = (y * 37 + x * 2) and 0xFF
            val b = (x * y * 13 + 40) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val f = FrameFormat(w, h, FrameFormats.E6, Nibbles4())
        val before = unhex(
            "000003030333333333333333060505555555555553332336050555555333353551533225665555066555532313132235" +
                "665556655562516231522215665566556251625122112255665665565565162163353350055055553653353353353550",
        )
        assertArrayEquals(before, f.pack(f.dither(px)))
    }

    @Test
    fun startFrame_carriesTheMarker() {
        assertArrayEquals(
            bytes(0xAE, 0x0A, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0xCA),
            FrameClient.frame(0x01, FrameClient.startPayload(FrameFormats.DEFAULT.marker)),
        )
        assertArrayEquals(
            bytes(0xAE, 0x0A, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0xCA),
            FrameClient.frame(0x01, FrameClient.startPayload(1)),
        )
    }
}
