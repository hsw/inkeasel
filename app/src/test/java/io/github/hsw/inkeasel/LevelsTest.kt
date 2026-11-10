package io.github.hsw.inkeasel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelsTest {
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun value(p: Int) = p and 0xFF

    @Test
    fun luma_bt601() {
        assertEquals(0, Levels.luma(grey(0)))
        assertEquals(255, Levels.luma(grey(255)))
        assertEquals(76, Levels.luma(0xFFFF0000.toInt()))
        assertEquals(150, Levels.luma(0xFF00FF00.toInt()))
        assertEquals(29, Levels.luma(0xFF0000FF.toInt()))
    }

    @Test
    fun darkPicture_isStretchedToTheWholeScale() {
        val px = IntArray(1000) { grey(20 + it % 61) } // 20..80
        val out = Levels.stretched(px)
        assertEquals(20 to 80, Levels.range(px))
        assertEquals(0, value(out[0]))
        assertEquals(255, value(out[60]))
        assertEquals(128, value(out[30])) // 50 lies halfway
    }

    @Test
    fun aFewHighlights_areClipped_theDarkEndIsNot() {
        // 996 pixels at 10..60, 4 stray highlights (0.4 %, under the 0.5 % cut) at 250
        val px = IntArray(1000) { if (it < 4) grey(250) else grey(10 + it % 51) }
        assertEquals(10 to 60, Levels.range(px))
        val out = Levels.stretched(px)
        assertEquals(255, value(out[0])) // a clipped highlight
        assertEquals(0, value(out.minOf { value(it) }))
    }

    @Test
    fun lightCut_isInclusive() {
        // 1000 pixels: the cut is 5. Exactly 5 highlights are clipped, 6 hold the range.
        fun withHighlights(n: Int) = IntArray(1000) { if (it < n) grey(250) else grey(10 + it % 51) }
        assertEquals(10 to 60, Levels.range(withHighlights(5)))
        assertEquals(10 to 250, Levels.range(withHighlights(6)))
    }

    @Test
    fun flatPicturePlusAFewHighlights_isLeftAlone() {
        // the highlights fall under the cut, and what is left has one luminance: nothing to stretch, and nothing is
        // clipped either
        val px = IntArray(1000) { if (it < 4) grey(250) else grey(100) }
        assertNull(Levels.range(px))
        assertArrayEquals(px, Levels.stretched(px))
    }

    @Test
    fun onePixelDarker_keepsTheDarkEnd() {
        val px = IntArray(1000) { if (it == 0) grey(5) else grey(100 + it % 100) }
        assertEquals(5, Levels.range(px)!!.first)
    }

    @Test
    fun flatPicture_isLeftAlone() {
        assertNull(Levels.range(IntArray(10) { grey(90) }))
        assertArrayEquals(IntArray(10) { grey(90) }, Levels.stretched(IntArray(10) { grey(90) }))
    }

    @Test
    fun colour_becomesGrey() {
        val out = Levels.stretched(intArrayOf(0xFF0000FF.toInt(), 0xFFFF0000.toInt()))
        assertTrue(out.all { (it shr 16 and 0xFF) == (it and 0xFF) && (it shr 8 and 0xFF) == (it and 0xFF) })
    }

    @Test
    fun onlyGreyPanels_getLevels() {
        assertTrue(FrameFormat(8, 2, FrameFormats.OD_G4, RowPlanes(2)).greys)
        assertTrue(FrameFormat(8, 2, FrameFormats.OD_BW, RowPlanes(1)).greys)
        assertFalse(FrameFormats.DEFAULT.greys)
        // a dark grey picture on a four-grey panel reaches black and white
        val f = FrameFormat(8, 1, FrameFormats.OD_G4, RowPlanes(2))
        val idx = f.dither(IntArray(8) { grey(30 + it * 5) })
        assertEquals(0, idx.first().toInt()) // black
        assertEquals(3, idx.last().toInt()) // white
    }

    @Test
    fun placedPicture_levelsComeFromThePictureAlone_barsStayPlain() {
        // four greys 30…60 stretch to 0, 85, 170, 255: exactly the inks, so no error is spread; had the white bars
        // been measured too, the range would reach 255 and the picture would stay dark
        val f = FrameFormat(8, 1, FrameFormats.OD_G4, RowPlanes(2))
        val at = Placement(2, 0, 4, 1)
        val idx = f.dither(intArrayOf(grey(30), grey(40), grey(50), grey(60)), at)
        assertEquals(3, f.paper) // white
        assertArrayEquals(byteArrayOf(3, 3, 0, 1, 2, 3, 3, 3), idx)
    }
}
