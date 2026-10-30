package com.app.androidnativearcore.wayfinder.core

import java.util.Random
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconSpotFinderTest {
    /** The phone is held at 1.3 m; the beacon sits on a desk below it. */
    private val beacon = Vec3(2f, 0.75f, 3f)

    /** Feeds the readings a phone would hear walking [path] (a reading every 100 ms at 0.8 m/s, ±4 dB of noise). */
    private fun walk(
        finder: BeaconSpotFinder,
        path: List<Vec3>,
        at: Vec3 = beacon,
        strengthAt1m: Double = -62.0,
        seed: Long = 7,
        startMillis: Long = 0L,
    ) {
        val noise = Random(seed)
        var time = startMillis
        for ((from, to) in path.zipWithNext()) {
            val steps = (from.distanceTo(to) / 0.08f).toInt().coerceAtLeast(1)
            for (i in 0 until steps) {
                val t = i / steps.toFloat()
                val phone = Vec3(from.x + (to.x - from.x) * t, from.y, from.z + (to.z - from.z) * t)
                val d = phone.distanceTo(at).toDouble()
                val rssi = strengthAt1m - 25.0 * log10(d) + noise.nextGaussian() * 4.0
                finder.add(phone, rssi.toInt(), time)
                time += 100
            }
        }
    }

    private fun Vec3.horizontalDistanceTo(o: Vec3) = sqrt((x - o.x) * (x - o.x) + (z - o.z) * (z - o.z))

    /** Walking up to the beacon from across the room, past it, and turning: how a visitor arrives at it. */
    private val walkUpAndTurn = listOf(Vec3(-3f, 1.3f, -2f), Vec3(1.5f, 1.3f, 2.5f), Vec3(3.5f, 1.3f, 2f))

    /** A loop around the beacon, 2.5 m out. */
    private val loopAround = listOf(Vec3(-0.5f, 1.3f, 0.5f), Vec3(4.5f, 1.3f, 0.5f), Vec3(4.5f, 1.3f, 5.5f), Vec3(-0.5f, 1.3f, 5.5f), Vec3(-0.5f, 1.3f, 0.5f))

    private fun found(finder: BeaconSpotFinder) =
        finder.estimate() as? BeaconSpotFinder.Estimate.Found ?: error("not found: ${finder.estimate()}")

    @Test
    fun walkingUpToTheBeaconFindsIt() {
        val finder = BeaconSpotFinder()
        walk(finder, walkUpAndTurn)
        val spot = found(finder)
        val off = spot.spot.horizontalDistanceTo(beacon)
        assertTrue("within a meter (was $off m off)", off < 1f)
        assertTrue("says how sure it is (${spot.uncertaintyMeters} m)", spot.uncertaintyMeters in BeaconSpotFinder.MIN_UNCERTAINTY_M..BeaconSpotFinder.MAX_UNCERTAINTY_M)
        assertEquals("on the desk below the phone", 0.75f, spot.spot.y, 0.01f)
    }

    @Test
    fun walkingAroundTheBeaconFindsItClosely() {
        val finder = BeaconSpotFinder()
        walk(finder, loopAround)
        assertTrue(found(finder).spot.horizontalDistanceTo(beacon) < 0.5f)
    }

    @Test
    fun itHoldsUpWithDifferentNoise() {
        val offs = (1L..10L).mapNotNull { seed ->
            val finder = BeaconSpotFinder()
            walk(finder, walkUpAndTurn, seed = seed)
            (finder.estimate() as? BeaconSpotFinder.Estimate.Found)?.spot?.horizontalDistanceTo(beacon)
        }
        assertTrue("found in nearly every walk (${offs.size}/10)", offs.size >= 9)
        assertTrue("never far off ($offs)", offs.max() < 1.6f)
        assertTrue("well under a meter on average ($offs)", offs.average() < 0.8)
    }

    @Test
    fun aWeakBeaconIsFoundToo() {
        // Beacons differ in strength (a phone app may send weakly): the fit works out each beacon's own strength.
        val finder = BeaconSpotFinder()
        walk(finder, walkUpAndTurn, strengthAt1m = -76.0, seed = 3)
        assertTrue(found(finder).spot.horizontalDistanceTo(beacon) < 1f)
    }

    @Test
    fun calibrationFindsAQuietBeaconFromAWalkPast() {
        // One walk past a quiet beacon at a distance: not enough to tell its spot unless its strength is known.
        val walkPast = listOf(Vec3(-2f, 1.3f, 0f), Vec3(4.5f, 1.3f, 0f), Vec3(4.5f, 1.3f, 5f))
        val unknown = BeaconSpotFinder()
        walk(unknown, walkPast, strengthAt1m = -76.0)
        assertTrue("${unknown.estimate()}", unknown.estimate() !is BeaconSpotFinder.Estimate.Found)
        val calibrated = BeaconSpotFinder(strengthAt1m = -74) // calibrated with another phone: 2 dB off
        walk(calibrated, walkPast, strengthAt1m = -76.0)
        assertTrue(found(calibrated).spot.horizontalDistanceTo(beacon) < 1.2f)
    }

    @Test
    fun aMovedBeaconIsFoundWhereItIsNow() {
        // Found once, then moved 4 m: two minutes later, a walk around its new spot finds it there, not at the old one.
        val finder = BeaconSpotFinder()
        walk(finder, walkUpAndTurn)
        val movedTo = Vec3(beacon.x - 4f, beacon.y, beacon.z)
        val loopThere = loopAround.map { Vec3(it.x - 4f, it.y, it.z) }
        walk(finder, loopThere, at = movedTo, startMillis = 120_000L)
        assertTrue(found(finder).spot.horizontalDistanceTo(movedTo) < 0.8f)
    }

    @Test
    fun aHintSettlesAStraightWalk() {
        // A straight walk alone can't tell which side the beacon is on; where the camera pointed (the hint) can.
        val finder = BeaconSpotFinder()
        val at = Vec3(0f, 0.75f, 2.5f)
        finder.hint(Vec3(0.4f, 0.75f, 2.2f))
        walk(finder, listOf(Vec3(-4f, 1.3f, 0f), Vec3(4f, 1.3f, 0f)), at = at)
        val spot = found(finder).spot
        assertTrue("on the right side, near the beacon (${spot.horizontalDistanceTo(at)} m off)", spot.horizontalDistanceTo(at) < 1f)
    }

    @Test
    fun aLongWalkOutweighsAWrongHint() {
        val finder = BeaconSpotFinder()
        finder.hint(Vec3(beacon.x + 2f, beacon.y, beacon.z - 1f)) // pointed 2.2 m off
        walk(finder, loopAround + loopAround)
        assertTrue(found(finder).spot.horizontalDistanceTo(beacon) < 0.8f)
    }

    @Test
    fun aStraightWalkCantTellWhichSideTheBeaconIsOn() {
        val finder = BeaconSpotFinder()
        walk(finder, listOf(Vec3(-4f, 1.3f, 0f), Vec3(4f, 1.3f, 0f)), at = Vec3(0f, 0.75f, 2.5f))
        val estimate = finder.estimate()
        assertTrue("$estimate", estimate is BeaconSpotFinder.Estimate.Unclear && estimate.sideways)
    }

    @Test
    fun standingStillIsNotEnough() {
        val finder = BeaconSpotFinder()
        walk(finder, listOf(Vec3(0f, 1.3f, 0f), Vec3(0.05f, 1.3f, 0f)))
        repeat(60) { finder.add(Vec3(0f, 1.3f, 0f), -65, 1_000L + it * 100L) }
        assertTrue(finder.estimate() is BeaconSpotFinder.Estimate.Gathering)
    }

    @Test
    fun aFewReadingsAreNotEnough() {
        val finder = BeaconSpotFinder()
        walk(finder, listOf(Vec3(0f, 1.3f, 0f), Vec3(0.3f, 1.3f, 0f)))
        val estimate = finder.estimate()
        assertTrue("$estimate", estimate is BeaconSpotFinder.Estimate.Gathering)
    }
}
