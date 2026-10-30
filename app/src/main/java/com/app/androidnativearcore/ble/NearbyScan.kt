package com.app.androidnativearcore.ble

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.app.androidnativearcore.wayfinder.core.NearbyDevice
import com.app.androidnativearcore.wayfinder.core.NearbyDevices

/**
 * A scan for screens to show: [BleScanner] feeding [NearbyDevices], with what the screen needs as Compose state.
 * The list is refreshed by [refresh] (called on a timer while scanning), not on every advertisement, so the screen
 * redraws a couple of times a second instead of dozens.
 */
class NearbyScan(private val scanner: BleScanner) {
    private val nearby = NearbyDevices()

    var devices by mutableStateOf<List<NearbyDevice>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set

    /** What stopped the last attempt to scan, or null. */
    var problem by mutableStateOf<BleScanner.Problem?>(null)
        private set

    /** A message from the system when it stopped the scan, or null. */
    var error by mutableStateOf<String?>(null)
        private set

    fun start() {
        error = null
        problem = scanner.start(onReading = nearby::add, onError = { error = it; scanning = false })
        scanning = problem == null
    }

    fun stop() {
        scanner.stop()
        scanning = false
    }

    /** Re-reads the smoothed list of devices heard recently. */
    fun refresh() {
        devices = nearby.current(SystemClock.elapsedRealtime())
    }

    /** Re-checks what scanning needs (after the user granted a permission or turned Bluetooth on). */
    fun recheck() {
        problem = scanner.problem()
    }
}
