package com.app.androidnativearcore.wayfinder.ar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.app.androidnativearcore.wayfinder.core.AlignmentFilter
import com.app.androidnativearcore.wayfinder.core.AlignmentSmoother
import com.app.androidnativearcore.wayfinder.core.MarkerAlignment
import com.app.androidnativearcore.wayfinder.core.Mat4
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlin.math.cos
import kotlin.math.sin

/**
 * The venue alignment as it is drawn: glued to the real world by an ARCore anchor, and changed smoothly.
 *
 * The alignment comes from sign sightings, as a pose in the AR session's coordinates. Between sightings (most of a
 * walk, with the sign out of view) those coordinates are not fixed to the room: as ARCore learns more of the space it
 * corrects its map, and content placed at fixed session coordinates slides along with the correction. An anchor is
 * what ARCore keeps attached to the real place through such corrections, so the alignment is held as an anchor and
 * drawn where the anchor is now. A new anchor is made whenever the sightings change the alignment.
 *
 * Changes are passed through [AlignmentSmoother] so corrections glide instead of making the scene jump.
 */
class StableAlignment {
    private var anchor: Anchor? = null
    private var anchoredFor: Mat4? = null
    private val smoother = AlignmentSmoother()
    private var lastFrameNs = 0L

    /** The alignment to draw with. Only meaningful while [ready]. */
    var display by mutableStateOf(Mat4.IDENTITY)
        private set

    /** True once there is a trusted alignment to draw with (the map was found). */
    var ready by mutableStateOf(false)
        private set

    /**
     * Call every frame with the latest measured [alignment] and whether it is trusted ([aligned]). [frameTimeNs] is
     * the frame's timestamp.
     */
    fun update(session: Session, alignment: Mat4, aligned: Boolean, frameTimeNs: Long) {
        if (!aligned) {
            release()
            return
        }
        if (anchoredFor?.let { MarkerAlignment.difference(it, alignment).let { d -> d.meters > 0.001f || d.degrees > 0.05f } } != false) {
            anchor?.detach()
            anchor = runCatching { session.createAnchor(poseOf(alignment)) }.getOrNull()
            anchoredFor = alignment
        }
        // Where the anchor is now (ARCore keeps it on the real place); upright, as alignments always are.
        val live = anchor?.takeIf { it.trackingState == TrackingState.TRACKING }?.let { a ->
            val m = FloatArray(16)
            a.pose.toMatrix(m, 0)
            MarkerAlignment.levelled(Mat4(m))
        } ?: alignment
        val dt = if (lastFrameNs == 0L) 0f else ((frameTimeNs - lastFrameNs) / 1e9f).coerceIn(0f, 0.5f)
        lastFrameNs = frameTimeNs
        val shown = smoother.step(live, dt)
        if (MarkerAlignment.difference(shown, display).let { it.meters > 0.0005f || it.degrees > 0.02f } || !ready) display = shown
        if (!ready) ready = true
    }

    /** Forgets the alignment (map lost or screen closed) and frees the anchor. */
    fun release() {
        anchor?.detach()
        anchor = null
        anchoredFor = null
        smoother.reset()
        lastFrameNs = 0L
        if (ready) ready = false
    }

    private companion object {
        /** An ARCore pose for an upright alignment: its translation and its turn about the vertical axis. */
        fun poseOf(alignment: Mat4): Pose {
            val t = alignment.translation
            val half = AlignmentFilter.yaw(alignment) / 2f
            return Pose(floatArrayOf(t.x, t.y, t.z), floatArrayOf(0f, sin(half), 0f, cos(half)))
        }
    }
}
