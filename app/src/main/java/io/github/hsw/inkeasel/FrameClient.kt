package io.github.hsw.inkeasel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

private const val TAG = "FrameClient"

/**
 * BLE client for the NowMean frame.
 * No pairing or auth: connect to the frame chosen in the settings ([FrameStore]), write frames to FFE1,
 * status notifications arrive on FFE2. Without a chosen frame it uploads nothing.
 */
@SuppressLint("MissingPermission") // permissions are requested by MainActivity before any upload
class FrameClient(private val ctx: Context) {
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = ctx.getSharedPreferences("frame", Context.MODE_PRIVATE)

    private val store = FrameStore(ctx)
    private val scanner = FrameScanner(ctx)

    /** Spaces uploads by the panel refresh; the time the last image was handed over is kept in [prefs]. */
    val gate = DrawGate(SystemClock::elapsedRealtime, object : DrawGate.Store {
        override fun load(): Long? = prefs.getLong(KEY_ENDED, -1L).takeIf { it >= 0 }
        override fun save(time: Long) = prefs.edit().putLong(KEY_ENDED, time).apply()
    })

    /** Waits while the frame still draws the previous picture ([DrawGate]); not an error. */
    suspend fun awaitIdle(status: (Txt) -> Unit = {}) {
        val wait = gate.waitMs()
        if (wait <= 0) return
        Log.i(TAG, "frame still drawing the previous picture: waiting $wait ms")
        status(txt(R.string.frame_status_waiting, ((wait + 999) / 1000).toInt()))
        delay(wait)
    }

    /**
     * Sends one [payload] rendered for [format] to the selected frame ([FrameStore]); tries the fast path
     * (write without response) first, then acked writes. Refused if the selected frame no longer has that
     * format (another frame was chosen, or it advertises other properties since), so a picture is never sent
     * in a layout the frame does not expect. Each attempt first waits until the frame has finished drawing the
     * previous picture ([awaitIdle]). If the saved address does not connect, the next attempt scans for the
     * selected frame's name only (the name is its identity, the MAC may change) and remembers where it was
     * found; it never falls back to another frame.
     */
    suspend fun upload(format: FrameFormat, payload: ByteArray, status: (Txt) -> Unit, progress: (Float) -> Unit) {
        val sel = store.selected() ?: throw TxtException(Txt(R.string.no_frame_error))
        if (sel.kind != FrameKind.NOWMEAN) throw TxtException(txt(R.string.frame_err_kind, sel.name))
        if (sel.lookup.formatOrNull != format) throw TxtException(txt(R.string.frame_err_format_changed, sel.name))
        require(payload.size == format.payloadSize) { "payload ${payload.size} B, the format needs ${format.payloadSize} B" }
        val bt = adapter ?: throw TxtException(Txt(R.string.bt_missing))
        if (!bt.isEnabled) throw TxtException(Txt(R.string.bt_off))
        var device: BluetoothDevice? = try {
            bt.getRemoteDevice(sel.address)
        } catch (e: IllegalArgumentException) { // not a valid MAC: find the frame by name instead
            null
        }
        var lastError: Exception? = null
        for (reliable in listOf(false, true)) {
            awaitIdle(status)
            val dev = device ?: run {
                status(txt(R.string.frame_status_searching, sel.name))
                find(sel.name, format)
            }.also { device = it }
            status(txt(R.string.frame_status_connecting, sel.name))
            val session = GattSession(ctx, dev)
            try {
                try {
                    session.open()
                } catch (e: IOException) {
                    Log.w(TAG, "connect to ${sel.name} failed", e)
                    lastError = e
                    device = null // the saved address may be stale: look for the frame by name on the next attempt
                    continue
                }
                store.seen(sel.name, dev.address, null, System.currentTimeMillis())
                status(Txt(if (reliable) R.string.frame_status_sending_reliable else R.string.frame_status_sending))
                if (session.sendImage(format.marker, payload, reliable, progress)) return
                lastError = TxtException(Txt(R.string.frame_err_not_acked))
            } catch (e: IOException) {
                Log.w(TAG, "upload failed (reliable=$reliable)", e)
                lastError = e
            } finally {
                session.close() // also on cancellation
                if (session.endSent) gate.ended() // the frame draws once the link drops (docs/device-api.md §14.3)
            }
            delay(2_000) // let the stack tear down the old link before reconnecting
        }
        throw lastError ?: TxtException(Txt(R.string.frame_err_send))
    }

    /**
     * Scan until the frame [name] shows up; remember its address and advertisement. Fails if it now
     * advertises properties that do not give [format].
     */
    private suspend fun find(name: String, format: FrameFormat): BluetoothDevice {
        val bt = adapter ?: throw TxtException(Txt(R.string.bt_missing))
        val found = CompletableDeferred<SeenFrame>()
        val stop = scanner.start(
            onSeen = { if (it.name == name) found.complete(it) },
            onFailed = { found.completeExceptionally(TxtException(it)) },
        )
        val seen = try {
            withTimeoutOrNull(15_000) { found.await() }
        } finally {
            stop()
        }
            ?: throw TxtException(txt(R.string.frame_err_not_found, name, Txt(R.string.scanning_hint)))
        Log.i(TAG, "found $name at ${seen.address}: ${seen.advert}")
        store.seen(name, seen.address, seen.advert, System.currentTimeMillis())
        // its advertisement may have changed (e.g. new firmware): never send a picture rendered for another layout
        if (seen.advert != null) when (val l = seen.lookup) {
            is FormatLookup.Unsupported -> throw TxtException(txt(R.string.frame_toast_unsupported, name, l.reason))
            is FormatLookup.Supported -> if (l.format != format) throw TxtException(txt(R.string.frame_err_format_changed, name))
        }
        return bt.getRemoteDevice(seen.address)
    }

    companion object {
        val SVC_ADV: UUID = UUID.fromString("0000ffbb-0000-1000-8000-00805f9b34fb")
        val FFE1: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb") // app -> device frames
        val FFE2: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb") // device -> app status notify
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val KEY_ENDED = "endedAt" // SystemClock.elapsedRealtime when the last image was handed over

        /**
         * The start frame's payload (docs/device-api.md §5.1): `00 MK ST=00 00 CF=00 01`, with the format's content
         * marker [marker] (§5.4.4) and CF 0: the payload is sent raw, never LZO-compressed.
         */
        fun startPayload(marker: Int): ByteArray = byteArrayOf(0, marker.toByte(), 0, 0, 0, 1)

        /** SOF LEN OP payload CA; SOF=0xAF when total >= 256, LEN = total & 0xFF. */
        fun frame(op: Int, payload: ByteArray): ByteArray {
            val total = payload.size + 4
            return byteArrayOf((if (total >= 256) 0xAF else 0xAE).toByte(), (total and 0xFF).toByte(), op.toByte()) +
                payload + 0xCA.toByte()
        }
    }
}

/** One GATT connection with suspend wrappers; Android allows a single outstanding GATT operation at a time. */
@SuppressLint("MissingPermission")
private class GattSession(private val ctx: Context, private val device: BluetoothDevice) : BluetoothGattCallback() {
    private var gatt: BluetoothGatt? = null
    private lateinit var ffe1: BluetoothGattCharacteristic
    private val connected = CompletableDeferred<Unit>()
    @Volatile private var pending: CompletableDeferred<Int>? = null
    @Volatile private var lost: IOException? = null
    @Volatile private var mtu = 23

    /** The image end frame was (about to be) written: the frame may draw the image after this session. */
    var endSent = false
        private set

    suspend fun open() {
        val g = device.connectGatt(ctx, false, this, BluetoothDevice.TRANSPORT_LE)
            ?: throw TxtException(Txt(R.string.frame_err_gatt))
        gatt = g
        withTimeoutOrNull(15_000) { connected.await() } ?: throw TxtException(Txt(R.string.frame_err_no_answer))
        delay(300)
        op("MTU", 5_000) { g.requestMtu(512) }
        if (mtu <= 23) throw TxtException(Txt(R.string.frame_err_mtu))
        if (op("discoverServices", 10_000) { g.discoverServices() } != BluetoothGatt.GATT_SUCCESS) throw TxtException(Txt(R.string.frame_err_discover))
        val chars = g.services.flatMap { it.characteristics }
        ffe1 = chars.firstOrNull { it.uuid == FrameClient.FFE1 } ?: throw TxtException(Txt(R.string.frame_err_no_ffe1))
        chars.firstOrNull { it.uuid == FrameClient.FFE2 }?.let { ffe2 ->
            g.setCharacteristicNotification(ffe2, true)
            ffe2.getDescriptor(FrameClient.CCCD)?.let { d ->
                val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                op("CCCD", 5_000) {
                    if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
                    else @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) }
                }
            }
        }
        // Android drops to a ~50 ms connection interval after discovery; ask for 7.5-15 ms for the transfer
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        // the frame's WCH CH58x supports LE 2M; without it the Shield manages only ~7 KB/s
        g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
        delay(500)
        Log.i(TAG, "connected, mtu=$mtu")
    }

    /**
     * Start 0x01 (content marker [marker], the format's MK) -> data 0x02 frames carrying [payload] as packed
     * -> end 0x04 (write with response). True if the device acked the end frame.
     *
     * Android reports a write-without-response as done once it is queued, not sent, so on a slow link the
     * queue fills up until the stack fails writes with GATT_CONGESTED and never calls back. Every
     * [ACK_EVERY]-th data frame is therefore a write request: its response arrives only after everything
     * queued before it reached the frame, which bounds the queue. [reliable] makes every frame a request.
     *
     * The end frame is acked within ~25 ms, before the panel draws; [endSent] is set once its write was
     * attempted, acked or not, since the frame may draw the image from then on.
     */
    suspend fun sendImage(marker: Int, payload: ByteArray, reliable: Boolean, progress: (Float) -> Unit): Boolean {
        val chunk = mtu - 3 - 6 // ATT header; SOF LEN OP + 2-byte index + CA
        val n = (payload.size + chunk - 1) / chunk
        write(FrameClient.frame(0x01, FrameClient.startPayload(marker)), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        val t0 = SystemClock.elapsedRealtime()
        for (i in 0 until n) {
            val idx = i + 2
            val part = payload.copyOfRange(i * chunk, minOf(payload.size, (i + 1) * chunk))
            val sent = SystemClock.elapsedRealtime()
            val acked = reliable || (i + 1) % ACK_EVERY == 0
            val type = if (acked) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            write(FrameClient.frame(0x02, part + byteArrayOf((idx shr 8).toByte(), idx.toByte())), type)
            // the device has no flow control; the stock app runs at ~12 ms/frame, so never go much faster than that
            if (!reliable) (PACE_MS - (SystemClock.elapsedRealtime() - sent)).takeIf { it > 0 }?.let { delay(it) }
            if (i % 10 == 0 || i == n - 1) progress((i + 1).toFloat() / n)
        }
        Log.i(TAG, "data: ${payload.size} B in $n frames of $chunk B, ${SystemClock.elapsedRealtime() - t0} ms, reliable=$reliable")
        val end = n + 2
        endSent = true
        val status = write(FrameClient.frame(0x04, byteArrayOf((end shr 8).toByte(), end.toByte())), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, 40_000)
        delay(1_000)
        return status == BluetoothGatt.GATT_SUCCESS
    }

    private suspend fun write(value: ByteArray, type: Int, timeoutMs: Long = 5_000): Int {
        val g = gatt ?: throw TxtException(Txt(R.string.frame_err_not_connected))
        return op("write", timeoutMs) {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ffe1, value, type) == BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { ffe1.writeType = type; ffe1.value = value; g.writeCharacteristic(ffe1) }
        }
    }

    /** Runs one GATT operation and waits for its callback; retries while the stack reports busy. */
    private suspend fun op(what: String, timeoutMs: Long, start: () -> Boolean): Int {
        val d = CompletableDeferred<Int>()
        pending = d
        var tries = 0
        while (!start()) {
            lost?.let { throw it }
            if (++tries > 400) throw TxtException(txt(R.string.frame_err_busy, what))
            delay(5)
        }
        val status = withTimeoutOrNull(timeoutMs) { d.await() }
        pending = null
        return status ?: throw (lost ?: TxtException(txt(R.string.frame_err_timeout, what, timeoutMs.toInt())))
    }

    fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }

    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            connected.complete(Unit)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            val e = TxtException(txt(R.string.frame_err_lost, status))
            lost = e
            connected.completeExceptionally(e)
            pending?.completeExceptionally(e)
        }
    }

    override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
        Log.i(TAG, "PHY tx=$txPhy rx=$rxPhy status=$status")
    }

    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
        pending?.complete(status)
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        pending?.complete(status)
    }

    override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
        pending?.complete(status)
    }

    override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
        pending?.complete(status)
    }

    @Deprecated("API < 33")
    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        @Suppress("DEPRECATION") onStatus(c.value)
    }

    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        onStatus(value)
    }

    private fun onStatus(v: ByteArray?) {
        if (v == null) return
        val hex = v.joinToString(" ") { "%02x".format(it) }
        // 01 <battery %> <lzo flag>
        Log.i(TAG, "FFE2: $hex" + if (v.size >= 3 && v[0].toInt() == 1) " (battery ${v[1]}%, lzo ${v[2]})" else "")
    }

    companion object {
        const val PACE_MS = 12L
        const val ACK_EVERY = 8
    }
}
