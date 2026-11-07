package io.github.hsw.inkeasel

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date

private const val TAG = "FrameSettings"

/**
 * "Settings", section "Frame": a full-screen dialog over the catalog. While it is open it scans for frames
 * and lists them live (one entry per name); choosing a supported frame calls [onSelect], which saves it and
 * closes the dialog. "Cancel" and Back call [onCancel] and change nothing. The scan stops when the dialog
 * closes and while the app is in the background.
 *
 * A dialog rather than a separate screen: the catalog stays composed below it, so on close the D-pad focus
 * returns to where it was (the header button that opened it).
 */
@Composable
fun FrameSettingsDialog(
    selected: SelectedFrame?,
    permitted: Boolean,
    onRequestPermissions: () -> Unit,
    onSelect: (SeenFrame) -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), shape = RectangleShape) {
            FrameSettings(selected, permitted, onRequestPermissions, onSelect, onCancel)
        }
    }
}

@Composable
private fun FrameSettings(
    selected: SelectedFrame?,
    permitted: Boolean,
    onRequestPermissions: () -> Unit,
    onSelect: (SeenFrame) -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    val scanner = remember { FrameScanner(ctx) }
    var frames by remember { mutableStateOf(emptyList<SeenFrame>()) }
    var scanError by remember { mutableStateOf<Txt?>(null) }
    var btProblem by remember { mutableStateOf(scanner.unavailable()) }
    var scanRun by remember { mutableIntStateOf(0) } // bumped by "Retry" to restart the scan

    // Bluetooth switched on or off while the screen is open: start or drop the scan accordingly
    DisposableEffect(scanner) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                btProblem = scanner.unavailable()
            }
        }
        val f = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(r, f)
        onDispose { ctx.unregisterReceiver(r) }
    }

    if (permitted && btProblem == null && scanError == null) {
        LifecycleStartEffect(scanner, scanRun) {
            val stop = scanner.start(
                onSeen = { frames = SeenFrame.merge(frames, it) },
                onFailed = { scanError = it },
            )
            onStopOrDispose { stop() }
        }
    }

    // Focus: "Cancel" at first, so OK/Back work at once even before any frame shows up; then, once, the
    // chosen frame (given a moment to show up) or else the first one that can be chosen, unless the user
    // has already moved onto the list.
    val cancelFocus = remember { FocusRequester() }
    val targetFocus = remember { FocusRequester() }
    var listTouched by remember { mutableStateOf(false) }
    val supported = frames.filter { it.lookup is FormatLookup.Supported }
    val target = (supported.firstOrNull { it.name == selected?.name } ?: supported.firstOrNull())?.name
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { cancelFocus.requestFocus() }
    }
    LaunchedEffect(target) {
        if (target == null || listTouched) return@LaunchedEffect
        if (selected != null && target != selected.name) delay(1_500) // restarted if the chosen one shows up
        withFrameNanos {}
        if (!listTouched) runCatching { targetFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.settings_frame), style = MaterialTheme.typography.titleLarge)
        Text(
            selected?.let {
                listOfNotNull(
                    stringResource(R.string.frame_selected, it.name), it.address,
                    stringResource(R.string.frame_seen, seenAt(it.seenAt)),
                    if (it.lookup.formatOrNull?.verified == false) stringResource(R.string.frame_unverified) else null,
                ).joinToString(" · ")
            }
                ?: stringResource(R.string.frame_none_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !permitted -> Problem(
                    stringResource(R.string.ble_permission_needed), stringResource(R.string.allow), onRequestPermissions,
                )
                btProblem != null -> Problem("${btProblem!!.text().replaceFirstChar { it.uppercase() }}.", stringResource(R.string.bt_settings)) {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "no Bluetooth settings screen", e)
                    }
                }
                scanError != null -> Problem(txt(R.string.scan_failed, scanError!!).text(), stringResource(R.string.retry)) {
                    scanError = null
                    scanRun++
                }
                frames.isEmpty() -> Column {
                    Text(stringResource(R.string.scanning), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.scanning_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in frames) key(f.name) {
                        FrameItem(
                            f,
                            isSelected = f.name == selected?.name,
                            modifier = (if (f.name == target) Modifier.focusRequester(targetFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused) listTouched = true },
                        ) { reason ->
                            if (reason == null) {
                                if (f.lookup.formatOrNull?.verified == false) {
                                    val msg = txt(R.string.frame_toast_unverified, f.name, Txt(R.string.frame_unverified))
                                    Toast.makeText(ctx, msg.resolve(ctx), Toast.LENGTH_LONG).show()
                                }
                                onSelect(f)
                            } else {
                                val msg = txt(R.string.frame_toast_unsupported, f.name, reason)
                                Toast.makeText(ctx, msg.resolve(ctx), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row {
            Button(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus).onTap(onCancel)) { Text(stringResource(R.string.cancel)) }
        }
    }
}

/**
 * One frame of the scan. An unsupported one stays focusable, so the D-pad can reach it and its reason is
 * readable, but clicking it only toasts the reason ([onClick] gets it; `null` = it can be chosen). A
 * supported one in an unverified format says so under its properties and can still be chosen.
 */
@Composable
private fun FrameItem(f: SeenFrame, isSelected: Boolean, modifier: Modifier, onClick: (Txt?) -> Unit) {
    val reason = f.lookup.reasonOrNull
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        selected = isSelected,
        onClick = { onClick(reason) },
        modifier = modifier.onTap { onClick(reason) },
        headlineContent = { Text(if (isSelected) stringResource(R.string.frame_item_selected, f.name) else f.name) },
        supportingContent = {
            Column {
                Text(
                    listOf(
                        f.address, f.advert?.describe()?.map { it.text() }?.joinToString(" · ") ?: stringResource(R.string.frame_no_advert),
                        stringResource(R.string.frame_signal, f.rssi),
                    ).joinToString(" · ")
                )
                reason?.let { Text(txt(R.string.frame_unsupported, it).text(), color = MaterialTheme.colorScheme.error) }
                // a supported format never seen working on a real panel ([FrameFormat.verified])
                if (f.lookup.formatOrNull?.verified == false) {
                    Text(stringResource(R.string.frame_unverified), color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        trailingContent = { if (reason != null) Text(stringResource(R.string.frame_unavailable), color = dim) },
    )
}

@Composable
private fun Problem(message: String, action: String, onAction: () -> Unit) {
    Column {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onAction, modifier = Modifier.onTap(onAction)) { Text(action) }
    }
}

@Composable
private fun seenAt(ms: Long): String {
    if (ms <= 0) return stringResource(R.string.frame_seen_long_ago)
    val locale = LocalConfiguration.current.locales[0]
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dMMMjjmm"), locale).format(Date(ms))
}
