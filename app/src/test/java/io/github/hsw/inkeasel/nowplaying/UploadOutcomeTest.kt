package io.github.hsw.inkeasel.nowplaying

import io.github.hsw.inkeasel.R
import io.github.hsw.inkeasel.Txt
import io.github.hsw.inkeasel.txt
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
        assertEquals(Txt(R.string.upload_sent), o.toast)
        assertFalse(o.report)
        assertTrue(o.remember)
    }

    @Test
    fun manualFailureToastsTheErrorAndDoesNotRemember() {
        val o = UploadOutcome.of(auto = false, ok = false, error = "frame not found")
        assertEquals(txt(R.string.upload_failed, "frame not found"), o.toast)
        assertFalse(o.report)
        assertFalse(o.remember)
    }

    @Test
    fun manualFailureWithoutMessageStillToasts() {
        assertEquals(txt(R.string.upload_failed, Txt(R.string.upload_error_unknown)), UploadOutcome.of(auto = false, ok = false).toast)
    }

    @Test
    fun untitledPictureIsNamedByItsKey() {
        assertEquals(Txt(R.string.upload_default_picture), UploadOutcome.untitled(FrameController.DEFAULT_PREFIX + "/p/sea.png"))
        assertEquals(Txt(R.string.upload_picture), UploadOutcome.untitled(FrameController.MANUAL_PREFIX + "url:https://e/p.jpg"))
        assertEquals(Txt(R.string.upload_picture), UploadOutcome.untitled("https://e/poster.jpg"))
        assertEquals(Txt(R.string.upload_picture), UploadOutcome.untitled(null))
    }
}
