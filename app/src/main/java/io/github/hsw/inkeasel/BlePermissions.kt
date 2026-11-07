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
     * `shouldShowRequestPermissionRationale` of each permission, which turns false in that state.
     */
    fun blocked(granted: Boolean, rationale: (String) -> Boolean): Boolean = !granted && all.none(rationale)
}

/** Open this app's App info screen (permissions, restricted settings); `false` if the device has none. */
fun openAppInfo(ctx: Context): Boolean = try {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
    true
} catch (e: ActivityNotFoundException) {
    Log.w("AppInfo", "no App info screen", e)
    false
}
