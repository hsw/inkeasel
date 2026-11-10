package io.github.hsw.inkeasel

/**
 * Auto levels for a panel of greys only ([FrameFormat.greys]): the picture as luminance, its range stretched
 * to 0..255 so a dark painting uses the panel's whole scale. The lightest [LIGHT_CUT] of the pixels is
 * clipped to white (stray highlights would otherwise hold the range); the dark end is not clipped, since
 * clipping it merges the darkest areas into one black blot. Free of Android types.
 */
object Levels {
    /** Share of the pixels clipped at the light end. */
    const val LIGHT_CUT = 0.005

    /** Luminance 0..255 of an ARGB pixel (ITU-R BT.601 weights). */
    fun luma(p: Int): Int =
        (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF) + 500) / 1000

    /**
     * The darkest luminance in [px] and the one above which no more than [LIGHT_CUT] of it lies; `null` for a
     * picture of one luminance (nothing to stretch).
     */
    fun range(px: IntArray): Pair<Int, Int>? {
        if (px.isEmpty()) return null
        val hist = IntArray(256)
        for (p in px) hist[luma(p)]++
        val lo = hist.indexOfFirst { it > 0 }
        val cut = (px.size * LIGHT_CUT).toInt()
        var above = 0
        var hi = 255
        while (hi > lo) {
            above += hist[hi]
            if (above > cut) break
            hi--
        }
        return if (hi > lo) lo to hi else null
    }

    /** [px] as opaque greys (ARGB) of its luminance, stretched from [range] to 0..255. */
    fun stretched(px: IntArray): IntArray {
        val r = range(px)
        val map = IntArray(256) { l ->
            if (r == null) l else ((l - r.first) * 255 + (r.second - r.first) / 2) / (r.second - r.first)
        }
        return IntArray(px.size) { i ->
            val v = map[luma(px[i])].coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }
}
