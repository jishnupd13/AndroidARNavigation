package com.app.androidnativearcore.wayfinder.core

/**
 * Where the visitor probably is, from the registered beacon they are nearest to: that beacon's floor and position
 * on the floor plan. This is a first, coarse estimate: the visitor is somewhere around the beacon (a few meters),
 * not exactly at it.
 */
data class BeaconFix(
    val beacon: RegisteredBeacon,
    /** The beacon's smoothed signal strength (dBm). */
    val rssi: Int,
    /** A rough distance to the beacon from its signal, if the beacon says how strong it is at 1 m. */
    val roughDistanceMeters: Float?,
    /** When this beacon became the nearest one. */
    val sinceMillis: Long,
) {
    /** True when the visitor is probably right by the beacon (within about [NearestBeacon.CLOSE_METERS]). */
    val isClose: Boolean
        get() = roughDistanceMeters?.let { it <= NearestBeacon.CLOSE_METERS } ?: (rssi >= NearestBeacon.CLOSE_RSSI)

    /** How far off the estimate may be (meters): at least a couple of meters, more when the beacon is far. */
    val uncertaintyMeters: Float
        get() = maxOf(NearestBeacon.MIN_UNCERTAINTY_METERS, roughDistanceMeters ?: NearestBeacon.UNKNOWN_DISTANCE_METERS)
}

/**
 * Picks the registered beacon the visitor is nearest to, from the smoothed signals of the devices heard
 * ([NearbyDevice]), and keeps it from flickering between two beacons.
 *
 * The nearest beacon is simply the one with the strongest signal. But signals fluctuate by several dB, so between
 * two beacons the strongest one changes back and forth. The current beacon is therefore kept until another one is
 * stronger by [HYSTERESIS_DB] for [SWITCH_AFTER_MILLIS] in a row, or until the current one isn't heard any more.
 * Unregistered devices, signals still settling ([NearbyDevice.isStable]) and very weak signals are ignored.
 */
class NearestBeacon(
    private val hysteresisDb: Int = HYSTERESIS_DB,
    private val switchAfterMillis: Long = SWITCH_AFTER_MILLIS,
    private val minRssi: Int = MIN_RSSI,
) {
    var current: BeaconFix? = null
        private set

    /** The beacon trying to take over, and since when it has been clearly stronger. */
    private var challenger: Pair<String, Long>? = null

    /** Updates the estimate from the devices heard now and returns it (null when no registered beacon is heard). */
    fun update(devices: List<NearbyDevice>, registered: List<RegisteredBeacon>, nowMillis: Long): BeaconFix? {
        // Each registered beacon heard, with its smoothed signal.
        val heard = devices
            .filter { it.isStable && it.rssi >= minRssi }
            .mapNotNull { device -> registered.firstOrNull { it.matches(device) }?.let { it to device } }
            .distinctBy { it.first.beaconId }
        val strongest = heard.maxByOrNull { it.second.rssi }
        val now = current
        val currentHeard = now?.let { fix -> heard.firstOrNull { it.first.beaconId == fix.beacon.beaconId } }

        current = when {
            strongest == null -> null.also { challenger = null }
            // Nothing yet, or the current beacon went quiet: take the strongest straight away.
            now == null || currentHeard == null -> {
                challenger = null
                fixOf(strongest.first, strongest.second, nowMillis)
            }
            strongest.first.beaconId != now.beacon.beaconId && strongest.second.rssi >= currentHeard.second.rssi + hysteresisDb -> {
                val since = challenger?.takeIf { it.first == strongest.first.beaconId }?.second ?: nowMillis
                challenger = strongest.first.beaconId to since
                if (nowMillis - since >= switchAfterMillis) {
                    challenger = null
                    fixOf(strongest.first, strongest.second, nowMillis)
                } else {
                    now.copy(rssi = currentHeard.second.rssi, roughDistanceMeters = currentHeard.second.roughDistanceMeters)
                }
            }
            else -> {
                challenger = null
                now.copy(rssi = currentHeard.second.rssi, roughDistanceMeters = currentHeard.second.roughDistanceMeters)
            }
        }
        return current
    }

    fun reset() {
        current = null
        challenger = null
    }

    private fun fixOf(beacon: RegisteredBeacon, device: NearbyDevice, nowMillis: Long) =
        BeaconFix(beacon, device.rssi, device.roughDistanceMeters, nowMillis)

    companion object {
        /** Another beacon must be this much stronger (dB) than the current one to take over… */
        const val HYSTERESIS_DB = 6

        /** …for this long (ms) in a row. */
        const val SWITCH_AFTER_MILLIS = 2_000L

        /** Signals weaker than this (dBm) are too far or too unreliable to use. */
        const val MIN_RSSI = -95

        /** Within this many meters the visitor counts as right by the beacon. */
        const val CLOSE_METERS = 2f

        /** Without a distance estimate, a signal this strong (dBm) or stronger counts as right by the beacon. */
        const val CLOSE_RSSI = -65

        const val MIN_UNCERTAINTY_METERS = 2f
        const val UNKNOWN_DISTANCE_METERS = 5f
    }
}

/**
 * Where a route starts when the visitor was found by Bluetooth: at the nearest beacon's position on the floor plan,
 * joined to the recorded walks within [BEACON_JOIN_M]. Beacons hang on walls and ceilings beside the walkways, and
 * the visitor is only known to be around the beacon, so the join is more generous than for a scanned reference point.
 * The walkable network itself still comes from the walks the admin recorded: beacons don't add any paths.
 */
fun BeaconFix.routeStart(): RouteEndpoint = beacon.routeEndpoint()

/** The beacon as either end of a route: its spot on the floor plan, joined to the walks within [BEACON_JOIN_M]. */
fun RegisteredBeacon.routeEndpoint(): RouteEndpoint = RouteEndpoint(name, position, floorId, BEACON_JOIN_M)

/** The beacon's spot in venue coordinates (its floor-plan y is the venue's z). */
val RegisteredBeacon.position: Vec3 get() = Vec3(x, 0f, y)

/** How far (meters) a beacon may be from the recorded walks for a route to start or end at it. */
const val BEACON_JOIN_M = 4f
