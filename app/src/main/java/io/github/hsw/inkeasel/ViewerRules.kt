package io.github.hsw.inkeasel

import io.github.hsw.inkeasel.catalog.CommonsUrls
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * The open full-screen viewer ([FullscreenViewer]): the [picture] shown, at the 0-based [index] of the list it
 * was opened from, which holds [count] pictures. [rank] is a catalog picture's place in the catalog's order
 * (its neighbours are read from the database by it); `null` for a picture of a list in memory.
 */
data class ViewerState(val picture: Picture, val index: Int, val count: Int, val rank: Long? = null) {
    /** "n / N" shows only in a list of more than one picture. */
    val showsPosition: Boolean get() = count > 1

    companion object {
        /** [p] alone, until (or instead of) its place in a list. */
        fun alone(p: Picture) = ViewerState(p, 0, 1)
    }
}

/** How the viewer moves through the list it was opened from: Left/Right (or a swipe) stop at its ends. */
fun interface ViewerSteps {
    /** The state one picture on from [from] (+1 the next, -1 the previous); `null` past either end. */
    suspend fun step(from: ViewerState, delta: Int): ViewerState?
}

/** The steps of a list in memory (a tab's loaded list, as My pictures has). Pure. */
class ListSteps(private val items: List<Picture>) : ViewerSteps {
    /** The viewer on [p] within the list; a picture no longer in it is shown alone. */
    fun open(p: Picture): ViewerState {
        val i = items.indexOfFirst { it.id == p.id }
        return if (i >= 0) ViewerState(items[i], i, items.size) else ViewerState.alone(p)
    }

    override suspend fun step(from: ViewerState, delta: Int): ViewerState? {
        if (items.getOrNull(from.index)?.id != from.picture.id) return null // shown alone
        val i = from.index + delta.sign
        return if (delta != 0 && i in items.indices) ViewerState(items[i], i, items.size) else null
    }
}

/** What the viewer shows over the picture. */
enum class ViewerOverlay {
    /** The picture alone. */
    HIDDEN,

    /** The credit line (title, artist, source, licence): on open and after Left/Right. */
    CREDIT,

    /** The credit line and the action row: after any other key, or a tap. */
    ACTIONS;

    /** Left/Right, or a swipe, moved to another picture: its credit shows; an open action row stays. */
    fun onStep(): ViewerOverlay = if (this == ACTIONS) ACTIONS else CREDIT

    /** Any key but Left/Right and Back (CENTER, Up, Down, …) shows the action row. */
    fun onKey(): ViewerOverlay = ACTIONS

    /** A tap toggles the action row (with the credit line): it shows it, also over the credit alone, or hides both. */
    fun onTap(): ViewerOverlay = if (this == ACTIONS) HIDDEN else ACTIONS

    companion object {
        /** How long the overlay stays after the last key, tap or step before it hides. */
        const val SHOWN_MS = 5_000L
    }
}

/**
 * Which rendition the viewer loads and what it says about the picture. Pure: a window size in pixels in, a URL
 * (or a local file path) and [Txt] parts out.
 */
object ViewerRules {
    /**
     * Wikimedia's standard thumbnail widths at or above the 960 px step (mediawiki.org "Common thumbnail
     * sizes"): a direct request for any other width is refused (HTTP 400 or 429).
     */
    val COMMONS_WIDTHS = listOf(960, 1280, 1920, 3840)

    /**
     * Cleveland's `web` image is about 893 px high; up to a Full HD window (short side 1080 px) it is shown
     * slightly enlarged, beyond that the `print` image (up to 3400 px wide) is loaded.
     */
    const val CLEVELAND_WEB_UP_TO = 1080

    /**
     * The width in pixels [p] needs to fill a window of [w] × [h] px, fitted (no crop), with the window turned
     * landscape: a landscape picture is never wider in portrait, so a rotation never needs another rendition.
     * Without a known size the picture is taken as wide as the window.
     */
    fun neededWidth(p: Picture, w: Int, h: Int): Int {
        val long = max(w, h)
        val short = min(w, h)
        val pw = p.width
        val ph = p.height
        if (pw == null || ph == null || pw <= 0 || ph <= 0) return long
        return min(long, ceil(short.toDouble() * pw / ph).toInt())
    }

    /**
     * What the viewer loads for [p] in a window of [w] × [h] px: the local file as is (its path); a picture
     * with a [Picture.commonsFile] its [CommonsUrls.url] for [neededWidth], at least the [COMMONS_WIDTHS] first
     * step (the original when that is a JPEG, PNG or WebP no wider, a TIFF always as a thumbnail); for the
     * Commons source the smallest [COMMONS_WIDTHS] step that covers [neededWidth], or the original's width
     * when that is smaller, 3840 at most, or the URL as given when it is the original itself (narrower than
     * the frame's 1920 px rendition); for Cleveland `web` ([Picture.thumbUrl]) up to [CLEVELAND_WEB_UP_TO] and
     * `print` ([Picture.fullUrl]) beyond; any other source its [Picture.fullUrl].
     */
    fun rendition(p: Picture, w: Int, h: Int): String = when {
        p.local -> p.fullUrl
        p.commonsFile != null -> CommonsUrls.url(p.commonsFile, max(neededWidth(p, w, h), COMMONS_WIDTHS.first()), p.width)
        p.source == "commons" -> commons(p, w, h)
        p.source == "cma" -> if (min(w, h) > CLEVELAND_WEB_UP_TO) p.fullUrl else p.thumbUrl
        else -> p.fullUrl
    }

    private fun commons(p: Picture, w: Int, h: Int): String {
        val frameStep = "/${CommonsMapping.FULL_WIDTH}px-"
        if (frameStep !in p.fullUrl) return p.fullUrl // the original, no wider than the frame's rendition
        val need = min(neededWidth(p, w, h), p.width ?: Int.MAX_VALUE)
        val step = COMMONS_WIDTHS.firstOrNull { it >= need } ?: COMMONS_WIDTHS.last()
        return p.fullUrl.replace(frameStep, "/${step}px-")
    }

    /**
     * The second line of the credit under the title: artist, the image's credit where its licence asks for one
     * ([Picture.attribution], as "image: …"), source, licence, each only when known; joined with
     * `list_separator` where shown. The licences of the sources (CC BY and BY-SA on Commons) ask for it
     * whenever the picture is shown.
     */
    fun credit(p: Picture): List<Any> = listOfNotNull(
        p.artist?.takeIf { it.isNotBlank() },
        p.attribution?.takeIf { it.isNotBlank() }?.let { Txt(R.string.viewer_attribution, it) },
        PictureDetails.sourceName(p.source),
        p.licence?.takeIf { it.isNotBlank() },
    )

    /**
     * Why the rendition did not load (the thumbnail stays): a download failure as the catalog says it
     * ([CatalogError]), anything else (e.g. an undecodable file) after "Could not load the picture:".
     */
    fun failure(e: Throwable): Txt =
        CatalogError.of(e).takeIf { it.id != R.string.catalog_failed } ?: e.userText(R.string.viewer_failed)

    /**
     * A horizontal swipe of [dx] px: -1 (to the previous picture, a swipe to the right) or +1 (to the next, a
     * swipe to the left) once it is longer than [threshold] px; 0 for a shorter one.
     */
    fun swipe(dx: Float, threshold: Float): Int = when {
        dx <= -threshold -> 1
        dx >= threshold -> -1
        else -> 0
    }
}
