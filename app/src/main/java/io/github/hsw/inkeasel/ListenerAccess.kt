package io.github.hsw.inkeasel

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import io.github.hsw.inkeasel.nowplaying.NowPlayingListener

/**
 * Notification access for [NowPlayingListener], which the now-playing poster needs (it is what lets the app
 * read the media sessions). Tablets and phones grant it on a system screen; Android TV builds may have only
 * the list screen (Android TV 14) or none at all (Android TV 11), and then it is granted with [adbCommand].
 * What the settings show for it is decided by [ListenerAccessUi].
 */
object ListenerAccess {
    private const val TAG = "ListenerAccess"

    fun component(ctx: Context) = ComponentName(ctx, NowPlayingListener::class.java)

    fun enabled(ctx: Context): Boolean = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)

    /** Grants the access from a computer, where the device has no screen for it. */
    fun adbCommand(ctx: Context) = "adb shell cmd notification allow_listener ${component(ctx).flattenToString()}"

    /**
     * The system screens that grant it, most specific first: the app's own page (Android 11+; missing on the
     * Android TV builds checked), then the list of every app that asks for it.
     */
    private fun intents(ctx: Context): List<Intent> = listOfNotNull(
        if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(ctx).flattenToString())
        } else null,
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
    )

    /** Whether the device has one of those screens (the manifest's `<queries>` make them visible). */
    fun canOpen(ctx: Context): Boolean = intents(ctx).any { it.resolveActivity(ctx.packageManager) != null }

    /** Open the first of those screens that starts; `false` if none does (then show [adbCommand]). */
    fun open(ctx: Context): Boolean {
        for (i in intents(ctx)) {
            if (i.resolveActivity(ctx.packageManager) == null) continue
            try {
                ctx.startActivity(i)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no screen for ${i.action}", e)
            }
        }
        return false
    }
}
