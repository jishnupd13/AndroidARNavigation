package com.app.androidnativearcore.ble

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.app.androidnativearcore.wayfinder.core.BeaconFix
import com.app.androidnativearcore.wayfinder.core.BeaconReading
import com.app.androidnativearcore.wayfinder.core.BeaconPosition
import com.app.androidnativearcore.wayfinder.core.BeaconTracker
import com.app.androidnativearcore.wayfinder.core.HeardBeacon
import com.app.androidnativearcore.wayfinder.core.NearestBeacon
import com.app.androidnativearcore.wayfinder.core.heardBeacons
import com.app.androidnativearcore.wayfinder.core.RegisteredBeacon

/**
 * Finds the visitor by Bluetooth beacon: scans ([NearbyScan]) and keeps the registered beacon they are nearest to
 * ([NearestBeacon]) and their position between the beacons heard ([BeaconTracker]) as Compose state ([fix],
 * [position]) for the screens. Call [update] regularly while it runs.
 */
class BeaconLocator(val scanner: BleScanner) {
    private val readingListeners = mutableListOf<(BeaconReading) -> Unit>()
    private val scan = NearbyScan(scanner) { reading -> readingListeners.toList().forEach { it(reading) } }

    /**
     * Calls [listener] with every advertisement heard while scanning (on the main thread, unsmoothed), until the
     * returned function is called.
     */
    fun listenToReadings(listener: (BeaconReading) -> Unit): () -> Unit {
        readingListeners += listener
        return { readingListeners -= listener }
    }
    private val nearest = NearestBeacon()
    private val tracker = BeaconTracker()

    /** The nearest registered beacon, or null while none is heard. */
    var fix by mutableStateOf<BeaconFix?>(null)
        private set

    /** Where the visitor probably is between the beacons heard, or null while none is heard. */
    var position by mutableStateOf<BeaconPosition?>(null)
        private set

    /** Every registered beacon heard now, strongest first. */
    var heard by mutableStateOf<List<HeardBeacon>>(emptyList())
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
        tracker.reset()
        fix = null
        position = null
        heard = emptyList()
    }

    fun recheck() = scan.recheck()

    /** Re-reads the devices heard and updates [fix] against the [registered] beacons. */
    fun update(registered: List<RegisteredBeacon>) {
        scan.refresh()
        val before = fix?.beacon?.beaconId
        val millis = SystemClock.elapsedRealtime()
        val now = nearest.update(scan.devices, registered, millis)
        if (now?.beacon?.beaconId != before) {
            Log.i(TAG, now?.let { "Nearest beacon: ${it.beacon.name} (${it.beacon.beaconId}) ${it.rssi} dBm" } ?: "No registered beacon heard")
        }
        fix = now
        position = tracker.update(scan.devices, registered, now, millis)
        heard = heardBeacons(scan.devices, registered)
    }

    private companion object {
        const val TAG = "WFBeacon"
    }
}
