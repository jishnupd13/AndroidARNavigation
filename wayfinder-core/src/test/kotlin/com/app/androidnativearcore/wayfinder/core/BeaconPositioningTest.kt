package com.app.androidnativearcore.wayfinder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconPositioningTest {
    private val uuid = "18516F0F-64DE-42B1-8707-F02A087E5733"
    private val door = RegisteredBeacon("BEACON_001", "Door", uuid, 1, 1, floorId = "L0", x = 1.35f, y = -12.4f)
    private val desk = RegisteredBeacon("BEACON_002", "Desk", uuid, 2, 2, floorId = "L0", x = 1.92f, y = -10.14f)
    private val registered = listOf(door, desk)

    /** A steady device heard with [rssi] dBm, as the scanner's smoothing reports it. */
    private fun heard(major: Int, minor: Int, rssi: Int, readings: Int = 5) =
        NearbyDevice(BeaconIdentity.IBeacon(uuid, major, minor, -59), "AA:BB:CC:DD:EE:0$minor", null, rssi, readings, 0L)

    @Test
    fun theStrongestRegisteredBeaconIsNearest() {
        val fix = NearestBeacon().update(listOf(heard(1, 1, -80), heard(2, 2, -60)), registered, 1_000L)!!
        assertEquals("BEACON_002", fix.beacon.beaconId)
        assertEquals(-60, fix.rssi)
    }

    @Test
    fun unregisteredUnsteadyAndVeryWeakDevicesAreIgnored() {
        val stranger = heard(9, 9, -40)
        val settling = heard(1, 1, -50, readings = 1)
        val faint = heard(2, 2, -99)
        assertNull(NearestBeacon().update(listOf(stranger, settling, faint), registered, 1_000L))
    }

    @Test
    fun aSlightlyStrongerBeaconDoesNotTakeOver() {
        val nearest = NearestBeacon()
        nearest.update(listOf(heard(1, 1, -60), heard(2, 2, -70)), registered, 0L)
        // The desk is now 3 dB stronger: signals jump that much on their own, so the door stays.
        val fix = nearest.update(listOf(heard(1, 1, -63), heard(2, 2, -60)), registered, 5_000L)!!
        assertEquals("BEACON_001", fix.beacon.beaconId)
    }

    @Test
    fun aClearlyStrongerBeaconTakesOverAfterTwoSeconds() {
        val nearest = NearestBeacon()
        nearest.update(listOf(heard(1, 1, -60), heard(2, 2, -75)), registered, 0L)
        val walkingToDesk = listOf(heard(1, 1, -75), heard(2, 2, -58))
        assertEquals("BEACON_001", nearest.update(walkingToDesk, registered, 1_000L)!!.beacon.beaconId)
        assertEquals("still the door after 1.5 s", "BEACON_001", nearest.update(walkingToDesk, registered, 2_500L)!!.beacon.beaconId)
        assertEquals("the desk after 2 s of being clearly stronger", "BEACON_002", nearest.update(walkingToDesk, registered, 3_000L)!!.beacon.beaconId)
    }

    @Test
    fun aMomentaryJumpDoesNotSwitch() {
        val nearest = NearestBeacon()
        nearest.update(listOf(heard(1, 1, -60), heard(2, 2, -75)), registered, 0L)
        nearest.update(listOf(heard(1, 1, -75), heard(2, 2, -58)), registered, 1_000L) // a spike…
        nearest.update(listOf(heard(1, 1, -60), heard(2, 2, -74)), registered, 1_500L) // …and back
        assertEquals("BEACON_001", nearest.update(listOf(heard(1, 1, -75), heard(2, 2, -58)), registered, 3_000L)!!.beacon.beaconId)
    }

    @Test
    fun whenTheNearestBeaconGoesQuietTheNextOneIsUsed() {
        val nearest = NearestBeacon()
        nearest.update(listOf(heard(1, 1, -60), heard(2, 2, -80)), registered, 0L)
        assertEquals("BEACON_002", nearest.update(listOf(heard(2, 2, -80)), registered, 1_000L)!!.beacon.beaconId)
        assertNull(nearest.update(emptyList(), registered, 2_000L))
    }

    @Test
    fun theFixSaysHowCloseTheVisitorIs() {
        val close = NearestBeacon().update(listOf(heard(1, 1, -59)), registered, 0L)!!
        assertTrue("about 1 m away", close.isClose)
        assertEquals(NearestBeacon.MIN_UNCERTAINTY_METERS, close.uncertaintyMeters, 0.01f)
        val far = NearestBeacon().update(listOf(heard(1, 1, -85)), registered, 0L)!!
        assertTrue(!far.isClose)
        assertTrue(far.uncertaintyMeters > 5f)
    }
}

class BeaconRoutingTest {
    private val uuid = "18516F0F-64DE-42B1-8707-F02A087E5733"

    /** A recorded walk along z = 0 from x = 0 to x = 10, a point every 0.5 m, leading to destination "room". */
    private val walk = (0..20).map { WalkedPoint("w", it, "L0", it * 0.5f, 0f, 0f, "room") }

    private fun fixAt(x: Float, y: Float, id: String = "BEACON_001") =
        BeaconFix(RegisteredBeacon(id, "Door", uuid, 1, 1, floorId = "L0", x = x, y = y), -60, 1f, 0L)

    @Test
    fun aRouteStartsAtTheNearestBeaconAndFollowsTheWalks() {
        // The beacon hangs on a wall 1.5 m beside the start of the walk.
        val result = ConnectionRoutePlanner(walk).route(fixAt(0f, 1.5f).routeStart(), RouteEndpoint("Room", Vec3(10f, 0f, 0f), "L0", 1.5f))
        assertTrue(result is ConnectionRouteResult.Success)
        val length = (result as ConnectionRouteResult.Success).lengthMeters
        assertTrue("about 10 m along the walk, plus the step from the beacon (was $length)", length in 10f..11.6f)
    }

    @Test
    fun aBeaconFarFromEveryWalkCannotStartARoute() {
        val result = ConnectionRoutePlanner(walk).route(fixAt(5f, 9f).routeStart(), RouteEndpoint("Room", Vec3(10f, 0f, 0f), "L0", 1.5f))
        assertEquals(ConnectionRouteResult.Failure(ConnectionRouteResult.Reason.START_NOT_CONNECTED), result)
    }

    @Test
    fun movingToAnotherBeaconShortensTheRoute() {
        val planner = ConnectionRoutePlanner(walk)
        val room = RouteEndpoint("Room", Vec3(10f, 0f, 0f), "L0", 1.5f)
        val fromDoor = (planner.route(fixAt(0f, 1f).routeStart(), room) as ConnectionRouteResult.Success).lengthMeters
        val fromDesk = (planner.route(fixAt(7f, 1f, id = "BEACON_002").routeStart(), room) as ConnectionRouteResult.Success).lengthMeters
        assertTrue("$fromDesk < $fromDoor", fromDesk < fromDoor - 5f)
    }

    @Test
    fun theDemoMallBeaconsBothReachO1OnceAWalkFromR1IsRecorded() {
        // Positions as registered on the test phone: reference point R1, destination O1 and the two beacons.
        val r1 = Vec3(1.20f, 0f, -12.64f)
        val o1 = Vec3(-3.10f, 0f, -4.75f)
        val steps = (r1.distanceTo(o1) / 0.5f).toInt()
        val walkToO1 = (0..steps).map { i ->
            val t = i / steps.toFloat()
            WalkedPoint("O1", i, "L0", r1.x + (o1.x - r1.x) * t, 0f, r1.z + (o1.z - r1.z) * t, "O1")
        }
        val planner = ConnectionRoutePlanner(walkToO1)
        val toO1 = RouteEndpoint("O1", o1, "L0", 1.5f)
        val fromIphone = planner.route(fixAt(1.35f, -12.4f).routeStart(), toO1)
        val fromOnePlus = planner.route(fixAt(1.78f, -10.11f, id = "BEACON_002").routeStart(), toO1)
        assertTrue("$fromIphone", fromIphone is ConnectionRouteResult.Success)
        assertTrue("$fromOnePlus", fromOnePlus is ConnectionRouteResult.Success)
        assertTrue(
            "the OnePlus beacon is further along the walk",
            (fromOnePlus as ConnectionRouteResult.Success).lengthMeters < (fromIphone as ConnectionRouteResult.Success).lengthMeters,
        )
    }

    @Test
    fun aRouteCanGoFromOneBeaconToAnother() {
        val door = fixAt(0f, 1f)
        val desk = RegisteredBeacon("BEACON_002", "Desk", uuid, 2, 2, floorId = "L0", x = 7f, y = 1f)
        val result = ConnectionRoutePlanner(walk).route(door.routeStart(), desk.routeEndpoint())
        assertTrue("$result", result is ConnectionRouteResult.Success)
        val length = (result as ConnectionRouteResult.Success).lengthMeters
        assertTrue("about 7 m along the walk, plus the steps to and from it (was $length)", length in 7f..9.5f)
        assertEquals(7.0f, door.beacon.position.distanceTo(desk.position), 0.001f)
    }

    @Test
    fun aBeaconAwayFromTheWalksCannotBeReached() {
        val far = RegisteredBeacon("BEACON_002", "Far", uuid, 2, 2, floorId = "L0", x = 5f, y = 9f)
        val result = ConnectionRoutePlanner(walk).route(fixAt(0f, 1f).routeStart(), far.routeEndpoint())
        assertTrue("$result", result is ConnectionRouteResult.Failure)
    }
}
