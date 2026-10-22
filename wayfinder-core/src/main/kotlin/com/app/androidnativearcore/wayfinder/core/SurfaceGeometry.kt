package com.app.androidnativearcore.wayfinder.core

import kotlin.math.abs
import kotlin.math.acos

/**
 * Which of the surfaces along a tapped ray an object goes on.
 *
 * ARCore reports every surface the ray crosses, nearest first. A detected plane is a better anchor than a single
 * depth point (it is fitted from many observations and keeps being refined), but only if it is the surface the user
 * actually tapped. The ray through a sofa also meets the floor plane behind it, so "the first plane" can be a
 * meter or more beyond what was tapped. A plane is therefore only preferred when it lies within
 * [SAME_SURFACE_M] of the nearest surface; otherwise the nearest surface wins.
 */
object SurfaceChoice {
    /** One surface along the ray: how far from the camera it is, and whether it is a detected plane. */
    data class Candidate(val distanceMeters: Float, val isPlane: Boolean)

    /** Surfaces closer together than this (meters, along the ray) are the same surface. */
    const val SAME_SURFACE_M = 0.1f

    /** The index of the candidate to use, or null if there are none. */
    fun choose(candidates: List<Candidate>): Int? {
        if (candidates.isEmpty()) return null
        val nearest = candidates.indices.minBy { candidates[it].distanceMeters }
        val limit = candidates[nearest].distanceMeters + SAME_SURFACE_M
        return candidates.indices
            .filter { candidates[it].isPlane && candidates[it].distanceMeters <= limit }
            .minByOrNull { candidates[it].distanceMeters }
            ?: nearest
    }
}

/**
 * Sharpens the direction of a sign on a wall using the wall itself.
 *
 * A sign's pose comes from how its picture looks in the camera. Its turn left/right (the yaw that aligns the whole
 * venue) is the least precise part: for a 20 cm sign a degree or two is typical, which is 2–4 cm of error per meter
 * away from the sign. ARCore also detects walls as vertical planes, fitted from many points over a much larger area,
 * so a wall's facing direction is usually more precise than the sign's. When the sign clearly lies on such a wall,
 * the wall's direction is used for the sign's yaw; its center stays where the picture put it.
 */
object WallRefinement {
    /** The wall's facing must be within this many degrees of the sign's own for the sign to lie on it. */
    const val MAX_ANGLE_DEGREES = 8f

    /** The sign's center must be within this distance (meters) of the wall's surface. */
    const val MAX_OFFSET_M = 0.03f

    /** A wall plane must be at least this wide and tall (meters); a smaller one may be just the sign itself. */
    const val MIN_EXTENT_M = 0.4f

    /**
     * [markerInSession] with its facing direction replaced by that of the wall plane through [planePoint] with
     * normal [planeNormal], or null if the sign doesn't lie on that wall (too far from it, turned differently,
     * or the plane isn't upright).
     */
    fun refine(markerInSession: Mat4, planePoint: Vec3, planeNormal: Vec3): Mat4? {
        val length = planeNormal.length
        if (length < 1e-4f) return null
        var normal = planeNormal * (1f / length)
        // Only upright walls: their facing is horizontal.
        if (abs(normal.y) > 0.1f) return null
        val center = markerInSession.translation
        val signNormal = markerInSession.column(1) // an image's +y points out of its front
        if (dot(signNormal, normal) < 0f) normal = -normal
        val cosine = (dot(signNormal, normal) / signNormal.length.coerceAtLeast(1e-6f)).coerceIn(-1f, 1f)
        if (Math.toDegrees(acos(cosine).toDouble()) > MAX_ANGLE_DEGREES) return null
        if (abs(dot(center - planePoint, normal)) > MAX_OFFSET_M) return null

        // The same frame MarkerAlignment.transform builds for a wall sign: +y out of the front, +z towards the bottom.
        val horizontal = Vec3(normal.x, 0f, normal.z)
        val facing = horizontal * (1f / horizontal.length)
        val towardsBottom = -Vec3.UP
        val right = facing cross towardsBottom
        return Mat4.fromColumns(right, facing, towardsBottom, center)
    }

    private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z
}
