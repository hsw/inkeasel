package io.github.hsw.inkeasel

import io.github.hsw.inkeasel.RetryPolicy.MAX_RETRIES
import io.github.hsw.inkeasel.RetryPolicy.MAX_WAIT_MS
import io.github.hsw.inkeasel.RetryPolicy.retryAfterMs
import io.github.hsw.inkeasel.RetryPolicy.waitMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
