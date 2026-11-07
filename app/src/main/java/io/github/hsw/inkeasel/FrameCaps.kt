package io.github.hsw.inkeasel

/** Ink sets of e-paper panels (the vendor app's names, docs/device-api.md §2). */
enum class ColourScheme { BW, BWR, BWY, E4, E6, E7, BP }

/**
 * What a frame can show, whatever protocol reported it: [FrameFormats.find] picks the render format from
 * this alone. A NowMean frame's caps come from its advertisement ([FrameAdvert.caps]); a protocol that
 * reports them only after connecting would fill them from there. `null` [width]/[height]/[rotation] mean the
 * frame reported a value this app does not know.
 */
data class FrameCaps(val width: Int?, val height: Int?, val colours: ColourScheme, val rotation: Int?)

/**
 * Which protocol a frame speaks; saved with the chosen frame and carried by every scan result, so a second
 * kind of frame can be added next to this one. [id] is what goes into prefs.
 */
enum class FrameKind(val id: String) {
    /** BLE service 0xFFBB, manufacturer data 0xABC8 (docs/device-api.md). */
    NOWMEAN("nowmean");

    companion object {
        fun of(id: String): FrameKind? = entries.firstOrNull { it.id == id }
    }
}
