package io.github.hsw.inkeasel

import coil.network.HttpException
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap

/**
 * When a request is asked again: only after HTTP 429 (too many requests) or 503 (unavailable), only for GET
 * and HEAD, at most [MAX_RETRIES] times, and never later than [MAX_WAIT_MS] from the answer. `Retry-After`
 * (seconds or an HTTP date) is honoured: an answer that asks for a longer wait than [MAX_WAIT_MS] is final, so
 * a server is never asked earlier than it said. Without the header the waits are [DEFAULT_WAITS_MS]. No other
 * code is retried, and neither is a connection failure (OkHttp retries those itself). A host that answered
 * 429 or 503 is held for the wait it asked for ([holdMs]): no card thumbnail ([Holdable]) is sent to it until
 * then. A card's thumbnail that failed so is asked again once the wait is over ([thumbRetryMs]). Pure.
 */
object RetryPolicy {
    const val MAX_RETRIES = 2
    const val MAX_WAIT_MS = 10_000L

    /** The longest a host is held ([holdMs]), whatever its `Retry-After` says. */
    const val MAX_HOLD_MS = 60_000L

    /** How many times a card asks for its thumbnail again after a 429 or 503 ([thumbRetryMs]). */
    const val THUMB_RETRIES = 3

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
     * How long nothing more is sent to a host after it answered [code] with the `Retry-After` value
     * [retryAfter] at [nowMs]: after 429 or 503 (to any method) the wait it asked for, or the first default
     * wait without one, at most [MAX_HOLD_MS]; `null` after any other answer.
     */
    fun holdMs(code: Int, retryAfter: String?, nowMs: Long): Long? {
        if (code !in CODES) return null
        return (retryAfterMs(retryAfter, nowMs) ?: DEFAULT_WAITS_MS[0]).coerceAtMost(MAX_HOLD_MS)
    }

    /** The `Retry-After` seconds of the answer to a request not sent because its host is held [leftMs] more. */
    fun retryAfterSeconds(leftMs: Long): Long = (leftMs.coerceAtLeast(0) + 999) / 1000

    /**
     * How long a card waits before it asks again for its thumbnail, which failed with HTTP [code] (`null`: not
     * an HTTP answer) and the `Retry-After` value [retryAfter] at [nowMs], after [retries] earlier retries;
     * `null`: it does not ask again. Only after 429 or 503, at most [THUMB_RETRIES] times, after the wait the
     * answer asked for (the first default wait without one), at most [MAX_HOLD_MS].
     */
    fun thumbRetryMs(code: Int?, retryAfter: String?, retries: Int, nowMs: Long): Long? {
        if (code == null || retries >= THUMB_RETRIES) return null
        return holdMs(code, retryAfter, nowMs)?.coerceAtLeast(DEFAULT_WAITS_MS[0])
    }

    /**
     * [thumbRetryMs] for the [error] Coil reports for a card's thumbnail: its HTTP answer, a local "held" one
     * ([RetryInterceptor]) included; any other failure is not asked again.
     */
    fun thumbRetryMs(error: Throwable, retries: Int, nowMs: Long): Long? {
        val answer = (error as? HttpException)?.response
        return thumbRetryMs(answer?.code, answer?.header("Retry-After"), retries, nowMs)
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
 * The tag of a request the host hold applies to ([RetryInterceptor]): a card's thumbnail, which a scroll asks
 * for by the hundred and the card asks for again after the pause ([RetryPolicy.thumbRetryMs]). Coil passes an
 * image request's tags on to OkHttp. A request without it (the picture sent to the frame, the viewer's
 * rendition, a list, a release file: something a user asked for, or asked for once) is never held: it is sent,
 * and its own 429 or 503 is retried or returned as the server gave it.
 */
object Holdable

/**
 * Asks a request again as [RetryPolicy] says. The wait runs on the calling thread (an OkHttp dispatcher
 * thread for an enqueued call) in slices of [sliceMs], checking the call before and while waiting: a cancelled
 * call (a cancelled coroutine, a call timeout) stops at once with an [IOException]. Where [mayWait] is false
 * (the main thread) the answer is returned as it is. The answer that is not used is closed.
 *
 * A host (with its port) that answered 429 or 503 is held ([RetryPolicy.holdMs]): a [Holdable] request to it
 * before the hold ends waits for it where it may wait no longer than [RetryPolicy.MAX_WAIT_MS], and is
 * otherwise answered here, without being sent, with a 429 whose `Retry-After` is what is left of the hold. So a
 * fast scroll past hundreds of cards sends one request to a host that asked for a pause, not hundreds. Any
 * answer holds its host, whatever the request; the hold is kept for the host the request was sent to (a
 * redirect's target is not checked before sending).
 */
class RetryInterceptor(
    private val mayWait: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val sliceMs: Long = 50,
) : Interceptor {
    /** Host and port → the time (of [now]) until which nothing is sent to it. */
    private val holds = ConcurrentHashMap<String, Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = "${request.url.host}:${request.url.port}"
        val left = (holds[host] ?: 0L) - now()
        if (left > 0 && request.tag(Holdable::class.java) != null) {
            if (!mayWait() || left > RetryPolicy.MAX_WAIT_MS) return held(request, left)
            pause(chain.call(), left)
        }
        var response = send(chain, request, host)
        var retry = 1
        while (true) {
            val wait = RetryPolicy.waitMs(request.method, response.code, response.header("Retry-After"), retry, now())
            if (wait == null || !mayWait()) return response
            response.close()
            pause(chain.call(), wait)
            response = send(chain, request, host)
            retry++
        }
    }

    /** Sends [request] and holds its [host] when the answer asks for a pause. */
    private fun send(chain: Interceptor.Chain, request: Request, host: String): Response {
        val response = chain.proceed(request)
        RetryPolicy.holdMs(response.code, response.header("Retry-After"), now())?.let { ms ->
            holds.merge(host, now() + ms, ::maxOf)
        }
        return response
    }

    /** The answer to a request not sent because its host is held [leftMs] more. */
    private fun held(request: Request, leftMs: Long): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(429)
        .message("Too Many Requests (held)")
        .header("Retry-After", RetryPolicy.retryAfterSeconds(leftMs).toString())
        .body("".toResponseBody(null))
        .build()

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
