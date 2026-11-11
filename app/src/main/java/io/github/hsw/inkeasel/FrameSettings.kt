package io.github.hsw.inkeasel

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.Uri
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
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import io.github.hsw.inkeasel.catalog.NudityFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val TAG = "FrameSettings"

/**
 * "Settings": a full-screen dialog over the catalog. Section "Frame": while the dialog is open it scans for
 * frames and lists them live (one entry per name); choosing a supported frame calls [onSelect], which saves
 * it and closes the dialog. Section "Catalog" ([CatalogSection]): the nudity filter. Section "Permissions and
 * features" ([FeaturesSection]): the Bluetooth permission and the now-playing poster switch with its
 * notification access (a change there applies at once), and the privacy policy link. "Close" and Back call
 * [onCancel] and leave the frame as it was (Back closes the notification-access disclosure first, while it is
 * open). The scan stops when the dialog closes and while the app is in the background.
 *
 * The whole content scrolls as one column (a phone in landscape is about 380 dp tall), and "Close" sits at
 * the top next to the title, so the first focus needs no scrolling.
 *
 * A dialog rather than a separate screen: the catalog stays composed below it, so on close the D-pad focus
 * returns to where it was (the header button that opened it).
 *
 * Its window is drawn behind the system bars like the activity's, and the content pads itself with the
 * safe-drawing insets and the catalog's side padding for the width ([CatalogLayout]).
 *
 * Text the user must read at TV distance is `bodyMedium` (14 sp) or larger; a `ListItem`'s supporting text is
 * `bodySmall` (12 sp) by default, so such text there sets `bodyMedium` itself, and only secondary metadata (a
 * frame's address and properties) stays small.
 */
@Composable
fun FrameSettingsDialog(
    selected: SelectedFrame?,
    ble: BleAccess,
    poster: Boolean,
    access: Boolean,
    pending: Boolean,
    nudity: NudityFilter,
    nudityLock: NudityLock?,
    onNudity: () -> Unit,
    onPoster: () -> PosterSwitch.Press,
    onDisclosureConfirmed: () -> Unit,
    probing: String?,
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
                FrameSettings(
                    selected = selected,
                    ble = ble,
                    poster = poster,
                    access = access,
                    pending = pending,
                    nudity = nudity,
                    nudityLock = nudityLock,
                    onNudity = onNudity,
                    onPoster = onPoster,
                    onDisclosureConfirmed = onDisclosureConfirmed,
                    sidePadding = sidePadding,
                    probing = probing,
                    onSelect = onSelect,
                    onCancel = onCancel,
                )
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
internal fun EdgeToEdgeDialogWindow() {
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

/**
 * The stateful part of the settings: the [FrameScanner] and what it reports (the frames seen, a scan error),
 * the Bluetooth state (followed live), whether location is switched off where the scan needs it (before Android
 * 12, followed live) and the count of scan starts (`scanRun`). Everything shown is drawn by
 * [FrameSettingsContent], which the UI tests drive with fake frames and states.
 */
@Composable
private fun FrameSettings(
    selected: SelectedFrame?,
    ble: BleAccess,
    poster: Boolean,
    access: Boolean,
    pending: Boolean,
    nudity: NudityFilter,
    nudityLock: NudityLock?,
    onNudity: () -> Unit,
    onPoster: () -> PosterSwitch.Press,
    onDisclosureConfirmed: () -> Unit,
    sidePadding: Int,
    probing: String?,
    onSelect: (SeenFrame) -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    val scanner = remember { FrameScanner(ctx) }
    var frames by remember { mutableStateOf(emptyList<SeenFrame>()) }
    var scanError by remember { mutableStateOf<Txt?>(null) }
    var btProblem by remember { mutableStateOf(scanner.unavailable()) }
    // bumped at every scan start: the first, "Retry", Bluetooth back on, the permission granted, the app back in
    // the foreground; the content's "No frames seen yet" timer starts again with it
    var scanRun by remember { mutableIntStateOf(0) }

    // Bluetooth switched on or off while the screen is open: start or drop the scan accordingly
    DisposableEffect(scanner) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                btProblem = scanner.unavailable()
            }
        }
        registerSystemReceiver(ctx, r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        onDispose { ctx.unregisterReceiver(r) }
    }

    // Before Android 12 a BLE scan also needs location switched on, and the search hint says so only while it is
    // off. Only that switch is read (never where the device is), while the screen is open: at the start, on every
    // resume and when it changes; it is not stored. Android 12 and newer never count it as off.
    val locationMatters = Build.VERSION.SDK_INT < 31
    fun readLocationOff() = locationMatters && !locationEnabled(ctx)
    var locationOff by remember { mutableStateOf(readLocationOff()) }
    if (locationMatters) {
        LifecycleResumeEffect(Unit) {
            locationOff = readLocationOff()
            onPauseOrDispose {}
        }
        DisposableEffect(Unit) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    locationOff = readLocationOff()
                }
            }
            registerSystemReceiver(ctx, r, IntentFilter(LocationManager.MODE_CHANGED_ACTION))
            onDispose { ctx.unregisterReceiver(r) }
        }
    }

    // "Retry" clears the error, which brings this effect back and starts a new scan
    if (ble.permitted && btProblem == null && scanError == null) {
        LifecycleStartEffect(scanner) {
            scanRun++
            val stop = scanner.start(
                onSeen = { frames = SeenFrame.merge(frames, it) },
                onFailed = { scanError = it },
            )
            onStopOrDispose { stop() }
        }
    }

    FrameSettingsContent(
        selected = selected,
        ble = ble,
        poster = poster,
        access = access,
        pending = pending,
        nudity = nudity,
        nudityLock = nudityLock,
        onNudity = onNudity,
        onPoster = onPoster,
        onDisclosureConfirmed = onDisclosureConfirmed,
        sidePadding = sidePadding,
        probing = probing,
        frames = frames,
        scanError = scanError,
        btProblem = btProblem,
        locationOff = locationOff,
        scanRun = scanRun,
        onRetry = { scanError = null },
        onSelect = onSelect,
        onCancel = onCancel,
    )
}

/**
 * What the settings show, with no state of the scan of its own: the [frames] seen so far (in list order), a
 * [scanError] (shown with "Retry", which calls [onRetry]) and a [btProblem] (Bluetooth missing or off) come from
 * [FrameSettings], and so do [locationOff] (location switched off where the scan needs it on: before Android 12)
 * and [scanRun], which changes at every scan start; the rest is [FrameSettingsDialog]'s parameters. Only the
 * focus, the disclosure and the "No frames seen yet" timer live here (so the tests' clock drives it); the
 * notification-access screens and the device kind are read by [FeaturesSection] itself. `internal` for the
 * Compose UI tests (`FrameSettingsUiTest`).
 */
@Composable
internal fun FrameSettingsContent(
    selected: SelectedFrame?,
    ble: BleAccess,
    poster: Boolean,
    access: Boolean,
    pending: Boolean,
    nudity: NudityFilter,
    nudityLock: NudityLock?,
    onNudity: () -> Unit,
    onPoster: () -> PosterSwitch.Press,
    onDisclosureConfirmed: () -> Unit,
    sidePadding: Int,
    probing: String?,
    frames: List<SeenFrame>,
    scanError: Txt?,
    btProblem: BtProblem?,
    locationOff: Boolean,
    scanRun: Int,
    onRetry: () -> Unit,
    onSelect: (SeenFrame) -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    val listHidden = !ble.permitted || btProblem != null || scanError != null

    // A running scan that has heard no frame for [ScanStatus.NONE_SEEN_MS] says so, and what to check; a frame
    // heard replaces it with the list, and every scan start begins the wait again.
    val searching = !listHidden && frames.isEmpty()
    var noneSeen by remember { mutableStateOf(false) }
    LaunchedEffect(scanRun, searching) {
        noneSeen = false
        if (searching) {
            delay(ScanStatus.NONE_SEEN_MS)
            noneSeen = true
        }
    }

    // Focus: "Close" at first, so OK/Back work at once even before any frame shows up. When the chosen frame
    // shows up the focus moves to it, but only while it has not left "Close" yet (the user moved, or it
    // already went to the chosen frame once). With no frame chosen it stays on "Close": a frame found nearby
    // may be a neighbour's, and one stray OK on it would choose it.
    val cancelFocus = remember { FocusRequester() }
    val targetFocus = remember { FocusRequester() }
    var onClose by remember { mutableStateOf(false) }
    var leftClose by remember { mutableStateOf(false) }
    val target = selected?.name?.takeIf { name -> frames.any { it.name == name } }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { cancelFocus.requestFocus() }
    }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        snapshotFlow { onClose || leftClose }.first { it } // "Close" got its first focus
        withFrameNanos {}
        if (onClose && !leftClose) runCatching { targetFocus.requestFocus() }
    }

    val sep = stringResource(R.string.list_separator)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = sidePadding.dp, vertical = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Button(
                onClick = onCancel,
                modifier = Modifier
                    .focusRequester(cancelFocus)
                    .onFocusChanged {
                        if (it.isFocused) {
                            onClose = true
                        } else if (onClose) {
                            onClose = false
                            leftClose = true
                        }
                    }
                    .onTap(onCancel),
            ) { Text(stringResource(R.string.close)) }
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
        // the frame being read says so in its own row; while no row shows it (the list is hidden by a problem
        // below), this line does, so a running read stays visible
        probing?.takeIf { name -> listHidden || frames.none { it.name == name } }?.let {
            Text(stringResource(R.string.frame_reading_panel, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth()) {
            when {
                !ble.permitted -> Problem(
                    if (ble.blocked) blockedText() else BlePermissions.neededText.text(),
                    stringResource(if (ble.blocked) R.string.app_info else R.string.allow),
                    ble.request,
                )
                btProblem != null -> Problem(btProblem.sentence.text(), stringResource(R.string.bt_settings)) {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "no Bluetooth settings screen", e)
                    }
                }
                scanError != null -> Problem(Txt(R.string.scan_failed, scanError).text(), stringResource(R.string.retry), onRetry)
                frames.isEmpty() -> Column {
                    // before Android 12 the hint names location while it is off
                    val status = ScanStatus.of(locationOff, noneSeen)
                    Text(status.title.text(), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        status.hint.text(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in frames) key(f.name) {
                        FrameItem(
                            f,
                            isSelected = f.name == selected?.name,
                            reading = f.name == probing,
                            modifier = if (f.name == target) Modifier.focusRequester(targetFocus) else Modifier,
                        ) { reason ->
                            if (reason == null) {
                                if (f.lookup?.formatOrNull?.verified == false) {
                                    val msg = Txt(R.string.frame_toast_reason, f.name, Txt(R.string.frame_unverified))
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
        CatalogSection(nudity, nudityLock, onNudity)
        Spacer(Modifier.height(32.dp))
        FeaturesSection(ble, poster, access, pending, onPoster, onDisclosureConfirmed)
    }
}

/**
 * "Catalog": the nudity filter ([NuditySetting]). The row shows the filter in force ([nudity]); a press (OK or a
 * tap) calls [onNudity] (`MainViewModel.pressNudity`), which moves to the next value and applies it at once.
 * Under the value, where the marks come from and that some works may be unmarked. While the device locks the
 * filter ([lock]) the row is disabled (still focusable, so the D-pad reaches its text) and says why.
 */
@Composable
private fun CatalogSection(nudity: NudityFilter, lock: NudityLock?, onNudity: () -> Unit) {
    val press: () -> Unit = { if (lock == null) onNudity() }
    Text(stringResource(R.string.settings_catalog), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    ListItem(
        selected = false,
        onClick = press,
        enabled = lock == null,
        modifier = Modifier.onTap(press),
        headlineContent = { Text(stringResource(R.string.settings_nudity)) },
        supportingContent = {
            Column {
                Text(NuditySetting.valueText(nudity).text(), style = MaterialTheme.typography.bodyMedium)
                lock?.let { Text(it.reason.text(), style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.settings_nudity_about), style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
}

/**
 * "Permissions and features", one `ListItem` per row with no button inside a row (a row's press does what a
 * button would): the Bluetooth permission (its status; while it is missing a trailing label names what a press
 * does, "Allow" or, once the system no longer asks, "App info", and the press calls [BleAccess.request]; the
 * frame list above names the path to it then), and the now-playing [poster] switch
 * with the notification [access] it needs ([ListenerAccessUi]). The switch shows the poster as it works
 * ([PosterSwitch]); pressing it calls [onPoster] (`MainViewModel.pressPoster`). Without the access, the switch
 * and "Grant access" both open the [Disclosure] first; its "Continue" calls [onDisclosureConfirmed]
 * (`MainViewModel.confirmDisclosure`) and starts the grant flow: the system screen [ListenerAccessUi.grant]
 * names. The menu path on TV and the adb command (where no screen leads to the switch) are shown in the
 * disclosure; the menu path also under the switch once the poster waits for the access ([pending]), so no way
 * to the grant appears before the disclosure. Where the poster cannot work at all
 * ([ListenerAccessUi.unavailable]) the row says so, the switch is disabled and nothing leads to the grant.
 * With [ListenerAccessUi.restrictedHint] an "App info" row says how to allow the restricted setting and opens
 * App info on a press (only that screen: it is not a way into the grant).
 * The access itself is read again by the view model on resume, the screens here. Last, the [PrivacyRow].
 *
 * Every text sits in a row with something focusable, so the D-pad scrolls it into view.
 */
@Composable
private fun FeaturesSection(
    ble: BleAccess,
    poster: Boolean,
    access: Boolean,
    pending: Boolean,
    onPoster: () -> PosterSwitch.Press,
    onDisclosureConfirmed: () -> Unit,
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

    // The disclosure is open while it knows what opened it, and gives the focus back there when it closes.
    var disclosedFrom by remember { mutableStateOf<DisclosureOpener?>(null) }
    var refocus by remember { mutableStateOf<DisclosureOpener?>(null) }
    val posterFocus = remember { FocusRequester() }
    val grantFocus = remember { FocusRequester() }
    val closeDisclosure: () -> Unit = {
        refocus = disclosedFrom
        disclosedFrom = null
    }
    val pressPoster: () -> Unit = {
        if (ui.switchEnabled && onPoster() == PosterSwitch.Press.DISCLOSE) disclosedFrom = DisclosureOpener.SWITCH
    }
    val pressGrant: () -> Unit = { disclosedFrom = DisclosureOpener.BUTTON }
    val confirm: () -> Unit = { // offered only with a screen to open
        ui.grant?.let {
            onDisclosureConfirmed()
            ListenerAccess.open(ctx, it, tv)
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
    // the frame list above already says how to allow it (and where, once the system no longer asks); granted, the
    // row only shows it, and stays focusable so the D-pad moves through it as through any row
    val pressBt: () -> Unit = { if (!ble.permitted) ble.request() }
    val btAction = if (ble.permitted) null else stringResource(if (ble.blocked) R.string.app_info else R.string.allow)
    ListItem(
        selected = false,
        onClick = pressBt,
        modifier = Modifier.onTap(pressBt),
        headlineContent = { Text(stringResource(R.string.perm_bt)) },
        supportingContent = {
            Text(stringResource(if (ble.permitted) R.string.perm_bt_granted else R.string.perm_bt_missing), style = MaterialTheme.typography.bodyMedium)
        },
        trailingContent = btAction?.let { action -> { Text(action, maxLines = 1) } },
    )
    Spacer(Modifier.height(8.dp))
    ListItem(
        selected = false,
        onClick = pressPoster,
        // disabled where the poster cannot work (tv-material keeps it focusable, so the D-pad still reaches the text)
        enabled = ui.switchEnabled,
        modifier = Modifier.focusRequester(posterFocus).onTap(pressPoster),
        headlineContent = { Text(stringResource(R.string.feature_poster)) },
        supportingContent = {
            Column {
                Text(ui.status.text(), style = MaterialTheme.typography.bodyMedium)
                ui.rowMenuHint(pending)?.let { Text(ListenerAccessUi.menuText(it).text(), style = MaterialTheme.typography.bodyMedium) }
            }
        },
        trailingContent = { Switch(checked = ui.switchChecked(poster), onCheckedChange = null, enabled = ui.switchEnabled) },
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
        key(from) { Disclosure(ui, confirm, closeDisclosure) }
    }
    if (ui.restrictedHint) {
        Spacer(Modifier.height(8.dp))
        // only App info, where the restricted setting is allowed; the grant itself stays behind the disclosure
        val pressAppInfo: () -> Unit = { openAppInfo(ctx) }
        ListItem(
            selected = false,
            onClick = pressAppInfo,
            modifier = Modifier.onTap(pressAppInfo),
            headlineContent = { Text(stringResource(R.string.app_info)) },
            supportingContent = { Text(stringResource(R.string.access_restricted), style = MaterialTheme.typography.bodyMedium) },
        )
    }
    Spacer(Modifier.height(8.dp))
    PrivacyRow(tv)
}

/**
 * "Privacy policy" with its URL as text. A phone or tablet opens it in the browser on a tap; a TV app must
 * not launch a browser (TV-WB), so on TV the row only shows the URL. It stays focusable there all the same,
 * so the D-pad scrolls it into view (it is the last row of the settings) and moves on from it as from any row.
 */
@Composable
private fun PrivacyRow(tv: Boolean) {
    val ctx = LocalContext.current
    val url = stringResource(R.string.settings_privacy_url)
    val open: () -> Unit = {
        if (!tv) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no app to open the privacy policy", e)
            }
        }
    }
    ListItem(
        selected = false,
        onClick = open,
        modifier = Modifier.onTap(open),
        headlineContent = { Text(stringResource(R.string.settings_privacy)) },
        supportingContent = { Text(url, style = MaterialTheme.typography.bodyMedium) },
    )
}

/** What opened the [Disclosure]: the poster switch or "Grant access". */
private enum class DisclosureOpener { SWITCH, BUTTON }

/**
 * The prominent disclosure before notification access: what the system grant covers (all notifications),
 * what the app really reads (the playing app's media-session title and artwork) and what leaves the device
 * (only the poster, to the frame over Bluetooth). It takes the D-pad focus when it opens. "Continue"
 * ([onContinue]) is the affirmative action; on TV the menu path to the switch comes before it ([ui]'s
 * [ListenerAccessUi.menuHint]). Where no system screen leads to the access ([ListenerAccessUi.adbHint]) the adb
 * command takes the place of "Continue". "Cancel" ([onCancel]) changes nothing.
 */
@OptIn(ExperimentalFoundationApi::class) // BringIntoViewRequester
@Composable
private fun Disclosure(ui: ListenerAccessUi, onContinue: () -> Unit, onCancel: () -> Unit) {
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
        ui.menuHint?.let {
            Spacer(Modifier.height(8.dp))
            Text(ListenerAccessUi.menuText(it).text(), style = MaterialTheme.typography.bodyMedium)
        }
        val adb = ui.adbHint
        if (adb) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.access_adb, ListenerAccess.adbCommand(ctx)),
                style = MaterialTheme.typography.bodyMedium,
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

/** The Bluetooth permission is denied for good: where to allow it in the system settings. */
@Composable
private fun blockedText(): String =
    Txt(R.string.perm_bt_blocked, Txt(R.string.app_name), BlePermissions.settingsName).text()

/**
 * One frame of the scan, as a radio row: the row itself is the radio (`ListItem(selected)`, which carries the
 * selected state, plus the radio-button role), the leading [RadioButton] is only its mark. Its selected colours
 * are the plain ones, so the chosen frame never looks focused; the mark alone tells it apart. An unsupported
 * one stays focusable, so the D-pad can reach it and its reason is readable, but clicking it only toasts the
 * reason ([onClick] gets it; `null` = it can be chosen). A supported one in an unverified format says so under
 * its properties and can still be chosen. While the frame is being read before it is chosen ([reading], an
 * OpenDisplay frame), its row says so.
 */
@Composable
private fun FrameItem(f: SeenFrame, isSelected: Boolean, reading: Boolean, modifier: Modifier, onClick: (Txt?) -> Unit) {
    val reason = f.lookup?.reasonOrNull
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val sep = stringResource(R.string.list_separator)
    ListItem(
        selected = isSelected,
        onClick = { onClick(reason) },
        modifier = modifier.semantics { role = Role.RadioButton }.onTap { onClick(reason) },
        colors = ListItemDefaults.colors(
            selectedContainerColor = Color.Transparent,
            selectedContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        leadingContent = { RadioButton(selected = isSelected, onClick = null) },
        headlineContent = { Text(f.name) },
        supportingContent = {
            Column {
                Text(
                    listOf(
                        f.address, f.describe()?.map { it.text() }?.joinToString(sep) ?: stringResource(R.string.frame_no_advert),
                        stringResource(R.string.frame_signal, f.rssi),
                    ).joinToString(sep)
                )
                reason?.let {
                    Text(Txt(R.string.frame_unsupported, it).text(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                // a supported format never seen working on a real panel ([FrameFormat.verified])
                if (f.lookup?.formatOrNull?.verified == false) {
                    Text(stringResource(R.string.frame_unverified), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                }
                if (reading) {
                    Text(stringResource(R.string.frame_row_reading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        trailingContent = { if (reason != null) Text(stringResource(R.string.frame_unavailable), color = dim) },
    )
}

/**
 * Registers [receiver] for the system broadcasts in [filter]. On Android 13+ it is not exported; before that it is
 * registered plainly, so it is exported there (the location receiver, Android 11 and older only, always is). Both
 * actions it is used for are protected system broadcasts, and the receiver only re-reads the state they name.
 */
private fun registerSystemReceiver(ctx: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else ctx.registerReceiver(receiver, filter)
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

/** Whether location is switched on (not where the device is); a device without a location service counts as on. */
private fun locationEnabled(ctx: Context): Boolean =
    ctx.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: true
