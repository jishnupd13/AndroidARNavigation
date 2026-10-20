package com.app.androidnativearcore.wayfinder.core

import kotlinx.serialization.Serializable

/**
 * A destination the user added themselves by walking to a spot in the venue ("Add destination").
 * Its position is in venue coordinates, so it is only meaningful together with the venue it was saved for.
 * Routes to it follow the paths people walked ([WalkedPoint]), not the venue's fixed network.
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
    /** Height of the real floor under the destination (venue coordinates); null in files saved by older versions. */
    val floorY: Float? = null,
) {
    fun toPOI() = Venue.POI(
        id = id, name = LocalizedText(name), category = CATEGORY, level = level, x = x, z = z, node = node,
        elevation = floorY,
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
