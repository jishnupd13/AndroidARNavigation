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

class BeaconDetectorTest {
    private val uuid = "18516F0F-64DE-42B1-8707-F02A087E5733"
    private val iphone = RegisteredBeacon("BEACON_001", "iphone17", uuid, 1, 1, floorId = "L0", rssiAt1m = -50, rssiAt05m = -43)
    private val onePlus = RegisteredBeacon("BEACON_002", "OnePlus", uuid, 2, 2, floorId = "L0", rssiAt1m = -72, rssiAt05m = -64)
    private val here = Vec3(0f, 1.3f, 0f)

    private fun heard(beacon: RegisteredBeacon, rssi: Int) =
        HeardBeacon(beacon, NearbyDevice(BeaconIdentity.IBeacon(uuid, beacon.major!!, beacon.minor!!, -59), "AA", null, rssi, 5, 0L))

    /** Feeds the same signals every half second from [from] to [to] (ms); returns the last detections. */
    private fun BeaconDetector.hold(signals: List<HeardBeacon>, from: Long, to: Long, phone: Vec3? = here): Set<String> {
        var out = emptySet<String>()
        var t = from
        while (t <= to) { out = update(signals, phone, t); t += 500 }
        return out
    }

    @Test
    fun aWeakOrMediumSignalIsNeverDetected() {
        val detector = BeaconDetector()
        assertEquals("heard, 2 m away", emptySet<String>(), detector.hold(listOf(heard(iphone, -58)), 0, 10_000))
        assertEquals("medium, 1 m away", emptySet<String>(), detector.hold(listOf(heard(iphone, -50)), 10_500, 20_000))
        assertEquals(SignalLevel.MEDIUM, heard(iphone, -50).signalLevel)
        assertEquals(SignalLevel.WEAK, heard(iphone, -60).signalLevel)
    }

    @Test
    fun aStrongSignalIsDetectedOnceItHolds() {
        val detector = BeaconDetector()
        assertEquals("one moment is not enough", emptySet<String>(), detector.hold(listOf(heard(iphone, -42)), 0, 1_000))
        assertEquals(setOf("BEACON_001"), detector.hold(listOf(heard(iphone, -42)), 1_500, 2_000))
        assertEquals(SignalLevel.STRONG, heard(iphone, -42).signalLevel)
    }

    @Test
    fun aCalibrationNeverMakesItMoreLenientThanAStrongSignal() {
        // Calibrated at 0.5 m with something in the way, the OnePlus read −64: that is about 2 m really.
        assertEquals(STRONG_SIGNAL_DBM, heard(onePlus, -63).detectRssi)
        assertEquals("not at −63", emptySet<String>(), BeaconDetector().hold(listOf(heard(onePlus, -63)), 0, 10_000))
        assertEquals(setOf("BEACON_002"), BeaconDetector().hold(listOf(heard(onePlus, -53)), 0, 2_000))
        assertEquals("a stricter calibration stays", -43, heard(iphone, -40).detectRssi)
    }

    @Test
    fun onlyTheClearlyNearestBeaconIsDetected() {
        // Both strong, about as near: neither is clearly the one the visitor is at.
        assertEquals(emptySet<String>(), BeaconDetector().hold(listOf(heard(iphone, -42), heard(onePlus, -52)), 0, 10_000))
        // The iPhone well ahead (6 dB or more over its threshold than the other): only it.
        assertEquals(setOf("BEACON_001"), BeaconDetector().hold(listOf(heard(iphone, -40), heard(onePlus, -60)), 0, 2_000))
    }

    @Test
    fun turningAroundKeepsTheBeaconDetected() {
        val detector = BeaconDetector()
        detector.hold(listOf(heard(iphone, -42)), 0, 2_000)
        // Facing away: the body blocks the signal (−14 dB) for a long time, but the phone hasn't moved.
        assertEquals(setOf("BEACON_001"), detector.hold(listOf(heard(iphone, -56)), 2_500, 20_000))
    }

    @Test
    fun walkingAwayLosesTheBeacon() {
        val detector = BeaconDetector()
        detector.hold(listOf(heard(iphone, -42)), 0, 2_000)
        assertEquals("1.2 m from where it was detected", emptySet<String>(), detector.update(listOf(heard(iphone, -44)), Vec3(1.2f, 1.3f, 0f), 2_500))
    }

    @Test
    fun aWeakSignalAfterMovingABitLosesTheBeacon() {
        val detector = BeaconDetector()
        detector.hold(listOf(heard(iphone, -42)), 0, 2_000)
        val stepped = Vec3(0.5f, 1.3f, 0f)
        assertEquals("not yet", setOf("BEACON_001"), detector.hold(listOf(heard(iphone, -54)), 2_500, 4_000, stepped))
        assertEquals(emptySet<String>(), detector.hold(listOf(heard(iphone, -54)), 4_500, 6_000, stepped))
    }

    @Test
    fun aBeaconNotHeardForAWhileIsGone() {
        val detector = BeaconDetector()
        detector.hold(listOf(heard(iphone, -42)), 0, 2_000)
        assertEquals(setOf("BEACON_001"), detector.hold(emptyList(), 2_500, 8_000))
        assertEquals(emptySet<String>(), detector.hold(emptyList(), 8_500, 13_000))
    }

    @Test
    fun aBeaconIsDetectedOnce() {
        val detector = BeaconDetector()
        assertEquals(setOf("BEACON_001"), detector.hold(listOf(heard(iphone, -42), heard(iphone, -41)), 0, 2_000))
    }

    @Test
    fun withoutA05mCalibrationTheThresholdIsProjectedFrom1m() {
        val only1m = iphone.copy(rssiAt05m = null)
        assertEquals("−50 at 1 m: −42 at 0.5 m", -42, heard(only1m, -42).detectRssi)
        assertTrue(!heard(only1m, -42).thresholdCalibrated)
    }

    @Test
    fun anUncalibratedBeaconGetsACautiousThreshold() {
        // It advertises −91 dBm at 1 m (a misconfigured beacon app): trusting that made it "within 0.5 m" at −79.
        val uncalibrated = onePlus.copy(rssiAt1m = null, rssiAt05m = null)
        fun signal(advertised: Int, rssi: Int) =
            HeardBeacon(uncalibrated, NearbyDevice(BeaconIdentity.IBeacon(uuid, 2, 2, advertised), "AA", null, rssi, 5, 0L))
        assertEquals("the same for every uncalibrated beacon", STRONG_SIGNAL_DBM, signal(-91, -79).detectRssi)
        assertEquals(STRONG_SIGNAL_DBM, signal(-45, -79).detectRssi)
        assertEquals(emptySet<String>(), BeaconDetector().hold(listOf(signal(-91, -79)), 0, 10_000))
        assertEquals(setOf("BEACON_002"), BeaconDetector().hold(listOf(signal(-91, -54)), 0, 2_000))
        assertEquals("a medium signal isn't enough", emptySet<String>(), BeaconDetector().hold(listOf(signal(-91, -62)), 0, 10_000))
        assertTrue(!signal(-45, -54).isCalibrated)
    }

    @Test
    fun aNonsenseAdvertisedStrengthIsIgnored() {
        val uncalibrated = onePlus.copy(rssiAt1m = null, rssiAt05m = null)
        val misconfigured = HeardBeacon(uncalibrated, NearbyDevice(BeaconIdentity.IBeacon(uuid, 2, 2, -91), "AA", null, -79, 5, 0L))
        assertEquals(null, misconfigured.strengthAt1m)
        assertEquals("judged against a usual beacon instead", SignalLevel.WEAK, misconfigured.signalLevel)
    }
}
