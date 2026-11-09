package io.github.hsw.inkeasel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class LocalLoadTest {
    private fun pic(n: Int) = Picture("t:$n", "Picture $n", "", "https://example.org/$n-s.jpg", "https://example.org/$n.jpg")
    private fun pics(n: Int) = (1..n).map(::pic)

    /** A tab's source (as "My pictures") that answers [answer] (or throws it). */
    private class Fake(val answer: () -> Listing<Picture>) : Source {
        override val id = "fake"
        override val name = Txt(R.string.tab_local)
        override suspend fun load() = answer()
    }

    // --- readLocal ---

    @Test
    fun success_shown() = runTest {
        assertEquals(LocalState.Loaded(pics(3)), readLocal(Fake { Listing(pics(3)) }))
        assertEquals(LocalState.Loaded(emptyList()), readLocal(Fake { Listing(emptyList()) }))
    }

    @Test
    fun failure_speaksOfThePictures_notOfACatalog() = runTest {
        val logs = mutableListOf<String>()
        assertEquals(
            LocalState.Failed(Txt(R.string.local_failed, "Permission denied")),
            readLocal(Fake { throw IOException("Permission denied") }) { logs += it },
        )
        assertEquals(1, logs.size)
        val own = Txt(R.string.share_err_save) // a text of its own is kept
        assertEquals(LocalState.Failed(own), readLocal(Fake { throw TxtException(own) }))
    }

    @Test
    fun cancellation_isNotAFailure() = runTest {
        try {
            readLocal(Fake { throw CancellationException("gone") })
            fail("no exception")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
    }

    // --- needsLoad ---

    @Test
    fun needsLoad_neverLoadedOrFailed() {
        assertTrue((null as LocalState?).needsLoad())
        assertTrue(LocalState.Failed(Txt(R.string.local_failed, "x")).needsLoad())
        assertFalse(LocalState.Loaded(pics(1)).needsLoad())
        assertFalse(LocalState.Loaded(emptyList()).needsLoad())
        assertFalse(LocalState.Loading.needsLoad())
    }
}
