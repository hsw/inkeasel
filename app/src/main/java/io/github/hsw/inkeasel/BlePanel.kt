package io.github.hsw.inkeasel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

/**
 * The Bluetooth panel ([BleAsk.panel]): why the app needs the permission, before the system dialog. A small
 * dialog over the catalog, never over the settings. The affirmative button ("Find my frame" or "Allow",
 * [onConfirm]) takes the D-pad focus; "Not now" ([onDismiss]) and Back close it. The content scrolls, for a
 * phone in landscape.
 */
@Composable
fun BlePanel(panel: BleAsk.Panel, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos {}
            runCatching { focus.requestFocus() }
        }
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp),
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text(panel.title.text(), style = MaterialTheme.typography.headlineSmall)
                for (t in panel.text) {
                    Spacer(Modifier.height(12.dp))
                    Text(t.text(), style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onConfirm, modifier = Modifier.focusRequester(focus).onTap(onConfirm)) {
                        Text(panel.confirm.text(), maxLines = 1)
                    }
                    Button(onClick = onDismiss, modifier = Modifier.onTap(onDismiss)) {
                        Text(panel.dismiss.text(), maxLines = 1)
                    }
                }
            }
        }
    }
}
