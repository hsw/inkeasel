package io.github.hsw.inkeasel

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MainActivity"

sealed interface CatalogState {
    data object Loading : CatalogState
    data class Loaded(val items: List<Picture>) : CatalogState
    data class Failed(val message: String) : CatalogState
}

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

    /** The "My pictures" tab, if present; it is re-scanned when its folder changes. */
    private val localTab = sources.indexOfFirst { it is LocalImages }
    private var localReload: Job? = null
    private val localWatcher: FileObserver? = (sources.getOrNull(localTab) as? LocalImages)?.let { src ->
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or FileObserver.CLOSE_WRITE
        @Suppress("DEPRECATION") // File ctor is API 29+; the path ctor works down to minSdk
        object : FileObserver(src.dir().absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) = refreshLocal()
        }.apply { startWatching() }
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

    fun selectTab(i: Int) {
        tab = i
        if (catalogs[i] == null || catalogs[i] is CatalogState.Failed) load(i)
    }

    /** Re-scan the local folder (debounced — a single file push fires several events). */
    fun refreshLocal() {
        if (localTab < 0) return
        localReload?.cancel()
        localReload = viewModelScope.launch {
            delay(300)
            val fresh = try {
                CatalogState.Loaded(sources[localTab].load())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CatalogState.Failed(e.message ?: e.toString())
            }
            catalogs[localTab] = fresh
        }
    }

    override fun onCleared() {
        localWatcher?.stopWatching()
    }

    fun load(i: Int) {
        catalogs[i] = CatalogState.Loading
        viewModelScope.launch {
            catalogs[i] = try {
                CatalogState.Loaded(sources[i].load())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CatalogState.Failed(e.message ?: e.toString())
            }
        }
    }

}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permissions =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Whether [permissions] are granted; the frame settings scan only then. */
    private var permitted by mutableStateOf(false)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = hasPermissions()
    }

    private fun hasPermissions() = permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Coil.setImageLoader(ImageLoader.Builder(this).okHttpClient(Http.client).crossfade(true).build())
        permitted = hasPermissions()
        if (!permitted) askPermissions.launch(permissions)
        // a fresh start without a chosen frame opens the frame settings; cancelling them leaves the catalog
        if (savedInstanceState == null && vm.selected == null) {
            Log.i(TAG, "no frame selected: opening the frame settings")
            vm.openSettings()
        }
        setContent {
            App(
                vm,
                permitted = permitted,
                onRequestPermissions = { askPermissions.launch(permissions) },
                onSend = { p ->
                    when {
                        vm.selected == null -> {
                            Log.i(TAG, "no frame selected: upload of ${p.title} refused")
                            Toast.makeText(this, R.string.no_frame, Toast.LENGTH_SHORT).show()
                        }
                        hasPermissions() -> sendToFrame(p)
                        else -> askPermissions.launch(permissions)
                    }
                },
            )
        }
    }

    // Hand the upload to the background service, so it survives leaving the app and does not
    // interrupt playback in another app. A short toast confirms; the service toasts again when done.
    private fun sendToFrame(p: Picture) {
        val i = Intent(this, UploadService::class.java)
        if (p.local) i.putExtra(UploadService.EXTRA_PATH, p.fullUrl)
        else i.putExtra(UploadService.EXTRA_URL, p.fullUrl).putExtra(UploadService.EXTRA_TITLE, p.title)
        startForegroundService(i)
        Toast.makeText(this, R.string.sending, Toast.LENGTH_SHORT).show()
    }

    // Returning to the app re-scans the local folder, in case files changed while it was away.
    // Permissions may also have been granted from the system settings meanwhile.
    override fun onResume() {
        super.onResume()
        permitted = hasPermissions()
        vm.refreshLocal()
    }
}

@Composable
fun App(vm: MainViewModel, permitted: Boolean, onRequestPermissions: () -> Unit, onSend: (Picture) -> Unit) {
    // the cards take the chosen frame's shape; the frame this app was built with while none is chosen
    val aspect = (vm.selected?.lookup?.formatOrNull ?: FrameFormats.DEFAULT).aspect
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), shape = RectangleShape) {
            Column(Modifier.padding(horizontal = 48.dp, vertical = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(32.dp))
                    TabRow(selectedTabIndex = vm.tab) {
                        vm.sources.forEachIndexed { i, s ->
                            Tab(selected = vm.tab == i, onFocus = { vm.selectTab(i) }, modifier = Modifier.onTap { vm.selectTab(i) }) {
                                Text(s.name.text(), Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = vm::openSettings, modifier = Modifier.onTap(vm::openSettings)) {
                        Text(vm.selected?.let { stringResource(R.string.header_frame, it.name) } ?: stringResource(R.string.header_no_frame))
                    }
                }
                Spacer(Modifier.height(16.dp))
                LaunchedEffect(Unit) { vm.selectTab(vm.tab) }
                when (val st = vm.catalogs[vm.tab] ?: CatalogState.Loading) {
                    CatalogState.Loading -> Text(stringResource(R.string.catalog_loading))
                    is CatalogState.Failed -> Column {
                        Text(stringResource(R.string.catalog_failed, st.message))
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.load(vm.tab) }, modifier = Modifier.onTap { vm.load(vm.tab) }) { Text(stringResource(R.string.retry)) }
                    }
                    is CatalogState.Loaded -> if (st.items.isEmpty()) {
                        Text(
                            stringResource(R.string.local_empty, LocalImages.ADB_DIR),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(st.items, key = { it.id }) { p -> PictureCard(p, aspect) { onSend(p) } }
                    }
                }
            }
            if (vm.settingsOpen) {
                FrameSettingsDialog(
                    selected = vm.selected,
                    permitted = permitted,
                    onRequestPermissions = onRequestPermissions,
                    onSelect = vm::select,
                    onCancel = vm::closeSettings,
                )
            }
        }
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

