package io.github.hsw.inkeasel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OdExchange] against a scripted frame (docs/protocol.md#opendisplay-upload): each request is answered with
 * what the firmware sends back (`display_service.cpp`), and the requests are checked.
 */
class OdExchangeTest {
    private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }

    /** A frame that answers each request with [answer] (its notifications, in order). */
    private class Frame(val answer: Frame.(ByteArray) -> List<ByteArray>) {
        val replies = Channel<ByteArray>(Channel.UNLIMITED)
        val sent = mutableListOf<ByteArray>()
        val dataChunks = mutableListOf<Boolean>() // per request: sent as a data chunk
        val exchange = OdExchange(replies, { null }) { v, data ->
            sent += v
            dataChunks += data
            answer(v).forEach { replies.trySend(it) }
        }

        /** The opcode of each request sent. */
        val ops: List<Int> get() = sent.map { op(it) }

        /** The bytes of every [Od.WRITE_DATA] request, joined. */
        val data: ByteArray get() = sent.filter { op(it) == Od.WRITE_DATA }.fold(ByteArray(0)) { acc, r -> acc + r.copyOfRange(2, r.size) }
    }

    private fun txtOf(e: Throwable) = (e as TxtException).txt

    /**
     * The firmware's direct write ([payloadSize] image bytes): start `00 70`, each chunk `00 71`, an uncompressed
     * write ended by the frame on its last byte (`00 72`), the end `00 72`; then [refresh] (`00 73` done).
     */
    private fun firmware(payloadSize: Int, refresh: ByteArray = b(0x00, 0x73), compressedStart: ByteArray = b(0x00, 0x70)): Frame.(ByteArray) -> List<ByteArray> {
        var got = 0
        var compressed = false
        return { r ->
            when (op(r)) {
                Od.WRITE_START -> {
                    compressed = r.size > 2
                    listOf(if (compressed) compressedStart else b(0x00, 0x70))
                }
                Od.WRITE_DATA -> {
                    got += r.size - 2
                    if (!compressed && got >= payloadSize) listOf(b(0x00, 0x72), refresh) else listOf(b(0x00, 0x71))
                }
                Od.WRITE_END -> listOf(b(0x00, 0x72), refresh)
                else -> emptyList()
            }
        }
    }

    @Test
    fun uncompressed_endsWithTheLastChunk_noEndRequest() = runTest {
        val payload = ByteArray(500) { it.toByte() }
        val f = Frame(firmware(payload.size))
        f.exchange.write(payload, null)
        assertEquals(listOf(Od.WRITE_START, Od.WRITE_DATA, Od.WRITE_DATA, Od.WRITE_DATA), f.ops)
        assertArrayEquals(Od.start(), f.sent[0])
        assertArrayEquals(payload, f.data)
        assertEquals(listOf(false, true, true, true), f.dataChunks) // only the chunks may go without response
    }

    @Test
    fun compressed_headInTheStart_restInChunks_thenTheEnd() = runTest {
        val payload = ByteArray(96_000)
        val zlib = ByteArray(Od.START_DATA + 300) { (it * 7).toByte() }
        val f = Frame(firmware(payload.size))
        f.exchange.write(payload, zlib)
        assertEquals(listOf(Od.WRITE_START, Od.WRITE_DATA, Od.WRITE_DATA, Od.WRITE_END), f.ops)
        assertArrayEquals(Od.startCompressed(payload.size, zlib.copyOf(Od.START_DATA)), f.sent[0])
        assertArrayEquals(zlib.copyOfRange(Od.START_DATA, zlib.size), f.data)
        assertArrayEquals(Od.end(), f.sent.last())
    }

    @Test
    fun compressed_wholeStreamInTheStart_endFollowsAtOnce() = runTest {
        // an all-white 800×480 four-grey frame compresses to about 116 bytes: it all goes in the start request.
        // The firmware inflates it there and finishes on the end request (handleDirectWriteStart, then
        // directWriteFinishAndRefresh), so no data request is needed.
        val payload = ByteArray(96_000)
        val zlib = Od.zlib(payload)
        assertTrue("${zlib.size} B", zlib.size <= Od.START_DATA)
        val f = Frame(firmware(payload.size))
        f.exchange.write(payload, zlib)
        assertEquals(listOf(Od.WRITE_START, Od.WRITE_END), f.ops)
        assertArrayEquals(Od.startCompressed(payload.size, zlib), f.sent[0])
    }

    @Test
    fun refusedCompressedStart_isSentUncompressed() = runTest {
        for (refusal in listOf(b(0xFF, 0x70), b(0xFF, 0xFF))) { // current firmware, older firmware
            val payload = ByteArray(300) { 1 }
            val f = Frame(firmware(payload.size, compressedStart = refusal))
            f.exchange.write(payload, ByteArray(250) { 2 })
            assertEquals(listOf(Od.WRITE_START, Od.WRITE_START, Od.WRITE_DATA, Od.WRITE_DATA), f.ops)
            assertArrayEquals(Od.start(), f.sent[1])
            assertArrayEquals(payload, f.data)
        }
    }

    @Test
    fun refreshTimeout_isAnError() = runTest {
        val f = Frame(firmware(10, refresh = b(0x00, 0x74)))
        val e = runCatching { f.exchange.write(ByteArray(10), null) }.exceptionOrNull()!!
        assertEquals(Txt(R.string.frame_err_refresh), txtOf(e))
    }

    @Test
    fun refreshAnsweredOtherwise_namesTheStep() = runTest {
        // an answer that is neither done nor timed out: its opcode, as an unexpected answer
        val other = runCatching { Frame(firmware(10, refresh = b(0x00, 0x75))).exchange.write(ByteArray(10), null) }.exceptionOrNull()!!
        assertEquals(Txt(R.string.frame_err_unexpected, OdStep.REFRESH.txt, 0x75), txtOf(other))
        // a NACK: its status, as a refusal
        val nack = runCatching { Frame(firmware(10, refresh = b(0xFF, 0x73))).exchange.write(ByteArray(10), null) }.exceptionOrNull()!!
        assertEquals(Txt(R.string.frame_err_refused, OdStep.REFRESH.txt, 0xFF), txtOf(nack))
    }

    @Test
    fun compressedChunkAnsweredWithTheEnd_isUnexpected() = runTest {
        // a compressed write never ends by itself: an end answer to a chunk is not the frame finishing
        val f = Frame { r ->
            when (op(r)) {
                Od.WRITE_START -> listOf(b(0x00, 0x70))
                Od.WRITE_DATA -> listOf(b(0x00, 0x72))
                else -> emptyList()
            }
        }
        val e = runCatching { f.exchange.write(ByteArray(1000), ByteArray(Od.START_DATA + 10)) }.exceptionOrNull()!!
        assertEquals(Txt(R.string.frame_err_unexpected, OdStep.DATA.txt, 0x72), txtOf(e))
    }

    @Test
    fun refusedChunk_givesTheStatus() = runTest {
        val f = Frame { r -> if (op(r) == Od.WRITE_START) listOf(b(0x00, 0x70)) else listOf(b(0xFF, 0x71)) }
        val e = runCatching { f.exchange.write(ByteArray(10), null) }.exceptionOrNull()!!
        assertEquals(Txt(R.string.frame_err_refused, OdStep.DATA.txt, 0xFF), txtOf(e))
    }

    @Test
    fun noAnswer_timesOutNamingTheStep() = runTest {
        val f = Frame { emptyList() }
        val e = runCatching { f.exchange.write(ByteArray(10), null) }.exceptionOrNull()!!
        assertEquals(Txt(R.string.frame_err_timeout, OdStep.START.txt, 10_000), txtOf(e))
    }

    @Test
    fun aChunk_mayTakeAsLongAsTheReferenceSenderAllows() = runTest {
        // the frame writes each chunk to the panel before answering: up to about a minute on colour panels
        val slow = Frame { r -> if (op(r) == Od.WRITE_START) listOf(b(0x00, 0x70)) else emptyList() }
        val e = runCatching { slow.exchange.write(ByteArray(10), null) }.exceptionOrNull()!!
        assertEquals(Txt(R.string.frame_err_timeout, OdStep.DATA.txt, 90_000), txtOf(e))
    }

    @Test
    fun lostLink_isTheError() = runTest {
        val gone = TxtException(Txt(R.string.frame_err_lost, 8))
        val replies = Channel<ByteArray>(Channel.UNLIMITED)
        val x = OdExchange(replies, { gone }) { _, _ -> replies.close(gone) }
        assertEquals(gone.txt, txtOf(runCatching { x.write(ByteArray(10), null) }.exceptionOrNull()!!))
    }

    /** A config with a system packet, then the four-grey display record of an 800×480 panel. */
    private val display = OdFixtures.display()
    private val config = OdFixtures.config(display)

    @Test
    fun display_assemblesTheConfig_skippingOtherAnswers() = runTest {
        val total = config.size
        val f = Frame { r ->
            if (op(r) != Od.READ_CONFIG) emptyList() else listOf(
                b(0x00, 0x73), // a stray answer of another request
                b(0x00, 0x40, 0, 0, total, 0) + config.copyOfRange(0, 40),
                b(0x00, 0x40, 1, 0) + config.copyOfRange(40, total),
            )
        }
        assertEquals(OdDisplay(display), f.exchange.display("OD000001"))
        assertEquals(listOf(Od.READ_CONFIG), f.ops)
    }

    @Test
    fun display_authRequired_isRefused() = runTest {
        for (answer in listOf(b(0xFE, 0x40), b(0x00, 0x40, 0xFE))) {
            val e = runCatching { Frame { listOf(answer) }.exchange.display("OD000001") }.exceptionOrNull()!!
            assertEquals(Txt(R.string.frame_err_encrypted), txtOf(e))
        }
    }

    @Test
    fun display_shortFirstChunk_isAConfigError() = runTest {
        val e = runCatching { Frame { listOf(b(0x00, 0x40, 0, 0)) }.exchange.display("OD000001") }.exceptionOrNull()!!
        assertEquals(Txt(R.string.frame_err_config), txtOf(e))
        val refused = runCatching { Frame { listOf(b(0xFF, 0x40)) }.exchange.display("OD000001") }.exceptionOrNull()!!
        assertEquals(Txt(R.string.frame_err_refused, OdStep.CONFIG.txt, 0xFF), txtOf(refused))
    }

    @Test
    fun steps_haveTheirOwnTexts() {
        assertEquals(OdStep.entries.size, OdStep.entries.map { it.txt.id }.toSet().size)
        assertFalse(OdStep.entries.any { it.txt.args.isNotEmpty() })
    }

    private companion object {
        /** The opcode of request [r]. */
        fun op(r: ByteArray) = ((r[0].toInt() and 0xFF) shl 8) or (r[1].toInt() and 0xFF)
    }
}
