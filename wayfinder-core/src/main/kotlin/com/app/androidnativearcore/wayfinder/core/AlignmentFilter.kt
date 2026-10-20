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

    /**
     * How well the recent sightings agree, as the weighted spread of their positions in centimeters and of their
     * yaw in degrees. Small numbers (a few cm, under 1°) mean a good, stable alignment; large ones mean noisy
     * sightings: a sign of the wrong size, a tilted or moving sign, or a bad angle. 0 until there are two sightings.
     */
    var spreadCm: Float = 0f
        private set
    var spreadDegrees: Float = 0f
        private set

    /** Where the camera was for each close, frontal sighting (positions in the session frame). */
    private val closeViews = ArrayList<Vec3>()

    /** How far apart (meters) the camera was across the close, frontal sightings: the variety of viewpoints. */
    val viewSpreadMeters: Float
        get() {
            var most = 0f
            for (i in closeViews.indices) for (j in i + 1 until closeViews.size) {
                most = maxOf(most, horizontalDistance(closeViews[i], closeViews[j]))
            }
            return most
        }

    /**
     * True once enough close, frontal sightings have settled the alignment (precise enough to start recording).
     * The sightings must come from different viewpoints (the camera moved a little): a sign's pose error depends on
     * the viewpoint, so sightings from one spot share the same error and averaging them doesn't remove it. If the
     * user cannot or does not move, [PATIENCE_SIGHTINGS] sightings are accepted anyway.
     */
    val isLockedIn: Boolean
        get() = isAligned && closeSightings >= LOCK_IN_SIGHTINGS &&
            (closeViews.isEmpty() || viewSpreadMeters >= MIN_VIEW_SPREAD_M || closeSightings >= PATIENCE_SIGHTINGS)

    /**
     * Adds [candidate] (the alignment implied by one sighting, from [MarkerAlignment.alignment]) to the window.
     * [distanceMeters] is how far the camera was from the sign. Returns true if the alignment changed.
     */
    fun update(candidate: Mat4, distanceMeters: Float, viewCosine: Float = 1f, camera: Vec3? = null): Boolean {
        // Seen head-on, a sign's pose is precise; at a steep angle it is poor (a flat image is ambiguous when
        // viewed from the side), so such a sighting counts for much less and doesn't help lock in.
        val cosine = viewCosine.coerceIn(0f, 1f)
        if (distanceMeters <= CLOSE_DISTANCE_M && cosine >= MIN_FRONTAL_COSINE) {
            closeSightings += 1
            if (camera != null) {
                closeViews += camera
                if (closeViews.size > WINDOW) closeViews.removeAt(0)
            }
        }
        val t = candidate.translation
        samples.addLast(Sample(yaw(candidate), t.x, t.y, t.z, weight(distanceMeters) * maxOf(0.1f, cosine * cosine)))
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
        spreadCm = 0f
        spreadDegrees = 0f
        samples.clear()
        closeViews.clear()
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
        val meanYaw = atan2(sinSum, cosSum)
        val yawDegrees = Math.toDegrees(meanYaw.toDouble()).toFloat()
        val mean = Vec3(x / total, y / total, z / total)
        // Weighted spread of the sightings around their average.
        var squares = 0f
        var yawSquares = 0f
        for (s in samples) {
            val dx = s.x - mean.x; val dz = s.z - mean.z
            squares += (dx * dx + dz * dz) * s.weight
            var dyaw = s.yaw - meanYaw
            while (dyaw > Math.PI) dyaw -= (2 * Math.PI).toFloat()
            while (dyaw < -Math.PI) dyaw += (2 * Math.PI).toFloat()
            yawSquares += dyaw * dyaw * s.weight
        }
        spreadCm = if (samples.size < 2) 0f else kotlin.math.sqrt(squares / total) * 100f
        spreadDegrees = if (samples.size < 2) 0f else Math.toDegrees(kotlin.math.sqrt(yawSquares / total).toDouble()).toFloat()
        return Mat4.yaw(yawDegrees, mean)
    }

    companion object {
        private const val MIN_CHANGE_METERS = 0.015f
        private const val MIN_CHANGE_DEGREES = 0.3f

        /** How many recent sightings are averaged. */
        const val WINDOW = 12

        /** Sightings from within this distance (meters) are precise enough to count toward the lock-in. */
        const val CLOSE_DISTANCE_M = 2.5f
        const val LOCK_IN_SIGHTINGS = 3

        /** A sighting counts as frontal when the camera is within about 53° of straight in front of the sign. */
        const val MIN_FRONTAL_COSINE = 0.6f

        /** The camera must move this far (meters) during lock-in, so the sightings come from different viewpoints. */
        const val MIN_VIEW_SPREAD_M = 0.12f

        /** After this many close, frontal sightings the viewpoint variety is no longer required. */
        const val PATIENCE_SIGHTINGS = 8

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
