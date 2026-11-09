package io.github.hsw.inkeasel

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val TAG = "FullscreenViewer"

/** Keys that stay the system's: they neither show the overlay nor are taken from it (Back closes the viewer). */
private val SYSTEM_KEYS = setOf(Key.VolumeUp, Key.VolumeDown, Key.VolumeMute, Key.Home, Key.Power)

/**
 * A picture full screen ([ViewerState]), opened by a press on its card or by "View full screen" in the details
 * panel: letterboxed on black and fitted to the screen, no crop, in a rendition sized to the window
 * ([ViewerRules.rendition]) and decoded to the window's size; the card's thumbnail stands in while it loads.
 * Left/Right (a swipe on a touch screen) go to the previous and next picture of the same list ([onStep]; the
 * catalog's from the database, [ViewerSteps]); any other key, or a tap, shows the action row: "Send to frame"
 * ([onSend], the card's own send path) with a frame chosen, otherwise a note that none is. The credit line
 * (title, artist, source, licence) shows on open ([start]: the action row too when [ViewerOverlay.onOpen] says
 * so), on every picture change and with the action row, and hides after [ViewerOverlay.SHOWN_MS]. A CENTER
 * press it handles itself (the one that shows the row, or one still held when it opens) never also presses the
 * row's button ([HeldCenter]). Back closes ([onClose]) in one press, also while the row's button has the focus.
 * The screen is not kept on: a still picture lets the screen saver start as usual.
 */
@Composable
fun FullscreenViewer(
    state: ViewerState,
    selected: SelectedFrame?,
    start: ViewerOverlay,
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
        var overlay by remember { mutableStateOf(start) }
        var shown by remember { mutableIntStateOf(0) } // bumped by every key, tap or step: restarts the hide timer
        val held = remember { HeldCenter() }
        val step = { delta: Int ->
            onStep(delta) // nothing past either end of the list
            overlay = overlay.onStep()
            shown++
        }
        val currentStep by rememberUpdatedState(step)
        val root = remember { FocusRequester() }
        val send = remember { FocusRequester() }
        LaunchedEffect(overlay == ViewerOverlay.ACTIONS, selected != null) {
            withFrameNanos {}
            // the action row's button takes the focus while it is shown; the picture itself the rest of the time
            runCatching { if (overlay == ViewerOverlay.ACTIONS && selected != null) send.requestFocus() else root.requestFocus() }
        }
        LaunchedEffect(overlay, shown) {
            if (overlay != ViewerOverlay.HIDDEN) {
                delay(ViewerOverlay.SHOWN_MS)
                overlay = ViewerOverlay.HIDDEN
            }
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
                            if (down) step(if (e.key == Key.DirectionRight) 1 else -1)
                            true
                        }
                        // Back closes at once, also while the row's button has the focus (Compose would take that
                        // Back to move the focus out of the button to the picture, so leaving took two presses)
                        e.key == Key.Back || e.key == Key.Escape -> {
                            if (e.type == KeyEventType.KeyUp) onClose()
                            true
                        }
                        e.key in SYSTEM_KEYS -> false
                        e.key in CENTER_KEYS && (down || e.type == KeyEventType.KeyUp) &&
                            held.swallow(down, e.nativeKeyEvent.repeatCount) -> true
                        down && overlay != ViewerOverlay.ACTIONS -> {
                            overlay = overlay.onKey()
                            shown++
                            // CENTER only shows the row; its repeats and release must not press the button it brings
                            if (e.key in CENTER_KEYS) held.arm()
                            e.key in CENTER_KEYS
                        }
                        else -> {
                            if (down) shown++
                            false
                        }
                    }
                }
                .focusRequester(root)
                .focusable()
                .pointerInput(Unit) { detectTapGestures(onTap = { overlay = overlay.onTap(); shown++ }) }
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
            key(p.id) { ViewerImage(p, w, h) { failure = it } }
            Overlay(p, state, selected, overlay, failure, send, onSend)
        }
    }
}

/**
 * [p] fitted to a window of [w] × [h] px: its thumbnail first (already in Coil's cache from the grid), the
 * rendition over it once loaded, decoded to at most the window's size turned landscape (so a rotation decodes
 * nothing again). A failed load leaves the thumbnail and reports why ([onFailure]).
 */
@Composable
private fun ViewerImage(p: Picture, w: Int, h: Int, onFailure: (Txt) -> Unit) {
    val ctx = LocalContext.current
    val url = remember(p.id, w, h) { ViewerRules.rendition(p, w, h) }
    val request = remember(url) {
        Log.i(TAG, "viewing ${p.id}: $url")
        ImageRequest.Builder(ctx)
            .data(if (p.local) File(url) else url)
            .size(max(w, h), min(w, h))
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .build()
    }
    if (p.thumbUrl != url) {
        AsyncImage(model = p.thumbModel(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
    AsyncImage(
        model = request,
        contentDescription = p.title,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
        onError = { err ->
            val e = err.result.throwable
            Log.w(TAG, "${p.id}: $url failed", e)
            onFailure(ViewerRules.failure((e as? coil.network.HttpException)?.let { HttpStatusException(it.response.code, url) } ?: e))
        },
    )
}

/** The credit line (and why the full picture did not load), with the action row under it when [overlay] says so. */
@Composable
private fun Overlay(
    p: Picture,
    state: ViewerState,
    selected: SelectedFrame?,
    overlay: ViewerOverlay,
    failure: Txt?,
    send: FocusRequester,
    onSend: (Picture) -> Unit,
) {
    if (overlay == ViewerOverlay.HIDDEN) return
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val layout = CatalogLayout.forWidth(maxWidth.value.toInt())
            Column(Modifier.padding(start = layout.sidePadding.dp, end = layout.sidePadding.dp, top = 48.dp, bottom = layout.verticalPadding.dp)) {
                val dim = Color.White.copy(alpha = 0.8f)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        p.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
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
                val credit = ViewerRules.credit(p)
                if (credit.isNotEmpty()) {
                    val sep = stringResource(R.string.list_separator)
                    Text(
                        credit.map { (it as? Txt)?.text() ?: it.toString() }.joinToString(sep),
                        style = MaterialTheme.typography.bodyMedium,
                        color = dim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (failure != null) {
                    Text(failure.text(), style = MaterialTheme.typography.bodyMedium, color = dim, maxLines = 2)
                }
                if (overlay == ViewerOverlay.ACTIONS) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selected != null) {
                            val press = { onSend(p) }
                            Button(onClick = press, modifier = Modifier.focusRequester(send).onTap(press)) {
                                Text(stringResource(R.string.details_send), maxLines = 1)
                            }
                        } else {
                            Text(stringResource(R.string.viewer_no_frame), style = MaterialTheme.typography.bodyMedium, color = Color.White)
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
