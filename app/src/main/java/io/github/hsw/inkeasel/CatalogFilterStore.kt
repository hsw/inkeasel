package io.github.hsw.inkeasel

import android.content.Context
import android.content.SharedPreferences
import android.media.tv.TvInputManager
import android.os.UserManager
import android.util.Log
import io.github.hsw.inkeasel.catalog.NudityFilter

private const val TAG = "CatalogFilterStore"

/**
 * The Catalog's nudity filter as the user chose it in the settings, in shared preferences `catalog`, key
 * `nudity` (the [NudityFilter] name); [NudityFilter.DEFAULT] until one is chosen, and for a value it does not
 * know. What is in force is [NudityFilter.effective] of it and the device's lock ([readLock]), which is never
 * stored.
 */
class CatalogFilterStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("catalog", Context.MODE_PRIVATE)

    var nudity: NudityFilter
        get() = prefs.getString(NUDITY, null)?.let { name -> NudityFilter.entries.firstOrNull { it.name == name } }
            ?: NudityFilter.DEFAULT
        set(value) {
            prefs.edit().putString(NUDITY, value.name).apply()
        }

    companion object {
        private const val NUDITY = "nudity"

        /**
         * Whether the device locks the filter now ([NuditySetting.lock]): Android TV's parental controls, or a
         * user that may not change accounts (a restricted profile). Read each time, never stored or sent. There is
         * no public API for Family Link or a Google TV kids profile, so those are not seen. The TV input service
         * is missing on a device without `android.software.live_tv`, as most phones and tablets: not locked there.
         */
        fun readLock(ctx: Context): NudityLock? {
            val tv = ctx.getSystemService(TvInputManager::class.java)
            val users = ctx.getSystemService(UserManager::class.java)
            return readLock(
                parentalControls = { tv?.isParentalControlsEnabled == true },
                accountsRestricted = { users?.hasUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS) == true },
            )
        }

        /** [readLock] from the two reads, each of which counts as off when it fails (a system service gone). */
        internal fun readLock(parentalControls: () -> Boolean, accountsRestricted: () -> Boolean): NudityLock? =
            NuditySetting.lock(read("parental controls", parentalControls), read("user restrictions", accountsRestricted))

        private fun read(what: String, block: () -> Boolean): Boolean = try {
            block()
        } catch (e: RuntimeException) {
            Log.w(TAG, "$what unreadable", e)
            false
        }
    }
}
