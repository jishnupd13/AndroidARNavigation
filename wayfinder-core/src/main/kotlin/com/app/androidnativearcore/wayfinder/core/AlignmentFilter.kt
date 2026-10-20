package com.app.androidnativearcore.wayfinder.core

import kotlin.math.PI
import kotlin.math.atan2

/**
 * Keeps the venue's pose in the AR session ([alignment]) up to date from marker sightings.
 *
 * The iOS app replaces the alignment with each new sighting. A sign's measured pose is much more precise from
 * up close than from several meters away (the error grows with distance), so this filter blends each sighting
 * in with a weight that falls off with distance: a close-up scan pulls the alignment strongly toward it, a far
 * one only nudges it. Tiny changes (under 5 cm / 1°) are ignored so the scene doesn't jitter.
 */
class AlignmentFilter {
    var alignment: Mat4 = Mat4.IDENTITY
        private set
    var isAligned: Boolean = false
        private set

    /**
     * Blends [candidate] (the alignment implied by one sighting, from [MarkerAlignment.alignment]) into the
     * current alignment. [distanceMeters] is how far the camera was from the sign. Returns true if the
     * alignment changed.
     */
    fun update(candidate: Mat4, distanceMeters: Float): Boolean {
        if (!isAligned) {
            alignment = candidate
            isAligned = true
            return true
        }
        val w = weight(distanceMeters)
        val blended = blend(alignment, candidate, w)
        val change = MarkerAlignment.difference(blended, alignment)
        if (!(change.meters > MIN_CHANGE_METERS || change.degrees > MIN_CHANGE_DEGREES)) return false
        alignment = blended
        return true
    }

    fun reset() {
        alignment = Mat4.IDENTITY
        isAligned = false
    }

    companion object {
        private const val MIN_CHANGE_METERS = 0.05f
        private const val MIN_CHANGE_DEGREES = 1f

        /** How much a sighting from [distanceMeters] away counts: ~0.75 at 0.5 m, 0.5 at 1.5 m, 0.27 at 4 m. */
        fun weight(distanceMeters: Float): Float = 1.5f / (1.5f + distanceMeters.coerceAtLeast(0f))

        /** Yaw (radians, about +Y) of a levelled alignment. */
        fun yaw(m: Mat4): Float {
            val x = m.column(0)
            return atan2(-x.z, x.x)
        }

        /** [from] moved fraction [t] of the way to [to], in yaw and translation (alignments only have those). */
        fun blend(from: Mat4, to: Mat4, t: Float): Mat4 {
            var delta = yaw(to) - yaw(from)
            while (delta > PI) delta -= (2 * PI).toFloat()
            while (delta < -PI) delta += (2 * PI).toFloat()
            val yawDegrees = Math.toDegrees((yaw(from) + t * delta).toDouble()).toFloat()
            val a = from.translation
            val b = to.translation
            return Mat4.yaw(yawDegrees, Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t))
        }
    }
}
