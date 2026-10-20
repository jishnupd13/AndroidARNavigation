package com.app.androidnativearcore.wayfinder.core

import kotlin.math.abs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A venue (mall, museum, stadium…) described in one local coordinate system, in meters:
 * `x` and `z` are horizontal, `y` is up. Each level has an elevation; nodes, POIs and markers are placed on levels.
 *
 * The same coordinate system is used by the AR session once it is aligned with a marker, so anything
 * positioned in venue coordinates appears at the right physical spot.
 */
@Serializable
data class Venue(
    val formatVersion: Int = 1,
    val id: String,
    val name: LocalizedText,
    val levels: List<Level>,
    val nodes: List<Node>,
    val edges: List<Edge>,
    val pois: List<POI> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val tips: List<Tip> = emptyList(),
) {
    @Serializable
    data class Level(
        val id: String,
        val name: LocalizedText,
        /** Floor height in meters. */
        val elevation: Float,
    )

    /** A point of the walkable network (corridor junction, door, elevator stop…). */
    @Serializable
    data class Node(val id: String, val level: String, val x: Float, val z: Float)

    @Serializable
    enum class EdgeType {
        @SerialName("walkway") WALKWAY,
        @SerialName("door") DOOR,
        @SerialName("ramp") RAMP,
        @SerialName("stairs") STAIRS,
        @SerialName("escalator") ESCALATOR,
        @SerialName("elevator") ELEVATOR;

        /** Whether wheelchairs and strollers can use this kind of connection. */
        val isStepFree: Boolean
            get() = when (this) {
                WALKWAY, DOOR, RAMP, ELEVATOR -> true
                STAIRS, ESCALATOR -> false
            }
    }

    /** A walkable connection between two nodes. */
    @Serializable
    data class Edge(
        val from: String,
        val to: String,
        val type: EdgeType = EdgeType.WALKWAY,
        /** Only walkable from `from` to `to` (e.g. an up escalator). */
        val oneWay: Boolean = false,
        /** Temporarily closed (construction, crowd control). Closed edges are never routed through. */
        val closed: Boolean = false,
        /** Set to false for step-free edge types that still aren't accessible (e.g. a narrow passage). */
        val accessible: Boolean = true,
    )

    /** A destination or landmark: shop, restroom, exit… */
    @Serializable
    data class POI(
        val id: String,
        val name: LocalizedText,
        val category: String,
        val level: String,
        val x: Float,
        val z: Float,
        /** The network node routes to this POI end at. */
        val node: String,
        val description: LocalizedText? = null,
        /**
         * Height of the real floor at this POI, in venue coordinates. When null, the elevation of its level is used.
         * Destinations a user records set it from the floor ARCore detected, so the pin stands on the real floor
         * even if the sign isn't mounted at exactly the height the venue data assumes.
         */
        val elevation: Float? = null,
    )

    @Serializable
    enum class MarkerMounting {
        /** Vertical, e.g. a sign on a wall or pillar. */
        @SerialName("wall") WALL,

        /** Flat on the floor, e.g. a floor sticker. */
        @SerialName("floor") FLOOR,
    }

    /** A printed image ("AR sign") with a surveyed position. Seeing it aligns the AR session with the venue. */
    @Serializable
    data class Marker(
        val id: String,
        val name: LocalizedText,
        /** Image path, relative to the venue package folder. */
        val image: String,
        /** Printed width of the image, in meters. */
        val physicalWidth: Float,
        val level: String,
        /** Center of the printed image. */
        val x: Float,
        val y: Float,
        val z: Float,
        /**
         * Wall markers: compass-style direction the front of the sign faces, in degrees (0 = +z, 90 = +x).
         * Floor markers: direction the top of the image points to.
         */
        val facing: Float = 0f,
        val mounting: MarkerMounting = MarkerMounting.WALL,
    )

    /** A contextual message shown when the user passes nearby (e.g. "Restrooms 50 m ahead"). */
    @Serializable
    data class Tip(
        val id: String,
        val level: String,
        val x: Float,
        val z: Float,
        /** Distance in meters within which the tip is shown. */
        val radius: Float,
        val text: LocalizedText,
    )

    // region Lookup

    fun level(withID: String): Level? = levels.firstOrNull { it.id == withID }
    fun node(withID: String): Node? = nodes.firstOrNull { it.id == withID }
    fun poi(withID: String): POI? = pois.firstOrNull { it.id == withID }
    fun marker(withID: String): Marker? = markers.firstOrNull { it.id == withID }

    /** Position of a node in venue coordinates (on its level's floor). */
    fun position(of: Node): Vec3 = Vec3(of.x, level(of.level)?.elevation ?: 0f, of.z)

    fun position(of: POI): Vec3 = Vec3(of.x, of.elevation ?: level(of.level)?.elevation ?: 0f, of.z)

    /** The level whose floor best matches a device held at roughly chest/eye height. */
    fun level(forDeviceHeight: Float, typicalDeviceHeightAboveFloor: Float = 1.3f): Level? {
        val floorHeight = forDeviceHeight - typicalDeviceHeightAboveFloor
        return levels.minByOrNull { abs(it.elevation - floorHeight) }
    }

    // endregion

    // region Validation

    /** Problems in the venue data (unknown references, duplicate ids). Empty when the venue is valid. */
    fun validationIssues(): List<String> {
        val issues = mutableListOf<String>()
        val levelIDs = levels.map { it.id }.toSet()
        val nodeIDs = nodes.map { it.id }.toSet()

        fun checkDuplicates(ids: List<String>, kind: String) {
            ids.groupBy { it }.filter { it.value.size > 1 }.keys.sorted()
                .forEach { issues += "Duplicate $kind id '$it'." }
        }
        checkDuplicates(levels.map { it.id }, "level")
        checkDuplicates(nodes.map { it.id }, "node")
        checkDuplicates(pois.map { it.id }, "POI")
        checkDuplicates(markers.map { it.id }, "marker")

        for (node in nodes) if (node.level !in levelIDs) issues += "Node '${node.id}' is on unknown level '${node.level}'."
        for (edge in edges) {
            if (edge.from !in nodeIDs) issues += "Edge references unknown node '${edge.from}'."
            if (edge.to !in nodeIDs) issues += "Edge references unknown node '${edge.to}'."
        }
        for (poi in pois) {
            if (poi.level !in levelIDs) issues += "POI '${poi.id}' is on unknown level '${poi.level}'."
            if (poi.node !in nodeIDs) issues += "POI '${poi.id}' uses unknown node '${poi.node}'."
        }
        for (marker in markers) {
            if (marker.level !in levelIDs) issues += "Marker '${marker.id}' is on unknown level '${marker.level}'."
            if (marker.physicalWidth <= 0) issues += "Marker '${marker.id}' needs a positive physicalWidth."
        }
        return issues
    }

    // endregion
}
