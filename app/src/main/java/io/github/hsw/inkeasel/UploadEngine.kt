package io.github.hsw.inkeasel

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import io.github.hsw.inkeasel.nowplaying.Art
import io.github.hsw.inkeasel.nowplaying.FrameController
import io.github.hsw.inkeasel.nowplaying.FrameControllers
import io.github.hsw.inkeasel.nowplaying.UploadEngineCore
import io.github.hsw.inkeasel.nowplaying.UploadReports
import io.github.hsw.inkeasel.nowplaying.UploadRequest
import java.io.File

/**
 * The process-wide upload engine: the one queue and worker ([UploadEngineCore]) wired to the frame
 * ([FrameClient] with the application context), and its reports ([UploadReports]: the toasts of manual
 * uploads and the `FrameController` callbacks). A picture is rendered in the selected frame's format
 * ([FrameStore], [FrameFormats]). Manual uploads come from [UploadService], which holds the process in the
 * foreground for them; automatic ones come straight from the controller ([submitAuto], wired in
 * `FrameControllers`), held by the service only while the notification listener is not bound, or once it
 * unbinds with them still queued or in flight ([holdAll]).
 *
 * The controller is resolved on each callback, never while the engine is built, so building the engine from
 * inside `FrameControllers.get` cannot recurse into a second controller. Lock order is controller → engine:
 * the controller submits under its lock, and the core calls back with none of its own locks held. [onIdle]
 * runs on the main thread.
 */
class UploadEngine private constructor(private val app: Context) {
    private val frame = FrameClient(app)
    private val frames = FrameStore(app)
    private val main = Handler(Looper.getMainLooper())
    private val controller: FrameController get() = FrameControllers.get(app)

    /** Called on the main thread when the last held request has been reported ([UploadEngineCore]). */
    @Volatile
    var onIdle: (() -> Unit)? = null

    private val reports = UploadReports(
        onUploadResult = { key, ok -> controller.onUploadResult(key, ok) },
        onManualUpload = { controller.onManualUpload(it) },
        toast = { msg -> main.post { Toast.makeText(app, msg.resolve(app), Toast.LENGTH_SHORT).show() } },
        log = logTo(TAG),
    )

    private val core = UploadEngineCore<UploadRequest>(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        isAuto = { it.auto },
        awaitIdle = { frame.awaitIdle() },
        upload = ::upload,
        isStale = { controller.isStale(it.auto, it.stamp, it.key) },
        onDone = reports::done,
        onDropped = reports::dropped,
        onIdle = { main.post { onIdle?.invoke() } },
        log = logTo(TAG),
    )

    /**
     * Queue [req] ([hold]: keep the foreground service up for it; a manual one always does). A queued request
     * of its kind that it replaces is reported at once, from inside this call ([UploadReports.dropped]).
     */
    fun submit(req: UploadRequest, hold: Boolean) {
        core.submit(req, hold)
    }

    /**
     * Queue an automatic upload of the `FrameController` ([UploadRequest.auto]). Called under the controller's
     * lock; a request it replaces is reported to the controller from inside this call, under that lock.
     */
    fun submitAuto(art: Art, title: String, key: String, stamp: Long, hold: Boolean) =
        submit(UploadRequest.auto(art, title, key, stamp), hold)

    /**
     * Make the unheld work queued or in flight held ([UploadEngineCore.holdAll]): the notification listener,
     * which kept the process alive for it, has gone. True if the caller should start the hold service.
     */
    fun holdAll(): Boolean = core.holdAll()

    /** Held requests not yet reported; the foreground service stays while there are any. */
    val heldCount: Int get() = core.heldCount

    /** How long the frame still draws the previous picture, in ms ([DrawGate]); 0 if it is free. */
    fun drawWaitMs(): Long = frame.gate.waitMs()

    private suspend fun upload(req: UploadRequest) {
        // the selected frame's format: its size, inks and packing (a later rescan may change it, see FrameClient);
        // checked first, so a request without a frame fails before any download or Bluetooth
        val sel = frames.selected() ?: throw TxtException(Txt(R.string.upload_err_no_frame))
        val format = when (val l = sel.lookup) {
            is FormatLookup.Supported -> l.format
            is FormatLookup.Unsupported -> throw TxtException(Txt(R.string.frame_err_unsupported, sel.name, l.reason))
        }
        val bytes = when (val art = req.art) {
            is Art.File -> withContext(Dispatchers.IO) { File(art.path).readBytes() }
            is Art.Url -> Http.bytes(art.url)
        }
        val packed = withContext(Dispatchers.Default) { EpdImage.render(bytes, format) }
        frame.upload(format, packed)
    }

    companion object {
        private const val TAG = "UploadEngine"

        @SuppressLint("StaticFieldLeak") // holds only the application context
        @Volatile
        private var instance: UploadEngine? = null

        fun get(context: Context): UploadEngine =
            instance ?: synchronized(this) { instance ?: UploadEngine(context.applicationContext).also { instance = it } }
    }
}

/**
 * The Android side of the pure classes' `log(message, throwable)` callbacks ([UploadReports],
 * [UploadEngineCore], `AutoUploader`), one rule for all: a message with a throwable is a fault and logged as a
 * warning, one without is information.
 */
internal fun logTo(tag: String): (String, Throwable?) -> Unit =
    { msg, e -> if (e != null) Log.w(tag, msg, e) else Log.i(tag, msg) }
