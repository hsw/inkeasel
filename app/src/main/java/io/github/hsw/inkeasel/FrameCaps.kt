package io.github.hsw.inkeasel

/**
 * Ink sets of e-paper panels: the NowMean vendor app's names (docs/protocol.md#manufacturer-data), plus G4,
 * four greys (an OpenDisplay colour scheme).
 */
enum class ColourScheme { BW, BWR, BWY, E4, E6, E7, BP, G4 }

/**
 * What a frame can show, whatever protocol reported it: [FrameFormats.find] picks the render format from
 * this alone. A NowMean frame's caps come from its advertisement ([FrameAdvert.caps]); a protocol that
 * reports them only after connecting fills them from there (an OpenDisplay frame's [OdDisplay.caps]). `null`
 * [width]/[height]/[rotation] mean the frame reported a value this app does not know.
 */
data class FrameCaps(val width: Int?, val height: Int?, val colours: ColourScheme, val rotation: Int?)

/**
 * Which protocol a frame speaks; saved with the chosen frame and carried by every scan result, so a second
 * kind of frame can be added next to this one. [id] is what goes into prefs.
 */
enum class FrameKind(val id: String) {
    /** BLE service 0xFFBB, manufacturer data 0xABC8 (docs/protocol.md). */
    NOWMEAN("nowmean"),

    /**
     * BLE service and manufacturer data 0x2446 (docs/protocol.md#opendisplay); it reports its panel only once
     * connected.
     */
    OPENDISPLAY("opendisplay");

    companion object {
        fun of(id: String): FrameKind? = entries.firstOrNull { it.id == id }
    }
}
