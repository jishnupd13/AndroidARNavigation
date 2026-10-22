package com.app.androidnativearcore.wayfinder.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keeps the venue's pose in the AR session ([alignment]) up to date from marker sightings.
 *
 * The iOS app replaces the alignment with each new sighting. A single sighting is noisy, and much more so from
 * several meters away than up close (the error of a sign's pose grows with distance). This filter instead averages
 * up to [WINDOW] sightings, each weighted by how close the camera was ([weight]) and how head-on it looked, so
 * close-ups dominate and one bad reading can't move the venue. Yaw is averaged on the circle, so 179° and -179°
 * average to 180°, not 0°.
 *
 * Three things keep the estimate accurate as sightings pile up:
 * - **The best sightings stay.** When the window is full, the sighting with the least weight leaves, not simply the
 *   oldest one. Walking away with the sign still in view used to push the close-ups out and leave only far, noisy
 *   sightings. A sighting's claim to stay fades slowly as newer ones arrive ([RECENCY]), so a long time later fresh
 *   sightings do take over (ARCore drifts, so very old ones become stale).
 * - **Outliers are ignored.** A sighting whose position or yaw is far from the median of the others (a reflection,
 *   motion blur, a half-hidden sign) is left out of the average.
 * - **Lock-in waits for agreement.** The alignment is only trusted ([isLockedIn]) once the sightings agree within
 *   [MAX_LOCK_SPREAD_CM] and [MAX_LOCK_SPREAD_DEGREES], or after [PATIENCE_SIGHTINGS] close-ups if they never do.
 *
 * Changes of less than [MIN_CHANGE_METERS] / [MIN_CHANGE_DEGREES] are ignored so the scene doesn't jitter.
 */
class AlignmentFilter {
    private class Sample(val yaw: Float, val x: Float, val y: Float, val z: Float, val weight: Float, val sequence: Long)

    private val samples = ArrayList<Sample>()
    private var nextSequence = 0L

    var alignment: Mat4 = Mat4.IDENTITY
        private set
    var isAligned: Boolean = false
        private set

    /** How many sightings from within [CLOSE_DISTANCE_M] have been seen: the alignment is "locked in" after a few. */
    var closeSightings: Int = 0
        private set

    /**
     * How well the sightings in use agree, as the weighted spread of their positions in centimeters and of their
     * yaw in degrees. Small numbers (a few cm, under 1°) mean a good, stable alignment; large ones mean noisy
     * sightings: a sign of the wrong size, a tilted or moving sign, or a bad angle. 0 until there are two sightings.
     */
    var spreadCm: Float = 0f
        private set
    var spreadDegrees: Float = 0f
        private set

    /** How many of the sightings in the window were left out as outliers the last time the average was taken. */
    var rejectedSightings: Int = 0
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

    /** True when the sightings in use agree well enough to trust the alignment (see [MAX_LOCK_SPREAD_CM]). */
    val sightingsAgree: Boolean
        get() = spreadCm <= MAX_LOCK_SPREAD_CM && spreadDegrees <= MAX_LOCK_SPREAD_DEGREES

    /**
     * True once enough close, frontal sightings have settled the alignment (precise enough to start recording).
     * The sightings must come from different viewpoints (the camera moved a little): a sign's pose error depends on
     * the viewpoint, so sightings from one spot share the same error and averaging them doesn't remove it. They must
     * also agree with each other. If the user cannot or does not move, or the readings stay noisy,
     * [PATIENCE_SIGHTINGS] close sightings are accepted anyway.
     */
    val isLockedIn: Boolean
        get() {
            if (!isAligned || closeSightings < LOCK_IN_SIGHTINGS) return false
            if (closeSightings >= PATIENCE_SIGHTINGS) return true
            val variedViews = closeViews.isEmpty() || viewSpreadMeters >= MIN_VIEW_SPREAD_M
            return variedViews && sightingsAgree
        }

    /**
     * Adds [candidate] (the alignment implied by one sighting, from [MarkerAlignment.alignment]) to the window.
     * [distanceMeters] is how far the camera was from the sign. [countAsClose] is for captured reference points
     * (photos of the scene): they are recognized from about where they were photographed, whatever the distance or
     * angle, and ARCore holds them less steadily than a printed sign, so each sighting counts
     * [REFERENCE_LOCK_IN_CREDIT] times toward the lock-in. Its weight in the average still depends on its real
     * distance and angle. Returns true if the alignment changed.
     */
    fun update(
        candidate: Mat4,
        distanceMeters: Float,
        viewCosine: Float = 1f,
        camera: Vec3? = null,
        countAsClose: Boolean = false,
    ): Boolean {
        // Seen head-on, a sign's pose is precise; at a steep angle it is poor (a flat image is ambiguous when
        // viewed from the side), so such a sighting counts for much less and doesn't help lock in.
        val cosine = viewCosine.coerceIn(0f, 1f)
        if (countAsClose || (distanceMeters <= CLOSE_DISTANCE_M && cosine >= MIN_FRONTAL_COSINE)) {
            closeSightings += if (countAsClose) REFERENCE_LOCK_IN_CREDIT else 1
            if (camera != null) {
                closeViews += camera
                if (closeViews.size > WINDOW) closeViews.removeAt(0)
            }
        }
        val t = candidate.translation
        samples += Sample(yaw(candidate), t.x, t.y, t.z, weight(distanceMeters) * maxOf(0.1f, cosine * cosine), nextSequence++)
        if (samples.size > WINDOW) samples.remove(samples.minBy(::keepScore))

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
        rejectedSightings = 0
        samples.clear()
        closeViews.clear()
        alignment = Mat4.IDENTITY
        isAligned = false
        closeSightings = 0
    }

    /** How strongly a sample deserves to stay in the window: its weight, fading as newer sightings arrive. */
    private fun keepScore(sample: Sample): Float =
        sample.weight * RECENCY.pow((nextSequence - 1 - sample.sequence).toFloat())

    private fun average(): Mat4 {
        val used = inliers()
        rejectedSightings = samples.size - used.size
        var total = 0f
        var x = 0f; var y = 0f; var z = 0f
        var sinSum = 0f; var cosSum = 0f
        for (s in used) {
            total += s.weight
            x += s.x * s.weight; y += s.y * s.weight; z += s.z * s.weight
            sinSum += sin(s.yaw) * s.weight; cosSum += cos(s.yaw) * s.weight
        }
        val meanYaw = atan2(sinSum, cosSum)
        val yawDegrees = Math.toDegrees(meanYaw.toDouble()).toFloat()
        val mean = Vec3(x / total, y / total, z / total)
        // Weighted spread of the sightings in use around their average.
        var squares = 0f
        var yawSquares = 0f
        for (s in used) {
            val dx = s.x - mean.x; val dz = s.z - mean.z
            squares += (dx * dx + dz * dz) * s.weight
            val dyaw = wrapRadians(s.yaw - meanYaw)
            yawSquares += dyaw * dyaw * s.weight
        }
        spreadCm = if (used.size < 2) 0f else sqrt(squares / total) * 100f
        spreadDegrees = if (used.size < 2) 0f else Math.toDegrees(sqrt(yawSquares / total).toDouble()).toFloat()
        return Mat4.yaw(yawDegrees, mean)
    }

    /**
     * The samples that agree with the majority. Each sample's distance from the median position and its yaw
     * difference from the median yaw are compared with the typical (median) deviation; a sample more than
     * [OUTLIER_FACTOR] times as far off, and beyond [MIN_OUTLIER_METERS] / [MIN_OUTLIER_DEGREES], is left out. With
     * fewer than [MIN_SAMPLES_FOR_OUTLIERS] samples there is no majority to compare with, so all are used.
     */
    private fun inliers(): List<Sample> {
        if (samples.size < MIN_SAMPLES_FOR_OUTLIERS) return samples
        val medianX = median(samples.map { it.x })
        val medianZ = median(samples.map { it.z })
        val medianYaw = circularMedian(samples.map { it.yaw })
        val positionOff = samples.map { hypot(it.x - medianX, it.z - medianZ) }
        val yawOff = samples.map { abs(wrapRadians(it.yaw - medianYaw)) }
        val positionLimit = maxOf(MIN_OUTLIER_METERS, OUTLIER_FACTOR * median(positionOff))
        val yawLimit = maxOf(Math.toRadians(MIN_OUTLIER_DEGREES.toDouble()).toFloat(), OUTLIER_FACTOR * median(yawOff))
        val kept = samples.filterIndexed { i, _ -> positionOff[i] <= positionLimit && yawOff[i] <= yawLimit }
        return kept.ifEmpty { samples }
    }

    companion object {
        private const val MIN_CHANGE_METERS = 0.015f
        private const val MIN_CHANGE_DEGREES = 0.3f

        /** How many sightings are averaged. */
        const val WINDOW = 12

        /** Per newer sighting, how much a sample's claim to stay in the window fades (see [AlignmentFilter]). */
        const val RECENCY = 0.9f

        /** Sightings from within this distance (meters) are precise enough to count toward the lock-in. */
        const val CLOSE_DISTANCE_M = 2.5f
        const val LOCK_IN_SIGHTINGS = 3

        /** How much one sighting of a captured reference point counts toward the lock-in: two sightings lock in. */
        const val REFERENCE_LOCK_IN_CREDIT = 2

        /** A sighting counts as frontal when the camera is within about 53° of straight in front of the sign. */
        const val MIN_FRONTAL_COSINE = 0.6f

        /** The camera must move this far (meters) during lock-in, so the sightings come from different viewpoints. */
        const val MIN_VIEW_SPREAD_M = 0.12f

        /** After this many close, frontal sightings the viewpoint variety and agreement are no longer required. */
        const val PATIENCE_SIGHTINGS = 8

        /** Sightings must agree within this (position, cm) and [MAX_LOCK_SPREAD_DEGREES] (yaw) to lock in early. */
        const val MAX_LOCK_SPREAD_CM = 4f
        const val MAX_LOCK_SPREAD_DEGREES = 2.5f

        /** Outlier rejection needs this many samples to have a majority to compare with. */
        const val MIN_SAMPLES_FOR_OUTLIERS = 4

        /** A sample further off than this many typical deviations (and the minimums below) is an outlier. */
        const val OUTLIER_FACTOR = 3f
        const val MIN_OUTLIER_METERS = 0.08f
        const val MIN_OUTLIER_DEGREES = 3f

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

        private fun wrapRadians(angle: Float): Float {
            var a = angle
            while (a > Math.PI) a -= (2 * Math.PI).toFloat()
            while (a < -Math.PI) a += (2 * Math.PI).toFloat()
            return a
        }

        private fun median(values: List<Float>): Float {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
        }

        /** The median of angles: taken relative to their circular mean, so it works across ±180°. */
        private fun circularMedian(angles: List<Float>): Float {
            val mean = atan2(angles.sumOf { sin(it).toDouble() }, angles.sumOf { cos(it).toDouble() }).toFloat()
            return wrapRadians(mean + median(angles.map { wrapRadians(it - mean) }))
        }
    }
}
