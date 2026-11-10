package io.github.hsw.inkeasel

import android.app.Application
import android.content.Intent
import android.os.Build
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.hsw.inkeasel.catalog.CatalogDao
import io.github.hsw.inkeasel.catalog.CatalogPicture
import io.github.hsw.inkeasel.catalog.CatalogRow
import io.github.hsw.inkeasel.catalog.CatalogSteps
import io.github.hsw.inkeasel.catalog.CatalogStore
import io.github.hsw.inkeasel.catalog.NudityFilter
import io.github.hsw.inkeasel.nowplaying.Art
import io.github.hsw.inkeasel.nowplaying.FeatureStore
import io.github.hsw.inkeasel.nowplaying.FrameController
import io.github.hsw.inkeasel.nowplaying.FrameControllers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

private const val TAG = "MainActivity"

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val frames = FrameStore(app)

    /** The frame chosen in the settings; `null` until one is, and then nothing is uploaded. */
    var selected by mutableStateOf(frames.selected())
        private set

    /** The frame settings dialog is open (kept here, so it survives a configuration change). */
    var settingsOpen by mutableStateOf(false)
        private set

    private val store = CatalogStore.get(app)

    /** The Catalog tab's list, paged from the catalog database; it survives a switch to another tab. */
    val catalogPages: Flow<PagingData<CatalogRow>> = store.pages.cachedIn(viewModelScope)

    /** The catalog database has no picture once its starter step has run ([CatalogStore.empty]). */
    val catalogEmpty: StateFlow<Boolean> = store.empty

    /** The nudity filter in force ([CatalogStore.nudityBelow]): a change takes the Catalog grid to its top. */
    val catalogNudityBelow: StateFlow<Int> = store.nudityBelow

    private val catalogFilter = CatalogFilterStore(app)

    /** The Catalog's nudity filter as chosen in the settings ([CatalogFilterStore.nudity]). */
    var nudityChoice by mutableStateOf(catalogFilter.nudity)
        private set

    /** The device's lock on the filter ([NudityLockReader]): read at each start and when the settings open. */
    var nudityLock by mutableStateOf<NudityLock?>(null)
        private set

    /** The filter in force: [nudityChoice], or the lock's ([NudityFilter.effective]). */
    val nudityInForce: NudityFilter get() = NudityFilter.effective(nudityChoice, nudityLock != null)

    /**
     * Read the device's lock again and put the filter in force on the Catalog ([applyNudity]). At each start of
     * the activity, so a lock switched on while the app was in the background applies on its return, and when
     * the settings open (in a split screen the system settings change it while the app stays started).
     */
    fun readNudityLock() {
        nudityLock = NudityLockReader.read(getApplication())
        applyNudity()
    }

    /** "Catalog → Nudity" pressed: the next value ([NuditySetting.next]), saved and in force; nothing while locked. */
    fun pressNudity() {
        if (nudityLock != null) return
        nudityChoice = NuditySetting.next(nudityChoice)
        catalogFilter.nudity = nudityChoice
        applyNudity()
    }

    /**
     * Puts the filter in force on the Catalog ([CatalogStore.applyFilter]): a new one re-pages the grid, and
     * whatever shows a Catalog picture picked under the old one goes, as the picture may be one it hides now
     * (the device's lock found on at a return to the foreground): an open Catalog viewer closes (it steps under
     * the filter it opened with, so its "n / N" would count another list than the grid's), so does a details
     * panel on a Catalog picture, and the Bluetooth panel forgets the Catalog picture it was to send.
     */
    private fun applyNudity() {
        val filter = nudityInForce
        if (store.nudityBelow.value != filter.nudityBelow) {
            Log.i(TAG, "nudity filter: $filter (choice $nudityChoice, lock $nudityLock)")
            dropPending() // an open waiting at the gate reads its steps under the old filter
            if (viewerOnCatalog && viewer != null) {
                Log.i(TAG, "nudity filter changed: Catalog viewer closed")
                nav.close()
            }
            if (details?.local == false) {
                Log.i(TAG, "nudity filter changed: details closed")
                details = null
            }
            blePanel?.let { panel ->
                if (panel.picture?.local == false) {
                    Log.i(TAG, "nudity filter changed: the Bluetooth panel sends no Catalog picture")
                    blePanel = panel.copy(picture = null)
                }
            }
        }
        store.applyFilter(filter)
    }

    /** [p] may be shown or sent under the nudity filter in force now ([Picture.shownUnder]), at its level as given. */
    private fun passes(p: Picture): Boolean {
        val ok = p.shownUnder(store.nudityBelow.value)
        if (!ok) Log.i(TAG, "${p.id} refused: the nudity filter in force hides it")
        return ok
    }

    /**
     * The one gate before a Catalog picture shows or is sent: a press (the viewer), a long press (the details
     * panel), "View full screen", each Left/Right of the viewer ([ViewerNav]), "Send to frame" and the
     * Bluetooth panel's later send. [p] [passes] at the level stored for it now ([CatalogDao.nudity]), not the
     * one read with it: the grid keeps its old cards until a new filter's first page or an import's re-page
     * arrives, and a release import or the built-in list ([CatalogDao.markListed]) may have raised the level
     * since. A write that finishes during the read ([CatalogStore.writes]) reads it again; one that finishes
     * after it is [recheckShown]'s. A picture gone from the database, or a failed read, keeps the level it was
     * read with; one of My pictures is never read. A refusal is only logged: its card is about to go.
     */
    suspend fun allowed(p: Picture): Boolean {
        if (p.local) return passes(p)
        var level: Int?
        do {
            val writes = store.writes.value
            level = try {
                storedNudity(p.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "nudity level of ${p.id} not read", e)
                null
            }
        } while (store.writes.value != writes)
        return passes(if (level == null) p else p.copy(nudity = level))
    }

    /**
     * The read behind [allowed]. A fast read may return before the gate suspends, so the action runs at once;
     * tests that act while an open waits replace it with one that always waits for the main looper.
     */
    internal var storedNudity: suspend (String) -> Int? = { store.db.catalog().nudity(it) }

    /**
     * The open or send waiting for [allowed] ([whenAllowed]), one at a time; dropped ([dropPending]) by a newer
     * one and by each user action that changes what the screen is about: another tab, the details panel
     * dismissed, the settings, the Bluetooth panel, a new nudity filter, Back while it waits.
     */
    private var pending: Job? = null

    /** An open or send waits for the gate: Back drops it ([dropPending]) before anything else takes Back. */
    var gatePending by mutableStateOf(false)
        private set

    /** An open or send is still waiting for the gate (tests). */
    internal val openPending: Boolean get() = pending?.isActive == true

    /**
     * Runs [action] once [p] passes the gate ([allowed]), unless dropped meanwhile ([pending]); at once for a
     * picture of My pictures. [action] reads nothing that may have changed while it waited: what it depends on
     * is taken before the call.
     */
    fun whenAllowed(p: Picture, action: () -> Unit) {
        dropPending()
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!allowed(p) || pending !== job) return@launch
            } finally {
                if (pending === job) {
                    pending = null
                    gatePending = false
                }
            }
            action()
        }
        pending = job
        gatePending = true
        job.start()
    }

    /** Drops the open or send waiting for the gate, if one does ([whenAllowed]). */
    fun dropPending() {
        pending?.let {
            Log.i(TAG, "open or send at the gate dropped")
            it.cancel()
        }
        pending = null
        gatePending = false
    }

    /**
     * After each write to the catalog ([CatalogStore.writes]), and when the viewer finds no place for its
     * picture: whatever shows a Catalog picture goes if the level stored for it now is one the filter in force
     * hides ([allowed]), as [applyNudity] takes it away for a new filter: the viewer closes, so does the
     * details panel, and the Bluetooth panel forgets its picture. Each goes only while it still shows the
     * picture checked.
     */
    private suspend fun recheckShown() {
        viewer?.picture?.takeIf { viewerOnCatalog }?.let { p ->
            if (!allowed(p) && viewerOnCatalog && viewer?.picture == p) {
                Log.i(TAG, "nudity level of ${p.id} raised: Catalog viewer closed")
                nav.close()
            }
        }
        details?.takeIf { !it.local }?.let { p ->
            if (!allowed(p) && details == p) {
                Log.i(TAG, "nudity level of ${p.id} raised: details closed")
                details = null
            }
        }
        blePanel?.picture?.takeIf { !it.local }?.let { p ->
            if (!allowed(p)) blePanel?.takeIf { it.picture == p }?.let { panel ->
                Log.i(TAG, "nudity level of ${p.id} raised: the Bluetooth panel sends no Catalog picture")
                blePanel = panel.copy(picture = null)
            }
        }
    }

    /** My pictures ([LOCAL_TAB]): a list loaded whole ([readLocal]), re-scanned when its folder changes. */
    private val local = LocalImages(app)

    /** The tab names: the Catalog ([CATALOG_TAB]), then My pictures ([LOCAL_TAB]). */
    val tabNames: List<Txt> = listOf(Txt(R.string.tab_catalog), local.name)
    var tab by mutableIntStateOf(CATALOG_TAB)
        private set

    /** What My pictures shows; `null` until its first load. */
    var localState by mutableStateOf<LocalState?>(null)
        private set

    private var localLoad: Job? = null
    private var localReload: Job? = null
    private val localWatcher: FileObserver = run {
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or FileObserver.CLOSE_WRITE
        @Suppress("DEPRECATION") // File ctor is API 29+; the path ctor works down to minSdk
        object : FileObserver(local.dir().absolutePath, mask) {
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

    /**
     * The Bluetooth panel ([BleAsk]) is open with this text stage, and the pressed picture that opened it,
     * if one did (sent once the permission is granted); `null` while it is closed. Never open with the settings.
     */
    var blePanel by mutableStateOf<BlePanelState?>(null)
        private set

    fun showBlePanel(stage: BleAsk.Stage, picture: Picture?) {
        dropPending()
        settingsOpen = false
        details = null
        nav.close()
        blePanel = BlePanelState(stage, picture)
    }

    /**
     * The picture whose details panel is open ([DetailsPanel], a long press on its card); `null` while it is
     * closed. Never open with the settings, the Bluetooth panel or the viewer.
     */
    var details by mutableStateOf<Picture?>(null)
        private set

    /** The details panel of [p] (a long press on its card), once it passes the gate ([allowed]). */
    fun openDetails(p: Picture) = whenAllowed(p) {
        Log.i(TAG, "details of ${p.id}")
        details = p
    }

    /** The details panel dismissed (or left for its send path): "View full screen" still at the gate goes too. */
    fun closeDetails() {
        dropPending()
        details = null
    }

    /**
     * The open full-screen viewer ([FullscreenViewer], from a press on a card or "View full screen" in the
     * details panel), on the list of the tab it was opened from; `null` while it is closed. Opened from the
     * details panel it replaces it, and Back brings that back for the picture then shown; opened by a press
     * ([viewerByPress]) Back returns to the grid ([closeViewer]). Never open with the settings or the
     * Bluetooth panel.
     */
    var viewer by mutableStateOf<ViewerState?>(null)
        private set
    private val nav = ViewerNav(viewModelScope, gate = { allowed(it) }) { viewer = it }

    /** The open viewer steps through the Catalog ([CatalogSteps]), not My pictures. */
    private var viewerOnCatalog = false

    /** The open viewer was opened by a press on a card, not from the details panel. */
    var viewerByPress by mutableStateOf(false)
        private set

    init {
        // an import or a live refresh may raise the level of a picture shown now
        viewModelScope.launch { store.writes.drop(1).collect { recheckShown() } }
    }

    /**
     * The viewer on [p] ([byPress]: opened by a press on its card): in the Catalog it steps through the
     * database by rank ([CatalogSteps], each row made a picture by [catalogPicture], as the grid makes it) and
     * shows [p] alone until its place is read; in My pictures through its loaded list ([ListSteps]). It opens
     * once [p] passes the gate ([allowed]), and only then [then] runs. Whether it is a
     * Catalog viewer comes from [p] itself, and its list is taken at the call, before the gate: a tab switched
     * while the gate reads drops the open anyway ([whenAllowed]).
     */
    fun openViewer(
        p: Picture,
        catalogPicture: (CatalogRow) -> Picture,
        byPress: Boolean = false,
        then: () -> Unit = {},
    ) {
        val onCatalog = !p.local
        val show: () -> Unit
        if (onCatalog) {
            val steps = CatalogSteps(store.db.catalog(), store.chain.value, store.nudityBelow.value, catalogPicture)
            show = {
                nav.open(ViewerState.alone(p), steps) {
                    // no place in the list: the card may hold a level an import has raised since it was read
                    steps.open(p.id)?.also(::logViewer) ?: run {
                        viewModelScope.launch { recheckShown() }
                        null
                    }
                }
            }
        } else {
            val steps = ListSteps((localState as? LocalState.Loaded)?.items.orEmpty())
            show = { nav.open(steps.open(p).also(::logViewer), steps) }
        }
        whenAllowed(p) {
            details = null
            viewerByPress = byPress
            viewerOnCatalog = onCatalog
            show()
            then()
        }
    }

    private fun logViewer(s: ViewerState) = Log.i(TAG, "viewer on ${s.picture.id} (${s.index + 1} of ${s.count})")

    /** Left/Right in the viewer ([ViewerNav.step]). */
    fun stepViewer(delta: Int) = nav.step(delta)

    /** Back from the viewer: the grid if a press opened it, else the details panel of the picture it showed. */
    fun closeViewer() {
        val shown = nav.close()
        details = if (viewerByPress) null else shown
    }

    fun closeBlePanel() {
        blePanel = null
    }

    fun openSettings() {
        dropPending()
        blePanel = null
        details = null
        nav.close()
        readNudityLock()
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

    /** Another tab drops an open still at the gate ([dropPending]); the same one (its focus again) does not. */
    fun selectTab(i: Int) {
        if (i != tab) dropPending()
        tab = i
        if (i == LOCAL_TAB && localState.needsLoad()) loadLocal()
    }

    /** Re-scan the local folder (debounced — a single file push fires several events). */
    fun refreshLocal() {
        localReload?.cancel()
        localReload = viewModelScope.launch {
            delay(300)
            localState = readLocal(local) { Log.w(TAG, it) }
        }
    }

    override fun onCleared() {
        localWatcher.stopWatching()
    }

    /** Load My pictures (again), unless it is loading already ([readLocal]). */
    fun loadLocal() {
        if (localLoad?.isActive == true) return
        localState = LocalState.Loading
        localLoad = viewModelScope.launch {
            localState = readLocal(local) { Log.w(TAG, it) }
        }
    }

    companion object {
        /** The Catalog: the first tab, the one the app starts on. */
        const val CATALOG_TAB = 0

        /** My pictures. */
        const val LOCAL_TAB = 1
    }
}

/** The open Bluetooth panel: its text [stage], and the pressed [picture] that opened it, if one did. */
data class BlePanelState(val stage: BleAsk.Stage, val picture: Picture?)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** Whether [BlePermissions] are granted; the frame settings scan only then. */
    private var permitted by mutableStateOf(false)

    /**
     * The system no longer shows its dialog ([BlePermissions.blocked], read again on every start, resume and
     * request): asking would silently do nothing, so "Allow" in the settings opens App info instead, and the
     * Bluetooth panel is not shown ([BleAsk]).
     */
    private var blocked by mutableStateOf(false)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val answered = result.isNotEmpty() // empty: cancelled (another request was running), nothing answered
        if (answered) {
            BlePermissions.noteDenial(this, BlePermissions.all.toList(), ::shouldShowRequestPermissionRationale)
            BlePermissions.noteIntroDone(this)
        }
        readBle()
        Log.i(TAG, "BLE permission request: answered=$answered granted=$permitted blocked=$blocked")
        panelAfter(answered)
        sendPendingShare()
    }

    /** Read whether the permissions are granted, and whether the system still asks ([BlePermissions.blocked]). */
    private fun readBle() {
        permitted = BlePermissions.granted(this)
        blocked = BlePermissions.blocked(
            BlePermissions.all.toList(), permitted, BlePermissions.deniedBefore(this), ::shouldShowRequestPermissionRationale,
        )
    }

    private fun rationale(): Boolean = BlePermissions.all.any(::shouldShowRequestPermissionRationale)

    /** Open the Bluetooth panel ([BleAsk.stage]); [picture] is sent once the permission is granted. */
    private fun showPanel(picture: Picture?) {
        vm.showBlePanel(BleAsk.stage(rationale()), picture)
    }

    /** The open panel after the system dialog, or after a return to the app with the permission ([BleAsk.afterRequest]). */
    private fun panelAfter(answered: Boolean) {
        val panel = vm.blePanel ?: return
        val after = BleAsk.afterRequest(answered, permitted, blocked, vm.selected != null, panel.picture != null)
        Log.i(TAG, "Bluetooth panel: $after")
        when (after) {
            BleAsk.After.FIND -> vm.openSettings()
            BleAsk.After.SEND -> {
                vm.closeBlePanel()
                panel.picture?.let(::sendToFrame)
            }
            BleAsk.After.CLOSE -> vm.closeBlePanel()
            BleAsk.After.STAY -> showPanel(panel.picture)
        }
    }

    /** "Not now", Back or a tap outside the panel: the first-run panel counts as answered. */
    private fun dismissPanel() {
        Log.i(TAG, "Bluetooth panel: not now")
        BlePermissions.noteIntroDone(this)
        vm.closeBlePanel()
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
        Images.install(this)
        // the catalog database fills and syncs in the background, process-wide: a pass at each start of the
        // activity (the app's start and each return to the foreground); a new UI language re-creates the
        // activity and so reaches the store too
        CatalogStore.get(this).startOnEachStart(lifecycle) { CatalogStore.chainOf(resources.configuration) }
        readBle()
        // a fresh start asks for nothing by itself: without the permission it shows the first-run panel (until
        // answered once), with it and no frame chosen the frame settings ([BleAsk.atStart]); a share comes after
        if (savedInstanceState == null) {
            val start = BleAsk.atStart(permitted, vm.selected != null, BlePermissions.introDone(this), blocked)
            Log.i(TAG, "start: $start (granted=$permitted blocked=$blocked frame=${vm.selected != null})")
            when (start) {
                BleAsk.Start.PANEL -> showPanel(null)
                BleAsk.Start.SETTINGS -> vm.openSettings()
                BleAsk.Start.CATALOG -> Unit
            }
            handleShareIntent(intent)
        }
        setContent {
            App(
                vm,
                ble = BleAccess(permitted, blocked, ::requestPermissions),
                onFrameSelected = ::sendPendingShare,
                onSend = ::send,
                onPanelConfirm = { askPermissions.launch(BlePermissions.all) },
                onPanelDismiss = ::dismissPanel,
            )
        }
    }

    /**
     * The send path of a picture ([BleAsk.onSend]), "Send to frame" in the viewer's card or the details panel:
     * sent, the Bluetooth panel (which replaces any open viewer), or a toast why not. Only a picture that
     * passes the gate ([MainViewModel.whenAllowed]) takes it. A press on a card sends nothing.
     */
    private fun send(p: Picture) {
        vm.whenAllowed(p) { sendAllowed(p) }
    }

    private fun sendAllowed(p: Picture) {
        readBle()
        when (BleAsk.onSend(permitted, vm.selected != null, blocked)) {
            BleAsk.Send.SEND -> upload(p)
            BleAsk.Send.NO_FRAME -> {
                Log.i(TAG, "no frame selected: upload of ${p.title} refused")
                Toast.makeText(this, R.string.upload_no_frame, Toast.LENGTH_SHORT).show()
            }
            BleAsk.Send.PANEL -> showPanel(p)
            BleAsk.Send.BLOCKED -> {
                Log.i(TAG, "Bluetooth denied for good: upload of ${p.title} refused")
                Toast.makeText(this, R.string.upload_toast_blocked, Toast.LENGTH_LONG).show()
            }
        }
    }

    // The Bluetooth panel's send, once the permission is granted, of the picture it was opened with: through
    // the gate again, as the filter or the picture's stored level may have changed meanwhile.
    private fun sendToFrame(p: Picture) {
        vm.whenAllowed(p) { upload(p) }
    }

    // Hand the upload to the background service, so it survives leaving the app and does not
    // interrupt playback in another app. A short toast confirms; the service toasts again when done.
    private fun upload(p: Picture) {
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
        handleShareIntent(intent)
    }

    /**
     * Opened by a share that waits for the permissions ([EXTRA_PENDING_SHARE]): while they are missing, the
     * Bluetooth panel asks for them, or, where the system no longer asks, the settings say where to allow them
     * ([BleAsk.onShare]).
     */
    private fun handleShareIntent(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_PENDING_SHARE, false)) return
        readBle()
        when (BleAsk.onShare(permitted, blocked)) {
            BleAsk.Share.NOTHING -> Unit
            BleAsk.Share.PANEL -> showPanel(null)
            BleAsk.Share.SETTINGS -> vm.openSettings()
        }
    }

    // The device's lock on the Catalog's nudity filter may have changed while the app was away.
    override fun onStart() {
        super.onStart()
        vm.readNudityLock()
    }

    // Returning to the app re-scans the local folder, in case files changed while it was away.
    // Permissions and notification access may also have been granted from the system settings meanwhile.
    override fun onResume() {
        super.onResume()
        readBle()
        if (permitted) panelAfter(answered = true)
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
    onPanelConfirm: () -> Unit,
    onPanelDismiss: () -> Unit,
) {
    // the cards take the chosen frame's shape; the frame this app was built with while none is chosen
    val aspect = (vm.selected?.lookup?.formatOrNull ?: FrameFormats.DEFAULT).aspect
    val grid = rememberLazyGridState()
    // the Catalog's own grid state: its place in the long list survives a visit to another tab
    val catalogGrid = rememberLazyGridState()
    val pages = vm.catalogPages.collectAsLazyPagingItems()
    val catalogEmpty by vm.catalogEmpty.collectAsState()
    val nudityBelow by vm.catalogNudityBelow.collectAsState()
    val names = stringResource(R.string.list_names_separator)
    val density = LocalDensity.current.density
    val tabFocus = remember { FocusRequester() }
    var contentFocused by remember { mutableStateOf(false) }
    // a new nudity filter is another list: the Catalog grid goes back to its top (not at a first composition);
    // a focused card goes with the old list, so the D-pad focus moves to the tab first, as Back does it
    LaunchedEffect(Unit) {
        vm.catalogNudityBelow.drop(1).collect {
            if (contentFocused && vm.tab == MainViewModel.CATALOG_TAB) runCatching { tabFocus.requestFocus() }
            // its own coroutine, as Back's: a refused scroll (a drag holds the grid) must not end the collector
            launch { catalogGrid.scrollToItem(0) }
        }
    }
    val scope = rememberCoroutineScope()
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), shape = RectangleShape) {
            // the width outside the layout padding (inside the system bars) picks the columns and the padding
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val width = maxWidth.value.toInt()
                val layout = CatalogLayout.forWidth(width)
                val columnPx = (layout.cardWidth(width) * density).roundToInt()
                // a catalog row as the grid and the viewer show it: the same thumbnail (by the width the picture
                // takes in the card), so the viewer's stand-in is the card's, already cached
                val catalogPicture = remember(names, columnPx, aspect) {
                    { row: CatalogRow -> CatalogPicture.of(row, names, CatalogPicture.thumbWidth(columnPx, aspect, row.width, row.height)) }
                }
                // a press on a card: the viewer on it, clean, sending nothing ("Send to frame" is in its card),
                // once the picture passes the gate (nothing for one the filter in force hides)
                val press = { p: Picture ->
                    Log.i(TAG, "press on ${p.id}")
                    vm.openViewer(p, catalogPicture, byPress = true)
                }
                Column(Modifier.padding(horizontal = layout.sidePadding.dp, vertical = layout.verticalPadding.dp)) {
                    Header(vm, layout.splitHeader, tabFocus)
                    Spacer(Modifier.height(16.dp))
                    LaunchedEffect(Unit) { vm.selectTab(vm.tab) }
                    val catalog = vm.tab == MainViewModel.CATALOG_TAB
                    val st = vm.localState ?: LocalState.Loading
                    // Back from the content goes to the tabs first ([CatalogBack]); the settings dialog has its own
                    val shownGrid = if (catalog) catalogGrid else grid
                    val gridItems = when {
                        catalog -> pages.itemCount
                        st is LocalState.Loaded -> st.items.size
                        else -> 0
                    }
                    val back = CatalogBack.of(contentFocused, gridItems, shownGrid.canScrollBackward)
                    BackHandler(enabled = back.taken && !vm.settingsOpen) {
                        if (back.focusTab) runCatching { tabFocus.requestFocus() } // before the scroll drops the focused card
                        if (back.scrollToTop) scope.launch { shownGrid.scrollToItem(0) }
                    }
                    // Back while a press or "Send to frame" waits for the gate drops it, as Back would close
                    // the viewer a press opens; registered after the grid's, so it comes first
                    BackHandler(enabled = vm.gatePending && !vm.settingsOpen) { vm.dropPending() }
                    Box(Modifier.onFocusChanged { contentFocused = it.hasFocus }) {
                        if (catalog) {
                            val view = CatalogView.of(
                                pages.itemCount, catalogEmpty, pages.loadState.refresh is LoadState.Loading,
                                endReached = pages.loadState.append.endOfPaginationReached,
                            )
                            when (view) {
                                CatalogView.GRID -> PagedGrid(
                                    pages, catalogPicture, nudityBelow, aspect, layout, catalogGrid,
                                    onPress = press, onDetails = vm::openDetails,
                                )
                                CatalogView.NONE -> Text(stringResource(R.string.catalog_none))
                                CatalogView.FILTERED -> Text(stringResource(R.string.catalog_filtered))
                                CatalogView.LOADING -> Text(stringResource(R.string.catalog_loading))
                            }
                        } else when (st) {
                            LocalState.Loading -> Text(stringResource(R.string.local_loading))
                            is LocalState.Failed -> Column {
                                Text(st.reason.text())
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = vm::loadLocal, modifier = Modifier.onTap(vm::loadLocal)) { Text(stringResource(R.string.retry)) }
                            }
                            is LocalState.Loaded -> LocalGrid(st, aspect, layout, grid, onPress = press, onDetails = vm::openDetails)
                        }
                    }
                }
                // the dialogs over the catalog (here for the card width the viewer shares with the grid)
                if (vm.settingsOpen) {
                    FrameSettingsDialog(
                        selected = vm.selected,
                        ble = ble,
                        poster = vm.posterOn,
                        access = vm.listenerAccess,
                        pending = vm.posterPending,
                        nudity = vm.nudityInForce,
                        nudityLock = vm.nudityLock,
                        onNudity = vm::pressNudity,
                        onPoster = vm::pressPoster,
                        onDisclosureConfirmed = vm::confirmDisclosure,
                        onSelect = { f -> vm.select(f); onFrameSelected() },
                        onCancel = vm::closeSettings,
                    )
                } else {
                    val panel = vm.blePanel
                    val details = vm.details
                    val viewer = vm.viewer
                    if (panel != null) {
                        BlePanel(BleAsk.panel(panel.stage, vm.selected != null, Build.VERSION.SDK_INT), onPanelConfirm, onPanelDismiss)
                    } else if (viewer != null) {
                        // "Send to frame" in its card keeps the viewer open (a toast), unless the Bluetooth panel
                        // replaces it
                        FullscreenViewer(viewer, vm.selected, onStep = vm::stepViewer, onSend = onSend, onClose = vm::closeViewer)
                    } else if (details != null) {
                        // "Send to frame" closes the panel and takes the card's send path without the viewer (a toast,
                        // or the Bluetooth panel); "View full screen" replaces it with the viewer, and Back from there
                        // brings it back
                        DetailsPanel(
                            details,
                            onSend = { vm.closeDetails(); onSend(details) },
                            onView = { vm.openViewer(details, catalogPicture) },
                            onClose = vm::closeDetails,
                        )
                    }
                }
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
            vm.tabNames.forEachIndexed { i, name ->
                Tab(
                    selected = vm.tab == i,
                    onFocus = { vm.selectTab(i) },
                    modifier = (if (vm.tab == i) Modifier.focusRequester(tabFocus) else Modifier).onTap { vm.selectTab(i) },
                ) {
                    Text(name.text(), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), maxLines = 1)
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

/** My pictures: the whole list in a grid, or, while the folder is empty, how to add pictures. */
@Composable
private fun LocalGrid(
    st: LocalState.Loaded,
    aspect: Float,
    layout: CatalogLayout,
    grid: LazyGridState,
    onPress: (Picture) -> Unit,
    onDetails: (Picture) -> Unit,
) {
    if (st.items.isEmpty()) { // Share first; the folder and adb only for advanced users
        Column {
            Text(stringResource(R.string.local_empty), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.local_empty_advanced, LocalImages.adbDir(LocalContext.current)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else LazyVerticalGrid(
        columns = GridCells.Fixed(layout.columns),
        state = grid,
        contentPadding = PaddingValues(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(layout.gap.dp),
        verticalArrangement = Arrangement.spacedBy(layout.gap.dp),
    ) {
        items(st.items, key = { it.id }) { p -> PictureCard(p, aspect, onClick = { onPress(p) }, onLongClick = { onDetails(p) }) }
    }
}

/**
 * The Catalog's grid over its [pages]: a loaded row is a [PictureCard] ([picture] maps it), a row not loaded yet
 * the same card empty, so the D-pad focus moves through the list as it will look. Items are keyed by their
 * place, not by the row's id: paging-compose keys a placeholder by its index and a loaded row by its id, so
 * keys by id would replace the focused placeholder with a new card when its page arrives (or comes back after
 * being dropped), and the focus would be lost. The places are stable (the count includes the rows not loaded).
 */
@Composable
private fun PagedGrid(
    pages: LazyPagingItems<CatalogRow>,
    picture: (CatalogRow) -> Picture,
    nudityBelow: Int,
    aspect: Float,
    layout: CatalogLayout,
    grid: LazyGridState,
    onPress: (Picture) -> Unit,
    onDetails: (Picture) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(layout.columns),
        state = grid,
        contentPadding = PaddingValues(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(layout.gap.dp),
        verticalArrangement = Arrangement.spacedBy(layout.gap.dp),
    ) {
        items(pages.itemCount) { i ->
            val row = pages[i]
            // a card of the old list that the filter in force hides is empty until the new list's page arrives
            val p = remember(row, picture, nudityBelow) { CatalogPicture.card(row, nudityBelow, picture) }
            PictureCard(p, aspect, onClick = { p?.let(onPress) }, onLongClick = { p?.let(onDetails) })
        }
    }
}

/**
 * A catalog card: the whole picture fitted inside it on black, its title and credit over a scrim at the bottom
 * (over the picture or the black around it). A press (CENTER or a tap, [onClick]) sends [p] and opens the
 * viewer on it, a long press opens its details ([onLongClick]). With no picture ([p] `null`: its row is still
 * loading) it is the same card, empty and focusable; it stays the same node when the picture arrives, so a
 * focused card keeps the focus. Its thumbnail may be held ([Holdable]); one refused with 429 or 503 (the host
 * asked for a pause, often after a fast scroll) is asked again once the pause is over
 * ([RetryPolicy.thumbRetryMs]), as long as the card is shown.
 */
@Composable
fun PictureCard(p: Picture?, aspect: Float, onClick: () -> Unit, onLongClick: () -> Unit) {
    var retries by remember(p?.thumbUrl) { mutableIntStateOf(0) }
    var retryIn by remember(p?.thumbUrl) { mutableStateOf<Long?>(null) }
    LaunchedEffect(retryIn) {
        val ms = retryIn ?: return@LaunchedEffect
        delay(ms)
        retryIn = null
        retries++
    }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().aspectRatio(aspect).onTap(onClick, onLongPress = onLongClick),
    ) {
        if (p != null) Box(Modifier.fillMaxSize().background(Color.Black)) {
            val context = LocalContext.current
            key(retries) {
                val request = remember(p.thumbUrl) {
                    ImageRequest.Builder(context).data(p.thumbModel()).tag(Holdable::class.java, Holdable).build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = p.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onError = { e ->
                        retryIn = RetryPolicy.thumbRetryMs(e.result.throwable, retries, System.currentTimeMillis())
                    },
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(p.title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.credit.isNotBlank()) {
                    Text(p.credit, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

