package io.github.hsw.inkeasel

import android.content.Context
import android.media.tv.TvInputManager
import android.os.UserManager
import android.util.Log

private const val TAG = "NudityLockReader"

/**
 * Whether the device locks the Catalog's nudity filter now ([NuditySetting.lock]): Android TV's parental
 * controls, or a user that may not change accounts (a restricted profile). Read each time, never stored or
 * sent. There is no public API for Family Link or a Google TV kids profile, so those are not seen. The TV input
 * service is missing on a device without `android.software.live_tv`, as most phones and tablets: not locked
 * there.
 */
object NudityLockReader {
    fun read(ctx: Context): NudityLock? {
        val tv = ctx.getSystemService(TvInputManager::class.java)
        val users = ctx.getSystemService(UserManager::class.java)
        return read(
            parentalControls = { tv?.isParentalControlsEnabled == true },
            accountsRestricted = { users?.hasUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS) == true },
        )
    }

    /** [read] from the two reads, each of which counts as off when it fails (a system service gone). */
    internal fun read(parentalControls: () -> Boolean, accountsRestricted: () -> Boolean): NudityLock? =
        NuditySetting.lock(orOff("parental controls", parentalControls), orOff("user restrictions", accountsRestricted))

    private fun orOff(what: String, block: () -> Boolean): Boolean = try {
        block()
    } catch (e: RuntimeException) {
        Log.w(TAG, "$what unreadable", e)
        false
    }
}
