package com.app.androidnativearcore.ble

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.app.androidnativearcore.wayfinder.core.BeaconFix
import com.app.androidnativearcore.wayfinder.core.NearestBeacon
import com.app.androidnativearcore.wayfinder.core.RegisteredBeacon

/**
 * Finds the visitor by Bluetooth beacon: scans ([NearbyScan]) and keeps the registered beacon they are nearest to
 * ([NearestBeacon]) as Compose state ([fix]) for the screens. Call [update] regularly while it runs.
 */
class BeaconLocator(val scanner: BleScanner) {
    private val scan = NearbyScan(scanner)
    private val nearest = NearestBeacon()

    /** The nearest registered beacon, or null while none is heard. */
    var fix by mutableStateOf<BeaconFix?>(null)
        private set

    val scanning: Boolean get() = scan.scanning

    /** What stops scanning (missing permission, Bluetooth off…), or null. */
    val problem: BleScanner.Problem? get() = scan.problem

    fun start() {
        if (!scan.scanning) scan.start()
    }

    fun stop() {
        scan.stop()
        nearest.reset()
        fix = null
    }

    fun recheck() = scan.recheck()

    /** Re-reads the devices heard and updates [fix] against the [registered] beacons. */
    fun update(registered: List<RegisteredBeacon>) {
        scan.refresh()
        val before = fix?.beacon?.beaconId
        val now = nearest.update(scan.devices, registered, SystemClock.elapsedRealtime())
        if (now?.beacon?.beaconId != before) {
            Log.i(TAG, now?.let { "Nearest beacon: ${it.beacon.name} (${it.beacon.beaconId}) ${it.rssi} dBm" } ?: "No registered beacon heard")
        }
        fix = now
    }

    private companion object {
        const val TAG = "WFBeacon"
    }
}
