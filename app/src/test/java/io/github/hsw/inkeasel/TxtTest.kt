package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

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
    fun equal_byIdAndArgs() {
        assertEquals(Txt(R.string.retry), Txt(R.string.retry))
        assertEquals(Txt(R.string.header_frame, listOf("NM000001")), Txt(R.string.header_frame, "NM000001"))
        assertNotEquals(Txt(R.string.retry), Txt(R.string.close))
        assertNotEquals(Txt(R.string.header_frame, "NM000001"), Txt(R.string.header_frame, "NM000002"))
        assertNotEquals(Txt(R.string.frame_signal, -60), Txt(R.string.frame_signal, "-60"))
    }

    @Test
    fun equal_withNestedArgs() {
        val a = Txt(R.string.frame_toast_unverified, "NM000001", Txt(R.string.frame_unverified))
        val b = Txt(R.string.frame_toast_unverified, "NM000001", Txt(R.string.frame_unverified))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, Txt(R.string.frame_toast_unverified, "NM000001", Txt(R.string.frame_unavailable)))
    }

    @Test
    fun varargConstructor_equalsTheListOne() {
        assertEquals(Txt(R.string.retry, emptyList()), Txt(R.string.retry))
        assertEquals(Txt(R.string.frame_signal, listOf(-60)), Txt(R.string.frame_signal, -60))
    }

    @Test
    fun format_withoutArgs() {
        assertEquals("Retry", Txt(R.string.retry).format(::lookup))
    }

    @Test
    fun format_withStringAndIntArgs() {
        assertEquals("Frame: NM000001", Txt(R.string.header_frame, "NM000001").format(::lookup))
        assertEquals("signal -60 dBm", Txt(R.string.frame_signal, -60).format(::lookup))
    }

    @Test
    fun nestedArgs_resolvedFirst() {
        val t = Txt(R.string.frame_toast_unverified, "NM000001", Txt(R.string.frame_unverified))
        assertEquals("Frame NM000001: not verified on hardware", t.format(::lookup))
        assertEquals(listOf(R.string.frame_unverified, R.string.frame_toast_unverified), calls)
    }

    @Test
    fun deeplyNestedArgs() {
        val inner = Txt(R.string.header_frame, "NM000001")
        val t = Txt(R.string.frame_toast_unverified, "NM000001", Txt(R.string.frame_toast_unverified, "x", inner))
        assertEquals("Frame NM000001: Frame x: Frame: NM000001", t.format(::lookup))
    }

    @Test
    fun txtException_carriesTheTextAndTheCause() {
        val cause = RuntimeException("boom")
        val e = TxtException(Txt(R.string.header_frame, "NM000001"), cause)
        assertEquals(Txt(R.string.header_frame, "NM000001"), e.txt)
        assertEquals(cause, e.cause)
    }

    @Test
    fun userText_ownTextElseGenericWithMessageOrClassName() {
        val own = Txt(R.string.bt_off)
        assertEquals(own, TxtException(own).userText(R.string.viewer_failed))
        assertEquals(Txt(R.string.viewer_failed, "boom"), IllegalStateException("boom").userText(R.string.viewer_failed))
        assertEquals(Txt(R.string.upload_err_text, "IOException"), java.io.IOException(" ").userText(R.string.upload_err_text))
    }
}
