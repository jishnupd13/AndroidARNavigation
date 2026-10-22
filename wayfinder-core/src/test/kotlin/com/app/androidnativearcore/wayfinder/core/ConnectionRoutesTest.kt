package com.app.androidnativearcore.wayfinder.core

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shortest routes over the recorded walks, checked against small hand-made venues whose answers are known.
 * Coordinates are meters on floor level L0 (y = 0).
 */
class ConnectionRoutesTest {
    /** A recorded walk through [corners], with a point every 0.4 m, for the saved destination [destinationId]. */
    private fun walk(run: String, destinationId: String, vararg corners: Pair<Float, Float>): List<WalkedPoint> {
        val out = mutableListOf<WalkedPoint>()
        var sequence = 0
        for ((a, b) in corners.toList().zipWithNext()) {
            val length = hypot(b.first - a.first, b.second - a.second)
            val steps = maxOf(1, (length / 0.4f).toInt())
            for (i in 0 until steps) {
                val t = i / steps.toFloat()
                out += WalkedPoint(run, sequence++, "L0", a.first + (b.first - a.first) * t, 0f, a.second + (b.second - a.second) * t, destinationId)
            }
        }
        val last = corners.last()
        out += WalkedPoint(run, sequence, "L0", last.first, 0f, last.second, destinationId)
        return out
    }

    private fun at(name: String, x: Float, z: Float, join: Float = 2f, own: String? = null, y: Float = 0f) =
        RouteEndpoint(name, Vec3(x, y, z), "L0", join, own)

    private val rp1 = "RP1" to Vec3(0f, 1.4f, 0f)
    private val rp2 = "RP2" to Vec3(10f, 1.4f, 0f)
    private val rp3 = "RP3" to Vec3(10f, 1.4f, 10f)

    private fun success(result: ConnectionRouteResult): ConnectionRouteResult.Success {
        assertTrue("expected a route, got $result", result is ConnectionRouteResult.Success)
        return result as ConnectionRouteResult.Success
    }

    @Test
    fun aDirectConnectionIsFollowed() {
        val planner = ConnectionRoutePlanner(walk("a", "room", 0f to 0f, 10f to 0f), listOf(rp1))
        val route = success(planner.route(at("RP1", 0f, 0f), at("Conference Room", 10f, 0f)))
        assertEquals(10f, route.lengthMeters, 0.05f)
        assertEquals(emptyList<String>(), route.via)
    }

    @Test
    fun theShorterOfTwoConnectionsIsChosen() {
        val short = walk("short", "room", 0f to 0f, 10f to 0f)
        val long = walk("long", "room", 0f to 0f, 0f to 8f, 10f to 8f, 10f to 0f)
        val route = success(ConnectionRoutePlanner(long + short).route(at("RP1", 0f, 0f), at("Room", 10f, 0f)))
        assertEquals(10f, route.lengthMeters, 0.05f)
    }

    @Test
    fun aRouteRunsThroughIntermediateReferencePointsInOrder() {
        // RP1 → RP2 → RP3 → Conference Room were connected one by one. The room is only 10 m from RP1 in a straight
        // line (through a wall), but the walkable route is 30 m.
        val walks = walk("1-2", "c12", 0f to 0f, 10f to 0f) +
            walk("2-3", "c23", 10f to 0f, 10f to 10f) +
            walk("3-room", "room", 10f to 10f, 0f to 10f)
        val planner = ConnectionRoutePlanner(walks, listOf(rp1, rp2, rp3))
        val route = success(planner.route(at("RP1", 0f, 0f), at("Conference Room", 0f, 10f, join = 1.5f)))
        // 30 m along the walks (a little less where the route cuts the corners at RP2 and RP3, inside the 0.6 m
        // where two walks meet), and nowhere near the 10 m straight line.
        assertEquals(30f, route.lengthMeters, 0.6f)
        assertEquals(listOf("RP2", "RP3"), route.via)
        // Every point of the route lies on a recorded walk, never on the straight line through the wall.
        assertTrue(route.points.all { p -> walks.any { hypot(it.x - p.x, it.z - p.z) < 0.5f } })
    }

    @Test
    fun anUnconnectedDestinationIsReportedNotDrawn() {
        val walks = walk("1-2", "c12", 0f to 0f, 10f to 0f) + walk("4-room", "room", 0f to 20f, 10f to 20f)
        val result = ConnectionRoutePlanner(walks).route(at("RP1", 0f, 0f), at("Room", 10f, 20f))
        assertEquals(ConnectionRouteResult.Failure(ConnectionRouteResult.Reason.NOT_CONNECTED), result)
    }

    @Test
    fun duplicateConnectionsChangeNothing() {
        val once = walk("a", "room", 0f to 0f, 10f to 0f)
        val twice = once + walk("a-again", "room", 0f to 0f, 10f to 0f)
        val route = success(ConnectionRoutePlanner(twice).route(at("RP1", 0f, 0f), at("Room", 10f, 0f)))
        assertEquals(10f, route.lengthMeters, 0.05f)
    }

    @Test
    fun anObjectJoinsTheWalkItWasPlacedFrom() {
        // The printer (on a table, 1 m up) was placed while walking B along z = 0, 2.5 m off the walk. Walk C runs
        // just behind a wall 0.5 m from it and is not connected to B.
        val walkB = walk("b", "dest-b", 0f to 0f, 10f to 0f)
        val walkC = walk("c", "dest-c", 0f to 3f, 10f to 3f)
        val planner = ConnectionRoutePlanner(walkB + walkC)
        val printer = at("Printer", 5f, 2.5f, join = 6.5f, own = "dest-b", y = 1f)
        val route = success(planner.route(at("RP1", 0f, 0f), printer))
        // Along walk B, then across to the printer: about 4.4 m + 2.6 m (it leaves the walk where that is shortest).
        assertEquals(7f, route.lengthMeters, 0.1f)
        assertEquals("the route ends on the floor, not at table height", 0f, route.points.last().y, 1e-4f)
        // Joined to whatever walk is nearest, it would be attached through the wall to C, which RP1 can't reach.
        val careless = planner.route(at("RP1", 0f, 0f), at("Printer", 5f, 2.5f, join = 6.5f))
        assertEquals(ConnectionRouteResult.Failure(ConnectionRouteResult.Reason.NOT_CONNECTED), careless)
    }

    @Test
    fun aStartAwayFromEveryPathIsReported() {
        val result = ConnectionRoutePlanner(walk("a", "room", 0f to 0f, 10f to 0f)).route(at("RP9", 0f, 20f), at("Room", 10f, 0f))
        assertEquals(ConnectionRouteResult.Failure(ConnectionRouteResult.Reason.START_NOT_CONNECTED), result)
    }

    @Test
    fun aDestinationOnAnotherFloorIsReported() {
        val planner = ConnectionRoutePlanner(walk("a", "room", 0f to 0f, 10f to 0f))
        val result = planner.route(at("RP1", 0f, 0f), RouteEndpoint("Upstairs", Vec3(5f, 0f, 0f), "L1", 2f))
        assertEquals(ConnectionRouteResult.Failure(ConnectionRouteResult.Reason.DIFFERENT_LEVEL), result)
    }
}
