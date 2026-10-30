package com.app.androidnativearcore.wayfinder.ar

import android.graphics.BitmapFactory
import com.app.androidnativearcore.wayfinder.core.Mat4
import com.app.androidnativearcore.wayfinder.core.SurfaceChoice
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.VenuePackage
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState

/** A surface found at a screen position: where it is, and a way to anchor something to it. */
class SurfaceHit(val position: Vec3, val isPlane: Boolean, val createAnchor: () -> com.google.ar.core.Anchor)

/** A marker ARCore is currently seeing, with the pose of its image center in session coordinates. */
class MarkerSighting(
    val marker: Venue.Marker,
    val markerInSession: Mat4,
    val distanceMeters: Float,
    /** Where the camera was, in session coordinates. */
    val cameraInSession: Vec3,
    /** 1 when the camera looked at the sign head-on, near 0 when it saw it edge-on. */
    val viewCosine: Float,
)

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

    /**
     * Called once per image without a saved size (physical width 0), after a few sightings, with the width ARCore
     * measured for it, so it can be saved: next time the image is recognized at once, without having to move.
     */
    var onSizeMeasured: ((Venue.Marker, Float) -> Unit)? = null
    private val unsizedSightings = HashMap<String, Int>()

    /** Markers successfully added to the ARCore AugmentedImageDatabase. */
    val usableMarkers = mutableListOf<Venue.Marker>()

    /** Markers whose image ARCore rejected (too few features); the others still work. */
    val unusableMarkers = mutableListOf<Pair<Venue.Marker, String>>()

    /** Call from the session configuration callback, before the session resumes. */
    fun configure(session: Session, config: Config, aiming: Boolean = false) {
        val database = AugmentedImageDatabase(session)
        usableMarkers.clear()
        unusableMarkers.clear()
        for (marker in pkg.venue.markers) {
            val bitmap = BitmapFactory.decodeFile(pkg.file(marker).path)
            if (bitmap == null) {
                unusableMarkers += marker to "image can't be decoded"
                continue
            }
            try {
                // Without a known width (0), ARCore estimates the image's size itself while tracking it.
                if (marker.physicalWidth > 0f) database.addImage(marker.id, bitmap, marker.physicalWidth)
                else database.addImage(marker.id, bitmap)
                usableMarkers += marker
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
    fun sightings(frame: Frame, nowMs: Long, minIntervalMs: Long = REDETECT_INTERVAL_MS): List<MarkerSighting> {
        val out = ArrayList<MarkerSighting>()
        for (image in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
            if (image.trackingState != TrackingState.TRACKING ||
                image.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING
            ) continue
            val marker = markersByID[image.name] ?: continue
            val last = lastReportedMs[marker.id]
            if (last != null && nowMs - last < minIntervalMs) continue
            lastReportedMs[marker.id] = nowMs
            val matrix = FloatArray(16)
            image.centerPose.toMatrix(matrix, 0)
            val camera = frame.camera.pose
            val c = image.centerPose
            val distance = kotlin.math.sqrt(
                (c.tx() - camera.tx()).let { it * it } + (c.ty() - camera.ty()).let { it * it } + (c.tz() - camera.tz()).let { it * it },
            )
            // How head-on the view was: the angle between the sign's front normal and the direction to the camera.
            val normal = c.yAxis
            val toCamera = floatArrayOf(camera.tx() - c.tx(), camera.ty() - c.ty(), camera.tz() - c.tz())
            val toCameraLength = kotlin.math.sqrt(toCamera[0] * toCamera[0] + toCamera[1] * toCamera[1] + toCamera[2] * toCamera[2])
            val cosine = if (toCameraLength > 1e-4f) {
                kotlin.math.abs((normal[0] * toCamera[0] + normal[1] * toCamera[1] + normal[2] * toCamera[2]) / toCameraLength)
            } else 1f
            // The size ARCore measures for the image: for a reference point exported without a width (0), this is
            // ARCore's own estimate, and comparing it with the real size shows whether distances will be right.
            android.util.Log.d(
                ACCURACY_TAG,
                "Image ${marker.id}: ARCore size %.2f x %.2f m (saved width %.2f m, 0 = unknown)".format(image.extentX, image.extentZ, marker.physicalWidth),
            )
            if (marker.physicalWidth <= 0f && image.extentX > 0.05f) {
                val count = (unsizedSightings[marker.id] ?: 0) + 1
                unsizedSightings[marker.id] = count
                if (count == SIZE_MEASURE_SIGHTINGS) onSizeMeasured?.invoke(marker, image.extentX)
            }
            out += MarkerSighting(marker, Mat4(matrix), distance, Vec3(camera.tx(), camera.ty(), camera.tz()), cosine)
        }
        return out
    }

    /**
     * The ray (origin and unit direction, session coordinates) through screen position ([xPx], [yPx]) of a view of
     * [widthPx] × [heightPx], for placing something where ARCore knows no surface.
     */
    fun rayThrough(frame: Frame, xPx: Float, yPx: Float, widthPx: Float, heightPx: Float): Pair<Vec3, Vec3> {
        val projection = FloatArray(16)
        val view = FloatArray(16)
        frame.camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
        frame.camera.getViewMatrix(view, 0)
        val viewProjection = FloatArray(16)
        android.opengl.Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
        val inverse = FloatArray(16)
        android.opengl.Matrix.invertM(inverse, 0, viewProjection, 0)
        val ndcX = 2f * xPx / widthPx - 1f
        val ndcY = 1f - 2f * yPx / heightPx
        fun unproject(ndcZ: Float): Vec3 {
            val out = FloatArray(4)
            android.opengl.Matrix.multiplyMV(out, 0, inverse, 0, floatArrayOf(ndcX, ndcY, ndcZ, 1f), 0)
            return Vec3(out[0] / out[3], out[1] / out[3], out[2] / out[3])
        }
        val near = unproject(-1f)
        val far = unproject(1f)
        val d = far - near
        val length = d.length.coerceAtLeast(1e-6f)
        return near to Vec3(d.x / length, d.y / length, d.z / length)
    }

    /**
     * The nearest measured surface point at screen position ([xPx], [yPx]) (a plane, a depth point or a feature point
     * with a known surface), or null if ARCore has measured nothing there. Used to check how deep a scene is.
     */
    fun nearestSurfaceAt(frame: Frame, xPx: Float, yPx: Float, maxDistanceM: Float = 8f): Vec3? =
        frame.hitTest(xPx, yPx)
            .filter { hit ->
                val trackable = hit.trackable
                hit.distance <= maxDistanceM && when (trackable) {
                    is Plane -> trackable.isPoseInPolygon(hit.hitPose)
                    is DepthPoint -> true
                    is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                    else -> false
                }
            }
            .minByOrNull { it.distance }
            ?.hitPose?.let { Vec3(it.tx(), it.ty(), it.tz()) }

    /**
     * Height of the real floor under the user in the session frame, from the horizontal planes ARCore has detected:
     * the lowest upward-facing plane 0.8–2.3 m below the camera and within 6 m of it (tables are higher; the
     * lowest remaining surface is the floor). Null when no such plane is known yet. Needs [configure] with
     * `aiming = true`.
     */
    fun floorHeightBelow(session: Session, camera: Vec3): Float? {
        var lowest: Float? = null
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            val center = plane.centerPose
            val y = center.ty()
            if (y > camera.y - FLOOR_MIN_BELOW_M || y < camera.y - FLOOR_MAX_BELOW_M) continue
            if (kotlin.math.hypot((center.tx() - camera.x).toDouble(), (center.tz() - camera.z).toDouble()) > 8.0) continue
            if (lowest == null || y < lowest) lowest = y
        }
        return lowest
    }

    /**
     * An instant floor reading that needs no detected plane: a ray straight down from the camera, hit against planes
     * and the depth map. Planes take seconds to appear; depth answers on the first frames.
     */
    fun floorHeightByRay(frame: Frame, camera: Vec3): Float? {
        val hits = frame.hitTest(floatArrayOf(camera.x, camera.y, camera.z), 0, floatArrayOf(0f, -1f, 0f), 0)
        for (hit in hits) {
            val trackable = hit.trackable
            val usable = (trackable is Plane && trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING && trackable.isPoseInPolygon(hit.hitPose)) ||
                trackable is DepthPoint
            if (!usable) continue
            val y = hit.hitPose.ty()
            if (y <= camera.y - FLOOR_MIN_BELOW_M && y >= camera.y - FLOOR_MAX_BELOW_M) return y
        }
        return null
    }

    /**
     * The surface at screen position ([xPx], [yPx]) (e.g. where the user tapped), in session coordinates, with an
     * ARCore anchor that keeps it glued to the real world, or null if ARCore knows no surface there.
     *
     * Detected planes (tables, floors, walls) are preferred: a plane is fitted from many observations and its anchor
     * follows the plane as it is refined. Without one, the depth value is read at the tap and at four points
     * around it and the middle one is used, so a single noisy depth sample (or the edge of an object) can't throw
     * the position off. Needs [configure] with `aiming = true`.
     */
    fun surfaceAt(
        frame: Frame,
        session: Session,
        xPx: Float,
        yPx: Float,
        maxDistanceM: Float = 6f,
        preferPlane: Boolean = true,
    ): SurfaceHit? {
        fun usable(result: HitResult): Boolean {
            val trackable = result.trackable
            return result.distance <= maxDistanceM && when (trackable) {
                is Plane -> trackable.isPoseInPolygon(result.hitPose)
                is DepthPoint -> true
                is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
        }
        fun position(p: com.google.ar.core.Pose) = Vec3(p.tx(), p.ty(), p.tz())

        val atTap = frame.hitTest(xPx, yPx).filter(::usable)
        if (preferPlane) {
            // A plane only if it is the surface tapped, not the floor plane far behind a tapped sofa (SurfaceChoice).
            val chosen = SurfaceChoice.choose(atTap.map { SurfaceChoice.Candidate(it.distance, it.trackable is Plane) })
                ?.let { atTap[it] }
            if (chosen != null && chosen.trackable is Plane) {
                return SurfaceHit(position(chosen.hitPose), isPlane = true) { chosen.createAnchor() }
            }
        } else {
            // When capturing a foreground object (e.g. a bottle on a table), pick the closest hit so we don't
            // measure the background plane behind the object.
            atTap.minByOrNull { it.distance }?.let { closest ->
                return SurfaceHit(position(closest.hitPose), isPlane = closest.trackable is Plane) { closest.createAnchor() }
            }
        }
        val spread = 12f
        val samples = listOf(0f to 0f, spread to 0f, -spread to 0f, 0f to spread, 0f to -spread).mapNotNull { (dx, dy) ->
            frame.hitTest(xPx + dx, yPx + dy).firstOrNull { usable(it) }
        }
        if (samples.isEmpty()) return null
        val median = samples.sortedBy { it.distance }[samples.size / 2]
        return SurfaceHit(position(median.hitPose), isPlane = false) { median.createAnchor() }
    }

    companion object {
        const val REDETECT_INTERVAL_MS = 1_500L

        /** ARCore's size estimate for an image without a saved size is used after this many sightings. */
        private const val SIZE_MEASURE_SIGHTINGS = 3

        /** Widest camera image (pixels) used for tracking; larger costs too much CPU on mid-range phones. */
        private const val MAX_TRACKING_IMAGE_WIDTH = 1280

        /**
         * The camera configuration to start ARCore with: the sharpest CPU image up to [MAX_TRACKING_IMAGE_WIDTH] wide
         * at 30 fps. ARCore finds and measures signs and reference photos in this image, and reference photos are cut
         * from it; its default is often only 640×480. Twice the pixels across makes reference photos sharper and their
         * measured pose (above all their facing) steadier. SceneView's own default (the highest resolution) can
         * starve motion tracking on mid-range phones.
         */
        fun preferredCameraConfig(session: Session): CameraConfig {
            val filter = CameraConfigFilter(session).setTargetFps(java.util.EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
            val chosen = session.getSupportedCameraConfigs(filter)
                .filter { it.imageSize.width <= MAX_TRACKING_IMAGE_WIDTH }
                .maxByOrNull { it.imageSize.width * it.imageSize.height }
                ?: session.cameraConfig
            android.util.Log.i("WayFinder", "Camera config: CPU image ${chosen.imageSize}, GPU texture ${chosen.textureSize}")
            return chosen
        }

        /** A floor is between these distances below the phone (tables are higher; lower is a hole or noise). */
        const val FLOOR_MIN_BELOW_M = 0.5f
        const val FLOOR_MAX_BELOW_M = 2.8f

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
