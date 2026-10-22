package com.app.androidnativearcore.wayfinder.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun yawDegreesOf(m: Mat4) = Math.toDegrees(AlignmentFilter.yaw(m).toDouble()).toFloat()

class AlignmentRobustnessTest {
    private val truth = Mat4.yaw(10f, Vec3(2f, 0f, 3f))

    @Test
    fun aWildReadingAmongGoodOnesIsIgnored() {
        val filter = AlignmentFilter()
        // Six good close-ups with a centimeter of jitter, then one reading 30 cm and 8 degrees off (a reflection, blur).
        for (i in 0 until 6) filter.update(Mat4.yaw(10f, Vec3(2f + if (i % 2 == 0) 0.01f else -0.01f, 0f, 3f)), 1f)
        filter.update(Mat4.yaw(18f, Vec3(2.3f, 0f, 3f)), 1f)
        assertEquals(1, filter.rejectedSightings)
        assertTrue("x stayed on the truth", abs(filter.alignment.translation.x - 2f) < 0.01f)
        assertTrue("yaw stayed on the truth", abs(yawDegreesOf(filter.alignment) - 10f) < 0.3f)
    }

    @Test
    fun closeUpsSurviveWalkingAwayWithTheSignInView() {
        // Four close-ups read the truth. Then twelve far sightings, all 2.5 degrees off, arrive while the user walks
        // away. Keeping only the most recent twelve would leave nothing but the far ones (2.5 degrees of error).
        val filter = AlignmentFilter()
        repeat(4) { filter.update(truth, 1f) }
        repeat(12) { filter.update(Mat4.yaw(12.5f, Vec3(2f, 0f, 3f)), 4f) }
        val error = abs(yawDegreesOf(filter.alignment) - 10f)
        assertTrue("yaw error $error should stay under 1 degree", error < 1f)
    }

    @Test
    fun freshSightingsTakeOverEventually() {
        // Long after the first close-ups, a new close scan (the session drifted by 20 cm) replaces them.
        val filter = AlignmentFilter()
        repeat(4) { filter.update(truth, 1f) }
        repeat(30) { filter.update(Mat4.yaw(10f, Vec3(2.2f, 0f, 3f)), 1f) }
        assertTrue(abs(filter.alignment.translation.x - 2.2f) < 0.01f)
    }

    @Test
    fun lockInWaitsUntilTheReadingsAgree() {
        val filter = AlignmentFilter()
        val views = listOf(0f, 0.1f, 0.2f)
        // Three close-ups from different spots, but 4 degrees either way: too noisy to trust yet.
        views.forEachIndexed { i, dx ->
            filter.update(Mat4.yaw(10f + if (i % 2 == 0) 4f else -4f, Vec3(2f, 0f, 3f)), 1.2f, 1f, Vec3(2f + dx, 1.4f, 5f))
        }
        assertFalse("disagreeing readings don't lock in", filter.isLockedIn)
        assertFalse(filter.sightingsAgree)
        // Patience: after eight close-ups it gives in, using the average.
        repeat(5) { filter.update(truth, 1.2f, 1f, Vec3(2.3f, 1.4f, 5f)) }
        assertTrue(filter.isLockedIn)
    }

    @Test
    fun agreeingReadingsLockInAtThree() {
        val filter = AlignmentFilter()
        listOf(0f, 0.1f, 0.2f).forEach { dx -> filter.update(truth, 1.2f, 1f, Vec3(2f + dx, 1.4f, 5f)) }
        assertTrue(filter.sightingsAgree)
        assertTrue(filter.isLockedIn)
    }
}

class WallRefinementTest {
    /** A wall sign at [center] whose picture faces [degrees] from +z (the way MarkerAlignment builds a wall sign). */
    private fun signFacing(degrees: Float, center: Vec3 = Vec3(1f, 1.4f, 2f)): Mat4 {
        val r = Math.toRadians(degrees.toDouble())
        val facing = Vec3(sin(r).toFloat(), 0f, cos(r).toFloat())
        val bottom = -Vec3.UP
        return Mat4.fromColumns(facing cross bottom, facing, bottom, center)
    }

    private val wallPoint = Vec3(0f, 1f, 2f)       // the wall is the plane z = 2, facing +z
    private val wallNormal = Vec3(0f, 0f, 1f)

    @Test
    fun aSignOnTheWallTakesTheWallsFacing() {
        val seen = signFacing(4f) // the picture read 4 degrees off
        val refined = WallRefinement.refine(seen, wallPoint, wallNormal)
        assertNotNull(refined)
        val facing = refined!!.column(1)
        assertEquals(0.0, facing.x.toDouble(), 1e-5)
        assertEquals(1.0, facing.z.toDouble(), 1e-5)
        assertEquals(seen.translation, refined.translation)
        // The venue alignment built from it carries no yaw error any more.
        val signInVenue = signFacing(0f, Vec3.ZERO)
        val alignment = MarkerAlignment.alignment(refined, signInVenue)
        assertEquals(0.0, yawDegreesOf(alignment).toDouble(), 1e-3)
    }

    @Test
    fun aWallNormalPointingTheOtherWayIsFlipped() {
        val refined = WallRefinement.refine(signFacing(3f), wallPoint, -wallNormal)
        assertNotNull(refined)
        assertTrue(refined!!.column(1).z > 0.99f)
    }

    @Test
    fun aSignOffTheWallOrTurnedAwayIsLeftAlone() {
        assertNull("10 cm in front of the wall", WallRefinement.refine(signFacing(2f, Vec3(1f, 1.4f, 2.1f)), wallPoint, wallNormal))
        assertNull("turned 20 degrees to the wall", WallRefinement.refine(signFacing(20f), wallPoint, wallNormal))
        assertNull("a floor is not a wall", WallRefinement.refine(signFacing(2f), wallPoint, Vec3.UP))
    }
}

class SurfaceChoiceTest {
    private fun c(distance: Float, plane: Boolean) = SurfaceChoice.Candidate(distance, plane)

    @Test
    fun theFloorBehindATappedSofaIsNotUsed() {
        // Ray hits the sofa (depth point) at 1.2 m, then the floor plane at 2.5 m behind it.
        assertEquals(0, SurfaceChoice.choose(listOf(c(1.2f, false), c(2.5f, true))))
    }

    @Test
    fun aPlaneAtTheTappedSpotIsPreferred() {
        // A table top plane at 1.25 m and a depth point on it at 1.2 m: the same surface, so the plane anchors better.
        assertEquals(1, SurfaceChoice.choose(listOf(c(1.2f, false), c(1.25f, true))))
        assertEquals(0, SurfaceChoice.choose(listOf(c(3f, true))))
    }

    @Test
    fun nothingToChooseFrom() {
        assertNull(SurfaceChoice.choose(emptyList()))
    }
}
