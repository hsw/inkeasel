package io.github.hsw.inkeasel

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
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
