package com.app.androidnativearcore.wayfinder.core

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Math for aligning an AR session with venue coordinates using a detected marker.
 *
 * ARKit (and ARCore) describe a detected image with a transform whose origin is the image center,
 * `+y` is the image normal (pointing out of the front), `+x` points to the image's right and `+z` towards
 * the image's bottom. [transform] builds the same frame for a marker from its surveyed venue position,
 * so [alignment] can derive the venue origin in session coordinates.
 */
object MarkerAlignment {
    /** The marker's frame in venue coordinates, using the image-anchor convention. */
    fun transform(marker: Venue.Marker): Mat4 {
        val yaw = Math.toRadians(marker.facing.toDouble())
        // Horizontal unit vector for the facing angle (0° = +z, 90° = +x).
        val facing = Vec3(sin(yaw).toFloat(), 0f, cos(yaw).toFloat())
        val up = Vec3.UP

        val normal: Vec3         // image +y
        val towardsBottom: Vec3  // image +z
        when (marker.mounting) {
            Venue.MarkerMounting.WALL -> { normal = facing; towardsBottom = -up }
            Venue.MarkerMounting.FLOOR -> { normal = up; towardsBottom = -facing }
        }
        val right = normal cross towardsBottom // image +x (right-handed: y × z = x)
        return Mat4.fromColumns(right, normal, towardsBottom, Vec3(marker.x, marker.y, marker.z))
    }

    /**
     * The venue origin expressed in session coordinates, given where the marker was detected (in session
     * coordinates) and where it is surveyed (in venue coordinates). This is the venue's pose in the session:
     * `sessionPoint = alignment * venuePoint`.
     *
     * ARKit applies it with `setWorldOrigin`; ARCore has no such call, so the app keeps this transform and
     * applies it to the content root (venue → session) and, inverted, to the camera (session → venue).
     * Small tilt from detection noise is removed so the venue stays level: only yaw and translation remain.
     */
    fun alignment(markerInSession: Mat4, markerInVenue: Mat4): Mat4 =
        levelled(markerInSession * markerInVenue.inverse())

    /** Keeps only the rotation around the vertical axis (and the translation) of a transform. */
    fun levelled(transform: Mat4): Mat4 {
        // Project the rotated x axis onto the horizontal plane to recover the yaw.
        val xAxis = transform.column(0)
        val horizontal = Vec2(xAxis.x, xAxis.z)
        if (horizontal.length <= 1e-4f) return transform
        val direction = horizontal.normalized()
        val cosYaw = direction.x
        val sinYaw = -direction.y // rotation about +y maps x (1,0,0) to (cos, 0, -sin)
        return Mat4.fromColumns(
            Vec3(cosYaw, 0f, -sinYaw), Vec3(0f, 1f, 0f), Vec3(sinYaw, 0f, cosYaw), transform.translation,
        )
    }

    /**
     * Angle in degrees between the rotations of two transforms around the vertical axis, and the distance
     * between their origins. Used to decide whether a new alignment is a meaningful correction.
     */
    fun difference(a: Mat4, b: Mat4): Difference {
        val aX = Vec2(a.column(0).x, a.column(0).z).normalized()
        val bX = Vec2(b.column(0).x, b.column(0).z).normalized()
        val angle = Math.toDegrees(acos(max(-1f, min(1f, aX dot bX)).toDouble())).toFloat()
        return Difference(angle, a.translation.distanceTo(b.translation))
    }

    data class Difference(val degrees: Float, val meters: Float)
}
