package io.github.hsw.inkeasel

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * When a request is asked again: only after HTTP 429 (too many requests) or 503 (unavailable), only for GET
 * and HEAD, at most [MAX_RETRIES] times, and never later than [MAX_WAIT_MS] from the answer. `Retry-After`
 * (seconds or an HTTP date) is honoured: an answer that asks for a longer wait than [MAX_WAIT_MS] is final, so
 * a server is never asked earlier than it said. Without the header the waits are [DEFAULT_WAITS_MS]. No other
 * code is retried, and neither is a connection failure (OkHttp retries those itself). Pure.
 */
object RetryPolicy {
    const val MAX_RETRIES = 2
    const val MAX_WAIT_MS = 10_000L

    /** The wait before the first and the second retry when the answer has no usable `Retry-After`. */
    val DEFAULT_WAITS_MS = listOf(1_000L, 2_000L)

    private val CODES = setOf(429, 503)
    private val METHODS = setOf("GET", "HEAD")

    /**
     * How long to wait before retry number [retry] (1 for the first) of a [method] request answered with
     * [code] and the `Retry-After` value [retryAfter] at [nowMs]; `null`: the answer is final.
     */
    fun waitMs(method: String, code: Int, retryAfter: String?, retry: Int, nowMs: Long): Long? {
        if (code !in CODES || method !in METHODS || retry !in 1..MAX_RETRIES) return null
        val wait = retryAfterMs(retryAfter, nowMs) ?: DEFAULT_WAITS_MS[retry - 1]
        return wait.takeIf { it <= MAX_WAIT_MS }
    }

    /**
     * A `Retry-After` value in milliseconds from [nowMs]: delta seconds or an HTTP date (a past one is 0);
     * `null` when absent or unreadable.
     */
    fun retryAfterMs(value: String?, nowMs: Long): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.all { it.isDigit() }) {
            // too many digits for a Long: longer than any wait
            val seconds = text.toLongOrNull() ?: return Long.MAX_VALUE
            return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
        }
        val at = try {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            return null
        }
        return (at - nowMs).coerceAtLeast(0)
    }
}

/**
 * Asks a request again as [RetryPolicy] says. The wait runs on the calling thread (an OkHttp dispatcher
 * thread for an enqueued call) in slices of [sliceMs], checking the call before and while waiting: a cancelled
 * call (a cancelled coroutine, a call timeout) stops at once with an [IOException]. Where [mayWait] is false
 * (the main thread) the answer is returned as it is. The answer that is not used is closed.
 */
class RetryInterceptor(
    private val mayWait: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val sliceMs: Long = 50,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var retry = 1
        while (true) {
            val wait = RetryPolicy.waitMs(request.method, response.code, response.header("Retry-After"), retry, now())
            if (wait == null || !mayWait()) return response
            response.close()
            pause(chain.call(), wait)
            response = chain.proceed(request)
            retry++
        }
    }

    private fun pause(call: Call, ms: Long) {
        val end = System.nanoTime() + ms * 1_000_000
        while (true) {
            if (call.isCanceled()) throw IOException("Canceled")
            val left = (end - System.nanoTime()) / 1_000_000
            if (left <= 0) return
            try {
                Thread.sleep(minOf(left, sliceMs))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("interrupted while waiting to retry")
            }
        }
    }
}
