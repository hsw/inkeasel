package io.github.hsw.inkeasel

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.Button
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val TAG = "FullscreenViewer"

/** Keys that stay the system's: the viewer neither acts on them nor takes them (Back is the viewer's own). */
private val SYSTEM_KEYS = setOf(Key.VolumeUp, Key.VolumeDown, Key.VolumeMute, Key.Home, Key.Power)

/**
 * A picture full screen ([ViewerState]), opened by a press on its card or by "View full screen" in the details
 * panel: letterboxed on black and fitted to the screen, no crop, in a rendition sized to the window
 * ([ViewerRules.rendition]) and decoded to the window's size; the card's thumbnail stands in while it loads.
 * Opening it sends nothing. It opens clean, the picture alone ("as in a museum": no caption, counter or hint).
 * Left/Right (a swipe on a touch screen) go to the previous and next picture of the same list ([onStep]; the
 * catalog's from the database, [ViewerSteps]). Down or CENTER (a tap) shows the picture's card over its lower
 * part ([ViewerOverlay]): its details ([PictureRows]: artist, date, source, licence, image credit, …) and
 * "Send to frame" ([onSend], the card's own send path), focused first, with a frame chosen, otherwise a note
 * that none is. Up or Back (a tap) hides it; Left/Right go on stepping under it. A CENTER press the viewer
 * handles itself (the one that shows the card, or one still held when the viewer opens) never also presses the
 * card's button ([HeldCenter]). Back without the card closes ([onClose]) in one press. The screen is not kept
 * on: a still picture lets the screen saver start as usual.
 */
@Composable
fun FullscreenViewer(
    state: ViewerState,
    selected: SelectedFrame?,
    onStep: (Int) -> Unit,
    onSend: (Picture) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        EdgeToEdgeDialogWindow()
        ImmersiveDialogWindow()
        val p = state.picture
        var overlay by remember { mutableStateOf(ViewerOverlay.HIDDEN) }
        val held = remember { HeldCenter() }
        val currentStep by rememberUpdatedState(onStep) // nothing past either end of the list
        val root = remember { FocusRequester() }
        val send = remember { FocusRequester() }
        LaunchedEffect(overlay, selected != null) {
            withFrameNanos {}
            // the card's button takes the focus while it is shown; the picture itself the rest of the time
            runCatching { if (overlay == ViewerOverlay.CARD && selected != null) send.requestFocus() else root.requestFocus() }
        }
        val swipeMin = with(LocalDensity.current) { 64.dp.toPx() }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { e ->
                    val down = e.type == KeyEventType.KeyDown
                    when {
                        e.key == Key.DirectionLeft || e.key == Key.DirectionRight -> {
                            if (down) onStep(if (e.key == Key.DirectionRight) 1 else -1)
                            true
                        }
                        // Back hides the card, or closes; taken here also while the card's button has the focus
                        // (Compose would take that Back to move the focus out of the button first)
                        e.key == Key.Back || e.key == Key.Escape -> {
                            if (e.type == KeyEventType.KeyUp) overlay.onBack()?.let { overlay = it } ?: onClose()
                            true
                        }
                        e.key in SYSTEM_KEYS -> false
                        e.key in CENTER_KEYS && (down || e.type == KeyEventType.KeyUp) &&
                            held.swallow(down, e.nativeKeyEvent.repeatCount) -> true
                        overlay == ViewerOverlay.HIDDEN && (e.key == Key.DirectionDown || e.key in CENTER_KEYS) -> {
                            if (down) {
                                overlay = overlay.onShow()
                                // CENTER only shows the card: its repeats and release must not press its button
                                if (e.key in CENTER_KEYS) held.arm()
                            }
                            true
                        }
                        // the card has one row of buttons: Up from it hides the card
                        overlay == ViewerOverlay.CARD && e.key == Key.DirectionUp -> {
                            if (down) overlay = overlay.onHide()
                            true
                        }
                        // nothing above or below to move the focus to
                        e.key == Key.DirectionUp || e.key == Key.DirectionDown -> true
                        else -> false
                    }
                }
                .focusRequester(root)
                .focusable()
                .pointerInput(Unit) { detectTapGestures(onTap = { overlay = overlay.onTap() }) }
                .pointerInput(Unit) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = { ViewerRules.swipe(dx, swipeMin).takeIf { it != 0 }?.let(currentStep) },
                        onDragCancel = { dx = 0f },
                    ) { change, amount ->
                        change.consume()
                        dx += amount
                    }
                },
        ) {
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            var failure by remember(p.id) { mutableStateOf<Txt?>(null) }
            key(p.id) { FittedPicture(p, w, h) { failure = it } }
            if (overlay == ViewerOverlay.CARD) PictureCardOverlay(p, state, selected, failure, send, onSend)
        }
    }
}

/**
 * [p] whole, fitted to an area of [w] × [h] px (the viewer's window, the details panel's picture area) at
 * [alignment]: its thumbnail first (already in Coil's cache from the grid), the rendition for that area
 * ([ViewerRules.rendition]) over it once loaded, decoded to at most the area's size turned landscape (so a
 * rotation decodes nothing again). A failed load leaves the thumbnail and reports why ([onFailure]).
 */
@Composable
fun FittedPicture(p: Picture, w: Int, h: Int, alignment: Alignment = Alignment.Center, onFailure: (Txt) -> Unit = {}) {
    val ctx = LocalContext.current
    val url = remember(p.id, w, h) { ViewerRules.rendition(p, w, h) }
    val request = remember(url) {
        Log.i(TAG, "loading ${p.id}: $url")
        ImageRequest.Builder(ctx)
            .data(if (p.local) File(url) else url)
            .size(max(w, h), min(w, h))
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .build()
    }
    if (p.thumbUrl != url) {
        AsyncImage(
            model = p.thumbModel(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alignment = alignment,
            modifier = Modifier.fillMaxSize(),
        )
    }
    AsyncImage(
        model = request,
        contentDescription = p.title,
        contentScale = ContentScale.Fit,
        alignment = alignment,
        modifier = Modifier.fillMaxSize(),
        onError = { err ->
            val e = err.result.throwable
            Log.w(TAG, "${p.id}: $url failed", e)
            onFailure(ViewerRules.failure((e as? coil.network.HttpException)?.let { HttpStatusException(it.response.code, url) } ?: e))
        },
    )
}

/**
 * The picture's card over the lower part of the viewer, like a museum label beside the work: the title (and
 * its place in the list), the details ([PictureRows], scrolling when they are long; the credit the licences
 * ask for is among them), why the full picture did not load, and the action row.
 */
@Composable
private fun PictureCardOverlay(
    p: Picture,
    state: ViewerState,
    selected: SelectedFrame?,
    failure: Txt?,
    send: FocusRequester,
    onSend: (Picture) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rowsMax = maxHeight * 0.4f
        val scrim = Color.Black.copy(alpha = 0.9f)
        BoxWithConstraints(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.25f to scrim, 1f to scrim))
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val layout = CatalogLayout.forWidth(maxWidth.value.toInt())
            val dim = Color.White.copy(alpha = 0.8f)
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column(Modifier.padding(start = layout.sidePadding.dp, end = layout.sidePadding.dp, top = 64.dp, bottom = layout.verticalPadding.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            p.title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.showsPosition) {
                            Spacer(Modifier.width(16.dp))
                            Text(
                                stringResource(R.string.viewer_position, state.index + 1, state.count),
                                style = MaterialTheme.typography.bodyMedium,
                                color = dim,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.heightIn(max = rowsMax).verticalScroll(rememberScrollState())) { PictureRows(p) }
                    if (failure != null) {
                        Text(failure.text(), style = MaterialTheme.typography.bodyMedium, color = dim, maxLines = 2)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selected != null) {
                            val press = { onSend(p) }
                            Button(onClick = press, modifier = Modifier.focusRequester(send).onTap(press)) {
                                Text(stringResource(R.string.details_send), maxLines = 1)
                            }
                        } else {
                            Text(stringResource(R.string.viewer_no_frame), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The viewer's dialog window hides the status and navigation bars (a swipe from an edge shows them for a
 * moment); they return with the activity's window when the viewer closes. A TV has none.
 */
@Composable
private fun ImmersiveDialogWindow() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    DisposableEffect(window) {
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {}
    }
}
