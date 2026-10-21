package com.app.androidnativearcore.wayfinder.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Merges reference points captured on-device into the venue as ordinary [Venue.Marker]s, so routing and the AR
 * marker database treat them exactly like the bundled AR signs. Markers whose level no longer exists, or whose id
 * collides with an existing marker, are skipped.
 */
fun Venue.withExtraMarkers(extra: List<Venue.Marker>): Venue {
    if (extra.isEmpty()) return this
    val levelIDs = levels.map { it.id }.toSet()
    val existingIDs = markers.map { it.id }.toSet()
    val additions = extra.filter { it.level in levelIDs && it.id !in existingIDs && it.physicalWidth > 0f }
    if (additions.isEmpty()) return this
    return copy(markers = markers + additions)
}

/** A captured reference point on disk: the venue it belongs to and its marker (whose [Venue.Marker.image] is an
 *  absolute path to the saved image file). */
@Serializable
data class StoredMarker(val venueId: String, val marker: Venue.Marker)

/**
 * Persists reference points the admin captures, one JSON file per marker in [directory], mirroring how
 * [ArPathStore] keeps recorded paths. The image bytes are written separately by the Android layer to a file in the
 * same [directory]; [Venue.Marker.image] holds that file's absolute path.
 */
class CapturedMarkerStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    init {
        directory.mkdirs()
    }

    /** The folder captured images should be written into (same place as the metadata). */
    fun imageFile(id: String): File = File(directory, "$id.png")

    fun load(venueId: String): List<Venue.Marker> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { file ->
                runCatching { json.decodeFromString(StoredMarker.serializer(), file.readText()) }.getOrNull()
            }
            .filter { it.venueId == venueId }
            .map { it.marker }
            .filter { File(it.image).exists() }

    fun save(venueId: String, marker: Venue.Marker) {
        val target = File(directory, "${marker.id}.json")
        val tmp = File(directory, "${marker.id}.json.tmp")
        tmp.writeText(json.encodeToString(StoredMarker.serializer(), StoredMarker(venueId, marker)))
        tmp.renameTo(target)
    }

    fun delete(id: String) {
        File(directory, "$id.json").delete()
        imageFile(id).delete()
    }
}

/** [Venue.POI.category] of a captured reference point listed as a place to navigate to. */
const val REFERENCE_CATEGORY = "reference"

/** How far in front of a captured reference point (meters) the user is taken: where they can see it again. */
private const val REFERENCE_STAND_OFF_M = 1.5f

/** POI id of the reference point [markerId]. */
fun referencePoiId(markerId: String) = "refpoi-$markerId"

/** Destinations whose routes follow the walked paths rather than the venue network. */
val Venue.POI.followsWalkedPaths: Boolean
    get() = category == CustomDestination.CATEGORY || category == REFERENCE_CATEGORY

/**
 * Lists each captured reference point as a destination, standing [REFERENCE_STAND_OFF_M] in front of its image, so
 * "from here" can offer every point the admin marked along the way, and tapping one navigates to it.
 */
fun Venue.withReferenceDestinations(captured: List<Venue.Marker>): Venue {
    val levelIDs = levels.map { it.id }.toSet()
    val takenIDs = pois.map { it.id }.toMutableSet()
    val added = captured.filter { it.level in levelIDs }.mapNotNull { marker ->
        val id = referencePoiId(marker.id)
        if (!takenIDs.add(id)) return@mapNotNull null
        val facing = Math.toRadians(marker.facing.toDouble())
        Venue.POI(
            id = id, name = marker.name, category = REFERENCE_CATEGORY, level = marker.level,
            x = marker.x + kotlin.math.sin(facing).toFloat() * REFERENCE_STAND_OFF_M,
            z = marker.z + kotlin.math.cos(facing).toFloat() * REFERENCE_STAND_OFF_M,
            node = "",
        )
    }
    return if (added.isEmpty()) this else copy(pois = pois + added)
}
