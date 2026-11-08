package io.github.hsw.inkeasel

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.FileObserver
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.Coil
import coil.ImageLoader
import coil.compose.AsyncImage
import io.github.hsw.inkeasel.nowplaying.Art
import io.github.hsw.inkeasel.nowplaying.FeatureStore
import io.github.hsw.inkeasel.nowplaying.FrameController
import io.github.hsw.inkeasel.nowplaying.FrameControllers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "MainActivity"

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val frames = FrameStore(app)

    /** The frame chosen in the settings; `null` until one is, and then nothing is uploaded. */
    var selected by mutableStateOf(frames.selected())
        private set

    /** The frame settings dialog is open (kept here, so it survives a configuration change). */
    var settingsOpen by mutableStateOf(false)
        private set

    val sources = buildSources(app)
    var tab by mutableIntStateOf(0)
        private set
    val catalogs = mutableStateMapOf<Int, CatalogState>()

    /** The last good list of each remote source, shown when it fails. */
    private val cache = CatalogCache(File(app.filesDir, "catalog"))
    private val loads = mutableMapOf<Int, Job>()

    /** The "My pictures" tab, if present; it is re-scanned when its folder changes. */
    val localTab = sources.indexOfFirst { it.local }
    private var localReload: Job? = null
    private val localWatcher: FileObserver? = (sources.getOrNull(localTab) as? LocalImages)?.let { src ->
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or FileObserver.CLOSE_WRITE
        @Suppress("DEPRECATION") // File ctor is API 29+; the path ctor works down to minSdk
        object : FileObserver(src.dir().absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) = refreshLocal()
        }.apply { startWatching() }
    }

    private val features = FeatureStore(app)

    /** The now-playing poster as wanted ([FeatureStore]); the switch shows [posterOn]. */
    var posterEnabled by mutableStateOf(features.posterEnabled)
        private set

    /** Notification access for the poster ([ListenerAccess]), read again on every resume ([onResume]). */
    var listenerAccess by mutableStateOf(ListenerAccess.enabled(app))
        private set

    /** What the poster switch shows: wanted and working ([PosterSwitch.checked]). */
    val posterOn: Boolean get() = PosterSwitch.checked(posterEnabled, listenerAccess)

    /** The poster waits for the access: the disclosure was confirmed ([FeatureStore.posterPending]). */
    var posterPending by mutableStateOf(features.posterPending)
        private set

    private fun setPending(pending: Boolean) {
        features.posterPending = pending
        posterPending = pending
    }

    fun openSettings() {
        settingsOpen = true
    }

    fun closeSettings() {
        settingsOpen = false
    }

    /** Choose [f] as the frame: saved with its advertisement and the time it was seen; closes the settings. */
    fun select(f: SeenFrame) {
        val frame = SelectedFrame(f.name, f.address, f.advert, System.currentTimeMillis(), f.kind)
        frames.select(frame)
        selected = frame
        settingsOpen = false
        Log.i(TAG, "frame selected: ${f.kind.id} ${f.name} ${f.address} ${f.advert}")
    }

    /** Switch the poster [on] or off: saved, and passed on to the [FrameController] at once. */
    fun setPoster(on: Boolean) {
        features.posterEnabled = on
        posterEnabled = on
        FrameControllers.get(getApplication()).onPosterSwitched()
    }

    /**
     * The poster switch was pressed ([PosterSwitch.press]): switched off or on, or, without the access,
     * nothing changes and [PosterSwitch.Press.DISCLOSE] tells the caller to show the disclosure.
     */
    fun pressPoster(): PosterSwitch.Press {
        val press = PosterSwitch.press(posterEnabled, listenerAccess)
        Log.i(TAG, "poster switch pressed: $press")
        when (press) {
            PosterSwitch.Press.TURN_OFF -> {
                setPending(false)
                setPoster(false)
            }
            PosterSwitch.Press.TURN_ON -> setPoster(true)
            PosterSwitch.Press.DISCLOSE -> Unit
        }
        return press
    }

    /**
     * The disclosure's affirmative action, "Continue" ([PosterSwitch]): the wish is kept as pending, and the
     * caller opens the system screen. Cancelling the disclosure does not call this and changes nothing.
     */
    fun confirmDisclosure() {
        Log.i(TAG, "notification access disclosure confirmed")
        setPending(true)
    }

    /** The app is back in front: read the access again and switch on a poster that waited for it. */
    fun onResume() {
        listenerAccess = ListenerAccess.enabled(getApplication())
        posterPending = features.posterPending
        if (PosterSwitch.enableOnResume(listenerAccess, posterPending)) {
            Log.i(TAG, "notification access granted: switching on the poster that waited for it")
            setPending(false)
            setPoster(true)
        }
    }

    fun selectTab(i: Int) {
        tab = i
        if (catalogs[i].needsLoad()) load(i)
    }

    /** Re-scan the local folder (debounced — a single file push fires several events). */
    fun refreshLocal() {
        if (localTab < 0) return
        localReload?.cancel()
        localReload = viewModelScope.launch {
            delay(300)
            catalogs[localTab] = loadCatalog(sources[localTab], cache = null, System::currentTimeMillis) { Log.w(TAG, it) }
        }
    }

    override fun onCleared() {
        localWatcher?.stopWatching()
    }

    /**
     * Load tab [i] (again), unless it is loading already ([loadCatalog]: a remote source's good list is saved,
     * and shown with a note when the source fails; the local folder is not cached).
     */
    fun load(i: Int) {
        if (loads[i]?.isActive == true) return
        val src = sources[i]
        catalogs[i] = CatalogState.reloading(catalogs[i])
        loads[i] = viewModelScope.launch {
            catalogs[i] = loadCatalog(src, cache.takeIf { i != localTab }, System::currentTimeMillis) { Log.w(TAG, it) }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** Whether [BlePermissions] are granted; the frame settings scan only then. */
    private var permitted by mutableStateOf(false)

    /**
     * The last request came back denied and the system will not show its dialog again ([BlePermissions.blocked]):
     * asking would silently do nothing, so "Allow" opens App info instead.
     */
    private var blocked by mutableStateOf(false)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permitted = BlePermissions.granted(this)
        if (result.isEmpty()) { // cancelled (another request was running): nothing was answered
            Log.i(TAG, "BLE permission request cancelled: granted=$permitted")
        } else {
            val perms = BlePermissions.all.toList()
            val rationale = { p: String -> shouldShowRequestPermissionRationale(p) }
            BlePermissions.noteDenial(this, perms, rationale)
            blocked = BlePermissions.blocked(perms, permitted, BlePermissions.deniedBefore(this), rationale)
            Log.i(TAG, "BLE permissions: granted=$permitted blocked=$blocked")
        }
        sendPendingShare()
    }

    /** A shared picture that waits for the permissions and a frame ([ShareActivity]). */
    private val pendingShare by lazy { PendingShare(this) }

    /** Ask for the permissions, or open App info where the system no longer asks. */
    private fun requestPermissions() {
        if (blocked) openAppInfo(this) else askPermissions.launch(BlePermissions.all)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // drawn behind the system bars (Android 15 enforces it for targetSdk 35); the UI pads itself with the
        // safe-drawing insets. Dark bars always: the app is dark whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Coil.setImageLoader(ImageLoader.Builder(this).okHttpClient(Http.client).crossfade(true).build())
        permitted = BlePermissions.granted(this)
        if (!permitted) askPermissions.launch(BlePermissions.all)
        // a fresh start without a chosen frame opens the frame settings; cancelling them leaves the catalog
        if (savedInstanceState == null && vm.selected == null) {
            Log.i(TAG, "no frame selected: opening the frame settings")
            vm.openSettings()
        }
        if (savedInstanceState == null) handleShareIntent(intent, ask = false) // the request runs already
        setContent {
            App(
                vm,
                ble = BleAccess(permitted, blocked, ::requestPermissions),
                onFrameSelected = ::sendPendingShare,
                onSend = { p ->
                    when {
                        vm.selected == null -> {
                            Log.i(TAG, "no frame selected: upload of ${p.title} refused")
                            Toast.makeText(this, R.string.upload_no_frame, Toast.LENGTH_SHORT).show()
                        }
                        BlePermissions.granted(this) -> sendToFrame(p)
                        else -> requestPermissions()
                    }
                },
            )
        }
    }

    // Hand the upload to the background service, so it survives leaving the app and does not
    // interrupt playback in another app. A short toast confirms; the service toasts again when done.
    private fun sendToFrame(p: Picture) {
        if (p.local) UploadService.startManual(this, Art.File(p.fullUrl))
        else UploadService.startManual(this, Art.Url(p.fullUrl), p.title)
        Toast.makeText(this, R.string.upload_sending, Toast.LENGTH_SHORT).show()
    }

    /**
     * Send the picture shared while the permissions were missing, once they are granted and a frame is
     * chosen (a manual upload); a stale one or one whose file is gone is dropped ([PendingShare.take]).
     * Called after the permission request, on resume and when a frame is selected.
     */
    private fun sendPendingShare() {
        if (!permitted || vm.selected == null) return
        val path = pendingShare.take(System.currentTimeMillis()) ?: return
        Log.i(TAG, "sending the pending share: ${File(path).name}")
        UploadService.startManual(this, Art.File(path))
        Toast.makeText(this, R.string.upload_sending, Toast.LENGTH_SHORT).show()
    }

    // A share without the permissions reaches a running app here (ShareActivity: CLEAR_TOP | SINGLE_TOP).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent, ask = true)
    }

    /**
     * Opened by a share that waits for the permissions ([EXTRA_PENDING_SHARE]): while they are missing, show
     * the settings, which say what is missing (with "App info" where the system no longer asks), and [ask]
     * for them unless the system no longer shows its dialog.
     */
    private fun handleShareIntent(intent: Intent, ask: Boolean) {
        if (!intent.getBooleanExtra(EXTRA_PENDING_SHARE, false) || BlePermissions.granted(this)) return
        vm.openSettings()
        if (ask && !blocked) askPermissions.launch(BlePermissions.all)
    }

    // Returning to the app re-scans the local folder, in case files changed while it was away.
    // Permissions and notification access may also have been granted from the system settings meanwhile.
    override fun onResume() {
        super.onResume()
        permitted = BlePermissions.granted(this)
        if (permitted) blocked = false
        vm.refreshLocal()
        vm.onResume()
        sendPendingShare()
    }

    companion object {
        /** Set by [ShareActivity] when a shared picture waits for the permissions ([PendingShare]). */
        const val EXTRA_PENDING_SHARE = "pendingShare"
    }
}

@Composable
fun App(
    vm: MainViewModel,
    ble: BleAccess,
    onFrameSelected: () -> Unit,
    onSend: (Picture) -> Unit,
) {
    // the cards take the chosen frame's shape; the frame this app was built with while none is chosen
    val aspect = (vm.selected?.lookup?.formatOrNull ?: FrameFormats.DEFAULT).aspect
    val grid = rememberLazyGridState()
    val tabFocus = remember { FocusRequester() }
    var contentFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), shape = RectangleShape) {
            // the width outside the layout padding (inside the system bars) picks the columns and the padding
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val layout = CatalogLayout.forWidth(maxWidth.value.toInt())
                Column(Modifier.padding(horizontal = layout.sidePadding.dp, vertical = layout.verticalPadding.dp)) {
                    Header(vm, layout.splitHeader, tabFocus)
                    Spacer(Modifier.height(16.dp))
                    LaunchedEffect(Unit) { vm.selectTab(vm.tab) }
                    val st = vm.catalogs[vm.tab] ?: CatalogState.Loading
                    // Back from the content goes to the tabs first ([CatalogBack]); the settings dialog has its own
                    val gridShown = st is CatalogState.Loaded && st.items.isNotEmpty()
                    val back = CatalogBack.of(contentFocused, scrolledDown = gridShown && grid.canScrollBackward)
                    BackHandler(enabled = back.taken && !vm.settingsOpen) {
                        if (back.focusTab) runCatching { tabFocus.requestFocus() } // before the scroll drops the focused card
                        if (back.scrollToTop) scope.launch { grid.scrollToItem(0) }
                    }
                    Box(Modifier.onFocusChanged { contentFocused = it.hasFocus }) {
                        when (st) {
                            CatalogState.Loading -> Text(CatalogState.loadingText(local = vm.tab == vm.localTab).text())
                            is CatalogState.Failed -> Column {
                                Text(st.reason.text())
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { vm.load(vm.tab) }, modifier = Modifier.onTap { vm.load(vm.tab) }) { Text(stringResource(R.string.retry)) }
                            }
                            is CatalogState.Loaded -> Column {
                                if (st.staleSince != null) StaleNote(st) { vm.load(vm.tab) }
                                CatalogGrid(st, aspect, layout, grid, local = vm.tab == vm.localTab, onRetry = { vm.load(vm.tab) }, onSend)
                            }
                        }
                    }
                }
            }
            if (vm.settingsOpen) {
                FrameSettingsDialog(
                    selected = vm.selected,
                    ble = ble,
                    poster = vm.posterOn,
                    access = vm.listenerAccess,
                    pending = vm.posterPending,
                    onPoster = vm::pressPoster,
                    onDisclosureConfirmed = vm::confirmDisclosure,
                    onSelect = { f -> vm.select(f); onFrameSelected() },
                    onCancel = vm::closeSettings,
                )
            }
        }
    }
}

/**
 * The app name, the source tabs and the frame button: one row on a wide screen; on a narrow one ([split]) the
 * tabs go on their own row under the other two (the tv-material `TabRow` scrolls by itself when they do not fit).
 * [tabFocus] reaches the selected tab ([CatalogBack]).
 */
@Composable
private fun Header(vm: MainViewModel, split: Boolean, tabFocus: FocusRequester) {
    val title = @Composable {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, maxLines = 1)
    }
    val tabs = @Composable {
        TabRow(selectedTabIndex = vm.tab) {
            vm.sources.forEachIndexed { i, s ->
                Tab(
                    selected = vm.tab == i,
                    onFocus = { vm.selectTab(i) },
                    modifier = (if (vm.tab == i) Modifier.focusRequester(tabFocus) else Modifier).onTap { vm.selectTab(i) },
                ) {
                    Text(s.name.text(), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), maxLines = 1)
                }
            }
        }
    }
    val frame = @Composable {
        Button(onClick = vm::openSettings, modifier = Modifier.onTap(vm::openSettings)) {
            Text(
                vm.selected?.let { stringResource(R.string.header_frame, it.name) } ?: stringResource(R.string.header_no_frame),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (split) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                title()
                Spacer(Modifier.width(16.dp).weight(1f))
                frame()
            }
            Spacer(Modifier.height(12.dp))
            tabs()
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            title()
            Spacer(Modifier.width(32.dp))
            tabs()
            Spacer(Modifier.weight(1f))
            frame()
        }
    }
}

/** Above a saved list shown because its source failed: from when, why, and a retry. */
@Composable
private fun StaleNote(st: CatalogState.Loaded, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.catalog_stale, dateTimeText(st.staleSince ?: 0L), st.staleReason?.text().orEmpty()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        if (st.retrying) {
            Text(stringResource(R.string.catalog_retrying), style = MaterialTheme.typography.bodyMedium)
        } else {
            Button(onClick = onRetry, modifier = Modifier.onTap(onRetry)) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun CatalogGrid(
    st: CatalogState.Loaded,
    aspect: Float,
    layout: CatalogLayout,
    grid: LazyGridState,
    local: Boolean,
    onRetry: () -> Unit,
    onSend: (Picture) -> Unit,
) {
    if (st.items.isEmpty() && local) { // Share first; the folder and adb only for advanced users
        Column {
            Text(stringResource(R.string.local_empty), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.local_empty_advanced, LocalImages.adbDir(LocalContext.current)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else if (st.items.isEmpty()) { // a remote source answered with nothing usable
        Column {
            Text(stringResource(R.string.catalog_empty))
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry, modifier = Modifier.onTap(onRetry)) { Text(stringResource(R.string.retry)) }
        }
    } else LazyVerticalGrid(
        columns = GridCells.Fixed(layout.columns),
        state = grid,
        contentPadding = PaddingValues(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(layout.gap.dp),
        verticalArrangement = Arrangement.spacedBy(layout.gap.dp),
    ) {
        items(st.items, key = { it.id }) { p -> PictureCard(p, aspect) { onSend(p) } }
    }
}

@Composable
fun PictureCard(p: Picture, aspect: Float, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(aspect).onTap(onClick)) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = p.thumbModel(),
                contentDescription = p.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(p.title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.credit.isNotBlank()) {
                    Text(p.credit, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

