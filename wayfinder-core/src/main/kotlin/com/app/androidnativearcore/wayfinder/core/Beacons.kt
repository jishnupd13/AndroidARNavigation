package com.app.androidnativearcore.wayfinder.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A Bluetooth beacon the admin registered: what identifies it over the air, and where it is fixed in the venue.
 *
 * The field names follow the shared beacon format (`beaconId`, `uuid`, `major`, `minor`, `floorId`, `x`, `y`) so the
 * same file can be read by other apps. [x] and [y] are floor-plan coordinates in meters: [x] is the venue's x and
 * [y] the venue's z (in the venue's 3D frame, y is height). They are optional: a beacon registered without a
 * reference point has no position, and is then found by its signal alone (no 2D map, routes or AR marker for it).
 *
 * Not every beacon advertises the same things: an iBeacon has a [uuid], [major] and [minor]; other beacons may only
 * show a Bluetooth address ([macAddress]), and many change that address regularly. So every identifier is optional,
 * but a beacon needs at least one ([identifies]).
 */
@Serializable
data class RegisteredBeacon(
    val beaconId: String,
    val name: String,
    /** iBeacon proximity UUID, upper case with dashes (see [Beacons.normalizeUuid]); "" when there is none. */
    val uuid: String = "",
    val major: Int? = null,
    val minor: Int? = null,
    /** The Bluetooth address seen when registering, upper case with colons; "" when unknown. Only a hint. */
    val macAddress: String = "",
    /** The building it belongs to: the venue id. */
    val buildingId: String = "",
    /** The floor it is on: a [Venue.Level] id. */
    val floorId: String,
    /** Floor-plan position (meters), or null when it isn't known. */
    val x: Float? = null,
    val y: Float? = null,
    val createdAtMillis: Long = 0L,
    /**
     * The beacon's signal measured 1 m away when the admin calibrated it (dBm), or null. Beacons differ a lot in
     * strength (a phone acting as a beacon may be much louder or quieter than what it advertises), so this is what
     * makes distances, and so which beacon is nearest, comparable between beacons.
     */
    val rssiAt1m: Int? = null,
    /**
     * The beacon's signal measured 0.5 m away when the admin calibrated it (dBm), or null: the threshold at which the
     * visitor counts as at the beacon ([BeaconDetector]). Measured there, it is far more exact than projecting 0.5 m
     * from [rssiAt1m].
     */
    val rssiAt05m: Int? = null,
) {
    /** True if the beacon carries at least one identifier the app can recognize it by. */
    val identifies: Boolean get() = uuid.isNotEmpty() || macAddress.isNotEmpty()

    /** True if the beacon's spot on the floor plan is known. */
    val hasPosition: Boolean get() = x != null && y != null

    /** "UUID · 1 · 2" (iBeacon) or the address, for showing which beacon this is. */
    val identityLabel: String
        get() = when {
            uuid.isNotEmpty() -> listOfNotNull(uuid, major?.let { "Major $it" }, minor?.let { "Minor $it" }).joinToString(" · ")
            macAddress.isNotEmpty() -> macAddress
            else -> "no identifier"
        }

    /** True if [other] is the same physical beacon (same iBeacon identity, or same address when neither has one). */
    fun sameDeviceAs(other: RegisteredBeacon): Boolean = when {
        uuid.isNotEmpty() && other.uuid.isNotEmpty() -> uuid == other.uuid && major == other.major && minor == other.minor
        uuid.isEmpty() && other.uuid.isEmpty() && macAddress.isNotEmpty() -> macAddress == other.macAddress
        else -> false
    }
}

/** Formatting and checks for beacon identifiers. */
object Beacons {
    private val HEX32 = Regex("^[0-9A-F]{32}$")
    private val MAC = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    /** Largest value of an iBeacon major or minor (16 bits). */
    const val MAX_MAJOR_MINOR = 65_535

    /** The range a beacon's signal 1 m away can sensibly be calibrated to (dBm). */
    const val MIN_RSSI_AT_1M = -100
    const val MAX_RSSI_AT_1M = -20

    /**
     * [text] as a canonical UUID (`E2C56DB5-DFFB-48D2-B060-D0F5A71096E0`), accepting any case and with or without
     * dashes; "" for blank input, or null if it isn't a UUID.
     */
    fun normalizeUuid(text: String): String? {
        val hex = text.trim().replace("-", "").uppercase()
        if (hex.isEmpty()) return ""
        if (!HEX32.matches(hex)) return null
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    /** [text] as an upper-case colon-separated Bluetooth address; "" for blank input, or null if it isn't one. */
    fun normalizeMac(text: String): String? {
        val compact = text.trim().replace("-", ":").uppercase()
        if (compact.isEmpty()) return ""
        val withColons = if (':' !in compact && compact.length == 12) compact.chunked(2).joinToString(":") else compact
        return if (MAC.matches(withColons)) withColons else null
    }

    /**
     * What is wrong with [beacon] before it can be saved, or an empty list. [floorIds] are the venue's levels;
     * [others] the beacons already registered (a beacon being edited is matched by id and not counted twice).
     */
    fun problems(beacon: RegisteredBeacon, floorIds: Set<String>, others: List<RegisteredBeacon>): List<String> {
        val out = mutableListOf<String>()
        if (beacon.name.isBlank()) out += "Give the beacon a name."
        if (beacon.floorId !in floorIds) out += "Choose the floor it is on."
        if (!beacon.identifies) out += "Enter the beacon's UUID (with Major and Minor) or its Bluetooth address."
        if (beacon.uuid.isNotEmpty() && (beacon.major == null || beacon.minor == null)) out += "An iBeacon needs both Major and Minor."
        for (value in listOfNotNull(beacon.major, beacon.minor)) {
            if (value !in 0..MAX_MAJOR_MINOR) out += "Major and Minor must be between 0 and $MAX_MAJOR_MINOR."
        }
        // The position is optional, but half a position (or a broken number) is a mistake.
        if ((beacon.x == null) != (beacon.y == null)) out += "Enter both X and Y, or leave both empty."
        if (listOfNotNull(beacon.x, beacon.y).any { !it.isFinite() }) out += "X and Y must be numbers in meters."
        for (calibrated in listOfNotNull(beacon.rssiAt1m, beacon.rssiAt05m)) {
            if (calibrated !in MIN_RSSI_AT_1M..MAX_RSSI_AT_1M) out += "A calibrated signal must be between $MIN_RSSI_AT_1M and $MAX_RSSI_AT_1M dBm."
        }
        others.firstOrNull { it.beaconId != beacon.beaconId && it.sameDeviceAs(beacon) }?.let {
            out += "This beacon is already registered as “${it.name}”."
        }
        return out.distinct()
    }

    /** The next free id in the shared style: `BEACON_001`, `BEACON_002`, … */
    fun nextId(existing: List<RegisteredBeacon>): String {
        val highest = existing.mapNotNull { it.beaconId.removePrefix("BEACON_").toIntOrNull() }.maxOrNull() ?: 0
        return "BEACON_%03d".format(highest + 1)
    }
}

/**
 * Registered beacons, one JSON file per beacon in [directory] (`BEACON_001.json`), like [ArPathStore] keeps paths. A
 * file the app can't read is skipped and left untouched.
 */
class BeaconStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true; prettyPrint = true }

    /** The beacons of [buildingId] (and beacons saved without a building). */
    fun load(buildingId: String): List<RegisteredBeacon> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
            .mapNotNull { runCatching { json.decodeFromString(RegisteredBeacon.serializer(), it.readText()) }.getOrNull() }
            .filter { it.buildingId == buildingId || it.buildingId.isEmpty() }

    /** Saves (or replaces) [beacon] in its own file. */
    fun save(beacon: RegisteredBeacon) {
        directory.mkdirs()
        val target = fileOf(beacon.beaconId)
        // Write to a temp file and rename, so a crash mid-write can't leave a half-written file.
        val temp = File(target.path + ".tmp")
        temp.writeText(json.encodeToString(RegisteredBeacon.serializer(), beacon))
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Can't save ${target.path}" }
        }
    }

    /** Deletes the beacon [beaconId]. Returns whether it existed. */
    fun delete(beaconId: String): Boolean = fileOf(beaconId).delete()

    private fun fileOf(beaconId: String) = File(directory, ArPathStore.slugOf(beaconId).ifEmpty { "beacon" } + ".json")
}
