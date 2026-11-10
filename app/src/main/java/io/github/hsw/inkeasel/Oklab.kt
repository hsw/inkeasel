package io.github.hsw.inkeasel

import kotlin.math.cbrt
import kotlin.math.pow

/** A colour in OKLab: lightness [l] (0 black … 1 white) and the opponent axes [a] (green–red), [b] (blue–yellow). */
data class OkLabColor(val l: Double, val a: Double, val b: Double)

/**
 * The colour math of the dithering (docs/protocol.md#dithering), ported from the OpenDisplay `epaper-dithering`
 * library (MIT) with its constants and order of operations, so the same input gives the same doubles: sRGB
 * channels to linear light (IEC 61966-2-1), linear sRGB to OKLab (Björn Ottosson's matrices). Free of Android
 * types.
 */
object Oklab {
    /**
     * Weight of the chromatic axes against lightness in [InkMatcher]: above 1 a vivid colour goes to a colour
     * ink instead of the black or grey of a similar lightness.
     */
    const val WAB = 1.5

    /** Linear light 0..1 of an sRGB fraction 0..1 (IEC 61966-2-1, the knee at 0.04045). */
    fun linear(fraction: Double): Double =
        if (fraction <= 0.04045) fraction / 12.92 else ((fraction + 0.055) / 1.055).pow(2.4)

    /** Linear light of each sRGB channel value 0..255. */
    private val LUT = DoubleArray(256) { linear(it / 255.0) }

    /** Linear light 0..1 of the sRGB channel value [v] (0..255). */
    fun linear(v: Int): Double = LUT[v]

    /** OKLab of an sRGB colour with channels 0..255. */
    fun fromSrgb(r: Int, g: Int, b: Int): OkLabColor = oklab(LUT[r], LUT[g], LUT[b]) { l, a, bb -> OkLabColor(l, a, bb) }

    /**
     * Linear sRGB → LMS (Ottosson's combined matrix) → cube root → OKLab (M2), handed to [out] without an
     * object, for the per-pixel path.
     */
    internal inline fun <T> oklab(r: Double, g: Double, b: Double, out: (Double, Double, Double) -> T): T {
        val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
        val l1 = cbrt(l)
        val m1 = cbrt(m)
        val s1 = cbrt(s)
        return out(
            0.2104542553 * l1 + 0.7936177850 * m1 + -0.0040720468 * s1,
            1.9779984951 * l1 + -2.4285922050 * m1 + 0.4505937099 * s1,
            0.0259040371 * l1 + 0.7827717662 * m1 + -0.8086757660 * s1,
        )
    }
}

/**
 * The nearest ink of [palette] to a colour; the first ink wins a tie. The inks are converted once, here;
 * [nearest] allocates nothing. Free of Android types.
 *
 * On a palette of greys only, or with three colour inks or more (E6, E7), in OKLab with the chromatic axes
 * weighted by [Oklab.WAB]: `dL² + WAB²·(da² + db²)`, as the reference library does. On a palette with one or two
 * colour inks (BWR, BWY, E4, BP: [rgb]) by squared RGB distance instead: no mix of so few colour inks is neutral,
 * so in OKLab a grey near a colour ink's lightness (red: L 0.63) takes that ink now and then and nothing cancels
 * it, and greys get red specks (docs/protocol.md#dithering, docs/decisions.md).
 */
class InkMatcher(palette: Palette) {
    /** Matched by squared RGB distance, not in OKLab: the palette has one or two colour inks. */
    val rgb: Boolean = palette.inks.count { it.r != it.g || it.g != it.b } in 1..2

    private val inkR = IntArray(palette.inks.size) { palette.inks[it].r }
    private val inkG = IntArray(palette.inks.size) { palette.inks[it].g }
    private val inkB = IntArray(palette.inks.size) { palette.inks[it].b }
    private val inkL: DoubleArray
    private val inkA: DoubleArray
    private val inkBb: DoubleArray

    init {
        val labs = palette.inks.map { Oklab.fromSrgb(it.r, it.g, it.b) }
        inkL = DoubleArray(labs.size) { labs[it].l }
        inkA = DoubleArray(labs.size) { labs[it].a }
        inkBb = DoubleArray(labs.size) { labs[it].b }
    }

    /** The index of the ink nearest to the sRGB colour [r], [g], [b] (channels 0..255). */
    fun nearest(r: Int, g: Int, b: Int): Int =
        if (rgb) nearestRgb(r, g, b) else Oklab.oklab(Oklab.linear(r), Oklab.linear(g), Oklab.linear(b)) { l, a, bb -> nearestLab(l, a, bb) }

    private fun nearestRgb(r: Int, g: Int, b: Int): Int {
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in inkR.indices) {
            val dr = r - inkR[i]
            val dg = g - inkG[i]
            val db = b - inkB[i]
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun nearestLab(l: Double, a: Double, b: Double): Int {
        val wabSq = Oklab.WAB * Oklab.WAB
        var best = 0
        var bestD = Double.POSITIVE_INFINITY
        for (i in inkL.indices) {
            val dl = l - inkL[i]
            val da = a - inkA[i]
            val db = b - inkBb[i]
            val d = dl * dl + wabSq * (da * da + db * db)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
