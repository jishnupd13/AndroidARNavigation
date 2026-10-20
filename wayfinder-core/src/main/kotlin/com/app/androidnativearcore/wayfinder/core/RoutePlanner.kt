package com.app.androidnativearcore.wayfinder.core

import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min

/** Who the route is for. Step-free routes avoid stairs, escalators and edges marked as not accessible. */
enum class RouteProfile { FASTEST, STEP_FREE }

/** One instruction along a route. */
data class Maneuver(
    val kind: Kind,
    /** Index into [Route.points] where the maneuver happens. */
    val pointIndex: Int,
    /** Distance along the route from the start, in meters. */
    val distanceFromStart: Float,
) {
    sealed interface Kind {
        data object Start : Kind
        data object SlightLeft : Kind
        data object TurnLeft : Kind
        data object SlightRight : Kind
        data object TurnRight : Kind
        data object UTurn : Kind

        /** Change level using the given connection type (elevator, escalator, stairs, ramp). */
        data class ChangeLevel(val type: Venue.EdgeType, val toLevel: String) : Kind
        data object Arrive : Kind
    }
}

/** A planned path through the venue. */
data class Route(
    /** Node ids from start to destination. */
    val nodeIDs: List<String>,
    /** Positions of the nodes in venue coordinates (floor height). */
    val points: List<Vec3>,
    /** Level id of each point. */
    val levels: List<String>,
    /** Distance from the start to each point, in meters. */
    val cumulativeDistances: List<Float>,
    val maneuvers: List<Maneuver>,
    val profile: RouteProfile,
) {
    val length: Float get() = cumulativeDistances.lastOrNull() ?: 0f
    val destination: Vec3 get() = points.lastOrNull() ?: Vec3.ZERO
}

sealed class RoutingError(message: String) : Exception(message) {
    class UnknownNode(val id: String) : RoutingError("Unknown location '$id'.")
    class UnknownPOI(val id: String) : RoutingError("Unknown destination '$id'.")
    /** A failure with its own user-facing explanation (e.g. from walked-path routing). */
    class Other(message: String) : RoutingError(message)
    data object NoRoute : RoutingError("No route was found. Some paths may be closed or not accessible.")
}

/** Finds shortest routes on a venue's walkable network with A*. */
class RoutePlanner(val venue: Venue) {
    /** Extra cost (in meters of walking) for using an elevator, to account for waiting. */
    var elevatorPenalty: Float = 20f

    /** Multiplier for stairs, which are slower than walking on the flat. */
    var stairsFactor: Float = 1.5f

    private val positions: Map<String, Vec3> = venue.nodes.associate { it.id to venue.position(it) }
    private val nodeLevels: Map<String, String> = venue.nodes.associate { it.id to it.level }

    // region Planning

    /** Route from a node to a POI. */
    fun route(fromNode: String, toPOI: String, profile: RouteProfile): Route {
        val poi = venue.poi(toPOI) ?: throw RoutingError.UnknownPOI(toPOI)
        return routeToNode(fromNode, poi.node, profile)
    }

    /** Route from the node closest to a position (on the given level) to a POI. */
    fun route(from: Vec3, level: String, toPOI: String, profile: RouteProfile): Route {
        val start = nearestNode(from, level) ?: throw RoutingError.NoRoute
        return route(start, toPOI, profile)
    }

    fun routeToNode(fromNode: String, toNode: String, profile: RouteProfile): Route {
        val startPosition = positions[fromNode] ?: throw RoutingError.UnknownNode(fromNode)
        val goalPosition = positions[toNode] ?: throw RoutingError.UnknownNode(toNode)

        val adjacency = buildAdjacency(profile)
        val openSet = mutableSetOf(fromNode)
        val cameFrom = HashMap<String, Pair<String, Venue.EdgeType>>()
        val gScore = hashMapOf(fromNode to 0f)
        val fScore = hashMapOf(fromNode to startPosition.distanceTo(goalPosition))

        while (openSet.isNotEmpty()) {
            val current = openSet.minBy { fScore[it] ?: Float.POSITIVE_INFINITY }
            if (current == toNode) return makeRoute(toNode, cameFrom, profile)
            openSet.remove(current)
            for (link in adjacency[current].orEmpty()) {
                val tentative = (gScore[current] ?: Float.POSITIVE_INFINITY) + link.cost
                if (tentative < (gScore[link.to] ?: Float.POSITIVE_INFINITY)) {
                    cameFrom[link.to] = current to link.type
                    gScore[link.to] = tentative
                    fScore[link.to] = tentative + positions.getValue(link.to).distanceTo(goalPosition)
                    openSet.add(link.to)
                }
            }
        }
        throw RoutingError.NoRoute
    }

    /**
     * A route that follows an already-planned floor polyline (e.g. from [WalkedPathRouter]) instead of the venue
     * network, with the same distances and turn-by-turn maneuvers as network routes. All points are on [level].
     */
    fun routeAlong(points: List<Vec3>, level: String, profile: RouteProfile): Route {
        require(points.isNotEmpty()) { "A route needs at least one point" }
        val levels = points.map { level }
        val edgeTypes = List(max(points.size - 1, 0)) { Venue.EdgeType.WALKWAY }
        val cumulative = mutableListOf(0f)
        for (index in 1 until points.size) {
            cumulative.add(cumulative[index - 1] + segmentLength(points[index - 1], points[index], edgeTypes[index - 1]))
        }
        return Route(
            nodeIDs = points.indices.map { "walked-$it" }, points = points, levels = levels,
            cumulativeDistances = cumulative, maneuvers = maneuvers(points, levels, edgeTypes, cumulative), profile = profile,
        )
    }

    /** The closest node on a level to a position (horizontal distance). */
    fun nearestNode(position: Vec3, level: String): String? =
        venue.nodes.filter { it.level == level }
            .minByOrNull { horizontalDistance(positions.getValue(it.id), position) }?.id

    // endregion

    // region Graph

    private class Link(val to: String, val cost: Float, val type: Venue.EdgeType)

    private fun buildAdjacency(profile: RouteProfile): Map<String, List<Link>> {
        val adjacency = HashMap<String, MutableList<Link>>()
        for (edge in venue.edges) {
            if (edge.closed) continue
            if (profile == RouteProfile.STEP_FREE && (!edge.type.isStepFree || !edge.accessible)) continue
            val a = positions[edge.from] ?: continue
            val b = positions[edge.to] ?: continue
            val cost = cost(edge, a, b)
            adjacency.getOrPut(edge.from) { mutableListOf() }.add(Link(edge.to, cost, edge.type))
            if (!edge.oneWay) adjacency.getOrPut(edge.to) { mutableListOf() }.add(Link(edge.from, cost, edge.type))
        }
        return adjacency
    }

    private fun cost(edge: Venue.Edge, a: Vec3, b: Vec3): Float {
        val length = a.distanceTo(b)
        return when (edge.type) {
            Venue.EdgeType.ELEVATOR -> horizontalDistance(a, b) + elevatorPenalty
            Venue.EdgeType.STAIRS -> length * stairsFactor
            else -> length
        }
    }

    // endregion

    // region Building the route

    private fun makeRoute(goal: String, cameFrom: Map<String, Pair<String, Venue.EdgeType>>, profile: RouteProfile): Route {
        val nodeIDs = mutableListOf(goal)
        val edgeTypes = mutableListOf<Venue.EdgeType>() // edgeTypes[i] connects nodeIDs[i] -> nodeIDs[i + 1] (after reversing)
        var current = goal
        while (true) {
            val previous = cameFrom[current] ?: break
            nodeIDs.add(previous.first)
            edgeTypes.add(previous.second)
            current = previous.first
        }
        nodeIDs.reverse()
        edgeTypes.reverse()

        val points = nodeIDs.map { positions.getValue(it) }
        val levels = nodeIDs.map { nodeLevels.getValue(it) }
        val cumulative = mutableListOf(0f)
        for (index in 1 until max(points.size, 1)) {
            cumulative.add(cumulative[index - 1] + segmentLength(points[index - 1], points[index], edgeTypes[index - 1]))
        }

        return Route(
            nodeIDs = nodeIDs, points = points, levels = levels, cumulativeDistances = cumulative,
            maneuvers = maneuvers(points, levels, edgeTypes, cumulative), profile = profile,
        )
    }

    /** Distance walked along a segment. Elevator rides count as no walking distance. */
    private fun segmentLength(a: Vec3, b: Vec3, type: Venue.EdgeType): Float =
        if (type == Venue.EdgeType.ELEVATOR) horizontalDistance(a, b) else a.distanceTo(b)

    private fun maneuvers(points: List<Vec3>, levels: List<String>, edgeTypes: List<Venue.EdgeType>, cumulative: List<Float>): List<Maneuver> {
        val result = mutableListOf(Maneuver(Maneuver.Kind.Start, 0, 0f))
        for (index in 0 until max(points.size - 1, 0)) {
            // Segment index -> index + 1. A level change is reported once, at the start of a run of
            // level-changing segments (e.g. several elevator stops), naming the level where the run ends.
            if (levels[index + 1] != levels[index]) {
                val startsRun = index == 0 || levels[index - 1] == levels[index]
                if (startsRun) {
                    var end = index + 1
                    while (end + 1 < levels.size && levels[end + 1] != levels[end]) end += 1
                    result.add(Maneuver(Maneuver.Kind.ChangeLevel(edgeTypes[index], levels[end]), index, cumulative[index]))
                }
                continue
            }
            // A turn at the end of this segment, if the next segment continues on the same level.
            val next = index + 1
            if (next + 1 >= points.size || levels[next + 1] != levels[next]) continue
            val turn = turnKind(points[index], points[next], points[next + 1]) ?: continue
            result.add(Maneuver(turn, next, cumulative[next]))
        }
        result.add(Maneuver(Maneuver.Kind.Arrive, max(points.size - 1, 0), cumulative.lastOrNull() ?: 0f))
        return result
    }

    private fun turnKind(a: Vec3, b: Vec3, c: Vec3): Maneuver.Kind? {
        val incoming = Vec2(b.x - a.x, b.z - a.z)
        val outgoing = Vec2(c.x - b.x, c.z - b.z)
        if (incoming.length <= 0.01f || outgoing.length <= 0.01f) return null
        val inDirection = incoming.normalized()
        val outDirection = outgoing.normalized()
        val angle = Math.toDegrees(acos(max(-1f, min(1f, inDirection dot outDirection)).toDouble())).toFloat()
        // With y up, a positive cross product (x_in * z_out - z_in * x_out) is a right turn.
        val isRight = inDirection.x * outDirection.y - inDirection.y * outDirection.x > 0

        return when {
            angle < 20 -> null
            angle < 50 -> if (isRight) Maneuver.Kind.SlightRight else Maneuver.Kind.SlightLeft
            angle < 150 -> if (isRight) Maneuver.Kind.TurnRight else Maneuver.Kind.TurnLeft
            else -> Maneuver.Kind.UTurn
        }
    }

    // endregion
}
