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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
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
import com.app.androidnativearcore.wayfinder.core.AlignmentFilter
import com.app.androidnativearcore.wayfinder.core.MarkerAlignment
import com.app.androidnativearcore.wayfinder.core.Mat4
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.VenuePackage
import com.app.androidnativearcore.wayfinder.core.WalkRecorder
import com.app.androidnativearcore.wayfinder.core.WalkedPoint
import io.github.sceneview.ar.ARSceneView
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * A spot the user chose, in venue coordinates, with the walk that leads to it from the sign they scanned.
 * Save [path] (the recorded points) and the destination together; routes to the destination follow the path.
 */
data class CapturedDestination(
    val name: String,
    val level: String,
    val x: Float,
    val z: Float,
    val path: List<WalkedPoint>,
)

/** Shortest recorded walk (meters) that is worth saving as a route. */
private const val MIN_PATH_LENGTH_M = 1f

/** An aimed point farther than this from the user is ignored: it must stay joinable to their walked path. */
private const val MAX_AIM_DISTANCE_M = 2.5f

/**
 * Full-screen AR "Add destination", the Android port of the iOS prototype's recording mode:
 *
 * 1. The user scans an AR sign. That aligns the session with the venue and **starts recording the walk**.
 * 2. They walk to the spot to save. The path is recorded as floor points (every 0.5 m) in venue coordinates,
 *    starting at the sign.
 * 3. **Save destination here** names the spot. The destination and its path are reported together, so routes to it
 *    follow the way the user actually walked (around walls), like the iOS `WalkedPathRouter`.
 */
@Composable
fun WayFinderCaptureScreen(
    pkg: VenuePackage,
    onSave: (CapturedDestination) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val venue = pkg.venue
    val markerTracker = remember(pkg) { ArMarkerTracker(pkg) }
    val signs = remember(pkg) {
        venue.markers.take(6).mapNotNull { marker ->
            BitmapFactory.decodeFile(pkg.file(marker).path)?.let { marker.name.resolved() to it }
        }
    }

    // One recorder per level walked on, so a walk that changes floors keeps each point on its own level.
    val recorders = remember(pkg) { mutableListOf<WalkRecorder>() }
    val filter = remember(pkg) { AlignmentFilter() }
    var alignment by remember { mutableStateOf(Mat4.IDENTITY) }
    var alignedMarker by remember { mutableStateOf<Venue.Marker?>(null) }
    var here by remember { mutableStateOf<Triple<String, Float, Float>?>(null) } // level, x, z
    // Where the crosshair hits a surface (venue x, z), if it is close enough to the user's own path.
    var aimed by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var recordedSummary by remember { mutableStateOf("") }
    var canSave by remember { mutableStateOf(false) }
    var trackingMessage by remember { mutableStateOf<String?>("Starting the camera… move the phone slowly.") }
    var toast by remember { mutableStateOf<Toast?>(null) }
    var naming by remember { mutableStateOf(false) }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(onBack = onCancel)

    fun recordedPoints() = recorders.flatMap { it.points }
    fun recordedLength() = recorders.sumOf { it.lengthMeters.toDouble() }.toFloat()

    Box(modifier.fillMaxSize().background(Color.Black)) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            sessionCameraConfig = null,
            sessionConfiguration = { session, config ->
                markerTracker.configure(session, config, aiming = true)
                markerTracker.unusableMarkers.firstOrNull()?.let { (marker, reason) ->
                    toast = Toast(1, "AR sign “${marker.name.resolved()}” can't be used: $reason", 6_000)
                }
            },
            onSessionUpdated = { _, frame ->
                val message = ArMarkerTracker.trackingMessage(frame)
                if (message != trackingMessage) trackingMessage = message

                for (sighting in markerTracker.sightings(frame, frame.timestamp / 1_000_000)) {
                    // Same rule as navigation: the first sign aligns, later ones are blended in by distance.
                    val candidate = MarkerAlignment.alignment(sighting.markerInSession, MarkerAlignment.transform(sighting.marker))
                    if (filter.update(candidate, sighting.distanceMeters)) {
                        alignment = filter.alignment
                        if (alignedMarker == null) alignedMarker = sighting.marker
                    }
                }
                val marker = alignedMarker
                if (marker != null && message == null) {
                    val matrix = FloatArray(16)
                    frame.camera.pose.toMatrix(matrix, 0)
                    val inVenue = (alignment.inverse() * Mat4(matrix)).translation
                    val level = venue.level(forDeviceHeight = inVenue.y)?.id ?: marker.level
                    here = Triple(level, inVenue.x, inVenue.z)

                    // The object the user points at: the surface under the screen center, in venue coordinates.
                    val aimedWorld = markerTracker.aimedPoint(frame, view.width.toFloat(), view.height.toFloat())
                    aimed = aimedWorld?.let { alignment.inverse().transformPoint(it) }
                        ?.takeIf { hypot(it.x - inVenue.x, it.z - inVenue.z) <= MAX_AIM_DISTANCE_M }
                        ?.let { it.x to it.z }

                    if (recorders.lastOrNull()?.level != level) {
                        val floor = venue.level(level)?.elevation ?: 0f
                        val recorder = WalkRecorder(level, floor)
                        // Start the path at the sign itself, then straight to where the user stands: they just
                        // looked at the sign, so the way between is clear. This lets routes begin at the sign later.
                        if (recorders.isEmpty()) lineFrom(Vec3(marker.x, floor, marker.z), inVenue).forEach(recorder::recordAnchor)
                        recorders += recorder
                    }
                    recorders.last().record(inVenue)

                    val count = recordedPoints().size
                    canSave = recordedLength() >= MIN_PATH_LENGTH_M
                    recordedSummary = "Recording · $count points · %.0f m".format(recordedLength())
                } else {
                    aimed = null
                }
            },
            onSessionFailed = { error -> toast = Toast(2, "AR isn't available: ${error.message}", 8_000) },
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val marker = alignedMarker
            if (marker == null) {
                AlignmentPromptView(
                    trackingMessage ?: "Look for a sign like these near entrances, elevators and escalators. " +
                        "Scanning it starts recording your walk.",
                    signs,
                )
            } else {
                val levelName = here?.let { venue.level(it.first)?.name?.resolved() }
                GuidanceBannerView(
                    GuidanceBanner(
                        instruction = if (canSave) "Walk to the destination, then save it" else "Walk to where the destination is",
                        glyph = "●",
                        detail = when {
                            trackingMessage != null -> trackingMessage!!
                            else -> listOfNotNull(
                                levelName, recordedSummary.ifEmpty { null },
                                if (aimed != null) "aiming at object ✓" else "aim the + at the object",
                            ).joinToString(" · ")
                        },
                    ),
                )
            }
        }

        if (alignedMarker != null) {
            Text(
                "+",
                color = if (aimed != null) Color(0xFF34C759) else Color.White,
                fontSize = 40.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Row(Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(16.dp)) {
            CircleButton("✕", "Cancel", onClick = onCancel)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToastHost(toast)
            Button(enabled = canSave && trackingMessage == null, onClick = { naming = true }) {
                Text("Save destination here")
            }
        }
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name this destination") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("e.g. Sofa") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && here != null,
                    onClick = {
                        naming = false
                        val (level, userX, userZ) = here!!
                        // End the recorded path exactly where the user stands, and put the destination on the
                        // object they aim at (or under them when no surface was found).
                        recorders.lastOrNull { it.level == level }?.recordAnchor(Vec3(userX, 0f, userZ))
                        val (x, z) = aimed ?: (userX to userZ)
                        onSave(CapturedDestination(name.trim(), level, x, z, recordedPoints()))
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

/** Points every 0.5 m (at most) from [from] to [to], excluding [to] itself (the recorder adds that one). */
private fun lineFrom(from: Vec3, to: Vec3): List<Vec3> {
    val length = hypot(to.x - from.x, to.z - from.z)
    val steps = ceil(length / 0.5f).toInt().coerceAtLeast(1)
    return (0 until steps).map { i ->
        val t = i / steps.toFloat()
        Vec3(from.x + (to.x - from.x) * t, from.y, from.z + (to.z - from.z) * t)
    }
}
