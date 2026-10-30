package com.app.androidnativearcore.wayfinder.ar

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
 *
 * With [pickPosition] it is used to find a spot on the map instead (for example where a Bluetooth beacon hangs):
 * once a reference point is recognized it keeps tracking, shows the phone's live position, and reports it through
 * [onLocated] only when the user, standing at the spot, taps "Use this position".
 */
@Composable
fun WayFinderLocateScreen(
    pkg: VenuePackage,
    onLocated: (LocatedUser) -> Unit,
    onCancel: () -> Unit,
    /** A reference photo saved without a size was measured by ARCore (width in meters): save it for next time. */
    onReferenceSizeMeasured: (markerId: String, widthMeters: Float) -> Unit = { _, _ -> },
    /** Pick a spot: keep tracking after recognition and report the position the user confirms. */
    pickPosition: Boolean = false,
    /** What the user walks to in [pickPosition] mode, e.g. "the beacon". */
    pickTarget: String = "the spot",
    /** Where Bluetooth beacons say the user is ("Near the entrance beacon · Ground Floor"), shown while scanning. */
    beaconHint: String? = null,
    modifier: Modifier = Modifier,
) {
    val venue = pkg.venue
    val markerTracker = remember(pkg) { ArMarkerTracker(pkg) }
    markerTracker.onSizeMeasured = { marker, width -> onReferenceSizeMeasured(marker.id, width) }
    val signMarkers = remember(pkg) {
        venue.markers.mapNotNull { marker -> BitmapFactory.decodeFile(pkg.file(marker).path)?.let { marker to it } }
    }
    val signs = remember(signMarkers) { signMarkers.map { (marker, bitmap) -> marker.name.resolved() to bitmap } }
    // The reference photo chosen to line the camera up with, and where its frame is on screen.
    var guide by remember { mutableStateOf<Int?>(null) }
    var guideFrame by remember { mutableStateOf<FloatArray?>(null) }
    var cameraFrame by remember { mutableStateOf<FloatArray?>(null) } // the whole camera view on screen
    val frameCount = remember { IntArray(1) }
    val filter = remember(pkg) { AlignmentFilter() }
    val reported = remember(pkg) { BooleanArray(1) }
    var matched by remember { mutableStateOf<Venue.Marker?>(null) }
    var progress by remember { mutableStateOf(0) }
    var trackingMessage by remember { mutableStateOf<String?>("Starting the camera… move the phone slowly.") }
    var toast by remember { mutableStateOf<Toast?>(null) }
    // Pick mode: the phone's live position on the map once a reference point was recognized.
    var live by remember { mutableStateOf<LocatedUser?>(null) }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(onBack = onCancel)

    Box(modifier.fillMaxSize().background(Color.Black)) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            sessionCameraConfig = ArMarkerTracker::preferredCameraConfig,
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
                if (frameCount[0]++ % 15 == 0) {
                    a4FrameOnScreen(frame, view)?.let { if (!it.contentEquals(guideFrame)) guideFrame = it }
                    cameraViewOnScreen(frame)?.let { if (!it.contentEquals(cameraFrame)) cameraFrame = it }
                }
                val now = frame.timestamp / 1_000_000
                for (sighting in markerTracker.sightings(frame, now, LOCATE_INTERVAL_MS)) {
                    // Sightings of two different points can't be averaged: start over when the camera moves to another.
                    if (matched != null && matched?.id != sighting.marker.id && !filter.isLockedIn) filter.reset()
                    matched = sighting.marker
                    val candidate = MarkerAlignment.alignment(sighting.markerInSession, MarkerAlignment.transform(sighting.marker))
                    val isCapturedRef = sighting.marker.id.startsWith("ref-")
                    // Captured photo reference points are harder for ARCore to hold across viewpoints, so they lock in
                    // after two readings without side-to-side movement; both are still weighted by real distance/angle.
                    filter.update(
                        candidate, sighting.distanceMeters, sighting.viewCosine,
                        if (isCapturedRef) null else sighting.cameraInSession, countAsClose = isCapturedRef,
                    )
                    progress = filter.closeSightings.coerceAtMost(AlignmentFilter.LOCK_IN_SIGHTINGS)
                    if (filter.isLockedIn && !pickPosition) {
                        val camera = frame.camera.pose
                        val inVenue = filter.alignment.inverse().transformPoint(Vec3(camera.tx(), camera.ty(), camera.tz()))
                        val level = venue.level(forDeviceHeight = inVenue.y)?.id ?: sighting.marker.level
                        reported[0] = true
                        onLocated(LocatedUser(sighting.marker, level, inVenue.x, inVenue.z))
                        return@ARSceneView
                    }
                }
                // Pick mode: follow the phone on the map once it is known, a few times a second.
                val anchor = matched
                if (pickPosition && filter.isLockedIn && anchor != null && message == null && frameCount[0] % 6 == 0) {
                    val camera = frame.camera.pose
                    val inVenue = filter.alignment.inverse().transformPoint(Vec3(camera.tx(), camera.ty(), camera.tz()))
                    val level = venue.level(forDeviceHeight = inVenue.y)?.id ?: anchor.level
                    val previous = live
                    if (previous == null || previous.level != level ||
                        kotlin.math.hypot(previous.x - inVenue.x, previous.z - inVenue.z) > 0.02f
                    ) {
                        live = LocatedUser(anchor, level, inVenue.x, inVenue.z)
                    }
                }
            },
            onSessionFailed = { error -> toast = Toast((toast?.id ?: 0) + 1, "AR isn't available: ${error.message}", 8_000) },
        )

        // The chosen reference photo, faded where it was framed, until a point is recognized.
        val chosen = guide?.let { signMarkers.getOrNull(it) }?.takeIf { it.first.id.startsWith("ref-") }
        if (matched == null && chosen != null) {
            ReferenceGuideOverlay(chosen.second, guideFrame, cameraFrame, sizeUnknown = chosen.first.physicalWidth <= 0f)
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val point = matched
            val here = live
            if (beaconHint != null && point == null) {
                Text(
                    beaconHint,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .background(Color(0xCC1C1C1E), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (point == null) {
                AlignmentPromptView(
                    trackingMessage ?: if (pickPosition) {
                        "First show the camera a sign or a saved reference point, 1–2.5 m away, so the app knows where " +
                            "you are on the map. Then walk to $pickTarget."
                    } else {
                        "Show the camera any reference point you can see (a sign or a marked spot) " +
                            "from 1–2.5 m and move the phone slowly side to side."
                    },
                    signs,
                    selected = guide,
                    onSelect = { index -> guide = if (guide == index) null else index },
                )
            } else if (pickPosition && filter.isLockedIn) {
                val floor = here?.let { venue.level(it.level)?.name?.resolved() ?: it.level }
                GuidanceBannerView(
                    GuidanceBanner(
                        instruction = "Map found — walk to $pickTarget",
                        glyph = "📍",
                        detail = trackingMessage ?: (
                            "Hold the phone right next to $pickTarget, then tap Use this position." +
                                (here?.let { " Now: X %.2f m · Y %.2f m · %s".format(it.x, it.z, floor) } ?: "")
                            ),
                    ),
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
            CircleButton(
                glyph = "✕",
                description = "Cancel: Exit camera and return to the destination list",
                onClick = onCancel,
                onLongPress = { toast = Toast((toast?.id ?: 0) + 1, it, 3_500) },
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ToastHost(toast)
            val here = live
            if (pickPosition && filter.isLockedIn && here != null) {
                Button(
                    enabled = trackingMessage == null,
                    onClick = {
                        reported[0] = true
                        onLocated(here)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Use this position") }
            }
        }
    }
}
