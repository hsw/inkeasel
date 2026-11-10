package io.github.hsw.inkeasel

import android.content.Context
import android.content.SharedPreferences

/**
 * One frame heard in a BLE scan: its advertised [name] (the identity), [address] (may change), [rssi] in dBm,
 * and the protocol it speaks ([kind]).
 */
data class SeenFrame(
    val name: String,
    val address: String,
    val rssi: Int,
    val advert: FrameAdvert?,
    val kind: FrameKind = FrameKind.NOWMEAN,
    /** An OpenDisplay frame's advertisement ([FrameKind.OPENDISPLAY]; [advert] is a NowMean one's). */
    val od: OdAdvert? = null,
) {
    /**
     * How to draw on it ([FrameFormats]); a frame without one cannot be chosen. `null`: the frame reports its
     * panel only once connected (OpenDisplay), so choosing it asks it first.
     */
    val lookup: FormatLookup?
        get() = when (kind) {
            FrameKind.NOWMEAN -> FrameAdvert.lookup(advert)
            FrameKind.OPENDISPLAY -> null
        }

    /** Its advertisement in parts for the frame list, or `null` if it sent none this app reads. */
    fun describe(): List<Txt>? = when (kind) {
        FrameKind.NOWMEAN -> advert?.describe()
        FrameKind.OPENDISPLAY -> od?.describe() ?: listOf(Txt(R.string.od_name))
    }

    companion object {
        /**
         * The scan list with [seen] in it: a frame already listed under the same name is replaced (its address,
         * signal and advertisement are the latest), others are kept. Sorted by name, so live updates do not
         * move the entries under the D-pad focus.
         */
        fun merge(list: List<SeenFrame>, seen: SeenFrame): List<SeenFrame> =
            (list.filter { it.name != seen.name } + seen).sortedBy { it.name }
    }
}

/**
 * The frame chosen in the settings: uploads go to it only. [name] is its identity (docs/protocol.md#advertising:
 * the vendor app keys frames by name, the MAC may change); [address] is where it was last seen, [advert] what it
 * advertised then, [seenAt] when (wall clock, ms).
 */
data class SelectedFrame(
    val name: String,
    val address: String,
    val advert: FrameAdvert?,
    val seenAt: Long,
    val kind: FrameKind = FrameKind.NOWMEAN,
    /** What an OpenDisplay frame reported about its panel when it was chosen or last connected. */
    val display: OdDisplay? = null,
) {
    /** How to draw on it ([FrameFormats]): uploads render with this format. */
    val lookup: FormatLookup
        get() = when (kind) {
            FrameKind.NOWMEAN -> FrameAdvert.lookup(advert)
            FrameKind.OPENDISPLAY -> OdDisplay.lookup(display)
        }

    /**
     * The pref entries of this frame (see [decode]). The raw advertisement (OpenDisplay: the raw display
     * record) is the source of truth; its parsed properties are written next to it only so the prefs file is
     * readable on its own.
     */
    fun encode(): Map<String, Any> = buildMap {
        put(KIND, kind.id)
        put(NAME, name)
        put(ADDRESS, address)
        put(SEEN_AT, seenAt)
        display?.let { d ->
            put(DISPLAY, d.hex)
            put(PANEL, d.panel.toString())
            put(COLOUR, d.colours?.name ?: d.scheme.toString())
            put(WIDTH, d.width)
            put(HEIGHT, d.height)
            d.rotation?.let { put(ROTATION, it) }
        }
        advert?.let { a ->
            put(ADVERT, a.hex)
            put(COLOUR, a.colour.name)
            put(PANEL, a.panel.name)
            a.width?.let { put(WIDTH, it) }
            a.height?.let { put(HEIGHT, it) }
            a.rotation?.let { put(ROTATION, it) }
            put(BATTERY, a.battery)
            a.firmware?.let { put(FIRMWARE, it) }
            put(SAFE_MODE, a.safeMode)
        }
    }

    companion object {
        /**
         * Pref keys. They differ from the `address`/`name` the app wrote before it had a picker, so a frame
         * picked automatically then does not count as chosen.
         */
        private const val P = "selected."
        const val KIND = P + "kind"
        const val NAME = P + "name"
        const val ADDRESS = P + "address"
        const val SEEN_AT = P + "seenAt"
        const val ADVERT = P + "advert"
        const val DISPLAY = P + "display"
        const val COLOUR = P + "colour"
        const val PANEL = P + "panel"
        const val WIDTH = P + "width"
        const val HEIGHT = P + "height"
        const val ROTATION = P + "rotation"
        const val BATTERY = P + "battery"
        const val FIRMWARE = P + "firmware"
        const val SAFE_MODE = P + "safeMode"

        fun isKey(key: String) = key.startsWith(P)

        /**
         * Read the frame back from pref entries written by [encode]; `null` if there is none, it is corrupt, or
         * it is of a kind this build does not know.
         */
        fun decode(prefs: Map<String, *>): SelectedFrame? {
            val kind = FrameKind.of(prefs[KIND] as? String ?: return null) ?: return null
            val name = (prefs[NAME] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val address = (prefs[ADDRESS] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return SelectedFrame(
                name, address, FrameAdvert.parseHex(prefs[ADVERT] as? String), prefs[SEEN_AT] as? Long ?: 0L, kind,
                OdDisplay.parseHex(prefs[DISPLAY] as? String),
            )
        }
    }
}

/** The [SelectedFrame] in shared preferences `frame` (next to `DrawGate`'s `endedAt` and the `onFrame` key). */
class FrameStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("frame", Context.MODE_PRIVATE)

    fun selected(): SelectedFrame? = SelectedFrame.decode(prefs.all)

    /** Make [frame] the selected one, replacing every entry of the previous one. */
    fun select(frame: SelectedFrame) {
        val e = prefs.edit()
        prefs.all.keys.filter { SelectedFrame.isKey(it) || it in LEGACY }.forEach { e.remove(it) }
        for ((k, v) in frame.encode()) when (v) {
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Boolean -> e.putBoolean(k, v)
        }
        e.apply()
    }

    /**
     * The selected frame [name] was seen again at [address] with [advert] or [display] (`null`: keep the stored
     * one). Ignored if another frame was selected meanwhile.
     */
    fun seen(name: String, address: String, advert: FrameAdvert?, at: Long, display: OdDisplay? = null) {
        val cur = selected()?.takeIf { it.name == name } ?: return
        select(cur.copy(address = address, advert = advert ?: cur.advert, seenAt = at, display = display ?: cur.display))
    }

    private companion object {
        /** Written by the app before the picker: the first frame a scan found. */
        val LEGACY = setOf("address", "name")
    }
}
