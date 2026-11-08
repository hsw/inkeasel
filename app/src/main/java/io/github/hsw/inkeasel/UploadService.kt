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
import io.github.hsw.inkeasel.nowplaying.Source
import io.github.hsw.inkeasel.nowplaying.UploadOutcome
import java.io.File

/**
 * Keeps the process in the foreground while an upload runs, so playback in another app (e.g. a video player)
 * is not interrupted — the upload is Bluetooth only and never brings UI forward. Start it with
 * `startForegroundService`; it is a transient foreground service: it hands a manual request to the in-process
 * [UploadEngine] as held work, and stops once the engine has reported the last held request
 * ([UploadEngine.onIdle]), so nothing stays resident when idle. The queue, the worker, the toasts and the
 * reports live in the engine, not here.
 *
 * Trigger by intent (keeps the current app on screen):
 *   adb shell am start-foreground-service -n io.github.hsw.inkeasel/.UploadService -e photo seaworld-abudhabi
 * Extras (first usable wins): `path` = an existing absolute file path, `photo` = file name that resolves in
 * the pictures folder (extension optional), `url` = remote image; optional `title` for the toast and
 * notification, for any of them. Every request it takes is manual: it toasts and, on success, becomes the
 * frame's default picture (`FrameController.onManualUpload`).
 *
 * Automatic uploads (a now-playing poster, the revert to the default) do not come through here: the
 * `FrameController` submits them to the engine itself (`AutoUploader`). Only while the notification listener
 * is not bound, so nothing keeps the process alive, it also starts this service with [ACTION_HOLD]: no
 * payload, the service only goes foreground and stays until the engine's held work is done.
 *
 * Nothing is uploaded while no frame is chosen in the settings ([FrameStore]): a manual request is refused at
 * once, before Bluetooth is touched, with a toast. Without the Bluetooth permissions ([BlePermissions]) it is
 * not started at all: the app asks for them before a manual upload, and `AutoUploader` reports an automatic
 * one as failed.
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
        if (intent?.action == ACTION_HOLD) {
            startForeground(NOTIF_ID, notification(null)) // must be called within 5 s of start
            stopIfIdle() // the held work may already be done
            return START_NOT_STICKY
        }
        val req = intent?.let { parse(it) }
        // must be called within 5 s of start; a picture without a title is named by UploadOutcome.untitled
        startForeground(NOTIF_ID, notification(req?.let { it.source.title.ifBlank { UploadOutcome.untitled(null).resolve(this) } }))
        if (frames.selected() == null) { // refuse without touching Bluetooth
            Log.w(TAG, "no frame selected: manual upload refused: ${req?.source?.title}")
            toast(getString(R.string.upload_no_frame))
            stopIfIdle()
            return START_NOT_STICKY
        }
        if (req == null) {
            toast(getString(R.string.upload_no_picture))
            stopIfIdle()
            return START_NOT_STICKY
        }
        engine.drawWaitMs().takeIf { it > 0 }?.let { // known only when no upload is in flight
            toast(getString(R.string.upload_wait, ((it + 999) / 1000).toInt()))
        }
        engine.submit(req, hold = true) // a replaced request is reported by the engine
        return START_NOT_STICKY
    }

    private fun parse(intent: Intent): UploadEngine.Req? {
        val resolve = { name: String -> resolvePhoto(this, name) }
        val picked = Source.fromExtras(
            path = intent.getStringExtra(EXTRA_PATH),
            photo = intent.getStringExtra(EXTRA_PHOTO),
            url = intent.getStringExtra(EXTRA_URL),
            title = intent.getStringExtra(EXTRA_TITLE),
            resolvePhoto = resolve,
        ) ?: return null
        // no key and no stamp: a manual request is never stale (FrameController.isStale)
        return UploadEngine.Req(picked.source, picked.art, auto = false, key = null, stamp = 0)
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

        /** Internal: hold the process in the foreground for the engine's held work; no payload, no upload. */
        const val ACTION_HOLD = "io.github.hsw.inkeasel.action.HOLD"

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
         * Hold the process in the foreground until the engine's held work is done ([ACTION_HOLD]), for an
         * automatic upload submitted while the notification listener is not bound (`AutoUploader`). Throws
         * if the system refuses the start (a background start on Android 12+).
         */
        fun startHold(context: Context) {
            context.startForegroundService(Intent(context, UploadService::class.java).setAction(ACTION_HOLD))
        }
    }
}
