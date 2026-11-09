package io.github.hsw.inkeasel

import io.github.hsw.inkeasel.catalog.NudityFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NuditySettingTest {
    @Test
    fun lock_parentalControlsFirst_thenRestrictedProfile() {
        assertNull(NuditySetting.lock(parentalControls = false, accountsRestricted = false))
        assertEquals(NudityLock.PARENTAL_CONTROLS, NuditySetting.lock(parentalControls = true, accountsRestricted = false))
        assertEquals(NudityLock.PARENTAL_CONTROLS, NuditySetting.lock(parentalControls = true, accountsRestricted = true))
        assertEquals(NudityLock.RESTRICTED_PROFILE, NuditySetting.lock(parentalControls = false, accountsRestricted = true))
    }

    @Test
    fun lock_reasons() {
        assertEquals(Txt(R.string.settings_nudity_locked_tv), NudityLock.PARENTAL_CONTROLS.reason)
        assertEquals(Txt(R.string.settings_nudity_locked_profile), NudityLock.RESTRICTED_PROFILE.reason)
    }

    @Test
    fun lock_forcesHideAllNudity_whateverTheChoice() {
        for (choice in NudityFilter.entries) {
            val lock = NuditySetting.lock(parentalControls = true, accountsRestricted = false)
            assertEquals(NudityFilter.HIDE_NUDES, NudityFilter.effective(choice, lock != null))
            assertEquals(choice, NudityFilter.effective(choice, NuditySetting.lock(false, false) != null))
        }
    }

    @Test
    fun next_cyclesFromTheDefaultThroughAllThree() {
        val seen = generateSequence(NudityFilter.DEFAULT, NuditySetting::next).take(4).toList()
        assertEquals(
            listOf(NudityFilter.HIDE_EXPLICIT, NudityFilter.HIDE_NUDES, NudityFilter.SHOW_ALL, NudityFilter.HIDE_EXPLICIT),
            seen,
        )
    }

    @Test
    fun valueText_perFilter() {
        assertEquals(Txt(R.string.settings_nudity_hide_explicit), NuditySetting.valueText(NudityFilter.HIDE_EXPLICIT))
        assertEquals(Txt(R.string.settings_nudity_hide_nudes), NuditySetting.valueText(NudityFilter.HIDE_NUDES))
        assertEquals(Txt(R.string.settings_nudity_show_all), NuditySetting.valueText(NudityFilter.SHOW_ALL))
    }
}
