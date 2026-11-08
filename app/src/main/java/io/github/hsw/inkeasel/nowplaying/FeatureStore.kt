package io.github.hsw.inkeasel.nowplaying

import android.content.Context
import android.content.SharedPreferences

/**
 * Features the user switches on and off in the settings, in shared preferences `features`. [posterEnabled]:
 * the now-playing poster (on by default; it works only once notification access is granted). A change is
 * passed on to the [FrameController] with [FrameController.onPosterSwitched] by whoever makes it.
 * [posterPending]: the poster switch was pressed without the access, so it is switched on once the access is
 * granted (`PosterSwitch`).
 */
class FeatureStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("features", Context.MODE_PRIVATE)

    var posterEnabled: Boolean
        get() = prefs.getBoolean(POSTER, true)
        set(value) {
            prefs.edit().putBoolean(POSTER, value).apply()
        }

    var posterPending: Boolean
        get() = prefs.getBoolean(POSTER_PENDING, false)
        set(value) {
            prefs.edit().putBoolean(POSTER_PENDING, value).apply()
        }

    private companion object {
        const val POSTER = "poster"
        const val POSTER_PENDING = "posterPending"
    }
}
