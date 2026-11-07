package io.github.hsw.inkeasel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import io.github.hsw.inkeasel.nowplaying.Art
import io.github.hsw.inkeasel.nowplaying.FrameController
import io.github.hsw.inkeasel.nowplaying.FrameControllers
import io.github.hsw.inkeasel.nowplaying.Source
import io.github.hsw.inkeasel.nowplaying.UploadOutcome
import io.github.hsw.inkeasel.nowplaying.UploadQueue
import io.github.hsw.inkeasel.nowplaying.Uploader
import java.io.File

/**
 * Sends one image to the frame in the background, so playback in another app (e.g. a video player) is not
 * interrupted — the upload is Bluetooth only and never brings UI forward. Start it with
 * `startForegroundService`; it is a transient foreground service: it uploads, shows a short toast for a
 * manual upload, then stops itself, so nothing stays resident when idle.
 *
 * Trigger by intent (keeps the current app on screen):
 *   adb shell am start-foreground-service -n io.github.hsw.inkeasel/.UploadService -e photo seaworld-abudhabi
 * Extras (first usable wins): `path` = an existing absolute file path, `photo` = file name that resolves in
 * the pictures folder (extension optional), `url` = remote image; optional `title` for the toast and
 * notification, for any of them.
 *
 * A manual upload (the default) toasts and, on success, becomes the frame's default picture
 * (`FrameController.onManualUpload`). `auto=true` (boolean) marks an upload started by the
 * `FrameController` (a now-playing poster or the revert to the default): no toast, log only, and the
 * result goes to `FrameController.onUploadResult(key, ok)` with the `key` extra. Requests queue in an
 * [UploadQueue]: a newer one replaces a queued one of the same kind (a replaced automatic one is reported
 * as failed), an automatic request never replaces a manual one, and a manual one goes first. An automatic
 * request carries the controller's `stamp` ([FrameController.manualUploads] when it was decided); if a manual
 * upload succeeded since, it is dropped before upload and reported as failed, so a revert or a poster queued
 * behind a manual upload never covers the user's pick. A poster still queued when the poster is switched off
 * in the settings is dropped the same way.
 *
 * Nothing is uploaded while no frame is chosen in the settings ([FrameStore]): every request is refused at
 * once, before Bluetooth is touched ([refuseNoFrame]). A picture is rendered in the chosen frame's format
 * ([FrameFormats]: size, inks, packing).
 *
 * Uploads are spaced by the frame's panel refresh ([DrawGate], via [FrameClient.awaitIdle]): the worker waits
 * until the frame has finished drawing the previous picture before it takes the next request, also when the
 * previous upload ran in an earlier service start. The wait is not a failure; a manual request that has to
 * wait toasts how long.
 *
 * It stops with `stopSelfResult(lastStartId)`, so a start accepted by the system but not yet delivered to
 * [onStartCommand] keeps it running. If it is destroyed anyway, every request still owed a result (in flight
 * or queued) is reported as failed in [onDestroy], so the `FrameController` can retry or reconcile.
 */
class UploadService : Service() {
    private class Req(val source: Source, val art: Art, val auto: Boolean, val key: String?, val stamp: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = UploadQueue<Req> { it.auto } // one upload at a time
    private val wake = Channel<Unit>(Channel.CONFLATED) // a request was queued
    private var worker: Job? = null // main thread only
    private var lastStartId = 0 // the latest start delivered to onStartCommand; main thread only
    private val frame by lazy { FrameClient(this) }
    private val frames by lazy { FrameStore(this) }
    private val controller by lazy { FrameControllers.get(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH_ID, getString(R.string.upload_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val auto = intent?.getBooleanExtra(EXTRA_AUTO, false) == true
        val key = intent?.getStringExtra(EXTRA_KEY)
        val stamp = intent?.getLongExtra(EXTRA_STAMP, -1L)?.takeIf { it >= 0 } ?: controller.manualUploads
        val req = intent?.let { parse(it, auto, key, stamp) }
        // must be called within 5 s of start; a picture without a title is named by UploadOutcome.untitled
        startForeground(NOTIF_ID, notification(req?.let { it.source.title.ifBlank { UploadOutcome.untitled(key).resolve(this) } }))
        if (frames.selected() == null) {
            refuseNoFrame(auto, key, req?.source?.title)
            stopIfIdle()
            return START_NOT_STICKY
        }
        if (req == null) {
            if (auto) {
                Log.w(TAG, "auto upload: no picture (key=$key)")
                key?.let { controller.onUploadResult(it, false) }
            } else {
                toast(getString(R.string.upload_no_picture))
            }
            stopIfIdle()
            return START_NOT_STICKY
        }
        if (!auto) frame.gate.waitMs().takeIf { it > 0 }?.let { // known only when no upload is in flight
            toast(getString(R.string.upload_wait, ((it + 999) / 1000).toInt()))
        }
        queue.offer(req)?.let { replaced ->
            Log.i(TAG, "replaced by a newer request: ${replaced.source.title} (key=${replaced.key})")
            if (replaced.auto) report(replaced, ok = false)
        }
        wake.trySend(Unit)
        ensureWorker()
        return START_NOT_STICKY
    }

    /**
     * No frame is chosen: refuse without touching Bluetooth. A manual request toasts where to choose one. An
     * automatic one is reported as failed rather than dropped: the controller keeps its key pending until a
     * result comes, and a silently dropped request would block that key for good. The controller itself
     * schedules nothing without a frame, so this happens only in a race, and its retries are bounded anyway
     * (a failed revert at most [FrameController.MAX_REVERT_ATTEMPTS] times, 60 s apart; a poster only on the
     * next transition into PLAYING).
     */
    private fun refuseNoFrame(auto: Boolean, key: String?, title: String?) {
        Log.w(TAG, "no frame selected: ${if (auto) "auto" else "manual"} upload refused: $title (key=$key)")
        if (auto) key?.let { controller.onUploadResult(it, false) }
        else toast(getString(R.string.no_frame))
    }

    private fun parse(intent: Intent, auto: Boolean, key: String?, stamp: Long): Req? {
        val resolve = { name: String -> resolvePhoto(this, name) }
        val picked = Source.fromExtras(
            path = intent.getStringExtra(EXTRA_PATH),
            photo = intent.getStringExtra(EXTRA_PHOTO),
            url = intent.getStringExtra(EXTRA_URL),
            title = intent.getStringExtra(EXTRA_TITLE),
            resolvePhoto = resolve,
        ) ?: return null
        return Req(picked.source, picked.art, auto, key, stamp)
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            val self = coroutineContext[Job]
            try {
                // Linger briefly after each upload so a rapid re-trigger reuses this service.
                while (true) {
                    if (queue.isEmpty()) withTimeoutOrNull(1_500) { wake.receive() } ?: return@launch
                    if (queue.isEmpty()) continue // a wake left over from a request already taken
                    // wait out the frame's refresh before taking a request, so a newer one can still replace
                    // it or a manual one overtake it meanwhile
                    frame.awaitIdle()
                    val req = next() ?: continue
                    try {
                        upload(req)
                        finish(req, ok = true, error = null)
                    } catch (e: CancellationException) {
                        throw e // onDestroy reported it (UploadQueue.takeAll)
                    } catch (e: Exception) {
                        Log.w(TAG, "upload failed", e)
                        finish(req, ok = false, error = e)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (worker !== self) return@withContext // a newer worker took over while this one finished
                    worker = null
                    if (!queue.isEmpty()) ensureWorker() // queued while this worker was finishing
                    else stopIfIdle()
                }
            }
        }
    }

    /**
     * Stop unless an upload runs or waits. `stopSelfResult` refuses if the system has accepted a newer start
     * whose [onStartCommand] has not run yet; the service then stays and that start queues its request.
     */
    private fun stopIfIdle() {
        if (worker?.isActive == true || !queue.isEmpty()) return // do not cut off an upload
        if (stopSelfResult(lastStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
        else Log.i(TAG, "a newer start is pending; staying")
    }

    /**
     * The next request to upload; an automatic one decided before a later manual upload, or a poster while the
     * poster is switched off, is dropped ([FrameController.isStale]).
     */
    private fun next(): Req? = queue.poll(stale = { controller.isStale(it.auto, it.stamp, it.key) }) { dropped ->
        Log.i(TAG, "auto upload dropped (a manual upload came after it, or the poster is off): ${dropped.source.title} (key=${dropped.key})")
        report(dropped, ok = false)
    }

    private suspend fun upload(req: Req) {
        val bytes = when (val art = req.art) {
            is Art.File -> withContext(Dispatchers.IO) { File(art.path).readBytes() }
            is Art.Url -> Http.bytes(art.url)
        }
        // the selected frame's format: its size, inks and packing (a later rescan may change it, see FrameClient)
        val sel = frames.selected() ?: throw TxtException(Txt(R.string.no_frame_error))
        val format = when (val l = sel.lookup) {
            is FormatLookup.Supported -> l.format
            is FormatLookup.Unsupported -> throw TxtException(txt(R.string.frame_toast_unsupported, sel.name, l.reason))
        }
        val packed = withContext(Dispatchers.Default) { EpdImage.render(bytes, format) }
        frame.upload(format, packed, status = {}, progress = {})
    }

    /** The in-flight [req] finished; report it unless [onDestroy] already did. */
    private fun finish(req: Req, ok: Boolean, error: Throwable?) {
        if (queue.settle(req)) done(req, ok, error)
    }

    /** Toast, report and remember as [UploadOutcome] decides. */
    private fun done(req: Req, ok: Boolean, error: Throwable?) {
        val out = UploadOutcome.of(req.auto, ok, error)
        Log.i(TAG, "${if (req.auto) "auto" else "manual"} upload ${if (ok) "done" else "failed"}: ${req.source.title} (key=${req.key})")
        out.toast?.let { toast(it.resolve(this)) }
        if (out.report) report(req, ok)
        if (out.remember) controller.onManualUpload(req.source)
    }

    private fun report(req: Req, ok: Boolean) {
        val key = req.key
        if (key != null) controller.onUploadResult(key, ok) else Log.w(TAG, "auto upload without key: not reported")
    }

    private fun toast(msg: String) = Handler(Looper.getMainLooper()).post {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    private fun notification(title: String?): Notification {
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH_ID) else Notification.Builder(this)
        return b.setContentTitle(getString(R.string.sending))
            .setContentText(title.orEmpty())
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        // the worker is cancelled below: report what it would never finish (it reports nothing once taken)
        for (req in queue.takeAll()) {
            Log.w(TAG, "service stopped before the upload finished: ${req.source.title} (key=${req.key})")
            done(req, ok = false, error = TxtException(Txt(R.string.upload_stopped)))
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "UploadService"
        private const val CH_ID = "upload"
        private const val NOTIF_ID = 1

        const val EXTRA_PHOTO = "photo"
        const val EXTRA_PATH = "path"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_AUTO = "auto"
        const val EXTRA_KEY = "key"
        const val EXTRA_STAMP = "stamp"

        /** Find a file in the pictures folder by name, with or without extension, case-insensitive. */
        fun resolvePhoto(context: Context, name: String): File? {
            val dir = File(context.getExternalFilesDir(null), "pictures")
            File(dir, name).takeIf { it.isFile }?.let { return it }
            for (e in LocalImages.EXTENSIONS) {
                File(dir, "$name.$e").takeIf { it.isFile }?.let { return it }
            }
            return dir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension.equals(name, ignoreCase = true) }
        }

        /**
         * The production [Uploader] of the `FrameController`: starts this service with `auto=true`, `key` and
         * `stamp` (called under the controller's lock, so the stamp matches the decision).
         */
        fun autoUploader(context: Context) = Uploader { art, title, key ->
            val i = Intent(context, UploadService::class.java)
                .putExtra(EXTRA_AUTO, true)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_STAMP, FrameControllers.get(context).manualUploads)
                .putExtra(EXTRA_TITLE, title)
            when (art) {
                is Art.Url -> i.putExtra(EXTRA_URL, art.url)
                is Art.File -> i.putExtra(EXTRA_PATH, art.path)
            }
            try {
                context.startForegroundService(i)
            } catch (e: RuntimeException) { // e.g. a background start refused by the system
                Log.w(TAG, "auto upload not started: $key", e)
                FrameControllers.get(context).onUploadResult(key, false)
            }
        }
    }
}
