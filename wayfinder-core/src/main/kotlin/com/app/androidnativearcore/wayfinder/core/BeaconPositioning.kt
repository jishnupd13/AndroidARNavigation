package com.app.androidnativearcore.wayfinder.core

import kotlin.math.exp

/**
 * Where the visitor probably is, from the registered beacon they are nearest to: that beacon's floor and position
 * on the floor plan. This is a first, coarse estimate: the visitor is somewhere around the beacon (a few meters),
 * not exactly at it.
 */
data class BeaconFix(
    val beacon: RegisteredBeacon,
    /** The beacon's smoothed signal strength (dBm). */
    val rssi: Int,
    /** A rough distance to the beacon from its signal, if its strength at 1 m is known (calibrated or advertised). */
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

    /** True when the admin calibrated the beacon: then [roughDistanceMeters] really is a distance. */
    val isCalibrated: Boolean get() = beacon.rssiAt1m != null

    /**
     * True when the visitor is at this beacon (it is the nearest one). A calibrated beacon says so by its distance;
     * for one that isn't, a distance from its signal can be off two or three times, so a strong signal decides.
     */
    val isAtBeacon: Boolean get() = atBeacon(rssi, roughDistanceMeters.takeIf { isCalibrated })

    /** How near the beacon is, in words: in meters only when it was calibrated, else by its signal. */
    val howNear: String
        get() = when {
            isCalibrated -> roughDistanceMeters?.let { if (it <= NearestBeacon.RIGHT_BY_METERS) "right by it" else "about %.0f m away".format(it) }
                ?: "near it"
            rssi >= NearestBeacon.RIGHT_BY_RSSI -> "right by it"
            rssi >= NearestBeacon.AT_BEACON_RSSI -> "strong signal"
            else -> "weak signal"
        }
}

/** A registered beacon heard now, with the device's smoothed signal. */
data class HeardBeacon(val beacon: RegisteredBeacon, val device: NearbyDevice) {
    val rssi: Int get() = device.rssi

    /**
     * The beacon's signal 1 m away: as calibrated by the admin, else as the beacon advertises it if that is plausible
     * (beacon apps sometimes advertise nonsense, like −91 dBm), else unknown.
     */
    val strengthAt1m: Int?
        get() = beacon.rssiAt1m ?: device.identity.txPowerAt1m?.takeIf { it in PLAUSIBLE_STRENGTH_AT_1M }

    /** A rough distance (meters) from the signal and [strengthAt1m], or null when that is unknown. */
    val roughDistanceMeters: Float? get() = strengthAt1m?.let(device::roughDistanceFor)

    /**
     * How near the beacon sounds for its own strength (dB above its signal at 1 m; −10 is about 2.5 m away). Beacons
     * that differ in strength compare fairly by this, not by their raw signals.
     */
    val nearness: Int get() = rssi - (strengthAt1m ?: DEFAULT_STRENGTH_AT_1M)

    /** True when the signal says the visitor is at this beacon (as [BeaconFix.isAtBeacon]). */
    val isAtBeacon: Boolean get() = atBeacon(rssi, roughDistanceMeters.takeIf { beacon.rssiAt1m != null })
}

/**
 * At a beacon: within [NearestBeacon.AT_BEACON_METERS] of it when it was calibrated ([calibratedMeters]); otherwise a
 * distance from its signal can be off two or three times, so a strong signal decides.
 */
internal fun atBeacon(rssi: Int, calibratedMeters: Float?): Boolean =
    calibratedMeters?.let { it <= NearestBeacon.AT_BEACON_METERS } ?: (rssi >= NearestBeacon.AT_BEACON_RSSI)

/** What a beacon's signal 1 m away usually is (dBm), for beacons that neither were calibrated nor advertise it. */
const val DEFAULT_STRENGTH_AT_1M = -59

/** Advertised strengths at 1 m (dBm) outside this range are wrong (a misconfigured beacon), and ignored. */
val PLAUSIBLE_STRENGTH_AT_1M = -75..-45

/**
 * True when the visitor is clearly at [beaconId] rather than between beacons: it sounds nearer, for its own
 * strength, than every other beacon heard by at least [gapDb]. Walking from one beacon to the next, the one left
 * behind can stay the nearest for a moment; this says it isn't clear then.
 */
fun clearlyAt(heard: List<HeardBeacon>, beaconId: String, gapDb: Int = CLEAR_GAP_DB): Boolean {
    val here = heard.firstOrNull { it.beacon.beaconId == beaconId } ?: return false
    return heard.all { it.beacon.beaconId == beaconId || it.nearness <= here.nearness - gapDb }
}

/** How much nearer (dB) a beacon must sound than all others for the visitor to be clearly at it. */
const val CLEAR_GAP_DB = 6

/**
 * The registered beacons among the [devices] heard, strongest first. Unregistered devices, signals still settling
 * ([NearbyDevice.isStable]) and signals weaker than [minRssi] are left out.
 */
fun heardBeacons(devices: List<NearbyDevice>, registered: List<RegisteredBeacon>, minRssi: Int = NearestBeacon.MIN_RSSI): List<HeardBeacon> =
    devices
        .filter { it.isStable && it.rssi >= minRssi }
        .sortedByDescending { it.rssi }
        .mapNotNull { device -> registered.firstOrNull { it.matches(device) }?.let { HeardBeacon(it, device) } }
        .distinctBy { it.beacon.beaconId }

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
        // Each registered beacon heard, with its smoothed signal. The nearest sounds nearest for its own strength.
        val heard = heardBeacons(devices, registered, minRssi)
        val strongest = heard.maxByOrNull { it.nearness }
        val now = current
        val currentHeard = now?.let { fix -> heard.firstOrNull { it.beacon.beaconId == fix.beacon.beaconId } }

        current = when {
            strongest == null -> null.also { challenger = null }
            // Nothing yet, or the current beacon went quiet: take the strongest straight away.
            now == null || currentHeard == null -> {
                challenger = null
                fixOf(strongest, nowMillis)
            }
            strongest.beacon.beaconId != now.beacon.beaconId && strongest.nearness >= currentHeard.nearness + hysteresisDb -> {
                val since = challenger?.takeIf { it.first == strongest.beacon.beaconId }?.second ?: nowMillis
                challenger = strongest.beacon.beaconId to since
                if (nowMillis - since >= switchAfterMillis) {
                    challenger = null
                    fixOf(strongest, nowMillis)
                } else {
                    now.copy(rssi = currentHeard.rssi, roughDistanceMeters = currentHeard.roughDistanceMeters)
                }
            }
            else -> {
                challenger = null
                now.copy(rssi = currentHeard.rssi, roughDistanceMeters = currentHeard.roughDistanceMeters)
            }
        }
        return current
    }

    fun reset() {
        current = null
        challenger = null
    }

    private fun fixOf(heard: HeardBeacon, nowMillis: Long) =
        BeaconFix(heard.beacon, heard.rssi, heard.roughDistanceMeters, nowMillis)

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

        /** At the beacon: within this (meters) of a calibrated beacon, or a signal this strong (dBm) from another. */
        const val AT_BEACON_METERS = 2.5f
        const val AT_BEACON_RSSI = -70

        /** Right by the beacon: within this (meters) of a calibrated beacon, or a signal this strong (dBm) from another. */
        const val RIGHT_BY_METERS = 1.5f
        const val RIGHT_BY_RSSI = -62

        const val MIN_UNCERTAINTY_METERS = 2f
        const val UNKNOWN_DISTANCE_METERS = 5f
    }
}

/**
 * Where the visitor probably is on the floor plan, from all the registered beacons heard on their floor, not only
 * the nearest one: a point between them, pulled towards the beacons that sound closer. It moves while the visitor
 * walks, instead of jumping from beacon to beacon. It is still coarse ([uncertaintyMeters]), and with two beacons it
 * can only move along the line between them.
 */
data class BeaconPosition(
    val floorId: String,
    val x: Float,
    /** The floor-plan y: the venue's z. */
    val z: Float,
    val uncertaintyMeters: Float,
    /** How many beacons the estimate rests on. */
    val beaconsHeard: Int,
) {
    val position: Vec3 get() = Vec3(x, 0f, z)

    /** Where a route starts from here: joined to the recorded walks like a beacon ([BEACON_JOIN_M]). */
    fun routeStart(name: String): RouteEndpoint = RouteEndpoint(name, position, floorId, BEACON_JOIN_M)
}

/**
 * Follows the visitor between the beacons ([BeaconPosition]). Each registered beacon heard on the nearest beacon's
 * floor pulls the estimate towards it by 1 / distance² (the rough distance from its signal); only the
 * [MAX_BEACONS] strongest count. The estimate then follows that point with a lag of about [followSeconds], so one
 * noisy reading moves it a little, not all the way.
 */
class BeaconTracker(
    private val followSeconds: Float = FOLLOW_SECONDS,
    private val minRssi: Int = NearestBeacon.MIN_RSSI,
) {
    var current: BeaconPosition? = null
        private set

    private var lastMillis = 0L

    /**
     * Updates the estimate from the devices heard now, on the [nearest] beacon's floor. Only beacons with a position
     * count; null when none of those is heard.
     */
    fun update(devices: List<NearbyDevice>, registered: List<RegisteredBeacon>, nearest: BeaconFix?, nowMillis: Long): BeaconPosition? {
        if (nearest == null) return null.also { reset() }
        val floor = nearest.beacon.floorId
        val heard = heardBeacons(devices, registered, minRssi)
            .filter { it.beacon.floorId == floor && it.beacon.hasPosition }
            .sortedByDescending { it.nearness }
            .take(MAX_BEACONS)
        val target = centreOf(heard) ?: nearest.beacon.position ?: return null.also { reset() }
        // How far to move towards the new point: all the way at first, then a share that grows with the time passed.
        val previous = current?.takeIf { it.floorId == floor }
        val follow = if (previous == null) 1f else 1f - exp(-(nowMillis - lastMillis).coerceAtLeast(0L) / 1000f / followSeconds)
        val x = previous?.let { it.x + (target.x - it.x) * follow } ?: target.x
        val z = previous?.let { it.z + (target.z - it.z) * follow } ?: target.z
        lastMillis = nowMillis
        return BeaconPosition(floor, x, z, nearest.uncertaintyMeters, heard.size).also { current = it }
    }

    fun reset() {
        current = null
    }

    private fun centreOf(heard: List<HeardBeacon>): Vec3? {
        var sumX = 0.0
        var sumZ = 0.0
        var sumWeights = 0.0
        for (one in heard) {
            val spot = one.beacon.position ?: continue
            val meters = (one.roughDistanceMeters ?: one.device.roughDistanceFor(DEFAULT_STRENGTH_AT_1M)).coerceAtLeast(MIN_DISTANCE_METERS)
            val weight = 1.0 / (meters * meters)
            sumX += spot.x * weight
            sumZ += spot.z * weight
            sumWeights += weight
        }
        return if (sumWeights > 0.0) Vec3((sumX / sumWeights).toFloat(), 0f, (sumZ / sumWeights).toFloat()) else null
    }

    companion object {
        /** The estimate covers about two thirds of the way to a new point in this many seconds. */
        const val FOLLOW_SECONDS = 1.5f

        /** Only this many of the strongest beacons count. */
        const val MAX_BEACONS = 3

        /** Closer than this (meters) counts as this close, so one beacon can't pull with an endless weight. */
        const val MIN_DISTANCE_METERS = 0.5f

    }
}

/**
 * Where a route starts when the visitor was found by Bluetooth: at the nearest beacon's position on the floor plan,
 * joined to the recorded walks within [BEACON_JOIN_M]. Beacons hang on walls and ceilings beside the walkways, and
 * the visitor is only known to be around the beacon, so the join is more generous than for a scanned reference point.
 * The walkable network itself still comes from the walks the admin recorded: beacons don't add any paths.
 */
fun BeaconFix.routeStart(): RouteEndpoint? = beacon.routeEndpoint()

/**
 * The beacon as either end of a route: its spot on the floor plan, joined to the walks within [BEACON_JOIN_M]; null
 * for a beacon without a position.
 */
fun RegisteredBeacon.routeEndpoint(): RouteEndpoint? = position?.let { RouteEndpoint(name, it, floorId, BEACON_JOIN_M) }

/** The beacon's spot in venue coordinates (its floor-plan y is the venue's z), or null when it isn't known. */
val RegisteredBeacon.position: Vec3?
    get() {
        val px = x ?: return null
        val py = y ?: return null
        return Vec3(px, 0f, py)
    }

/** How far (meters) a beacon may be from the recorded walks for a route to start or end at it. */
const val BEACON_JOIN_M = 4f

/**
 * Whether the visitor is getting closer to a place or moving away, from how far it is over the last few seconds:
 * a distance in meters, or (for a beacon without a position) how much weaker than 0 dBm its signal is, `-rssi`.
 * Both wander, so it only says [Direction.CLOSER] or [Direction.FURTHER] once the value changed by [threshold]
 * within [windowMillis]; anything less is [Direction.STEADY].
 */
class ProximityTrend(
    private val windowMillis: Long = WINDOW_MILLIS,
    private val threshold: Float = THRESHOLD_METERS,
) {
    enum class Direction { CLOSER, FURTHER, STEADY }

    private val samples = ArrayDeque<Pair<Long, Float>>()

    /** Adds how far the place is now (smaller is closer) and returns the trend over the last [windowMillis]. */
    fun update(farness: Float, nowMillis: Long): Direction {
        samples.addLast(nowMillis to farness)
        while (samples.size > 1 && nowMillis - samples.first().first > windowMillis) samples.removeFirst()
        val change = farness - samples.first().second
        return when {
            change <= -threshold -> Direction.CLOSER
            change >= threshold -> Direction.FURTHER
            else -> Direction.STEADY
        }
    }

    fun reset() = samples.clear()

    companion object {
        const val WINDOW_MILLIS = 3_000L

        /** For distances: a change of this many meters counts. */
        const val THRESHOLD_METERS = 0.4f

        /** For signals: a change of this many dB counts (smoothed signals still wobble by a couple of dB). */
        const val THRESHOLD_DB = 3f
    }
}

/** The distance (meters) from the beacon's signal: with its calibrated or advertised strength, else a usual one. */
val HeardBeacon.distanceMeters: Float get() = roughDistanceMeters ?: device.roughDistanceFor(DEFAULT_STRENGTH_AT_1M)

/** A beacon's signal is this much stronger 0.5 m away than 1 m away (dB): path loss 2.5 halves the distance by 7.5 dB. */
const val HALF_METER_GAIN_DB = 8

/**
 * The filtered signal (dBm) at which the visitor counts as at the beacon (about 0.5 m): as the admin calibrated it
 * there (or projected from 1 m), but never weaker than [STRONG_SIGNAL_DBM]: only a strong signal ever shows the
 * icon, so a calibration made with something in the way (it then reads weak, and 0.5 m would really be 2 m) can't
 * make it show early. What a beacon says about its own strength is never used for this.
 */
val HeardBeacon.detectRssi: Int
    get() = maxOf(STRONG_SIGNAL_DBM, beacon.rssiAt05m ?: beacon.rssiAt1m?.plus(HALF_METER_GAIN_DB) ?: STRONG_SIGNAL_DBM)

/**
 * The weakest filtered signal (dBm) that can count as at a beacon. For a beacon not calibrated it is the threshold;
 * a calibration can only make it stricter (a loud beacon calibrated at −45 dBm needs −45).
 */
const val STRONG_SIGNAL_DBM = -55

/** True when the 0.5 m threshold was measured at 0.5 m, not projected from 1 m. */
val HeardBeacon.thresholdCalibrated: Boolean get() = beacon.rssiAt05m != null

/** True when the admin calibrated the beacon at all (at 0.5 m or 1 m); otherwise its 0.5 m is a cautious guess. */
val HeardBeacon.isCalibrated: Boolean get() = beacon.rssiAt05m != null || beacon.rssiAt1m != null

/** How strong a beacon's signal is against its 0.5 m threshold. Only [STRONG] can mean "at the beacon". */
enum class SignalLevel { WEAK, MEDIUM, STRONG }

val HeardBeacon.signalLevel: SignalLevel
    get() = when {
        rssi >= detectRssi - BeaconDetector.ENTER_MARGIN_DB -> SignalLevel.STRONG
        rssi >= detectRssi - BeaconDetector.MEDIUM_BELOW_DB -> SignalLevel.MEDIUM
        else -> SignalLevel.WEAK
    }

/**
 * Detects the beacons the visitor is at: within about 0.5 m, by Bluetooth signal strength.
 *
 * A beacon merely heard is not detected. Its filtered signal (the median of the last few seconds, [NearbyDevices])
 * must reach its 0.5 m threshold ([detectRssi], never weaker than [STRONG_SIGNAL_DBM]) and stay there for
 * [enterHoldMillis], and it must sound clearly nearer than every other beacon ([CLEAR_GAP_DB]): a weak or medium
 * signal never counts, and neither does one reading. For a beacon the admin hasn't calibrated, the threshold is a cautious guess.
 *
 * Once detected, it stays detected while the visitor stays put: turning around puts the body between phone and
 * beacon and weakens the signal by up to 15 dB, without changing the distance. It is lost
 * - at once when the phone moved more than [leaveMeters] from where it was detected (the beacon was within 0.5 m of
 *   that spot, so it is out of range now);
 * - when its signal stayed well below the threshold for [exitHoldMillis] and the phone moved at least [moveMeters]
 *   (or where the phone is isn't known);
 * - when it hasn't been heard at all for [unheardMillis].
 * Where the phone is comes from the camera's motion tracking, accurate to centimeters.
 */
class BeaconDetector(
    private val enterHoldMillis: Long = ENTER_HOLD_MS,
    private val exitHoldMillis: Long = EXIT_HOLD_MS,
    private val leaveMeters: Float = LEAVE_M,
    private val moveMeters: Float = MOVE_M,
    private val unheardMillis: Long = UNHEARD_MS,
) {
    private class State {
        var strongSince: Long? = null
        var weakSince: Long? = null
        var unheardSince: Long? = null
        var detected = false
        var detectedAt: Vec3? = null
    }

    private val states = mutableMapOf<String, State>()

    /**
     * Updates the detections from the beacons [heard] now (their filtered signals) and where the [phone] is (null
     * when the camera isn't tracking), and returns the ids of the beacons the visitor is at.
     */
    fun update(heard: List<HeardBeacon>, phone: Vec3?, nowMillis: Long): Set<String> {
        val byId = heard.associateBy { it.beacon.beaconId }
        for (id in states.keys + byId.keys) {
            val state = states.getOrPut(id) { State() }
            val signal = byId[id]
            // Strong, and clearly the nearest beacon: two beacons can't both be within 0.5 m of the phone.
            val strong = signal != null && signal.rssi >= signal.detectRssi - ENTER_MARGIN_DB &&
                heard.all { it.beacon.beaconId == id || it.rssi - it.detectRssi <= signal.rssi - signal.detectRssi - CLEAR_GAP_DB }
            val wellBelow = signal == null || signal.rssi < signal.detectRssi - EXIT_MARGIN_DB
            state.unheardSince = if (signal == null) state.unheardSince ?: nowMillis else null
            if (!state.detected) {
                state.strongSince = if (strong) state.strongSince ?: nowMillis else null
                if (strong && nowMillis - state.strongSince!! >= enterHoldMillis) {
                    state.detected = true
                    state.detectedAt = phone
                    state.weakSince = null
                }
                continue
            }
            val moved = if (phone != null) state.detectedAt?.let { horizontal(phone, it) } else null
            if (state.detectedAt == null && phone != null) state.detectedAt = phone // tracking came back: from here
            state.weakSince = if (wellBelow) state.weakSince ?: nowMillis else null
            val lost = (moved != null && moved > leaveMeters) ||
                (state.weakSince?.let { nowMillis - it >= exitHoldMillis } == true && (moved == null || moved >= moveMeters)) ||
                (state.unheardSince?.let { nowMillis - it >= unheardMillis } == true)
            if (lost) states[id] = State()
        }
        return states.filterValues { it.detected }.keys.toSet()
    }

    /** Where the phone was when [beaconId] was detected, while it is. */
    fun detectedAt(beaconId: String): Vec3? = states[beaconId]?.takeIf { it.detected }?.detectedAt

    fun reset() = states.clear()

    private fun horizontal(a: Vec3, b: Vec3) = kotlin.math.hypot(a.x - b.x, a.z - b.z)

    companion object {
        /** Strong enough: at the 0.5 m threshold (no slack under it). */
        const val ENTER_MARGIN_DB = 0

        /** Medium: up to this far (dB) under the threshold; weaker is weak. */
        const val MEDIUM_BELOW_DB = 12

        /** Well below: this far (dB) under the threshold, more than a wobble. */
        const val EXIT_MARGIN_DB = 8

        /** The signal must stay strong this long (ms) before the beacon counts as detected. */
        const val ENTER_HOLD_MS = 1_500L

        /** …and well below this long (ms) before it is lost (with the phone moved). */
        const val EXIT_HOLD_MS = 3_000L

        /** The phone moved this far (meters) from where the beacon was detected: out of its 0.5 m range. */
        const val LEAVE_M = 1.0f

        /** A weak signal only counts as having left once the phone moved this much (meters), not on turning around. */
        const val MOVE_M = 0.4f

        /** Not heard at all this long (ms): gone. */
        const val UNHEARD_MS = 10_000L
    }
}
