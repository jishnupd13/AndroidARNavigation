package com.app.androidnativearcore.wayfinder.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A destination the user added themselves by walking to a spot in the venue ("Add destination").
 * Its position is in venue coordinates, so it is only meaningful together with the venue it was saved for.
 * Routes to it follow the paths people walked ([WalkedPathStore]), not the venue's fixed network.
 */
@Serializable
data class CustomDestination(
    val id: String,
    val name: String,
    val level: String,
    val x: Float,
    val z: Float,
    val createdAtMillis: Long,
    /** Unused for walked destinations; kept so files saved by older versions still load. */
    val node: String = "",
) {
    fun toPOI() = Venue.POI(
        id = id, name = LocalizedText(name), category = CATEGORY, level = level, x = x, z = z, node = node,
    )

    companion object {
        const val CATEGORY = "custom"
    }
}

/** The venue with the user's destinations added as POIs, so routing and the AR screen treat them like any other. */
fun Venue.withCustomDestinations(destinations: List<CustomDestination>): Venue {
    val levelIDs = levels.map { it.id }.toSet()
    val takenIDs = pois.map { it.id }.toMutableSet()
    // Skip entries that no longer fit this venue (e.g. its data changed) instead of failing to load it.
    val valid = destinations.filter { it.level in levelIDs && takenIDs.add(it.id) }
    return copy(pois = pois + valid.map { it.toPOI() })
}

/** Keeps the user's destinations in a JSON file so they survive restarts. */
class CustomDestinationStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CustomDestination.serializer())

    /** Saved destinations; empty if nothing was saved yet or the file can't be read. */
    fun load(): List<CustomDestination> = runCatching {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    fun add(destination: CustomDestination): List<CustomDestination> = save(load() + destination)

    fun remove(id: String): List<CustomDestination> = save(load().filterNot { it.id == id })

    private fun save(list: List<CustomDestination>): List<CustomDestination> {
        file.parentFile?.mkdirs()
        // Write to a temp file and rename, so a crash mid-write can't corrupt the saved list.
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(serializer, list))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Can't save destinations to ${file.path}" }
        }
        return list
    }
}
