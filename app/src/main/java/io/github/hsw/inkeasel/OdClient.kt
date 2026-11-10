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
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

private const val TAG = "OdClient"

/**
 * BLE client for OpenDisplay frames ([FrameKind.OPENDISPLAY], docs/protocol.md#opendisplay): reads the
 * panel a frame reports ([interrogate], when it is chosen) and sends it a picture ([upload]). Without
 * encryption only: a frame with encryption on answers "auth required" and is refused.
 *
 * Such a frame sleeps most of the time and wakes about once a minute to advertise; a connection must start
 * right after an advertisement, so both scan for the frame's name (up to [FIND_MS]) and connect at once.
 */
@SuppressLint("MissingPermission") // permissions are requested by MainActivity before any upload or scan
class OdClient(private val ctx: Context) {
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val store = FrameStore(ctx)
    private val scanner = FrameScanner(ctx)

    /**
     * Waits for the frame [name] to advertise, connects at once and reads the display it reports. Fails if it
     * is not heard, reports none, or has encryption on.
     */
    suspend fun interrogate(name: String): OdDisplay {
        usable()
        val s = OdSession(ctx, find(name))
        try {
            s.open()
            return s.exchange.display(name)
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
            val display = s.exchange.display(sel.name)
            store.seen(sel.name, dev.address, null, System.currentTimeMillis(), display)
            // the frame's config may have changed since it was chosen: never send a picture in another layout
            when (val l = OdDisplay.lookup(display)) {
                is FormatLookup.Unsupported -> throw TxtException(Txt(R.string.frame_err_unsupported, sel.name, l.reason))
                is FormatLookup.Supported -> if (l.format != format) throw TxtException(Txt(R.string.frame_err_format_changed, sel.name))
            }
            val zlib = withContext(Dispatchers.Default) { Od.zlibFor(display, payload) }
            s.exchange.write(payload, zlib)
        } finally {
            s.close()
        }
    }

    private fun usable() = (adapter ?: throw TxtException(Txt(R.string.bt_missing))).also {
        if (!it.isEnabled) throw TxtException(Txt(R.string.bt_off))
    }

    /** Scans until the frame [name] advertises (it wakes about once a minute), then stops at once. */
    private suspend fun find(name: String): BluetoothDevice {
        val seen = scanner.awaitFrame(name, FrameKind.OPENDISPLAY, FIND_MS)
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
 * queued in [replies] for [exchange]). Android allows a single outstanding GATT operation at a time.
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

    /** The request and answer sequences over this connection; after [open]. */
    val exchange = OdExchange(replies, { lost }, { Log.i(TAG, it) }) { value, data ->
        send(value, if (data && noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    suspend fun open() {
        val g = device.connectGatt(ctx, false, this, BluetoothDevice.TRANSPORT_LE)
            ?: throw TxtException(Txt(R.string.frame_err_gatt))
        gatt = g
        withTimeoutOrNull(CONNECT_MS) { connected.await() } ?: throw TxtException(Txt(R.string.frame_err_no_answer))
        op(OdStep.MTU, 5_000) { g.requestMtu(MTU) }
        if (mtu < Od.CHUNK + 2 + 3) throw TxtException(Txt(R.string.frame_err_mtu))
        if (op(OdStep.SERVICES, 10_000) { g.discoverServices() } != BluetoothGatt.GATT_SUCCESS) {
            throw TxtException(Txt(R.string.frame_err_discover))
        }
        char = g.getService(Od.SERVICE)?.characteristics?.firstOrNull() ?: throw TxtException(Txt(R.string.frame_err_no_od_service))
        noResponse = char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        g.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(FrameClient.CCCD) ?: throw TxtException(Txt(R.string.frame_err_no_od_service))
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op(OdStep.NOTIFY, 5_000) {
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, v) == BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { cccd.value = v; g.writeDescriptor(cccd) }
        }
        // every data chunk waits for the frame's answer: a short connection interval is most of the speed
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        Log.i(TAG, "connected, mtu=$mtu, write without response=$noResponse")
    }

    private suspend fun send(value: ByteArray, type: Int) {
        val g = gatt ?: throw TxtException(Txt(R.string.frame_err_not_connected))
        op(OdStep.WRITE, 5_000) {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(char, value, type) == BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { char.writeType = type; char.value = value; g.writeCharacteristic(char) }
        }
    }

    /**
     * Runs one GATT operation, named [step] in its errors, and waits for its callback; retries while the stack
     * reports busy.
     */
    private suspend fun op(step: OdStep, timeoutMs: Long, start: () -> Boolean): Int {
        val d = CompletableDeferred<Int>()
        pending = d
        var tries = 0
        while (!start()) {
            lost?.let { throw it }
            if (++tries > 400) throw TxtException(Txt(R.string.frame_err_busy, step.txt))
            delay(5)
        }
        val status = withTimeoutOrNull(timeoutMs) { d.await() }
        pending = null
        return status ?: throw (lost ?: TxtException(Txt(R.string.frame_err_timeout, step.txt, timeoutMs.toInt())))
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

        /**
         * How long the connection may take; it starts right after the frame advertised, and the frame stays
         * awake only about 40 s after that (`sleep_timeout_ms`, docs/protocol.md#opendisplay-advertising), so a
         * connection not made by then will not be made at all.
         */
        const val CONNECT_MS = 35_000L
    }
}
