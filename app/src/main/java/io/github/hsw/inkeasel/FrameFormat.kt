package io.github.hsw.inkeasel

/**
 * One ink of a panel: the RGB dithering aims at and the device's code for it. What the code means is up to
 * the [Packing]: a 4/2-bit colour code ([Nibbles4], [Crumbs2]) or a set of plane bits ([BitPlanes]).
 */
data class Ink(val r: Int, val g: Int, val b: Int, val code: Int)

data class Palette(val name: String, val inks: List<Ink>)

/**
 * How palette indices (one per pixel, row-major from the top-left) become the whole payload the frame
 * expects (`imageData` before LZO, docs/device-api.md §5.4.3): bit depth, planes, padding and split layout.
 */
interface Packing {
    /** Whether a [w]×[h] image can be laid out this way at all. */
    fun fits(w: Int, h: Int): Boolean = true

    /** Payload bytes for a [w]×[h] image (§5.4.7). */
    fun size(w: Int, h: Int): Int

    fun pack(indices: ByteArray, palette: Palette, w: Int, h: Int): ByteArray
}

/** `P(n)` of §5.4.7: [n] rounded up to a multiple of 512. */
internal fun pad512(n: Int) = (n + 511) / 512 * 512

/**
 * 4 bits per pixel (E6, E7), `byte = c(x) << 4 | c(x+1)`, row-major from the top-left, no rotation, no
 * padding (§5.2, verified on a 1024×600 E6 frame in §14).
 *
 * [split] (§5.4.5, the vendor app's layout for image width 1200 or 1600): each row is cut at its middle and
 * all left halves (x < W/2, rows 0..H−1) are sent first, then all right halves; same nibbles, no padding.
 * Needs a width that is a multiple of 4, so a half row is whole bytes.
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
 * over the whole image (rows may share a byte), no padding (§5.4.3). W·H must be a multiple of 4.
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
 * the MSB, rows not byte-aligned (§5.4.3); every plane is sent, even an empty one (MK 0).
 *
 * Unsplit, each plane is padded with 0x00 to `P(ceil(W·H/8))` (§5.4.7). [split] (§5.4.5, the vendor app's BWR
 * layout at width 1360 and 1440): the parts are left half of every plane, then right half of every plane
 * (BWR: left black, left red, right black, right red), each part the half rows (x < W/2, then x ≥ W/2) of
 * rows 0..H−1 and padded by `512 − (len mod 512)` bytes, as the vendor code does (a full 512 if `len` is
 * already a multiple, which no real size hits). Needs a width that is a multiple of 16, so a half row is
 * whole bytes.
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
 * Everything the renderer and the uploader need for one frame: its size, its inks, how pixels are packed,
 * the content marker (MK) of the start frame, and whether this exact layout was seen working on a real
 * panel ([verified]; the picker says "не проверено на железе" otherwise). Chosen from the frame's
 * [FrameCaps] by [FrameFormats.find]. Free of Android types.
 */
data class FrameFormat(
    val width: Int,
    val height: Int,
    val palette: Palette,
    val packing: Packing,
    /** MK of the start frame (§5.4.4); 0 = image data follows, for every format sent so far. */
    val marker: Int = 0,
    val verified: Boolean = false,
) {
    init {
        require(width > 0 && height > 0 && packing.fits(width, height)) { "$packing cannot lay out $width×$height" }
        require(palette.inks.isNotEmpty() && marker in 0..0xFF)
    }

    val payloadSize: Int get() = packing.size(width, height)
    val aspect: Float get() = width.toFloat() / height

    /**
     * Floyd–Steinberg of [px] (ARGB, [width]×[height], row-major) onto [palette], always the panel's own
     * (§5.4.1: the vendor app dithers every type with the E6 inks, which breaks the others): plain raster
     * order, nearest ink by squared RGB distance (the first wins a tie), the pixel plus its error clamped to
     * 0..255. One ink index per pixel.
     */
    fun dither(px: IntArray): ByteArray {
        val w = width
        val h = height
        require(px.size == w * h)
        val inks = palette.inks
        val pr = IntArray(inks.size) { inks[it].r }
        val pg = IntArray(inks.size) { inks[it].g }
        val pb = IntArray(inks.size) { inks[it].b }
        // error buffers for the current and next row, 3 channels each, with one pixel of padding on both sides
        var cur = IntArray((w + 2) * 3)
        var nxt = IntArray((w + 2) * 3)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = px[y * w + x]
                val e = (x + 1) * 3
                val r = (((p shr 16) and 0xFF) + cur[e] / 16).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + cur[e + 1] / 16).coerceIn(0, 255)
                val b = ((p and 0xFF) + cur[e + 2] / 16).coerceIn(0, 255)
                var best = 0
                var bestD = Int.MAX_VALUE
                for (i in pr.indices) {
                    val dr = r - pr[i]
                    val dg = g - pg[i]
                    val db = b - pb[i]
                    val d = dr * dr + dg * dg + db * db
                    if (d < bestD) { bestD = d; best = i }
                }
                out[y * w + x] = best.toByte()
                val er = r - pr[best]
                val eg = g - pg[best]
                val eb = b - pb[best]
                // weights 7/16 right, 3/16 down-left, 5/16 down, 1/16 down-right (stored ×16)
                cur[e + 3] += er * 7; cur[e + 4] += eg * 7; cur[e + 5] += eb * 7
                nxt[e - 3] += er * 3; nxt[e - 2] += eg * 3; nxt[e - 1] += eb * 3
                nxt[e] += er * 5; nxt[e + 1] += eg * 5; nxt[e + 2] += eb * 5
                nxt[e + 3] += er; nxt[e + 4] += eg; nxt[e + 5] += eb
            }
            val t = cur; cur = nxt; nxt = t
            nxt.fill(0)
        }
        return out
    }

    /** The payload ([payloadSize] bytes) for [indices] from [dither]. */
    fun pack(indices: ByteArray): ByteArray = packing.pack(indices, palette, width, height)

    /** ARGB of each ink index, for a preview of roughly what the panel will show. */
    fun previewPixels(indices: ByteArray): IntArray =
        IntArray(indices.size) { val c = palette.inks[indices[it].toInt()]; (0xFF shl 24) or (c.r shl 16) or (c.g shl 8) or c.b }
}

/** The format for an advertisement, or why there is none. */
sealed interface FormatLookup {
    data class Supported(val format: FrameFormat) : FormatLookup
    data class Unsupported(val reason: String) : FormatLookup

    val formatOrNull: FrameFormat? get() = (this as? Supported)?.format
    val reasonOrNull: String? get() = (this as? Unsupported)?.reason
}

/**
 * The frames this app can draw on, by their [FrameCaps] (whatever protocol reported them), and the one place
 * that decides it: the frame list greys out the rest and does not let them be chosen, [FrameClient] refuses a
 * selected frame whose caps no longer qualify, and `UploadService` renders with the format found here. A
 * panel type is one or more [Entry]s (palette, packing, marker, sizes); a protocol's own rejections come first
 * (e.g. [FrameAdvert.lookup]). Formats from docs/device-api.md §5.4.
 */
object FrameFormats {
    /** E6 (Spectra 6) inks, measured (`e6Palettes`): device codes 0 black, 1 white, 2 yellow, 3 red, 5 blue, 6 green (§5.2). */
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

    /** BWR (§5.4.2, in the vendor list order); codes are plane bits for [BitPlanes]: 1 = black plane, 2 = red plane. */
    val BWR = Palette("BWR", listOf(Ink(255, 255, 255, 0), Ink(0, 0, 0, 1), Ink(255, 0, 0, 2)))

    /** E4 (§5.4.2, in the vendor list order), codes of `_pixel2C`: 0 black, 1 white, 2 yellow, 3 red. */
    val E4 = Palette("E4", listOf(Ink(255, 255, 255, 1), Ink(0, 0, 0, 0), Ink(255, 0, 0, 3), Ink(255, 255, 0, 2)))

    /** BW (§5.4.2): plane bit 1 = black. */
    val BW = Palette("BW", listOf(Ink(255, 255, 255, 0), Ink(0, 0, 0, 1)))

    /** BP (§5.4.2): black and pink, no white; plane bit 1 = black. */
    val BP = Palette("BP", listOf(Ink(0, 0, 0, 1), Ink(255, 192, 203, 0)))

    /** E7 (§5.4.2), codes of `_pixel2C` with orange as 4 (the intended code, §5.4.2 notes). */
    val E7 = Palette(
        "E7",
        listOf(
            Ink(0, 0, 0, 0), Ink(255, 255, 255, 1), Ink(255, 0, 0, 3), Ink(255, 255, 0, 2),
            Ink(0, 0, 255, 5), Ink(0, 255, 0, 6), Ink(255, 127, 0, 4),
        ),
    )

    /** BWY: no vendor palette; a guess from the BWR packing, which carries (255,255,0) in the red plane (§5.4.2). */
    val BWY = Palette("BWY", listOf(Ink(255, 255, 255, 0), Ink(0, 0, 0, 1), Ink(255, 255, 0, 2)))

    /** The frame this app was built and verified with (§14); also the card shape while no frame is chosen. */
    val DEFAULT = FrameFormat(1024, 600, E6, Nibbles4(), marker = 0, verified = true)

    /**
     * One panel type at some [sizes]. [refused]: why such frames cannot be chosen yet (the rest is what they
     * would use, so enabling them is dropping the reason).
     */
    private class Entry(
        val colours: ColourScheme,
        val palette: Palette,
        val packing: Packing,
        val sizes: Set<Pair<Int, Int>>,
        val marker: Int = 0,
        val verified: Boolean = false,
        val refused: String? = null,
    )

    private val ALL = FrameAdvert.SIZES.values.toSet()
    private val E6_SPLIT = setOf(1200 to 1600) // width 1200 or 1600; only 1200×1600 has a size code (§5.4.5)
    private val BWR_SPLIT = setOf(1360 to 480, 1440 to 600) // width 1360 or 1440 (§5.4.5)

    private const val UNTESTED_FORMAT = "формат не проверен (приложение производителя не рисует его правильно)"
    private const val VENDOR_CANNOT = "приложение производителя не умеет отправлять на такие рамки"

    private val ENTRIES = listOf(
        // verified at 1024×600 (§14); at the other unsplit sizes only W and H differ, and these bytes are what
        // the vendor app sends (it renders E6 correctly, §5.4.1)
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
    )

    /** The format for [caps] (`null`: the frame reported none). */
    fun find(caps: FrameCaps?): FormatLookup {
        if (caps == null) return FormatLookup.Unsupported("рамка не сообщает свои параметры")
        if (caps.rotation != 0) return FormatLookup.Unsupported("поворот ${caps.rotation?.let { "$it°" } ?: "?"} пока не поддерживается")
        val w = caps.width
        val h = caps.height
        if (w == null || h == null) return FormatLookup.Unsupported("неизвестное разрешение")
        val entries = ENTRIES.filter { it.colours == caps.colours }
        if (entries.isEmpty()) return FormatLookup.Unsupported("цвета ${caps.colours.name} пока не поддерживаются")
        val entry = entries.firstOrNull { (w to h) in it.sizes }
            ?: return FormatLookup.Unsupported("${caps.colours.name} ${w}×$h пока не поддерживается")
        entry.refused?.let { return FormatLookup.Unsupported(it) }
        return FormatLookup.Supported(FrameFormat(w, h, entry.palette, entry.packing, entry.marker, entry.verified))
    }
}
