package com.app.androidnativearcore.wayfinder.ar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.androidnativearcore.wayfinder.core.AlignmentFilter
import com.app.androidnativearcore.wayfinder.core.AlignmentTrack
import com.app.androidnativearcore.wayfinder.core.CustomDestination
import com.app.androidnativearcore.wayfinder.core.LocalizedText
import com.app.androidnativearcore.wayfinder.core.MarkerAlignment
import com.app.androidnativearcore.wayfinder.core.Mat4
import com.app.androidnativearcore.wayfinder.core.PlacedObject
import com.app.androidnativearcore.wayfinder.core.PlacedObjectKind
import com.app.androidnativearcore.wayfinder.core.REFERENCE_CATEGORY
import com.app.androidnativearcore.wayfinder.core.ReferenceGeometry
import com.app.androidnativearcore.wayfinder.core.RegisteredBeacon
import com.app.androidnativearcore.wayfinder.core.Route
import com.app.androidnativearcore.wayfinder.core.RoutePlanner
import com.app.androidnativearcore.wayfinder.core.RouteProfile
import com.app.androidnativearcore.wayfinder.core.SessionPathRecorder
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.VenuePackage
import com.app.androidnativearcore.wayfinder.core.WalkedPathRouter
import com.app.androidnativearcore.wayfinder.core.WalkedPoint
import com.app.androidnativearcore.wayfinder.core.WalkedRouteResult
import com.app.androidnativearcore.wayfinder.core.followsWalkedPaths
import com.app.androidnativearcore.wayfinder.core.objectScaleFor
import com.app.androidnativearcore.wayfinder.core.spreadObjectPosition
import com.app.androidnativearcore.wayfinder.core.straightWalk
import com.app.androidnativearcore.wayfinder.core.toVenue
import com.app.androidnativearcore.wayfinder.core.toWalkedPoints
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.delay

/**
 * A spot the user chose, in venue coordinates, with the walk that leads to it from the sign they scanned and the
 * objects they placed along the way. Save the destination, [path] and [objects] together; routes to the
 * destination follow the path, and the objects show up while walking it.
 */
data class CapturedDestination(
    val name: String,
    val level: String,
    val x: Float,
    val z: Float,
    val path: List<WalkedPoint>,
    /** Objects the user placed along the path; [PlacedObject.destinationId] is still empty. */
    val objects: List<PlacedObject> = emptyList(),
    /** Height of the real floor under the destination, in venue coordinates (null if unknown). */
    val floorY: Float? = null,
    /** The sign the walk started from. */
    val startMarkerId: String = "",
    /** How well the sign sightings agreed while recording, in centimeters (smaller is better). */
    val alignmentSpreadCm: Float? = null,
    /** How much drift (cm) re-scanning a sign during the walk corrected; null if no sign was re-scanned. */
    val driftCorrectedCm: Float? = null,
)

/**
 * A reference point the admin captured: a photo of a distinctive spot plus where it is, so it can be registered as
 * an AR marker and later re-recognized to re-align the session. The caller saves the [image] and builds a
 * [com.app.androidnativearcore.wayfinder.core.Venue.Marker] from the rest.
 */
data class CapturedReferencePoint(
    val name: String,
    val image: Bitmap,
    /** Real-world width of [image], in meters (estimated from distance and camera focal length). */
    val physicalWidth: Float,
    val level: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val facing: Float,
    val mounting: Venue.MarkerMounting,
)

/** What the capture produced, held while the admin names/confirms it. */
private class RefCapture(
    val image: Bitmap,
    val physicalWidth: Float,
    val level: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val facing: Float,
    /** Detail proxy 0–100. */
    val detail: Int,
    /** Whether ARCore accepted the image into an augmented-image database (will it track?). */
    val accepted: Boolean,
    val distanceM: Float,
    /** Why the photo may match poorly (not flat, seen at a steep angle), or null if it looks fine. */
    val problem: String? = null,
)

/** An entry in the in-camera navigation dropdown (destination, reference point, or placed object). */
private data class AdminNavTarget(
    val id: String,
    val label: String,
    val glyph: String,
    val subtitle: String,
    val level: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val poi: Venue.POI? = null,
)

/** Degrees to rotate the sensor-landscape camera image so it is upright for the current display orientation. */
private fun displayRotationDegrees(view: android.view.View): Int = when (view.display?.rotation) {
    Surface.ROTATION_90 -> 0
    Surface.ROTATION_180 -> 270
    Surface.ROTATION_270 -> 180
    else -> 90
}

/** Log tag for accuracy diagnostics: reference captures, object placement, alignment and tracking events. */
internal const val ACCURACY_TAG = "WFAccuracy"

/** A reference photo with less detail than this (0–100, [CameraImage.detailScore]) is slow to recognize. */
private const val LOW_DETAIL = 35

/** A new reference point closer than this (meters) to an existing one is reported as a duplicate. */
private const val DUPLICATE_REFERENCE_M = 0.3f

/** Why a reference point can't be added or a walk started yet: the saved map hasn't been found in this session. */
private const val NOT_ON_MAP_YET = "First find the saved map: point the camera at a sign or a saved reference point " +
    "(tap its photo at the top to line it up) and hold it until it is recognized. New points then line up with the others."

/** Auto-capture a reference point roughly every this many metres walked (when the toggle is on). */
private const val AUTO_INTERVAL_M = 3f

/** Don't auto-capture two reference points closer together than this. */
private const val AUTO_MIN_SEPARATION_M = 2f

/** Shortest recorded walk (meters) that is worth saving as a route. */
private const val MIN_PATH_LENGTH_M = 0.3f

/** Objects can be placed on surfaces up to this far away. */
private const val MAX_OBJECT_DISTANCE_M = 6f

/** Objects tapped closer together than this are moved apart so none hides another. */
private const val MIN_OBJECT_SEPARATION_M = 0.2f

/** The destination is the average of the phone's last [STEADY_FRAMES] positions, once they stay this close together. */
private const val STEADY_FRAMES = 20
private const val STEADY_RADIUS_M = 0.04f

private fun averageOf(points: Collection<Vec3>): Vec3? {
    if (points.isEmpty()) return null
    val n = points.size.toFloat()
    return Vec3(
        points.sumOf { it.x.toDouble() }.toFloat() / n,
        points.sumOf { it.y.toDouble() }.toFloat() / n,
        points.sumOf { it.z.toDouble() }.toFloat() / n,
    )
}

/** Sightings of a sign closer together in time than this belong to one scan ("visit"); a longer gap starts a new one. */
private const val VISIT_GAP_MS = 4_000L

/** One scan of a sign: the alignment its sightings gave, and how far the user had walked when it happened. */
private class Visit(var progress: Float, var lastMs: Long) {
    val filter = AlignmentFilter()
}

/** Typical height of a phone above the floor, to put the reference object on the floor under the scan position. */
private const val PHONE_HEIGHT_M = 1.4f

/** Milliseconds without tracking after which the AR world may have been re-based, so the sign must be scanned again. */
private const val TRACKING_LOST_RESET_MS = 3_000L

/** While locking onto the sign, re-detect it this often (the navigation screen uses a slower 1.5 s). */
private const val LOCK_IN_INTERVAL_MS = 400L

/** The user's latest pose, written every frame. */
private class LiveUser {
    var valid = false
    var session = Vec3.ZERO
    var forward = Vec3.ZERO
    var level = ""
    var venueX = 0f
    var venueZ = 0f
}

/**
 * Full-screen AR "Add destination" & live Admin authoring screen:
 *
 * - All action buttons (`✕ Cancel`, `⊕ Start here`, `◎ Add point`, `☰ Points`, `◳ Objects`, `ObjectBar`,
 *   `Save destination here`, `Auto points`) are ALWAYS visible so the admin can capture reference points, place
 *   objects, or save destinations at any moment.
 * - Shows a live top bar with **"You are currently at: <Reference Point>"** and a **Dropdown** of all available
 *   destinations, reference points, and placed objects to preview 3D AR navigation routes right inside the camera.
 * - Dynamically renders all reference points in **blue** (`ReferencePointsScene`) and all placed objects
 *   (`ObjectsScene`) in the live camera video.
 */
@Composable
fun WayFinderCaptureScreen(
    pkg: VenuePackage,
    onSave: (CapturedDestination) -> Unit,
    onCancel: () -> Unit,
    /** A reference photo saved without a size was measured by ARCore (width in meters): save it for next time. */
    onReferenceSizeMeasured: (markerId: String, widthMeters: Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    /** Called when the admin captures and confirms a reference point; the caller persists it. */
    onSaveReferencePoint: (CapturedReferencePoint) -> Unit = {},
    /** Called when the admin deletes a reference point from the in-camera Points list. */
    onDeleteReferencePoint: (id: String, name: String) -> Unit = { _, _ -> },
    /** Called when the admin deletes a previously saved object from the in-camera Objects list. */
    onDeleteObject: (id: String) -> Unit = {},
    /** Called when the admin opens the AR Signs gallery directly from the camera HUD. */
    onOpenSigns: (() -> Unit)? = null,
    /** Called when connecting a walked path to a reference point whose 3D venue position should be updated. */
    onUpdateReferencePointCoords: (id: String, x: Float, y: Float, z: Float) -> Unit = { _, _, _, _ -> },
    /** Optional starting reference point ID pre-selected from the Admin Connect panel. */
    initialFromRefId: String? = null,
    /** Optional target reference point / object / destination ID pre-selected from the Admin Connect panel. */
    initialConnectTargetId: String? = null,
    /** Previously saved walked points from earlier destinations, for live in-camera routing preview. */
    existingWalkedPoints: List<WalkedPoint> = emptyList(),
    /** Previously saved 3D objects from earlier destinations, rendered in the live camera video. */
    existingObjects: List<PlacedObject> = emptyList(),
    /** The registered Bluetooth beacons, drawn in the camera at their spots once the map is found. */
    beacons: List<RegisteredBeacon> = emptyList(),
) {
    val venue = pkg.venue
    val defaultLevel = venue.levels.firstOrNull()?.id.orEmpty()
    val markerTracker = remember(pkg) { ArMarkerTracker(pkg) }
    markerTracker.onSizeMeasured = { marker, width -> onReferenceSizeMeasured(marker.id, width) }
    val planner = remember(venue) { RoutePlanner(venue) }
    val signMarkers = remember(pkg) {
        venue.markers.mapNotNull { marker -> BitmapFactory.decodeFile(pkg.file(marker).path)?.let { marker to it } }
    }
    val signs = remember(signMarkers) { signMarkers.map { (marker, bitmap) -> marker.name.resolved() to bitmap } }
    var guide by remember { mutableStateOf<Int?>(null) } // the saved reference photo chosen to line the camera up with

    // --- Recording state ---
    val filter = remember(pkg) { AlignmentFilter() }
    var alignment by remember { mutableStateOf(Mat4.IDENTITY) }
    var startMarker by remember {
        mutableStateOf<Venue.Marker?>(
            Venue.Marker(
                id = "origin-bootstrap", name = LocalizedText("Start point"), image = "",
                physicalWidth = 0.2f, level = defaultLevel, x = 0f, y = PHONE_HEIGHT_M, z = 0f, facing = 0f,
                mounting = Venue.MarkerMounting.WALL,
            ),
        )
    }
    val recorder = remember(pkg) { SessionPathRecorder() }
    // Segments already converted to venue coordinates (when tracking was lost, the old session frame is gone).
    val committedPath = remember(pkg) { mutableListOf<WalkedPoint>() }
    val committedObjects = remember(pkg) { mutableStateListOf<PlacedObject>() }
    // Objects placed since the last commit, in session coordinates (so no alignment change can shift them).
    val sessionObjects = remember(pkg) { mutableStateListOf<PlacedObject>() }
    // Reference points captured this session (venue coordinates), shown as blue pins in the camera.
    val sessionRefPins = remember(pkg) { mutableStateListOf<RefPin>() }
    // Destinations saved during this camera session (so they immediately appear in the dropdown without leaving camera).
    val sessionSavedDestinations = remember(pkg) { mutableStateListOf<Venue.POI>() }
    // ARCore keeps anchors glued to the real world as its tracking improves; objects follow their anchors.
    val anchors = remember(pkg) { HashMap<String, Anchor>() }
    val camera = remember { FloatArray(3) }       // camera position in the session
    val user = remember { LiveUser() }
    val pendingTap = remember { FloatArray(3) }   // x, y, and 1 when a tap waits to be handled in the next frame
    var lostSince by remember { mutableStateOf<Long?>(null) }
    val visits = remember(pkg) { mutableListOf<Visit>() }
    val otherSignToastMs = remember { LongArray(1) }
    val objectProgress = remember(pkg) { HashMap<String, Float>() }
    val driftCm = remember { FloatArray(1) }
    val recent = remember(pkg) { ArrayDeque<Vec3>() }
    val floorSamples = remember(pkg) { ArrayDeque<Float>() }
    val frameCounter = remember { IntArray(1) }

    // --- What the user sees ---
    var lockedIn by remember { mutableStateOf(false) }
    var lockProgress by remember { mutableStateOf(0) }
    var signAgreement by remember { mutableStateOf("") }
    var levelName by remember { mutableStateOf("") }
    var recordedSummary by remember { mutableStateOf("") }
    var canSave by remember { mutableStateOf(false) }
    var steady by remember { mutableStateOf(false) }
    var floorFound by remember { mutableStateOf(false) }
    var pendingDestination by remember { mutableStateOf<Vec3?>(null) }
    var pendingLevel by remember { mutableStateOf("") }
    var pendingFloorSession by remember { mutableStateOf(0f) }
    var trackingMessage by remember { mutableStateOf<String?>("Starting the camera… move the phone slowly.") }
    var toast by remember { mutableStateOf<Toast?>(null) }
    var naming by remember { mutableStateOf(false) }
    var selectedKind by remember { mutableStateOf(PlacedObjectKind.CUP) }
    var namingObjectId by remember { mutableStateOf<String?>(null) }
    var nameDraft by remember { mutableStateOf("") }
    var pendingRef by remember { mutableStateOf<RefCapture?>(null) }
    var refFailure by remember { mutableStateOf<String?>(null) } // why the last reference capture was refused
    var a4Frame by remember { mutableStateOf<FloatArray?>(null) } // the A4 photo frame on screen: left, top, right, bottom
    var cameraFrame by remember { mutableStateOf<FloatArray?>(null) } // the whole camera view on screen
    // Set when tracking was lost long enough that the session frame may have been re-based: from then on, new points
    // need the map to be found again even in a place that had no saved map (the earlier ones are in the old frame).
    val mapFrameLost = remember { BooleanArray(1) }
    val captureReq = remember { BooleanArray(1) }
    var autoPoints by remember { mutableStateOf(false) }
    val lastAutoProgress = remember { FloatArray(1) }
    var lastAutoPos by remember { mutableStateOf<Vec3?>(null) }
    val autoCount = remember { IntArray(1) }
    var greenFlash by remember { mutableStateOf<String?>(null) }
    // Auto-bootstrapped so all controls, tap-to-place, and reference point capture work immediately.
    var bootstrapped by remember { mutableStateOf(true) }
    var showPointsList by remember { mutableStateOf(false) }
    var showObjectsList by remember { mutableStateOf(false) }
    var showHelpGuide by remember { mutableStateOf(false) }
    var show2DMapDialog by remember { mutableStateOf(false) }
    var showConnectPickerDialog by remember { mutableStateOf(false) }
    var unusableRefsWarning by remember { mutableStateOf<String?>(null) }

    // --- Live "Currently at" & Dropdown Navigation state ---
    var currentAtName by remember { mutableStateOf("Start point") }
    var userVenuePos by remember { mutableStateOf(Vec3.ZERO) }
    var selectedNavTarget by remember { mutableStateOf<AdminNavTarget?>(null) }
    var activeRoute by remember { mutableStateOf<Route?>(null) }
    var activeRouteDestPoi by remember { mutableStateOf<Venue.POI?>(null) }
    val sessionConnectedWalkedPoints = remember(pkg) { mutableStateListOf<WalkedPoint>() }

    LaunchedEffect(greenFlash) { if (greenFlash != null) { delay(1500); greenFlash = null } }

    val view = LocalView.current
    // The map alignment as drawn: anchored to the real world and changed smoothly (see StableAlignment).
    val stable = remember { StableAlignment() }
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            anchors.values.forEach { it.detach() }
            anchors.clear()
            stable.release()
        }
    }
    BackHandler(onBack = onCancel)

    fun showToast(message: String, durationMs: Long = 2_000) {
        toast = Toast((toast?.id ?: 0) + 1, message, durationMs)
    }

    fun floorSessionY(): Float =
        floorSamples.sorted().let { if (it.isEmpty()) camera[1] - PHONE_HEIGHT_M else it[it.size / 2] }

    fun yawDegrees(m: Mat4) = Math.toDegrees(AlignmentFilter.yaw(m).toDouble()).toFloat()

    val hasUserObjects = sessionObjects.any { !it.isReference } || committedObjects.any { !it.isReference }
    val deletedRefIds = remember(pkg) { mutableStateListOf<String>() }
    val deletedRefNames = remember(pkg) { mutableStateListOf<String>() }
    val deletedObjectIds = remember(pkg) { mutableStateListOf<String>() }

    // Whether this place already has a map: reference points or destinations saved earlier. Their coordinates are only
    // meaningful once this session knows where the map is, which takes recognizing a sign or a saved reference point.
    // Until then, anything measured would be saved in this session's own arbitrary frame, out of line with the rest.
    val hadSavedMap = remember(pkg) {
        venue.markers.any { it.id.startsWith("ref-") } || venue.pois.any { it.category == CustomDestination.CATEGORY }
    }

    /**
     * Whether new things can be measured into the map now. With a saved map (or after the session frame may have been
     * re-based) that needs the map to have been found in this session by recognizing a sign or reference point.
     */
    fun onMap(): Boolean = filter.isLockedIn || !(hadSavedMap || mapFrameLost[0])

    // Combine saved reference points from venue + newly captured reference points in this session (deduped by name/pos).
    val allRefPins = remember(venue.markers, sessionRefPins.size, deletedRefIds.size, deletedRefNames.size) {
        val fromVenue = venue.markers
            .filter { it.id.startsWith("ref-") && it.id !in deletedRefIds && it.name.resolved() !in deletedRefNames }
            .map { RefPin(it.x, it.y, it.z, it.name.resolved(), it.id) }
        val combined = mutableListOf<RefPin>()
        combined.addAll(fromVenue)
        for (pin in sessionRefPins) {
            if (pin.name !in deletedRefNames && combined.none { it.name == pin.name && hypot(it.x - pin.x, it.z - pin.z) < 0.2f }) {
                combined.add(pin)
            }
        }
        combined
    }
    val hasValidStartRef = remember(allRefPins.size, lockedIn) {
        lockedIn || allRefPins.isNotEmpty()
    }

    // All venue-frame objects (saved from previous paths + committed in this session).
    val allVenueObjects = remember(existingObjects, committedObjects.size, deletedObjectIds.size) {
        (existingObjects + committedObjects).filter { it.id !in deletedObjectIds }
    }

    fun committedLength(): Float =
        committedPath.zipWithNext().sumOf { (a, b) ->
            if (a.run == b.run && a.level == b.level) hypot(a.x - b.x, a.z - b.z).toDouble() else 0.0
        }.toFloat()

    fun refreshFromAnchors() {
        for (index in sessionObjects.indices) {
            val obj = sessionObjects[index]
            val anchor = anchors[obj.id] ?: continue
            if (anchor.trackingState != TrackingState.TRACKING) continue
            val p = anchor.pose
            if (hypot(p.tx() - obj.x, p.tz() - obj.z) > 0.005f || kotlin.math.abs(p.ty() - obj.y) > 0.005f) {
                sessionObjects[index] = obj.copy(x = p.tx(), y = p.ty(), z = p.tz())
            }
        }
    }

    fun buildTrack(): AlignmentTrack = AlignmentTrack().also { track ->
        visits.filter { it.filter.closeSightings >= 2 }.forEach { track.add(it.progress, it.filter.alignment) }
        if (track.isEmpty) track.add(0f, filter.alignment)
    }

    fun commit() {
        val marker = startMarker ?: return
        val track = buildTrack()
        val raw = recorder.points
        if (raw.isNotEmpty()) {
            val converted = raw.toWalkedPoints(venue, track, marker.level, floorSessionY())
            if (committedPath.isEmpty()) {
                val first = converted.first()
                val floor = first.y
                val lead = straightWalk(Vec3(marker.x, floor, marker.z), Vec3(first.x, floor, first.z), first.run, first.level, floor)
                    .let { points -> points.mapIndexed { i, p -> p.copy(sequence = i - points.size) } }
                committedPath += lead
            }
            committedPath += converted
            recorder.clear()
        }
        refreshFromAnchors()
        committedObjects += sessionObjects.map { obj ->
            val alignmentHere = track.alignmentAt(objectProgress[obj.id] ?: 0f)
            obj.toVenue(venue, alignmentHere.inverse(), yawDegrees(alignmentHere), marker.level)
        }
        sessionObjects.clear()
        objectProgress.clear()
        anchors.values.forEach { it.detach() }
        anchors.clear()
    }

    /**
     * Places the selected object where the user tapped: on the surface ARCore measured there, or, where it measured
     * none, on the detected floor along the tapped direction. With neither, nothing is placed: a spot guessed at a
     * fixed distance from the camera would be wherever the phone happened to be, not where the user meant.
     */
    fun handleTap(frame: com.google.ar.core.Frame, session: com.google.ar.core.Session, x: Float, y: Float) {
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            showToast("Tracking is limited right now. Hold still for a moment, then tap again.", 2_500)
            return
        }
        if (!onMap()) {
            showToast(NOT_ON_MAP_YET, 4_500)
            return
        }
        val cameraPosition = Vec3(camera[0], camera[1], camera[2])
        val hit = markerTracker.surfaceAt(frame, session, x, y, MAX_OBJECT_DISTANCE_M)
        val onSurface = hit != null
        val wanted = hit?.position ?: run {
            val (origin, direction) = markerTracker.rayThrough(frame, x, y, view.width.toFloat(), view.height.toFloat())
            val toFloor = if (floorFound && direction.y < -0.05f) (floorSessionY() - origin.y) / direction.y else -1f
            if (toFloor !in 0.5f..MAX_OBJECT_DISTANCE_M) {
                showToast("Couldn't find a surface there. Move the phone slowly so ARCore can see it, then tap again.", 3_000)
                android.util.Log.i(ACCURACY_TAG, "Object tap refused: no surface and no floor along the ray")
                return
            }
            Vec3(origin.x + direction.x * toFloor, floorSessionY(), origin.z + direction.z * toFloor)
        }
        val spot = spreadObjectPosition(wanted, sessionObjects.map { it.position }, MIN_OBJECT_SEPARATION_M)
        val id = "object-" + java.util.UUID.randomUUID()
        anchors[id] = if (hit != null && spot == hit.position) hit.createAnchor()
        else session.createAnchor(Pose(floatArrayOf(spot.x, spot.y, spot.z), floatArrayOf(0f, 0f, 0f, 1f)))
        val yaw = Math.toDegrees(kotlin.math.atan2((cameraPosition.x - spot.x).toDouble(), (cameraPosition.z - spot.z).toDouble())).toFloat()
        val distance = spot.distanceTo(cameraPosition)
        android.util.Log.i(
            ACCURACY_TAG,
            "Object placed: ${selectedKind.label} %s at session (%.2f, %.2f, %.2f), %.2f m away, anchor=%s".format(
                if (hit == null) "on floor (no surface)" else if (hit.isPlane) "on plane" else "on depth point",
                spot.x, spot.y, spot.z, distance, anchors[id]?.trackingState,
            ),
        )
        objectProgress[id] = recorder.progress
        sessionObjects += PlacedObject(
            id = id, kind = selectedKind, level = "", x = spot.x, y = spot.y, z = spot.z, yawDegrees = yaw,
            text = if (selectedKind == PlacedObjectKind.MESSAGE) "Hello!" else "",
            createdAtMillis = System.currentTimeMillis(), scale = objectScaleFor(distance),
        )
        namingObjectId = id
        nameDraft = if (selectedKind == PlacedObjectKind.MESSAGE) "Hello!" else ""
        showToast(
            if (onSurface) "${selectedKind.label} added, %.1f m away".format(distance)
            else "${selectedKind.label} added %.1f m away (on floor)".format(distance),
            if (onSurface) 1_800 else 2_500,
        )
    }

    /**
     * Where the centered crop of [cropW] × [cropH] camera-image pixels appears on screen: its middle, then its four
     * corners (x, y pairs). The screen shows a cropped, rotated camera image, so ARCore's own mapping is used.
     */
    fun a4CropOnScreen(frame: com.google.ar.core.Frame, dims: IntArray, cropW: Float, cropH: Float): FloatArray {
        val cx = dims[0] / 2f
        val cy = dims[1] / 2f
        val hw = cropW / 2f
        val hh = cropH / 2f
        val imagePoints = floatArrayOf(cx, cy, cx - hw, cy - hh, cx + hw, cy - hh, cx - hw, cy + hh, cx + hw, cy + hh)
        val viewPoints = FloatArray(imagePoints.size)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, imagePoints, Coordinates2d.VIEW, viewPoints)
        return viewPoints
    }

    /**
     * Photographs the reference point in the middle of the camera view, as an upright A4-shaped photo (the frame
     * drawn on screen), and measures it, so the saved marker matches what ARCore recognizes later (see
     * [ReferenceGeometry]):
     * - its center is the measured surface point on the camera image's center ray (found through ARCore's own mapping
     *   from camera image to screen, since the screen shows a cropped camera image);
     * - its width comes from the depth along the camera's axis there, not a guess: without a measured surface no
     *   reference point is taken ([refFailure] says why);
     * - its facing comes from the photo's own left-to-right edge;
     * - its corners are measured too, to warn when the photo isn't of a flat surface.
     */
    fun grabReference(frame: com.google.ar.core.Frame, session: com.google.ar.core.Session): RefCapture? {
        refFailure = null
        val cameraPose = frame.camera.pose
        val cameraPosition = Vec3(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
        val forward = cameraPose.zAxis.let { Vec3(-it[0], -it[1], -it[2]) } // the camera looks along -z
        val intrinsics = frame.camera.imageIntrinsics
        val dims = intrinsics.imageDimensions
        val rotation = displayRotationDegrees(view)
        val rotated = rotation % 180 != 0
        val (cropW, cropH) = ReferenceGeometry.a4Crop(dims[0], dims[1], rotated)
        // The photo's width (left to right once upright) and the focal length along that direction.
        val photoWidthPx = if (rotated) cropH else cropW
        val focalAcross = if (rotated) intrinsics.focalLength[1] else intrinsics.focalLength[0]

        // The crop's middle and corners, from camera-image pixels to screen positions.
        val viewPoints = a4CropOnScreen(frame, dims, cropW, cropH)

        val hit = markerTracker.surfaceAt(frame, session, viewPoints[0], viewPoints[1], MAX_OBJECT_DISTANCE_M, preferPlane = false)
        if (hit == null) {
            refFailure = "Couldn't measure how far away that is. Aim at a detailed surface 0.5–4 m away, move the phone " +
                "a little so ARCore can see its depth, then try again."
            return null
        }
        val hitPos = hit.position
        fun depthOf(p: Vec3) = (p.x - cameraPosition.x) * forward.x + (p.y - cameraPosition.y) * forward.y + (p.z - cameraPosition.z) * forward.z
        val centerDepth = depthOf(hitPos)
        if (centerDepth < 0.3f) {
            refFailure = "Too close. Step back to at least half a meter and try again."
            return null
        }
        val cornerDepths = (1..4).map { i ->
            markerTracker.nearestSurfaceAt(frame, viewPoints[2 * i], viewPoints[2 * i + 1])?.let(::depthOf)
        }
        val problem = ReferenceGeometry.problem(centerDepth, cornerDepths)

        val bitmap = try {
            frame.acquireCameraImage().use { image ->
                val full = CameraImage.toBitmap(image)
                CameraImage.rotate(CameraImage.centerRect(full, cropW, cropH), rotation)
            }
        } catch (e: Exception) {
            return null
        }
        val distance = hitPos.distanceTo(cameraPosition)
        val physicalWidth = ReferenceGeometry.physicalWidth(centerDepth, photoWidthPx, focalAcross).coerceIn(0.05f, 3f)

        val accepted = try {
            AugmentedImageDatabase(session).addImage("probe", bitmap, physicalWidth)
            true
        } catch (e: Exception) {
            false
        }
        val detail = CameraImage.detailScore(bitmap)

        val venueFromSession = filter.alignment.inverse()
        val surfaceVenue = venueFromSession.transformPoint(hitPos)
        // The photo is saved upright as the screen shows it, so its left-to-right edge is the screen's right direction.
        val right = frame.camera.displayOrientedPose.xAxis.let { Vec3(it[0], it[1], it[2]) }
        val rightVenue = venueFromSession.transformPoint(hitPos + right) - surfaceVenue
        val facing = ReferenceGeometry.facingDegrees(rightVenue)
        val level = user.level.ifEmpty { startMarker?.level ?: defaultLevel }
        // Two reference points on the same spot only compete with each other when the camera sees them.
        val duplicate = allRefPins.minByOrNull { hypot(it.x - surfaceVenue.x, it.z - surfaceVenue.z) }
            ?.takeIf { hypot(it.x - surfaceVenue.x, it.z - surfaceVenue.z) < DUPLICATE_REFERENCE_M }
            ?.let { "There is already a reference point here (“${it.name}”, %.0f cm away).".format(hypot(it.x - surfaceVenue.x, it.z - surfaceVenue.z) * 100f) }
        val problems = listOfNotNull(problem, duplicate).joinToString("\n").ifEmpty { null }
        android.util.Log.i(
            ACCURACY_TAG,
            "Reference capture: depth=%.2f m width=%.2f m facing=%.1f° venue=(%.2f, %.2f, %.2f) corners=%s detail=%d accepted=%b sign±%.1f cm/%.1f° problem=%s".format(
                centerDepth, physicalWidth, facing, surfaceVenue.x, surfaceVenue.y, surfaceVenue.z,
                cornerDepths.map { it?.let { d -> "%.2f".format(d) } ?: "-" }, detail, accepted,
                filter.spreadCm, filter.spreadDegrees, problems,
            ),
        )
        return RefCapture(bitmap, physicalWidth, level, surfaceVenue.x, surfaceVenue.y, surfaceVenue.z, facing, detail, accepted, distance, problems)
    }

    fun captureReferencePoint(frame: com.google.ar.core.Frame, session: com.google.ar.core.Session) {
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            showToast("Tracking isn't stable yet. Hold still for a moment, then tap ◎ again.", 3_000)
            return
        }
        if (!onMap()) {
            showToast(NOT_ON_MAP_YET, 4_500)
            return
        }
        val ref = grabReference(frame, session)
        if (ref == null) {
            showToast(refFailure ?: "Camera image not ready yet — hold still and try again.", 3_500)
            return
        }
        pendingRef = ref
    }

    fun toCaptured(ref: RefCapture, name: String) = CapturedReferencePoint(
        name = name, image = ref.image, physicalWidth = ref.physicalWidth, level = ref.level,
        x = ref.x, y = ref.y, z = ref.z, facing = ref.facing, mounting = Venue.MarkerMounting.WALL,
    )

    fun maybeAutoCapture(frame: com.google.ar.core.Frame, session: com.google.ar.core.Session) {
        if (!autoPoints) return
        if (!onMap()) return
        if (recorder.progress - lastAutoProgress[0] < AUTO_INTERVAL_M) return
        lastAutoProgress[0] = recorder.progress
        val ref = grabReference(frame, session) ?: return
        // Automatic points are only kept when they look reliable: trackable, flat and seen head-on.
        if (!ref.accepted || ref.problem != null) return
        val last = lastAutoPos
        if (last != null && hypot(ref.x - last.x, ref.z - last.z) < AUTO_MIN_SEPARATION_M) return
        autoCount[0] = autoCount[0] + 1
        val autoName = "Auto point ${autoCount[0]}"
        onSaveReferencePoint(toCaptured(ref, autoName))
        sessionRefPins.add(RefPin(ref.x, ref.y, ref.z, autoName))
        currentAtName = autoName
        lastAutoPos = Vec3(ref.x, ref.y, ref.z)
        greenFlash = "Reference point added: $autoName"
    }

    fun startWithoutSign() {
        if (!onMap()) {
            // Starting a new origin here would put everything recorded now out of line with the saved map.
            showToast(NOT_ON_MAP_YET, 4_500)
            return
        }
        val level = defaultLevel.ifEmpty { return }
        val camSession = Vec3(camera[0], camera[1], camera[2])
        val inVenue = filter.alignment.inverse().transformPoint(camSession)
        startMarker = Venue.Marker(
            id = "origin-bootstrap", name = LocalizedText("Start point"), image = "",
            physicalWidth = 0.2f, level = level, x = inVenue.x, y = inVenue.y, z = inVenue.z, facing = 0f,
            mounting = Venue.MarkerMounting.WALL,
        )
        currentAtName = "Start point"
        bootstrapped = true
        showToast("Started here — walk, add points or objects, and save!", 2_500)
    }

    /**
     * Starts the walk from the saved reference point [pin]. The point's position alone can't place the camera in the
     * map: it says nothing about where the phone stands or which way it faces (the old shortcut assumed the phone was
     * at the point, facing the map's own direction, which turned and shifted everything recorded after it). So the
     * point's photo must be recognized first; the walk then starts from there.
     */
    fun selectStartReferencePoint(pin: RefPin) {
        val level = defaultLevel.ifEmpty { return }
        currentAtName = pin.name
        recorder.clear()
        committedPath.clear()
        if (filter.isLockedIn) {
            // Already on the map: start the walk from the point's saved position.
            startMarker = Venue.Marker(
                id = pin.id.ifEmpty { "ref-${pin.name}" }, name = LocalizedText(pin.name), image = "",
                physicalWidth = 0.2f, level = level, x = pin.x, y = pin.y, z = pin.z, facing = 0f,
                mounting = Venue.MarkerMounting.WALL,
            )
            showToast("Start set to 📍 ${pin.name} — walk to your target & tap Connect!", 3_000)
            return
        }
        val recognizable = markerTracker.usableMarkers.any { it.id == pin.id || it.id == "ref-${pin.id}" }
        // Recording starts when the camera recognizes this point (or any other saved sign or point).
        startMarker = null
        visits.clear()
        showToast(
            if (recognizable) "Point the camera at 📍 ${pin.name} and hold steady — the walk starts once it is recognized."
            else "📍 ${pin.name} has no photo to recognize. Scan a sign or another saved reference point to start.",
            4_500,
        )
    }

    fun guideToSession(target: Vec3, name: String) {
        val cam = Vec3(camera[0], camera[1], camera[2])
        val dx = target.x - cam.x
        val dz = target.z - cam.z
        val dist = hypot(dx, dz)
        val fwd = user.forward
        val dot = fwd.x * dx + fwd.z * dz
        val cross = fwd.x * dz - fwd.z * dx
        val dir = when {
            dist < 0.8f -> "you're here"
            dot <= 0f -> "behind you — turn around"
            kotlin.math.abs(cross) < dist * 0.35f -> "straight ahead"
            cross > 0f -> "ahead, on your left"
            else -> "ahead, on your right"
        }
        showToast("$name · %.1f m · $dir".format(dist), 4_000)
    }

    /**
     * Connects the walked path from the current start reference point ([currentAtName] / [startMarker]) to [target]
     * (another reference point, placed object, or destination), saving a continuous linked [WalkedPoint] run so
     * [WalkedPathRouter] routes between them in both directions.
     */
    fun connectWalkedPathTo(target: AdminNavTarget) {
        val spot = averageOf(recent) ?: Vec3(camera[0], camera[1], camera[2])
        recorder.recordAnchor(spot)
        refreshFromAnchors()
        val track = buildTrack()
        val venueFromSession = track.venueFromSessionAt(recorder.progress)
        val here = venueFromSession.transformPoint(spot)
        val savedLevel = target.level.ifEmpty { user.level.ifEmpty { defaultLevel } }
        val floorY = venueFromSession.transformPoint(Vec3(spot.x, floorSessionY(), spot.z)).y
        val fromMarker = startMarker
        val fromName = currentAtName.ifBlank { fromMarker?.name?.resolved() ?: "Start point" }
        val fromPos = if (fromMarker != null) Vec3(fromMarker.x, floorY, fromMarker.z) else Vec3(here.x, floorY, here.z)

        // Convert recorded session points and combine with any already-committed segments
        val rawConverted = recorder.points.toWalkedPoints(venue, track, savedLevel, floorSessionY())
        val rawWalk = (committedPath + rawConverted).map { Vec3(it.x, floorY, it.z) }

        // If the target is a reference point that was added from the gallery at (0,0) and we walked away from origin,
        // update its 3D coordinates to where the admin walked!
        val isUnpositionedRef = (target.id.startsWith("ref-") || target.id.startsWith("refpin-")) &&
            hypot(target.x, target.z) < 0.05f && hypot(here.x - fromPos.x, here.z - fromPos.z) > 0.3f
        val goalPos = if (isUnpositionedRef) {
            val rawRefId = target.id.removePrefix("ref-").let { if (it.startsWith("ref-")) it else "ref-$it" }
            onUpdateReferencePointCoords(rawRefId, here.x, here.y, here.z)
            val idx = sessionRefPins.indexOfFirst { it.name == target.label || it.id == rawRefId }
            if (idx >= 0) sessionRefPins[idx] = sessionRefPins[idx].copy(x = here.x, y = here.y, z = here.z)
            else sessionRefPins.add(RefPin(here.x, here.y, here.z, target.label, rawRefId))
            Vec3(here.x, floorY, here.z)
        } else {
            Vec3(target.x, floorY, target.z)
        }

        // Build a single continuous run: fromPos -> rawWalk -> here -> goalPos with spacing <= 0.5m
        val runId = SessionPathRecorder.newRunId()
        val waypoints = mutableListOf<Vec3>()
        waypoints += fromPos
        waypoints += rawWalk
        waypoints += Vec3(here.x, floorY, here.z)
        if (hypot(goalPos.x - here.x, goalPos.z - here.z) > 0.1f) {
            waypoints += goalPos
        }

        val densePoints = mutableListOf<WalkedPoint>()
        var seq = 0
        for (i in 0 until waypoints.size - 1) {
            val a = waypoints[i]
            val b = waypoints[i + 1]
            val d = hypot(b.x - a.x, b.z - a.z)
            if (d > 0.05f) {
                val seg = straightWalk(a, b, runId, savedLevel, floorY, spacing = 0.4f)
                for (pt in seg) {
                    densePoints += WalkedPoint(runId, seq++, savedLevel, pt.x, floorY, pt.z)
                }
            }
        }
        densePoints += WalkedPoint(runId, seq, savedLevel, goalPos.x, floorY, goalPos.z)

        // Commit objects placed along the walk
        committedObjects += sessionObjects.map { obj ->
            val alignmentHere = track.alignmentAt(objectProgress[obj.id] ?: 0f)
            obj.toVenue(venue, alignmentHere.inverse(), yawDegrees(alignmentHere), savedLevel)
        }
        val objectsToSave = committedObjects.toList()
        sessionObjects.clear()
        committedObjects.clear()
        objectProgress.clear()
        anchors.values.forEach { it.detach() }
        anchors.clear()
        recorder.clear()
        committedPath.clear()

        val connName = "$fromName → ${target.label}"
        val spreadCm = visits.firstOrNull()?.filter?.spreadCm
        val drift = driftCm[0].takeIf { it > 0f }
        onSave(
            CapturedDestination(
                name = connName,
                level = savedLevel,
                x = goalPos.x,
                z = goalPos.z,
                path = densePoints,
                objects = objectsToSave,
                floorY = floorY,
                startMarkerId = fromMarker?.id.orEmpty(),
                alignmentSpreadCm = spreadCm,
                driftCorrectedCm = drift,
            ),
        )
        sessionConnectedWalkedPoints.addAll(densePoints)
        sessionSavedDestinations.add(
            Venue.POI(
                id = "custom-session-${sessionSavedDestinations.size}",
                name = LocalizedText(connName),
                category = CustomDestination.CATEGORY,
                level = savedLevel,
                x = goalPos.x,
                z = goalPos.z,
                node = "",
                elevation = floorY,
            ),
        )
        // Advance start point to the newly connected target so the admin can chain to the next reference point!
        startMarker = Venue.Marker(
            id = target.id,
            name = LocalizedText(target.label),
            image = "",
            physicalWidth = 0.2f,
            level = savedLevel,
            x = goalPos.x, y = target.y, z = goalPos.z,
            facing = 0f,
            mounting = Venue.MarkerMounting.WALL,
        )
        currentAtName = target.label
        greenFlash = "Connected $connName (${densePoints.size} pts)"
    }

    /** Plans a live 3D route in the camera to the selected dropdown target. */
    fun planToTarget(target: AdminNavTarget?) {
        selectedNavTarget = target
        if (target == null) {
            activeRoute = null
            activeRouteDestPoi = null
            return
        }
        val level = target.level.ifEmpty { user.level.ifEmpty { defaultLevel } }
        val floor = target.poi?.elevation ?: floorSessionY()
        val start = Vec3(user.venueX, floor, user.venueZ)
        val goal = Vec3(target.x, floor, target.z)
        val destPoi = (target.poi ?: Venue.POI(
            id = target.id,
            name = LocalizedText(target.label),
            category = CustomDestination.CATEGORY,
            level = level,
            x = target.x,
            z = target.z,
            node = "",
            elevation = floor,
        )).copy(level = level, elevation = floor)
        activeRouteDestPoi = destPoi

        // Gather all walked points (saved + current session + connected runs)
        val track = buildTrack()
        val currentWalked = recorder.points.toWalkedPoints(venue, track, level, floorSessionY())
        val combinedWalked = existingWalkedPoints + sessionConnectedWalkedPoints + committedPath + currentWalked
        val allWalked = combinedWalked.filter { it.level == level }.ifEmpty { combinedWalked }

        val route = runCatching {
            if (!destPoi.followsWalkedPaths && destPoi.node.isNotEmpty()) {
                planner.route(start, level, toPOI = destPoi.id, profile = RouteProfile.FASTEST)
            } else {
                val res = WalkedPathRouter(startJoinDistance = 12f, destinationJoinDistance = 12f).route(start, goal, allWalked)
                when (res) {
                    is WalkedRouteResult.Success -> planner.routeAlong(res.points, level, RouteProfile.FASTEST)
                    is WalkedRouteResult.Failure -> {
                        val matchingRun = allWalked.filter { it.destinationId == destPoi.id }.sortedBy { it.sequence }
                            .ifEmpty {
                                allWalked.groupBy { it.run }.values
                                    .map { run -> run.sortedBy { it.sequence } }
                                    .minByOrNull { run -> run.last().position.distanceTo(goal) }
                                    ?.takeIf { run -> run.last().position.distanceTo(goal) <= 5f }
                                    .orEmpty()
                            }
                        if (matchingRun.size >= 2) {
                            val closestIdx = matchingRun.indices.minByOrNull { matchingRun[it].position.distanceTo(start) } ?: 0
                            val lead = straightWalk(start, matchingRun[closestIdx].position, "lead", level, floor).map { it.position }
                            val tail = matchingRun.drop(closestIdx).map { Vec3(it.x, floor, it.z) } + goal
                            planner.routeAlong((lead + tail).distinct(), level, RouteProfile.FASTEST)
                        } else {
                            val straight = straightWalk(start, goal, "direct", level, floor).map { it.position } + goal
                            planner.routeAlong(straight, level, RouteProfile.FASTEST)
                        }
                    }
                }
            }
        }.getOrElse {
            val straight = listOf(start, goal)
            runCatching { planner.routeAlong(straight, level, RouteProfile.FASTEST) }.getOrNull()
        }
        activeRoute = route
        guideToSession(alignment.transformPoint(Vec3(target.x, target.y, target.z)), target.label)
    }

    // Build all targets available in the navigation dropdown, sorted by distance from current user position.
    val navTargets = remember(
        venue.pois,
        sessionSavedDestinations.size,
        allRefPins.size,
        allVenueObjects.size,
        sessionObjects.size,
        userVenuePos,
    ) {
        val list = mutableListOf<AdminNavTarget>()
        val allPois = venue.pois + sessionSavedDestinations
        for (poi in allPois) {
            val glyph = when (poi.category) {
                REFERENCE_CATEGORY -> "📍"
                CustomDestination.CATEGORY -> "⭐"
                else -> "🏁"
            }
            val sub = when (poi.category) {
                REFERENCE_CATEGORY -> "Reference point"
                CustomDestination.CATEGORY -> "Saved destination"
                else -> "Venue destination"
            }
            list += AdminNavTarget(
                id = poi.id,
                label = poi.name.resolved(),
                glyph = glyph,
                subtitle = sub,
                level = poi.level,
                x = poi.x,
                y = poi.elevation ?: 0f,
                z = poi.z,
                poi = poi,
            )
        }
        for (pin in allRefPins) {
            if (list.none { it.label.equals(pin.name, ignoreCase = true) && hypot(it.x - pin.x, it.z - pin.z) < 2f }) {
                list += AdminNavTarget(
                    id = "refpin-${pin.name}",
                    label = pin.name,
                    glyph = "📍",
                    subtitle = "Reference point",
                    level = defaultLevel,
                    x = pin.x,
                    y = pin.y,
                    z = pin.z,
                )
            }
        }
        val venueFromSession = filter.alignment.inverse()
        for (obj in allVenueObjects.filter { !it.isReference }) {
            val label = obj.text.ifBlank { obj.kind.label }
            list += AdminNavTarget(
                id = obj.id,
                label = label,
                glyph = obj.kind.glyph,
                subtitle = "Placed ${obj.kind.label}",
                level = obj.level.ifEmpty { defaultLevel },
                x = obj.x,
                y = obj.y,
                z = obj.z,
            )
        }
        for (obj in sessionObjects.filter { !it.isReference }) {
            val v = venueFromSession.transformPoint(obj.position)
            val label = obj.text.ifBlank { obj.kind.label }
            list += AdminNavTarget(
                id = obj.id,
                label = label,
                glyph = obj.kind.glyph,
                subtitle = "Placed ${obj.kind.label}",
                level = defaultLevel,
                x = v.x,
                y = v.y,
                z = v.z,
            )
        }
        list.sortedBy { hypot(it.x - userVenuePos.x, it.z - userVenuePos.z) }
    }

    LaunchedEffect(initialFromRefId, initialConnectTargetId) {
        if (!initialFromRefId.isNullOrEmpty()) {
            allRefPins.firstOrNull { it.id == initialFromRefId || "ref-${it.id}" == initialFromRefId || it.name == initialFromRefId }
                ?.let { selectStartReferencePoint(it) }
        }
        if (!initialConnectTargetId.isNullOrEmpty()) {
            navTargets.firstOrNull {
                it.id == initialConnectTargetId ||
                    it.id.removePrefix("ref-") == initialConnectTargetId.removePrefix("ref-") ||
                    it.label == initialConnectTargetId
            }?.let { planToTarget(it) }
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            sessionCameraConfig = ArMarkerTracker::preferredCameraConfig,
            sessionConfiguration = { session, config ->
                markerTracker.configure(session, config, aiming = true)
                val badRefs = markerTracker.unusableMarkers.filter { it.first.id.startsWith("ref-") }
                if (badRefs.isNotEmpty()) {
                    unusableRefsWarning = badRefs.joinToString(", ") { "“${it.first.name.resolved()}”" }
                } else {
                    unusableRefsWarning = null
                }
                markerTracker.unusableMarkers.firstOrNull()?.let { (marker, reason) ->
                    showToast("Ref/Sign “${marker.name.resolved()}” wasn't captured clearly ($reason) — retake with ◎", 5_000)
                }
            },
            onSessionUpdated = { session, frame ->
                val message = ArMarkerTracker.trackingMessage(frame)
                if (message != trackingMessage) trackingMessage = message
                val now = frame.timestamp / 1_000_000

                // Always keep camera coordinates updated even if tracking is still warming up
                val camMatrix = FloatArray(16)
                frame.camera.pose.toMatrix(camMatrix, 0)
                camera[0] = camMatrix[12]; camera[1] = camMatrix[13]; camera[2] = camMatrix[14]

                // Handle manual reference capture immediately on next frame without waiting for 20 steady frames!
                if (captureReq[0]) {
                    captureReq[0] = false
                    captureReferencePoint(frame, session)
                }

                // Where the A4 reference photo frame is on screen (it only changes with the camera configuration).
                if (frameCounter[0] % 15 == 0) {
                    runCatching {
                        val dims = frame.camera.imageIntrinsics.imageDimensions
                        val (w, h) = ReferenceGeometry.a4Crop(dims[0], dims[1], displayRotationDegrees(view) % 180 != 0)
                        val p = a4CropOnScreen(frame, dims, w, h)
                        val xs = floatArrayOf(p[2], p[4], p[6], p[8])
                        val ys = floatArrayOf(p[3], p[5], p[7], p[9])
                        val rect = floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
                        val old = a4Frame
                        if (old == null || rect.indices.any { kotlin.math.abs(old[it] - rect[it]) > 2f }) a4Frame = rect
                        cameraViewOnScreen(frame)?.let { if (!it.contentEquals(cameraFrame)) cameraFrame = it }
                    }
                }

                if (message != null) {
                    val since = lostSince ?: now.also { lostSince = it }
                    if (now - since > TRACKING_LOST_RESET_MS && filter.isAligned) {
                        commit()
                        mapFrameLost[0] = true
                        android.util.Log.w(ACCURACY_TAG, "Tracking lost for over ${TRACKING_LOST_RESET_MS} ms: recorded data committed, map must be found again")
                        filter.reset()
                        visits.clear()
                        lockedIn = false
                        lockProgress = 0
                        user.valid = false
                        recent.clear()
                        floorSamples.clear()
                    }
                } else {
                    lostSince = null
                }

                val interval = if (lockedIn) ArMarkerTracker.REDETECT_INTERVAL_MS else LOCK_IN_INTERVAL_MS
                for (sighting in markerTracker.sightings(frame, now, interval)) {
                    val start = startMarker
                    val isCapturedRef = sighting.marker.id.startsWith("ref-")
                    val isBootstrap = start == null || start.id == "origin-bootstrap"
                    if (!isBootstrap && sighting.marker.id != start!!.id && !isCapturedRef) {
                        if (now - otherSignToastMs[0] > 6_000) {
                            otherSignToastMs[0] = now
                            showToast("Matched “${sighting.marker.name.resolved()}”", 2_500)
                        }
                    }
                    val candidate = MarkerAlignment.alignment(sighting.markerInSession, MarkerAlignment.transform(sighting.marker))
                    // A captured reference point (a photo of the scene) is averaged like a sign, weighted by its real
                    // distance and angle; it locks in after two readings and without the side-to-side movement a sign
                    // needs (moving in front of a 3D scene changes how it looks and breaks the photo match).
                    val viewpoint = if (isCapturedRef) null else sighting.cameraInSession
                    if (filter.update(candidate, sighting.distanceMeters, sighting.viewCosine, viewpoint, countAsClose = isCapturedRef)) {
                        alignment = filter.alignment
                    }
                    android.util.Log.d(
                        ACCURACY_TAG,
                        "Sighting ${sighting.marker.id}: %.2f m, cos %.2f -> spread ±%.1f cm/%.1f°, %d outliers, locked=%b".format(
                            sighting.distanceMeters, sighting.viewCosine, filter.spreadCm, filter.spreadDegrees,
                            filter.rejectedSightings, filter.isLockedIn,
                        ),
                    )
                    if (isBootstrap || isCapturedRef) {
                        startMarker = sighting.marker
                    }
                    currentAtName = sighting.marker.name.resolved()

                    val visit = visits.lastOrNull()?.takeIf { now - it.lastMs < VISIT_GAP_MS }
                        ?: Visit(recorder.progress, now).also { visits += it }
                    val closeBefore = visit.filter.closeSightings
                    visit.filter.update(candidate, sighting.distanceMeters, sighting.viewCosine, viewpoint, countAsClose = isCapturedRef)
                    visit.progress = recorder.progress
                    visit.lastMs = now
                    if (visits.size >= 2 && closeBefore < 2 && visit.filter.closeSightings >= 2) {
                        val drift = AlignmentTrack.driftMeters(Vec3(camera[0], camera[1], camera[2]), visits.first().filter.alignment, visit.filter.alignment)
                        driftCm[0] = maxOf(driftCm[0], drift * 100f)
                        showToast("Sign re-scanned: ${"%.0f".format(drift * 100f)} cm of drift corrected", 3_500)
                    }
                }
                val spread = filter.spreadCm.toInt()
                val agreement = if (filter.isLockedIn) "sign ±$spread cm" else ""
                if (agreement != signAgreement) signAgreement = agreement
                lockProgress = filter.closeSightings.coerceAtMost(AlignmentFilter.LOCK_IN_SIGHTINGS)
                val nowLocked = filter.isLockedIn
                if (nowLocked && !lockedIn && hadSavedMap) greenFlash = "Map found — you can add reference points now"
                lockedIn = nowLocked
                stable.update(session, filter.alignment, onMap(), frame.timestamp)

                if (message == null && frameCounter[0] % 2 == 0) {
                    val here = Vec3(camera[0], camera[1], camera[2])
                    (markerTracker.floorHeightBelow(session, here) ?: markerTracker.floorHeightByRay(frame, here))
                        ?.let { floorSamples.addLast(it) }
                    while (floorSamples.size > 7) floorSamples.removeFirst()
                    val found = floorSamples.size >= 2
                    if (found != floorFound) floorFound = found
                }

                val marker = startMarker
                if (marker != null && (lockedIn || bootstrapped) && message == null) {
                    val cameraSession = Vec3(camera[0], camera[1], camera[2])
                    val inVenue = filter.alignment.inverse().transformPoint(cameraSession)
                    val level = venue.level(forDeviceHeight = inVenue.y)?.id ?: marker.level
                    user.valid = true
                    user.session = cameraSession
                    user.forward = -Mat4(camMatrix).column(2)
                    user.level = level; user.venueX = inVenue.x; user.venueZ = inVenue.z
                    if (frameCounter[0] % 15 == 0) {
                        userVenuePos = inVenue
                        // Update nearest reference point if within 2.5m
                        allRefPins.minByOrNull { hypot(it.x - inVenue.x, it.z - inVenue.z) }?.let { nearest ->
                            if (hypot(nearest.x - inVenue.x, nearest.z - inVenue.z) <= 2.5f) {
                                currentAtName = nearest.name
                            }
                        }
                    }
                    venue.level(level)?.name?.resolved()?.let { if (it != levelName) levelName = it }

                    recorder.record(cameraSession)
                    recent.addLast(cameraSession)
                    while (recent.size > STEADY_FRAMES) recent.removeFirst()
                    val mean = averageOf(recent)
                    val isSteady = mean != null && recent.size >= STEADY_FRAMES &&
                        recent.all { hypot(it.x - mean.x, it.z - mean.z) <= STEADY_RADIUS_M }
                    if (isSteady != steady) steady = isSteady
                    refreshFromAnchors()
                    val length = committedLength() + recorder.lengthMeters
                    canSave = length >= MIN_PATH_LENGTH_M || recorder.points.isNotEmpty()
                    recordedSummary = "Recording · ${committedPath.size + recorder.points.size} pts · %.1f m".format(length)

                    if (pendingTap[2] == 1f) {
                        pendingTap[2] = 0f
                        handleTap(frame, session, pendingTap[0], pendingTap[1])
                    }
                    maybeAutoCapture(frame, session)
                } else {
                    if (pendingTap[2] == 1f) {
                        pendingTap[2] = 0f
                        handleTap(frame, session, pendingTap[0], pendingTap[1])
                    }
                }
                frameCounter[0]++
            },
            onSessionFailed = { error -> showToast("AR isn't available: ${error.message}", 8_000) },
        ) {
            val cameraPosition = { Position(camera[0], camera[1], camera[2]) }
            // Render live 3D route if the admin picked a target from the dropdown
            val routeDest = activeRouteDestPoi
            if (activeRoute != null && routeDest != null) {
                RouteScene(
                    route = activeRoute,
                    level = activeRoute?.levels?.firstOrNull() ?: routeDest.level.ifEmpty { defaultLevel },
                    venue = venue,
                    destination = routeDest,
                    alignment = stable.display,
                    cameraPosition = cameraPosition,
                )
            }
            // Saved objects and reference points are drawn in the map's frame, so only once this session knows where the
            // map is (before that they would appear in arbitrary places). Session objects live in the session frame.
            if (stable.ready) {
                ObjectsScene(allVenueObjects, stable.display, cameraPosition)
                // Render all reference points (saved + captured this session) with BLUE pins and BLUE text labels
                ReferencePointsScene(allRefPins, stable.display, cameraPosition)
                // The registered Bluetooth beacons on this floor, standing on the floor at their spots.
                val level = startMarker?.level ?: defaultLevel
                BeaconsScene(
                    beacons = beacons.filter { it.floorId == level },
                    floorY = stable.display.inverse().transformPoint(Vec3(0f, floorSessionY(), 0f)).y,
                    alignment = stable.display,
                    cameraPosition = cameraPosition,
                )
            }
            ObjectsScene(sessionObjects.toList(), Mat4.IDENTITY, cameraPosition)
        }

        val needsMap = hadSavedMap && !lockedIn
        val chosenGuide = guide?.let { signMarkers.getOrNull(it) }?.takeIf { it.first.id.startsWith("ref-") }

        // The A4 frame: exactly what ◎ photographs as a reference point.
        a4Frame?.takeIf { !(needsMap && chosenGuide != null) }?.let { r ->
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.8f),
                    topLeft = Offset(r[0], r[1]),
                    size = Size(r[2] - r[0], r[3] - r[1]),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
                )
            }
            Text(
                "A4 reference photo",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                modifier = Modifier
                    .offset { IntOffset(r[0].toInt() + 8.dp.roundToPx(), r[1].toInt() + 6.dp.roundToPx()) }
                    .background(Color(0x66000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        // Taps on the camera view place the selected object; the controls below sit on top and take their own taps.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { offset ->
                    pendingTap[0] = offset.x; pendingTap[1] = offset.y; pendingTap[2] = 1f
                }
            },
        )

        // Until the saved map is found: the chosen saved reference photo, faded where it was framed, to line up with.
        // Above the tap area so its size buttons can be pressed.
        if (needsMap && chosenGuide != null) {
            ReferenceGuideOverlay(chosenGuide.second, a4Frame, cameraFrame, sizeUnknown = chosenGuide.first.physicalWidth <= 0f)
        }

        // Top HUD: the recording status banner
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // This place already has a saved map: it must be recognized before anything new can be added to it.
            if (hadSavedMap && !lockedIn) {
                AlignmentPromptView(
                    trackingMessage ?: "Find the saved map first: point at a sign or a saved reference point, 1–2.5 m " +
                        "away, until it is recognized. Then add reference points, objects and paths.",
                    signs,
                    selected = guide,
                    onSelect = { index -> guide = if (guide == index) null else index },
                )
            }
            // Compact recording status banner
            val objectCount = allVenueObjects.count { !it.isReference } + sessionObjects.count { !it.isReference }
            GuidanceBannerView(
                GuidanceBanner(
                    instruction = trackingMessage ?: "Tap screen to place ${selectedKind.glyph} ${selectedKind.label} · ◎ to add ref point",
                    glyph = selectedKind.glyph,
                    detail = listOfNotNull(
                        levelName.ifEmpty { null },
                        recordedSummary.ifEmpty { "Ready to record" },
                        signAgreement.ifEmpty { null },
                        "${allRefPins.size} ref pt${if (allRefPins.size == 1) "" else "s"}",
                        "$objectCount obj${if (objectCount == 1) "" else "s"}",
                        if (floorFound) "floor ✓" else "finding floor…",
                    ).joinToString(" · "),
                ),
            )
        }

        greenFlash?.let { msg ->
            Box(
                Modifier.align(Alignment.Center)
                    .background(Color(0xFF0A84FF), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("✓  $msg", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        // Bottom panel: ALWAYS shows all Admin controls so nothing is ever hidden.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToastHost(toast)
            namingObjectId?.let { id ->
                val isMessage = sessionObjects.firstOrNull { it.id == id }?.kind == PlacedObjectKind.MESSAGE
                NameBox(
                    value = nameDraft,
                    hint = if (isMessage) "Your message" else "Name this object (optional)",
                    onChange = { text ->
                        nameDraft = text.take(30)
                        val index = sessionObjects.indexOfFirst { it.id == id }
                        if (index >= 0) sessionObjects[index] = sessionObjects[index].copy(text = nameDraft.trim())
                    },
                    onDone = { namingObjectId = null },
                )
            }
            ObjectBar(
                selected = selectedKind,
                onSelect = { selectedKind = it },
                onLongPress = { kind ->
                    showToast("${kind.glyph} ${kind.label}: Select this, then tap anywhere on the camera video to place a 3D ${kind.label} in the room.", 3_500)
                },
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton(
                    glyph = "✕",
                    description = "Exit camera: Closes the camera view and returns to the list (all captured points are kept).",
                    onClick = onCancel,
                    onLongPress = { showToast(it, 3_500) },
                )
                CircleButton(
                    glyph = "⊕",
                    description = "Start here: Sets your current standing spot as the starting origin point without scanning a sign.",
                    onClick = { startWithoutSign() },
                    onLongPress = { showToast(it, 3_500) },
                )
                CircleButton(
                    glyph = "◎",
                    description = "Add Reference Point: Captures a photo & 3D position of what the camera center is aimed at so the app can recognize this spot later.",
                    onClick = {
                        captureReq[0] = true
                        showToast("Capturing reference point…", 1_200)
                    },
                    onLongPress = { showToast(it, 3_500) },
                )
                CircleButton(
                    glyph = "☰",
                    description = "Reference Points List: View, navigate to, add (+), or delete any saved reference points.",
                    onClick = { showPointsList = true },
                    onLongPress = { showToast(it, 3_500) },
                )
                CircleButton(
                    glyph = "◳",
                    description = "Placed Objects List: View, navigate to, or delete any 3D objects placed in the room.",
                    onClick = { showObjectsList = true },
                    onLongPress = { showToast(it, 3_500) },
                )
                CircleButton(
                    glyph = "🗺",
                    description = "2D Floor Map: Opens a 2D map dialog displaying all reference points, destinations, 3D objects, and walked paths.",
                    onClick = { show2DMapDialog = true },
                    onLongPress = { showToast(it, 3_500) },
                )
                if (onOpenSigns != null) {
                    CircleButton(
                        glyph = "🏷",
                        description = "AR Signs Gallery: Add sign images from Camera/Gallery, share to print, or delete signs.",
                        onClick = onOpenSigns,
                        onLongPress = { showToast(it, 3_500) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (hasUserObjects) {
                    TextButton(onClick = {
                        val inSession = sessionObjects.indexOfLast { !it.isReference }
                        if (inSession >= 0) {
                            val removed = sessionObjects.removeAt(inSession)
                            anchors.remove(removed.id)?.detach()
                            if (namingObjectId == removed.id) namingObjectId = null
                        } else {
                            val committed = committedObjects.indexOfLast { !it.isReference }
                            if (committed >= 0) committedObjects.removeAt(committed)
                        }
                    }) { Text("Undo", color = Color.White) }
                }
            }
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val target = selectedNavTarget
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF34C759))
                        .combinedClickable(
                            onClick = {
                                if (target != null) {
                                    connectWalkedPathTo(target)
                                } else {
                                    showConnectPickerDialog = true
                                }
                            },
                            onLongClick = {
                                showToast(
                                    "Connect walked path: Once you start at a reference point/destination and walk to another destination, reference point, or object, tap this to link them with your walked path!",
                                    3_500,
                                )
                            },
                        )
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (target != null) {
                            "🔗 Connect: $currentAtName → ${target.label}"
                        } else {
                            "🔗 Connect $currentAtName → Destination / Ref / Object…"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                if (target != null) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF2C2C2E))
                            .clickable { showConnectPickerDialog = true }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Pick ▾", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF0A84FF))
                    .combinedClickable(
                        onClick = {
                            pendingDestination = averageOf(recent) ?: Vec3(camera[0], camera[1], camera[2])
                            pendingLevel = user.level.ifEmpty { defaultLevel }
                            pendingFloorSession = floorSessionY()
                            naming = true
                        },
                        onLongClick = {
                            showToast("Save destination here: Saves your current spot, walked path, and placed objects as a named destination.", 3_500)
                        },
                    )
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Save destination here", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }

    if (showConnectPickerDialog) {
        AlertDialog(
            onDismissRequest = { showConnectPickerDialog = false },
            title = { Text("🔗 Connect walked path from “$currentAtName”") },
            text = {
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Tap the Destination, Reference Point, or Object you reached to connect your walked path from “$currentAtName” to it:",
                        fontSize = 12.sp,
                        color = Color.Gray,
                    )
                    if (navTargets.isEmpty()) {
                        Text("No saved destinations, reference points, or objects yet.")
                    } else {
                        navTargets.forEach { t ->
                            val dist = hypot(t.x - userVenuePos.x, t.z - userVenuePos.z)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x1834C759))
                                    .clickable {
                                        showConnectPickerDialog = false
                                        selectedNavTarget = t
                                        connectWalkedPathTo(t)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${t.glyph} ${t.label}",
                                        fontWeight = FontWeight.Bold,
                                        color = if (t.glyph == "📍") Color(0xFF0A84FF) else Color.Unspecified,
                                    )
                                    Text("${t.subtitle} · %.1f m away".format(dist), fontSize = 12.sp, color = Color.Gray)
                                }
                                Text("🔗 Connect", color = Color(0xFF34C759), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConnectPickerDialog = false }) { Text("Close") }
            },
        )
    }

    if (show2DMapDialog) {
        val venueFromSession = filter.alignment.inverse()
        val liveWalkInVenue = recorder.points.map { venueFromSession.transformPoint(it.position) }
        val sessionObjsInVenue = sessionObjects.filter { !it.isReference }.map { obj ->
            val v = venueFromSession.transformPoint(obj.position)
            obj.copy(x = v.x, y = v.y, z = v.z, level = defaultLevel)
        }
        val allPoisForMap = (venue.pois + sessionSavedDestinations).filter { it.category != REFERENCE_CATEGORY }
        Live2DMapDialog(
            walkedPoints = existingWalkedPoints + sessionConnectedWalkedPoints + committedPath,
            liveWalkPoints = liveWalkInVenue,
            refPins = allRefPins,
            destinations = allPoisForMap,
            objects = allVenueObjects + sessionObjsInVenue,
            userPos = userVenuePos,
            activeRoutePoints = activeRoute?.points.orEmpty(),
            currentFromName = currentAtName,
            onDismiss = { show2DMapDialog = false },
            onSelectNavigate = { item ->
                navTargets.firstOrNull { it.id == item.id || it.label == item.label }?.let { planToTarget(it) }
            },
            onSelectConnect = { item ->
                val found = navTargets.firstOrNull { it.id == item.id || it.label == item.label }
                    ?: AdminNavTarget(
                        id = item.id,
                        label = item.label,
                        glyph = item.glyph,
                        subtitle = item.kindLabel,
                        level = defaultLevel,
                        x = item.x,
                        y = item.y,
                        z = item.z,
                    )
                selectedNavTarget = found
                connectWalkedPathTo(found)
            },
        )
    }

    if (showPointsList) {
        AlertDialog(
            onDismissRequest = { showPointsList = false },
            title = { Text("Reference points (${allRefPins.size})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (allRefPins.isEmpty()) {
                        Text("None yet. Tap ◎ to capture one.")
                    } else {
                        Text(
                            "Tap a point to select it as your target, tap 'Start' to start walking from it, or tap '🔗 Connect' to save the walked path to it.",
                            fontSize = 12.sp,
                            color = Color.Gray,
                        )
                        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            allRefPins.forEach { p ->
                                val dist = hypot(p.x - userVenuePos.x, p.z - userVenuePos.z)
                                val targetObj = AdminNavTarget(
                                    id = p.id.ifEmpty { "refpin-${p.name}" },
                                    label = p.name,
                                    glyph = "📍",
                                    subtitle = "Reference point",
                                    level = defaultLevel,
                                    x = p.x,
                                    y = p.y,
                                    z = p.z,
                                )
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "📍 ${p.name} (%.1f m)".format(dist),
                                        color = Color(0xFF0A84FF),
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f).clickable {
                                            showPointsList = false
                                            planToTarget(targetObj)
                                        },
                                    )
                                    TextButton(
                                        onClick = {
                                            showPointsList = false
                                            selectStartReferencePoint(p)
                                        },
                                    ) {
                                        Text("Start", color = Color(0xFF0A84FF))
                                    }
                                    TextButton(
                                        onClick = {
                                            showPointsList = false
                                            connectWalkedPathTo(targetObj)
                                        },
                                    ) {
                                        Text("🔗 Connect", color = Color(0xFF34C759), fontWeight = FontWeight.Bold)
                                    }
                                    TextButton(
                                        onClick = {
                                            if (p.id.isNotEmpty()) deletedRefIds.add(p.id)
                                            deletedRefNames.add(p.name)
                                            sessionRefPins.removeAll { it.name == p.name }
                                            onDeleteReferencePoint(p.id, p.name)
                                            showToast("Deleted reference point “${p.name}”", 2_000)
                                        },
                                    ) {
                                        Text("Delete", color = Color(0xFFFF3B30))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPointsList = false
                    captureReq[0] = true
                    showToast("Capturing reference point…", 1_200)
                }) { Text("＋ Add point") }
            },
            dismissButton = { TextButton(onClick = { showPointsList = false }) { Text("Close") } },
        )
    }

    if (showObjectsList) {
        val items = sessionObjects.filter { !it.isReference }.map { it to false } +
            allVenueObjects.filter { !it.isReference }.map { it to true }
        AlertDialog(
            onDismissRequest = { showObjectsList = false },
            title = { Text("Objects (${items.size})") },
            text = {
                if (items.isEmpty()) {
                    Text("None yet. Pick an object on the bar, then tap the camera view to place it.")
                } else {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        items.forEach { (obj, inVenue) ->
                            val label = if (obj.text.isNotBlank()) obj.text else obj.kind.label
                            val vPos = if (inVenue) obj.position else filter.alignment.inverse().transformPoint(obj.position)
                            val targetObj = AdminNavTarget(
                                id = obj.id,
                                label = label,
                                glyph = obj.kind.glyph,
                                subtitle = "Placed ${obj.kind.label}",
                                level = obj.level.ifEmpty { defaultLevel },
                                x = vPos.x,
                                y = vPos.y,
                                z = vPos.z,
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${obj.kind.glyph} $label",
                                    modifier = Modifier.weight(1f).clickable {
                                        showObjectsList = false
                                        planToTarget(targetObj)
                                    },
                                )
                                TextButton(
                                    onClick = {
                                        showObjectsList = false
                                        connectWalkedPathTo(targetObj)
                                    },
                                ) {
                                    Text("🔗 Connect", color = Color(0xFF34C759), fontWeight = FontWeight.Bold)
                                }
                                TextButton(
                                    onClick = {
                                        sessionObjects.removeAll { it.id == obj.id }
                                        committedObjects.removeAll { it.id == obj.id }
                                        anchors.remove(obj.id)?.detach()
                                        deletedObjectIds.add(obj.id)
                                        onDeleteObject(obj.id)
                                        showToast("Deleted $label", 2_000)
                                    },
                                ) {
                                    Text("Delete", color = Color(0xFFFF3B30))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showObjectsList = false }) { Text("Close") } },
        )
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name this destination") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Destination") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && pendingDestination != null,
                    onClick = {
                        naming = false
                        val spot = pendingDestination!!
                        recorder.recordAnchor(spot)
                        refreshFromAnchors()
                        val track = buildTrack()
                        val venueFromSession = track.venueFromSessionAt(recorder.progress)
                        val here = venueFromSession.transformPoint(spot)
                        val floorY = venueFromSession.transformPoint(Vec3(spot.x, pendingFloorSession, spot.z)).y
                        val spreadCm = visits.firstOrNull()?.filter?.spreadCm
                        val drift = driftCm[0].takeIf { it > 0f }
                        val startId = startMarker?.id.orEmpty()
                        val savedLevel = pendingLevel.ifEmpty { defaultLevel }
                        commit()
                        val trimmed = name.trim()
                        onSave(
                            CapturedDestination(
                                trimmed, savedLevel, here.x, here.z, committedPath.toList(), committedObjects.toList(),
                                floorY = floorY, startMarkerId = startId, alignmentSpreadCm = spreadCm, driftCorrectedCm = drift,
                            ),
                        )
                        // Keep the admin in the live camera view! Add the newly saved destination to the dropdown & currentAt
                        sessionSavedDestinations.add(
                            Venue.POI(
                                id = "custom-session-${sessionSavedDestinations.size}",
                                name = LocalizedText(trimmed),
                                category = CustomDestination.CATEGORY,
                                level = savedLevel,
                                x = here.x,
                                z = here.z,
                                node = "",
                                elevation = floorY,
                            ),
                        )
                        currentAtName = trimmed
                        greenFlash = "Destination “$trimmed” saved"
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }

    pendingRef?.let { ref ->
        var refName by remember(ref) { mutableStateOf("") }
        val ratio = remember(ref.image) {
            (ref.image.width.toFloat() / ref.image.height.toFloat().coerceAtLeast(1f)).coerceIn(0.4f, 2.5f)
        }
        val quality = when {
            !ref.accepted -> "Low contrast — will still save as reference pin"
            ref.detail >= 55 -> "Good detail — trackable"
            ref.detail < LOW_DETAIL -> "Very little detail — will be slow or hard to recognize"
            else -> "Low detail — may need close-up view"
        }
        AlertDialog(
            onDismissRequest = { pendingRef = null },
            title = { Text("Save reference point") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(
                            bitmap = ref.image.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .heightIn(max = 180.dp)
                                .aspectRatio(ratio)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    }
                    Text(quality, fontWeight = FontWeight.Bold)
                    Text("Quality ${ref.detail}/100 · about %.1f m away".format(ref.distanceM))
                    ref.problem?.let { Text("⚠ $it", color = Color(0xFFFF9500)) }
                    // What decides how fast the photo is recognized later.
                    Text(
                        "Recognized in about a second: flat, detailed surfaces such as posters, signs, paintings or a " +
                            "printed sheet. Plants, furniture and objects are only recognized from almost exactly this " +
                            "spot, which can take a long search.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                    )
                    OutlinedTextField(
                        value = refName,
                        onValueChange = { refName = it },
                        singleLine = true,
                        label = { Text("Name, e.g. Lobby poster") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = true,
                    onClick = {
                        val trimmed = refName.trim().ifEmpty { "Ref point ${allRefPins.size + 1}" }
                        onSaveReferencePoint(
                            CapturedReferencePoint(
                                name = trimmed, image = ref.image, physicalWidth = ref.physicalWidth,
                                level = ref.level, x = ref.x, y = ref.y, z = ref.z, facing = ref.facing,
                                mounting = Venue.MarkerMounting.WALL,
                            ),
                        )
                        sessionRefPins.add(RefPin(ref.x, ref.y, ref.z, trimmed))
                        currentAtName = trimmed
                        greenFlash = "Reference point “$trimmed” saved"
                        pendingRef = null
                    },
                ) { Text(if (ref.problem != null || ref.detail < LOW_DETAIL) "Save anyway" else "Save") }
            },
            dismissButton = { TextButton(onClick = { pendingRef = null }) { Text("Retake") } },
        )
    }

    if (showHelpGuide) {
        AlertDialog(
            onDismissRequest = { showHelpGuide = false },
            title = { Text("Admin Camera Button Guide") },
            text = {
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Tip: You can also long-press any button on the camera screen to see what it does!", fontSize = 13.sp, color = Color(0xFF0A84FF), fontWeight = FontWeight.SemiBold)
                    Text("• 📍 You are currently at + Dropdown:\nShows your current reference point. Tap 'Navigate to' dropdown to see live 3D floor arrows to any destination, reference point, or placed object.")
                    Text("• Auto points (Switch):\nWhen ON, automatically captures a reference point every 3 meters as you walk.")
                    Text("• ☕ Mug / 🎁 Gift / ⭐ Star / 🎈 Balloon / 💬 Message:\nSelect an object on the bar, then tap anywhere on the live camera video to place it in 3D.")
                    Text("• ✕ (Exit Camera):\nCloses the camera and goes back to the list. All saved points & destinations are kept.")
                    Text("• ⊕ (Start Here):\nSets your current standing position as the starting origin without needing to scan a sign.")
                    Text("• ◎ (Add Reference Point):\nCaptures a photo & 3D position of what the center of the camera is aimed at, placing a blue 3D pin and registering it for recognition.")
                    Text("• ☰ (Reference Points List):\nLists all reference points with distance. Tap one to navigate to it, tap 'Delete' to remove it, or tap '+ Add point'.")
                    Text("• ◳ (Objects List):\nLists all placed 3D objects. Tap one to navigate to it, or tap 'Delete' to remove it.")
                    Text("• 🏷 (AR Signs):\nOpens the AR Signs gallery to add a sign from Camera/Gallery, share to print, or delete signs.")
                    Text("• Save destination here:\nSaves your current position, walked path, and placed objects as a named destination while staying in the camera view.")
                }
            },
            confirmButton = { TextButton(onClick = { showHelpGuide = false }) { Text("Got it") } },
        )
    }
}

/** The bar of objects a tap can place; the selected one is highlighted. Long-pressing shows what it does. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ObjectBar(
    selected: PlacedObjectKind,
    onSelect: (PlacedObjectKind) -> Unit,
    onLongPress: (PlacedObjectKind) -> Unit = {},
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (kind in PlacedObjectKind.entries) {
            val isSelected = kind == selected
            Text(
                "${kind.glyph} ${kind.label}",
                color = if (isSelected) Color.Black else Color.White,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .background(if (isSelected) Color(0xFFFFFFFF) else Color(0x99000000), RoundedCornerShape(20.dp))
                    .combinedClickable(
                        onClick = { onSelect(kind) },
                        onLongClick = { onLongPress(kind) },
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/** Where the user types the name of the object they just placed. It sits above the object bar, not in a pop-up. */
@Composable
private fun NameBox(value: String, hint: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(Color(0xE6FFFFFF), RoundedCornerShape(10.dp)).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true,
            placeholder = { Text(hint) },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDone) { Text("OK") }
    }
}
