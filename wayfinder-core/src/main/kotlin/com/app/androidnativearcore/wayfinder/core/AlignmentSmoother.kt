package com.app.androidnativearcore.wayfinder.core

import kotlin.math.exp

/**
 * Moves the drawn alignment smoothly toward the measured one, so content glides into place instead of jumping.
 *
 * Every sign sighting can move the alignment by a centimeter or two and a fraction of a degree. Applied directly,
 * the whole scene (arrows, objects, reference pins) jumps that much every time, which looks like shaking. Here the
 * drawn alignment follows the measured one with a time constant of [timeConstantSeconds], so small corrections take
 * a fraction of a second. A large change (a different sign, a re-localization) is applied at once
 * ([SNAP_METERS], [SNAP_DEGREES]): gliding across meters would show content sweeping through the room.
 */
class AlignmentSmoother(private val timeConstantSeconds: Float = 0.15f) {
    /** The alignment drawn now, or null before the first [step]. */
    var current: Mat4? = null
        private set

    fun reset() {
        current = null
    }

    /** Advances by [dtSeconds] toward [target] and returns the alignment to draw. */
    fun step(target: Mat4, dtSeconds: Float): Mat4 {
        val now = current
        if (now == null) {
            current = target
            return target
        }
        val change = MarkerAlignment.difference(target, now)
        val next = if (change.meters > SNAP_METERS || change.degrees > SNAP_DEGREES || dtSeconds <= 0f) {
            if (dtSeconds <= 0f) now else target
        } else {
            AlignmentTrack.interpolate(now, target, 1f - exp(-dtSeconds / timeConstantSeconds))
        }
        current = next
        return next
    }

    companion object {
        /** Changes bigger than this are applied at once rather than smoothed. */
        const val SNAP_METERS = 0.5f
        const val SNAP_DEGREES = 10f
    }
}
