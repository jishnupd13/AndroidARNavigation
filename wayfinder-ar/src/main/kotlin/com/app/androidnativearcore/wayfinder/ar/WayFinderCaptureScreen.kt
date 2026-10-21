package com.app.androidnativearcore.wayfinder.ar

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
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import kotlin.math.atan2
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
) {
    val venue = pkg.venue
    val defaultLevel = venue.levels.firstOrNull()?.id.orEmpty()
    val markerTracker = remember(pkg) { ArMarkerTracker(pkg) }
    val planner = remember(venue) { RoutePlanner(venue) }
    val signs = remember(pkg) {
        venue.markers.mapNotNull { marker ->
            BitmapFactory.decodeFile(pkg.file(marker).path)?.let { marker.name.resolved() to it }
        }
    }

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
    var unusableRefsWarning by remember { mutableStateOf<String?>(null) }

    // --- Live "Currently at" & Dropdown Navigation state ---
    var currentAtName by remember { mutableStateOf("Start point") }
    var userVenuePos by remember { mutableStateOf(Vec3.ZERO) }
    var fromDropdownExpanded by remember { mutableStateOf(false) }
    var navDropdownExpanded by remember { mutableStateOf(false) }
    var selectedNavTarget by remember { mutableStateOf<AdminNavTarget?>(null) }
    var activeRoute by remember { mutableStateOf<Route?>(null) }
    var activeRouteDestPoi by remember { mutableStateOf<Venue.POI?>(null) }
    val sessionConnectedWalkedPoints = remember(pkg) { mutableStateListOf<WalkedPoint>() }

    LaunchedEffect(greenFlash) { if (greenFlash != null) { delay(1500); greenFlash = null } }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            anchors.values.forEach { it.detach() }
            anchors.clear()
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

    fun handleTap(frame: com.google.ar.core.Frame, session: com.google.ar.core.Session, x: Float, y: Float) {
        val cameraPosition = Vec3(camera[0], camera[1], camera[2])
        val hit = markerTracker.surfaceAt(frame, session, x, y, MAX_OBJECT_DISTANCE_M)
        val onSurface = hit != null
        val wanted = hit?.position ?: run {
            val (origin, direction) = markerTracker.rayThrough(frame, x, y, view.width.toFloat(), view.height.toFloat())
            val floor = floorSessionY()
            val toFloor = if (direction.y < -0.05f) (floor - origin.y) / direction.y else -1f
            val along = if (toFloor in 0.5f..MAX_OBJECT_DISTANCE_M) toFloor else 1.5f
            Vec3(origin.x + direction.x * along, if (toFloor in 0.5f..MAX_OBJECT_DISTANCE_M) floor else origin.y + direction.y * along, origin.z + direction.z * along)
        }
        val spot = spreadObjectPosition(wanted, sessionObjects.map { it.position }, MIN_OBJECT_SEPARATION_M)
        val id = "object-" + java.util.UUID.randomUUID()
        anchors[id] = if (hit != null && spot == hit.position) hit.createAnchor()
        else session.createAnchor(Pose(floatArrayOf(spot.x, spot.y, spot.z), floatArrayOf(0f, 0f, 0f, 1f)))
        val yaw = Math.toDegrees(kotlin.math.atan2((cameraPosition.x - spot.x).toDouble(), (cameraPosition.z - spot.z).toDouble())).toFloat()
        val distance = spot.distanceTo(cameraPosition)
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

    fun grabReference(frame: com.google.ar.core.Frame, session: com.google.ar.core.Session): RefCapture? {
        val cameraPosition = Vec3(camera[0], camera[1], camera[2])
        val hit = markerTracker.surfaceAt(
            frame, session, view.width / 2f, view.height / 2f, MAX_OBJECT_DISTANCE_M, preferPlane = false,
        )
        val hitPos = hit?.position ?: run {
            val (origin, direction) = markerTracker.rayThrough(frame, view.width / 2f, view.height / 2f, view.width.toFloat(), view.height.toFloat())
            Vec3(origin.x + direction.x * 1.2f, origin.y + direction.y * 1.2f, origin.z + direction.z * 1.2f)
        }
        val cropFraction = 0.45f
        val bitmap = try {
            frame.acquireCameraImage().use { image ->
                val full = CameraImage.toBitmap(image)
                CameraImage.rotate(CameraImage.centerSquare(full, cropFraction), displayRotationDegrees(view))
            }
        } catch (e: Exception) {
            return null
        }
        val intrinsics = frame.camera.imageIntrinsics
        val fx = intrinsics.focalLength[0]
        val dims = intrinsics.imageDimensions
        val cropSidePx = min(dims[0], dims[1]) * cropFraction
        val distance = hitPos.distanceTo(cameraPosition).coerceIn(0.3f, MAX_OBJECT_DISTANCE_M)
        val physicalWidth = (distance * cropSidePx / fx).coerceIn(0.05f, 1.5f)

        val accepted = try {
            AugmentedImageDatabase(session).addImage("probe", bitmap, physicalWidth)
            true
        } catch (e: Exception) {
            false
        }
        val detail = CameraImage.detailScore(bitmap)

        val venueFromSession = filter.alignment.inverse()
        val surfaceVenue = venueFromSession.transformPoint(hitPos)
        val cameraVenue = venueFromSession.transformPoint(cameraPosition)
        val facing = (Math.toDegrees(
            atan2((cameraVenue.x - surfaceVenue.x).toDouble(), (cameraVenue.z - surfaceVenue.z).toDouble()),
        ).toFloat() + 360f) % 360f
        val level = user.level.ifEmpty { startMarker?.level ?: defaultLevel }
        return RefCapture(bitmap, physicalWidth, level, surfaceVenue.x, surfaceVenue.y, surfaceVenue.z, facing, detail, accepted, distance)
    }

    fun captureReferencePoint(frame: com.google.ar.core.Frame, session: com.google.ar.core.Session) {
        val ref = grabReference(frame, session)
        if (ref == null) {
            showToast("Camera image not ready yet — hold still and try again.", 2_500)
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
        if (recorder.progress - lastAutoProgress[0] < AUTO_INTERVAL_M) return
        lastAutoProgress[0] = recorder.progress
        val ref = grabReference(frame, session) ?: return
        if (!ref.accepted) return
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

    /** Sets [pin] as the starting reference point ("From") and anchors the current walk to its venue coordinates. */
    fun selectStartReferencePoint(pin: RefPin) {
        val level = defaultLevel.ifEmpty { return }
        val camSession = Vec3(camera[0], camera[1], camera[2])
        val candidate = Mat4.yaw(0f, Vec3(camSession.x - pin.x, camSession.y - pin.y, camSession.z - pin.z))
        filter.reset()
        repeat(AlignmentFilter.LOCK_IN_SIGHTINGS) {
            if (filter.update(candidate, 1f, 1f, null)) alignment = filter.alignment
        }
        startMarker = Venue.Marker(
            id = pin.id.ifEmpty { "ref-${pin.name}" },
            name = LocalizedText(pin.name),
            image = "",
            physicalWidth = 0.2f,
            level = level,
            x = pin.x, y = pin.y, z = pin.z,
            facing = 0f,
            mounting = Venue.MarkerMounting.WALL,
        )
        currentAtName = pin.name
        userVenuePos = Vec3(pin.x, pin.y, pin.z)
        recorder.clear()
        committedPath.clear()
        visits.clear()
        bootstrapped = true
        lockedIn = true
        showToast("Start set to 📍 ${pin.name} — walk to your target & tap Connect!", 3_000)
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
            sessionCameraConfig = null,
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

                if (message != null) {
                    val since = lostSince ?: now.also { lostSince = it }
                    if (now - since > TRACKING_LOST_RESET_MS && filter.isAligned) {
                        commit()
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
                    if (isCapturedRef && !filter.isLockedIn) {
                        repeat(AlignmentFilter.LOCK_IN_SIGHTINGS) {
                            if (filter.update(candidate, 1f, 1f, null)) alignment = filter.alignment
                        }
                    } else if (filter.update(candidate, sighting.distanceMeters, sighting.viewCosine, sighting.cameraInSession)) {
                        alignment = filter.alignment
                    }
                    if (isBootstrap || isCapturedRef) {
                        startMarker = sighting.marker
                    }
                    currentAtName = sighting.marker.name.resolved()

                    val visit = visits.lastOrNull()?.takeIf { now - it.lastMs < VISIT_GAP_MS }
                        ?: Visit(recorder.progress, now).also { visits += it }
                    val closeBefore = visit.filter.closeSightings
                    visit.filter.update(candidate, sighting.distanceMeters, sighting.viewCosine, sighting.cameraInSession)
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
                lockedIn = filter.isLockedIn

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
                    alignment = alignment,
                    cameraPosition = cameraPosition,
                )
            }
            // Render all saved objects + committed objects in venue frame, and session objects in session frame
            ObjectsScene(allVenueObjects, alignment, cameraPosition)
            ObjectsScene(sessionObjects.toList(), Mat4.IDENTITY, cameraPosition)
            // Render all reference points (saved + captured this session) with BLUE pins and BLUE text labels
            ReferencePointsScene(allRefPins, alignment, cameraPosition)
        }

        // Taps on the camera view place the selected object; the controls below sit on top and take their own taps.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { offset ->
                    pendingTap[0] = offset.x; pendingTap[1] = offset.y; pendingTap[2] = 1f
                }
            },
        )

        // Top HUD: "You are currently at" + Navigation Dropdown + Auto points switch + status banner
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top bar: Currently At + Dropdown + Auto Points
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
                    Box(Modifier.weight(1f)) {
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { fromDropdownExpanded = true }
                                .padding(vertical = 2.dp),
                        ) {
                            Text(
                                "You are currently at (tap to pick start ref ▾)",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                            )
                            Text(
                                if (hasValidStartRef) "📍 $currentAtName ▾" else "⚠ Start point (No ref image — tap ◎ or pick ▾)",
                                color = if (hasValidStartRef) Color(0xFF0A84FF) else Color(0xFFFF9500),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            unusableRefsWarning?.let { bad ->
                                Text(
                                    "⚠ Unclear ref image: $bad — tap ◎ to recapture",
                                    color = Color(0xFFFF453A),
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = fromDropdownExpanded,
                            onDismissRequest = { fromDropdownExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.85f).heightIn(max = 280.dp),
                        ) {
                            DropdownMenuItem(
                                text = { Text("⊕ Current standing spot (Start point)", fontWeight = FontWeight.SemiBold) },
                                onClick = {
                                    fromDropdownExpanded = false
                                    startWithoutSign()
                                },
                            )
                            if (allRefPins.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No saved reference points yet — tap ◎ to capture one", color = Color.Gray) },
                                    onClick = { fromDropdownExpanded = false },
                                )
                            } else {
                                allRefPins.forEach { pin ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "📍 Start from: ${pin.name}",
                                                color = Color(0xFF0A84FF),
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        },
                                        onClick = {
                                            fromDropdownExpanded = false
                                            selectStartReferencePoint(pin)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                        Text(
                            "Auto points",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.combinedClickable(
                                onClick = { autoPoints = !autoPoints },
                                onLongClick = { showToast("Auto points: Automatically captures & saves a reference point every 3 meters as you walk.", 3_500) },
                            ),
                        )
                        Switch(checked = autoPoints, onCheckedChange = { autoPoints = it })
                        CircleButton(
                            glyph = "ⓘ",
                            description = "Button Guide: Tap to see what every button does",
                            onClick = { showHelpGuide = true },
                            onLongPress = { showToast(it, 3_500) },
                        )
                    }
                }

                // Dropdown selector for available navigations / targets to walk & connect to
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color(0xFF2C2C2E), RoundedCornerShape(10.dp))
                            .clickable { navDropdownExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val currentSelection = selectedNavTarget
                        Text(
                            if (currentSelection != null) {
                                val d = hypot(currentSelection.x - userVenuePos.x, currentSelection.z - userVenuePos.z)
                                "Target: ${currentSelection.glyph} ${currentSelection.label} (%.1f m)".format(d)
                            } else {
                                "Walk & Connect / Navigate to (${navTargets.size} available)…"
                            },
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selectedNavTarget != null) {
                                Text(
                                    "✕",
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable { planToTarget(null) }.padding(horizontal = 4.dp),
                                )
                            }
                            Text("▾", color = Color(0xFF0A84FF), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    DropdownMenu(
                        expanded = navDropdownExpanded,
                        onDismissRequest = { navDropdownExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 320.dp),
                    ) {
                        if (navTargets.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No destinations, reference points, or objects yet") },
                                onClick = { navDropdownExpanded = false },
                            )
                        } else {
                            if (selectedNavTarget != null) {
                                DropdownMenuItem(
                                    text = { Text("✕ Clear active route", color = Color(0xFFFF3B30), fontWeight = FontWeight.SemiBold) },
                                    onClick = {
                                        navDropdownExpanded = false
                                        planToTarget(null)
                                    },
                                )
                            }
                            navTargets.forEach { target ->
                                val dist = hypot(target.x - userVenuePos.x, target.z - userVenuePos.z)
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    "${target.glyph} ${target.label}",
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (target.glyph == "📍") Color(0xFF0A84FF) else Color.Unspecified,
                                                )
                                                Text(target.subtitle, fontSize = 12.sp, color = Color.Gray)
                                            }
                                            Text("%.1f m".format(dist), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        }
                                    },
                                    onClick = {
                                        navDropdownExpanded = false
                                        planToTarget(target)
                                    },
                                )
                            }
                        }
                    }
                }
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
            selectedNavTarget?.let { target ->
                @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF34C759))
                        .combinedClickable(
                            onClick = { connectWalkedPathTo(target) },
                            onLongClick = {
                                showToast(
                                    "Connect walked path: Saves the walked path connecting 📍 $currentAtName to ${target.glyph} ${target.label} so navigation routes between them.",
                                    3_500,
                                )
                            },
                        )
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "🔗 Connect walked path: $currentAtName → ${target.label}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
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
                ) { Text("Save") }
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
