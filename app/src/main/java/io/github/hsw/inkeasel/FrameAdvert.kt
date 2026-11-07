package io.github.hsw.inkeasel

/**
 * What a frame advertises in its manufacturer data (company ID [COMPANY_ID], the bytes after the ID;
 * docs/device-api.md §2, verified in §14): colour and panel type, resolution, rotation, safe mode, battery
 * and the optional firmware version. [raw] keeps the bytes as received, so a later reading of a bit we do not
 * decode yet needs no new scan. Unknown colour and panel codes decode the way the vendor app does (BWR, AB);
 * an unknown resolution or rotation code leaves [width]/[height] or [rotation] `null`. Free of Android types.
 */
class FrameAdvert private constructor(
    val raw: ByteArray,
    val colour: ColourScheme,
    val panel: Panel,
    /** b1 bits 4..0, see [SIZES]. */
    val resolutionCode: Int,
    val width: Int?,
    val height: Int?,
    /** Degrees (0, 90, 180, 270); `null` for a code the vendor app does not know. */
    val rotation: Int?,
    val safeMode: Boolean,
    /** Percent (b2 bits 6..0; 100 on a full frame, §14). */
    val battery: Int,
    /** Firmware/protocol version (b3 bits 6..0); `null` if the frame sends no b3. */
    val firmware: Int?,
) {
    enum class Panel { A, AB, TA }

    /** What the frame can show, for [FrameFormats.find]. */
    val caps: FrameCaps get() = FrameCaps(width, height, colour, rotation)

    /**
     * Why this frame is out regardless of its caps, or `null`: facts of this protocol that [FrameCaps] does
     * not carry.
     */
    val rejection: String?
        get() = when {
            // the vendor album app drops panel type A as well (§2): not a photo frame
            panel == Panel.A -> "панель A (не фоторамка)"
            width == null || height == null -> "неизвестное разрешение ($size)"
            else -> null
        }

    val hex: String get() = hex(raw)

    /** "1024×600", or the code if it is unknown. */
    val size: String get() = if (width != null && height != null) "$width×$height" else "код 0x%02x".format(resolutionCode)

    /** One line for the frame list, e.g. "E6, панель TA · 1024×600, 0° · батарея 100 % · прошивка 21". */
    fun describe(): String = listOfNotNull(
        "${colour.name}, панель ${panel.name}",
        "$size, " + (rotation?.let { "$it°" } ?: "поворот ?"),
        "батарея $battery %",
        firmware?.let { "прошивка $it" },
        "безопасный режим".takeIf { safeMode },
    ).joinToString(" · ")

    override fun equals(other: Any?) = other is FrameAdvert && raw.contentEquals(other.raw)
    override fun hashCode() = raw.contentHashCode()
    override fun toString() = "FrameAdvert($hex: ${describe()})"

    companion object {
        const val COMPANY_ID = 0xABC8

        /** The render format of a frame advertising [a]: its own [rejection] first, then [FrameFormats.find]. */
        fun lookup(a: FrameAdvert?): FormatLookup =
            a?.rejection?.let { FormatLookup.Unsupported(it) } ?: FrameFormats.find(a?.caps)

        /** Resolution codes (b1 bits 4..0) to width × height (§2). */
        val SIZES: Map<Int, Pair<Int, Int>> = mapOf(
            0x01 to (200 to 200), 0x02 to (122 to 250), 0x03 to (128 to 296), 0x04 to (152 to 296),
            0x05 to (184 to 384), 0x06 to (400 to 300), 0x07 to (648 to 480), 0x08 to (800 to 480),
            0x09 to (960 to 640), 0x10 to (960 to 672), 0x11 to (1440 to 600), 0x12 to (1360 to 480),
            0x13 to (1024 to 600), 0x14 to (1200 to 1600),
        )

        /** Parse the manufacturer data after the company ID; `null` if shorter than the 3 mandatory bytes. */
        fun parse(data: ByteArray?): FrameAdvert? {
            if (data == null || data.size < 3) return null
            val b0 = data[0].toInt() and 0xFF
            val b1 = data[1].toInt() and 0xFF
            val b2 = data[2].toInt() and 0xFF
            val colour = when (b0 shr 4) {
                0 -> ColourScheme.BW
                1 -> ColourScheme.BWR
                2 -> ColourScheme.BWY
                3 -> ColourScheme.E4
                4 -> ColourScheme.E6
                5 -> ColourScheme.E7
                6 -> ColourScheme.BP
                else -> ColourScheme.BWR
            }
            val panel = when (b0 and 0x0F) {
                1 -> Panel.A
                3 -> Panel.TA
                else -> Panel.AB
            }
            val code = b1 and 0x1F
            val size = SIZES[code]
            val rot = (b1 shr 5).takeIf { it <= 3 }?.let { it * 90 }
            return FrameAdvert(
                raw = data.copyOf(),
                colour = colour,
                panel = panel,
                resolutionCode = code,
                width = size?.first,
                height = size?.second,
                rotation = rot,
                safeMode = b2 and 0x80 != 0,
                battery = b2 and 0x7F,
                firmware = data.getOrNull(3)?.let { it.toInt() and 0x7F },
            )
        }

        /** Parse the bytes written as [hex] (e.g. kept in prefs); `null` if [s] is not even-length hex. */
        fun parseHex(s: String?): FrameAdvert? = s?.let(::unhex)?.let(::parse)

        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

        fun unhex(s: String): ByteArray? {
            if (s.length % 2 != 0 || !s.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
            val out = ByteArray(s.length / 2)
            for (i in out.indices) out[i] = s.substring(2 * i, 2 * i + 2).toIntOrNull(16)?.toByte() ?: return null
            return out
        }
    }
}
