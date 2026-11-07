package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TxtTest {
    // a fake resource table: what Txt.format sees instead of Context.getString
    private val table = mapOf(
        R.string.retry to "Retry",
        R.string.header_frame to "Frame: %1\$s",
        R.string.frame_signal to "signal %1\$d dBm",
        R.string.frame_toast_unverified to "Frame %1\$s: %2\$s",
        R.string.frame_unverified to "not verified on hardware",
    )

    private val calls = mutableListOf<Int>()

    private fun lookup(id: Int, args: Array<Any>): String {
        calls += id
        val template = table.getValue(id)
        return if (args.isEmpty()) template else String.format(template, *args)
    }

    @Test
    fun `equal by id and args`() {
        assertEquals(Txt(R.string.retry), Txt(R.string.retry))
        assertEquals(Txt(R.string.header_frame, listOf("NM000001")), txt(R.string.header_frame, "NM000001"))
        assertNotEquals(Txt(R.string.retry), Txt(R.string.close))
        assertNotEquals(txt(R.string.header_frame, "NM000001"), txt(R.string.header_frame, "NM000002"))
        assertNotEquals(txt(R.string.frame_signal, -60), txt(R.string.frame_signal, "-60"))
    }

    @Test
    fun `equal with nested args`() {
        val a = txt(R.string.frame_toast_unverified, "NM000001", Txt(R.string.frame_unverified))
        val b = txt(R.string.frame_toast_unverified, "NM000001", Txt(R.string.frame_unverified))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, txt(R.string.frame_toast_unverified, "NM000001", Txt(R.string.frame_unavailable)))
    }

    @Test
    fun `txt shorthand without args`() {
        assertEquals(Txt(R.string.retry, emptyList()), txt(R.string.retry))
    }

    @Test
    fun `format without args`() {
        assertEquals("Retry", Txt(R.string.retry).format(::lookup))
    }

    @Test
    fun `format with string and int args`() {
        assertEquals("Frame: NM000001", txt(R.string.header_frame, "NM000001").format(::lookup))
        assertEquals("signal -60 dBm", txt(R.string.frame_signal, -60).format(::lookup))
    }

    @Test
    fun `nested args are resolved first`() {
        val t = txt(R.string.frame_toast_unverified, "NM000001", Txt(R.string.frame_unverified))
        assertEquals("Frame NM000001: not verified on hardware", t.format(::lookup))
        assertEquals(listOf(R.string.frame_unverified, R.string.frame_toast_unverified), calls)
    }

    @Test
    fun `deeply nested args`() {
        val inner = txt(R.string.header_frame, "NM000001")
        val t = txt(R.string.frame_toast_unverified, "NM000001", txt(R.string.frame_toast_unverified, "x", inner))
        assertEquals("Frame NM000001: Frame x: Frame: NM000001", t.format(::lookup))
    }

    @Test
    fun `TxtException carries the text and is an IOException`() {
        val cause = RuntimeException("boom")
        val e = TxtException(txt(R.string.header_frame, "NM000001"), cause)
        assertTrue((e as Any) is IOException)
        assertEquals(txt(R.string.header_frame, "NM000001"), e.txt)
        assertEquals(cause, e.cause)
    }
}
