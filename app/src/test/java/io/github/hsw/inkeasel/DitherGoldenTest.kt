package io.github.hsw.inkeasel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FrameFormat.dither] against the reference it is ported from (docs/protocol.md#dithering): the indices below
 * are what the OpenDisplay `epaper-dithering` library (6.1.1, MIT; BURKES, serpentine, no preprocessing) gives
 * for the same two pictures on the same inks, printed by `uv run tools/dither-golden.py`. Every index must match:
 * same doubles, same order of operations.
 *
 * The pattern formulas are copies of the generator's (`colour_pixel`, `grey_pixel`), integer-only, so both
 * sides draw identical pixels; change them together and paste the new output here.
 */
class DitherGoldenTest {
    private val w = 48
    private val h = 32

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Hue sweep along x (six segments of 256 steps), lightness ramp along y: dark, full colour, pale. */
    private fun colourPixel(x: Int, y: Int): Int {
        val hp = x * 1536 / w
        val seg = hp / 256
        val f = hp % 256
        val (r, g, b) = when (seg) {
            0 -> Triple(255, f, 0)
            1 -> Triple(255 - f, 255, 0)
            2 -> Triple(0, 255, f)
            3 -> Triple(0, 255 - f, 255)
            4 -> Triple(f, 0, 255)
            else -> Triple(255, 0, 255 - f)
        }
        fun light(c: Int) = if (y < 16) c * (y + 1) / 16 else c + (255 - c) * (y - 15) / 17
        return argb(light(r), light(g), light(b))
    }

    /** Diagonal grey ramp 0..255 along x with a ripple along y, clamped. */
    private fun greyPixel(x: Int, y: Int): Int {
        val v = ((x * 255 + 23) / 47 + (y * 23) % 41 - 20).coerceIn(0, 255)
        return argb(v, v, v)
    }

    private fun picture(pixel: (Int, Int) -> Int) = IntArray(w * h) { pixel(it % w, it / w) }

    private fun expected(rows: List<String>): ByteArray {
        assertEquals(h, rows.size)
        rows.forEach { assertEquals(w, it.length) }
        return rows.joinToString("").map { it.digitToInt(16).toByte() }.toByteArray()
    }

    private fun assertGolden(golden: List<String>, actual: ByteArray) {
        val want = expected(golden)
        val diff = want.indices.filter { want[it] != actual[it] }.map { "(${it % w},${it / w}) ${want[it]}→${actual[it]}" }
        assertTrue("${diff.size} indices differ from the reference: ${diff.take(20)}", diff.isEmpty())
        assertArrayEquals(want, actual)
    }

    /** Not every pixel is an ink, so the reference's "already all inks" shortcut did not apply. */
    private fun assertNotAllInks(px: IntArray, palette: Palette) {
        val inks = palette.inks.map { argb(it.r, it.g, it.b) }.toSet()
        assertTrue(px.any { it !in inks })
    }

    @Test
    fun e6_matchesTheReference() {
        val f = FrameFormat(w, h, FrameFormats.E6, Nibbles4())
        val px = picture(::colourPixel)
        assertNotAllInks(px, f.palette)
        assertTrue(!f.greys)
        assertGolden(COLOUR_E6, f.dither(px))
    }

    @Test
    fun fourGreys_matchesTheReference() {
        val f = FrameFormat(w, h, FrameFormats.OD_G4, RowPlanes(2))
        val px = picture(::greyPixel)
        assertNotAllInks(px, f.palette)
        // the app's auto levels run first on a grey panel; this picture already spans 0..255, so they are the
        // identity and the reference sees the same pixels
        assertTrue(f.greys)
        assertEquals(0 to 255, Levels.range(px))
        assertArrayEquals(px, Levels.stretched(px))
        assertGolden(GREY_G4, f.dither(px))
    }

    // uv run tools/dither-golden.py (epaper-dithering 6.1.1)
    private val COLOUR_E6 = listOf(
        "000000000000000000000000000000000000000000000000",
        "000300300505005005005005004000000000000003000300",
        "300050505005050500500500500400400404043040300030",
        "003303030550505050550550440040040040004004030303",
        "303055355055055055055050504004040404043433403030",
        "303303050555055505550555440440404040400404033033",
        "330353555555555555555505404044044404443433434303",
        "333335353555555555555555540444044044044344433333",
        "333353555555555555555554444044440444443433343333",
        "333333555555555555555555544444444440444444433333",
        "333352322255555555255555444444444444443343343333",
        "333333525252222222222221144444444444444433333333",
        "333222222522222252252251444444444444443333333333",
        "333333352222222222222221144444444444443333333333",
        "333222222222222222222111114444444444443333333333",
        "333333222222222222222221114444444444433333333333",
        "333222222222222222222111144444444444433333333333",
        "333332222222222222222221111444444444433333333333",
        "332222222222222222221111114444444444433333333333",
        "333233222222222222221221111444444444334334343443",
        "323222222222222222221111114444444444334343433433",
        "332322222222222222222121111144444443343444344443",
        "313222222222222222111211114444444443444344443434",
        "132131222222222222222111111114444344444444444413",
        "313212212221221211111211114144444444444444444431",
        "131121122112212122212111111411411444444444444413",
        "131312212211212112111111111144144141414414414141",
        "311121121112121221211211111411141414441414141131",
        "112111212121111111121111111141414111141141414111",
        "111311111111121212111111111111141114411414111311",
        "111112112112111111111111111141114111111111411111",
        "111111111111111111111111111111111111411411111111",
    )

    private val GREY_G4 = listOf(
        "000000001011011111111111121212222222222223233233",
        "000100110101111111111212212222222222232332333333",
        "000000000100101111111111121121222222222223223333",
        "000010111111111111111122222222222222233333333333",
        "000000000001101111112111111221222222222222323233",
        "001010111111111111121122222222222232333333333333",
        "000000000101111111111111121222222222222223233333",
        "001011111111111111212222222222222223233333333333",
        "000000000101111111111112121222222222222323233333",
        "000000010010001111111111111211222222223222322323",
        "000101011111111111122122222222222232323333333333",
        "000000000000101111111111112112222222222222322333",
        "001011111111111111112222222222222223233333333333",
        "000000000001011111111111111212222222222222232333",
        "001011111111111111212222222222222233333333333333",
        "000000000010111111111111112122222222222223233333",
        "010111111111111111221222222222222323333333333333",
        "000000001001111111111121121222222222222322323333",
        "000000000100111111111112112122222222222233232333",
        "000010111111111111111221222222222222323332333333",
        "000000000001011111111111111212222222222223232333",
        "001010111111111111121222222222222223233333333333",
        "000000000010111111111111121222222222222223233333",
        "000101111111111111121222222222222232333333333333",
        "000000001011111111111111212122222222222232323333",
        "000000000100000111111112111211222222222322232323",
        "001011110111111111122122222222222223233233333333",
        "000000000001011111111111111121222222222223223233",
        "000101111111111111112122222222222223233333333333",
        "000000000010011111111111111212222222222222232333",
        "001011111111111111221222222222222233233333333333",
        "000000000000111111111111112122222222222222323333",
    )
}
