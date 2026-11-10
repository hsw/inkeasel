package io.github.hsw.inkeasel

import kotlin.math.roundToInt

/**
 * One ink of a panel: the RGB dithering aims at and the device's code for it. What the code means is up to
 * the [Packing]: a 4/2-bit colour code ([Nibbles4], [Crumbs2]) or a set of plane bits ([BitPlanes]).
 */
data class Ink(val r: Int, val g: Int, val b: Int, val code: Int)

data class Palette(val name: String, val inks: List<Ink>)

/**
 * How palette indices (one per pixel, row-major from the top-left) become the whole payload the frame
 * expects (before any LZO, docs/protocol.md#packing): bit depth, planes, padding and split layout.
 */
interface Packing {
    /** Whether a [w]×[h] image can be laid out this way at all. */
    fun fits(w: Int, h: Int): Boolean = true

    /** Payload bytes for a [w]×[h] image (docs/protocol.md#payload-size). */
    fun size(w: Int, h: Int): Int

    fun pack(indices: ByteArray, palette: Palette, w: Int, h: Int): ByteArray
}

/** `P(n)` of docs/protocol.md#payload-size: [n] rounded up to a multiple of 512. */
internal fun pad512(n: Int) = (n + 511) / 512 * 512

/**
 * 4 bits per pixel (E6, E7), `byte = c(x) << 4 | c(x+1)`, row-major from the top-left, no rotation, no
 * padding (docs/protocol.md#packing, verified on a 1024×600 E6 frame).
 *
 * [split] (docs/protocol.md#split-layouts, the vendor app's layout for image width 1200 or 1600): each row
 * is cut at its middle and all left halves (x < W/2, rows 0..H−1) are sent first, then all right halves; same
 * nibbles, no padding. Needs a width that is a multiple of 4, so a half row is whole bytes.
 *
 * Unsplit, an odd pixel count would leave the last low nibble 0; every resolution in the table has an even
 * width, so rows never share a byte.
 */
data class Nibbles4(val split: Boolean = false) : Packing {
    override fun fits(w: Int, h: Int) = !split || w % 4 == 0

    override fun size(w: Int, h: Int) = (w * h + 1) / 2

    override fun pack(indices: ByteArray, palette: Palette, w: Int, h: Int): ByteArray {
        require(indices.size == w * h && fits(w, h))
        val codes = IntArray(palette.inks.size) { palette.inks[it].code and 0x0F }
        val out = ByteArray(size(w, h))
        if (!split) {
            for (i in out.indices) {
                val hi = codes[indices[2 * i].toInt()]
                val lo = if (2 * i + 1 < indices.size) codes[indices[2 * i + 1].toInt()] else 0
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
        val half = w / 2
        var o = 0
        for (side in 0..1) for (y in 0 until h) {
            val row = y * w + side * half
            for (x in 0 until half step 2) {
                out[o++] = ((codes[indices[row + x].toInt()] shl 4) or codes[indices[row + x + 1].toInt()]).toByte()
            }
        }
        return out
    }
}

/**
 * 2 bits per pixel (E4): `byte = c0 << 6 | c1 << 4 | c2 << 2 | c3`, first pixel in the top bits, one stream
 * over the whole image (rows may share a byte), no padding (docs/protocol.md#packing). W·H must be a multiple
 * of 4.
 */
data object Crumbs2 : Packing {
    override fun fits(w: Int, h: Int) = (w * h) % 4 == 0

    override fun size(w: Int, h: Int) = w * h / 4

    override fun pack(indices: ByteArray, palette: Palette, w: Int, h: Int): ByteArray {
        require(indices.size == w * h && fits(w, h))
        val codes = IntArray(palette.inks.size) { palette.inks[it].code and 0x03 }
        val out = ByteArray(size(w, h))
        for (i in out.indices) {
            var v = 0
            for (k in 0..3) v = (v shl 2) or codes[indices[4 * i + k].toInt()]
            out[i] = v.toByte()
        }
        return out
    }
}

/**
 * [planes] 1-bit planes (BW/BP: 1, BWR: 2), sent in plane order; bit `p` of an ink's code is its pixel in plane
 * `p` (BWR: bit 0 = black plane, bit 1 = red plane). Each plane is one continuous bit stream, first pixel in
 * the MSB, rows not byte-aligned (docs/protocol.md#packing); every plane is sent, even an empty one (MK 0).
 *
 * Unsplit, each plane is padded with 0x00 to `P(ceil(W·H/8))` (docs/protocol.md#payload-size). [split]
 * (docs/protocol.md#split-layouts, the vendor app's BWR layout at width 1360 and 1440): the parts are left
 * half of every plane, then right half of every plane (BWR: left black, left red, right black, right red),
 * each part the half rows (x < W/2, then x ≥ W/2) of rows 0..H−1 and padded by `512 − (len mod 512)` bytes,
 * as the vendor code does (a full 512 if `len` is already a multiple, which no real size hits). Needs a width
 * that is a multiple of 16, so a half row is whole bytes.
 */
data class BitPlanes(val planes: Int, val split: Boolean = false) : Packing {
    init {
        require(planes in 1..8)
    }

    override fun fits(w: Int, h: Int) = !split || w % 16 == 0

    private fun part(w: Int, h: Int) = w * h / 16

    private fun partPadded(w: Int, h: Int) = part(w, h).let { it + 512 - it % 512 }

    override fun size(w: Int, h: Int) =
        if (split) 2 * planes * partPadded(w, h) else planes * pad512((w * h + 7) / 8)

    override fun pack(indices: ByteArray, palette: Palette, w: Int, h: Int): ByteArray {
        require(indices.size == w * h && fits(w, h))
        val codes = IntArray(palette.inks.size) { palette.inks[it].code }
        val out = ByteArray(size(w, h))
        // writes the bits of plane p for pixels [x0, x1) of every row, starting at byte [at]
        fun plane(p: Int, x0: Int, x1: Int, at: Int) {
            var bit = 0
            for (y in 0 until h) for (x in x0 until x1) {
                if ((codes[indices[y * w + x].toInt()] shr p) and 1 != 0) {
                    val i = at + bit / 8
                    out[i] = (out[i].toInt() or (0x80 ushr (bit % 8))).toByte()
                }
                bit++
            }
        }
        if (!split) {
            val stride = pad512((w * h + 7) / 8)
            for (p in 0 until planes) plane(p, 0, w, p * stride)
        } else {
            val stride = partPadded(w, h)
            val half = w / 2
            for (side in 0..1) for (p in 0 until planes) plane(p, side * half, side * half + half, (side * planes + p) * stride)
        }
        return out
    }
}

/**
 * [planes] 1-bit planes, each row padded to a whole byte, sent in plane order with no other padding (the
 * OpenDisplay layout, docs/protocol.md#opendisplay-image-formats); bit `p` of an ink's code is its pixel in
 * plane `p`, first pixel of a row in the MSB.
 */
data class RowPlanes(val planes: Int) : Packing {
    init {
        require(planes in 1..8)
    }

    private fun stride(w: Int) = (w + 7) / 8

    override fun size(w: Int, h: Int) = planes * stride(w) * h

    override fun pack(indices: ByteArray, palette: Palette, w: Int, h: Int): ByteArray {
        require(indices.size == w * h)
        val codes = IntArray(palette.inks.size) { palette.inks[it].code }
        val out = ByteArray(size(w, h))
        val stride = stride(w)
        for (p in 0 until planes) {
            val base = p * stride * h
            for (y in 0 until h) for (x in 0 until w) {
                if ((codes[indices[y * w + x].toInt()] shr p) and 1 != 0) {
                    val i = base + y * stride + x / 8
                    out[i] = (out[i].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
        }
        return out
    }
}

/**
 * Where a picture lands on a panel: [width] × [height] px with its top-left corner at ([left], [top]); the
 * rest of the panel is paper ([FrameFormat.paper]).
 */
data class Placement(val left: Int, val top: Int, val width: Int, val height: Int)

/** Cells of padding on each side of a dither row: Burkes reaches two pixels sideways. */
private const val PAD = 2

/**
 * Everything the renderer and the uploader need for one frame: its size, its inks, how pixels are packed,
 * the content marker (MK) of the start frame, and whether this exact layout was seen working on a real
 * panel ([verified]; the picker says "not verified on hardware" otherwise). Chosen from the frame's
 * [FrameCaps] by [FrameFormats.find]. Free of Android types.
 */
data class FrameFormat(
    val width: Int,
    val height: Int,
    val palette: Palette,
    val packing: Packing,
    /**
     * MK of the start frame (docs/protocol.md#content-marker); 0 = image data follows, for every format sent
     * so far.
     */
    val marker: Int = 0,
    val verified: Boolean = false,
) {
    init {
        require(width > 0 && height > 0 && packing.fits(width, height)) { "$packing cannot lay out $width×$height" }
        require(palette.inks.isNotEmpty() && marker in 0..0xFF)
    }

    val payloadSize: Int get() = packing.size(width, height)

    /** Every ink is a grey (BW, four greys): the picture is dithered by luminance, with auto levels. */
    val greys: Boolean get() = palette.inks.all { it.r == it.g && it.g == it.b }
    val aspect: Float get() = width.toFloat() / height

    /** The dithering's ink matcher, one per format: the inks are converted once. */
    private val matcher = InkMatcher(palette)

    /**
     * The ink index of the paper around a fitted picture ([fit]): the ink nearest to white by the dithering's
     * own [InkMatcher] (the first wins a tie), so the bars are the panel's own white, or its lightest ink where
     * it has no white (BP: pink).
     */
    val paper: Int = matcher.nearest(255, 255, 255)

    /**
     * A [w] × [h] px picture scaled to fit inside the panel, whole (no crop), up or down, and centred: bars of
     * paper at the sides of a picture narrower than the panel's shape, at the top and bottom of a wider one,
     * none for the same shape. Each side is at least 1 px.
     */
    fun fit(w: Int, h: Int): Placement {
        require(w > 0 && h > 0)
        // compare w/h with width/height in integers: the side that limits the scale is the panel's own
        val (pw, ph) = if (w.toLong() * height >= h.toLong() * width) {
            width to (h.toLong() * width / w.toDouble()).roundToInt().coerceIn(1, height)
        } else {
            (w.toLong() * height / h.toDouble()).roundToInt().coerceIn(1, width) to height
        }
        return centred(pw, ph)
    }

    /** A picture of [w] × [h] px, no larger than the panel, centred on it as it is. */
    fun centred(w: Int, h: Int): Placement {
        require(w in 1..width && h in 1..height) { "$w×$h does not fit $width×$height" }
        return Placement((width - w) / 2, (height - h) / 2, w, h)
    }

    /**
     * The decoder's subsampling for an original of [w] × [h] px: the largest power of two that still leaves
     * the picture at least as large as its place on the panel ([fit]), so it is never scaled up for nothing.
     */
    fun sampleSize(w: Int, h: Int): Int {
        val at = fit(w, h)
        var sample = 1
        while (w / (sample * 2) >= at.width && h / (sample * 2) >= at.height) sample *= 2
        return sample
    }

    /** The dithering of [px] (ARGB, the whole panel, row-major): [dither] with the picture filling the panel. */
    fun dither(px: IntArray): ByteArray = dither(px, Placement(0, 0, width, height))

    /**
     * Error diffusion of [px] (ARGB, [at]'s width × height, row-major) onto [palette], always the
     * panel's own (docs/protocol.md#dithering: the vendor app dithers every type with the E6 inks, which breaks
     * the others). The OpenDisplay `epaper-dithering` recipe (MIT), ported with its order of operations: Burkes
     * kernel (/32), serpentine rows (odd rows right to left, the kernel mirrored), the pixel plus its error
     * clamped to 0..255 and rounded, matched by [InkMatcher] (in OKLab, or by RGB distance on a palette with one or
     * two colour inks: a deviation from the reference), and the error, as doubles in sRGB, is that clamped value
     * minus the ink; error falling outside the picture is dropped. A see-through pixel is first laid on white, as
     * the reference does ([onWhite]).
     *
     * The picture sits at [at] on the panel and the rest is [paper], plain: it is dithered as an image of its
     * own, so no error reaches the bars and they stay clean. One ink index per pixel of the whole panel. On a
     * panel of [greys] the picture first becomes greys with auto levels ([Levels.stretched], measured on the
     * picture alone, not the bars).
     */
    fun dither(px: IntArray, at: Placement): ByteArray {
        val w = at.width
        val h = at.height
        require(px.size == w * h)
        @Suppress("NAME_SHADOWING") val px = onWhite(px).let { if (greys) Levels.stretched(it) else it }
        require(at.left >= 0 && at.top >= 0 && at.left + w <= width && at.top + h <= height)
        val inks = palette.inks
        // Two rows of the reference's working buffer (pixel value plus the error received so far, 3 channels),
        // not the whole picture (a TV has little memory): the kernel reaches one row down only. A row is loaded
        // with its pixels before any error arrives, so every cell sums in the same order as the full buffer.
        // Two cells of padding on both sides take the error that falls outside the picture; they are never read.
        val stride = (w + 2 * PAD) * 3
        var cur = DoubleArray(stride)
        var nxt = DoubleArray(stride)
        fun load(row: DoubleArray, y: Int) {
            row.fill(0.0)
            if (y >= h) return
            for (x in 0 until w) {
                val p = px[y * w + x]
                val e = (x + PAD) * 3
                row[e] = ((p shr 16) and 0xFF).toDouble()
                row[e + 1] = ((p shr 8) and 0xFF).toDouble()
                row[e + 2] = (p and 0xFF).toDouble()
            }
        }
        load(cur, 0)
        load(nxt, 1)
        val out = ByteArray(width * height)
        if (w != width || h != height) out.fill(paper.toByte())
        for (y in 0 until h) {
            val row = (at.top + y) * width + at.left
            val reverse = y % 2 == 1
            val s = if (reverse) -3 else 3 // one pixel along the scan, in cells
            for (xi in 0 until w) {
                val x = if (reverse) w - 1 - xi else xi
                val e = (x + PAD) * 3
                val rs = cur[e].coerceIn(0.0, 255.0)
                val gs = cur[e + 1].coerceIn(0.0, 255.0)
                val bs = cur[e + 2].coerceIn(0.0, 255.0)
                // Math.round is half up, as Rust's half away from zero for these non-negative values
                val best = matcher.nearest(Math.round(rs).toInt(), Math.round(gs).toInt(), Math.round(bs).toInt())
                out[row + x] = best.toByte()
                val ink = inks[best]
                val er = rs - ink.r
                val eg = gs - ink.g
                val eb = bs - ink.b
                // Burkes, in the reference's order: (1,0) 8, (2,0) 4, (−2,1) 2, (−1,1) 4, (0,1) 8, (1,1) 4, (2,1) 2
                spread(cur, e + s, er, eg, eb, 8.0 / 32.0)
                spread(cur, e + 2 * s, er, eg, eb, 4.0 / 32.0)
                spread(nxt, e - 2 * s, er, eg, eb, 2.0 / 32.0)
                spread(nxt, e - s, er, eg, eb, 4.0 / 32.0)
                spread(nxt, e, er, eg, eb, 8.0 / 32.0)
                spread(nxt, e + s, er, eg, eb, 4.0 / 32.0)
                spread(nxt, e + 2 * s, er, eg, eb, 2.0 / 32.0)
            }
            val t = cur; cur = nxt; nxt = t
            load(nxt, y + 2)
        }
        return out
    }

    /**
     * [px] with every pixel that is not opaque laid on white (`c·α + 255·(1 − α)`, rounded), as the reference's
     * `composite_rgba_on_white`, so a transparent area is paper, not black; [px] itself when all are opaque.
     */
    private fun onWhite(px: IntArray): IntArray {
        if (px.all { it ushr 24 == 0xFF }) return px
        return IntArray(px.size) { i ->
            val p = px[i]
            val a = (p ushr 24) / 255.0
            fun ch(shift: Int) = Math.round(((p shr shift) and 0xFF) * a + 255.0 * (1.0 - a)).toInt()
            (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    private fun spread(buf: DoubleArray, e: Int, er: Double, eg: Double, eb: Double, weight: Double) {
        buf[e] += er * weight
        buf[e + 1] += eg * weight
        buf[e + 2] += eb * weight
    }

    /** The payload ([payloadSize] bytes) for [indices] from [dither]. */
    fun pack(indices: ByteArray): ByteArray = packing.pack(indices, palette, width, height)
}

/** The format for an advertisement, or why there is none. */
sealed interface FormatLookup {
    data class Supported(val format: FrameFormat) : FormatLookup
    data class Unsupported(val reason: Txt) : FormatLookup

    val formatOrNull: FrameFormat? get() = (this as? Supported)?.format
    val reasonOrNull: Txt? get() = (this as? Unsupported)?.reason
}

/**
 * The frames this app can draw on, by their [FrameCaps] (whatever protocol reported them), and the one place
 * that decides it: the frame list greys out the rest and does not let them be chosen, [FrameClient] refuses a
 * selected frame whose caps no longer qualify, and `UploadEngine` renders with the format found here. A
 * panel type is one or more [Entry]s (palette, packing, marker, sizes); a protocol's own rejections come first
 * (e.g. [FrameAdvert.lookup], [OdDisplay.lookup]). Formats from docs/protocol.md#image-formats and
 * docs/protocol.md#opendisplay-image-formats.
 */
object FrameFormats {
    /**
     * E6 (Spectra 6) inks, measured: device codes 0 black, 1 white, 2 yellow, 3 red, 5 blue, 6 green
     * (docs/protocol.md#palettes-and-device-codes).
     */
    val E6 = Palette(
        "E6",
        listOf(
            Ink(0, 0, 0, 0),
            Ink(255, 255, 255, 1),
            Ink(240, 224, 80, 2),
            Ink(160, 32, 32, 3),
            Ink(80, 128, 184, 5),
            Ink(95, 130, 78, 6),
        ),
    )

    /**
     * BWR (docs/protocol.md#palettes-and-device-codes, in the vendor list order); codes are plane bits for
     * [BitPlanes]: 1 = black plane, 2 = red plane.
     */
    val BWR = Palette("BWR", listOf(Ink(255, 255, 255, 0), Ink(0, 0, 0, 1), Ink(255, 0, 0, 2)))

    /** E4 (docs/protocol.md#palettes-and-device-codes, in the vendor list order): 0 black, 1 white, 2 yellow, 3 red. */
    val E4 = Palette("E4", listOf(Ink(255, 255, 255, 1), Ink(0, 0, 0, 0), Ink(255, 0, 0, 3), Ink(255, 255, 0, 2)))

    /** BW (docs/protocol.md#palettes-and-device-codes): plane bit 1 = black. */
    val BW = Palette("BW", listOf(Ink(255, 255, 255, 0), Ink(0, 0, 0, 1)))

    /** BP (docs/protocol.md#palettes-and-device-codes): black and pink, no white; plane bit 1 = black. */
    val BP = Palette("BP", listOf(Ink(0, 0, 0, 1), Ink(255, 192, 203, 0)))

    /** E7 (docs/protocol.md#palettes-and-device-codes), the vendor's codes with orange as 4 (inferred). */
    val E7 = Palette(
        "E7",
        listOf(
            Ink(0, 0, 0, 0), Ink(255, 255, 255, 1), Ink(255, 0, 0, 3), Ink(255, 255, 0, 2),
            Ink(0, 0, 255, 5), Ink(0, 255, 0, 6), Ink(255, 127, 0, 4),
        ),
    )

    /**
     * BWY: no vendor palette; a guess from the BWR packing, which carries (255,255,0) in the red plane
     * (docs/protocol.md#palettes-and-device-codes).
     */
    val BWY = Palette("BWY", listOf(Ink(255, 255, 255, 0), Ink(0, 0, 0, 1), Ink(255, 255, 0, 2)))

    /**
     * OpenDisplay inks (docs/protocol.md#opendisplay-image-formats): the reference sender's even levels, codes
     * as plane bits for [RowPlanes]. Mono: plane bit 1 = white. BWR: first plane white or red, second red. BWY:
     * first plane white, second yellow. Four greys: the panel's stored 2-bit code, black 3, dark grey 1, light
     * grey 2, white 0.
     */
    val OD_BW = Palette("BW", listOf(Ink(0, 0, 0, 0), Ink(255, 255, 255, 1)))
    val OD_BWR = Palette("BWR", listOf(Ink(255, 255, 255, 1), Ink(0, 0, 0, 0), Ink(255, 0, 0, 3)))
    val OD_BWY = Palette("BWY", listOf(Ink(255, 255, 255, 1), Ink(0, 0, 0, 0), Ink(255, 255, 0, 2)))
    val OD_G4 = Palette(
        "G4",
        listOf(Ink(0, 0, 0, 3), Ink(85, 85, 85, 1), Ink(170, 170, 170, 2), Ink(255, 255, 255, 0)),
    )

    /** The frame this app was built and verified with; also the card shape while no frame is chosen. */
    val DEFAULT = FrameFormat(1024, 600, E6, Nibbles4(), marker = 0, verified = true)

    /**
     * One panel type of frames of [kind] at some [sizes] (`null`: any size). [refused]: why such frames cannot
     * be chosen yet (the rest is what they would use, so enabling them is dropping the reason).
     */
    private class Entry(
        val colours: ColourScheme,
        val palette: Palette,
        val packing: Packing,
        val sizes: Set<Pair<Int, Int>>?,
        val marker: Int = 0,
        val verified: Boolean = false,
        val refused: Txt? = null,
        val kind: FrameKind = FrameKind.NOWMEAN,
    )

    private val ALL = FrameAdvert.SIZES.values.toSet()
    // width 1200 or 1600; only 1200×1600 has a size code (docs/protocol.md#split-layouts)
    private val E6_SPLIT = setOf(1200 to 1600)
    private val BWR_SPLIT = setOf(1360 to 480, 1440 to 600) // width 1360 or 1440 (docs/protocol.md#split-layouts)

    private val UNTESTED_FORMAT = Txt(R.string.reason_untested_format)
    private val VENDOR_CANNOT = Txt(R.string.reason_vendor_cannot)

    private val ENTRIES = listOf(
        // verified at 1024×600; at the other unsplit sizes only W and H differ, and these bytes are what the
        // vendor app sends (it renders E6 correctly, docs/protocol.md#dithering)
        Entry(ColourScheme.E6, E6, Nibbles4(), ALL - E6_SPLIT - BWR_SPLIT, verified = true),
        // the vendor app sends these unsplit too, but such a wide panel may well have two controllers
        Entry(ColourScheme.E6, E6, Nibbles4(), BWR_SPLIT),
        Entry(ColourScheme.E6, E6, Nibbles4(split = true), E6_SPLIT),
        // both planes always (MK 0), never the "black only" (2) or "no data" (3) shortcuts
        Entry(ColourScheme.BWR, BWR, BitPlanes(2), ALL - BWR_SPLIT),
        Entry(ColourScheme.BWR, BWR, BitPlanes(2, split = true), BWR_SPLIT),
        // MK 0 always, without the all-white shortcut (MK 3)
        Entry(ColourScheme.E4, E4, Crumbs2, ALL),
        // refused: they plug in by dropping the reason
        Entry(ColourScheme.BW, BW, BitPlanes(1), ALL, marker = 1, refused = UNTESTED_FORMAT),
        Entry(ColourScheme.BP, BP, BitPlanes(1), ALL, marker = 1, refused = UNTESTED_FORMAT),
        Entry(ColourScheme.E7, E7, Nibbles4(), ALL - E6_SPLIT, refused = UNTESTED_FORMAT),
        Entry(ColourScheme.E7, E7, Nibbles4(split = true), E6_SPLIT, refused = UNTESTED_FORMAT),
        Entry(ColourScheme.BWY, BWY, BitPlanes(2), ALL - BWR_SPLIT, refused = VENDOR_CANNOT),
        Entry(ColourScheme.BWY, BWY, BitPlanes(2, split = true), BWR_SPLIT, refused = VENDOR_CANNOT),
        // OpenDisplay, any size the frame reports: the reference sender's layouts. Four greys verified on an
        // 800×480 panel; the rest differ from it only in the inks and plane count, or (Spectra 6) are the
        // NowMean E6 nibbles, but have not been seen on a panel.
        Entry(ColourScheme.G4, OD_G4, RowPlanes(2), null, verified = true, kind = FrameKind.OPENDISPLAY),
        Entry(ColourScheme.BW, OD_BW, RowPlanes(1), null, kind = FrameKind.OPENDISPLAY),
        Entry(ColourScheme.BWR, OD_BWR, RowPlanes(2), null, kind = FrameKind.OPENDISPLAY),
        Entry(ColourScheme.BWY, OD_BWY, RowPlanes(2), null, kind = FrameKind.OPENDISPLAY),
        Entry(ColourScheme.E6, E6, Nibbles4(), null, kind = FrameKind.OPENDISPLAY),
    )

    /** The format for [caps] of a frame of [kind] (`null`: the frame reported none). */
    fun find(caps: FrameCaps?, kind: FrameKind = FrameKind.NOWMEAN): FormatLookup {
        if (caps == null) return FormatLookup.Unsupported(Txt(R.string.reason_no_caps))
        if (caps.rotation != 0) return FormatLookup.Unsupported(
            Txt(R.string.reason_rotation, caps.rotation?.let { Txt(R.string.advert_degrees, it) } ?: Txt(R.string.advert_degrees_unknown))
        )
        val w = caps.width
        val h = caps.height
        if (w == null || h == null) return FormatLookup.Unsupported(Txt(R.string.reason_unknown_size))
        val entries = ENTRIES.filter { it.kind == kind && it.colours == caps.colours }
        if (entries.isEmpty()) return FormatLookup.Unsupported(Txt(R.string.reason_colours, caps.colours.name))
        val entry = entries.firstOrNull { it.sizes == null || (w to h) in it.sizes }
            ?: return FormatLookup.Unsupported(Txt(R.string.reason_colours_size, caps.colours.name, w, h))
        entry.refused?.let { return FormatLookup.Unsupported(it) }
        if (!entry.packing.fits(w, h)) return FormatLookup.Unsupported(Txt(R.string.reason_colours_size, caps.colours.name, w, h))
        return FormatLookup.Supported(FrameFormat(w, h, entry.palette, entry.packing, entry.marker, entry.verified))
    }
}
