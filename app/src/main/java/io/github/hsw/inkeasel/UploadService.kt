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
import io.github.hsw.inkeasel.nowplaying.Art
import io.github.hsw.inkeasel.nowplaying.FrameController
import io.github.hsw.inkeasel.nowplaying.FrameControllers
import io.github.hsw.inkeasel.nowplaying.Source
import io.github.hsw.inkeasel.nowplaying.UploadOutcome
import io.github.hsw.inkeasel.nowplaying.Uploader
import java.io.File

/**
 * Keeps the process in the foreground while an upload runs, so playback in another app (e.g. a video player)
 * is not interrupted — the upload is Bluetooth only and never brings UI forward. Start it with
 * `startForegroundService`; it is a transient foreground service: it hands the request to the in-process
 * [UploadEngine] as held work, and stops once the engine has reported the last held request
 * ([UploadEngine.onIdle]), so nothing stays resident when idle. The queue, the worker, the toasts and the
 * reports live in the engine, not here.
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
 * result goes to `FrameController.onUploadResult(key, ok)` with the `key` extra. An automatic request carries
 * the controller's `stamp` ([FrameController.manualUploads] when it was decided). The engine's queue rules
 * ([io.github.hsw.inkeasel.nowplaying.UploadEngineCore]) decide what is replaced or dropped as stale.
 *
 * Nothing is uploaded while no frame is chosen in the settings ([FrameStore]): every request is refused at
 * once, before Bluetooth is touched ([refuseNoFrame]). Without the Bluetooth permissions ([BlePermissions])
 * it is not started at all: the app asks for them before a manual upload, and [autoUploader] reports an
 * automatic one as failed.
 *
 * Uploads are spaced by the frame's panel refresh ([DrawGate], via [FrameClient.awaitIdle]); a manual request
 * that has to wait toasts how long.
 *
 * It stops with `stopSelfResult(lastStartId)`, so a start accepted by the system but not yet delivered to
 * [onStartCommand] keeps it running. [onDestroy] reports nothing: the engine is the only reporter, and a
 * process death takes the controller with it.
 */
class UploadService : Service() {
    private var lastStartId = 0 // the latest start delivered to onStartCommand; main thread only
    private val engine by lazy { UploadEngine.get(this) }
    private val frames by lazy { FrameStore(this) }
    private val controller by lazy { FrameControllers.get(applicationContext) }
    private val idle: () -> Unit = { stopIfIdle() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH_ID, getString(R.string.upload_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        engine.onIdle = idle
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
        if (!auto) engine.drawWaitMs().takeIf { it > 0 }?.let { // known only when no upload is in flight
            toast(getString(R.string.upload_wait, ((it + 999) / 1000).toInt()))
        }
        engine.submit(req, hold = true) // a replaced request is reported by the engine
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
        else toast(getString(R.string.upload_no_frame))
    }

    private fun parse(intent: Intent, auto: Boolean, key: String?, stamp: Long): UploadEngine.Req? {
        val resolve = { name: String -> resolvePhoto(this, name) }
        val picked = Source.fromExtras(
            path = intent.getStringExtra(EXTRA_PATH),
            photo = intent.getStringExtra(EXTRA_PHOTO),
            url = intent.getStringExtra(EXTRA_URL),
            title = intent.getStringExtra(EXTRA_TITLE),
            resolvePhoto = resolve,
        ) ?: return null
        return UploadEngine.Req(picked.source, picked.art, auto, key, stamp)
    }

    /**
     * Stop unless held work is still queued or in flight in the engine. `stopSelfResult` refuses if the system
     * has accepted a newer start whose [onStartCommand] has not run yet; the service then stays and that start
     * submits its request. Main thread only (also as [UploadEngine.onIdle]).
     */
    private fun stopIfIdle() {
        if (engine.heldCount > 0) return // do not cut off an upload
        if (stopSelfResult(lastStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
        else Log.i(TAG, "a newer start is pending; staying")
    }

    private fun toast(msg: String) = Handler(Looper.getMainLooper()).post {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    private fun notification(title: String?): Notification {
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH_ID) else Notification.Builder(this)
        return b.setContentTitle(getString(R.string.upload_sending))
            .setContentText(title.orEmpty())
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        if (engine.onIdle === idle) engine.onIdle = null // the engine keeps any work and reports it itself
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

        /** Start a manual upload of [art] ([title] for the toast and notification; a file is named by itself). */
        fun startManual(context: Context, art: Art, title: String? = null) {
            val i = Intent(context, UploadService::class.java).putArt(art)
            title?.let { i.putExtra(EXTRA_TITLE, it) }
            context.startForegroundService(i)
        }

        private fun Intent.putArt(art: Art): Intent = when (art) {
            is Art.Url -> putExtra(EXTRA_URL, art.url)
            is Art.File -> putExtra(EXTRA_PATH, art.path)
        }

        /** Find a file in the pictures folder by name, with or without extension, case-insensitive. */
        fun resolvePhoto(context: Context, name: String): File? {
            val dir = LocalImages.dir(context)
            File(dir, name).takeIf { it.isFile }?.let { return it }
            for (e in LocalImages.EXTENSIONS) {
                File(dir, "$name.$e").takeIf { it.isFile }?.let { return it }
            }
            return dir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension.equals(name, ignoreCase = true) }
        }

        /**
         * The production [Uploader] of the `FrameController`: starts this service with `auto=true`, `key` and
         * `stamp` (called under the controller's lock, so the stamp matches the decision). Without the Bluetooth
         * permissions it reports the upload as failed at once instead.
         */
        fun autoUploader(context: Context) = Uploader { art, title, key ->
            // without them the service could not even go foreground (connectedDevice type, Android 14+)
            if (!BlePermissions.granted(context)) {
                Log.w(TAG, "no Bluetooth permission: auto upload not started: $key")
                FrameControllers.get(context).onUploadResult(key, false)
                return@Uploader
            }
            val i = Intent(context, UploadService::class.java)
                .putExtra(EXTRA_AUTO, true)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_STAMP, FrameControllers.get(context).manualUploads)
                .putExtra(EXTRA_TITLE, title)
                .putArt(art)
            try {
                context.startForegroundService(i)
            } catch (e: RuntimeException) { // e.g. a background start refused by the system
                Log.w(TAG, "auto upload not started: $key", e)
                FrameControllers.get(context).onUploadResult(key, false)
            }
        }
    }
}
