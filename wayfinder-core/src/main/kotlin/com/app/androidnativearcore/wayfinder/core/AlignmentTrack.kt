package com.app.androidnativearcore.wayfinder.core

import kotlin.math.PI
import kotlin.math.hypot

/**
 * The venue's alignment at different moments of a recorded walk.
 *
 * ARCore tracks by following the camera image, and that slowly drifts: a few centimeters after several meters
 * of walking. Each time the user scans a sign, the alignment is measured again, and the new measurement differs from
 * the first by exactly the drift that built up. Using one alignment for the whole walk would leave the drift in
 * the saved path. Instead the alignment is interpolated between scans by how far the user had walked (loop closure):
 * the start of the path uses the first scan, the end uses the last one, and the points between get a blend.
 *
 * With a single scan the alignment simply stays the same all the way.
 */
class AlignmentTrack {
    private class Entry(val progress: Float, val alignment: Mat4)

    private val entries = mutableListOf<Entry>()

    val isEmpty: Boolean get() = entries.isEmpty()
    val size: Int get() = entries.size

    /** Records that the alignment measured after walking [progress] meters is [alignment]. */
    fun add(progress: Float, alignment: Mat4) {
        // A new measurement at the same spot (the user stood still) replaces the earlier one.
        entries.removeAll { kotlin.math.abs(it.progress - progress) < SAME_SPOT_M }
        entries += Entry(progress, alignment)
        entries.sortBy { it.progress }
    }

    /** The alignment to use for a point recorded after walking [progress] meters. */
    fun alignmentAt(progress: Float): Mat4 {
        check(entries.isNotEmpty()) { "No alignment recorded" }
        val after = entries.indexOfFirst { it.progress >= progress }
        return when {
            after == 0 -> entries.first().alignment            // before the first scan: use the first
            after < 0 -> entries.last().alignment              // after the last scan: use the last
            else -> {
                val a = entries[after - 1]
                val b = entries[after]
                val t = if (b.progress > a.progress) (progress - a.progress) / (b.progress - a.progress) else 0f
                interpolate(a.alignment, b.alignment, t)
            }
        }
    }

    /** Where session-frame points go in the venue: the inverse of [alignmentAt]. */
    fun venueFromSessionAt(progress: Float): Mat4 = alignmentAt(progress).inverse()

    /** How far apart the first and the latest scans put the user, in meters (the drift they disagree by). */
    fun totalDriftMeters(sessionPosition: Vec3): Float =
        if (entries.size < 2) 0f else driftMeters(sessionPosition, entries.first().alignment, entries.last().alignment)

    companion object {
        private const val SAME_SPOT_M = 0.3f

        /** [a] moved the fraction [t] of the way to [b], in yaw (the short way round) and position. */
        fun interpolate(a: Mat4, b: Mat4, t: Float): Mat4 {
            var delta = AlignmentFilter.yaw(b) - AlignmentFilter.yaw(a)
            while (delta > PI) delta -= (2 * PI).toFloat()
            while (delta < -PI) delta += (2 * PI).toFloat()
            val yawDegrees = Math.toDegrees((AlignmentFilter.yaw(a) + t * delta).toDouble()).toFloat()
            val p = a.translation
            val q = b.translation
            return Mat4.yaw(yawDegrees, Vec3(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t, p.z + (q.z - p.z) * t))
        }

        /** The horizontal distance between where alignments [a] and [b] put a session-frame position in the venue. */
        fun driftMeters(sessionPosition: Vec3, a: Mat4, b: Mat4): Float {
            val inA = a.inverse().transformPoint(sessionPosition)
            val inB = b.inverse().transformPoint(sessionPosition)
            return hypot(inA.x - inB.x, inA.z - inB.z)
        }
    }
}
