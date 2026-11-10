package io.github.hsw.inkeasel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Why Bluetooth cannot be used: as a [reason] inside a longer text (lowercase where the language allows), or
 * as a [sentence] of its own.
 */
enum class BtProblem(private val reasonId: Int, private val sentenceId: Int) {
    MISSING(R.string.bt_missing, R.string.bt_missing_sentence),
    OFF(R.string.bt_off, R.string.bt_off_sentence);

    val reason: Txt get() = Txt(reasonId)
    val sentence: Txt get() = Txt(sentenceId)
}

/**
 * BLE scan for frames: NowMean ([FrameKind.NOWMEAN]: service 0xFFBB) and OpenDisplay ([FrameKind.OPENDISPLAY]:
 * manufacturer data 0x2446), shared by the settings list (continuous, every frame), [FrameClient] and
 * [OdClient] (until the selected frame shows up, [awaitFrame]). Every advertisement is reported, so signal and
 * battery stay current; nameless ones are skipped, since the name is the frame's identity.
 */
@SuppressLint("MissingPermission") // callers check the permissions first (MainActivity)
class FrameScanner(ctx: Context) {
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    /** Why a scan cannot run now (no Bluetooth, or it is off), or `null`. */
    fun unavailable(): BtProblem? = when {
        adapter == null -> BtProblem.MISSING
        !adapter.isEnabled -> BtProblem.OFF
        else -> null
    }

    /**
     * Start scanning; [onSeen] gets each frame heard, [onFailed] a scan error (callbacks on the main thread).
     * Returns the function that stops the scan; call it exactly when done.
     */
    fun start(onSeen: (SeenFrame) -> Unit, onFailed: (Txt) -> Unit): () -> Unit {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            onFailed((unavailable() ?: BtProblem.OFF).reason)
            return {}
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                seen(result)?.let(onSeen)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r -> seen(r)?.let(onSeen) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
                onFailed(Txt(R.string.scan_err_code, errorCode))
            }
        }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(FrameClient.SVC_ADV)).build(),
            ScanFilter.Builder().setManufacturerData(Od.COMPANY_ID, ByteArray(0)).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, cb)
        Log.i(TAG, "scan started")
        return {
            try {
                scanner.stopScan(cb)
                Log.i(TAG, "scan stopped")
            } catch (e: IllegalStateException) { // Bluetooth was turned off meanwhile: the scan is gone anyway
                Log.i(TAG, "scan stop: ${e.message}")
            }
        }
    }

    /**
     * Scans until the frame [name] of [kind] advertises, then stops at once and returns what it advertised;
     * fails on a scan error, or after [timeoutMs] with a hint to the scan permissions.
     */
    suspend fun awaitFrame(name: String, kind: FrameKind, timeoutMs: Long): SeenFrame {
        val found = CompletableDeferred<SeenFrame>()
        val stop = start(
            onSeen = { if (it.name == name && it.kind == kind) found.complete(it) },
            onFailed = { found.completeExceptionally(TxtException(it)) },
        )
        val seen = try {
            withTimeoutOrNull(timeoutMs) { found.await() }
        } finally {
            stop()
        }
        return seen ?: throw TxtException(Txt(R.string.frame_err_not_found, name, BlePermissions.scanHint))
    }

    private fun seen(r: ScanResult): SeenFrame? {
        val name = (r.scanRecord?.deviceName ?: r.device.name)?.takeIf { it.isNotBlank() } ?: return null
        val od = r.scanRecord?.getManufacturerSpecificData(Od.COMPANY_ID)
        if (od != null) return SeenFrame(name, r.device.address, r.rssi, null, FrameKind.OPENDISPLAY, OdAdvert.parse(od))
        val advert = FrameAdvert.parse(r.scanRecord?.getManufacturerSpecificData(FrameAdvert.COMPANY_ID))
        return SeenFrame(name, r.device.address, r.rssi, advert, FrameKind.NOWMEAN)
    }

    private companion object {
        const val TAG = "FrameScanner"
    }
}
