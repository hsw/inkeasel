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

private const val TAG = "BlePermissions"

/**
 * The runtime permissions the frame search and the upload need: `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` on
 * Android 12+, fine location before (BLE scanning needed it then). The one set every screen asks for.
 */
object BlePermissions {
    val all: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /** What a frame search needs besides the permission; before Android 12 a BLE scan also needs location on. */
    val scanHint: Txt = Txt(if (Build.VERSION.SDK_INT >= 31) R.string.scanning_hint else R.string.scanning_hint_location)

    /** The name of the permission in the system's App info → Permissions screen. */
    val settingsName: Txt = settingsName(Build.VERSION.SDK_INT)

    /** [settingsName] on Android [sdk]. Pure. */
    fun settingsName(sdk: Int): Txt = Txt(if (sdk >= 31) R.string.perm_name_nearby else R.string.perm_name_location)

    /** The frame section's text while the permission is missing (and not blocked). */
    val neededText: Txt = neededText(Build.VERSION.SDK_INT)

    /**
     * [neededText] on Android [sdk]: before Android 12 the system dialog asks for location, so the text names
     * location and why a Bluetooth search needs it. Pure.
     */
    fun neededText(sdk: Int): Txt = Txt(if (sdk >= 31) R.string.ble_permission_needed else R.string.ble_permission_needed_location)

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

    /** Remember a denial once the rationale of one of [permissions] is true after a request ([blocked] needs it later). */
    fun noteDenial(ctx: Context, permissions: List<String>, rationale: (String) -> Boolean) {
        if (!deniedBefore(ctx) && permissions.any(rationale)) prefs(ctx).edit().putBoolean(KEY_DENIED, true).apply()
    }

    /**
     * Whether the first-run panel has been answered ("Not now", Back, or an answer in the system dialog):
     * from then on the app asks only in context ([BleAsk]).
     */
    fun introDone(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_INTRO, false)

    fun noteIntroDone(ctx: Context) {
        if (!introDone(ctx)) prefs(ctx).edit().putBoolean(KEY_INTRO, true).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("permissions", Context.MODE_PRIVATE)

    private const val KEY_DENIED = "bleDenied"
    private const val KEY_INTRO = "bleIntroDone"
}

/**
 * The Bluetooth permission as the settings see it: whether it is [permitted], whether the system no longer
 * asks for it ([blocked], see [BlePermissions.blocked]), and [request], which asks for it (the system dialog
 * straight away: the user asked for it there, and the section says why) or, once blocked, opens App info.
 */
data class BleAccess(val permitted: Boolean, val blocked: Boolean, val request: () -> Unit)

/** Open this app's App info screen (permissions, restricted settings); `false` if the device has none. */
fun openAppInfo(ctx: Context): Boolean = try {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
    true
} catch (e: ActivityNotFoundException) {
    Log.w(TAG, "no App info screen", e)
    false
}
