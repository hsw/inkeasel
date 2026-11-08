package io.github.hsw.inkeasel

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private const val TAG = "FrameSettings"

/**
 * "Settings": a full-screen dialog over the catalog. Section "Frame": while the dialog is open it scans for
 * frames and lists them live (one entry per name); choosing a supported frame calls [onSelect], which saves
 * it and closes the dialog. Section "Permissions and features" ([FeaturesSection]): the Bluetooth permission
 * and the now-playing poster switch with its notification access; a change there applies at once. "Close"
 * and Back call [onCancel] and leave the frame as it was (Back closes the notification-access disclosure
 * first, while it is open). The scan stops when the dialog closes and while
 * the app is in the background.
 *
 * The whole content scrolls as one column (a phone in landscape is about 380 dp tall), and "Close" sits at
 * the top next to the title, so the first focus needs no scrolling.
 *
 * A dialog rather than a separate screen: the catalog stays composed below it, so on close the D-pad focus
 * returns to where it was (the header button that opened it).
 *
 * Its window is drawn behind the system bars like the activity's, and the content pads itself with the
 * safe-drawing insets and the catalog's side padding for the width ([CatalogLayout]).
 */
@Composable
fun FrameSettingsDialog(
    selected: SelectedFrame?,
    ble: BleAccess,
    poster: Boolean,
    access: Boolean,
    onPoster: () -> PosterSwitch.Press,
    onDisclosureConfirmed: (grantAvailable: Boolean) -> PosterSwitch.Confirm,
    onSelect: (SeenFrame) -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        EdgeToEdgeDialogWindow()
        Surface(Modifier.fillMaxSize(), shape = RectangleShape) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val sidePadding = CatalogLayout.forWidth(maxWidth.value.toInt()).sidePadding
                FrameSettings(selected, ble, poster, access, onPoster, onDisclosureConfirmed, sidePadding, onSelect, onCancel)
            }
        }
    }
}

/**
 * A dialog window is a floating one: even with `decorFitsSystemWindows = false` its `fitInsetsTypes` (Android 11+)
 * keep it inside the status bar, while its content still reaches under the navigation bar (seen on Android 15).
 * Clearing them makes it cover the whole screen like the activity, and the content pads itself with the insets.
 * Older versions keep the platform's layout; the insets padding covers whatever the window overlaps.
 */
@Composable
private fun EdgeToEdgeDialogWindow() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    LaunchedEffect(window) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}

@Composable
private fun FrameSettings(
    selected: SelectedFrame?,
    ble: BleAccess,
    poster: Boolean,
    access: Boolean,
    onPoster: () -> PosterSwitch.Press,
    onDisclosureConfirmed: (grantAvailable: Boolean) -> PosterSwitch.Confirm,
    sidePadding: Int,
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

    if (ble.permitted && btProblem == null && scanError == null) {
        LifecycleStartEffect(scanner, scanRun) {
            val stop = scanner.start(
                onSeen = { frames = SeenFrame.merge(frames, it) },
                onFailed = { scanError = it },
            )
            onStopOrDispose { stop() }
        }
    }

    // Focus: "Close" at first, so OK/Back work at once even before any frame shows up; then, once, the
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

    val sep = stringResource(R.string.list_separator)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = sidePadding.dp, vertical = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Button(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus).onTap(onCancel)) { Text(stringResource(R.string.close)) }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.settings_frame), style = MaterialTheme.typography.titleLarge)
        Text(
            selected?.let {
                listOfNotNull(
                    stringResource(R.string.frame_selected, it.name), it.address,
                    stringResource(R.string.frame_seen, seenAt(it.seenAt)),
                    if (it.lookup.formatOrNull?.verified == false) stringResource(R.string.frame_unverified) else null,
                ).joinToString(sep)
            }
                ?: stringResource(R.string.frame_none_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth()) {
            when {
                !ble.permitted -> Problem(
                    if (ble.blocked) blockedText() else stringResource(R.string.ble_permission_needed),
                    stringResource(if (ble.blocked) R.string.app_info else R.string.allow),
                    ble.request,
                )
                btProblem != null -> Problem(btProblem!!.sentence.text(), stringResource(R.string.bt_settings)) {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "no Bluetooth settings screen", e)
                    }
                }
                scanError != null -> Problem(Txt(R.string.scan_failed, scanError!!).text(), stringResource(R.string.retry)) {
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
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in frames) key(f.name) {
                        FrameItem(
                            f,
                            isSelected = f.name == selected?.name,
                            modifier = (if (f.name == target) Modifier.focusRequester(targetFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused) listTouched = true },
                        ) { reason ->
                            if (reason == null) {
                                if (f.lookup.formatOrNull?.verified == false) {
                                    val msg = Txt(R.string.frame_toast_unverified, f.name, Txt(R.string.frame_unverified))
                                    Toast.makeText(ctx, msg.resolve(ctx), Toast.LENGTH_LONG).show()
                                }
                                onSelect(f)
                            } else {
                                val msg = Txt(R.string.frame_toast_unsupported, f.name, reason)
                                Toast.makeText(ctx, msg.resolve(ctx), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        FeaturesSection(ble, poster, access, onPoster, onDisclosureConfirmed)
    }
}

/**
 * "Permissions and features": the Bluetooth permission (status, and "Allow" or, once the system no longer
 * asks, "App info"; the frame list above names the path to it then), and the now-playing [poster] switch
 * with the notification [access] it needs ([ListenerAccessUi]). The switch shows the poster as it works
 * ([PosterSwitch]); pressing it calls [onPoster] (`MainViewModel.pressPoster`). Without the access, the switch
 * and "Grant access" both open the [Disclosure] first; its "Continue" calls [onDisclosureConfirmed]
 * (`MainViewModel.confirmDisclosure`) and starts the grant flow: the system screen [ListenerAccessUi.grant]
 * names, while the section says the menu path on TV (or the adb command where no screen leads to the switch).
 * The access itself is read again by the view model on resume, the screens here.
 *
 * Every text sits in a row with something focusable, so the D-pad scrolls it into view.
 */
@Composable
private fun FeaturesSection(
    ble: BleAccess,
    poster: Boolean,
    access: Boolean,
    onPoster: () -> PosterSwitch.Press,
    onDisclosureConfirmed: (grantAvailable: Boolean) -> PosterSwitch.Confirm,
) {
    val ctx = LocalContext.current
    val tv = remember { ListenerAccess.tv(ctx) }
    val lowRam = remember { ListenerAccess.lowRam(ctx) }
    val sideloaded = remember { ListenerAccess.sideloaded(ctx) }
    var direct by remember { mutableStateOf(ListenerAccess.canOpen(ctx, ListenerAccessUi.GrantScreen.DIRECT, tv)) }
    var apps by remember { mutableStateOf(ListenerAccess.canOpen(ctx, ListenerAccessUi.GrantScreen.APPS, tv)) }
    LifecycleResumeEffect(Unit) {
        direct = ListenerAccess.canOpen(ctx, ListenerAccessUi.GrantScreen.DIRECT, tv)
        apps = ListenerAccess.canOpen(ctx, ListenerAccessUi.GrantScreen.APPS, tv)
        onPauseOrDispose {}
    }
    val ui = ListenerAccessUi.of(access, direct, apps, Build.VERSION.SDK_INT, tv, lowRam, sideloaded)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    // The disclosure is open while it knows what opened it, and gives the focus back there when it closes.
    var disclosedFrom by remember { mutableStateOf<DisclosureOpener?>(null) }
    var refocus by remember { mutableStateOf<DisclosureOpener?>(null) }
    val posterFocus = remember { FocusRequester() }
    val grantFocus = remember { FocusRequester() }
    val closeDisclosure: () -> Unit = {
        refocus = disclosedFrom
        disclosedFrom = null
    }
    val pressPoster: () -> Unit = { if (onPoster() == PosterSwitch.Press.DISCLOSE) disclosedFrom = DisclosureOpener.SWITCH }
    val pressGrant: () -> Unit = { disclosedFrom = DisclosureOpener.BUTTON }
    val confirm: () -> Unit = {
        val grant = ui.grant
        if (onDisclosureConfirmed(grant != null) == PosterSwitch.Confirm.SET_PENDING_AND_GRANT && grant != null) {
            ListenerAccess.open(ctx, grant, tv)
        }
        closeDisclosure()
    }
    BackHandler(enabled = disclosedFrom != null, onBack = closeDisclosure)
    LaunchedEffect(access) { if (access && disclosedFrom != null) closeDisclosure() } // granted meanwhile
    LaunchedEffect(refocus) {
        val to = refocus ?: return@LaunchedEffect
        withFrameNanos {}
        // "Grant access" is gone once the access is granted: the switch takes the focus then
        val moved = to == DisclosureOpener.BUTTON && runCatching { grantFocus.requestFocus() }.isSuccess
        if (!moved) runCatching { posterFocus.requestFocus() }
        refocus = null
    }

    Text(stringResource(R.string.settings_features), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    SectionRow(
        title = stringResource(R.string.perm_bt),
        // the frame list above already says how to allow it (and where, once the system no longer asks)
        text = stringResource(if (ble.permitted) R.string.perm_bt_granted else R.string.perm_bt_missing),
        action = if (ble.permitted) null else stringResource(if (ble.blocked) R.string.app_info else R.string.allow),
        onAction = ble.request,
    )
    Spacer(Modifier.height(8.dp))
    ListItem(
        selected = false,
        onClick = pressPoster,
        modifier = Modifier.focusRequester(posterFocus).onTap(pressPoster),
        headlineContent = { Text(stringResource(R.string.feature_poster)) },
        supportingContent = {
            Column {
                Text(ui.status.text())
                ui.menuHint?.let { Text(ListenerAccessUi.menuText(it).text()) }
                if (ui.adbHint) {
                    Text(
                        stringResource(R.string.access_adb, ListenerAccess.adbCommand(ctx)),
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                    )
                }
            }
        },
        trailingContent = { Switch(checked = poster, onCheckedChange = null) },
    )
    if (ui.openButton) {
        Spacer(Modifier.height(8.dp))
        Button(onClick = pressGrant, modifier = Modifier.focusRequester(grantFocus).onTap(pressGrant)) {
            Text(stringResource(R.string.access_open))
        }
    }
    disclosedFrom?.let { from ->
        Spacer(Modifier.height(8.dp))
        // keyed by the opener, so opening it again from the other element moves the focus into it again
        key(from) { Disclosure(PosterSwitch.confirm(ui.grant != null), confirm, closeDisclosure) }
    }
    if (ui.restrictedHint) {
        Spacer(Modifier.height(8.dp))
        SectionRow(title = null, text = stringResource(R.string.access_restricted), action = stringResource(R.string.app_info), onAction = { openAppInfo(ctx) })
    }
}

/** What opened the [Disclosure]: the poster switch or "Grant access". */
private enum class DisclosureOpener { SWITCH, BUTTON }

/**
 * The prominent disclosure before notification access: what the system grant covers (all notifications),
 * what the app really reads (the playing app's media-session title and artwork) and what leaves the device
 * (only the poster, to the frame over Bluetooth). It takes the D-pad focus when it opens. "Continue"
 * ([onContinue]) is the affirmative action; where no system screen leads to the access ([confirm] is
 * [PosterSwitch.Confirm.SHOW_ADB_HINT]) the adb command takes its place. "Cancel" ([onCancel]) changes nothing.
 */
@OptIn(ExperimentalFoundationApi::class) // BringIntoViewRequester
@Composable
private fun Disclosure(confirm: PosterSwitch.Confirm, onContinue: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }
    val inView = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { focus.requestFocus() }
        withFrameNanos {}
        inView.bringIntoView() // the whole panel, not only the focused button, once it fits on the screen
    }
    Column(
        Modifier.fillMaxWidth()
            .bringIntoViewRequester(inView)
            .border(1.dp, MaterialTheme.colorScheme.border, RoundedCornerShape(12.dp)) // a fill would hide the buttons' own
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.access_disclosure_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(Txt(R.string.access_disclosure_text, Txt(R.string.app_name)).text(), style = MaterialTheme.typography.bodyMedium)
        val adb = confirm == PosterSwitch.Confirm.SHOW_ADB_HINT
        if (adb) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.access_adb, ListenerAccess.adbCommand(ctx)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!adb) {
                Button(onClick = onContinue, modifier = Modifier.focusRequester(focus).onTap(onContinue)) {
                    Text(stringResource(R.string.access_disclosure_continue))
                }
            }
            Button(onClick = onCancel, modifier = (if (adb) Modifier.focusRequester(focus) else Modifier).onTap(onCancel)) {
                Text(stringResource(R.string.access_disclosure_cancel))
            }
        }
    }
}

/** A compact row: an optional [title] over a dim [text] on the left, an optional button on the right. */
@Composable
private fun SectionRow(title: String?, text: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            Spacer(Modifier.width(16.dp))
            Button(onClick = onAction, modifier = Modifier.onTap(onAction)) { Text(action, maxLines = 1) }
        }
    }
}

/** The Bluetooth permission is denied for good: where to allow it in the system settings. */
@Composable
private fun blockedText(): String =
    Txt(R.string.perm_bt_blocked, Txt(R.string.app_name), BlePermissions.settingsName).text()

/**
 * One frame of the scan. An unsupported one stays focusable, so the D-pad can reach it and its reason is
 * readable, but clicking it only toasts the reason ([onClick] gets it; `null` = it can be chosen). A
 * supported one in an unverified format says so under its properties and can still be chosen.
 */
@Composable
private fun FrameItem(f: SeenFrame, isSelected: Boolean, modifier: Modifier, onClick: (Txt?) -> Unit) {
    val reason = f.lookup.reasonOrNull
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val sep = stringResource(R.string.list_separator)
    ListItem(
        selected = isSelected,
        onClick = { onClick(reason) },
        modifier = modifier.onTap { onClick(reason) },
        headlineContent = { Text(if (isSelected) stringResource(R.string.frame_item_selected, f.name) else f.name) },
        supportingContent = {
            Column {
                Text(
                    listOf(
                        f.address, f.advert?.describe()?.map { it.text() }?.joinToString(sep) ?: stringResource(R.string.frame_no_advert),
                        stringResource(R.string.frame_signal, f.rssi),
                    ).joinToString(sep)
                )
                reason?.let { Text(Txt(R.string.frame_unsupported, it).text(), color = MaterialTheme.colorScheme.error) }
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
    return dateTimeText(ms)
}
