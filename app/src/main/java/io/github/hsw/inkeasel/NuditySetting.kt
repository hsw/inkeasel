package io.github.hsw.inkeasel

import io.github.hsw.inkeasel.catalog.NudityFilter

/**
 * Why the device locks the Catalog's nudity filter at [NudityFilter.HIDE_NUDES] ([NudityFilter.effective]),
 * with the [reason] the settings show under the marked row while the rows cannot be pressed. Read at each start
 * of the activity and when the settings open ([NudityLockReader]); never stored or sent.
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

/** One row of the "Catalog → Nudity" radio group: the [filter] it chooses, its [text], and whether it carries the mark. */
data class NudityRow(val filter: NudityFilter, val text: Txt, val marked: Boolean)

/** The "Catalog → Nudity" settings group, pure: the lock decision, the rows and their texts. */
object NuditySetting {
    /** The lock from what the device reports; parental controls first, as the more specific reason. */
    fun lock(parentalControls: Boolean, accountsRestricted: Boolean): NudityLock? = when {
        parentalControls -> NudityLock.PARENTAL_CONTROLS
        accountsRestricted -> NudityLock.RESTRICTED_PROFILE
        else -> null
    }

    /** The values in the order the rows show them: from the strictest to showing all. */
    private val ORDER = listOf(NudityFilter.HIDE_NUDES, NudityFilter.HIDE_EXPLICIT, NudityFilter.SHOW_ALL)

    /**
     * The rows for the user's [choice]: the mark is on the filter in force ([NudityFilter.effective]), so while
     * the device [locked] it, on the lock's value (the settings then take no press on them).
     */
    fun rows(choice: NudityFilter, locked: Boolean): List<NudityRow> {
        val inForce = NudityFilter.effective(choice, locked)
        return ORDER.map { NudityRow(it, valueText(it), marked = it == inForce) }
    }

    /** A value's text: the row that chooses it. */
    fun valueText(filter: NudityFilter): Txt = when (filter) {
        NudityFilter.HIDE_EXPLICIT -> Txt(R.string.settings_nudity_hide_explicit)
        NudityFilter.HIDE_NUDES -> Txt(R.string.settings_nudity_hide_nudes)
        NudityFilter.SHOW_ALL -> Txt(R.string.settings_nudity_show_all)
    }
}
