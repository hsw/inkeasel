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
    fun eitherLock_forcesHideAllNudity_whateverTheChoice() {
        for (parental in listOf(false, true)) for (restricted in listOf(false, true)) {
            val locked = NuditySetting.lock(parental, restricted) != null
            for (choice in NudityFilter.entries) {
                val want = if (parental || restricted) NudityFilter.HIDE_NUDES else choice
                assertEquals("$parental $restricted $choice", want, NudityFilter.effective(choice, locked))
            }
        }
    }

    private val hideNudes = NudityRow(NudityFilter.HIDE_NUDES, Txt(R.string.settings_nudity_hide_nudes), marked = false)
    private val hideExplicit = NudityRow(NudityFilter.HIDE_EXPLICIT, Txt(R.string.settings_nudity_hide_explicit), marked = false)
    private val showAll = NudityRow(NudityFilter.SHOW_ALL, Txt(R.string.settings_nudity_show_all), marked = false)

    @Test
    fun rows_strictestFirst_theChoiceMarked() {
        assertEquals(
            listOf(hideNudes, hideExplicit.copy(marked = true), showAll),
            NuditySetting.rows(NudityFilter.HIDE_EXPLICIT, locked = false),
        )
        assertEquals(
            listOf(hideNudes.copy(marked = true), hideExplicit, showAll),
            NuditySetting.rows(NudityFilter.HIDE_NUDES, locked = false),
        )
        assertEquals(
            listOf(hideNudes, hideExplicit, showAll.copy(marked = true)),
            NuditySetting.rows(NudityFilter.SHOW_ALL, locked = false),
        )
    }

    @Test
    fun rows_locked_markTheLocksValue_whateverTheChoice() {
        for (choice in NudityFilter.entries) {
            assertEquals(
                choice.name,
                listOf(hideNudes.copy(marked = true), hideExplicit, showAll),
                NuditySetting.rows(choice, locked = true),
            )
        }
    }

    @Test
    fun rows_eachValueOnce_exactlyOneMarked() {
        for (locked in listOf(false, true)) for (choice in NudityFilter.entries) {
            val rows = NuditySetting.rows(choice, locked)
            assertEquals(NudityFilter.entries.toSet(), rows.map { it.filter }.toSet())
            assertEquals(3, rows.size)
            assertEquals(1, rows.count { it.marked })
        }
    }

    @Test
    fun valueText_perFilter() {
        assertEquals(Txt(R.string.settings_nudity_hide_explicit), NuditySetting.valueText(NudityFilter.HIDE_EXPLICIT))
        assertEquals(Txt(R.string.settings_nudity_hide_nudes), NuditySetting.valueText(NudityFilter.HIDE_NUDES))
        assertEquals(Txt(R.string.settings_nudity_show_all), NuditySetting.valueText(NudityFilter.SHOW_ALL))
    }
}
