package com.app.androidnativearcore.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.app.androidnativearcore.wayfinder.core.BeaconAdvertisement
import com.app.androidnativearcore.wayfinder.core.BeaconReading

/**
 * Bluetooth Low Energy scanning, kept apart from any screen: it checks what scanning needs, starts and stops the
 * system scanner, and turns each advertisement into a [BeaconReading] (iBeacon, Eddystone-UID or plain device).
 *
 * What scanning needs depends on the Android version:
 * - Android 12 and later: the "Nearby devices" permission (BLUETOOTH_SCAN; BLUETOOTH_CONNECT to ask for Bluetooth to
 *   be turned on) and location permission, because beacon readings are used to work out where the user is.
 * - Android 11 and earlier: location permission, and location services switched on, or the system hides all results.
 */
class BleScanner(private val context: Context) {
    /** Why scanning can't start. */
    enum class Problem { NO_BLUETOOTH_LE, PERMISSION_MISSING, BLUETOOTH_OFF, LOCATION_OFF }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var callback: ScanCallback? = null

    val isScanning: Boolean get() = callback != null

    /** The runtime permissions to ask for on this Android version. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                // Android 12+ needs fine and coarse location asked for together.
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** What stops scanning right now, or null if it can start. */
    fun problem(): Problem? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) || manager?.adapter == null) {
            return Problem.NO_BLUETOOTH_LE
        }
        if (!hasPermissions()) return Problem.PERMISSION_MISSING
        if (manager.adapter?.isEnabled != true) return Problem.BLUETOOTH_OFF
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val location = context.getSystemService(LocationManager::class.java)
            val on = location?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                location?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            if (!on) return Problem.LOCATION_OFF
        }
        return null
    }

    /**
     * Starts scanning; each advertisement heard goes to [onReading] (on the main thread), and a scan the system stops
     * goes to [onError]. Returns the [Problem] that prevented it, or null when it started. Times are
     * [SystemClock.elapsedRealtime] milliseconds.
     */
    @SuppressLint("MissingPermission") // checked in problem()
    fun start(onReading: (BeaconReading) -> Unit, onError: (String) -> Unit): Problem? {
        problem()?.let { return it }
        if (callback != null) return null
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return Problem.BLUETOOTH_OFF
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onReading(result.toReading())
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onReading(it.toReading()) }
            override fun onScanFailed(errorCode: Int) {
                callback = null
                onError(
                    when (errorCode) {
                        SCAN_FAILED_APPLICATION_REGISTRATION_FAILED, 6 /* SCAN_FAILED_SCANNING_TOO_FREQUENTLY */ ->
                            "Scanning was started too often. Wait half a minute and try again."
                        SCAN_FAILED_FEATURE_UNSUPPORTED -> "This phone can't scan for Bluetooth beacons."
                        else -> "Bluetooth scanning stopped (error $errorCode). Try again."
                    },
                )
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        // No filters: the admin wants to see every device nearby; the screens pick out the beacons.
        scanner.startScan(null, settings, cb)
        callback = cb
        return null
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(cb) }
    }

    private fun ScanResult.toReading(): BeaconReading {
        val record = scanRecord
        val identity = BeaconAdvertisement.identify(
            appleData = record?.getManufacturerSpecificData(BeaconAdvertisement.APPLE_COMPANY_ID),
            eddystoneData = record?.getServiceData(EDDYSTONE),
            address = device.address,
        )
        return BeaconReading(identity, device.address.uppercase(), record?.deviceName, rssi, SystemClock.elapsedRealtime())
    }

    private companion object {
        val EDDYSTONE: ParcelUuid = ParcelUuid.fromString(BeaconAdvertisement.EDDYSTONE_SERVICE_UUID)
    }
}
