package io.github.hsw.inkeasel

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import coil.request.Disposable
import coil.request.ImageRequest
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class HttpSetupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()

    @After
    fun stop() = server.shutdown()

    private fun call() = Http.client.newCall(Request.Builder().url(server.url("/a")).build())

    @Test
    fun fourRequestsPerHostAndTheRetry() {
        assertEquals(4, Http.client.dispatcher.maxRequestsPerHost)
        assertTrue(Http.client.interceptors.any { it is RetryInterceptor })
    }

    /** Runs the main looper (Coil's interceptor dispatcher) until [done] or 10 s have passed. */
    private fun idleUntil(done: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done() && System.nanoTime() < end) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    // Coil sends a thumbnail with Call.execute() only on the main thread (cache-only requests); its fetchers
    // run on Dispatchers.IO, where it enqueues the call, so the dispatcher's per-host limit counts it and a
    // card that is disposed cancels its call
    @Test
    fun cardThumbnailsKeepToFourRequestsPerHostAndADisposedCardSendsNothing() {
        val gate = CountDownLatch(1)
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                gate.await(10, TimeUnit.SECONDS)
                inFlight.decrementAndGet()
                return MockResponse().setResponseCode(404)
            }
        }
        val loader = Images.loader(context)
        val calls = Http.client.dispatcher
        var cards = emptyList<Disposable>()
        try {
            cards = (1..10).map { n ->
                loader.enqueue(
                    ImageRequest.Builder(context).data(server.url("/thumb/$n").toString())
                        .tag(Holdable::class.java, Holdable).build(),
                )
            }
            idleUntil { inFlight.get() == 4 && calls.queuedCallsCount() == 6 }
            Thread.sleep(200)
            assertEquals(4, most.get())
            assertEquals(6, calls.queuedCallsCount())

            cards.forEach { it.dispose() }
            gate.countDown()
            idleUntil { calls.runningCallsCount() == 0 && calls.queuedCallsCount() == 0 }
            assertEquals(0, calls.runningCallsCount())
            assertEquals(4, server.requestCount)
        } finally {
            gate.countDown()
            cards.forEach { it.dispose() }
            loader.shutdown()
        }
    }

    @Test
    fun noRetryWaitOnTheMainThread() {
        assertTrue(Looper.getMainLooper().isCurrentThread)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody("late"))

        call().execute().use { assertEquals(429, it.code) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anotherThreadRetries() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody("picture"))

        var code = 0
        thread { call().execute().use { code = it.code } }.join()
        assertEquals(200, code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun theImageLoaderKeepsAt150MbOnDisk() {
        val loader = Images.loader(context)
        try {
            val cache = loader.diskCache!!
            assertEquals(150L * 1024 * 1024, cache.maxSize)
            assertEquals(File(context.cacheDir, "image_cache").absolutePath, cache.directory.toFile().absolutePath)
        } finally {
            loader.shutdown()
        }
    }
}
