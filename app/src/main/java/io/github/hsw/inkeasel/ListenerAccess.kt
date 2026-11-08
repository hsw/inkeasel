package io.github.hsw.inkeasel

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import io.github.hsw.inkeasel.ListenerAccessUi.GrantScreen
import io.github.hsw.inkeasel.nowplaying.NowPlayingListener

/**
 * Notification access for [NowPlayingListener], which the now-playing poster needs (it is what lets the app
 * read the media sessions). There is no permission dialog for it: the user turns it on in the system
 * settings. Phones and tablets have a screen for it (the app's own page on Android 11+, else the list);
 * Android TV 12+ has the list screen ([GrantScreen.DIRECT]); Android TV 9-11 has the switch only in the menu
 * Settings → Apps → Special app access → Notification access, so the flow opens the Apps screen
 * ([GrantScreen.APPS]) and names the path. Android TV 8 has no screen at all: there it is granted with
 * [adbCommand]. What the settings show and which screen opens is decided by [ListenerAccessUi].
 */
object ListenerAccess {
    private const val TAG = "ListenerAccess"

    fun component(ctx: Context) = ComponentName(ctx, NowPlayingListener::class.java)

    fun enabled(ctx: Context): Boolean = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)

    /** Grants the access from a computer, where no settings screen leads to it. */
    fun adbCommand(ctx: Context) = "adb shell cmd notification allow_listener ${component(ctx).flattenToString()}"

    fun tv(ctx: Context): Boolean = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Low-RAM devices; their Android TV settings hide the Special app access item for notification access. */
    fun lowRam(ctx: Context): Boolean = ctx.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true

    /**
     * The intents for [screen], most specific first. [GrantScreen.DIRECT]: the app's own page (phones and
     * tablets, Android 11+; it resolves on none of the Android TV builds checked), then the list of every app
     * that asks for the access. [GrantScreen.APPS]: Settings → Apps, else the Settings home.
     */
    private fun intents(ctx: Context, screen: GrantScreen, tv: Boolean): List<Intent> = when (screen) {
        GrantScreen.DIRECT -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 30 && !tv) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(ctx).flattenToString())
            } else null,
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        )
        GrantScreen.APPS -> listOf(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    }

    /** Whether the device has one of the [screen]'s intents (the manifest's `<queries>` make them visible). */
    fun canOpen(ctx: Context, screen: GrantScreen, tv: Boolean): Boolean =
        intents(ctx, screen, tv).any { it.resolveActivity(ctx.packageManager) != null }

    /** Open the first of the [screen]'s intents that starts; `false` if none did. */
    fun open(ctx: Context, screen: GrantScreen, tv: Boolean): Boolean {
        for (i in intents(ctx, screen, tv)) {
            if (i.resolveActivity(ctx.packageManager) == null) continue
            try {
                ctx.startActivity(i)
                Log.i(TAG, "opened ${i.action} for notification access")
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no screen for ${i.action}", e)
            }
        }
        return false
    }

    /**
     * Whether the app was not installed by a store (an APK file, adb, or unknown), which Android 13+ restricts
     * ([ListenerAccessUi.restrictedHint]). Before Android 13 there is no such restriction to tell.
     */
    fun sideloaded(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < ListenerAccessUi.RESTRICTED_SINCE) return true
        val source = try {
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).packageSource
        } catch (e: PackageManager.NameNotFoundException) {
            return true
        }
        return source != PackageInstaller.PACKAGE_SOURCE_STORE
    }
}
