package com.app.androidnativearcore.wayfinder.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Keeps the venue's pose in the AR session ([alignment]) up to date from marker sightings.
 *
 * The iOS app replaces the alignment with each new sighting. A single sighting is noisy, and much more so from
 * several meters away than up close (the error of a sign's pose grows with distance). This filter instead averages
 * the last [WINDOW] sightings, each weighted by how close the camera was ([weight]), so close-ups dominate and one
 * bad reading can't move the venue. Yaw is averaged on the circle, so 179° and -179° average to 180°, not 0°.
 *
 * Changes of less than [MIN_CHANGE_METERS] / [MIN_CHANGE_DEGREES] are ignored so the scene doesn't jitter.
 */
class AlignmentFilter {
    private class Sample(val yaw: Float, val x: Float, val y: Float, val z: Float, val weight: Float)

    private val samples = ArrayDeque<Sample>()

    var alignment: Mat4 = Mat4.IDENTITY
        private set
    var isAligned: Boolean = false
        private set

    /** How many sightings from within [CLOSE_DISTANCE_M] have been seen: the alignment is "locked in" after a few. */
    var closeSightings: Int = 0
        private set

    /** True once enough close-up sightings have settled the alignment (precise enough to start recording). */
    val isLockedIn: Boolean get() = isAligned && closeSightings >= LOCK_IN_SIGHTINGS

    /**
     * Adds [candidate] (the alignment implied by one sighting, from [MarkerAlignment.alignment]) to the window.
     * [distanceMeters] is how far the camera was from the sign. Returns true if the alignment changed.
     */
    fun update(candidate: Mat4, distanceMeters: Float): Boolean {
        if (distanceMeters <= CLOSE_DISTANCE_M) closeSightings += 1
        val t = candidate.translation
        samples.addLast(Sample(yaw(candidate), t.x, t.y, t.z, weight(distanceMeters)))
        while (samples.size > WINDOW) samples.removeFirst()

        val averaged = average()
        if (!isAligned) {
            alignment = averaged
            isAligned = true
            return true
        }
        val change = MarkerAlignment.difference(averaged, alignment)
        if (!(change.meters > MIN_CHANGE_METERS || change.degrees > MIN_CHANGE_DEGREES)) return false
        alignment = averaged
        return true
    }

    fun reset() {
        samples.clear()
        alignment = Mat4.IDENTITY
        isAligned = false
        closeSightings = 0
    }

    private fun average(): Mat4 {
        var total = 0f
        var x = 0f; var y = 0f; var z = 0f
        var sinSum = 0f; var cosSum = 0f
        for (s in samples) {
            total += s.weight
            x += s.x * s.weight; y += s.y * s.weight; z += s.z * s.weight
            sinSum += sin(s.yaw) * s.weight; cosSum += cos(s.yaw) * s.weight
        }
        val yawDegrees = Math.toDegrees(atan2(sinSum, cosSum).toDouble()).toFloat()
        return Mat4.yaw(yawDegrees, Vec3(x / total, y / total, z / total))
    }

    companion object {
        private const val MIN_CHANGE_METERS = 0.015f
        private const val MIN_CHANGE_DEGREES = 0.3f

        /** How many recent sightings are averaged. */
        const val WINDOW = 12

        /** Sightings from within this distance (meters) are precise enough to count toward the lock-in. */
        const val CLOSE_DISTANCE_M = 2.5f
        const val LOCK_IN_SIGHTINGS = 3

        /** How much a sighting from [distanceMeters] away counts: 1.0 at 0.5 m, 0.25 at 1.5 m, 0.05 at 4 m. */
        fun weight(distanceMeters: Float): Float {
            val d = 0.5f + distanceMeters.coerceAtLeast(0f)
            return 1f / (d * d)
        }

        /** Yaw (radians, about +Y) of a levelled alignment. */
        fun yaw(m: Mat4): Float {
            val x = m.column(0)
            return atan2(-x.z, x.x)
        }
    }
}
