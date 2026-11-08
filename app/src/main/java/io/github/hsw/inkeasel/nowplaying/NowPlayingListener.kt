package io.github.hsw.inkeasel.nowplaying

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import io.github.hsw.inkeasel.nowplaying.SessionView.Meta
import java.io.File
import java.nio.ByteBuffer

/**
 * Reads the standard MediaSession metadata (title + artwork) and playback state of every app and feeds
 * them to [FrameController] as [Playing] events. A NotificationListener is used only because it is what
 * grants `MediaSessionManager.getActiveSessions`; notifications themselves are ignored. Granted on the system
 * screen the settings open ("Permissions and features", `ListenerAccess`), or where there is none with
 * `adb shell cmd notification allow_listener io.github.hsw.inkeasel/io.github.hsw.inkeasel.nowplaying.NowPlayingListener`;
 * the system rebinds it (verified after a force-stop, expected after boot), which also keeps the process (and
 * the controller's timers and automatic uploads, see [connected]) alive. Sessions are followed also while the poster is switched off
 * ([FeatureStore]); the [FrameController] then only schedules nothing.
 *
 * Threading: the session map and the callbacks live on the main thread. Everything else (reading the
 * metadata, hashing a bitmap, writing its PNG, mapping, calling the controller) runs on one serial
 * background dispatcher, so events reach the controller in the order they happened. [live] mirrors the
 * session map on that dispatcher, so an event can look at the other sessions of its app.
 *
 * Each tracked session derives one effective event ([SessionView]) and reports it whenever it changes, from
 * every path (snapshot, metadata, playback state, reconnect). Each has an id (`<pkg>#<n>`), carried in its
 * events as [Playing.session], so the controller matches an end to the session it ends. A reconnect numbers
 * the sessions anew; [FrameController.onSessionsSynced] moves the current playback to its session's new id.
 */
class NowPlayingListener : NotificationListenerService() {
    /** Serial: one task at a time, in submission order. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val sessions = HashMap<MediaSession.Token, Session>()
    private val live = ArrayList<Session>() // the sessions in [sessions]; touched only on the serial dispatcher
    private var sessionCount = 0L // main thread: numbers the sessions for [Playing.session]
    private var manager: MediaSessionManager? = null
    private val artCache by lazy { ArtCache(File(cacheDir, "nowplaying")) }

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> sync(list.orEmpty()) }

    override fun onListenerConnected() {
        connected = true // first: from now on automatic uploads need no foreground service
        release()
        val m = getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(this, NowPlayingListener::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsChanged, component)
            manager = m
            sync(m.getActiveSessions(component))
            // after the snapshots that sync posted; sessions that left while disconnected are not in the list,
            // so the controller checks that what it thinks plays is still among them
            post { controller().onSessionsSynced(live.mapNotNull { it.event() }) }
        } catch (e: SecurityException) {
            Log.w(TAG, "no access to media sessions", e)
        }
    }

    override fun onListenerDisconnected() {
        connected = false // first: an upload requested from now on is held by UploadService
        release()
    }

    override fun onDestroy() {
        connected = false
        release()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Unregister the session-list listener and every controller callback. Nothing is reported here: on the
     * next connect the snapshot and [FrameController.onSessionsSynced] catch up with what changed meanwhile.
     */
    private fun release() {
        manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        manager = null
        for (s in sessions.values) s.detach()
        sessions.clear()
        post { live.clear() }
    }

    /** Bring [sessions] in line with the active list: drop the ones that left, attach and snapshot new ones. */
    private fun sync(controllers: List<MediaController>) {
        val active = controllers.filter { it.packageName != packageName }.associateBy { it.sessionToken }
        val gone = (sessions.keys - active.keys).mapNotNull { sessions.remove(it) }
        for (s in gone) s.detach()
        if (gone.isNotEmpty()) post { live.removeAll(gone.toSet()) }
        for (s in gone) {
            // another session of the same app may still be active; the controller replays it if it plays
            post {
                val rest = live.filter { it.pkg == s.pkg }
                Log.i(TAG, "session gone: ${s.id} (${rest.size} left)")
                controller().onSessionGone(s.pkg, rest.mapNotNull { it.event() }, s.id)
            }
        }
        for ((token, c) in active) {
            if (token in sessions) continue
            val s = Session(c, "${c.packageName}#${++sessionCount}")
            sessions[token] = s
            c.registerCallback(s)
            Log.i(TAG, "session added: ${s.id}")
            post { live += s } // before its snapshot, so the snapshots of its siblings see it
            s.snapshot()
        }
    }

    private fun post(block: () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "event dropped", e)
            }
        }
    }

    private fun controller() = FrameControllers.get(this)

    /**
     * One app's MediaSession. Callbacks arrive on the main thread; [view] is touched only on the serial
     * dispatcher, so the bitmap is hashed once per metadata change, not on every state change.
     */
    private inner class Session(private val mc: MediaController, id: String) : MediaController.Callback() {
        val pkg: String = mc.packageName
        val view = SessionView(pkg, id) // the session's one effective event, reported when it changes
        val id: String get() = view.id
        private var attached = true // main thread

        fun snapshot() = post { report(view.update(read(mc.metadata), mc.playbackState?.state)) }

        fun detach() {
            attached = false
            mc.unregisterCallback(this)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (attached) post { report(view.update(meta = read(metadata))) }
        }

        override fun onPlaybackStateChanged(ps: PlaybackState?) {
            if (attached) post {
                // the cache may have pruned the PNG of the last metadata meanwhile: write it again
                val pruned = view.meta.bitmapPath?.let { !File(it).isFile } == true
                report(view.update(if (pruned) read(mc.metadata) else view.meta, ps?.state))
            }
        }

        /** This session as a sibling or the reconnect check sees it ([SessionView.event]). */
        fun event(): Playing? = view.event

        /** Send [p], a change of this session's effective event ([SessionView.update]), to the controller. */
        private fun report(p: Playing?) {
            if (p == null) return
            Log.i(TAG, "$id ${p.state} key=${p.key} title=${p.title}")
            // an end of this session is not an end of the app while another of its sessions plays
            val siblings = if (p.state == Playing.State.PLAYING) emptyList()
            else live.filter { it !== this && it.pkg == pkg }.mapNotNull { it.event() }
            controller().onEvent(p, siblings)
        }
    }

    private fun read(m: MediaMetadata?): Meta {
        if (m == null) return Meta.EMPTY
        val strings = TEXT_KEYS.associateWith { m.getString(it) }
        if (!Playing.needsBitmap(strings)) return Meta(strings, null, null)
        val bitmap = MetaKeys.BITMAPS.firstNotNullOfOrNull { m.getBitmap(it) } ?: return Meta(strings, null, null)
        val (key, path) = cache(bitmap) ?: return Meta(strings, null, null)
        return Meta(strings, key, path)
    }

    /**
     * Hash [bitmap]'s raw pixels and make sure `<sha1>.png` exists; returns the key and the file path. Pruning
     * spares the artwork of every tracked session and every key the controller may still upload.
     */
    private fun cache(bitmap: Bitmap): Pair<String, String>? = try {
        val b = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val pixels = ByteBuffer.allocate(b.byteCount).also { b.copyPixelsToBuffer(it) }.array()
        val key = ArtCache.keyOf(pixels)
        val inUse = controller().artKeysInUse() + live.mapNotNull { it.view.meta.bitmapKey }
        artCache.store(key, inUse) { out -> b.compress(Bitmap.CompressFormat.PNG, 100, out) }?.let { key to it.path }
    } catch (e: Exception) {
        Log.w(TAG, "artwork bitmap not cached", e)
        null
    }

    companion object {
        private const val TAG = "NowPlayingListener"

        /**
         * The system has this listener bound, which keeps the process alive: an automatic upload then runs in
         * the process without a foreground service ([AutoUploader]). Set first thing on connect, cleared
         * first thing on disconnect and destroy.
         */
        @Volatile
        var connected = false
            private set

        /** The text metadata the mapper looks at: titles and art URIs. */
        private val TEXT_KEYS = listOf(MetaKeys.TITLE, MetaKeys.DISPLAY_TITLE) + MetaKeys.URIS
    }
}
