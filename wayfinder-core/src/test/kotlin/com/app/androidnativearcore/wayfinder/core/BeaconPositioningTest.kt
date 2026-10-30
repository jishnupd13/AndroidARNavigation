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
    fun aQuietCalibratedBeaconIsNearestWhenItIsCloser() {
        // A phone beacon that sends loudly (−45 dBm at 1 m) heard from 3 m, and a quiet one (−75 at 1 m) from 0.5 m:
        // the loud one has the stronger signal, but the quiet one is nearer, and calibration shows it.
        val loud = door.copy(rssiAt1m = -45)
        val quiet = desk.copy(rssiAt1m = -75)
        val fix = NearestBeacon().update(listOf(heard(1, 1, -57), heard(2, 2, -67)), listOf(loud, quiet), 0L)!!
        assertEquals("BEACON_002", fix.beacon.beaconId)
        assertTrue("right by it (${fix.roughDistanceMeters} m)", fix.isClose)
    }

    @Test
    fun withoutCalibrationTheLouderBeaconWins() {
        val fix = NearestBeacon().update(listOf(heard(1, 1, -57), heard(2, 2, -67)), registered, 0L)!!
        assertEquals("BEACON_001", fix.beacon.beaconId)
    }

    @Test
    fun anUncalibratedBeaconIsJudgedByItsSignalNotAGuessedDistance() {
        // −66 dBm with a guessed strength of −59 "is" 1.9 m away; it could as well be 4 m. A strong signal decides.
        val fix = BeaconFix(door, -66, 1.9f, 0L)
        assertTrue(fix.isAtBeacon)
        assertEquals("strong signal", fix.howNear)
        assertEquals("right by it", BeaconFix(door, -55, 0.6f, 0L).howNear)
        assertTrue(!BeaconFix(door, -78, 4.2f, 0L).isAtBeacon)
    }

    @Test
    fun aCalibratedBeaconIsJudgedByItsDistance() {
        val calibrated = door.copy(rssiAt1m = -75)
        assertTrue(BeaconFix(calibrated, -80, 1.6f, 0L).isAtBeacon)
        assertEquals("about 2 m away", BeaconFix(calibrated, -80, 1.6f, 0L).howNear)
        assertEquals("right by it", BeaconFix(calibrated, -73, 0.8f, 0L).howNear)
        assertTrue(!BeaconFix(calibrated, -88, 3.3f, 0L).isAtBeacon)
    }

    @Test
    fun atABeaconOnlyWhenItClearlySoundsNearest() {
        fun at(vararg signals: Pair<Int, Int>) = heardBeacons(signals.map { (minor, rssi) -> heard(minor, minor, rssi) }, registered)
        assertTrue("alone", clearlyAt(at(1 to -60), "BEACON_001"))
        assertTrue("well ahead of the other", clearlyAt(at(1 to -55, 2 to -70), "BEACON_001"))
        // Walked from the door to the desk: the door is still a bit louder, but not clearly.
        assertTrue(!clearlyAt(at(1 to -62, 2 to -65), "BEACON_001"))
        assertTrue(!clearlyAt(at(1 to -62, 2 to -65), "BEACON_002"))
        assertTrue("not heard", !clearlyAt(at(2 to -60), "BEACON_001"))
    }

    @Test
    fun aHeardBeaconSaysWhetherTheVisitorIsAtIt() {
        val at = heardBeacons(listOf(heard(1, 1, -60), heard(2, 2, -78)), registered)
        assertTrue(at.first { it.beacon.beaconId == "BEACON_001" }.isAtBeacon)
        assertTrue(!at.first { it.beacon.beaconId == "BEACON_002" }.isAtBeacon)
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

class BeaconTrackerTest {
    private val uuid = "18516F0F-64DE-42B1-8707-F02A087E5733"
    private val door = RegisteredBeacon("BEACON_001", "Door", uuid, 1, 1, floorId = "L0", x = 1.35f, y = -12.4f)
    private val desk = RegisteredBeacon("BEACON_002", "Desk", uuid, 2, 2, floorId = "L0", x = 1.78f, y = -10.11f)
    private val upstairs = RegisteredBeacon("BEACON_003", "Upstairs", uuid, 3, 3, floorId = "L1", x = 20f, y = 20f)
    private val registered = listOf(door, desk, upstairs)

    private fun heard(major: Int, minor: Int, rssi: Int) =
        NearbyDevice(BeaconIdentity.IBeacon(uuid, major, minor, -59), "AA:BB:CC:DD:EE:0$minor", null, rssi, 5, 0L)

    private fun near(beacon: RegisteredBeacon) = BeaconFix(beacon, -60, 1f, 0L)

    private fun BeaconPosition.distanceTo(beacon: RegisteredBeacon) = position.distanceTo(beacon.position!!)

    @Test
    fun oneBeaconHeardPutsTheVisitorAtIt() {
        val at = BeaconTracker().update(listOf(heard(1, 1, -60)), registered, near(door), 0L)!!
        assertEquals(0f, at.distanceTo(door), 0.001f)
        assertEquals(1, at.beaconsHeard)
    }

    @Test
    fun twoEquallyLoudBeaconsPutTheVisitorHalfway() {
        val at = BeaconTracker().update(listOf(heard(1, 1, -65), heard(2, 2, -65)), registered, near(door), 0L)!!
        assertEquals(at.distanceTo(door), at.distanceTo(desk), 0.01f)
        assertEquals(door.position!!.distanceTo(desk.position!!) / 2f, at.distanceTo(door), 0.01f)
    }

    @Test
    fun theLouderBeaconPullsTheVisitorTowardsIt() {
        val at = BeaconTracker().update(listOf(heard(1, 1, -55), heard(2, 2, -75)), registered, near(door), 0L)!!
        assertTrue("closer to the door", at.distanceTo(door) < at.distanceTo(desk))
        assertTrue("but not exactly at it", at.distanceTo(door) > 0.01f)
    }

    @Test
    fun theVisitorMovesTowardsTheNextBeaconGradually() {
        val tracker = BeaconTracker()
        tracker.update(listOf(heard(1, 1, -55)), registered, near(door), 0L)
        // Walking to the desk: it is now much louder than the door.
        val walking = listOf(heard(1, 1, -80), heard(2, 2, -55))
        val halfSecondLater = tracker.update(walking, registered, near(door), 500L)!!
        assertTrue("part of the way after 0.5 s (${halfSecondLater.distanceTo(door)} m)", halfSecondLater.distanceTo(door) in 0.3f..1.2f)
        var at = halfSecondLater
        for (t in 1_000L..6_000L step 500L) at = tracker.update(walking, registered, near(door), t)!!
        assertTrue("at the desk after a few seconds (${at.distanceTo(desk)} m away)", at.distanceTo(desk) < 0.3f)
    }

    @Test
    fun beaconsOnAnotherFloorAreIgnored() {
        val at = BeaconTracker().update(listOf(heard(1, 1, -60), heard(3, 3, -40)), registered, near(door), 0L)!!
        assertEquals("L0", at.floorId)
        assertEquals(0f, at.distanceTo(door), 0.001f)
    }

    @Test
    fun noBeaconHeardMeansNoPosition() {
        val tracker = BeaconTracker()
        tracker.update(listOf(heard(1, 1, -60)), registered, near(door), 0L)
        assertNull(tracker.update(emptyList(), registered, null, 1_000L))
        assertNull(tracker.current)
    }

    @Test
    fun beaconsWithoutAPositionDontMoveTheVisitor() {
        val unplaced = RegisteredBeacon("BEACON_004", "Kiosk", uuid, 4, 4, floorId = "L0")
        val at = BeaconTracker().update(listOf(heard(1, 1, -70), heard(4, 4, -45)), registered + unplaced, near(door), 0L)!!
        assertEquals(0f, at.distanceTo(door), 0.001f)
        assertEquals(1, at.beaconsHeard)
    }

    @Test
    fun onlyBeaconsWithoutAPositionGiveNoPosition() {
        val unplaced = RegisteredBeacon("BEACON_004", "Kiosk", uuid, 4, 4, floorId = "L0")
        assertNull(BeaconTracker().update(listOf(heard(4, 4, -50)), listOf(unplaced), near(unplaced), 0L))
    }

    @Test
    fun theNearestBeaconNeedsNoPosition() {
        val unplaced = RegisteredBeacon("BEACON_004", "Kiosk", uuid, 4, 4, floorId = "L0")
        val fix = NearestBeacon().update(listOf(heard(4, 4, -50), heard(1, 1, -70)), listOf(door, unplaced), 0L)!!
        assertEquals("BEACON_004", fix.beacon.beaconId)
        assertNull(fix.routeStart())
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
        val result = ConnectionRoutePlanner(walk).route(fixAt(0f, 1.5f).routeStart()!!, RouteEndpoint("Room", Vec3(10f, 0f, 0f), "L0", 1.5f))
        assertTrue(result is ConnectionRouteResult.Success)
        val length = (result as ConnectionRouteResult.Success).lengthMeters
        assertTrue("about 10 m along the walk, plus the step from the beacon (was $length)", length in 10f..11.6f)
    }

    @Test
    fun aBeaconFarFromEveryWalkCannotStartARoute() {
        val result = ConnectionRoutePlanner(walk).route(fixAt(5f, 9f).routeStart()!!, RouteEndpoint("Room", Vec3(10f, 0f, 0f), "L0", 1.5f))
        assertEquals(ConnectionRouteResult.Failure(ConnectionRouteResult.Reason.START_NOT_CONNECTED), result)
    }

    @Test
    fun movingToAnotherBeaconShortensTheRoute() {
        val planner = ConnectionRoutePlanner(walk)
        val room = RouteEndpoint("Room", Vec3(10f, 0f, 0f), "L0", 1.5f)
        val fromDoor = (planner.route(fixAt(0f, 1f).routeStart()!!, room) as ConnectionRouteResult.Success).lengthMeters
        val fromDesk = (planner.route(fixAt(7f, 1f, id = "BEACON_002").routeStart()!!, room) as ConnectionRouteResult.Success).lengthMeters
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
        val fromIphone = planner.route(fixAt(1.35f, -12.4f).routeStart()!!, toO1)
        val fromOnePlus = planner.route(fixAt(1.78f, -10.11f, id = "BEACON_002").routeStart()!!, toO1)
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
        val result = ConnectionRoutePlanner(walk).route(door.routeStart()!!, desk.routeEndpoint()!!)
        assertTrue("$result", result is ConnectionRouteResult.Success)
        val length = (result as ConnectionRouteResult.Success).lengthMeters
        assertTrue("about 7 m along the walk, plus the steps to and from it (was $length)", length in 7f..9.5f)
        assertEquals(7.0f, door.beacon.position!!.distanceTo(desk.position!!), 0.001f)
    }

    @Test
    fun aBeaconAwayFromTheWalksCannotBeReached() {
        val far = RegisteredBeacon("BEACON_002", "Far", uuid, 2, 2, floorId = "L0", x = 5f, y = 9f)
        val result = ConnectionRoutePlanner(walk).route(fixAt(0f, 1f).routeStart()!!, far.routeEndpoint()!!)
        assertTrue("$result", result is ConnectionRouteResult.Failure)
    }
}

class ProximityTrendTest {
    @Test
    fun walkingTowardsThePlaceIsCloser() {
        val trend = ProximityTrend()
        trend.update(5f, 0L)
        trend.update(4.7f, 1_000L)
        assertEquals(ProximityTrend.Direction.CLOSER, trend.update(4.4f, 2_000L))
    }

    @Test
    fun walkingAwayIsFurther() {
        val trend = ProximityTrend()
        trend.update(2f, 0L)
        assertEquals(ProximityTrend.Direction.FURTHER, trend.update(2.6f, 1_500L))
    }

    @Test
    fun smallWobblesAreSteady() {
        val trend = ProximityTrend()
        trend.update(3f, 0L)
        trend.update(3.2f, 500L)
        assertEquals(ProximityTrend.Direction.STEADY, trend.update(2.9f, 1_000L))
    }

    @Test
    fun onlyTheLastFewSecondsCount() {
        val trend = ProximityTrend()
        trend.update(8f, 0L) // long ago, far away
        trend.update(3f, 10_000L)
        assertEquals("standing still now", ProximityTrend.Direction.STEADY, trend.update(3.1f, 11_000L))
    }
}

class BeaconProximityTest {
    private val uuid = "18516F0F-64DE-42B1-8707-F02A087E5733"
    private val loud = RegisteredBeacon("BEACON_001", "iphone17", uuid, 1, 1, floorId = "L0", rssiAt1m = -45)
    private val quiet = RegisteredBeacon("BEACON_002", "OnePlus", uuid, 2, 2, floorId = "L0", rssiAt1m = -70)

    /** A beacon heard [meters] away, as its signal would be with path-loss exponent 2.5. */
    private fun at(beacon: RegisteredBeacon, meters: Double): HeardBeacon {
        val rssi = (beacon.rssiAt1m!! - 25 * kotlin.math.log10(meters)).roundToIntExact()
        return HeardBeacon(beacon, NearbyDevice(BeaconIdentity.IBeacon(uuid, beacon.major!!, beacon.minor!!, -59), "AA", null, rssi, 5, 0L))
    }

    private fun Double.roundToIntExact() = kotlin.math.round(this).toInt()

    @Test
    fun aBeaconShowsOnlyWithinHalfAMeter() {
        val proximity = BeaconProximity()
        assertEquals(emptySet<String>(), proximity.update(listOf(at(loud, 1.5), at(quiet, 2.0))))
        assertEquals(setOf("BEACON_001"), proximity.update(listOf(at(loud, 0.4), at(quiet, 2.0))))
    }

    @Test
    fun aQuietBeaconShowsWhenCloseToo() {
        // Calibrated, its weak signal still says 0.4 m.
        assertEquals(setOf("BEACON_002"), BeaconProximity().update(listOf(at(quiet, 0.4), at(loud, 3.0))))
    }

    @Test
    fun itStaysShownAroundTheEdgeAndHidesFartherAway() {
        val proximity = BeaconProximity()
        proximity.update(listOf(at(loud, 0.45)))
        assertEquals("a wobble to 0.6 m", setOf("BEACON_001"), proximity.update(listOf(at(loud, 0.6))))
        assertEquals("walked away", emptySet<String>(), proximity.update(listOf(at(loud, 1.0))))
        assertEquals("not back within 0.5 m yet", emptySet<String>(), proximity.update(listOf(at(loud, 0.6))))
    }

    @Test
    fun aBeaconNoLongerHeardIsGone() {
        val proximity = BeaconProximity()
        proximity.update(listOf(at(loud, 0.3)))
        assertEquals(emptySet<String>(), proximity.update(emptyList()))
    }
}
