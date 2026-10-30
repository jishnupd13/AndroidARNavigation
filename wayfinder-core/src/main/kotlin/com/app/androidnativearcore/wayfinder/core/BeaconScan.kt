package com.app.androidnativearcore.wayfinder.core

import kotlin.math.pow

/**
 * What a Bluetooth advertisement says about who sent it.
 *
 * Beacons follow different protocols, and they don't all reveal the same things: an iBeacon sends a UUID, Major and
 * Minor; an Eddystone-UID beacon a namespace and instance; any other device may only show its Bluetooth address
 * (which many devices change every few minutes). The parsers below never guess: what isn't in the advertisement is
 * left out.
 */
sealed interface BeaconIdentity {
    /** A stable key for grouping the readings of one device. */
    val key: String

    /** The signal strength the beacon says it has at 1 m (dBm), if it says so. */
    val txPowerAt1m: Int?

    data class IBeacon(val uuid: String, val major: Int, val minor: Int, override val txPowerAt1m: Int) : BeaconIdentity {
        override val key get() = "ibeacon:$uuid:$major:$minor"
    }

    data class EddystoneUid(val namespace: String, val instance: String, override val txPowerAt1m: Int?) : BeaconIdentity {
        override val key get() = "eddystone:$namespace:$instance"
    }

    /** Not a recognized beacon format: known only by its Bluetooth address. */
    data class OtherDevice(val address: String) : BeaconIdentity {
        override val key get() = "device:$address"
        override val txPowerAt1m: Int? get() = null
    }
}

/** Reading beacon identities out of advertisement bytes. */
object BeaconAdvertisement {
    /** Apple's Bluetooth company id: iBeacon data is in manufacturer data under this id. */
    const val APPLE_COMPANY_ID = 0x004C

    /** The 16-bit service UUID Eddystone beacons put their frames under. */
    const val EDDYSTONE_SERVICE_UUID = "0000feaa-0000-1000-8000-00805f9b34fb"

    /**
     * The iBeacon in Apple manufacturer data ([data] is what follows the company id): type 0x02, length 0x15, then
     * a 16-byte UUID, 2-byte Major, 2-byte Minor and the 1-byte signal strength at 1 m. Null if it isn't one.
     */
    fun iBeacon(data: ByteArray?): BeaconIdentity.IBeacon? {
        if (data == null || data.size < 23 || data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) return null
        val uuid = Beacons.normalizeUuid(data.copyOfRange(2, 18).toHex()) ?: return null
        val major = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
        val minor = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
        return BeaconIdentity.IBeacon(uuid, major, minor, data[22].toInt())
    }

    /**
     * The Eddystone-UID frame in Eddystone service data: frame type 0x00, the signal strength at 0 m, a 10-byte
     * namespace and a 6-byte instance. Null if it isn't one (Eddystone also has URL and telemetry frames).
     */
    fun eddystoneUid(serviceData: ByteArray?): BeaconIdentity.EddystoneUid? {
        if (serviceData == null || serviceData.size < 18 || serviceData[0] != 0x00.toByte()) return null
        // Eddystone gives the power at 0 m; a signal is about 41 dB weaker at 1 m.
        val txAt1m = serviceData[1].toInt() - 41
        return BeaconIdentity.EddystoneUid(serviceData.copyOfRange(2, 12).toHex(), serviceData.copyOfRange(12, 18).toHex(), txAt1m)
    }

    /** The identity of one advertisement: iBeacon, else Eddystone-UID, else just the device's address. */
    fun identify(appleData: ByteArray?, eddystoneData: ByteArray?, address: String): BeaconIdentity =
        iBeacon(appleData) ?: eddystoneUid(eddystoneData) ?: BeaconIdentity.OtherDevice(address.uppercase())

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}

/** One advertisement heard: who sent it, how strong, and when. */
data class BeaconReading(
    val identity: BeaconIdentity,
    /** The device's Bluetooth address (may change over time for privacy). */
    val address: String,
    /** The device's advertised name, if any. */
    val name: String?,
    val rssi: Int,
    val timeMillis: Long,
)

/** A device heard recently, with its readings smoothed. */
data class NearbyDevice(
    val identity: BeaconIdentity,
    val address: String,
    val name: String?,
    /** The median of the recent signal strengths (dBm): one odd reading can't move it. */
    val rssi: Int,
    /** How many readings the smoothed value rests on. */
    val readings: Int,
    val lastSeenMillis: Long,
) {
    val isBeacon: Boolean get() = identity !is BeaconIdentity.OtherDevice

    /** True once there are enough readings for the signal to be trusted ([NearbyDevices.STABLE_READINGS]). */
    val isStable: Boolean get() = readings >= NearbyDevices.STABLE_READINGS

    /**
     * A rough distance in meters from the signal strength, or null without the beacon's own strength at 1 m. Walls,
     * bodies and the phone's orientation change the signal a lot, so this is only "near" or "far", not a measurement.
     */
    val roughDistanceMeters: Float?
        get() = identity.txPowerAt1m?.let { tx -> 10.0.pow((tx - rssi) / (10.0 * PATH_LOSS_EXPONENT)).toFloat() }

    private companion object {
        /** How fast a signal weakens with distance indoors (2 in open space; 2–3 inside buildings). */
        const val PATH_LOSS_EXPONENT = 2.5
    }
}

/**
 * The Bluetooth devices heard recently, each with a smoothed signal. Signal strength jumps by several dB from one
 * reading to the next (people walking by, reflections), so each device's value is the median of its readings in the
 * last [WINDOW_MILLIS]; a device not heard for [STALE_MILLIS] is dropped. Readings of the same beacon are grouped by
 * its identity, not its address, so a beacon that changes its address stays one entry.
 */
class NearbyDevices {
    private class Track(var reading: BeaconReading, val rssis: ArrayDeque<Pair<Long, Int>> = ArrayDeque())

    private val tracks = LinkedHashMap<String, Track>()

    fun add(reading: BeaconReading) {
        val track = tracks.getOrPut(reading.identity.key) { Track(reading) }
        track.reading = reading
        track.rssis.addLast(reading.timeMillis to reading.rssi)
        while (track.rssis.size > MAX_READINGS) track.rssis.removeFirst()
    }

    /** The devices heard within [STALE_MILLIS] of [nowMillis], strongest signal first. */
    fun current(nowMillis: Long): List<NearbyDevice> {
        tracks.values.removeAll { nowMillis - it.reading.timeMillis > STALE_MILLIS }
        return tracks.values.map { track ->
            // The readings of the last few seconds; a device that went quiet keeps its last reading until it is stale.
            val recent = track.rssis.filter { nowMillis - it.first <= WINDOW_MILLIS }.map { it.second }
                .ifEmpty { listOf(track.rssis.last().second) }
            val sorted = recent.sorted()
            NearbyDevice(
                identity = track.reading.identity, address = track.reading.address, name = track.reading.name,
                rssi = sorted[sorted.size / 2], readings = recent.size, lastSeenMillis = track.reading.timeMillis,
            )
        }.sortedByDescending { it.rssi }
    }

    fun clear() = tracks.clear()

    companion object {
        /** Readings older than this (ms) no longer count toward a device's signal. */
        const val WINDOW_MILLIS = 4_000L

        /** A device not heard for this long (ms) is gone. */
        const val STALE_MILLIS = 8_000L

        /** A device's signal is trusted once it rests on this many readings. */
        const val STABLE_READINGS = 3

        private const val MAX_READINGS = 30
    }
}

/** True if [device] is the beacon [registered]: same iBeacon identity, or the same address for other devices. */
fun RegisteredBeacon.matches(device: NearbyDevice): Boolean = when (val id = device.identity) {
    is BeaconIdentity.IBeacon -> uuid == id.uuid && major == id.major && minor == id.minor
    else -> uuid.isEmpty() && macAddress.isNotEmpty() && macAddress.equals(device.address, ignoreCase = true)
}
