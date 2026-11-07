package io.github.hsw.inkeasel.nowplaying

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.github.hsw.inkeasel.FrameStore
import io.github.hsw.inkeasel.UploadService

/**
 * The process-wide [FrameController], wired for production: uploads go through `UploadService` with
 * `auto=true`, the default picture and the frame key live in shared preferences, nothing is uploaded while
 * no frame is chosen ([FrameStore]), and the log goes to logcat under [FrameController.TAG]. Created on
 * first use with the application context.
 */
object FrameControllers {
    @Volatile
    private var instance: FrameController? = null

    fun get(context: Context): FrameController =
        instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }

    private fun create(app: Context) = FrameController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        uploader = UploadService.autoUploader(app),
        defaults = PrefsDefaultStore(app) { UploadService.resolvePhoto(app, it) },
        state = PrefsFrameState(app),
        log = { Log.i(FrameController.TAG, it) },
        frameSelected = FrameStore(app).let { store -> { store.selected() != null } },
    )
}
