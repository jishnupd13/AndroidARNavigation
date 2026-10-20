package com.app.androidnativearcore.wayfinder.ar

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.sp
import com.app.androidnativearcore.wayfinder.core.Mat4
import com.app.androidnativearcore.wayfinder.core.RouteProfile
import com.app.androidnativearcore.wayfinder.core.VenuePackage
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position

/**
 * Full-screen AR navigation to a POI (iOS `WayFinderNavigationViewController`).
 *
 * 1. The user points the camera at any of the venue's AR signs (markers). The session is aligned so that
 *    venue coordinates map onto the real world.
 * 2. A route is planned from the user's position and drawn as floor arrows, with a destination pin, POI labels,
 *    a mini map and turn-by-turn instructions.
 * 3. Every sign seen on the way corrects drift. Leaving the route triggers rerouting; reaching the destination
 *    shows an arrival screen.
 */
@Composable
fun WayFinderNavigationScreen(
    pkg: VenuePackage,
    destinationId: String,
    profile: RouteProfile = RouteProfile.FASTEST,
    listener: WayFinderNavigationListener? = null,
    languages: List<String>? = null,
    /** Paths people walked while adding destinations; routes to those destinations follow them. */
    walkedPoints: List<com.app.androidnativearcore.wayfinder.core.WalkedPoint> = emptyList(),
    /** Objects placed while recording; those belonging to this destination are shown along the way. */
    placedObjects: List<com.app.androidnativearcore.wayfinder.core.PlacedObject> = emptyList(),
    modifier: Modifier = Modifier,
    debug: Boolean = false,
) {
    val destination = pkg.venue.poi(destinationId)
    if (destination == null) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Unknown destination '$destinationId'.", color = Color.White)
        }
        return
    }

    val controller = remember(pkg, destinationId, profile, walkedPoints) {
        NavigationController(pkg, destination, profile, listener, languages, walkedPoints)
    }
    controller.listener = listener
    val markerTracker = remember(pkg) { ArMarkerTracker(pkg) }
    val state = controller.uiState
    val signs = remember(pkg) {
        pkg.venue.markers.take(6).mapNotNull { marker ->
            BitmapFactory.decodeFile(pkg.file(marker).path)?.let { marker.name.resolved() to it }
        }
    }
    var showFeedback by remember { mutableStateOf(false) }

    // Keep the screen awake while navigating (iOS: isIdleTimerDisabled).
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler { controller.finish() }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            // The default picks the highest-resolution camera config, which can starve motion tracking on mid-range
            // phones; ARCore's own default config is the safer choice for navigation.
            sessionCameraConfig = null,
            sessionConfiguration = { session, config ->
                markerTracker.configure(session, config)
                markerTracker.unusableMarkers.firstOrNull()?.let { (marker, reason) ->
                    controller.showToast("AR sign “${marker.name.resolved()}” can't be used: $reason", 6_000)
                }
            },
            onSessionUpdated = { _, frame ->
                val now = frame.timestamp / 1_000_000
                for (sighting in markerTracker.sightings(frame, now, if (controller.isLockedIn) ArMarkerTracker.REDETECT_INTERVAL_MS else 500L)) {
                    controller.onMarkerDetected(sighting.marker, sighting.markerInSession, sighting.distanceMeters)
                }
                val matrix = FloatArray(16)
                frame.camera.pose.toMatrix(matrix, 0)
                controller.onFrame(Mat4(matrix), ArMarkerTracker.trackingMessage(frame))
            },
            onSessionFailed = { error -> controller.showToast("AR isn't available: ${error.message}", 8_000) },
        ) {
            RouteScene(
                route = state.route,
                level = state.renderedLevel,
                venue = pkg.venue,
                destination = destination,
                alignment = controller.alignment,
                cameraPosition = { controller.cameraSessionPosition.let { Position(it.x, it.y, it.z) } },
                languages = languages,
            )
            ObjectsScene(
                objects = remember(placedObjects, destinationId) { placedObjects.filter { it.destinationId == destinationId } },
                alignment = controller.alignment,
                cameraPosition = { controller.cameraSessionPosition.let { Position(it.x, it.y, it.z) } },
            )
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            state.banner?.let { GuidanceBannerView(it) }
            state.alignmentPrompt?.let { AlignmentPromptView(it, signs) }
        }

        if (debug && state.debug.isNotEmpty()) {
            Text(
                state.debug,
                color = Color(0xFFFFEB3B),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterStart).safeDrawingPadding().background(Color(0x99000000)).padding(6.dp),
            )
        }
        Row(
            Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircleButton("✕", "Close navigation", onClick = controller::finish)
            CircleButton("!", "Report a problem", onClick = { showFeedback = true })
        }
        Column(
            Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ToastHost(state.toast)
            MiniMapView(state.route, state.renderedLevel, destination, pkg.venue, state.user)
        }

        state.arrival?.let { ArrivalOverlay(it, onDone = controller::finish) }
    }

    if (showFeedback) {
        AlertDialog(
            onDismissRequest = { showFeedback = false },
            title = { Text("Report a problem") },
            text = {
                Column {
                    Text("Your report helps improve directions here.")
                    for (reason in NavigationFeedback.Reason.entries) {
                        TextButton(onClick = {
                            showFeedback = false
                            controller.submitFeedback(reason)
                        }) { Text(reason.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showFeedback = false }) { Text("Cancel") } },
        )
    }
}
