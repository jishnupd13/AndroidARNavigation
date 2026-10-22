package com.app.androidnativearcore.wayfinder.core

import kotlin.math.abs
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

class ReferencePointLockInTest {
    private val truth = Mat4.yaw(10f, Vec3(2f, 0f, 3f))

    @Test
    fun aReferencePhotoNeedsTwoRealReadingsNotOne() {
        // Captured reference points used to lock in on their very first match, counted three times as a perfect
        // close-up. Now one match is not enough...
        val filter = AlignmentFilter()
        filter.update(truth, 3.5f, 0.5f, null, countAsClose = true)
        assertFalse(filter.isLockedIn)
        // ...two are, even from 3.5 m at an angle (a photo is recognized from about where it was taken).
        filter.update(truth, 3.5f, 0.5f, null, countAsClose = true)
        assertTrue(filter.isLockedIn)
    }

    @Test
    fun aFarReferenceReadingCountsLessThanACloseOne() {
        // A close reading of the truth and a far one 10 cm off: the result sits near the close one.
        val filter = AlignmentFilter()
        filter.update(truth, 1f, 1f, null, countAsClose = true)
        filter.update(Mat4.yaw(10f, Vec3(2.1f, 0f, 3f)), 4f, 1f, null, countAsClose = true)
        assertTrue(abs(filter.alignment.translation.x - 2f) < 0.02f)
    }
}

class ReferenceGeometryTest {
    @Test
    fun facingComesFromThePhotosRightEdge() {
        // A photo whose right edge points along +x faces +z (0 degrees); along -z it faces +x (90 degrees).
        assertEquals(0f, ReferenceGeometry.facingDegrees(Vec3(1f, 0f, 0f)), 1e-3f)
        assertEquals(90f, ReferenceGeometry.facingDegrees(Vec3(0f, 0f, -1f)), 1e-3f)
        // A slightly tilted right edge (phone rolled while looking down) still gives the horizontal direction.
        assertEquals(0f, ReferenceGeometry.facingDegrees(Vec3(0.99f, 0.14f, 0f)), 1e-3f)
        // It agrees with how MarkerAlignment builds a wall marker: right = facing x (-up).
        val marker = Venue.Marker("m", LocalizedText("m"), "", 0.2f, "L0", 0f, 1f, 0f, 37f, Venue.MarkerMounting.WALL)
        assertEquals(37f, ReferenceGeometry.facingDegrees(MarkerAlignment.transform(marker).column(0)), 1e-2f)
    }

    @Test
    fun theReferencePhotoIsCutInA4Proportions() {
        // Phone in portrait: the 1280 x 720 sensor image is turned upright, so the photo's width runs along its 720 px.
        val (w, h) = ReferenceGeometry.a4Crop(1280, 720, rotatedToUpright = true)
        assertEquals(360f, h, 1e-3f)                    // upright width: half the shorter side
        assertEquals(360f * 297f / 210f, w, 1e-3f)      // upright height: A4's 297/210 of that
        assertTrue("fits the image", w <= 1280f && h <= 720f)
        // Phone in landscape: no turn, so the width runs along the image's width.
        val (lw, lh) = ReferenceGeometry.a4Crop(1280, 720, rotatedToUpright = false)
        assertEquals(360f, lw, 1e-3f)
        assertEquals(297f / 210f, lh / lw, 1e-4f)
        assertTrue("fits the image", lh <= 720f)
    }

    @Test
    fun widthUsesDepthAlongTheCameraAxis() {
        // 216 px of a 500 px focal length at 2 m deep is 86.4 cm.
        assertEquals(0.864f, ReferenceGeometry.physicalWidth(2f, 216f, 500f), 1e-4f)
    }

    @Test
    fun aFlatSurfaceHeadOnIsFine() {
        assertNull(ReferenceGeometry.problem(2f, listOf(2f, 2f, 2f, 2f)))
    }

    @Test
    fun aFlatSurfaceAtAModerateAngleIsFine() {
        // A wall turned about 25 degrees: inverse depth changes evenly from left to right, so the middle fits.
        val left = 1f / (0.5f + 0.05f); val right = 1f / (0.5f - 0.05f)
        assertNull(ReferenceGeometry.problem(2f, listOf(left, right, left, right)))
    }

    @Test
    fun anObjectInFrontOfAWallIsNotFlat() {
        // A bottle 0.6 m away in the middle, the wall 2 m away at every corner.
        assertNotNull(ReferenceGeometry.problem(0.6f, listOf(2f, 2f, 2f, 2f)))
    }

    @Test
    fun aSteepViewIsReported() {
        // Corners from 1.2 m to 3 m deep around a 2 m middle: seen almost edge-on.
        val problem = ReferenceGeometry.problem(2f, listOf(1.2f, 3f, null, null))
        assertNotNull(problem)
        assertTrue(problem!!.contains("steep"))
    }

    @Test
    fun unmeasuredCornersAreNotHeldAgainstIt() {
        assertNull(ReferenceGeometry.problem(2f, listOf(null, null, null, null)))
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
        assertEquals(1, SurfaceChoice.choose(listOf(c(1.2f, false), c(1.25f, true))))
        assertEquals(0, SurfaceChoice.choose(listOf(c(3f, true))))
    }

    @Test
    fun nothingToChooseFrom() {
        assertNull(SurfaceChoice.choose(emptyList()))
    }
}
