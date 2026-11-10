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
}
