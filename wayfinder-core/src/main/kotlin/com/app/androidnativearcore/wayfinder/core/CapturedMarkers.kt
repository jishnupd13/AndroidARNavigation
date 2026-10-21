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

    // The image is stored as a bare filename ("<id>.png") so a saved file is portable between devices; on load it is
    // resolved to the current device's absolute path. Older files that stored an absolute path still work, because the
    // path is always recomputed from the marker id here.
    fun load(venueId: String): List<Venue.Marker> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { file ->
                runCatching { json.decodeFromString(StoredMarker.serializer(), file.readText()) }.getOrNull()
            }
            .filter { it.venueId == venueId }
            .map { it.marker.copy(image = imageFile(it.marker.id).absolutePath) }
            .filter { File(it.image).exists() }

    fun save(venueId: String, marker: Venue.Marker) {
        val target = File(directory, "${marker.id}.json")
        val tmp = File(directory, "${marker.id}.json.tmp")
        // Persist only the filename, not an absolute path, so the JSON is portable.
        val portable = marker.copy(image = imageFile(marker.id).name)
        tmp.writeText(json.encodeToString(StoredMarker.serializer(), StoredMarker(venueId, portable)))
        tmp.renameTo(target)
    }

    fun delete(id: String) {
        File(directory, "$id.json").delete()
        imageFile(id).delete()
    }

    /** Removes every captured reference point (all metadata and images). */
    fun clear() {
        directory.listFiles { f -> f.isFile && (f.name.endsWith(".json") || f.name.endsWith(".png")) }
            .orEmpty().forEach { it.delete() }
    }
}
