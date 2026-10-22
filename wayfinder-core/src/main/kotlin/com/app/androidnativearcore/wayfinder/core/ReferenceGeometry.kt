package com.app.androidnativearcore.wayfinder.core

import kotlin.math.abs
import kotlin.math.atan2

/**
 * The measurements taken when the admin photographs a reference point, so the saved marker matches what ARCore will
 * later recognize.
 *
 * ARCore treats a reference photo as a flat picture of known width. When the photo is recognized later, ARCore
 * reports where that picture is (its center and its facing). For the venue to line up, the saved marker must
 * describe exactly that picture: centered where the camera's center ray met the scene, as wide as the crop
 * really was at that depth, and facing the way the picture's own left-to-right edge says.
 */
object ReferenceGeometry {
    /** A4 paper's height over its width (297 mm / 210 mm): reference photos are cropped in this portrait shape. */
    const val A4_ASPECT = 297f / 210f

    /** The photo's width as a fraction of the camera image's shorter side (its height is [A4_ASPECT] times that). */
    const val A4_WIDTH_FRACTION = 0.5f

    /**
     * The size of an upright A4-shaped crop in camera-image pixels. The camera image is in the sensor's landscape
     * orientation; with [rotatedToUpright] (the phone held in portrait, so the image is turned a quarter to show it
     * upright) the photo's width runs along the image's height. Returns the crop's width and height in image pixels.
     */
    fun a4Crop(imageWidth: Int, imageHeight: Int, rotatedToUpright: Boolean): Pair<Float, Float> {
        val uprightWidth = minOf(imageWidth, imageHeight) * A4_WIDTH_FRACTION
        val uprightHeight = uprightWidth * A4_ASPECT
        return if (rotatedToUpright) uprightHeight to uprightWidth else uprightWidth to uprightHeight
    }

    /** Above this, the middle of the photo is too far off the plane through its corners to be a flat surface. */
    const val MAX_FLATNESS_ERROR = 0.15f

    /** Above this (depth difference across the photo, relative to its middle), it was seen at a steep angle. */
    const val MAX_DEPTH_SPREAD = 0.45f

    /**
     * Facing (degrees, 0 = +z, 90 = +x, as [Venue.Marker.facing]) of a picture whose left-to-right edge points along
     * [right] (venue frame). Taken from the picture's own horizontal edge rather than from the direction to the
     * camera: with the phone slightly rolled while looking down, those two differ by several degrees, and only the
     * edge is what ARCore measures later.
     */
    fun facingDegrees(right: Vec3): Float =
        ((Math.toDegrees(atan2(-right.z, right.x).toDouble()).toFloat()) + 360f) % 360f

    /**
     * Real width (meters) of a crop [cropSidePx] pixels wide, seen [depthMeters] away along the camera's axis (not
     * the slanted distance), with a focal length of [focalPx] pixels.
     */
    fun physicalWidth(depthMeters: Float, cropSidePx: Float, focalPx: Float): Float =
        depthMeters * cropSidePx / focalPx

    /**
     * What is wrong with the photo, or null if it looks usable. [centerDepth] is the depth (along the camera's axis)
     * at the photo's middle; [cornerDepths] at its four corners, null where nothing could be measured. ARCore expects
     * a flat picture: for a flat surface the inverse depth changes evenly across the photo, so the middle's inverse
     * depth is the average of the corners'. An object in front of a wall breaks that (the middle is much nearer), and
     * so does a scene with depth; such a photo matches only from the exact spot it was taken from.
     */
    fun problem(centerDepth: Float, cornerDepths: List<Float?>): String? {
        if (centerDepth <= 0f) return null
        val measured = cornerDepths.filterNotNull().filter { it > 0f }
        if (measured.size == 4) {
            val inverseMean = measured.map { 1f / it }.average().toFloat()
            val flatnessError = abs(1f / centerDepth - inverseMean) * centerDepth
            if (flatnessError > MAX_FLATNESS_ERROR) {
                return "Not flat: parts of the photo are much nearer or farther than its middle. Point at a flat, " +
                    "detailed surface (a poster, sign or painting) and retake."
            }
        }
        if (measured.size >= 2 && (measured.max() - measured.min()) / centerDepth > MAX_DEPTH_SPREAD) {
            return "Seen at a steep angle. Face the surface more directly and retake."
        }
        return null
    }
}
