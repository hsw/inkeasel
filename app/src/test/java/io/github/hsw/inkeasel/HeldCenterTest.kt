package io.github.hsw.inkeasel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldCenterTest {
    @Test
    fun longPress_repeatsAndReleaseAreSwallowed_thenAFreshPressGoesThrough() {
        val held = HeldCenter() // the details panel opened at the first repeat of a held CENTER
        assertTrue(held.swallow(down = true, repeat = 2))
        assertTrue(held.swallow(down = true, repeat = 3))
        assertTrue(held.swallow(down = false, repeat = 0)) // the release: the panel's button does not act
        assertFalse(held.armed)
        assertFalse(held.swallow(down = true, repeat = 0)) // the next press presses the button
        assertFalse(held.swallow(down = false, repeat = 0))
    }

    @Test
    fun opened_withNoKeyHeld_theFirstPressGoesThrough() {
        // a tap, or a short press released before the dialog opened: the next CENTER is a fresh press
        val held = HeldCenter()
        assertFalse(held.swallow(down = true, repeat = 0))
        assertFalse(held.armed)
        assertFalse(held.swallow(down = false, repeat = 0))
    }

    @Test
    fun arm_swallowsTheRestOfTheHandledPress() {
        // the viewer's CENTER that shows its action row: held, its repeats and release must not press "Send"
        val held = HeldCenter(armed = false)
        assertFalse(held.swallow(down = true, repeat = 0))
        held.arm()
        assertTrue(held.swallow(down = true, repeat = 1))
        assertTrue(held.swallow(down = false, repeat = 0))
        assertFalse(held.swallow(down = true, repeat = 0))
    }

    @Test
    fun unarmed_swallowsNothing() {
        val held = HeldCenter(armed = false)
        assertFalse(held.swallow(down = true, repeat = 5))
        assertFalse(held.swallow(down = false, repeat = 0))
    }
}
