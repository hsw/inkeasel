package io.github.hsw.inkeasel

import coil.network.HttpException
import io.github.hsw.inkeasel.RetryPolicy.MAX_HOLD_MS
import io.github.hsw.inkeasel.RetryPolicy.MAX_RETRIES
import io.github.hsw.inkeasel.RetryPolicy.MAX_WAIT_MS
import io.github.hsw.inkeasel.RetryPolicy.THUMB_RETRIES
import io.github.hsw.inkeasel.RetryPolicy.holdMs
import io.github.hsw.inkeasel.RetryPolicy.retryAfterMs
import io.github.hsw.inkeasel.RetryPolicy.retryAfterSeconds
import io.github.hsw.inkeasel.RetryPolicy.thumbRetryMs
import io.github.hsw.inkeasel.RetryPolicy.waitMs
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class RetryPolicyTest {
    // Mon, 09 Nov 2026 12:00:00 GMT
    private val now = 1_794_225_600_000L

    @Test
    fun tooManyRequestsAndUnavailableAreRetriedWithTheDefaultWaits() {
        for (code in listOf(429, 503)) {
            assertEquals(1_000L, waitMs("GET", code, null, 1, now))
            assertEquals(2_000L, waitMs("GET", code, null, 2, now))
        }
        assertEquals(1_000L, waitMs("HEAD", 503, null, 1, now))
    }

    @Test
    fun atMostTwoRetries() {
        assertEquals(2, MAX_RETRIES)
        assertNull(waitMs("GET", 429, null, 3, now))
        assertNull(waitMs("GET", 429, "0", 3, now))
        assertNull(waitMs("GET", 429, null, 0, now))
    }

    @Test
    fun noRetryOnOtherCodes() {
        for (code in listOf(200, 304, 400, 403, 404, 408, 410, 500, 502, 504)) {
            assertNull("HTTP $code", waitMs("GET", code, "1", 1, now))
        }
    }

    @Test
    fun onlyGetAndHeadAreRetried() {
        for (method in listOf("POST", "PUT", "DELETE", "PATCH")) assertNull(method, waitMs(method, 503, "1", 1, now))
    }

    @Test
    fun retryAfterSecondsAreHonoured() {
        assertEquals(0L, waitMs("GET", 429, "0", 1, now))
        assertEquals(3_000L, waitMs("GET", 429, "3", 1, now))
        assertEquals(3_000L, waitMs("GET", 503, " 3 ", 2, now))
        assertEquals(MAX_WAIT_MS, waitMs("GET", 429, "10", 1, now))
    }

    @Test
    fun aLongerRetryAfterThanTheCapIsFinal() {
        // never asked earlier than the server said, and never waited for longer than 10 s
        assertEquals(10_000L, MAX_WAIT_MS)
        assertNull(waitMs("GET", 429, "11", 1, now))
        assertNull(waitMs("GET", 503, "3600", 1, now))
        assertNull(waitMs("GET", 503, "99999999999999999999999", 1, now))
    }

    @Test
    fun retryAfterHttpDate() {
        assertEquals(5_000L, retryAfterMs("Mon, 09 Nov 2026 12:00:05 GMT", now))
        assertEquals(5_000L, waitMs("GET", 503, "Mon, 09 Nov 2026 12:00:05 GMT", 1, now))
        // a date in the past: at once
        assertEquals(0L, retryAfterMs("Mon, 09 Nov 2026 11:00:00 GMT", now))
        assertNull(waitMs("GET", 503, "Mon, 09 Nov 2026 12:01:00 GMT", 1, now))
    }

    @Test
    fun anUnreadableRetryAfterTakesTheDefaultWait() {
        for (value in listOf("", "  ", "soon", "-1", "1.5", "09 Nov 2026")) {
            assertNull(value, retryAfterMs(value, now))
            assertEquals(value, 1_000L, waitMs("GET", 429, value, 1, now))
        }
        assertNull(retryAfterMs(null, now))
    }

    @Test
    fun hugeSecondsDoNotOverflow() {
        assertEquals(Long.MAX_VALUE, retryAfterMs("${Long.MAX_VALUE}", now))
        assertEquals(Long.MAX_VALUE, retryAfterMs("99999999999999999999999", now))
    }

    @Test
    fun aHostIsHeldForTheWaitItAskedFor() {
        for (code in listOf(429, 503)) {
            assertEquals(11_000L, holdMs(code, "11", now))
            assertEquals(1_000L, holdMs(code, null, now)) // the first default wait
            assertEquals(0L, holdMs(code, "0", now))
            assertEquals(5_000L, holdMs(code, "Mon, 09 Nov 2026 12:00:05 GMT", now))
        }
        // longer than any retry wait, but never more than a minute
        assertEquals(60_000L, MAX_HOLD_MS)
        assertEquals(MAX_HOLD_MS, holdMs(429, "3600", now))
        assertEquals(MAX_HOLD_MS, holdMs(429, "99999999999999999999999", now))
    }

    @Test
    fun otherAnswersHoldNothing() {
        for (code in listOf(200, 304, 400, 404, 500, 502, 504)) assertNull("HTTP $code", holdMs(code, "11", now))
    }

    @Test
    fun aHeldAnswerSaysTheSecondsLeftRoundedUp() {
        assertEquals(11L, retryAfterSeconds(10_001))
        assertEquals(10L, retryAfterSeconds(10_000))
        assertEquals(1L, retryAfterSeconds(1))
        assertEquals(0L, retryAfterSeconds(-5))
    }

    @Test
    fun aCardAsksForItsThumbnailAgainAfterThePause() {
        assertEquals(11_000L, thumbRetryMs(429, "11", 0, now))
        assertEquals(1_000L, thumbRetryMs(503, null, 0, now))
        // never at once, never after more than a minute
        assertEquals(1_000L, thumbRetryMs(429, "0", 0, now))
        assertEquals(MAX_HOLD_MS, thumbRetryMs(429, "3600", 0, now))
        assertEquals(3, THUMB_RETRIES)
        assertEquals(11_000L, thumbRetryMs(429, "11", THUMB_RETRIES - 1, now))
        assertNull(thumbRetryMs(429, "11", THUMB_RETRIES, now))
    }

    @Test
    fun aCardDoesNotAskAgainAfterOtherFailures() {
        assertNull(thumbRetryMs(null, null, 0, now)) // no HTTP answer: a file, a connection failure
        for (code in listOf(400, 403, 404, 500, 502, 504)) assertNull("HTTP $code", thumbRetryMs(code, "1", 0, now))
    }

    private fun answer(code: Int, retryAfter: String?): Response = Response.Builder()
        .request(Request.Builder().url("https://upload.wikimedia.org/a.jpg").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
        .build()

    @Test
    fun aCardReadsTheAnswerFromCoilsError() {
        // a real 429, and the interceptor's own "held" one, which looks the same to Coil
        assertEquals(11_000L, thumbRetryMs(HttpException(answer(429, "11")), 0, now))
        assertEquals(1_000L, thumbRetryMs(HttpException(answer(503, null)), 0, now))
        assertNull(thumbRetryMs(HttpException(answer(429, "11")), THUMB_RETRIES, now))
        assertNull(thumbRetryMs(HttpException(answer(404, null)), 0, now))
        // not an HTTP answer: a decoding error, a connection failure
        assertNull(thumbRetryMs(IOException("reset"), 0, now))
        assertNull(thumbRetryMs(IllegalStateException("bad image"), 0, now))
    }
}
