package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OklabTest {
    private fun assertLab(l: Double, a: Double, b: Double, lab: OkLabColor, eps: Double) {
        assertEquals(l, lab.l, eps)
        assertEquals(a, lab.a, eps)
        assertEquals(b, lab.b, eps)
    }

    @Test
    fun white_isLightnessOne_andBlackIsZero() {
        assertLab(1.0, 0.0, 0.0, Oklab.fromSrgb(255, 255, 255), 1e-6)
        assertLab(0.0, 0.0, 0.0, Oklab.fromSrgb(0, 0, 0), 1e-12)
    }

    @Test
    fun primaries_matchOttossonsReference() {
        // sRGB #FF0000, #00FF00 and #0000FF in OKLab, as published with the colour space: each checks one column
        // of the first matrix
        assertLab(0.627955, 0.224863, 0.125846, Oklab.fromSrgb(255, 0, 0), 1e-5)
        assertLab(0.866440, -0.233888, 0.179498, Oklab.fromSrgb(0, 255, 0), 1e-5)
        assertLab(0.452014, -0.032457, -0.311528, Oklab.fromSrgb(0, 0, 255), 1e-5)
    }

    @Test
    fun linear_endpoints() {
        assertEquals(0.0, Oklab.linear(0), 0.0)
        assertEquals(1.0, Oklab.linear(255), 0.0)
    }

    @Test
    fun linear_matchesPublishedValues() {
        // sRGB 128 is 21.586 % linear light; 10/255 = 0.0392 lies below the knee (straight segment), 11/255 = 0.0431
        // above it (power curve)
        assertEquals(0.2158605, Oklab.linear(128), 1e-7)
        assertEquals(0.0030353, Oklab.linear(10), 1e-7)
        assertEquals(0.0033465, Oklab.linear(11), 1e-7)
        for (v in 1..255) assertTrue("rises at $v", Oklab.linear(v) > Oklab.linear(v - 1))
    }

    @Test
    fun matcher_exactInksMapToThemselves() {
        for (palette in listOf(FrameFormats.E6, FrameFormats.OD_G4, FrameFormats.E4, FrameFormats.E7)) {
            val m = InkMatcher(palette)
            for ((i, c) in palette.inks.withIndex()) assertEquals("${palette.name} $i", i, m.nearest(c.r, c.g, c.b))
        }
    }

    @Test
    fun matcher_vividPurpleOnE6_goesToAColourInk_whereRgbDistancePicksBlack() {
        // the "achromatic attractor": by squared RGB distance this dark purple is nearest to black (13312
        // against red's 14336); in OKLab with the chroma weighted it is a colour
        val e6 = FrameFormats.E6
        val rgbNearest = e6.inks.indices.minBy { i ->
            val c = e6.inks[i]
            (64 - c.r) * (64 - c.r) + c.g * c.g + (96 - c.b) * (96 - c.b)
        }
        assertEquals(0, rgbNearest)
        val idx = InkMatcher(e6).nearest(64, 0, 96)
        assertNotEquals("not black", 0, idx)
        assertNotEquals("not white", 1, idx)
        assertEquals(3, idx) // red
    }

    @Test
    fun matcher_greysOnFourGreys() {
        val m = InkMatcher(FrameFormats.OD_G4) // black, dark grey 85, light grey 170, white
        assertEquals(0, m.nearest(20, 20, 20))
        assertEquals(1, m.nearest(100, 100, 100))
        // 128 is above the perceptual midpoint of 85 and 170
        assertEquals(2, m.nearest(128, 128, 128))
        assertEquals(3, m.nearest(240, 240, 240))
    }

    @Test
    fun matcher_firstInkWinsATie() {
        val twice = Palette("twice", listOf(Ink(0, 0, 0, 0), Ink(255, 255, 255, 1), Ink(255, 255, 255, 2)))
        assertEquals(1, InkMatcher(twice).nearest(250, 250, 250))
    }

    @Test
    fun matcher_rgbOnlyWithOneOrTwoColourInks() {
        // greys only, and three colour inks or more: the reference's OKLab
        for (p in listOf(FrameFormats.OD_G4, FrameFormats.BW, FrameFormats.OD_BW, FrameFormats.E6, FrameFormats.E7)) {
            assertFalse(p.name, InkMatcher(p).rgb)
        }
        // one or two colour inks: RGB distance, so greys stay black and white
        for (p in listOf(FrameFormats.BWR, FrameFormats.OD_BWR, FrameFormats.BWY, FrameFormats.OD_BWY, FrameFormats.E4, FrameFormats.BP)) {
            assertTrue(p.name, InkMatcher(p).rgb)
        }
    }

    @Test
    fun matcher_midGreyOnBwr_isNeverRed() {
        // in OKLab with the chroma weighted, red (L 0.63) is the nearest BWR ink to the greys of about 80..135; by
        // RGB distance it never is
        for (palette in listOf(FrameFormats.BWR, FrameFormats.E4)) {
            val m = InkMatcher(palette)
            for (v in 0..255) assertTrue("${palette.name} $v", palette.inks[m.nearest(v, v, v)].let { it.r == it.g })
        }
        assertEquals(2, InkMatcher(FrameFormats.BWR).nearest(200, 30, 30)) // a red is still red
    }
}
