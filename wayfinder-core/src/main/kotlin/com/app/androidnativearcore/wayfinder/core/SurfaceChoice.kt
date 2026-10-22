package com.app.androidnativearcore.wayfinder.core

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
