package io.github.hsw.inkeasel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [OdClient] sessions run one at a time in the whole app ([OdClient.SESSIONS]): a settings read and an upload
 * would otherwise connect to the same frame together and take each other's answers. Each call here first
 * fails on Bluetooth (none, or off, under Robolectric), so how far it got shows whether it waited.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OdClientTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val bluetooth = setOf(R.string.bt_missing, R.string.bt_off)

    private fun failedOnBluetooth(r: Result<*>) = (r.exceptionOrNull() as? TxtException)?.txt?.id in bluetooth

    @Test
    fun readAndUpload_waitForTheSessionInProgress() = runTest {
        val d = OdDisplay(OdFixtures.display())
        val format = OdDisplay.lookup(d).formatOrNull!!
        val sel = SelectedFrame("OD000001", "AA:BB:CC:DD:EE:01", null, 1_000L, FrameKind.OPENDISPLAY, d)
        val a = OdClient(app)
        val b = OdClient(app) // another instance, as the settings and the upload engine each have their own
        OdClient.SESSIONS.lock() // a session in progress
        try {
            val read = async { runCatching { a.interrogate("OD000001") } }
            val upload = async { runCatching { b.upload(sel, format, ByteArray(format.payloadSize)) } }
            runCurrent()
            assertFalse(read.isCompleted)
            assertFalse(upload.isCompleted)
            OdClient.SESSIONS.unlock()
            assertTrue(failedOnBluetooth(read.await()))
            assertTrue(failedOnBluetooth(upload.await()))
        } finally {
            if (OdClient.SESSIONS.isLocked) OdClient.SESSIONS.unlock()
        }
        assertFalse(OdClient.SESSIONS.isLocked) // a failed session lets the next one in
    }
}
