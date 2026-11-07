package io.github.hsw.inkeasel

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * The runtime permissions the frame search and the upload need: `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` on
 * Android 12+, fine location before (BLE scanning needed it then). The one set every screen asks for.
 */
object BlePermissions {
    val all: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /** The name of the permission in the system's App info → Permissions screen. */
    val settingsName: Int = if (Build.VERSION.SDK_INT >= 31) R.string.perm_name_nearby else R.string.perm_name_location

    fun granted(ctx: Context): Boolean = all.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /**
     * After a request came back denied: whether the system will no longer show its dialog ("Don't ask again",
     * or a second denial on Android 11+), so asking again silently does nothing. [rationale] is
     * `shouldShowRequestPermissionRationale` of each of [permissions], which turns false in that state, but
     * is also false before the first denial: a dialog dismissed without an answer (Back, a tap outside) leaves
     * it false. So it counts only once a denial has been seen ([deniedBefore]: some rationale was true after an
     * earlier request, see [noteDenial]). Pure.
     */
    fun blocked(permissions: List<String>, granted: Boolean, deniedBefore: Boolean, rationale: (String) -> Boolean): Boolean =
        !granted && deniedBefore && permissions.none(rationale)

    /** Whether a denial was ever seen ([noteDenial]); kept across starts, since the system remembers it too. */
    fun deniedBefore(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DENIED, false)

    /** Remember a denial once some rationale is true after a request ([blocked] needs it later). */
    fun noteDenial(ctx: Context, rationale: (String) -> Boolean) {
        if (!deniedBefore(ctx) && all.any(rationale)) prefs(ctx).edit().putBoolean(KEY_DENIED, true).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("permissions", Context.MODE_PRIVATE)

    private const val KEY_DENIED = "bleDenied"
}

/** Open this app's App info screen (permissions, restricted settings); `false` if the device has none. */
fun openAppInfo(ctx: Context): Boolean = try {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
    true
} catch (e: ActivityNotFoundException) {
    Log.w("AppInfo", "no App info screen", e)
    false
}
