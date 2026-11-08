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
import io.github.hsw.inkeasel.nowplaying.Source
import io.github.hsw.inkeasel.nowplaying.UploadEngineCore
import io.github.hsw.inkeasel.nowplaying.UploadOutcome
import java.io.File

/**
 * The process-wide upload engine: the one queue and worker ([UploadEngineCore]) wired to the frame
 * ([FrameClient] with the application context), the toasts of manual uploads and the `FrameController`
 * callbacks. A picture is rendered in the selected frame's format ([FrameStore], [FrameFormats]). Manual
 * uploads come from [UploadService], which holds the process in the foreground for them; automatic ones come
 * straight from the controller ([submitAuto], wired in `FrameControllers`), held by the service only while
 * the notification listener is not bound.
 *
 * The controller is resolved on each callback, never while the engine is built, so building the engine from
 * inside `FrameControllers.get` cannot recurse into a second controller. Lock order is controller → engine:
 * the controller submits under its lock, and the core calls back with none of its own locks held. [onIdle]
 * runs on the main thread.
 */
class UploadEngine private constructor(private val app: Context) {
    /**
     * One upload: what to send and how it was named ([source]), the picture ([art]), and for an automatic one
     * ([auto]) the controller's [key] and [stamp] ([FrameController.manualUploads] when it was decided).
     */
    class Req(val source: Source, val art: Art, val auto: Boolean, val key: String?, val stamp: Long)

    private val frame = FrameClient(app)
    private val frames = FrameStore(app)
    private val main = Handler(Looper.getMainLooper())
    private val controller: FrameController get() = FrameControllers.get(app)

    /** Called on the main thread when the last held request has been reported ([UploadEngineCore]). */
    @Volatile
    var onIdle: (() -> Unit)? = null

    private val core = UploadEngineCore<Req>(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        isAuto = { it.auto },
        awaitIdle = { frame.awaitIdle() },
        upload = ::upload,
        isStale = { controller.isStale(it.auto, it.stamp, it.key) },
        onDone = ::done,
        onDropped = ::dropped,
        onIdle = { main.post { onIdle?.invoke() } },
        log = { msg, e -> Log.w(TAG, msg, e) },
    )

    /** Queue [req] ([hold]: keep the foreground service up for it; a manual one always does). */
    fun submit(req: Req, hold: Boolean): Req? = core.submit(req, hold)

    /**
     * Queue an automatic upload of the `FrameController` ([key], [stamp]: see [Req]); [title] is the film's,
     * or empty for the default picture (a file is then named by itself, for the log). Called under the
     * controller's lock; a request it replaces is reported by the engine.
     */
    fun submitAuto(art: Art, title: String, key: String, stamp: Long, hold: Boolean) {
        val source = when (art) {
            is Art.Url -> Source(Source.Type.URL, art.url, title)
            is Art.File -> Source(Source.Type.PATH, art.path, title.ifBlank { File(art.path).nameWithoutExtension })
        }
        submit(Req(source, art, auto = true, key = key, stamp = stamp), hold)
    }

    /** Held requests not yet reported; the foreground service stays while there are any. */
    val heldCount: Int get() = core.heldCount

    /** How long the frame still draws the previous picture, in ms ([DrawGate]); 0 if it is free. */
    fun drawWaitMs(): Long = frame.gate.waitMs()

    private suspend fun upload(req: Req) {
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

    /** Toast, report and remember as [UploadOutcome] decides. */
    private fun done(req: Req, ok: Boolean, error: Throwable?) {
        if (!ok) Log.w(TAG, "upload failed", error)
        val out = UploadOutcome.of(req.auto, ok, error)
        Log.i(TAG, "${kind(req)} upload ${if (ok) "done" else "failed"}: ${req.source.title} (key=${req.key})")
        out.toast?.let { toast(it.resolve(app)) }
        if (out.report) report(req, ok)
        if (out.remember) controller.onManualUpload(req.source)
    }

    /**
     * A request dropped unsent: an automatic one is reported as failed, so the controller can retry or
     * reconcile; a replaced manual one is simply superseded by the newer pick.
     */
    private fun dropped(req: Req, why: UploadEngineCore.Drop) {
        when (why) {
            UploadEngineCore.Drop.REPLACED ->
                Log.i(TAG, "replaced by a newer request: ${req.source.title} (key=${req.key})")
            UploadEngineCore.Drop.STALE ->
                Log.i(TAG, "auto upload dropped (a manual upload came after it, the poster is off, or its poster plays again): ${req.source.title} (key=${req.key})")
        }
        if (req.auto) report(req, ok = false)
    }

    private fun report(req: Req, ok: Boolean) {
        val key = req.key
        if (key != null) controller.onUploadResult(key, ok) else Log.w(TAG, "auto upload without key: not reported")
    }

    private fun kind(req: Req) = if (req.auto) "auto" else "manual"

    private fun toast(msg: String) = main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }

    companion object {
        private const val TAG = "UploadEngine"

        @SuppressLint("StaticFieldLeak") // holds only the application context
        @Volatile
        private var instance: UploadEngine? = null

        fun get(context: Context): UploadEngine =
            instance ?: synchronized(this) { instance ?: UploadEngine(context.applicationContext).also { instance = it } }
    }
}
