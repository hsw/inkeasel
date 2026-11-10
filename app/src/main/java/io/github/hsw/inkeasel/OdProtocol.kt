package io.github.hsw.inkeasel

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.Adler32
import java.util.zip.Deflater

/**
 * The OpenDisplay BLE protocol (docs/protocol.md#opendisplay), the part this app uses: read the config, send
 * a full picture with direct write. Requests are `[opcode:2 BE][payload]`, written to the one characteristic
 * of service [SERVICE]; answers arrive as notifications on it, `[status][opcode low byte][data]`. Free of
 * Android types.
 */
object Od {
    /** Manufacturer data company ID of the advertisement, and the 16-bit UUID of the GATT service. */
    const val COMPANY_ID = 0x2446
    val SERVICE: UUID = UUID.fromString("00002446-0000-1000-8000-00805f9b34fb")

    const val READ_CONFIG = 0x0040
    const val WRITE_START = 0x0070
    const val WRITE_DATA = 0x0071
    const val WRITE_END = 0x0072
    /** Unsolicited after [WRITE_END]: the panel was refreshed, or the refresh timed out. */
    const val REFRESH_DONE = 0x73
    const val REFRESH_TIMEOUT = 0x74

    /** The buffer of a frame that inflates a whole stream at once (transmission mode bit 1 only). */
    const val BUFFERED_MAX = 50 * 1024

    /** Image bytes per [WRITE_DATA] request without encryption; the request is 2 bytes longer. */
    const val CHUNK = 230

    /** `[opcode:2 BE][payload]`. */
    fun request(op: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf((op shr 8).toByte(), op.toByte()) + payload

    /** Start an uncompressed full-frame write: every image byte follows in [WRITE_DATA] requests. */
    fun start(): ByteArray = request(WRITE_START)

    /** Bytes of the zlib stream a compressed start carries: the request is at most 200 bytes. */
    const val START_DATA = 200 - 2 - 4

    /**
     * Start a compressed full-frame write of [size] image bytes, carrying the first bytes of the zlib stream
     * ([first], at most [START_DATA]); the rest follows in [WRITE_DATA] requests and the write needs [end].
     */
    fun startCompressed(size: Int, first: ByteArray): ByteArray {
        require(first.size in 1..START_DATA)
        return request(WRITE_START, byteArrayOf(size.toByte(), (size shr 8).toByte(), (size shr 16).toByte(), (size shr 24).toByte()) + first)
    }

    /** The largest DEFLATE window the frame's inflater takes (9 bits). */
    const val WINDOW = 512

    /**
     * [data] as a zlib stream the frame can inflate: no match reaches back more than [WINDOW] bytes, and the
     * header says so. `Deflater` cannot be given a window, so its dictionary is reset ([Deflater.FULL_FLUSH])
     * after every [WINDOW] bytes of input, which no match can then cross; the raw stream gets a zlib header
     * with a 512-byte window and the Adler-32 of [data].
     */
    fun zlib(data: ByteArray): ByteArray = zlibWithFlushes(data).first

    /** [zlib] and, for tests, where in it each [WINDOW]-byte part of [data] starts (the raw stream's offsets). */
    internal fun zlibWithFlushes(data: ByteArray): Pair<ByteArray, List<Int>> {
        val raw = ByteArrayOutputStream()
        val starts = mutableListOf<Int>()
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        val buf = ByteArray(4096)
        try {
            var at = 0
            while (at < data.size) {
                starts += raw.size()
                val n = minOf(WINDOW, data.size - at)
                d.setInput(data, at, n)
                do {
                    val k = d.deflate(buf, 0, buf.size, Deflater.FULL_FLUSH)
                    raw.write(buf, 0, k)
                } while (k == buf.size)
                at += n
            }
            d.finish()
            while (!d.finished()) raw.write(buf, 0, d.deflate(buf))
        } finally {
            d.end()
        }
        // CMF: deflate with a 2^(1+8) window; FLG: maximum compression, no dictionary, check bits
        val cmf = 0x18
        val flg = 0xC0 + (31 - (cmf * 256 + 0xC0) % 31) % 31
        val sum = Adler32().apply { update(data) }.value
        val out = byteArrayOf(cmf.toByte(), flg.toByte()) + raw.toByteArray() +
            byteArrayOf((sum shr 24).toByte(), (sum shr 16).toByte(), (sum shr 8).toByte(), sum.toByte())
        return out to starts
    }

    fun data(chunk: ByteArray): ByteArray {
        require(chunk.size in 1..CHUNK)
        return request(WRITE_DATA, chunk)
    }

    /** End the write and refresh the whole panel (refresh mode 0, full). */
    fun end(): ByteArray = request(WRITE_END, byteArrayOf(0))

    /**
     * One answer of the frame. [ok]: status 0x00; [authRequired]: the frame has encryption on and wants a
     * session first (`[0xFE][op]`, or `[0x00][op][0xFE]` from current firmware).
     */
    class Reply(val status: Int, val op: Int, val data: ByteArray) {
        val ok: Boolean get() = status == 0 && !authRequired
        val authRequired: Boolean get() = status == 0xFE || (status == 0 && data.size == 1 && data[0] == 0xFE.toByte())

        override fun toString() = "%02x %02x %s".format(status, op, FrameAdvert.hex(data))

        companion object {
            /** `null` for a notification shorter than status and opcode. */
            fun parse(b: ByteArray): Reply? =
                if (b.size < 2) null else Reply(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF, b.copyOfRange(2, b.size))
        }
    }

    /**
     * Collects the [READ_CONFIG] answer: chunk 0 is `[chunk:2 LE][total:2 LE][data]`, the rest
     * `[chunk:2 LE][data]`; [add] returns the whole config once [total] bytes have arrived.
     */
    class ConfigReader {
        private var total = -1
        private var buf = ByteArray(0)

        /** Feeds one [READ_CONFIG] reply's data; the assembled config when complete, else `null`. */
        fun add(data: ByteArray): ByteArray? {
            if (total < 0) {
                if (data.size < 4) throw IllegalArgumentException("config chunk 0 too short: ${data.size} B")
                total = u16(data, 2)
                buf = data.copyOfRange(4, data.size)
            } else {
                if (data.size <= 2) throw IllegalArgumentException("empty config chunk at ${buf.size}/$total B")
                buf += data.copyOfRange(2, data.size)
            }
            return if (buf.size >= total) buf.copyOf(total) else null
        }
    }

    /** Config TLV packet types and their fixed sizes after `[number][type]`. */
    private val PACKET_SIZES = mapOf(
        0x01 to 22, 0x02 to 22, 0x04 to 30, 0x20 to 46, 0x21 to 22, 0x23 to 30, 0x24 to 30, 0x25 to 30,
        0x26 to 160, 0x27 to 64, 0x28 to 32, 0x29 to 32, 0x2A to 32, 0x2B to 32, 0x2C to 288,
    )
    private const val DISPLAY = 0x20

    /**
     * The first display record in a whole config (`[length:2][version:1][packets][crc:2]`, each packet
     * `[number][type][fixed data]`), or `null` if it has none before a packet type this app does not know.
     */
    fun display(config: ByteArray): OdDisplay? {
        if (config.size < 5) return null
        val end = config.size - 2
        var at = 3
        while (at + 2 <= end) {
            val type = config[at + 1].toInt() and 0xFF
            val size = PACKET_SIZES[type] ?: return null
            if (at + 2 + size > end) return null
            if (type == DISPLAY) return OdDisplay(config.copyOfRange(at + 2, at + 2 + size))
            at += 2 + size
        }
        return null
    }

    internal fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
}

/**
 * The display record (config packet 0x20, 46 bytes) an OpenDisplay frame reports once connected: its panel,
 * size, rotation and colour scheme. [raw] keeps the bytes as received. Free of Android types.
 */
class OdDisplay(val raw: ByteArray) {
    init {
        require(raw.size >= 23) { "display record too short: ${raw.size} B" }
    }

    /** The panel model (`panel_ic_type`). */
    val panel: Int get() = Od.u16(raw, 2)
    val width: Int get() = Od.u16(raw, 4)
    val height: Int get() = Od.u16(raw, 6)

    /** Degrees; the firmware stores an index 0..3 (some configs the degrees); `null` for anything else. */
    val rotation: Int?
        get() = when (val r = raw[14].toInt() and 0xFF) {
            0, 1, 2, 3 -> r * 90
            90, 180 -> r
            else -> null
        }

    /**
     * `transmission_modes` bits 0 (streaming inflate, no size limit) and 1 (buffered inflate, under
     * [Od.BUFFERED_MAX] compressed bytes): whether the frame takes a zlib stream ([Od.zlib]) of [size] bytes.
     */
    fun takesZlib(size: Int): Boolean {
        val modes = raw[22].toInt() and 0xFF
        return modes and 0x01 != 0 || (modes and 0x02 != 0 && size < Od.BUFFERED_MAX)
    }

    /** `color_scheme`: 0 mono, 1 BWR, 2 BWY, 3 BWRY, 4 Spectra 6, 5 four greys, 6+ others. */
    val scheme: Int get() = raw[21].toInt() and 0xFF

    /** The ink set for [FrameFormats], or `null` for a scheme this app does not draw. */
    val colours: ColourScheme?
        get() = when (scheme) {
            0 -> ColourScheme.BW
            1 -> ColourScheme.BWR
            2 -> ColourScheme.BWY
            4 -> ColourScheme.E6
            5 -> ColourScheme.G4
            else -> null
        }

    /** What the frame can show, for [FrameFormats.find]; `null` if [rejection] says why not. */
    val caps: FrameCaps? get() = colours?.let { FrameCaps(width.takeIf { it > 0 }, height.takeIf { it > 0 }, it, rotation) }

    /** Why this frame is out regardless of its caps, or `null`. */
    val rejection: Txt?
        get() = when {
            colours == null -> Txt(R.string.reason_od_scheme, scheme)
            // these panels keep the two greys the other way round (the reference sender's per-panel table)
            colours == ColourScheme.G4 && panel in GREYS_SWAPPED -> Txt(R.string.reason_od_panel, panel)
            // the frame pads each row to a whole byte, [Nibbles4] packs one stream: the same only at even widths
            colours == ColourScheme.E6 && width % 2 != 0 -> Txt(R.string.reason_colours_size, "E6", width, height)
            else -> null
        }

    val hex: String get() = FrameAdvert.hex(raw)

    override fun equals(other: Any?) = other is OdDisplay && raw.contentEquals(other.raw)
    override fun hashCode() = raw.contentHashCode()
    override fun toString() = "OdDisplay(panel=$panel ${width}x$height rotation=$rotation scheme=$scheme)"

    companion object {
        private val GREYS_SWAPPED = setOf(0x28, 0x48)

        fun parseHex(s: String?): OdDisplay? =
            s?.let(FrameAdvert::unhex)?.takeIf { it.size >= 23 }?.let(::OdDisplay)

        /** The [FormatLookup] of a frame reporting [d]: its own [rejection] first, then [FrameFormats.find]. */
        fun lookup(d: OdDisplay?): FormatLookup = when {
            d == null -> FormatLookup.Unsupported(Txt(R.string.reason_no_caps))
            else -> d.rejection?.let { FormatLookup.Unsupported(it) } ?: FrameFormats.find(d.caps, FrameKind.OPENDISPLAY)
        }
    }
}

/**
 * What an OpenDisplay frame advertises (manufacturer data [Od.COMPANY_ID], the 14-byte v1 layout): battery
 * voltage and chip temperature; button and sensor bytes are not read. `null` from [parse] for anything else.
 */
data class OdAdvert(val batteryMv: Int, val temperatureC: Double) {
    /** The parts of one line for the frame list, e.g. "OpenDisplay · battery 4.12 V". */
    fun describe(): List<Txt> = listOf(Txt(R.string.od_name), Txt(R.string.od_battery, batteryMv / 1000, batteryMv % 1000 / 10))

    companion object {
        fun parse(b: ByteArray?): OdAdvert? {
            if (b == null || b.size < 14) return null
            val mv = ((b[12].toInt() and 0xFF) or ((b[13].toInt() and 0x01) shl 8)) * 10
            return OdAdvert(mv, (b[11].toInt() and 0xFF) / 2.0 - 40)
        }
    }
}
