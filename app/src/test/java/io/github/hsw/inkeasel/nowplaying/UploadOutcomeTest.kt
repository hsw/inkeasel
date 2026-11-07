package io.github.hsw.inkeasel.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What `UploadService` does after an upload. */
class UploadOutcomeTest {
    @Test
    fun autoSuccessReportsOnlyNoToastNoRemember() {
        val o = UploadOutcome.of(auto = true, ok = true)
        assertNull(o.toast)
        assertTrue(o.report)
        assertFalse(o.remember)
    }

    @Test
    fun autoFailureReportsOnlyNoToastNoRemember() {
        val o = UploadOutcome.of(auto = true, ok = false, error = "frame not found")
        assertNull(o.toast)
        assertTrue(o.report)
        assertFalse(o.remember)
    }

    @Test
    fun manualSuccessToastsAndRemembers() {
        val o = UploadOutcome.of(auto = false, ok = true)
        assertEquals("Фото отправлено", o.toast)
        assertFalse(o.report)
        assertTrue(o.remember)
    }

    @Test
    fun manualFailureToastsTheErrorAndDoesNotRemember() {
        val o = UploadOutcome.of(auto = false, ok = false, error = "frame not found")
        assertEquals("Ошибка отправки: frame not found", o.toast)
        assertFalse(o.report)
        assertFalse(o.remember)
    }

    @Test
    fun manualFailureWithoutMessageStillToasts() {
        assertTrue(UploadOutcome.of(auto = false, ok = false).toast!!.startsWith("Ошибка отправки: "))
    }
}
