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
