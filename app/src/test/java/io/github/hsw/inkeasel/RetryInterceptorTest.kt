package io.github.hsw.inkeasel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RetryInterceptorTest {
    private val server = MockWebServer()

    private fun client(mayWait: () -> Boolean = { true }): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(RetryInterceptor(mayWait)).build()

    private fun get(c: OkHttpClient = client(), path: String = "/a"): Response =
        c.newCall(Request.Builder().url(server.url(path)).build()).execute()

    private fun answer(code: Int, retryAfter: String? = null, body: String = "") = MockResponse()
        .setResponseCode(code).setBody(body).apply { if (retryAfter != null) setHeader("Retry-After", retryAfter) }

    @After
    fun stop() = server.shutdown()

    @Test
    fun tooManyRequestsThenOk() {
        server.enqueue(answer(429, "0"))
        server.enqueue(answer(200, body = "picture"))

        get().use { r ->
            assertEquals(200, r.code)
            assertEquals("picture", r.body!!.string())
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun unavailableUntilTheRetriesRunOut() {
        // not 0: OkHttp itself asks a 503 with "Retry-After: 0" once more
        repeat(4) { server.enqueue(answer(503, "1", body = "down")) }

        get().use { r ->
            assertEquals(503, r.code)
            assertEquals("down", r.body!!.string())
        }
        // the first request and two retries
        assertEquals(3, server.requestCount)
    }

    @Test
    fun withoutRetryAfterTheDefaultWaitIsKept() {
        server.enqueue(answer(503))
        server.enqueue(answer(200))

        val start = System.nanoTime()
        get().use { assertEquals(200, it.code) }
        val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("waited $waited ms", waited >= RetryPolicy.DEFAULT_WAITS_MS[0])
        assertEquals(2, server.requestCount)
    }

    @Test
    fun retryAfterLongerThanTheCapIsReturnedAtOnce() {
        server.enqueue(answer(429, "60"))
        server.enqueue(answer(200))

        val start = System.nanoTime()
        get().use { assertEquals(429, it.code) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun otherErrorsAreNotRetried() {
        for (code in listOf(400, 404, 500, 502, 504)) {
            server.enqueue(answer(code, "0"))
            server.enqueue(answer(200))
            get().use { assertEquals(code, it.code) }
            // the unused 200 answers the next request
            get().use { assertEquals(200, it.code) }
        }
        assertEquals(10, server.requestCount)
    }

    @Test
    fun aPostIsNotRetried() {
        server.enqueue(answer(503, "1"))
        server.enqueue(answer(200))
        val post = Request.Builder().url(server.url("/a")).post("x".toRequestBody()).build()

        client().newCall(post).execute().use { assertEquals(503, it.code) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun whereItMayNotWaitTheAnswerIsReturnedAsItIs() {
        server.enqueue(answer(429, "0"))
        server.enqueue(answer(200))

        get(client(mayWait = { false })).use { assertEquals(429, it.code) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRetryStartsAgainBeforeTheRedirect() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/signed"))
        server.enqueue(answer(429, "0"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/signed"))
        server.enqueue(answer(200, body = "file"))

        get(path = "/release").use { assertEquals("file", it.body!!.string()) }
        assertEquals(listOf("/release", "/signed", "/release", "/signed"), List(4) { server.takeRequest().path })
    }

    @Test
    fun theRequestIsSentAgainAsItWas() {
        server.enqueue(answer(429, "0"))
        server.enqueue(answer(304))
        val request = Request.Builder().url(server.url("/a")).header("If-None-Match", "\"e1\"").build()

        client().newCall(request).execute().use { assertEquals(304, it.code) }
        repeat(2) { assertEquals("\"e1\"", server.takeRequest().getHeader("If-None-Match")) }
    }

    @Test
    fun aCancelledCallStopsWaiting() {
        server.enqueue(answer(503, "10"))
        server.enqueue(answer(200))
        val call = client().newCall(Request.Builder().url(server.url("/a")).build())
        val failed = AtomicReference<IOException>()
        val done = CountDownLatch(1)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                failed.set(e)
                done.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                done.countDown()
            }
        })
        server.takeRequest()
        Thread.sleep(200)

        val start = System.nanoTime()
        call.cancel()
        assertTrue("still waiting", done.await(2, TimeUnit.SECONDS))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000)
        assertTrue(failed.get() != null)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aCallTimeoutStopsWaiting() {
        server.enqueue(answer(503, "10"))
        server.enqueue(answer(200))
        val c = client().newBuilder().callTimeout(500, TimeUnit.MILLISECONDS).build()

        val start = System.nanoTime()
        assertThrows(IOException::class.java) { get(c) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aCancelledCoroutineStopsWaiting() = runBlocking {
        server.enqueue(answer(503, "10"))
        server.enqueue(answer(200))
        val c = client()
        val pending = async(Dispatchers.IO) {
            suspendCancellableCoroutine<Int> { cont ->
                val call = c.newCall(Request.Builder().url(server.url("/a")).build())
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = cont.resumeWith(Result.failure(e))
                    override fun onResponse(call: Call, response: Response) = response.use { cont.resumeWith(Result.success(it.code)) }
                })
            }
        }
        withContext(Dispatchers.IO) { server.takeRequest() }

        val start = System.nanoTime()
        pending.cancel()
        withTimeout(2_000) {
            try {
                pending.await()
            } catch (e: CancellationException) {
                // expected
            }
        }
        // the call's thread is free again well before the 10 s wait
        withTimeout(2_000) { while (c.dispatcher.runningCallsCount() > 0) kotlinx.coroutines.delay(20) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aHostThatAskedForAPauseGetsNoRequestUntilItEnds() {
        var clock = 1_000_000L
        val c = OkHttpClient.Builder().addInterceptor(RetryInterceptor(now = { clock })).build()
        server.enqueue(answer(429, "30", body = "slow down"))
        server.enqueue(answer(200, body = "picture"))

        get(c).use { assertEquals(429, it.code) } // longer than a retry may wait: final
        assertEquals(1, server.requestCount)

        // held: answered here, with what is left of the pause, and not sent
        clock += 12_500
        get(c, "/b").use { r ->
            assertEquals(429, r.code)
            assertEquals("18", r.header("Retry-After"))
            assertEquals("", r.body!!.string())
        }
        assertEquals(1, server.requestCount)

        // the pause is over
        clock += 17_500
        get(c, "/b").use { assertEquals("picture", it.body!!.string()) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aShortHoldIsWaitedOutWhereItMayWait() {
        var mayWait = false
        val c = client(mayWait = { mayWait })
        server.enqueue(answer(503, "1"))
        server.enqueue(answer(200))

        get(c).use { assertEquals(503, it.code) } // the main thread: returned as it is, the host is held 1 s
        // still held, and the main thread does not wait
        get(c).use { assertEquals(429, it.code) }
        assertEquals(1, server.requestCount)

        mayWait = true
        val start = System.nanoTime()
        get(c).use { assertEquals(200, it.code) }
        val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("waited $waited ms", waited >= 500)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun onlyTheHostThatAskedIsHeld() {
        val other = MockWebServer()
        try {
            val c = client()
            server.enqueue(answer(429, "30"))
            other.enqueue(answer(200, body = "other"))

            get(c).use { assertEquals(429, it.code) }
            c.newCall(Request.Builder().url(other.url("/a")).build()).execute().use {
                assertEquals("other", it.body!!.string())
            }
            assertEquals(1, other.requestCount)
        } finally {
            other.shutdown()
        }
    }
}
