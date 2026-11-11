package io.github.hsw.inkeasel

import io.github.hsw.inkeasel.catalog.CommonsUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
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

/**
 * The open viewer ([state]) and its moves, run in [scope]: [open] shows a picture at once and may read its
 * place in the list afterwards; [step] reads a neighbour, and a step pressed while the previous one (or the
 * place) is still being read is dropped (a held key repeats faster); a result is applied only while the viewer
 * still shows what it was read from, so a late one never replaces a newer picture, a closed viewer or one
 * opened again. A neighbour shows only if it passes [gate] (the nudity filter at the level stored now: an
 * import may raise it after the step read it). Every change of [state] goes to [onChange] (the screen's
 * state). Pure.
 */
class ViewerNav(
    private val scope: CoroutineScope,
    private val gate: suspend (Picture) -> Boolean = { true },
    private val onChange: (ViewerState?) -> Unit = {},
) {
    var state: ViewerState? = null
        private set(value) {
            field = value
            onChange(value)
        }

    private var steps: ViewerSteps? = null
    private var move: Job? = null

    /**
     * Shows [shown] at once, stepping through [steps]. [place], when given, reads [shown]'s place in the list
     * (`null`: it stays alone), applied only while the viewer still shows [shown].
     */
    fun open(shown: ViewerState, steps: ViewerSteps, place: (suspend () -> ViewerState?)? = null) {
        move?.cancel()
        this.steps = steps
        state = shown
        if (place != null) {
            move = scope.launch {
                val placed = place() ?: return@launch
                if (state == shown) state = placed
            }
        }
    }

    /** Left/Right: the picture [delta] places on; nothing past either end, or while a read is running. */
    fun step(delta: Int) {
        val from = state ?: return
        val steps = steps ?: return
        if (move?.isActive == true) return
        move = scope.launch {
            val to = steps.step(from, delta) ?: return@launch
            if (gate(to.picture) && state == from) state = to
        }
    }

    /** Closes the viewer, dropping a read still running; the picture it showed, if it was open. */
    fun close(): Picture? {
        move?.cancel()
        val shown = state?.picture
        steps = null
        if (state != null) state = null
        return shown
    }
}

/**
 * Whether the viewer shows the picture's card over the picture: the viewer opens clean, the picture alone on
 * black ("as in a museum"); Down or CENTER brings the card (the details and "Send to frame"), Up or Back takes
 * it away; Left/Right step to another picture and leave it as it is; a tap toggles it. Pure.
 */
enum class ViewerOverlay {
    /** The picture alone: no caption, counter or hint. */
    HIDDEN,

    /** The card: the picture's details and the action row, its first button focused. */
    CARD;

    /** Down or CENTER: the card shows (with the card shown, the keys are its own). */
    fun onShow(): ViewerOverlay = CARD

    /** Up from the card's top row: it hides. */
    fun onHide(): ViewerOverlay = HIDDEN

    /** Back: hides the card, or closes the viewer (`null`) when none is shown. */
    fun onBack(): ViewerOverlay? = if (this == CARD) HIDDEN else null

    /** A tap toggles the card. */
    fun onTap(): ViewerOverlay = if (this == CARD) HIDDEN else CARD
}

/** What a swipe on the viewer does ([ViewerRules.swipe]). */
enum class ViewerSwipe {
    NONE,

    /** To the next picture of the list. */
    NEXT,

    /** To the previous picture. */
    PREVIOUS,

    /** What Back does: hides the card, or closes the viewer when none is shown. */
    BACK,
}

/**
 * Which rendition the viewer loads and what it says about the picture. Pure: a window size in pixels in, a URL
 * (or a local file path) and [Txt] parts out.
 */
object ViewerRules {
    /**
     * The narrowest Commons rendition the viewer loads: the 960 px step of Wikimedia's standard thumbnail
     * widths ([CommonsUrls.WIDTHS]; a request for any other width is refused with HTTP 400 or 429).
     */
    const val MIN_COMMONS_WIDTH = 960

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
     * with a [Picture.commonsFile] its [CommonsUrls.url] for [neededWidth], at least [MIN_COMMONS_WIDTH] (the
     * original when that is a JPEG, PNG or WebP no wider, a TIFF always as a thumbnail): the release's rows
     * and the Commons source's; for Cleveland `web` ([Picture.thumbUrl]) up to [CLEVELAND_WEB_UP_TO] and
     * `print` ([Picture.fullUrl]) beyond; any other source its [Picture.fullUrl].
     */
    fun rendition(p: Picture, w: Int, h: Int): String = when {
        p.local -> p.fullUrl
        p.commonsFile != null -> CommonsUrls.url(p.commonsFile, max(neededWidth(p, w, h), MIN_COMMONS_WIDTH), p.width)
        p.source == ClevelandPaintings.id -> if (min(w, h) > CLEVELAND_WEB_UP_TO) p.fullUrl else p.thumbUrl
        else -> p.fullUrl
    }

    /**
     * Why the rendition did not load (the thumbnail stays): a download failure as the catalog says it
     * ([CatalogError]), anything else (e.g. an undecodable file) after "Could not load the picture:".
     */
    fun failure(e: Throwable): Txt =
        CatalogError.of(e) ?: e.userText(R.string.viewer_failed)

    /**
     * What a swipe of [dx] × [dy] px does, by its longer axis, once that is at least [threshold] px:
     * sideways the next picture (a swipe to the left) or the previous one (to the right); down what Back does;
     * up nothing.
     */
    fun swipe(dx: Float, dy: Float, threshold: Float): ViewerSwipe = when {
        abs(dx) >= abs(dy) -> when {
            dx <= -threshold -> ViewerSwipe.NEXT
            dx >= threshold -> ViewerSwipe.PREVIOUS
            else -> ViewerSwipe.NONE
        }
        dy >= threshold -> ViewerSwipe.BACK
        else -> ViewerSwipe.NONE
    }
}
