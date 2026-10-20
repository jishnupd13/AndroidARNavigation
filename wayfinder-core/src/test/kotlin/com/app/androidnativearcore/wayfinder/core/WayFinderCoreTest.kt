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
    fun invalidOrDuplicateEntriesAreSkipped() {
        val venue = makeVenue().withCustomDestinations(listOf(
            destination.copy(id = "bad-level", level = "L9"),
            destination.copy(id = "shop"), // clashes with an existing POI id
            destination,
        ))
        assertEquals(setOf("shop", "c1"), venue.pois.map { it.id }.toSet())
        assertEquals("Shop 101", venue.poi("shop")?.name?.resolved(listOf("en")))
    }

    @Test
    fun storePersistsAddsAndRemoves() {
        val dir = Files.createTempDirectory("store").toFile()
        try {
            val file = File(dir, "nested/destinations.json")
            val store = CustomDestinationStore(file)
            assertEquals(emptyList<CustomDestination>(), store.load())
            store.add(destination)
            store.add(destination.copy(id = "c2", name = "Second"))
            // A new store instance reads what the first one wrote.
            assertEquals(listOf("c1", "c2"), CustomDestinationStore(file).load().map { it.id })
            store.remove("c1")
            assertEquals(listOf("c2"), CustomDestinationStore(file).load().map { it.id })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptStoreFileLoadsAsEmpty() {
        val dir = Files.createTempDirectory("store").toFile()
        try {
            val file = File(dir, "destinations.json").apply { writeText("{not json") }
            assertEquals(emptyList<CustomDestination>(), CustomDestinationStore(file).load())
        } finally {
            dir.deleteRecursively()
        }
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
    fun straightWalkStartsAtTheSign() {
        val points = straightWalk(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 2f), "s", "L0", 0f)
        assertEquals(4, points.size) // 0, 0.5, 1.0, 1.5 (the end point itself is left to the recorder)
        assertNear(0f, points.first().z); assertNear(1.5f, points.last().z)
    }

    @Test
    fun walkedPathStorePersists() {
        val dir = Files.createTempDirectory("walk").toFile()
        try {
            val file = File(dir, "walked.json")
            WalkedPathStore(file).addAll(straight("a", 2f))
            WalkedPathStore(file).addAll(straight("b", 1f))
            assertEquals(5 + 3, WalkedPathStore(file).load().size)
            WalkedPathStore(file).clear()
            assertEquals(0, WalkedPathStore(file).load().size)
        } finally {
            dir.deleteRecursively()
        }
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
    fun fallbackPositionIsOnTheFloorAheadOfTheUser() {
        val p = fallbackObjectPosition(Vec3(2f, 1.4f, 3f), headingX = 0f, headingZ = 5f, floorY = 0f, distance = 1f)
        assertNear(2f, p.x); assertNear(0f, p.y); assertNear(4f, p.z)
        // No heading at all: still a finite spot, not NaN.
        val q = fallbackObjectPosition(Vec3(0f, 1.4f, 0f), 0f, 0f, 0f)
        assertTrue(q.x.isFinite() && q.z.isFinite())
    }

    @Test
    fun storePersistsAndRemovesWithTheDestination() {
        val dir = Files.createTempDirectory("objects").toFile()
        try {
            val file = File(dir, "objects.json")
            val store = PlacedObjectStore(file)
            store.addAll(listOf(obj("a", "d1"), obj("b", "d2").copy(kind = PlacedObjectKind.MESSAGE, text = "Turn here!")))
            val loaded = PlacedObjectStore(file).load()
            assertEquals(listOf("a", "b"), loaded.map { it.id })
            assertEquals("Turn here!", loaded[1].text)
            assertEquals(PlacedObjectKind.MESSAGE, loaded[1].kind)
            store.removeForDestination("d1")
            assertEquals(listOf("b"), PlacedObjectStore(file).load().map { it.id })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun referenceFlagSurvivesSavingAndDefaultsToOff() {
        val dir = Files.createTempDirectory("objects").toFile()
        try {
            val file = File(dir, "objects.json")
            PlacedObjectStore(file).addAll(listOf(obj("start", "d1").copy(isReference = true), obj("other", "d1")))
            val loaded = PlacedObjectStore(file).load()
            assertTrue(loaded.first { it.id == "start" }.isReference)
            assertFalse(loaded.first { it.id == "other" }.isReference)
            // Files written before the flag existed still load, with the flag off.
            file.writeText("""[{"id":"old","kind":"cup","level":"L0","x":1,"y":0,"z":2}]""")
            assertFalse(PlacedObjectStore(file).load().single().isReference)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptFileLoadsAsEmpty() {
        val dir = Files.createTempDirectory("objects").toFile()
        try {
            val file = File(dir, "objects.json").apply { writeText("[oops") }
            assertEquals(emptyList<PlacedObject>(), PlacedObjectStore(file).load())
        } finally {
            dir.deleteRecursively()
        }
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
