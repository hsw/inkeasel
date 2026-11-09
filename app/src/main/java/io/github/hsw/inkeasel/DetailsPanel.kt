package io.github.hsw.inkeasel

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "DetailsPanel"

/** Where the frame preview of the details panel is. */
private sealed interface PreviewImage {
    data object Loading : PreviewImage
    data class Ready(val bitmap: ImageBitmap) : PreviewImage
    data class Failed(val reason: Txt) : PreviewImage
}

/**
 * The details of picture [p], opened by a long press on its card: Google's "content details" layout for TV
 * (the text and the actions on the left, the picture large on the right), with every field the source gave
 * ([PictureDetails.rows]; the web page as text, since a TV launches no browser) and a preview drawn as the
 * [selected] frame will show it ([EpdImage.previewOf]: fitted whole with white bars, in its inks), or why there
 * is none. [onSend] ("Send to frame", focused first) takes the same path as a plain press on the card; "View
 * full screen" ([onView]) opens [FullscreenViewer]; Back closes ([onClose]). A full-screen dialog over the
 * catalog; on a narrow or portrait window everything is one scrolling column, the actions under the title.
 */
@Composable
fun DetailsPanel(
    p: Picture,
    selected: SelectedFrame?,
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
        val plan = PictureDetails.preview(selected)
        val image = previewImage(p, plan)
        Surface(Modifier.fillMaxSize(), shape = RectangleShape) {
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
                        Column(Modifier.weight(0.55f).fillMaxHeight()) {
                            Caption(stringResource(R.string.details_original))
                            // half the height each with a preview; without one, the note follows the picture
                            Original(p, Modifier.weight(1f, fill = plan is PictureDetails.Preview.Render).fillMaxWidth())
                            Spacer(Modifier.height(16.dp))
                            Preview(plan, image, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                } else {
                    val aspect = (plan as? PictureDetails.Preview.Render)?.format?.aspect ?: FrameFormats.DEFAULT.aspect
                    val own = if (p.width != null && p.height != null) p.width.toFloat() / p.height else aspect
                    Column(pad.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Title(p)
                        Spacer(Modifier.height(16.dp))
                        actions()
                        Spacer(Modifier.height(20.dp))
                        Caption(stringResource(R.string.details_original))
                        Original(p, Modifier.fillMaxWidth().aspectRatio(own))
                        Spacer(Modifier.height(16.dp))
                        Preview(plan, image, Modifier.fillMaxWidth().aspectRatio(aspect))
                        Spacer(Modifier.height(20.dp))
                        Rows(p)
                    }
                }
            }
        }
    }
}

/** Renders the preview of [p] for [plan] off the main thread; cancelled (with its download) when the panel closes. */
@Composable
private fun previewImage(p: Picture, plan: PictureDetails.Preview): PreviewImage {
    val format = (plan as? PictureDetails.Preview.Render)?.format
    val state by produceState<PreviewImage>(PreviewImage.Loading, p.fullUrl, format) {
        if (format == null) return@produceState
        value = PreviewImage.Loading
        value = try {
            val bytes = p.fullBytes()
            PreviewImage.Ready(withContext(Dispatchers.Default) { EpdImage.previewOf(bytes, format) }.asImageBitmap())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "preview of ${p.id} failed", e)
            PreviewImage.Failed(PictureDetails.previewFailure(e))
        }
    }
    return state
}

@Composable
private fun Title(p: Picture) {
    Text(p.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 6.dp),
    )
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

@Composable
private fun Original(p: Picture, modifier: Modifier) {
    AsyncImage(
        model = p.thumbModel(),
        contentDescription = p.title,
        contentScale = ContentScale.Fit,
        alignment = Alignment.TopStart,
        modifier = modifier,
    )
}

/** The frame preview with its caption, or the text that stands for it (no frame, not supported, failed, loading). */
@Composable
private fun Preview(plan: PictureDetails.Preview, image: PreviewImage, modifier: Modifier) {
    val note = @Composable { t: String -> Text(t, style = MaterialTheme.typography.bodyMedium) }
    when (plan) {
        PictureDetails.Preview.NoFrame -> note(stringResource(R.string.details_preview_none))
        is PictureDetails.Preview.Unsupported -> note(plan.reason.text())
        is PictureDetails.Preview.Render -> {
            Caption(stringResource(R.string.details_preview, plan.frameName))
            Box(modifier) {
                when (image) {
                    PreviewImage.Loading -> note(stringResource(R.string.details_preview_loading))
                    is PreviewImage.Failed -> note(image.reason.text())
                    is PreviewImage.Ready -> Image(
                        bitmap = image.bitmap,
                        contentDescription = stringResource(R.string.details_preview, plan.frameName),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.TopStart,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
