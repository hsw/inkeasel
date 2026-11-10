package io.github.hsw.inkeasel

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The OpenDisplay request and answer sequences this app uses (docs/protocol.md#opendisplay-config,
 * docs/protocol.md#opendisplay-upload), over any link: [send] writes one request (`data`: a [Od.WRITE_DATA]
 * chunk, which may go without response), [replies] carries the frame's answers as they arrive (closed with the
 * cause when the link drops), and [lost] says why the link is gone, if it is. Free of Android types, so tests
 * drive it with a scripted frame; `OdSession` is the GATT link.
 */
internal class OdExchange(
    private val replies: ReceiveChannel<ByteArray>,
    private val lost: () -> Exception?,
    private val log: (String) -> Unit = {},
    private val send: suspend (value: ByteArray, data: Boolean) -> Unit,
) {
    /** Reads the frame's config and returns its display record; the frame [name] is for the log. */
    suspend fun display(name: String): OdDisplay {
        val reader = Od.ConfigReader()
        request(Od.request(Od.READ_CONFIG))
        var config: ByteArray? = null
        var timeout = 10_000L
        while (config == null) {
            val r = reply(Od.READ_CONFIG, OdStep.CONFIG, timeout)
            if (!r.ok) throw refused(OdStep.CONFIG, r)
            config = try {
                reader.add(r.data)
            } catch (e: IllegalArgumentException) {
                throw TxtException(Txt(R.string.od_err_config), e)
            }
            timeout = 3_000
        }
        // only the display record goes to the log: the rest of the config holds the Wi-Fi password and the key
        val d = Od.display(config) ?: throw TxtException(Txt(R.string.od_err_config))
        log("$name: ${config.size} B of config, $d")
        return d
    }

    /**
     * Writes [payload] as a full-frame direct write and waits for the refresh: compressed as [zlib] if given
     * (back to uncompressed if the frame refuses the compressed start), else uncompressed. A [zlib] stream that
     * fits in the start request whole is followed by the end at once, with no [Od.WRITE_DATA].
     */
    suspend fun write(payload: ByteArray, zlib: ByteArray?) {
        val t0 = System.nanoTime()
        val ended = if (zlib != null && startCompressed(payload.size, zlib)) {
            chunks(zlib.copyOfRange(minOf(zlib.size, Od.START_DATA), zlib.size), canEnd = false)
        } else {
            request(Od.start())
            reply(Od.WRITE_START, OdStep.START, 10_000).also { if (!it.ok) throw refused(OdStep.START, it) }
            chunks(payload, canEnd = true)
        }
        log("data: ${payload.size} B as ${zlib?.size ?: payload.size} B in ${ms(t0)} ms, ended by the frame: $ended")
        if (!ended) {
            request(Od.end())
            reply(Od.WRITE_END, OdStep.END, 90_000).also { if (!it.ok) throw refused(OdStep.END, it) }
        }
        val r = reply(null, OdStep.REFRESH, 90_000)
        log("refresh: $r after ${ms(t0)} ms")
        if (!r.ok) throw refused(OdStep.REFRESH, r)
        when (r.op) {
            Od.REFRESH_DONE -> return
            Od.REFRESH_TIMEOUT -> throw TxtException(Txt(R.string.od_err_refresh))
            else -> throw unexpected(OdStep.REFRESH, r)
        }
    }

    /**
     * Starts a compressed write of [size] image bytes with the head of [zlib]; false if the frame refuses it
     * (`FF 70`, or `FF FF` from older firmware), so the caller can send uncompressed.
     */
    private suspend fun startCompressed(size: Int, zlib: ByteArray): Boolean {
        request(Od.startCompressed(size, zlib.copyOf(minOf(zlib.size, Od.START_DATA))))
        val r = reply(null, OdStep.START, 10_000)
        if (r.ok && r.op == Od.WRITE_START) return true
        log("compressed start refused: $r; sending uncompressed")
        return false
    }

    /**
     * Sends [data] in [Od.WRITE_DATA] chunks, each answered within [DATA_MS]. True if the frame answered the
     * last one with the end ([canEnd]: an uncompressed write ends by itself once the frame has every byte).
     */
    private suspend fun chunks(data: ByteArray, canEnd: Boolean): Boolean {
        var at = 0
        while (at < data.size) {
            val chunk = data.copyOfRange(at, minOf(data.size, at + Od.CHUNK))
            send(Od.data(chunk), true)
            at += chunk.size
            val r = reply(null, OdStep.DATA, DATA_MS)
            if (!r.ok) throw refused(OdStep.DATA, r)
            if (canEnd && r.op == Od.WRITE_END) return true
            if (r.op != Od.WRITE_DATA) throw unexpected(OdStep.DATA, r)
        }
        return false
    }

    /** Writes a request with response, after dropping any stale answer. */
    private suspend fun request(value: ByteArray) {
        while (replies.tryReceive().isSuccess) Unit
        send(value, false)
    }

    /**
     * The next answer, for opcode [op] (others are skipped; `null`: any); fails on "auth required" (encryption
     * is on), when the link drops and after [timeoutMs].
     */
    private suspend fun reply(op: Int?, step: OdStep, timeoutMs: Long): Od.Reply =
        withTimeoutOrNull(timeoutMs) { next(op, step) }
            ?: throw (lost() ?: TxtException(Txt(R.string.frame_err_timeout, step.txt, timeoutMs.toInt())))

    private suspend fun next(op: Int?, step: OdStep): Od.Reply {
        while (true) {
            val r = Od.Reply.parse(replies.receive()) ?: continue
            if (r.authRequired) throw TxtException(Txt(R.string.od_err_auth))
            if (op == null || r.op == (op and 0xFF)) return r
            log("$step: skipping $r")
        }
    }

    private fun refused(step: OdStep, r: Od.Reply) = TxtException(Txt(R.string.od_err_refused, step.txt, r.status))

    private fun unexpected(step: OdStep, r: Od.Reply) = TxtException(Txt(R.string.od_err_unexpected, step.txt, r.op))

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    internal companion object {
        /**
         * How long the frame may take to answer one [Od.WRITE_DATA] chunk, as in the reference sender
         * (py-opendisplay): the answer follows the firmware's write of the chunk to the panel, which can hold the
         * panel's bus for up to about 60 s on Spectra 6 and other colour panels, so a shorter wait would fail a
         * slow but healthy transfer midway.
         */
        const val DATA_MS = 90_000L
    }
}

/** A step of [OdExchange], as named in the messages ([txt]) and the log ([name]). */
internal enum class OdStep(private val id: Int) {
    CONFIG(R.string.od_err_step_config),
    START(R.string.od_err_step_start),
    DATA(R.string.od_err_step_data),
    END(R.string.od_err_step_end),
    REFRESH(R.string.od_err_step_refresh);

    val txt: Txt get() = Txt(id)
}
