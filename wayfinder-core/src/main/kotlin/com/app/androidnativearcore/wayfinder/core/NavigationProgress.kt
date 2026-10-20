package com.app.androidnativearcore.wayfinder.core

import kotlin.math.max
import kotlin.math.min

/** Where the user is relative to a route, computed from their position in venue coordinates. */
data class NavigationProgress(
    /** Distance travelled along the route, in meters. */
    val distanceAlongRoute: Float,
    /** Distance left to the destination, in meters. */
    val remainingDistance: Float,
    /** Horizontal distance from the user to the closest point of the route on their level. */
    val distanceFromRoute: Float,
    /** The next instruction ahead of the user (the arrival maneuver near the end). */
    val nextManeuver: Maneuver?,
    /** Distance from the user to the next maneuver, in meters. */
    val distanceToNextManeuver: Float,
    val isOffRoute: Boolean,
    val hasArrived: Boolean,
)

/** Tracks progress along a route. */
class NavigationTracker(val route: Route) {
    /** Distance from the route beyond which the user counts as off-route. */
    var offRouteThreshold: Float = 3f

    /** Distance from the destination within which the user has arrived. */
    var arrivalRadius: Float = 2f

    /** Progress for a user at [position] (floor height or device height; only x/z are compared) on [level]. */
    fun progress(position: Vec3, level: String): NavigationProgress {
        val points = route.points
        val destinationDistance = horizontalDistance(position, route.destination)
        val onDestinationLevel = route.levels.lastOrNull() == level

        // Closest point on any route segment that lies on the user's level.
        var best: Pair<Float, Float>? = null // (distance, along)
        for (index in 0 until max(points.size - 1, 0)) {
            if (route.levels[index] != level || route.levels[index + 1] != level) continue
            val a = Vec2(points[index].x, points[index].z)
            val b = Vec2(points[index + 1].x, points[index + 1].z)
            val p = Vec2(position.x, position.z)
            val segment = b - a
            val lengthSquared = segment dot segment
            val t = if (lengthSquared > 0) max(0f, min(1f, ((p - a) dot segment) / lengthSquared)) else 0f
            val closest = a + segment * t
            val distance = p.distanceTo(closest)
            val along = route.cumulativeDistances[index] +
                (route.cumulativeDistances[index + 1] - route.cumulativeDistances[index]) * t
            if (best == null || distance < best.first) best = distance to along
        }
        // A single-point route (already at the destination) or a level with no route segments.
        if (best == null) {
            val index = route.levels.indexOf(level)
            if (index >= 0) best = horizontalDistance(position, points[index]) to route.cumulativeDistances[index]
        }

        val along = best?.second ?: 0f
        val distanceFromRoute = best?.first ?: Float.POSITIVE_INFINITY
        // Close to the destination in a straight line is not enough: the user must also be at the END of the route. A
        // path that starts (or passes) near its destination, or loops back to it, must not count as arrived on the way.
        val remaining = max(0f, route.length - along)
        val hasArrived = onDestinationLevel && destinationDistance <= arrivalRadius && remaining <= arrivalRadius + ARRIVAL_ROUTE_SLACK_M
        val next = route.maneuvers.firstOrNull { it.kind != Maneuver.Kind.Start && it.distanceFromStart > along + 0.5f }
            ?: route.maneuvers.lastOrNull()

        return NavigationProgress(
            distanceAlongRoute = along,
            remainingDistance = remaining,
            distanceFromRoute = distanceFromRoute,
            nextManeuver = next,
            distanceToNextManeuver = max(0f, (next?.distanceFromStart ?: route.length) - along),
            isOffRoute = !hasArrived && distanceFromRoute > offRouteThreshold,
            hasArrived = hasArrived,
        )
    }

    private companion object {
        /** How much farther along the route than [arrivalRadius] the user may still be and count as having arrived. */
        const val ARRIVAL_ROUTE_SLACK_M = 1.5f
    }
}
