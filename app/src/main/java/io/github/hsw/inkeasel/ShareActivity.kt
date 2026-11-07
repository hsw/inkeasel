package io.github.hsw.inkeasel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.hsw.inkeasel.nowplaying.UploadOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * "Share → InkEasel" from a gallery or a file manager (`ACTION_SEND` of any image type). Invisible (translucent, no
 * history, not in the recents): it copies the shared picture into the pictures folder ("My pictures"),
 * through a `.part` file renamed when complete ([ShareRules.MAX_BYTES] at most), then hands it to
 * [UploadService] as a manual upload (so it becomes the default picture) and finishes. The copy runs while
 * the activity lives, since the read grant of the sender's URI lasts only that long.
 *
 * Without the Bluetooth permissions ([BlePermissions]) it still copies the picture, remembers it as the
 * pending share ([PendingShare]) and opens [MainActivity], which asks for them and sends the picture once
 * they are granted and a frame is chosen (that path waits for a frame as well). With the permissions but no
 * chosen frame it keeps the picture in "My pictures" and says so itself, while it is in the foreground: the
 * upload service's own refusal toast would come after the share has finished, when Android 13+ suppresses
 * toasts of an app without the notification permission. For the same reason its toast on sending does not
 * promise a result: the service's result toast may not appear.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // a recreated instance (the process died during a copy) has nothing left to do: the grant is gone
        if (savedInstanceState != null) {
            finish()
            return
        }
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (uri == null || !ShareRules.acceptShare(uri.scheme, uri.authority, packageName)) {
            Log.w(TAG, "share refused: ${uri?.scheme}://${uri?.authority} type=${intent.type}")
            toast(Txt(R.string.share_unsupported))
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                val file = copy(uri)
                Log.i(TAG, "shared picture saved: ${file.name} (${file.length()} B)")
                val frameSelected = FrameStore(this@ShareActivity).selected() != null
                when {
                    !BlePermissions.granted(this@ShareActivity) -> waitForPermissions(file)
                    !frameSelected -> {
                        Log.i(TAG, "no frame selected: ${file.name} stays in the pictures folder")
                        toast(Txt(R.string.share_no_frame))
                    }
                    else -> {
                        UploadService.startManual(this@ShareActivity, path = file.absolutePath)
                        toast(Txt(R.string.share_sending))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "shared picture not saved", e)
                toast(Txt(R.string.share_failed, UploadOutcome.reason(e)))
            }
            finish()
        }
    }

    /** Copy the shared stream into the pictures folder ([ShareRules.save]). */
    private suspend fun copy(uri: Uri): File = withContext(Dispatchers.IO) {
        ShareRules.save(
            dir = LocalImages(application).dir(),
            displayName = displayName(uri),
            mime = contentResolver.getType(uri) ?: intent.type,
            nowMs = System.currentTimeMillis(),
            open = { contentResolver.openInputStream(uri) ?: throw IOException("no stream for $uri") },
            check = { ensureActive() },
        )
    }

    /** The sender's name for the picture, if it gives one. */
    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (e: Exception) { // a provider without the column, or one that throws
        Log.w(TAG, "no display name for $uri: $e")
        null
    }

    private fun waitForPermissions(file: File) {
        Log.i(TAG, "no Bluetooth permission: ${file.name} waits in the app")
        PendingShare(this).save(file.absolutePath, System.currentTimeMillis())
        toast(Txt(R.string.share_needs_permission))
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_PENDING_SHARE, true)
        )
    }

    /** Through the application context: the toast outlives this activity, which finishes at once. */
    private fun toast(t: Txt) = Toast.makeText(applicationContext, t.resolve(this), Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "ShareActivity"
    }
}

/**
 * A shared picture waiting for the Bluetooth permissions (and a chosen frame), in prefs `share`: one path,
 * the newest share wins. [MainActivity] sends it, or drops it once stale ([ShareRules.sendPending]).
 */
class PendingShare(context: Context) {
    private val prefs = context.getSharedPreferences("share", Context.MODE_PRIVATE)

    fun save(path: String, savedAtMs: Long) {
        prefs.edit().putString(PATH, path).putLong(SAVED_AT, savedAtMs).apply()
    }

    /**
     * The pending path to send now, cleared from the prefs; `null` (and cleared) if there is none or it is
     * stale or its file is gone.
     */
    fun take(nowMs: Long): String? {
        val path = prefs.getString(PATH, null) ?: return null
        val savedAt = prefs.getLong(SAVED_AT, 0L)
        prefs.edit().clear().apply()
        return path.takeIf { ShareRules.sendPending(savedAt, nowMs, File(it).isFile) }
            .also { if (it == null) Log.i("PendingShare", "dropped (stale or gone): ${File(path).name}") }
    }

    private companion object {
        const val PATH = "path"
        const val SAVED_AT = "savedAt"
    }
}
