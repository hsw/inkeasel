package io.github.hsw.inkeasel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The shared picture that waits for the permissions and a frame ([PendingShare]): it is cleared only once the
 * upload service has started, so a refused start (Android 12+ from the background) keeps it for the next try.
 */
@RunWith(RobolectricTestRunner::class)
class PendingShareTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val share = PendingShare(context)
    private lateinit var file: File
    private val now = 1_000_000L

    @Before
    fun setUp() {
        context.getSharedPreferences("share", Context.MODE_PRIVATE).edit().clear().commit()
        file = File.createTempFile("shared", ".jpg").apply { writeBytes(byteArrayOf(1)) }
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun aStartedUpload_clearsTheShare() {
        share.save(file.absolutePath, now)
        val started = mutableListOf<String>()
        assertEquals(file.absolutePath, share.send(now + 1) { started += it })
        assertEquals(listOf(file.absolutePath), started)
        assertNull(share.send(now + 2) { error("sent twice") })
    }

    @Test
    fun aRefusedStart_keepsTheShare_forTheNextTry() {
        share.save(file.absolutePath, now)
        val refusal = IllegalStateException("startForegroundService() not allowed")
        val thrown = runCatching { share.send(now + 1) { throw refusal } }.exceptionOrNull()
        assertTrue(thrown === refusal)
        assertEquals(file.absolutePath, share.send(now + 2) {})
    }

    @Test
    fun aStaleOrGoneShare_isDropped_withoutAStart() {
        share.save(file.absolutePath, now)
        assertNull(share.send(now + ShareRules.PENDING_MAX_AGE_MS + 1) { error("stale share started") })
        share.save(file.absolutePath, now)
        file.delete()
        assertNull(share.send(now + 1) { error("missing file started") })
        file.writeBytes(byteArrayOf(1)) // back again: the share was cleared all the same
        assertNull(share.send(now + 2) { error("dropped share started") })
    }

    @Test
    fun noShare_startsNothing() {
        assertNull(share.send(now) { error("nothing to send") })
    }
}
