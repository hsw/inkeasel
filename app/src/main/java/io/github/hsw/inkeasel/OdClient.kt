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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

private const val TAG = "OdClient"

/**
 * BLE client for OpenDisplay frames ([FrameKind.OPENDISPLAY], docs/protocol.md#opendisplay): reads the
 * panel a frame reports ([interrogate], when it is chosen) and sends it a picture ([upload]). Without
 * encryption only: a frame with encryption on answers "auth required" and is refused.
 *
 * Such a frame sleeps most of the time and wakes about once a minute to advertise; a connection must start
 * right after an advertisement, so an upload scans for the frame's name and connects at once.
 */
@SuppressLint("MissingPermission") // permissions are requested by MainActivity before any upload or scan
class OdClient(private val ctx: Context) {
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val store = FrameStore(ctx)
    private val scanner = FrameScanner(ctx)

    /**
     * Connects to the frame [name] at [address] (Android waits for its next advertisement) and reads the
     * display it reports. Fails if it reports none, or has encryption on.
     */
    suspend fun interrogate(name: String, address: String): OdDisplay {
        val bt = usable()
        val dev = try {
            bt.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            find(name)
        }
        val s = OdSession(ctx, dev)
        try {
            s.open()
            return s.display(name)
        } finally {
            s.close()
        }
    }

    /**
     * Sends [payload], rendered for [format], to the selected OpenDisplay frame [sel]: find it by name, connect,
     * check that it still reports the display [format] was made for, write the picture and wait until the
     * frame says its panel is refreshed. One attempt; a failure is reported to the caller.
     */
    suspend fun upload(sel: SelectedFrame, format: FrameFormat, payload: ByteArray) {
        require(payload.size == format.payloadSize) { "payload ${payload.size} B, the format needs ${format.payloadSize} B" }
        usable()
        Log.i(TAG, "searching for ${sel.name}")
        val dev = find(sel.name)
        val s = OdSession(ctx, dev)
        try {
            s.open()
            val display = s.display(sel.name)
            store.seen(sel.name, dev.address, null, System.currentTimeMillis(), display)
            // the frame's config may have changed since it was chosen: never send a picture in another layout
            when (val l = OdDisplay.lookup(display)) {
                is FormatLookup.Unsupported -> throw TxtException(Txt(R.string.frame_err_unsupported, sel.name, l.reason))
                is FormatLookup.Supported -> if (l.format != format) throw TxtException(Txt(R.string.frame_err_format_changed, sel.name))
            }
            s.write(payload)
        } finally {
            s.close()
        }
    }

    private fun usable() = (adapter ?: throw TxtException(Txt(R.string.bt_missing))).also {
        if (!it.isEnabled) throw TxtException(Txt(R.string.bt_off))
    }

    /** Scans until the frame [name] advertises (it wakes about once a minute), then stops at once. */
    private suspend fun find(name: String): BluetoothDevice {
        val found = CompletableDeferred<SeenFrame>()
        val stop = scanner.start(
            onSeen = { if (it.name == name && it.kind == FrameKind.OPENDISPLAY) found.complete(it) },
            onFailed = { found.completeExceptionally(TxtException(it)) },
        )
        val seen = try {
            withTimeoutOrNull(FIND_MS) { found.await() }
        } finally {
            stop()
        }
            ?: throw TxtException(Txt(R.string.frame_err_not_found, name, BlePermissions.scanHint))
        Log.i(TAG, "found $name at ${seen.address}: ${seen.od}")
        return usable().getRemoteDevice(seen.address)
    }

    private companion object {
        /** Longer than the frame's wake period (about a minute without deep sleep). */
        const val FIND_MS = 75_000L
    }
}

/**
 * One GATT connection to an OpenDisplay frame: one characteristic for requests and answers (notifications,
 * queued in [replies]). Android allows a single outstanding GATT operation at a time.
 */
@SuppressLint("MissingPermission")
private class OdSession(private val ctx: Context, private val device: BluetoothDevice) : BluetoothGattCallback() {
    private var gatt: BluetoothGatt? = null
    private lateinit var char: BluetoothGattCharacteristic
    private var noResponse = false
    private val connected = CompletableDeferred<Unit>()
    private val replies = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var pending: CompletableDeferred<Int>? = null
    @Volatile private var lost: IOException? = null
    @Volatile private var mtu = 23

    suspend fun open() {
        val g = device.connectGatt(ctx, false, this, BluetoothDevice.TRANSPORT_LE)
            ?: throw TxtException(Txt(R.string.frame_err_gatt))
        gatt = g
        withTimeoutOrNull(35_000) { connected.await() } ?: throw TxtException(Txt(R.string.frame_err_no_answer))
        op("MTU", 5_000) { g.requestMtu(MTU) }
        if (mtu < Od.CHUNK + 2 + 3) throw TxtException(Txt(R.string.frame_err_mtu))
        if (op("discoverServices", 10_000) { g.discoverServices() } != BluetoothGatt.GATT_SUCCESS) {
            throw TxtException(Txt(R.string.frame_err_discover))
        }
        char = g.getService(Od.SERVICE)?.characteristics?.firstOrNull() ?: throw TxtException(Txt(R.string.od_err_no_service))
        noResponse = char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        g.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(FrameClient.CCCD) ?: throw TxtException(Txt(R.string.od_err_no_service))
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op("CCCD", 5_000) {
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, v) == BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { cccd.value = v; g.writeDescriptor(cccd) }
        }
        // every data chunk waits for the frame's answer: a short connection interval is most of the speed
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        Log.i(TAG, "connected, mtu=$mtu, write without response=$noResponse")
    }

    /** Reads the frame's config and returns its display record; the frame [name] is for messages. */
    suspend fun display(name: String): OdDisplay {
        val reader = Od.ConfigReader()
        request(Od.request(Od.READ_CONFIG))
        var config: ByteArray? = null
        var timeout = 10_000L
        while (config == null) {
            val r = reply(Od.READ_CONFIG, "config", timeout)
            if (!r.ok) throw TxtException(Txt(R.string.od_err_refused, "config", r.status))
            config = try {
                reader.add(r.data)
            } catch (e: IllegalArgumentException) {
                throw TxtException(Txt(R.string.od_err_config), e)
            }
            timeout = 3_000
        }
        val d = Od.display(config) ?: throw TxtException(Txt(R.string.od_err_config))
        Log.i(TAG, "$name: ${config.size} B of config, $d")
        return d
    }

    /**
     * Writes [payload] as an uncompressed full-frame direct write and waits for the refresh: start, data
     * chunks (each answered; the last one is answered with the end, as the frame finishes by itself once it
     * has every byte), end if it did not, then the refresh notice.
     */
    suspend fun write(payload: ByteArray) {
        request(Od.start())
        reply(Od.WRITE_START, "start", 10_000).also { if (!it.ok) throw TxtException(Txt(R.string.od_err_refused, "start", it.status)) }
        val t0 = SystemClock.elapsedRealtime()
        var at = 0
        var ended = false
        while (at < payload.size) {
            val chunk = payload.copyOfRange(at, minOf(payload.size, at + Od.CHUNK))
            send(Od.data(chunk), if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            at += chunk.size
            val r = reply(null, "data", 30_000)
            if (!r.ok) throw TxtException(Txt(R.string.od_err_refused, "data", r.status))
            if (r.op == Od.WRITE_END) {
                ended = true
                break
            }
            if (r.op != Od.WRITE_DATA) throw TxtException(Txt(R.string.od_err_refused, "data", r.op))
        }
        Log.i(TAG, "data: ${payload.size} B in ${SystemClock.elapsedRealtime() - t0} ms, ended by the frame: $ended")
        if (at < payload.size) throw TxtException(Txt(R.string.od_err_refused, "data", 0))
        if (!ended) {
            request(Od.end())
            reply(Od.WRITE_END, "end", 90_000).also { if (!it.ok) throw TxtException(Txt(R.string.od_err_refused, "end", it.status)) }
        }
        val r = reply(null, "refresh", 90_000)
        Log.i(TAG, "refresh: $r after ${SystemClock.elapsedRealtime() - t0} ms")
        when (r.op) {
            Od.REFRESH_DONE -> return
            Od.REFRESH_TIMEOUT -> throw TxtException(Txt(R.string.od_err_refresh))
            else -> throw TxtException(Txt(R.string.od_err_refused, "refresh", r.op))
        }
    }

    /** Writes a request with response, after dropping any stale answer. */
    private suspend fun request(value: ByteArray) {
        while (replies.tryReceive().isSuccess) Unit
        send(value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    /**
     * The next answer, for opcode [op] (others are skipped; `null`: any); fails on "auth required" (encryption
     * is on) and after [timeoutMs].
     */
    private suspend fun reply(op: Int?, what: String, timeoutMs: Long): Od.Reply {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime()
            val raw = (if (left > 0) withTimeoutOrNull(left) { replies.receive() } else null)
                ?: throw (lost ?: TxtException(Txt(R.string.frame_err_timeout, what, timeoutMs.toInt())))
            val r = Od.Reply.parse(raw) ?: continue
            if (r.authRequired) throw TxtException(Txt(R.string.od_err_auth))
            if (op == null || r.op == (op and 0xFF)) return r
            Log.i(TAG, "$what: skipping $r")
        }
    }

    private suspend fun send(value: ByteArray, type: Int) {
        val g = gatt ?: throw TxtException(Txt(R.string.frame_err_not_connected))
        op("write", 5_000) {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(char, value, type) == BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { char.writeType = type; char.value = value; g.writeCharacteristic(char) }
        }
    }

    /** Runs one GATT operation and waits for its callback; retries while the stack reports busy. */
    private suspend fun op(what: String, timeoutMs: Long, start: () -> Boolean): Int {
        val d = CompletableDeferred<Int>()
        pending = d
        var tries = 0
        while (!start()) {
            lost?.let { throw it }
            if (++tries > 400) throw TxtException(Txt(R.string.frame_err_busy, what))
            delay(5)
        }
        val status = withTimeoutOrNull(timeoutMs) { d.await() }
        pending = null
        return status ?: throw (lost ?: TxtException(Txt(R.string.frame_err_timeout, what, timeoutMs.toInt())))
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
            val e = TxtException(Txt(R.string.frame_err_lost, status))
            lost = e
            connected.completeExceptionally(e)
            pending?.completeExceptionally(e)
            replies.close(e)
        }
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
        @Suppress("DEPRECATION") c.value?.let { replies.trySend(it.copyOf()) }
    }

    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        replies.trySend(value)
    }

    private companion object {
        /** The protocol's BLE frame ceiling (OD_BLE_MAX_FRAME 256 counts ATT opcode and handle too). */
        const val MTU = 247
    }
}
