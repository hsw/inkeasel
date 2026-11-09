package io.github.hsw.inkeasel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * The details of picture [p], opened by a long press on its card: Google's "content details" layout for TV
 * (the text and the actions on the left, the picture large on the right), with every field the source gave
 * ([PictureDetails.rows]; the web page as text, since a TV launches no browser) and the picture whole, fitted
 * into all of the right side ([FittedPicture]: the card's thumbnail, then a rendition for that area).
 * [onSend] ("Send to frame", focused first) takes the same send path as a plain press on the card; "View full
 * screen" ([onView]) opens [FullscreenViewer]; Back closes ([onClose]). The CENTER press that opened it may
 * still be held: its repeats and release are swallowed ([HeldCenter]), so the panel stays open with its first
 * button focused. A full-screen dialog over the catalog; on a narrow or portrait window everything is one
 * scrolling column, the actions under the title and the picture under them.
 */
@Composable
fun DetailsPanel(
    p: Picture,
    onSend: () -> Unit,
    onView: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        EdgeToEdgeDialogWindow()
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos {}
            runCatching { focus.requestFocus() }
        }
        // a long press opens the panel while CENTER is still down: its release must not press "Send to frame"
        val held = remember { HeldCenter() }
        Surface(Modifier.fillMaxSize().dropHeldCenter(held), shape = RectangleShape) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val layout = CatalogLayout.forWidth(maxWidth.value.toInt())
                val pad = Modifier.padding(horizontal = layout.sidePadding.dp, vertical = layout.verticalPadding.dp)
                val actions = @Composable {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onSend, modifier = Modifier.focusRequester(focus).onTap(onSend)) {
                            Text(stringResource(R.string.details_send), maxLines = 1)
                        }
                        Button(onClick = onView, modifier = Modifier.onTap(onView)) {
                            Text(stringResource(R.string.details_view), maxLines = 1)
                        }
                    }
                }
                if (maxWidth >= 600.dp && maxWidth >= maxHeight) {
                    Row(pad.fillMaxSize()) {
                        Column(Modifier.weight(0.45f).fillMaxHeight()) {
                            Title(p)
                            Spacer(Modifier.height(16.dp))
                            // the actions stay in view; the lines scroll if a large font pushes them down
                            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { Rows(p) }
                            Spacer(Modifier.height(20.dp))
                            actions()
                        }
                        Spacer(Modifier.width(32.dp))
                        // all of the content area's height: a portrait picture uses it whole
                        Large(p, Modifier.weight(0.55f).fillMaxHeight())
                    }
                } else {
                    val own = if (p.width != null && p.height != null && p.height > 0) p.width.toFloat() / p.height else null
                    Column(pad.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Title(p)
                        Spacer(Modifier.height(16.dp))
                        actions()
                        Spacer(Modifier.height(20.dp))
                        Large(p, Modifier.fillMaxWidth().aspectRatio(own ?: FrameFormats.DEFAULT.aspect))
                        Spacer(Modifier.height(20.dp))
                        Rows(p)
                    }
                }
            }
        }
    }
}

/** [p] whole in the area of [modifier], fitted (no crop) and placed at its top. */
@Composable
private fun Large(p: Picture, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        key(p.id) { FittedPicture(p, constraints.maxWidth, constraints.maxHeight, Alignment.TopCenter) }
    }
}

@Composable
private fun Title(p: Picture) {
    Text(p.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

/** The lines of [PictureDetails.rows]: the label in a column of its own, the value wrapping beside it. */
@Composable
private fun Rows(p: Picture) {
    for (r in PictureDetails.rows(p)) {
        Row(Modifier.padding(vertical = 3.dp)) {
            Text(
                r.label.text(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(136.dp).padding(end = 12.dp),
            )
            Text((r.value as? Txt)?.text() ?: r.value.toString(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
