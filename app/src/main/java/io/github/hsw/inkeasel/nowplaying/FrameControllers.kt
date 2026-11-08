package io.github.hsw.inkeasel.nowplaying

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.github.hsw.inkeasel.BlePermissions
import io.github.hsw.inkeasel.FrameStore
import io.github.hsw.inkeasel.UploadEngine
import io.github.hsw.inkeasel.UploadService
import io.github.hsw.inkeasel.logTo

/**
 * The process-wide [FrameController], wired for production: automatic uploads go straight to the in-process
 * [UploadEngine] ([AutoUploader]; held by `UploadService` only while [NowPlayingListener] is not bound), the
 * default picture and the frame key live in shared preferences, nothing is uploaded while no frame is chosen
 * ([FrameStore]), no poster while it is switched off ([FeatureStore]), and the log goes to logcat under
 * [FrameController.TAG]. Created on first use with the application context. The engine is resolved on each
 * upload, not while the controller is built.
 */
object FrameControllers {
    @Volatile
    private var instance: FrameController? = null

    @Volatile
    private var uploader: AutoUploader? = null

    fun get(context: Context): FrameController =
        instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }

    /**
     * [NowPlayingListener] has unbound: hold the automatic work it left unheld ([AutoUploader.listenerLost]).
     * Without a controller yet there is none.
     */
    fun listenerLost() {
        uploader?.listenerLost()
    }

    private fun create(app: Context): FrameController {
        lateinit var controller: FrameController
        val uploader = AutoUploader(
            permitted = { BlePermissions.granted(app) },
            listenerBound = { NowPlayingListener.connected },
            // under the controller's lock (FrameController.request), so the stamp matches the decision
            submit = { art, title, key, hold ->
                UploadEngine.get(app).submitAuto(art, title, key, controller.manualUploads, hold)
            },
            startHold = { UploadService.startHold(app) },
            refuse = { key -> controller.onUploadResult(key, false) },
            holdAll = { UploadEngine.get(app).holdAll() },
            log = logTo(FrameController.TAG),
        )
        this.uploader = uploader
        controller = FrameController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            uploader = uploader,
            defaults = PrefsDefaultStore(app) { UploadService.resolvePhoto(app, it) },
            state = PrefsFrameState(app),
            log = { Log.i(FrameController.TAG, it) },
            frameSelected = FrameStore(app).let { store -> { store.selected() != null } },
            posterEnabled = FeatureStore(app).let { store -> { store.posterEnabled } },
        )
        return controller
    }
}
