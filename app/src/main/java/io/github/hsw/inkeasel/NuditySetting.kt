package io.github.hsw.inkeasel

import io.github.hsw.inkeasel.catalog.NudityFilter

/**
 * Why the device locks the Catalog's nudity filter at [NudityFilter.HIDE_NUDES] ([NudityFilter.effective]),
 * with the [reason] the settings row shows instead of the press. Read at each start of the activity and when the
 * settings open ([NudityLockReader]); never stored or sent.
 */
enum class NudityLock(val reason: Txt) {
    /** Android TV's parental controls are on (`TvInputManager.isParentalControlsEnabled`). */
    PARENTAL_CONTROLS(Txt(R.string.settings_nudity_locked_tv)),

    /**
     * The user may not change accounts (`UserManager.DISALLOW_MODIFY_ACCOUNTS`): a restricted profile has it
     * set; so may a managed device, where a stricter filter does no harm.
     */
    RESTRICTED_PROFILE(Txt(R.string.settings_nudity_locked_profile)),
}

/** The "Catalog → Nudity" settings row, pure: the lock decision, the press and the texts. */
object NuditySetting {
    /** The lock from what the device reports; parental controls first, as the more specific reason. */
    fun lock(parentalControls: Boolean, accountsRestricted: Boolean): NudityLock? = when {
        parentalControls -> NudityLock.PARENTAL_CONTROLS
        accountsRestricted -> NudityLock.RESTRICTED_PROFILE
        else -> null
    }

    /** A press on the row (unlocked): the next value, from the default to stricter, then to showing all. */
    fun next(choice: NudityFilter): NudityFilter = when (choice) {
        NudityFilter.HIDE_EXPLICIT -> NudityFilter.HIDE_NUDES
        NudityFilter.HIDE_NUDES -> NudityFilter.SHOW_ALL
        NudityFilter.SHOW_ALL -> NudityFilter.HIDE_EXPLICIT
    }

    /** The value the row shows: the filter in force ([NudityFilter.effective]), so a locked row shows the lock's. */
    fun valueText(filter: NudityFilter): Txt = when (filter) {
        NudityFilter.HIDE_EXPLICIT -> Txt(R.string.settings_nudity_hide_explicit)
        NudityFilter.HIDE_NUDES -> Txt(R.string.settings_nudity_hide_nudes)
        NudityFilter.SHOW_ALL -> Txt(R.string.settings_nudity_show_all)
    }
}
