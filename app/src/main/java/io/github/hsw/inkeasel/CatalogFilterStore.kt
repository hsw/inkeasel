package io.github.hsw.inkeasel

import android.content.Context
import android.content.SharedPreferences
import io.github.hsw.inkeasel.catalog.NudityFilter

/**
 * The Catalog's nudity filter as the user chose it in the settings, in shared preferences `catalog`, key
 * `nudity` (the [NudityFilter] name); [NudityFilter.DEFAULT] until one is chosen, and for a value it does not
 * know. What is in force is [NudityFilter.effective] of it and the device's lock ([NudityLockReader]), which
 * is never stored.
 */
class CatalogFilterStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("catalog", Context.MODE_PRIVATE)

    var nudity: NudityFilter
        get() = prefs.getString(NUDITY, null)?.let { name -> NudityFilter.entries.firstOrNull { it.name == name } }
            ?: NudityFilter.DEFAULT
        set(value) {
            prefs.edit().putString(NUDITY, value.name).apply()
        }

    private companion object {
        const val NUDITY = "nudity"
    }
}
