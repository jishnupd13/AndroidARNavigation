package com.app.androidnativearcore.wayfinder.core

import kotlin.math.hypot

/**
 * Where a route starts or ends, and which recorded walks it may join.
 *
 * Routes run only along walks the admin recorded ("walk & connect" between reference points, and the walks of
 * saved destinations). An endpoint joins the nearest recorded points within [joinDistance], and only points that
 * [joins] accepts: an object joins the walk it was placed from, so it is never attached to a walk on the other side
 * of a wall that happens to be nearer.
 */
data class RouteEndpoint(
    val name: String,
    /** Venue position; only x and z are used to join the walks. */
    val position: Vec3,
    val level: String,
    val joinDistance: Float,
    /** Walks of this saved destination only (an object's own walk), or null for any walk. */
    val ownDestinationId: String? = null,
) {
    fun joins(point: WalkedPoint): Boolean = ownDestinationId == null || point.destinationId == ownDestinationId
}

/** The result of [ConnectionRoutePlanner.route]. */
sealed interface ConnectionRouteResult {
    /**
     * The shortest route along the recorded walks: a polyline on the floor from start to destination, its walking
     * length in meters, and the reference points it passes on the way, in order.
     */
    data class Success(val points: List<Vec3>, val lengthMeters: Float, val via: List<String>) : ConnectionRouteResult

    data class Failure(val reason: Reason) : ConnectionRouteResult

    enum class Reason(val message: String) {
        START_NOT_CONNECTED("Your starting point isn't on any recorded path. Scan a reference point that was connected by walking."),
        DESTINATION_NOT_CONNECTED("This destination isn't on any recorded path, so there is no route to it yet."),
        NOT_CONNECTED("No recorded path connects your starting point to this destination."),
        DIFFERENT_LEVEL("This destination is on another floor. Routes between floors aren't supported."),
    }
}

/**
 * Finds the shortest walkable route between a reference point (or the user's position) and a destination, over the
 * paths the admin recorded.
 *
 * The recorded walks form the navigation network: every recorded point is a node, linked to the next point of the
 * same walk and to points of other walks within [WalkedPathRouter.crossLinkDistance] (where walks meet), each link
 * weighted by its length. A reference point is connected to another wherever a walk runs between them, so a route
 * from RP1 to a room behind RP3 runs RP1 → RP2 → RP3 → room through those walks, never through a wall. The search is
 * A* (Dijkstra guided by the straight-line distance to the goal, which never overestimates the walking distance, so
 * the first route found is the shortest). There is deliberately no straight-line fallback: an unconnected
 * destination is reported, not drawn.
 */
class ConnectionRoutePlanner(
    private val walked: List<WalkedPoint>,
    /** Named places (reference points) to report along the route, with their venue positions. */
    private val landmarks: List<Pair<String, Vec3>> = emptyList(),
) {
    fun route(from: RouteEndpoint, to: RouteEndpoint): ConnectionRouteResult {
        if (from.level.isNotEmpty() && to.level.isNotEmpty() && from.level != to.level) {
            return ConnectionRouteResult.Failure(ConnectionRouteResult.Reason.DIFFERENT_LEVEL)
        }
        val level = to.level.ifEmpty { from.level }
        val points = walked.filter { level.isEmpty() || it.level == level }
        if (points.isEmpty()) return ConnectionRouteResult.Failure(ConnectionRouteResult.Reason.START_NOT_CONNECTED)

        // Both ends sit on the floor of the walk they join, so the route is drawn on the floor (an object's own height,
        // a table say, is not the floor).
        val start = onFloor(from, points.filter(from::joins))
            ?: return ConnectionRouteResult.Failure(ConnectionRouteResult.Reason.START_NOT_CONNECTED)
        val goal = onFloor(to, points.filter(to::joins))
            ?: return ConnectionRouteResult.Failure(ConnectionRouteResult.Reason.DESTINATION_NOT_CONNECTED)

        val router = WalkedPathRouter(startJoinDistance = from.joinDistance, destinationJoinDistance = to.joinDistance)
        return when (val result = router.route(start, goal, points, from::joins, to::joins)) {
            is WalkedRouteResult.Failure -> ConnectionRouteResult.Failure(
                when (result.error) {
                    WalkedRouteError.START_NOT_ON_PATH -> ConnectionRouteResult.Reason.START_NOT_CONNECTED
                    WalkedRouteError.DESTINATION_NOT_ON_PATH -> ConnectionRouteResult.Reason.DESTINATION_NOT_CONNECTED
                    WalkedRouteError.NOT_CONNECTED -> ConnectionRouteResult.Reason.NOT_CONNECTED
                },
            )
            is WalkedRouteResult.Success -> ConnectionRouteResult.Success(
                result.points, lengthOf(result.points), landmarksAlong(result.points, exclude = setOf(from.name, to.name)),
            )
        }
    }

    /** [endpoint]'s position at the height of the nearest of [candidates] within its join distance, or null. */
    private fun onFloor(endpoint: RouteEndpoint, candidates: List<WalkedPoint>): Vec3? {
        val nearest = candidates.minByOrNull { horizontalDistance(it.position, endpoint.position) } ?: return null
        if (horizontalDistance(nearest.position, endpoint.position) > endpoint.joinDistance) return null
        return Vec3(endpoint.position.x, nearest.y, endpoint.position.z)
    }

    /** The landmarks within [VIA_DISTANCE_M] of the route, in the order the route passes them. */
    private fun landmarksAlong(route: List<Vec3>, exclude: Set<String>): List<String> {
        if (route.size < 2) return emptyList()
        val cumulative = FloatArray(route.size)
        for (i in 1 until route.size) cumulative[i] = cumulative[i - 1] + horizontalDistance(route[i - 1], route[i])
        return landmarks.mapNotNull { (name, position) ->
            if (name in exclude) return@mapNotNull null
            var best = Float.MAX_VALUE
            var along = 0f
            for (i in 0 until route.size - 1) {
                val (distance, t) = distanceToSegment(position, route[i], route[i + 1])
                if (distance < best) {
                    best = distance
                    along = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
                }
            }
            if (best <= VIA_DISTANCE_M) name to along else null
        }.sortedBy { it.second }.map { it.first }.distinct()
    }

    companion object {
        /** A landmark this close to the route (meters) is passed on the way. */
        const val VIA_DISTANCE_M = 1.5f

        /** The walking length of [route] (meters, along the floor). */
        fun lengthOf(route: List<Vec3>): Float =
            route.zipWithNext().sumOf { (a, b) -> horizontalDistance(a, b).toDouble() }.toFloat()

        /** Horizontal distance from [p] to segment [a]–[b], and how far along the segment (0–1) the closest point is. */
        private fun distanceToSegment(p: Vec3, a: Vec3, b: Vec3): Pair<Float, Float> {
            val dx = b.x - a.x
            val dz = b.z - a.z
            val lengthSquared = dx * dx + dz * dz
            val t = if (lengthSquared > 0f) (((p.x - a.x) * dx + (p.z - a.z) * dz) / lengthSquared).coerceIn(0f, 1f) else 0f
            return hypot(p.x - (a.x + dx * t), p.z - (a.z + dz * t)) to t
        }
    }
}
