package com.app.androidnativearcore.wayfinder.ar

import android.graphics.BitmapFactory
import com.app.androidnativearcore.wayfinder.core.Mat4
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.VenuePackage
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState

/** A marker ARCore is currently seeing, with the pose of its image center in session coordinates. */
class MarkerSighting(val marker: Venue.Marker, val markerInSession: Mat4, val distanceMeters: Float)

/**
 * ARCore side of marker alignment: registers the venue's AR signs as an [AugmentedImageDatabase] and turns
 * tracked [AugmentedImage]s into [MarkerSighting]s. ARKit's `ARImageAnchor` equivalent.
 *
 * iOS detects each sign once and removes its anchor after 1.5 s so it can be detected again later for a fresh
 * correction. Here a sign that stays in view is reported at most every [REDETECT_INTERVAL_MS].
 */
class ArMarkerTracker(private val pkg: VenuePackage) {
    private val markersByID = pkg.venue.markers.associateBy { it.id }
    private val lastReportedMs = HashMap<String, Long>()

    /** Markers whose image ARCore rejected (too few features); the others still work. */
    val unusableMarkers = mutableListOf<Pair<Venue.Marker, String>>()

    /** Call from the session configuration callback, before the session resumes. */
    fun configure(session: Session, config: Config, aiming: Boolean = false) {
        val database = AugmentedImageDatabase(session)
        unusableMarkers.clear()
        for (marker in pkg.venue.markers) {
            val bitmap = BitmapFactory.decodeFile(pkg.file(marker).path)
            if (bitmap == null) {
                unusableMarkers += marker to "image can't be decoded"
                continue
            }
            try {
                database.addImage(marker.id, bitmap, marker.physicalWidth)
            } catch (e: Exception) {
                unusableMarkers += marker to (e.message ?: e.javaClass.simpleName)
            }
        }
        config.augmentedImageDatabase = database
        config.focusMode = Config.FocusMode.AUTO
        if (aiming) {
            // Aiming at an object needs surfaces: planes for floors and walls, depth for everything else.
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC
            else Config.DepthMode.DISABLED
        } else {
            // Not needed for sign alignment; leaving them on only costs CPU.
            config.depthMode = Config.DepthMode.DISABLED
            config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        }
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
    }

    /** Signs ARCore is actively tracking in this frame (not merely remembered), rate-limited per sign. */
    fun sightings(frame: Frame, nowMs: Long): List<MarkerSighting> {
        val out = ArrayList<MarkerSighting>()
        for (image in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
            if (image.trackingState != TrackingState.TRACKING ||
                image.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING
            ) continue
            val marker = markersByID[image.name] ?: continue
            val last = lastReportedMs[marker.id]
            if (last != null && nowMs - last < REDETECT_INTERVAL_MS) continue
            lastReportedMs[marker.id] = nowMs
            val matrix = FloatArray(16)
            image.centerPose.toMatrix(matrix, 0)
            val camera = frame.camera.pose
            val c = image.centerPose
            val distance = kotlin.math.sqrt(
                (c.tx() - camera.tx()).let { it * it } + (c.ty() - camera.ty()).let { it * it } + (c.tz() - camera.tz()).let { it * it },
            )
            out += MarkerSighting(marker, Mat4(matrix), distance)
        }
        return out
    }

    /**
     * The point on a surface the camera is aimed at (the screen center), in session coordinates, or null if ARCore
     * knows no surface there. Needs [configure] with `aiming = true`.
     */
    fun aimedPoint(frame: Frame, widthPx: Float, heightPx: Float, maxDistanceM: Float = 6f): Vec3? {
        val hit = frame.hitTest(widthPx / 2, heightPx / 2).firstOrNull { result ->
            val trackable = result.trackable
            result.distance <= maxDistanceM && when (trackable) {
                is Plane -> trackable.isPoseInPolygon(result.hitPose)
                is DepthPoint -> true
                is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
        } ?: return null
        val p = hit.hitPose
        return Vec3(p.tx(), p.ty(), p.tz())
    }

    companion object {
        const val REDETECT_INTERVAL_MS = 1_500L

        /** The wording the iOS app uses for each tracking problem. */
        fun trackingMessage(frame: Frame): String? {
            val camera = frame.camera
            if (camera.trackingState == TrackingState.TRACKING) return null
            if (camera.trackingState == TrackingState.STOPPED) return "AR tracking isn't available."
            return when (camera.trackingFailureReason) {
                TrackingFailureReason.EXCESSIVE_MOTION -> "Move the phone more slowly."
                TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point the camera at a brighter, more detailed area."
                TrackingFailureReason.INSUFFICIENT_LIGHT -> "It's too dark here. Move to a brighter area."
                TrackingFailureReason.CAMERA_UNAVAILABLE -> "The camera is in use by another app."
                TrackingFailureReason.NONE, TrackingFailureReason.BAD_STATE -> "Starting the camera… move the phone slowly."
            }
        }
    }
}
