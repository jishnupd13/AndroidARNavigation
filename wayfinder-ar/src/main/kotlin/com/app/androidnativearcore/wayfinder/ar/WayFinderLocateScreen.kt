package com.app.androidnativearcore.wayfinder.ar

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.app.androidnativearcore.wayfinder.core.AlignmentFilter
import com.app.androidnativearcore.wayfinder.core.MarkerAlignment
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.VenuePackage
import io.github.sceneview.ar.ARSceneView

/** Where the user is, found by recognizing a reference point (an AR sign or a photo the admin captured). */
data class LocatedUser(
    /** The reference point that was recognized. */
    val marker: Venue.Marker,
    val level: String,
    /** The user's own position (not the sign's) in venue coordinates. */
    val x: Float,
    val z: Float,
)

/** While locking onto a reference point, re-detect it this often. */
private const val LOCATE_INTERVAL_MS = 120L

/**
 * Full-screen camera that recognizes any of the venue's reference points and reports where the user stands.
 * The user's first step: show the camera a reference point, and it is matched against the saved ones.
 */
@Composable
fun WayFinderLocateScreen(
    pkg: VenuePackage,
    onLocated: (LocatedUser) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val venue = pkg.venue
    val markerTracker = remember(pkg) { ArMarkerTracker(pkg) }
    val signs = remember(pkg) {
        venue.markers.take(8).mapNotNull { marker ->
            BitmapFactory.decodeFile(pkg.file(marker).path)?.let { marker.name.resolved() to it }
        }
    }
    val filter = remember(pkg) { AlignmentFilter() }
    val reported = remember(pkg) { BooleanArray(1) }
    var matched by remember { mutableStateOf<Venue.Marker?>(null) }
    var progress by remember { mutableStateOf(0) }
    var trackingMessage by remember { mutableStateOf<String?>("Starting the camera… move the phone slowly.") }
    var toast by remember { mutableStateOf<Toast?>(null) }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(onBack = onCancel)

    Box(modifier.fillMaxSize().background(Color.Black)) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            sessionCameraConfig = null,
            sessionConfiguration = { session, config ->
                markerTracker.configure(session, config)
                markerTracker.unusableMarkers.firstOrNull()?.let { (marker, reason) ->
                    toast = Toast((toast?.id ?: 0) + 1, "“${marker.name.resolved()}” can't be used: $reason", 5_000)
                }
            },
            onSessionUpdated = { _, frame ->
                val message = ArMarkerTracker.trackingMessage(frame)
                if (message != trackingMessage) trackingMessage = message
                if (reported[0]) return@ARSceneView
                val now = frame.timestamp / 1_000_000
                for (sighting in markerTracker.sightings(frame, now, LOCATE_INTERVAL_MS)) {
                    // Sightings of two different points can't be averaged: start over when the camera moves to another.
                    if (matched != null && matched?.id != sighting.marker.id && !filter.isLockedIn) filter.reset()
                    matched = sighting.marker
                    val candidate = MarkerAlignment.alignment(sighting.markerInSession, MarkerAlignment.transform(sighting.marker))
                    val isCapturedRef = sighting.marker.id.startsWith("ref-")
                    if (isCapturedRef) {
                        // Captured photo reference points can be harder for ARCore to hold in FULL_TRACKING across
                        // multiple viewpoints; lock in immediately once ARCore recognizes the image.
                        repeat(AlignmentFilter.LOCK_IN_SIGHTINGS) {
                            filter.update(candidate, 1f, 1f, null)
                        }
                    } else {
                        filter.update(candidate, sighting.distanceMeters, sighting.viewCosine, sighting.cameraInSession)
                    }
                    progress = filter.closeSightings.coerceAtMost(AlignmentFilter.LOCK_IN_SIGHTINGS)
                    if (filter.isLockedIn) {
                        val camera = frame.camera.pose
                        val inVenue = filter.alignment.inverse().transformPoint(Vec3(camera.tx(), camera.ty(), camera.tz()))
                        val level = venue.level(forDeviceHeight = inVenue.y)?.id ?: sighting.marker.level
                        reported[0] = true
                        onLocated(LocatedUser(sighting.marker, level, inVenue.x, inVenue.z))
                        return@ARSceneView
                    }
                }
            },
            onSessionFailed = { error -> toast = Toast((toast?.id ?: 0) + 1, "AR isn't available: ${error.message}", 8_000) },
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val point = matched
            if (point == null) {
                AlignmentPromptView(
                    trackingMessage ?: "Show the camera any reference point you can see (a sign or a marked spot) " +
                        "from 1–2.5 m and move the phone slowly side to side.",
                    signs,
                )
            } else {
                GuidanceBannerView(
                    GuidanceBanner(
                        instruction = "Matching “${point.name.resolved()}”…",
                        glyph = "◎",
                        detail = trackingMessage ?: "Keep it in view, 1–2.5 m away ($progress/${AlignmentFilter.LOCK_IN_SIGHTINGS})",
                    ),
                )
            }
        }
        Box(Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(16.dp)) {
            CircleButton("✕", "Cancel", onClick = onCancel)
        }
        Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 16.dp)) { ToastHost(toast) }
    }
}
