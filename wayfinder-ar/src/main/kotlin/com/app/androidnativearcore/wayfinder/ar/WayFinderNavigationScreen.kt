package com.app.androidnativearcore.wayfinder.ar

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.androidnativearcore.wayfinder.core.CustomDestination
import com.app.androidnativearcore.wayfinder.core.LocalizedText
import com.app.androidnativearcore.wayfinder.core.Mat4
import com.app.androidnativearcore.wayfinder.core.PlacedObject
import com.app.androidnativearcore.wayfinder.core.REFERENCE_CATEGORY
import com.app.androidnativearcore.wayfinder.core.RouteProfile
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.VenuePackage
import com.app.androidnativearcore.wayfinder.core.WalkedPoint
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import kotlin.math.hypot

/**
 * Full-screen AR navigation & live room view:
 *
 * - Keeps the user in the live AR camera view at all times after matching a reference point.
 * - Shows **"You are currently at: <Reference Point>"** at the top of the camera view, paired with a **Dropdown**
 *   of all available destinations, reference points (`📍`), and placed objects (`☕`/`🎁`/`⭐`/`🎈`/`💬`), sorted
 *   by distance.
 * - Dynamically renders **all reference points with blue pins & blue text labels** (`ReferencePointsScene`) and
 *   **all placed objects** (`ObjectsScene`) in the live camera video alongside the 3D route arrows (`RouteScene`).
 */
@Composable
fun WayFinderNavigationScreen(
    pkg: VenuePackage,
    destinationId: String,
    profile: RouteProfile = RouteProfile.FASTEST,
    listener: WayFinderNavigationListener? = null,
    languages: List<String>? = null,
    /** Paths people walked while adding destinations; routes to those destinations follow them. */
    walkedPoints: List<WalkedPoint> = emptyList(),
    /** Objects placed while recording; all objects are rendered in the live camera video. */
    placedObjects: List<PlacedObject> = emptyList(),
    /** The sign this destination's path was recorded from, if known. */
    recordedFromMarkerId: String = "",
    /** A reference point the user was just located at (on the scan screen): offered first as the line-up guide. */
    guideMarkerId: String = "",
    modifier: Modifier = Modifier,
    debug: Boolean = false,
) {
    val venue = pkg.venue
    val defaultLevel = venue.levels.firstOrNull()?.id.orEmpty()
    val initialDest = venue.poi(destinationId)
        ?: venue.pois.firstOrNull { it.category == CustomDestination.CATEGORY }
        ?: venue.pois.firstOrNull()
        ?: Venue.POI(
            id = "default-poi",
            name = LocalizedText("Room"),
            category = CustomDestination.CATEGORY,
            level = defaultLevel,
            x = 0f,
            z = 0f,
            node = "",
        )

    val controller = remember(pkg, profile, walkedPoints, placedObjects) {
        NavigationController(pkg, initialDest, profile, listener, languages, walkedPoints, recordedFromMarkerId, placedObjects)
    }
    controller.listener = listener
    LaunchedEffect(destinationId) {
        venue.poi(destinationId)?.let {
            if (it.id != controller.destination.id) {
                controller.selectDestination(it)
            }
        }
    }

    val markerTracker = remember(pkg) { ArMarkerTracker(pkg) }
    val state = controller.uiState
    val activeDestination = controller.destination
    val signMarkers = remember(pkg) {
        pkg.venue.markers.mapNotNull { marker -> BitmapFactory.decodeFile(pkg.file(marker).path)?.let { marker to it } }
    }
    val signs = remember(signMarkers) { signMarkers.map { (marker, bitmap) -> marker.name.resolved() to bitmap } }
    // The reference photo to line the camera up with (the one this path was recorded from, if it is a photo), and
    // where its frame is on screen.
    var guide by remember(signMarkers) {
        mutableStateOf(
            signMarkers.indexOfFirst { it.first.id.startsWith("ref-") && (it.first.id == guideMarkerId || it.first.id == recordedFromMarkerId) }
                .takeIf { it >= 0 },
        )
    }
    var guideFrame by remember { mutableStateOf<FloatArray?>(null) }
    val frameCount = remember { IntArray(1) }
    var showFeedback by remember { mutableStateOf(false) }
    var showNavHelp by remember { mutableStateOf(false) }
    var show2DMapDialog by remember { mutableStateOf(false) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    // Reference points to render in blue in the live camera video
    val refPins = remember(venue.markers) {
        venue.markers.filter { it.id.startsWith("ref-") }.map {
            RefPin(it.x, it.y, it.z, it.name.resolved())
        }
    }

    // Current reference point name ("You are currently at")
    val userPos = state.user?.position
    val currentAtName = remember(controller.lastAlignedMarker, userPos, refPins, controller.isLockedIn) {
        // Only once a sign or reference point was recognized: before that the position means nothing.
        if (!controller.isLockedIn) return@remember "Scan a reference point / sign"
        val nearestRef = if (userPos != null) {
            refPins.minByOrNull { hypot(it.x - userPos.x, it.z - userPos.z) }
                ?.takeIf { hypot(it.x - userPos.x, it.z - userPos.z) <= 2.5f }
        } else null
        nearestRef?.name
            ?: controller.lastAlignedMarker?.name?.resolved()
            ?: "Scan a reference point / sign"
    }

    // Build all navigable POIs (saved destinations, reference points, placed objects, venue POIs), nearest first
    val availableTargets = remember(venue.pois, placedObjects, userPos) {
        val objectPois = placedObjects.filter { !it.isReference }.map { obj ->
            val label = obj.text.ifBlank { obj.kind.label }
            Venue.POI(
                id = "objpoi-${obj.id}",
                name = LocalizedText("${obj.kind.glyph} $label"),
                category = CustomDestination.CATEGORY,
                level = obj.level.ifEmpty { defaultLevel },
                x = obj.x,
                z = obj.z,
                node = "",
                elevation = obj.y,
            )
        }
        val combined = venue.pois + objectPois
        if (userPos != null) {
            combined.sortedWith(
                compareBy(
                    { it.category != CustomDestination.CATEGORY && it.category != REFERENCE_CATEGORY },
                    { hypot(it.x - userPos.x, it.z - userPos.z) },
                ),
            )
        } else {
            combined.sortedBy { it.category != CustomDestination.CATEGORY && it.category != REFERENCE_CATEGORY }
        }
    }

    // Keep the screen awake while navigating
    val view = LocalView.current
    // The map alignment as drawn: anchored to the real world and changed smoothly (see StableAlignment).
    val stable = remember { StableAlignment() }
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            stable.release()
        }
    }
    BackHandler { controller.finish() }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            sessionCameraConfig = ArMarkerTracker::preferredCameraConfig,
            sessionConfiguration = { session, config ->
                markerTracker.configure(session, config)
                markerTracker.unusableMarkers.firstOrNull()?.let { (marker, reason) ->
                    controller.showToast("AR sign “${marker.name.resolved()}” can't be used: $reason", 6_000)
                }
            },
            onSessionUpdated = { session, frame ->
                val now = frame.timestamp / 1_000_000
                if (!controller.isLockedIn && frameCount[0]++ % 15 == 0) {
                    a4FrameOnScreen(frame, view)?.let { if (!it.contentEquals(guideFrame)) guideFrame = it }
                }
                for (sighting in markerTracker.sightings(frame, now, if (controller.isLockedIn) ArMarkerTracker.REDETECT_INTERVAL_MS else 300L)) {
                    controller.onMarkerDetected(sighting.marker, sighting.markerInSession, sighting.distanceMeters, sighting.cameraInSession, sighting.viewCosine)
                }
                val matrix = FloatArray(16)
                frame.camera.pose.toMatrix(matrix, 0)
                controller.onFrame(Mat4(matrix), ArMarkerTracker.trackingMessage(frame))
                stable.update(session, controller.alignment, controller.isLockedIn, frame.timestamp)
            },
            onSessionFailed = { error -> controller.showToast("AR isn't available: ${error.message}", 8_000) },
        ) {
            val camPosProvider = { controller.cameraSessionPosition.let { Position(it.x, it.y, it.z) } }
            // Nothing is drawn until the map is found: before that, venue positions would land in arbitrary places.
            if (stable.ready) {
                RouteScene(
                    route = state.route,
                    level = state.renderedLevel,
                    venue = pkg.venue,
                    destination = activeDestination,
                    alignment = stable.display,
                    cameraPosition = camPosProvider,
                    languages = languages,
                )
                // Render ALL placed objects in the room so the user sees them in the live video as on the map
                ObjectsScene(
                    objects = placedObjects,
                    alignment = stable.display,
                    cameraPosition = camPosProvider,
                )
                // Render ALL reference points with blue pins and blue text labels in the live video
                ReferencePointsScene(
                    points = refPins,
                    alignment = stable.display,
                    cameraPosition = camPosProvider,
                )
            }
        }

        // Until the map is found: the chosen reference photo, faded where it was framed, to line the camera up with.
        val chosen = guide?.let { signMarkers.getOrNull(it) }?.takeIf { it.first.id.startsWith("ref-") }
        val onScreen = guideFrame
        if (!controller.isLockedIn && chosen != null && onScreen != null) ReferenceGuideOverlay(chosen.second, onScreen)

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top bar: "You are currently at" + Dropdown for available navigations
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0xCC1C1C1E), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("You are currently at", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text(
                            "📍 $currentAtName",
                            color = Color(0xFF0A84FF),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    CircleButton(
                        glyph = "ⓘ",
                        description = "Navigation Help: Tap to see what each control on this screen does",
                        onClick = { showNavHelp = true },
                        onLongPress = { controller.showToast(it, 3_500) },
                    )
                }

                // Destination / Reference Point / Object Dropdown
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color(0xFF2C2C2E), RoundedCornerShape(10.dp))
                            .clickable { dropdownExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The walking distance along the route (not the straight line, which may cross walls).
                        val distText = state.route?.let { " · %.0f m walk".format(it.length) }.orEmpty()
                        val glyph = when {
                            activeDestination.id.startsWith("objpoi-") -> ""
                            activeDestination.category == REFERENCE_CATEGORY -> "📍 "
                            activeDestination.category == CustomDestination.CATEGORY -> "⭐ "
                            else -> "🏁 "
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Navigate to: $glyph${activeDestination.name.resolved()}$distText",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            // The shortest route found: where it starts, its length and the reference points on the way.
                            if (state.route != null && controller.lastRouteSummary.isNotEmpty()) {
                                Text(controller.lastRouteSummary, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                        }
                        Text("▾", color = Color(0xFF0A84FF), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 340.dp),
                    ) {
                        availableTargets.forEach { poi ->
                            val isRef = poi.category == REFERENCE_CATEGORY
                            val isObj = poi.id.startsWith("objpoi-")
                            val glyph = when {
                                isObj -> ""
                                isRef -> "📍 "
                                poi.category == CustomDestination.CATEGORY -> "⭐ "
                                else -> "🏁 "
                            }
                            val sub = when {
                                isObj -> "Placed object"
                                isRef -> "Reference point"
                                poi.category == CustomDestination.CATEGORY -> "Saved destination"
                                else -> "Venue destination"
                            }
                            val dist = userPos?.let { hypot(poi.x - it.x, poi.z - it.z) }
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "$glyph${poi.name.resolved()}",
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isRef) Color(0xFF0A84FF) else Color.Unspecified,
                                            )
                                            Text(sub, fontSize = 12.sp, color = Color.Gray)
                                        }
                                        if (dist != null) {
                                            Text("%.1f m".format(dist), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                },
                                onClick = {
                                    dropdownExpanded = false
                                    controller.selectDestination(poi)
                                },
                            )
                        }
                    }
                }
            }

            state.banner?.let { GuidanceBannerView(it) }
            state.alignmentPrompt?.let {
                AlignmentPromptView(it, signs, selected = guide, onSelect = { index -> guide = if (guide == index) null else index })
            }
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
            CircleButton(
                glyph = "✕",
                description = "Close navigation: Exit the live AR camera and return to the list",
                onClick = controller::finish,
                onLongPress = { controller.showToast(it, 3_500) },
            )
            CircleButton(
                glyph = "🗺",
                description = "2D Floor Map: Opens a 2D map dialog displaying all reference points, destinations, 3D objects, and walked paths",
                onClick = { show2DMapDialog = true },
                onLongPress = { controller.showToast(it, 3_500) },
            )
            CircleButton(
                glyph = "!",
                description = "Report a problem: Report a blocked route, missing sign, or wrong directions",
                onClick = { showFeedback = true },
                onLongPress = { controller.showToast(it, 3_500) },
            )
            CircleButton(
                glyph = "ⓘ",
                description = "Help Guide: View info on what every button and indicator does",
                onClick = { showNavHelp = true },
                onLongPress = { controller.showToast(it, 3_500) },
            )
        }
        Column(
            Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ToastHost(state.toast)
            MiniMapView(state.route, state.renderedLevel, activeDestination, pkg.venue, state.user)
        }

        // Dismissing arrival keeps the user in the live camera view so they can pick another destination from the dropdown!
        state.arrival?.let { ArrivalOverlay(it, onDone = controller::dismissArrival) }
    }

    if (show2DMapDialog) {
        val allPoisForMap = venue.pois.filter { it.category != REFERENCE_CATEGORY }
        Live2DMapDialog(
            walkedPoints = walkedPoints,
            liveWalkPoints = emptyList(),
            refPins = refPins,
            destinations = allPoisForMap,
            objects = placedObjects,
            userPos = userPos ?: Vec3.ZERO,
            activeRoutePoints = state.route?.points.orEmpty(),
            currentFromName = currentAtName,
            onDismiss = { show2DMapDialog = false },
            onSelectNavigate = { item ->
                availableTargets.firstOrNull {
                    it.id == item.id || it.id == "objpoi-${item.id}" || it.name.resolved().contains(item.label, ignoreCase = true)
                }?.let { controller.selectDestination(it) }
            },
        )
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

    if (showNavHelp) {
        AlertDialog(
            onDismissRequest = { showNavHelp = false },
            title = { Text("AR Navigation Guide") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Tip: Long-press any button on the camera view for quick info!", fontSize = 13.sp, color = Color(0xFF0A84FF), fontWeight = FontWeight.SemiBold)
                    Text("• 📍 You are currently at:\nShows the reference point or sign the camera matched (or the nearest reference point as you walk).")
                    Text("• Navigate to ▾ (Dropdown):\nPick any saved destination (⭐), reference point (📍 in blue), or placed 3D object to immediately draw 3D route arrows in the live camera.")
                    Text("• Blue 3D Pins (📍):\nReference points placed around the room, shown with blue floating labels.")
                    Text("• ✕ (Close):\nExits the camera view back to the destination list.")
                    Text("• ! (Report a problem):\nReport a blocked route, missing sign, or inaccurate directions.")
                }
            },
            confirmButton = { TextButton(onClick = { showNavHelp = false }) { Text("Got it") } },
        )
    }
}
