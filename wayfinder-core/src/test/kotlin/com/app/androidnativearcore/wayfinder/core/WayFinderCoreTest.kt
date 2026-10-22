package com.app.androidnativearcore.wayfinder.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two levels, 5 m apart:
 *
 *     Level 0                         Level 1
 *     e0 (0,5) ─ elevator ─────────── e1 (0,5) ── x1 (0,10) ── d1 (10,10)  ← Shop
 *      │                                                         │
 *     a (0,0) ── b (10,0) ── stairs ── b1 (10,3) ────────────────┘
 *                  │
 *                 c (10,10)
 */
private fun makeVenue() = Venue(
    id = "test",
    name = LocalizedText(mapOf("en" to "Test Mall", "ar" to "مول الاختبار")),
    levels = listOf(
        Venue.Level("L0", LocalizedText("Ground"), 0f),
        Venue.Level("L1", LocalizedText("First"), 5f),
    ),
    nodes = listOf(
        Venue.Node("a", "L0", 0f, 0f),
        Venue.Node("b", "L0", 10f, 0f),
        Venue.Node("c", "L0", 10f, 10f),
        Venue.Node("e0", "L0", 0f, 5f),
        Venue.Node("e1", "L1", 0f, 5f),
        Venue.Node("x1", "L1", 0f, 10f),
        Venue.Node("b1", "L1", 10f, 3f),
        Venue.Node("d1", "L1", 10f, 10f),
    ),
    edges = listOf(
        Venue.Edge("a", "b"),
        Venue.Edge("b", "c"),
        Venue.Edge("a", "e0"),
        Venue.Edge("e0", "e1", type = Venue.EdgeType.ELEVATOR),
        Venue.Edge("e1", "x1"),
        Venue.Edge("x1", "d1"),
        Venue.Edge("b", "b1", type = Venue.EdgeType.STAIRS),
        Venue.Edge("b1", "d1"),
    ),
    pois = listOf(Venue.POI("shop", LocalizedText("Shop 101"), "shop", "L1", 10f, 10f, "d1")),
    markers = listOf(
        Venue.Marker("m", LocalizedText("Entrance"), "markers/m.png", 0.2f, "L0", 0f, 1.5f, -1f, 0f),
    ),
)

private fun assertNear(expected: Float, actual: Float, accuracy: Float = 1e-4f) =
    assertEquals(expected.toDouble(), actual.toDouble(), accuracy.toDouble())

class LocalizedTextTest {
    @Test
    fun resolvesPreferredLanguageWithRegionFallback() {
        val text = LocalizedText(mapOf("en" to "Entrance", "ar" to "المدخل"))
        assertEquals("المدخل", text.resolved(listOf("ar-QA", "en")))
        assertEquals("Entrance", text.resolved(listOf("fr")))
    }

    @Test
    fun decodesPlainStringAsEnglish() {
        val text = Json.decodeFromString(LocalizedText.serializer(), "\"Gate 5\"")
        assertEquals("Gate 5", text.resolved(listOf("en")))
    }
}

class VenueTest {
    @Test
    fun validVenueHasNoIssues() {
        assertEquals(emptyList<String>(), makeVenue().validationIssues())
    }

    @Test
    fun reportsUnknownReferencesAndDuplicates() {
        val venue = makeVenue().let {
            it.copy(
                edges = it.edges + Venue.Edge("a", "missing"),
                nodes = it.nodes + Venue.Node("a", "L9", 0f, 0f),
            )
        }
        val issues = venue.validationIssues()
        assertTrue(issues.contains("Duplicate node id 'a'."))
        assertTrue(issues.contains("Edge references unknown node 'missing'."))
        assertTrue(issues.contains("Node 'a' is on unknown level 'L9'."))
    }

    @Test
    fun decodesJsonWithDefaults() {
        val json = """
        {"id": "v", "name": "Venue",
         "levels": [{"id": "L0", "name": "Ground", "elevation": 0}],
         "nodes": [{"id": "n1", "level": "L0", "x": 0, "z": 0}, {"id": "n2", "level": "L0", "x": 1, "z": 0}],
         "edges": [{"from": "n1", "to": "n2"}]}
        """
        val venue = Json.decodeFromString(Venue.serializer(), json)
        assertEquals(Venue.EdgeType.WALKWAY, venue.edges.first().type)
        assertFalse(venue.edges.first().closed)
        assertEquals(0, venue.pois.size)
        assertEquals(1, venue.formatVersion)
    }

    @Test
    fun levelForDeviceHeight() {
        val venue = makeVenue()
        assertEquals("L0", venue.level(forDeviceHeight = 1.4f)?.id)
        assertEquals("L1", venue.level(forDeviceHeight = 6.3f)?.id)
    }
}

class RoutePlannerTest {
    private val planner = RoutePlanner(makeVenue())

    @Test
    fun fastestRouteUsesStairs() {
        val route = planner.route("a", toPOI = "shop", RouteProfile.FASTEST)
        assertEquals(listOf("a", "b", "b1", "d1"), route.nodeIDs)
        assertTrue(route.maneuvers.any { it.kind == Maneuver.Kind.ChangeLevel(Venue.EdgeType.STAIRS, "L1") })
    }

    @Test
    fun stepFreeRouteUsesElevator() {
        val route = planner.route("a", toPOI = "shop", RouteProfile.STEP_FREE)
        assertEquals(listOf("a", "e0", "e1", "x1", "d1"), route.nodeIDs)
        assertTrue(route.maneuvers.any { it.kind == Maneuver.Kind.ChangeLevel(Venue.EdgeType.ELEVATOR, "L1") })
        assertFalse(route.maneuvers.any { it.kind == Maneuver.Kind.ChangeLevel(Venue.EdgeType.STAIRS, "L1") })
    }

    @Test
    fun closedEdgeIsAvoided() {
        val venue = makeVenue().let { v ->
            v.copy(edges = v.edges.map { if (it.type == Venue.EdgeType.STAIRS) it.copy(closed = true) else it })
        }
        val route = RoutePlanner(venue).route("a", toPOI = "shop", RouteProfile.FASTEST)
        assertEquals(listOf("a", "e0", "e1", "x1", "d1"), route.nodeIDs)
    }

    @Test
    fun noRouteWhenEverythingIsClosed() {
        val venue = makeVenue().let { v -> v.copy(edges = v.edges.map { if (it.from == "a") it.copy(closed = true) else it }) }
        val error = assertThrows(RoutingError::class.java) {
            RoutePlanner(venue).route("a", toPOI = "shop", RouteProfile.FASTEST)
        }
        assertEquals(RoutingError.NoRoute, error)
    }

    @Test
    fun oneWayEdge() {
        val venue = makeVenue().copy(edges = listOf(Venue.Edge("a", "b", oneWay = true)))
        val planner = RoutePlanner(venue)
        planner.routeToNode("a", "b", RouteProfile.FASTEST)
        assertThrows(RoutingError::class.java) { planner.routeToNode("b", "a", RouteProfile.FASTEST) }
    }

    @Test
    fun turnDirections() {
        // a (0,0) -> b (10,0) -> c (10,10): walking +x then +z is a right turn (y up, viewed from above).
        val right = planner.routeToNode("a", "c", RouteProfile.FASTEST)
        assertEquals(listOf(Maneuver.Kind.Start, Maneuver.Kind.TurnRight, Maneuver.Kind.Arrive), right.maneuvers.map { it.kind })
        assertNear(20f, right.length, 0.001f)
        assertNear(10f, right.maneuvers[1].distanceFromStart, 0.001f)

        val left = planner.routeToNode("c", "a", RouteProfile.FASTEST)
        assertEquals(listOf(Maneuver.Kind.Start, Maneuver.Kind.TurnLeft, Maneuver.Kind.Arrive), left.maneuvers.map { it.kind })
    }

    @Test
    fun routeFromPositionStartsAtNearestNode() {
        val route = planner.route(Vec3(9f, 1.4f, 1f), "L0", toPOI = "shop", RouteProfile.FASTEST)
        assertEquals("b", route.nodeIDs.first())
    }

    @Test
    fun elevatorRideAddsNoWalkingDistance() {
        val route = planner.route("a", toPOI = "shop", RouteProfile.STEP_FREE)
        // 5 (a-e0) + 0 (elevator) + 5 (e1-x1) + 10 (x1-d1)
        assertNear(20f, route.length, 0.001f)
    }
}

class NavigationTrackerTest {
    private val route = RoutePlanner(makeVenue()).routeToNode("a", "c", RouteProfile.FASTEST)

    @Test
    fun progressAlongRoute() {
        val progress = NavigationTracker(route).progress(Vec3(4f, 1.4f, 0.5f), "L0")
        assertNear(4f, progress.distanceAlongRoute, 0.001f)
        assertNear(16f, progress.remainingDistance, 0.001f)
        assertNear(0.5f, progress.distanceFromRoute, 0.001f)
        assertEquals(Maneuver.Kind.TurnRight, progress.nextManeuver?.kind)
        assertNear(6f, progress.distanceToNextManeuver, 0.001f)
        assertFalse(progress.isOffRoute)
        assertFalse(progress.hasArrived)
    }

    @Test
    fun offRoute() {
        assertTrue(NavigationTracker(route).progress(Vec3(4f, 0f, 6f), "L0").isOffRoute)
    }

    @Test
    fun offRouteOnWrongLevel() {
        assertTrue(NavigationTracker(route).progress(Vec3(4f, 5f, 0f), "L1").isOffRoute)
    }

    @Test
    fun aPathThatLoopsBackNearItsDestinationDoesNotArriveEarly() {
        // Walk 8 m out and 8 m back along a U: the destination (end) is 1 m from the start.
        val polyline = listOf(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 8f), Vec3(1f, 0f, 8f), Vec3(1f, 0f, 0f))
        val loop = RoutePlanner(makeVenue()).routeAlong(polyline, "L0", RouteProfile.FASTEST)
        val tracker = NavigationTracker(loop)
        // Standing at the start the destination is only 1 m away in a straight line, but 17 m of walking remain.
        val atStart = tracker.progress(Vec3(0f, 0f, 0f), "L0")
        assertFalse("must not arrive at the start", atStart.hasArrived)
        assertTrue(atStart.remainingDistance > 15f)
        // At the real end it does arrive.
        assertTrue(tracker.progress(Vec3(1f, 0f, 0.3f), "L0").hasArrived)
    }

    @Test
    fun theArrivalRadiusCanBeTightened() {
        val tracker = NavigationTracker(route).apply { arrivalRadius = 1f }
        assertFalse(tracker.progress(Vec3(10f, 0f, 8.5f), "L0").hasArrived) // 1.5 m before the end: not yet
        assertTrue(tracker.progress(Vec3(10f, 0f, 9.4f), "L0").hasArrived)  // 0.6 m before it: arrived
    }

    @Test
    fun arrival() {
        val progress = NavigationTracker(route).progress(Vec3(10f, 0f, 9f), "L0")
        assertTrue(progress.hasArrived)
        assertFalse(progress.isOffRoute)
        assertEquals(Maneuver.Kind.Arrive, progress.nextManeuver?.kind)
    }
}

private fun assertVec(expected: Vec3, actual: Vec3) {
    // Compare numerically: data-class equality would tell -0.0f from 0.0f.
    assertNear(expected.x, actual.x, 1e-6f); assertNear(expected.y, actual.y, 1e-6f); assertNear(expected.z, actual.z, 1e-6f)
}

class MarkerAlignmentTest {
    private fun assertMatrix(a: Mat4, b: Mat4, accuracy: Float = 1e-4f) {
        for (column in 0 until 4) for (row in 0 until 4) {
            assertEquals("[$column][$row]", b[column, row].toDouble(), a[column, row].toDouble(), accuracy.toDouble())
        }
    }

    @Test
    fun wallMarkerFrameMatchesImageAnchorConvention() {
        // Sign facing +z: normal +z, image right +x, image bottom -y.
        val t = MarkerAlignment.transform(makeVenue().markers[0])
        assertVec(Vec3(1f, 0f, 0f), t.column(0))
        assertVec(Vec3(0f, 0f, 1f), t.column(1))
        assertVec(Vec3(0f, -1f, 0f), t.column(2))
        assertVec(Vec3(0f, 1.5f, -1f), t.translation)
    }

    @Test
    fun floorMarkerFrame() {
        val marker = makeVenue().markers[0].copy(mounting = Venue.MarkerMounting.FLOOR, facing = 90f) // top points to +x
        val t = MarkerAlignment.transform(marker)
        assertNear(1f, t[1, 1], 1e-6f)    // normal up
        assertNear(-1f, t[2, 0], 1e-6f)   // bottom towards -x
        assertNear(1f, kotlin.math.abs(t.determinant), 1e-5f)
    }

    @Test
    fun alignmentRecoversVenueOrigin() {
        val markers = listOf(
            makeVenue().markers[0],
            makeVenue().markers[0].copy(mounting = Venue.MarkerMounting.FLOOR, facing = 210f, x = 3f),
        )
        for (marker in markers) {
            val markerInVenue = MarkerAlignment.transform(marker)
            val venueInSession = Mat4.yaw(37f, Vec3(2f, -0.3f, 5f))
            val markerInSession = venueInSession * markerInVenue
            assertMatrix(MarkerAlignment.alignment(markerInSession, markerInVenue), venueInSession)
        }
    }

    @Test
    fun alignmentRemovesDetectionTilt() {
        val markerInVenue = MarkerAlignment.transform(makeVenue().markers[0])
        val venueInSession = Mat4.yaw(-120f, Vec3(1f, 0f, -4f))
        // 2° of tilt around the marker's own x axis, as detection noise.
        val result = MarkerAlignment.alignment(venueInSession * markerInVenue * Mat4.pitch(2f), markerInVenue)
        assertNear(1f, result[1, 1], 1e-6f) // still level
        assertTrue(MarkerAlignment.difference(result, venueInSession).degrees < 0.5f)
    }

    @Test
    fun aSignLeaningBackDoesNotShiftTheVenue() {
        // A wall sign 1.4 m up, but the real sign leans back 15° (a laptop screen). The venue must still land where the
        // sign's center says it is: tilting the sign about its own center cannot move the center.
        val markerInVenue = MarkerAlignment.transform(makeVenue().markers[0])
        val venueInSession = Mat4.yaw(25f, Vec3(2f, 0f, -3f))
        val leaning = MarkerAlignment.alignment(venueInSession * markerInVenue * Mat4.pitch(15f), markerInVenue)
        val upright = MarkerAlignment.alignment(venueInSession * markerInVenue, markerInVenue)
        assertTrue("tilt moved the venue by ${leaning.translation.distanceTo(upright.translation)} m",
            leaning.translation.distanceTo(upright.translation) < 0.005f)
        assertTrue(leaning.translation.distanceTo(venueInSession.translation) < 0.02f)
        // And the yaw is unaffected by the lean.
        assertTrue(MarkerAlignment.difference(leaning, venueInSession).degrees < 0.5f)
        assertNear(1f, leaning[1, 1], 1e-6f) // still level
    }

    @Test
    fun difference() {
        val d = MarkerAlignment.difference(Mat4.yaw(0f), Mat4.yaw(10f, Vec3(0f, 0f, 2f)))
        assertNear(10f, d.degrees, 0.01f)
        assertNear(2f, d.meters, 0.001f)
    }

    @Test
    fun inverseRoundTrips() {
        val m = Mat4.yaw(37f, Vec3(2f, -0.3f, 5f))
        assertMatrix(m * m.inverse(), Mat4.IDENTITY)
    }
}

class VenuePackageTest {
    @Test
    fun loadsFolderAndReportsMissingImages() {
        val folder = Files.createTempDirectory("venue").toFile()
        try {
            File(folder, "markers").mkdirs()
            File(folder, "venue.json").writeText(Json.encodeToString(Venue.serializer(), makeVenue()))

            val error = assertThrows(VenuePackage.LoadError::class.java) { VenuePackage.load(folder) }
            assertTrue(error.message!!.contains("markers/m.png"))

            File(folder, "markers/m.png").writeBytes(byteArrayOf(0))
            val pkg = VenuePackage.load(folder)
            assertEquals("test", pkg.venue.id)
            assertEquals("m.png", pkg.file(pkg.venue.markers[0]).name)
        } finally {
            folder.deleteRecursively()
        }
    }
}

/**
 * Checks the bundled demo venue used by the demo app. It ships without destinations (users add their own), so
 * routing is checked between its walkable-network nodes.
 */
class DemoVenueTest {
    private val demoVenueDir = File("../app/src/main/assets/DemoMall.venue")

    @Test
    fun demoVenueLoadsWithoutBuiltInDestinations() {
        val pkg = VenuePackage.load(demoVenueDir)
        assertTrue(pkg.venue.pois.isEmpty())
        assertTrue(pkg.venue.markers.isNotEmpty())
    }

    @Test
    fun everyNodeIsReachableFromTheEntrance() {
        val planner = RoutePlanner(VenuePackage.load(demoVenueDir).venue)
        for (node in planner.venue.nodes) for (profile in RouteProfile.entries) {
            assertNotNull("${node.id} ($profile)", planner.routeToNode("entrance", node.id, profile))
        }
    }

    @Test
    fun demoVenueStepFreeRouteToCinemaUsesLift() {
        val planner = RoutePlanner(VenuePackage.load(demoVenueDir).venue)
        val fastest = planner.routeToNode("entrance", "cinema", RouteProfile.FASTEST)
        val stepFree = planner.routeToNode("entrance", "cinema", RouteProfile.STEP_FREE)
        assertTrue(fastest.maneuvers.any { it.kind == Maneuver.Kind.ChangeLevel(Venue.EdgeType.ESCALATOR, "L1") })
        assertTrue(stepFree.maneuvers.any { it.kind == Maneuver.Kind.ChangeLevel(Venue.EdgeType.ELEVATOR, "L1") })
    }
}

class CustomDestinationTest {
    private val destination = CustomDestination("c1", "Sofa", "L0", 9f, 1f, createdAtMillis = 123L)

    @Test
    fun customDestinationBecomesAPOI() {
        val venue = makeVenue().withCustomDestinations(listOf(destination))
        assertEquals(CustomDestination.CATEGORY, venue.poi("c1")?.category)
        assertEquals("Sofa", venue.poi("c1")?.name?.resolved(listOf("en")))
    }

    @Test
    fun destinationPinUsesTheRecordedFloorHeight() {
        val venue = makeVenue().withCustomDestinations(listOf(
            destination.copy(floorY = 0.35f),
            destination.copy(id = "c-old", name = "Old"), // saved before floor heights were recorded
        ))
        assertNear(0.35f, venue.position(venue.poi("c1")!!).y)
        assertNear(0f, venue.position(venue.poi("c-old")!!).y) // falls back to the level's elevation
    }

    @Test
    fun invalidOrDuplicateEntriesAreSkipped() {
        val venue = makeVenue().withCustomDestinations(listOf(
            destination.copy(id = "bad-level", level = "L9"),
            destination.copy(id = "shop"), // clashes with an existing POI id
            destination,
        ))
        assertEquals(setOf("shop", "c1"), venue.pois.map { it.id }.toSet())
        assertEquals("Shop 101", venue.poi("shop")?.name?.resolved(listOf("en")))
    }
}

class WalkedPathTest {
    private fun pt(run: String, seq: Int, x: Float, z: Float, y: Float = 0f, level: String = "L0") =
        WalkedPoint(run, seq, level, x, y, z)

    /** A walk along +z at x = 0, every 0.5 m. */
    private fun straight(run: String = "r1", length: Float = 10f) =
        (0..(length / 0.5f).toInt()).map { pt(run, it, 0f, it * 0.5f) }

    private fun success(result: WalkedRouteResult) = (result as WalkedRouteResult.Success).points
    private fun failure(result: WalkedRouteResult) = (result as WalkedRouteResult.Failure).error

    @Test
    fun routesAlongTheWalkedLine() {
        val route = success(WalkedPathRouter().route(Vec3(0f, 0f, 0.2f), Vec3(0.3f, 0f, 9.8f), straight()))
        assertNear(0.2f, route.first().z, 0.01f)
        assertNear(9.8f, route.last().z, 0.01f)
        // A straight walk simplifies to a few points, all on the line x = 0 (the endpoints are the user and goal).
        assertTrue(route.size <= 4)
        assertTrue(route.drop(1).dropLast(1).all { kotlin.math.abs(it.x) < 0.01f })
    }

    @Test
    fun goesAroundTheCornerInsteadOfThroughTheWall() {
        // Walked: +z for 5 m, then +x for 5 m. The straight line start->goal would cut the corner through the wall.
        val walk = (0..10).map { pt("r1", it, 0f, it * 0.5f) } + (1..10).map { pt("r1", 10 + it, it * 0.5f, 5f) }
        val route = success(WalkedPathRouter().route(Vec3(0f, 0f, 0f), Vec3(5f, 0f, 5f), walk))
        val corner = route.minOf { Vec2(it.x, it.z).distanceTo(Vec2(0f, 5f)) }
        assertTrue("route passes the corner, was $corner away", corner < 0.3f)
        assertNear(10f, Route(
            nodeIDs = emptyList(), points = route, levels = emptyList(), cumulativeDistances = listOf(0f),
            maneuvers = emptyList(), profile = RouteProfile.FASTEST,
        ).let { route.zipWithNext().sumOf { (a, b) -> a.distanceTo(b).toDouble() }.toFloat() }, 0.2f)
    }

    @Test
    fun failsWhenStartOrDestinationIsFarFromThePath() {
        val router = WalkedPathRouter()
        assertEquals(WalkedRouteError.START_NOT_ON_PATH, failure(router.route(Vec3(20f, 0f, 20f), Vec3(0f, 0f, 5f), straight())))
        assertEquals(WalkedRouteError.DESTINATION_NOT_ON_PATH, failure(router.route(Vec3(0f, 0f, 0f), Vec3(20f, 0f, 20f), straight())))
        assertEquals(WalkedRouteError.START_NOT_ON_PATH, failure(router.route(Vec3.ZERO, Vec3(1f, 0f, 1f), emptyList())))
    }

    @Test
    fun separateWalksOnlyConnectWhereTheyCross() {
        val a = straight("a", 6f)                                   // x = 0, z 0..6
        val farAway = (0..12).map { pt("b", it, 5f, it * 0.5f) }    // x = 5, never touches a
        assertEquals(WalkedRouteError.NOT_CONNECTED,
            failure(WalkedPathRouter().route(Vec3(0f, 0f, 0f), Vec3(5f, 0f, 6f), a + farAway)))
        // A third walk crossing both joins them.
        val bridge = (0..10).map { pt("c", it, it * 0.5f, 3f) }     // z = 3, x 0..5
        assertNotNull(WalkedPathRouter().route(Vec3(0f, 0f, 0f), Vec3(5f, 0f, 6f), a + farAway + bridge) as? WalkedRouteResult.Success)
    }

    @Test
    fun trackingJumpsAreNotLinked() {
        // The walk teleports 30 m: the two halves must not be joined by a straight 30 m link.
        val walk = (0..6).map { pt("r1", it, 0f, it * 0.5f) } + (7..12).map { pt("r1", it, 30f, it * 0.5f) }
        assertEquals(WalkedRouteError.NOT_CONNECTED, failure(WalkedPathRouter().route(Vec3(0f, 0f, 0f), Vec3(30f, 0f, 6f), walk)))
    }

    @Test
    fun routeAlongGivesTurnByTurnManeuvers() {
        val polyline = listOf(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 5f), Vec3(5f, 0f, 5f))
        val route = RoutePlanner(makeVenue()).routeAlong(polyline, "L0", RouteProfile.FASTEST)
        assertEquals(listOf(Maneuver.Kind.Start, Maneuver.Kind.TurnLeft, Maneuver.Kind.Arrive), route.maneuvers.map { it.kind }) // north, then east
        assertNear(10f, route.length, 0.001f)
    }

    @Test
    fun recorderAddsAPointEveryQuarterMeterAndLinksThem() {
        val recorder = SessionPathRecorder(runId = "r", smoothing = 1f) // no smoothing: positions taken as they are
        for (i in 0..32) recorder.record(Vec3(0f, 1.4f, i * 0.125f)) // 4 m in 12.5 cm steps (exact in floating point)
        assertEquals(17, recorder.points.size) // 0, 0.25, ..., 4.0
        assertEquals((0..16).toList(), recorder.points.map { it.sequence })
        assertNear(4f, recorder.lengthMeters, 0.01f)
    }

    @Test
    fun smoothingEvensOutHandSway() {
        // Walking straight along z while the phone sways up to 6 cm sideways, a different phase at every sample.
        fun sidewaysSpread(smoothing: Float): Float {
            val recorder = SessionPathRecorder(runId = "r", smoothing = smoothing)
            for (i in 0..80) recorder.record(Vec3(0.06f * kotlin.math.sin(i * 2.0f), 1.4f, i * 0.0625f))
            val xs = recorder.points.map { it.position.x }
            return xs.max() - xs.min()
        }
        assertTrue("smoothed path wobbles less", sidewaysSpread(0.5f) < sidewaysSpread(1f) * 0.7f)
        // It still follows the walk: the recorded path reaches (nearly) the end.
        val recorder = SessionPathRecorder(runId = "r")
        for (i in 0..80) recorder.record(Vec3(0f, 1.4f, i * 0.0625f))
        assertTrue(recorder.lengthMeters > 4.5f)
    }

    @Test
    fun recorderStartsANewRunAfterATrackingJump() {
        val recorder = SessionPathRecorder(runId = "r")
        recorder.record(Vec3(0f, 1.4f, 0f))
        recorder.record(Vec3(0f, 1.4f, 20f)) // jump
        assertEquals(2, recorder.points.map { it.run }.distinct().size)
        assertEquals(0, recorder.points.last().sequence)
    }

    @Test
    fun sessionWalkConvertsToVenueCoordinatesWithTheFinalAlignment() {
        val venue = makeVenue()
        // The venue sits at yaw 90° and is shifted in the session; a walk straight "up" venue z by 4 m.
        val alignment = Mat4.yaw(90f, Vec3(10f, 0f, 20f))
        val sessionWalk = (0..4).map { RawWalkPoint("r", it, alignment.transformPoint(Vec3(1f, 1.4f, it.toFloat()))) }
        val walked = sessionWalk.toWalkedPoints(venue, alignment.inverse(), "L0")
        assertEquals(5, walked.size)
        walked.forEachIndexed { i, p ->
            assertNear(1f, p.x, 1e-3f); assertNear(i.toFloat(), p.z, 1e-3f)
            assertNear(0f, p.y, 1e-6f) // on the floor of level L0
            assertEquals("L0", p.level)
        }
    }

    @Test
    fun walkedPointsStandOnTheRealFloorWhenItIsKnown() {
        val venue = makeVenue()
        // The sign is mounted lower than the venue data assumes, so the real floor is 0.35 m above venue y = 0.
        val alignment = Mat4.yaw(0f, Vec3(5f, 0f, 5f))
        val floorInSession = alignment.transformPoint(Vec3(0f, 0.35f, 0f)).y
        val raw = (0..3).map { RawWalkPoint("r", it, alignment.transformPoint(Vec3(0f, 1.7f, it.toFloat()))) }
        val onRealFloor = raw.toWalkedPoints(venue, alignment.inverse(), "L0", floorSessionY = floorInSession)
        onRealFloor.forEach { assertNear(0.35f, it.y, 1e-3f) }
        // Without a detected floor the level's nominal elevation is used, as before.
        raw.toWalkedPoints(venue, alignment.inverse(), "L0").forEach { assertNear(0f, it.y, 1e-6f) }
    }

    @Test
    fun straightWalkStartsAtTheSign() {
        val points = straightWalk(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 2f), "s", "L0", 0f)
        assertEquals(4, points.size) // 0, 0.5, 1.0, 1.5 (the end point itself is left to the recorder)
        assertNear(0f, points.first().z); assertNear(1.5f, points.last().z)
    }

}

class AlignmentFilterTest {
    @Test
    fun firstSightingAlignsDirectly() {
        val filter = AlignmentFilter()
        val candidate = Mat4.yaw(30f, Vec3(1f, 0f, 2f))
        assertTrue(filter.update(candidate, 3f))
        assertTrue(filter.isAligned)
        assertNear(1f, filter.alignment.translation.x)
    }

    @Test
    fun closerSightingsCountMore() {
        assertTrue(AlignmentFilter.weight(0.5f) > AlignmentFilter.weight(1.5f))
        assertTrue(AlignmentFilter.weight(1.5f) > AlignmentFilter.weight(4f))
        assertNear(1f, AlignmentFilter.weight(0.5f), 1e-4f)
        assertNear(0.25f, AlignmentFilter.weight(1.5f), 1e-4f)
    }

    @Test
    fun aCloseSightingOutweighsAFarOne() {
        // The truth is x = 1.0. A first sighting from far away read 1.4; a second reads the truth.
        val far = AlignmentFilter().apply { update(Mat4.yaw(0f, Vec3(1.4f, 0f, 0f)), 4f) }
        val close = AlignmentFilter().apply { update(Mat4.yaw(0f, Vec3(1.4f, 0f, 0f)), 4f) }
        far.update(Mat4.yaw(0f, Vec3(1.0f, 0f, 0f)), 4f)    // equally unreliable: halfway
        close.update(Mat4.yaw(0f, Vec3(1.0f, 0f, 0f)), 0.5f) // much more reliable: nearly the truth
        val farError = kotlin.math.abs(far.alignment.translation.x - 1f)
        val closeError = kotlin.math.abs(close.alignment.translation.x - 1f)
        assertNear(0.2f, farError, 0.01f)
        assertTrue("close scan wins ($closeError < $farError)", closeError < 0.05f)
    }

    @Test
    fun averagingManySightingsCancelsNoise() {
        // Ten sightings with +-4 cm of alternating noise around x = 2.0: the average sits on the truth.
        val filter = AlignmentFilter()
        for (i in 0 until 10) filter.update(Mat4.yaw(0f, Vec3(if (i % 2 == 0) 2.04f else 1.96f, 0f, 0f)), 1f)
        assertNear(2f, filter.alignment.translation.x, 0.005f)
    }

    @Test
    fun oneBadReadingCannotMoveTheVenueFar() {
        val filter = AlignmentFilter()
        repeat(8) { filter.update(Mat4.yaw(10f, Vec3(2f, 0f, 3f)), 1f) }
        filter.update(Mat4.yaw(40f, Vec3(2.5f, 0f, 3.5f)), 4f) // a wild, far reading
        assertTrue("moved less than 5 cm", kotlin.math.abs(filter.alignment.translation.x - 2f) < 0.05f)
        assertTrue("turned less than 2 degrees", kotlin.math.abs(Math.toDegrees(AlignmentFilter.yaw(filter.alignment).toDouble()) - 10.0) < 2.0)
    }

    @Test
    fun yawAveragesThroughTheShortestWay() {
        // 170 and -170 degrees are 20 degrees apart across the wrap, so they average to 180, not 0.
        val filter = AlignmentFilter()
        filter.update(Mat4.yaw(170f), 1f)
        filter.update(Mat4.yaw(-170f), 1f)
        assertEquals(180.0, kotlin.math.abs(Math.toDegrees(AlignmentFilter.yaw(filter.alignment).toDouble())), 0.5)
    }

    @Test
    fun tinyChangesAreIgnored() {
        val filter = AlignmentFilter().apply { repeat(4) { update(Mat4.yaw(0f, Vec3(1f, 0f, 1f)), 1f) } }
        assertFalse(filter.update(Mat4.yaw(0f, Vec3(1.01f, 0f, 1f)), 1f))
        assertNear(1f, filter.alignment.translation.x)
    }
}

class AlignmentLockInTest {
    @Test
    fun lockInNeedsThreeCloseSightings() {
        val filter = AlignmentFilter()
        val a = Mat4.yaw(0f, Vec3(1f, 0f, 1f))
        filter.update(a, 5f)
        filter.update(a, 4f)
        assertFalse("far sightings never lock in", filter.isLockedIn)
        filter.update(a, 1.5f)
        filter.update(a, 1.2f)
        assertFalse("two close sightings are not enough", filter.isLockedIn)
        filter.update(a, 1.0f)
        assertTrue(filter.isLockedIn)
        filter.reset()
        assertFalse(filter.isLockedIn)
    }
}

class PlacedObjectTest {
    private fun obj(id: String, destination: String, kind: PlacedObjectKind = PlacedObjectKind.CUP) =
        PlacedObject(id, kind, "L0", 1f, 0.7f, 2f, destinationId = destination)

    @Test
    fun objectsAreFilteredByDestination() {
        val all = listOf(obj("a", "d1"), obj("b", "d2"), obj("c", "d1", PlacedObjectKind.GIFT))
        assertEquals(listOf("a", "c"), all.forDestination("d1").map { it.id })
        assertEquals(emptyList<PlacedObject>(), all.forDestination("none"))
    }

    @Test
    fun farObjectsAreDrawnLargerSoTheyCanBeSeen() {
        assertNear(1f, objectScaleFor(0.5f))   // up close: life-size
        assertNear(1f, objectScaleFor(1.5f))
        assertNear(2f, objectScaleFor(3f))
        assertNear(2.5f, objectScaleFor(20f))  // never more than 2.5x
        assertNear(1f, obj("a", "d").scale)    // saved objects default to life-size
    }

    @Test
    fun fallbackPositionIsOnTheFloorAheadOfTheUser() {
        val p = fallbackObjectPosition(Vec3(2f, 1.4f, 3f), headingX = 0f, headingZ = 5f, floorY = 0f, distance = 1f)
        assertNear(2f, p.x); assertNear(0f, p.y); assertNear(4f, p.z)
        // No heading at all: still a finite spot, not NaN.
        val q = fallbackObjectPosition(Vec3(0f, 1.4f, 0f), 0f, 0f, 0f)
        assertTrue(q.x.isFinite() && q.z.isFinite())
    }

}

class ObjectSpreadTest {
    @Test
    fun aClearSpotIsKept() {
        val spot = Vec3(1f, 0.7f, 2f)
        assertEquals(spot, spreadObjectPosition(spot, listOf(Vec3(5f, 0f, 5f))))
    }

    @Test
    fun anOccupiedSpotIsMovedToANearbyFreeOne() {
        val taken = Vec3(1f, 0.7f, 2f)
        val moved = spreadObjectPosition(taken, listOf(taken))
        assertTrue(horizontalDistance(moved, taken) >= 0.3f)
        assertTrue(horizontalDistance(moved, taken) <= 0.7f)
        assertNear(0.7f, moved.y) // stays on the same surface
    }

    @Test
    fun manyObjectsFromOneSpotAllEndUpApart() {
        val placed = mutableListOf<Vec3>()
        repeat(6) { placed += spreadObjectPosition(Vec3(0f, 0f, 0f), placed) }
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            assertTrue("objects $i and $j overlap", horizontalDistance(placed[i], placed[j]) >= 0.29f)
        }
    }

    @Test
    fun sessionObjectConvertsToVenueCoordinatesAndYaw() {
        val venue = makeVenue()
        val alignment = Mat4.yaw(90f, Vec3(10f, 0f, 20f))
        val venuePoint = Vec3(2f, 0.7f, 3f)
        val world = alignment.transformPoint(venuePoint)
        val inSession = PlacedObject("o", PlacedObjectKind.CUP, "L0", world.x, world.y, world.z, yawDegrees = 120f)
        val converted = inSession.toVenue(venue, alignment.inverse(), alignmentYawDegrees = 90f, fallbackLevel = "L0")
        assertNear(2f, converted.x, 1e-3f); assertNear(0.7f, converted.y, 1e-3f); assertNear(3f, converted.z, 1e-3f)
        assertNear(30f, converted.yawDegrees) // 120° in the session = 30° relative to the venue
        assertEquals("L0", converted.level)
    }
}

class ArPathStoreTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun destination(id: String, name: String = "Dest $id", x: Float = 1f, z: Float = 2f) =
        CustomDestination(id, name, "L0", x, z, createdAtMillis = 1L, floorY = 0.3f)
    private fun point(run: String, seq: Int, x: Float, z: Float) = WalkedPoint(run, seq, "L0", x, 0f, z)
    private fun obj(id: String, kind: PlacedObjectKind = PlacedObjectKind.CUP, reference: Boolean = false) =
        PlacedObject(id, kind, "L0", 1f, 0.7f, 2f, text = if (kind == PlacedObjectKind.MESSAGE) "Turn here!" else "", isReference = reference)
    private fun path(id: String, name: String = "Dest $id", venue: String = "v") = ArPath(
        venueId = venue, destination = destination(id, name),
        walkedPoints = listOf(point("r-$id", 0, 0f, 0f), point("r-$id", 1, 1f, 2f)),
        objects = listOf(obj("o-$id", PlacedObjectKind.STAR, reference = true), obj("n-$id", PlacedObjectKind.MESSAGE)),
    )

    /** A fresh `paths` directory inside an app-files-like parent directory. */
    private fun withStore(block: (store: ArPathStore, paths: File, parent: File) -> Unit) {
        val parent = Files.createTempDirectory("arpaths").toFile()
        try {
            val paths = File(parent, "paths")
            block(ArPathStore(paths), paths, parent)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun eachPathGetsItsOwnFileNamedAfterItsDestination() = withStore { store, paths, _ ->
        store.save(path("a", "Gaming Room"))
        store.save(path("b", "E4"))
        assertEquals(listOf("e4.json", "gaming-room.json"), paths.list()!!.sorted())

        val loaded = store.load("v")
        assertEquals(setOf("Gaming Room", "E4"), loaded.map { it.destination.name }.toSet())
        val room = loaded.first { it.destination.id == "a" }
        assertEquals(2, room.walkedPoints.size)
        assertTrue(room.walkedPoints.all { it.destinationId == "a" })
        assertTrue(room.objects.all { it.destinationId == "a" })
        assertTrue(room.objects.first { it.id == "o-a" }.isReference)
        assertEquals("Turn here!", room.objects.first { it.id == "n-a" }.text)
        assertEquals(0.3f, room.destination.floorY!!, 1e-6f)
    }

    @Test
    fun sameNameGetsANumberedFile() = withStore { store, paths, _ ->
        store.save(path("a", "Sofa"))
        store.save(path("b", "Sofa"))
        assertEquals(listOf("sofa-2.json", "sofa.json"), paths.list()!!.sorted())
        assertEquals("sofa", ArPathStore.slugOf("  Sofa!! "))
        assertEquals("path", ArPathStore.slugOf("???"))
    }

    @Test
    fun savingAgainReplacesTheSameFile() = withStore { store, paths, _ ->
        store.save(path("a", "Sofa"))
        store.save(path("a", "Sofa").copy(objects = emptyList()))
        assertEquals(listOf("sofa.json"), paths.list()!!.toList())
        assertEquals(0, store.load("v").single().objects.size)
    }

    @Test
    fun deletingRemovesOnlyThatPathsFile() = withStore { store, paths, _ ->
        store.save(path("a", "One"))
        store.save(path("b", "Two"))
        assertTrue(store.delete("a"))
        assertFalse(store.delete("a"))
        assertEquals(listOf("two.json"), paths.list()!!.toList())
        assertEquals(listOf("b"), store.load("v").map { it.destination.id })
    }

    @Test
    fun aDamagedFileIsSkippedAndNeverTouched() = withStore { store, paths, _ ->
        store.save(path("a", "Good"))
        val bad = File(paths, "bad.json").apply { writeText("{not json") }
        assertEquals(listOf("a"), store.load("v").map { it.destination.id })
        // Saving more paths leaves the damaged file exactly as it was.
        store.save(path("b", "Also good"))
        assertEquals("{not json", bad.readText())
        assertEquals(setOf("a", "b"), store.load("v").map { it.destination.id }.toSet())
    }

    @Test
    fun onlyThePathsOfTheCurrentVenueAreLoaded() = withStore { store, _, _ ->
        store.save(path("a", "Here", venue = "mall"))
        store.save(path("b", "Elsewhere", venue = "museum"))
        store.save(path("c", "Very old", venue = "")) // saved before venues were recorded
        assertEquals(setOf("a", "c"), store.load("mall").map { it.destination.id }.toSet())
        assertEquals(setOf("b", "c"), store.load("museum").map { it.destination.id }.toSet())
    }

    @Test
    fun splitsTheSingleFileIntoOneFilePerPathAndKeepsABackup() = withStore { store, paths, parent ->
        val data = ArData(
            destinations = listOf(destination("a", "Gaming Room", 1f, 2f), destination("b", "E4", 5f, 5f)),
            walkedPoints = listOf(
                point("ra", 0, 0f, 0f).copy(destinationId = "a"), point("ra", 1, 1f, 2f).copy(destinationId = "a"),
                point("rb", 0, 0f, 0f).copy(destinationId = "b"),
                point("stray", 0, 9f, 9f), // belongs to no destination
            ),
            objects = listOf(obj("o1").copy(destinationId = "a"), obj("o2").copy(destinationId = "b")),
        )
        File(parent, "ar_data.json").writeText(json.encodeToString(ArData.serializer(), data))

        val loaded = store.load("mall")
        assertEquals(setOf("e4.json", "gaming-room.json"), paths.list()!!.toSet())
        val room = loaded.first { it.destination.id == "a" }
        assertEquals(2, room.walkedPoints.size)
        assertEquals(listOf("o1"), room.objects.map { it.id })
        assertEquals("mall", room.venueId)
        assertEquals(1, loaded.first { it.destination.id == "b" }.walkedPoints.size)
        assertEquals(0, loaded.flatMap { it.walkedPoints }.count { it.run == "stray" }) // unowned points are dropped
        // The old file is kept as a backup, and loading again doesn't migrate twice.
        assertFalse(File(parent, "ar_data.json").exists())
        assertTrue(File(parent, "ar_data.json.migrated").exists())
        assertEquals(loaded.map { it.destination.id }.toSet(), store.load("mall").map { it.destination.id }.toSet())
    }

    @Test
    fun splitsTheThreeOldestFilesToo() = withStore { store, paths, parent ->
        val serializerD = kotlinx.serialization.builtins.ListSerializer(CustomDestination.serializer())
        val serializerP = kotlinx.serialization.builtins.ListSerializer(WalkedPoint.serializer())
        val serializerO = kotlinx.serialization.builtins.ListSerializer(PlacedObject.serializer())
        File(parent, "custom_destinations.json").writeText(json.encodeToString(serializerD, listOf(destination("d1", "Sofa", 1f, 2f))))
        File(parent, "walked_path.json").writeText(json.encodeToString(serializerP,
            listOf(point("mine", 0, 0f, 0f), point("mine", 1, 1f, 2f), point("stray", 0, 0f, 0f), point("stray", 1, 4f, 4f))))
        File(parent, "placed_objects.json").writeText(json.encodeToString(serializerO, listOf(obj("o1").copy(destinationId = "d1"))))

        val loaded = store.load("mall").single()
        assertEquals(listOf("sofa.json"), paths.list()!!.toList())
        assertEquals(setOf("mine"), loaded.walkedPoints.map { it.run }.toSet()) // the run that ends at Sofa
        assertEquals(listOf("o1"), loaded.objects.map { it.id })
        assertTrue(File(parent, "walked_path.json.migrated").exists())
        assertFalse(File(parent, "walked_path.json").exists())
    }

    @Test
    fun oldRecordsWithoutNewFieldsStillLoad() = withStore { store, paths, _ ->
        paths.mkdirs()
        File(paths, "old.json").writeText(
            """{"destination":{"id":"d","name":"Old","level":"L0","x":1,"z":2,"createdAtMillis":1},
               "walkedPoints":[{"run":"r","sequence":0,"level":"L0","x":0,"y":0,"z":0}],
               "objects":[{"id":"o","kind":"cup","level":"L0","x":1,"y":0,"z":2}]}""",
        )
        val loaded = store.load("anything").single()
        assertFalse(loaded.objects.single().isReference)
        assertEquals("", loaded.walkedPoints.single().destinationId)
        assertEquals(1, loaded.formatVersion)
    }
}

class AlignmentTrackTest {
    private val start = Mat4.yaw(0f, Vec3(10f, 0f, 20f))

    @Test
    fun aSingleScanKeepsTheSameAlignmentAllTheWay() {
        val track = AlignmentTrack().apply { add(0f, start) }
        listOf(0f, 3f, 50f).forEach { assertNear(10f, track.alignmentAt(it).translation.x) }
    }

    @Test
    fun driftBetweenTwoScansIsSpreadAlongTheWalk() {
        // The venue seems to have moved 10 cm (and turned 2 degrees) by the end of a 10 m walk: that is drift.
        val end = Mat4.yaw(2f, Vec3(10.1f, 0f, 20f))
        val track = AlignmentTrack().apply { add(0f, start); add(10f, end) }
        assertNear(10f, track.alignmentAt(0f).translation.x)
        assertNear(10.05f, track.alignmentAt(5f).translation.x, 1e-4f) // halfway
        assertNear(10.1f, track.alignmentAt(10f).translation.x, 1e-4f)
        assertNear(1f, Math.toDegrees(AlignmentFilter.yaw(track.alignmentAt(5f)).toDouble()).toFloat(), 0.01f)
        // Before the first scan and after the last one, the nearest scan is used.
        assertNear(10f, track.alignmentAt(-3f).translation.x)
        assertNear(10.1f, track.alignmentAt(99f).translation.x)
    }

    @Test
    fun correctingDriftBringsTheEndOfAPathBackOntoTheTruth() {
        // Real venue positions along a 10 m straight walk (z from 0 to 10). ARCore drifts: its session frame slides
        // 12 cm sideways by the end. With one alignment (from the start scan) the end is 12 cm off; with the track
        // (start and end scans) it is not.
        val truth = (0..10).map { Vec3(1f, 1.4f, it.toFloat()) }
        fun sessionOf(venuePoint: Vec3, progress: Float) = start.transformPoint(Vec3(venuePoint.x + 0.012f * progress, venuePoint.y, venuePoint.z))
        val raw = truth.mapIndexed { i, p -> RawWalkPoint("r", i, sessionOf(p, i.toFloat()), progress = i.toFloat()) }
        // The end scan sees the venue 12 cm away from where the start scan put it.
        val endAlignment = Mat4.yaw(0f, Vec3(10f + 0.12f, 0f, 20f))
        val venue = makeVenue()

        val withOneScan = raw.toWalkedPoints(venue, start.inverse(), "L0")
        val withTrack = raw.toWalkedPoints(venue, AlignmentTrack().apply { add(0f, start); add(10f, endAlignment) }, "L0")
        val errorOneScan = kotlin.math.abs(withOneScan.last().x - 1f)
        val errorWithTrack = kotlin.math.abs(withTrack.last().x - 1f)
        assertTrue("one scan leaves $errorOneScan m at the end", errorOneScan > 0.1f)
        assertTrue("the track corrects it to $errorWithTrack m", errorWithTrack < 0.01f)
        assertNear(0.12f, AlignmentTrack().apply { add(0f, start); add(10f, endAlignment) }.totalDriftMeters(raw.last().position), 0.01f)
    }

    @Test
    fun recorderCountsHowFarTheUserWalked() {
        val recorder = SessionPathRecorder(runId = "r", smoothing = 1f)
        for (i in 0..16) recorder.record(Vec3(0f, 1.4f, i * 0.25f)) // 4 m in 25 cm steps
        assertNear(4f, recorder.progress, 0.01f)
        assertNear(4f, recorder.points.last().progress, 0.01f)
        assertEquals(0f, recorder.points.first().progress)
        recorder.clear()
        assertEquals(0f, recorder.progress)
    }
}

class AlignmentSpreadTest {
    @Test
    fun agreeingSightingsHaveNoSpreadAndNoisyOnesDo() {
        val steady = AlignmentFilter()
        repeat(6) { steady.update(Mat4.yaw(10f, Vec3(2f, 0f, 3f)), 1f) }
        assertNear(0f, steady.spreadCm, 0.01f)
        assertNear(0f, steady.spreadDegrees, 0.01f)

        val noisy = AlignmentFilter()
        for (i in 0 until 6) noisy.update(Mat4.yaw(10f + if (i % 2 == 0) 3f else -3f, Vec3(2f + if (i % 2 == 0) 0.1f else -0.1f, 0f, 3f)), 1f)
        assertTrue("about 10 cm of scatter, was ${noisy.spreadCm}", noisy.spreadCm in 8f..12f)
        assertTrue("about 3 degrees, was ${noisy.spreadDegrees}", noisy.spreadDegrees in 2.5f..3.5f)
        noisy.reset()
        assertEquals(0f, noisy.spreadCm)
    }
}

class SightingQualityTest {
    private val truth = Mat4.yaw(10f, Vec3(2f, 0f, 3f))

    @Test
    fun obliqueSightingsCountForMuchLess() {
        // Three head-on sightings read the truth; one edge-on sighting reads 7 cm off (close enough not to be thrown
        // out as an outlier). It barely moves the alignment...
        val withOblique = AlignmentFilter().apply {
            repeat(3) { update(truth, 1f, viewCosine = 1f) }
            update(Mat4.yaw(10f, Vec3(2.07f, 0f, 3f)), 1f, viewCosine = 0.25f)
        }
        // ...whereas counted like a head-on one, the same reading would have dragged it much further.
        val asIfHeadOn = AlignmentFilter().apply {
            repeat(3) { update(truth, 1f, viewCosine = 1f) }
            update(Mat4.yaw(10f, Vec3(2.07f, 0f, 3f)), 1f, viewCosine = 1f)
        }
        val errorOblique = kotlin.math.abs(withOblique.alignment.translation.x - 2f)
        val errorHeadOn = kotlin.math.abs(asIfHeadOn.alignment.translation.x - 2f)
        assertTrue("oblique $errorOblique vs head-on $errorHeadOn", errorOblique < errorHeadOn * 0.4f)
    }

    @Test
    fun obliqueSightingsDoNotCountTowardLockIn() {
        val filter = AlignmentFilter()
        repeat(10) { filter.update(truth, 1f, viewCosine = 0.3f) }
        assertEquals(0, filter.closeSightings)
        assertFalse(filter.isLockedIn)
    }

    @Test
    fun lockInNeedsDifferentViewpointsOrPatience() {
        val standingStill = AlignmentFilter()
        val spot = Vec3(2f, 1.4f, 5f)
        repeat(3) { standingStill.update(truth, 1.5f, 1f, spot) }
        assertEquals(0f, standingStill.viewSpreadMeters, 1e-6f)
        assertFalse("three sightings from one spot are not enough", standingStill.isLockedIn)
        repeat(5) { standingStill.update(truth, 1.5f, 1f, spot) }
        assertTrue("after 8 it gives in", standingStill.isLockedIn)

        val moving = AlignmentFilter()
        listOf(0f, 0.08f, 0.2f).forEach { dx -> moving.update(truth, 1.5f, 1f, Vec3(2f + dx, 1.4f, 5f)) }
        assertTrue(moving.viewSpreadMeters >= 0.12f)
        assertTrue("moving 20 cm is enough", moving.isLockedIn)
    }
}
