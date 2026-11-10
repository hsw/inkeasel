package io.github.hsw.inkeasel.nowplaying

import io.github.hsw.inkeasel.R
import io.github.hsw.inkeasel.Txt
import io.github.hsw.inkeasel.TxtException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** What the upload engine does after an upload. */
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
        val o = UploadOutcome.of(auto = true, ok = false, error = TxtException(Txt(R.string.frame_err_no_answer)))
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
        val o = UploadOutcome.of(auto = false, ok = false, error = TxtException(Txt(R.string.frame_err_send, "NM000001")))
        assertEquals(Txt(R.string.upload_failed, Txt(R.string.frame_err_send, "NM000001")), o.toast)
        assertFalse(o.report)
        assertFalse(o.remember)
    }

    @Test
    fun manualFailureWithoutMessageStillToasts() {
        assertEquals(Txt(R.string.upload_failed, Txt(R.string.upload_err_unknown)), UploadOutcome.of(auto = false, ok = false).toast)
    }

    @Test
    fun aTxtExceptionIsShownInTheUsersLanguage() {
        val nested = Txt(R.string.frame_err_not_found, "NM000001", Txt(R.string.scanning_hint))
        assertEquals(nested, UploadOutcome.reason(TxtException(nested)))
        assertEquals(Txt(R.string.upload_err_decode), UploadOutcome.reason(TxtException(Txt(R.string.upload_err_decode), IOException("cause"))))
    }

    @Test
    fun anotherExceptionKeepsItsRawMessage() {
        assertEquals(Txt(R.string.upload_err_text, "HTTP 404: https://e/p.jpg"), UploadOutcome.reason(IOException("HTTP 404: https://e/p.jpg")))
        assertEquals(Txt(R.string.upload_err_text, "timeout"), UploadOutcome.reason(SocketTimeoutException("timeout")))
        assertEquals(
            Txt(R.string.upload_failed, Txt(R.string.upload_err_text, "HTTP 500: https://e/p.jpg")),
            UploadOutcome.of(auto = false, ok = false, error = IOException("HTTP 500: https://e/p.jpg")).toast,
        )
    }

    @Test
    fun anExceptionWithoutMessageIsNamedByItsClass() {
        assertEquals(Txt(R.string.upload_err_text, "IllegalStateException"), UploadOutcome.reason(IllegalStateException()))
        assertEquals(Txt(R.string.upload_err_text, "IOException"), UploadOutcome.reason(IOException(" ")))
    }

    @Test
    fun noExceptionIsAnUnknownError() {
        assertEquals(Txt(R.string.upload_err_unknown), UploadOutcome.reason(null))
    }

}
