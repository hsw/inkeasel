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

/**
 * BLE scan for NowMean frames ([FrameKind.NOWMEAN]: service 0xFFBB), shared by the settings list (continuous, every frame) and
 * [FrameClient] (until the selected frame shows up). Every advertisement is reported, so signal and battery
 * stay current; nameless ones are skipped, since the name is the frame's identity.
 */
@SuppressLint("MissingPermission") // callers check the permissions first (MainActivity)
class FrameScanner(ctx: Context) {
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    /** Why a scan cannot run now (no Bluetooth, or it is off), or `null`. */
    fun unavailable(): Txt? = when {
        adapter == null -> Txt(R.string.bt_missing)
        !adapter.isEnabled -> Txt(R.string.bt_off)
        else -> null
    }

    /**
     * Start scanning; [onSeen] gets each frame heard, [onFailed] a scan error (callbacks on the main thread).
     * Returns the function that stops the scan; call it exactly when done.
     */
    fun start(onSeen: (SeenFrame) -> Unit, onFailed: (Txt) -> Unit): () -> Unit {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            onFailed(unavailable() ?: Txt(R.string.bt_off))
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
                onFailed(txt(R.string.scan_error, errorCode))
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(FrameClient.SVC_ADV)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, cb)
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

    private fun seen(r: ScanResult): SeenFrame? {
        val name = (r.scanRecord?.deviceName ?: r.device.name)?.takeIf { it.isNotBlank() } ?: return null
        val advert = FrameAdvert.parse(r.scanRecord?.getManufacturerSpecificData(FrameAdvert.COMPANY_ID))
        return SeenFrame(name, r.device.address, r.rssi, advert, FrameKind.NOWMEAN)
    }

    private companion object {
        const val TAG = "FrameScanner"
    }
}
