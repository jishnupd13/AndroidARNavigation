package com.app.androidnativearcore.wayfinder.ar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.google.ar.core.AugmentedImageDatabase
import kotlin.math.atan2
import kotlin.math.min
import com.app.androidnativearcore.wayfinder.core.AlignmentFilter
import com.app.androidnativearcore.wayfinder.core.AlignmentTrack
import com.app.androidnativearcore.wayfinder.core.LocalizedText
import com.app.androidnativearcore.wayfinder.core.MarkerAlignment
import com.app.androidnativearcore.wayfinder.core.Mat4
import com.app.androidnativearcore.wayfinder.core.PlacedObject
import com.app.androidnativearcore.wayfinder.core.PlacedObjectKind
import com.app.androidnativearcore.wayfinder.core.SessionPathRecorder
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.VenuePackage
import com.app.androidnativearcore.wayfinder.core.WalkedPoint
import com.app.androidnativearcore.wayfinder.core.objectScaleFor
import com.app.androidnativearcore.wayfinder.core.spreadObjectPosition
import com.app.androidnativearcore.wayfinder.core.straightWalk
import com.app.androidnativearcore.wayfinder.core.toVenue
import com.app.androidnativearcore.wayfinder.core.toWalkedPoints
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import kotlin.math.hypot
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
private const val MIN_PATH_LENGTH_M = 1f

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

/** The user's latest pose, written every frame but read only on taps and saves, so it never triggers a redraw. */
private class LiveUser {
    var valid = false
    var session = Vec3.ZERO
    var forward = Vec3.ZERO
    var level = ""
    var venueX = 0f
    var venueZ = 0f
}

/**
 * Full-screen AR "Add destination", the Android port of the iOS prototype's recording mode:
 *
 * 1. The user scans an AR sign. After a moment of close-up sightings the alignment is locked in and **recording
 *    starts**.
 * 2. They walk to the destination, picking objects from the bar at the bottom and **tapping the screen** where each
 *    one goes. Scanning a sign again on the way (for example at the end) corrects the drift ARCore built up: the
 *    path is converted with an alignment that follows the scans along the walk.
 * 3. The walk and the objects are recorded in the AR session's own (stable) frame, like iOS anchors, and converted
 *    to venue coordinates once, with the best alignment, when **Save destination here** is tapped. The destination
 *    is set automatically: it is where the user stands when saving. Routes to it follow the walked path (around
 *    walls).
 */
@Composable
fun WayFinderCaptureScreen(
    pkg: VenuePackage,
    onSave: (CapturedDestination) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Called when the admin captures and confirms a reference point; the caller persists it. */
    onSaveReferencePoint: (CapturedReferencePoint) -> Unit = {},
) {
    val venue = pkg.venue
    val markerTracker = remember(pkg) { ArMarkerTracker(pkg) }
    val signs = remember(pkg) {
        venue.markers.take(6).mapNotNull { marker ->
            BitmapFactory.decodeFile(pkg.file(marker).path)?.let { marker.name.resolved() to it }
        }
    }

    // --- Recording state ---
    val filter = remember(pkg) { AlignmentFilter() }
    var alignment by remember { mutableStateOf(Mat4.IDENTITY) }
    var startMarker by remember { mutableStateOf<Venue.Marker?>(null) } // the sign the walk starts from
    val recorder = remember(pkg) { SessionPathRecorder() }
    // Segments already converted to venue coordinates (when tracking was lost, the old session frame is gone).
    val committedPath = remember(pkg) { mutableListOf<WalkedPoint>() }
    val committedObjects = remember(pkg) { mutableStateListOf<PlacedObject>() }
    // Objects placed since the last commit, in session coordinates (so no alignment change can shift them).
    val sessionObjects = remember(pkg) { mutableStateListOf<PlacedObject>() }
    // Reference points captured this session (venue coordinates), shown as pins in the camera.
    val sessionRefPins = remember(pkg) { mutableStateListOf<RefPin>() }
    // ARCore keeps anchors glued to the real world as its tracking improves; objects follow their anchors.
    val anchors = remember(pkg) { HashMap<String, Anchor>() }
    val camera = remember { FloatArray(3) }       // camera position in the session
    val user = remember { LiveUser() }
    val pendingTap = remember { FloatArray(3) }   // x, y, and 1 when a tap waits to be handled in the next frame
    var lostSince by remember { mutableStateOf<Long?>(null) }
    // Every scan of a sign, with how far the walk had got. Their alignments are interpolated along the walk to remove drift.
    val visits = remember(pkg) { mutableListOf<Visit>() }
    val otherSignToastMs = remember { LongArray(1) } // when the "scan the same sign" hint was last shown
    val objectProgress = remember(pkg) { HashMap<String, Float>() } // how far the walk had got when each object was placed
    val driftCm = remember { FloatArray(1) }                         // the most drift a re-scan has corrected so far
    val recent = remember(pkg) { ArrayDeque<Vec3>() }       // the phone's latest positions, to tell when it is held steady
    val floorSamples = remember(pkg) { ArrayDeque<Float>() } // recent floor heights (session frame) from detected planes
    val frameCounter = remember { IntArray(1) }

    // --- What the user sees (only things that change rarely) ---
    var lockedIn by remember { mutableStateOf(false) }
    var lockProgress by remember { mutableStateOf(0) }
    var signAgreement by remember { mutableStateOf("") } // how well the sign sightings agree, e.g. "sign ±2 cm"
    var levelName by remember { mutableStateOf("") }
    var recordedSummary by remember { mutableStateOf("") }
    var canSave by remember { mutableStateOf(false) }
    var steady by remember { mutableStateOf(false) }
    var pendingDestination by remember { mutableStateOf<Vec3?>(null) } // session position chosen when Save was tapped
    var pendingLevel by remember { mutableStateOf("") }
    var pendingFloorSession by remember { mutableStateOf(0f) }
    var trackingMessage by remember { mutableStateOf<String?>("Starting the camera… move the phone slowly.") }
    var toast by remember { mutableStateOf<Toast?>(null) }
    var naming by remember { mutableStateOf(false) }
    var selectedKind by remember { mutableStateOf(PlacedObjectKind.CUP) } // what the next tap places
    var messageText by remember { mutableStateOf("") }
    var pendingRef by remember { mutableStateOf<RefCapture?>(null) } // a captured reference point awaiting naming
    val captureReq = remember { BooleanArray(1) }                    // set when the Add-reference-point button is tapped
    var autoPoints by remember { mutableStateOf(false) }             // auto-drop reference points while walking (default off)
    val lastAutoProgress = remember { FloatArray(1) }                // walk distance at the last auto-capture
    var lastAutoPos by remember { mutableStateOf<Vec3?>(null) }      // venue position of the last auto-captured point
    val autoCount = remember { IntArray(1) }
    var greenFlash by remember { mutableStateOf<String?>(null) }     // brief green "captured" confirmation
    var bootstrapped by remember { mutableStateOf(false) }           // started mapping without scanning a sign
    var showPointsList by remember { mutableStateOf(false) }
    var showObjectsList by remember { mutableStateOf(false) }

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

    /**
     * The real floor's height in the session frame: the middle of the recent plane-based estimates, or, until ARCore
     * has found the floor, the phone's height minus a typical 1.4 m (what the iOS prototype does too).
     */
    fun floorSessionY(): Float =
        floorSamples.sorted().let { if (it.isEmpty()) camera[1] - PHONE_HEIGHT_M else it[it.size / 2] }

    fun yawDegrees(m: Mat4) = Math.toDegrees(AlignmentFilter.yaw(m).toDouble()).toFloat()

    val hasUserObjects = sessionObjects.any { !it.isReference } || committedObjects.any { !it.isReference }

    fun committedLength(): Float =
        committedPath.zipWithNext().sumOf { (a, b) ->
            if (a.run == b.run && a.level == b.level) hypot(a.x - b.x, a.z - b.z).toDouble() else 0.0
        }.toFloat()

    /** Moves each placed object to where ARCore now believes its anchor is (tracking refines it over time). */
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

    /** The alignment along the walk: one entry per scan of a sign that had a few close-up sightings. */
    fun buildTrack(): AlignmentTrack = AlignmentTrack().also { track ->
        visits.filter { it.filter.closeSightings >= 2 }.forEach { track.add(it.progress, it.filter.alignment) }
        if (track.isEmpty) track.add(0f, filter.alignment)
    }

    /**
     * Converts what was recorded in the session frame to venue coordinates and keeps it. Each point and object is
     * converted with the alignment for the moment of the walk it belongs to, so drift between scans is corrected.
     * Done when saving, and when tracking is lost (after which the session frame can't be trusted any more).
     */
    fun commit() {
        val marker = startMarker ?: return
        val track = buildTrack()
        val raw = recorder.points
        if (raw.isNotEmpty()) {
            val converted = raw.toWalkedPoints(venue, track, marker.level, floorSessionY())
            if (committedPath.isEmpty()) {
                // Start the path at the sign itself, then straight to where the user first stood: they just looked at
                // the sign, so the way between is clear. This lets routes begin at the sign later.
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
     * What a tap does: puts the selected object where the user tapped. On a surface ARCore knows, it goes exactly
     * there. Where ARCore knows no surface yet (blank wall, glass, a spot it hasn't scanned), it still goes somewhere
     * sensible, on the floor along the tapped direction, and the user is told, so a tap is never ignored.
     */
    fun handleTap(frame: com.google.ar.core.Frame, session: com.google.ar.core.Session, x: Float, y: Float) {
        if (selectedKind == PlacedObjectKind.MESSAGE && messageText.isBlank()) {
            showToast("Type your message first, then tap where it goes.")
            return
        }
        val cameraPosition = Vec3(camera[0], camera[1], camera[2])
        val hit = markerTracker.surfaceAt(frame, session, x, y, MAX_OBJECT_DISTANCE_M)
        val onSurface = hit != null
        val wanted = hit?.position ?: run {
            val (origin, direction) = markerTracker.rayThrough(frame, x, y, view.width.toFloat(), view.height.toFloat())
            val floor = floorSessionY()
            val toFloor = if (direction.y < -0.05f) (floor - origin.y) / direction.y else -1f
            // The floor if the tap points at it within reach; otherwise a spot 1.5 m along the tapped direction.
            val along = if (toFloor in 0.5f..MAX_OBJECT_DISTANCE_M) toFloor else 1.5f
            Vec3(origin.x + direction.x * along, if (toFloor in 0.5f..MAX_OBJECT_DISTANCE_M) floor else origin.y + direction.y * along, origin.z + direction.z * along)
        }
        val spot = spreadObjectPosition(wanted, sessionObjects.map { it.position }, MIN_OBJECT_SEPARATION_M)
        val id = "object-" + java.util.UUID.randomUUID()
        // Anchor to the tapped surface itself; an object nudged off its spot, or placed without a surface, gets a
        // free-standing anchor at its position.
        anchors[id] = if (hit != null && spot == hit.position) hit.createAnchor()
        else session.createAnchor(Pose(floatArrayOf(spot.x, spot.y, spot.z), floatArrayOf(0f, 0f, 0f, 1f)))
        // Turn the object's front toward the user who is placing it.
        val yaw = Math.toDegrees(kotlin.math.atan2((cameraPosition.x - spot.x).toDouble(), (cameraPosition.z - spot.z).toDouble())).toFloat()
        val distance = spot.distanceTo(cameraPosition)
        objectProgress[id] = recorder.progress
        sessionObjects += PlacedObject(
            id = id, kind = selectedKind, level = "", x = spot.x, y = spot.y, z = spot.z, yawDegrees = yaw,
            text = if (selectedKind == PlacedObjectKind.MESSAGE) messageText.trim() else "",
            createdAtMillis = System.currentTimeMillis(), scale = objectScaleFor(distance),
        )
        showToast(
            if (onSurface) "${selectedKind.label} added, %.1f m away".format(distance)
            else "${selectedKind.label} added %.1f m away (no surface found there, so it is on the floor)".format(distance),
            if (onSurface) 1_800 else 3_000,
        )
    }

    /**
     * Grabs the current camera image of whatever the center of the screen is aimed at, estimates its real-world size
     * (from depth distance and focal length) and its venue position, scores how trackable it looks, and offers it for
     * saving as a reference point. Aim at a distinctive flat spot, ideally above head height.
     */
    /** Grabs and measures the spot the center of the screen is aimed at, or null if there's no surface / no image. */
    fun grabReference(frame: com.google.ar.core.Frame, session: com.google.ar.core.Session): RefCapture? {
        val cameraPosition = Vec3(camera[0], camera[1], camera[2])
        val hit = markerTracker.surfaceAt(frame, session, view.width / 2f, view.height / 2f, MAX_OBJECT_DISTANCE_M)
            ?: return null
        val bitmap = try {
            frame.acquireCameraImage().use { image ->
                val full = CameraImage.toBitmap(image) ?: return null
                CameraImage.rotate(CameraImage.centerSquare(full, 0.6f), displayRotationDegrees(view))
            }
        } catch (e: Exception) {
            return null
        }
        val intrinsics = frame.camera.imageIntrinsics
        val fx = intrinsics.focalLength[0]
        val dims = intrinsics.imageDimensions
        val cropSidePx = min(dims[0], dims[1]) * 0.6f
        val distance = hit.position.distanceTo(cameraPosition)
        val physicalWidth = (distance * cropSidePx / fx).coerceIn(0.05f, 3f)

        val accepted = try {
            AugmentedImageDatabase(session).addImage("probe", bitmap, physicalWidth)
            true
        } catch (e: Exception) {
            false
        }
        val detail = CameraImage.detailScore(bitmap)

        val venueFromSession = filter.alignment.inverse()
        val surfaceVenue = venueFromSession.transformPoint(hit.position)
        val cameraVenue = venueFromSession.transformPoint(cameraPosition)
        val facing = (Math.toDegrees(
            atan2((cameraVenue.x - surfaceVenue.x).toDouble(), (cameraVenue.z - surfaceVenue.z).toDouble()),
        ).toFloat() + 360f) % 360f
        val level = user.level.ifEmpty { startMarker?.level ?: "" }
        return RefCapture(bitmap, physicalWidth, level, surfaceVenue.x, surfaceVenue.y, surfaceVenue.z, facing, detail, accepted, distance)
    }

    /** Manual capture: opens the name/quality confirmation dialog, or tells the user to aim somewhere usable. */
    fun captureReferencePoint(frame: com.google.ar.core.Frame, session: com.google.ar.core.Session) {
        val ref = grabReference(frame, session)
        if (ref == null) {
            showToast("Aim the center at a wall/feature within ${MAX_OBJECT_DISTANCE_M.toInt()} m and hold still.", 3_000)
            return
        }
        pendingRef = ref
    }

    fun toCaptured(ref: RefCapture, name: String) = CapturedReferencePoint(
        name = name, image = ref.image, physicalWidth = ref.physicalWidth, level = ref.level,
        x = ref.x, y = ref.y, z = ref.z, facing = ref.facing, mounting = Venue.MarkerMounting.WALL,
    )

    /** Auto capture: when enabled, drops a quality-checked reference point every few metres, spaced apart. */
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
        lastAutoPos = Vec3(ref.x, ref.y, ref.z)
        greenFlash = "Reference point added"
    }

    /**
     * Starts mapping without an existing sign: the current spot becomes the venue origin (identity alignment), which
     * unlocks the capture controls so the admin can drop the first reference points. Those captured points (with their
     * images) then serve as the anchors future sessions scan.
     */
    fun startWithoutSign() {
        val level = venue.levels.firstOrNull()?.id ?: return
        startMarker = Venue.Marker(
            id = "origin-bootstrap", name = LocalizedText("Start point"), image = "",
            physicalWidth = 0.2f, level = level, x = 0f, y = PHONE_HEIGHT_M, z = 0f, facing = 0f,
            mounting = Venue.MarkerMounting.WALL,
        )
        alignment = Mat4.IDENTITY
        bootstrapped = true
    }

    /** Shows distance and left/right/ahead guidance from where the phone is now to a point (session coordinates). */
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

    Box(modifier.fillMaxSize().background(Color.Black)) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            sessionCameraConfig = null,
            sessionConfiguration = { session, config ->
                markerTracker.configure(session, config, aiming = true)
                markerTracker.unusableMarkers.firstOrNull()?.let { (marker, reason) ->
                    showToast("AR sign “${marker.name.resolved()}” can't be used: $reason", 6_000)
                }
            },
            onSessionUpdated = { session, frame ->
                val message = ArMarkerTracker.trackingMessage(frame)
                if (message != trackingMessage) trackingMessage = message
                val now = frame.timestamp / 1_000_000

                // Tracking lost for a while: the AR world may have been re-based. Keep what was recorded, then ask
                // for a new scan of a sign before recording continues.
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
                    // The walk is measured from one sign. Another sign sits in the venue at its surveyed position, which for
                    // a sign hung wherever it was convenient is wrong, and blending it in would bend the whole path. So after
                    // the first sign, only that same sign is used (to lock in and to correct drift when re-scanned).
                    val start = startMarker
                    if (start != null && sighting.marker.id != start.id) {
                        if (now - otherSignToastMs[0] > 6_000) {
                            otherSignToastMs[0] = now
                            showToast("This path is measured from “${start.name.resolved()}”. Scan that sign again, not another one.", 3_500)
                        }
                        continue
                    }
                    // The first sign aligns; later sightings are blended in by distance, head-on view and viewpoint variety.
                    val candidate = MarkerAlignment.alignment(sighting.markerInSession, MarkerAlignment.transform(sighting.marker))
                    if (filter.update(candidate, sighting.distanceMeters, sighting.viewCosine, sighting.cameraInSession)) alignment = filter.alignment
                    if (startMarker == null) startMarker = sighting.marker

                    // Keep every scan of a sign separately, with how far the walk had got: re-scanning a sign later shows
                    // how much ARCore drifted since the first scan, and that drift is then removed from the path.
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

                val marker = startMarker
                if (marker != null && (lockedIn || bootstrapped) && message == null) {
                    val matrix = FloatArray(16)
                    frame.camera.pose.toMatrix(matrix, 0)
                    camera[0] = matrix[12]; camera[1] = matrix[13]; camera[2] = matrix[14]
                    val cameraSession = Vec3(camera[0], camera[1], camera[2])
                    val inVenue = filter.alignment.inverse().transformPoint(cameraSession)
                    val level = venue.level(forDeviceHeight = inVenue.y)?.id ?: marker.level
                    user.valid = true
                    user.session = cameraSession
                    user.forward = -Mat4(matrix).column(2)
                    user.level = level; user.venueX = inVenue.x; user.venueZ = inVenue.z
                    venue.level(level)?.name?.resolved()?.let { if (it != levelName) levelName = it }

                    // Sample the real floor a few times a second; the middle of the recent samples is used.
                    if (frameCounter[0]++ % 8 == 0) {
                        markerTracker.floorHeightBelow(session, cameraSession)?.let { floorSamples.addLast(it) }
                        while (floorSamples.size > 15) floorSamples.removeFirst()
                    }
                    recorder.record(cameraSession)
                    recent.addLast(cameraSession)
                    while (recent.size > STEADY_FRAMES) recent.removeFirst()
                    val mean = averageOf(recent)
                    val isSteady = mean != null && recent.size >= STEADY_FRAMES &&
                        recent.all { hypot(it.x - mean.x, it.z - mean.z) <= STEADY_RADIUS_M }
                    if (isSteady != steady) steady = isSteady
                    refreshFromAnchors()
                    val length = committedLength() + recorder.lengthMeters
                    canSave = length >= MIN_PATH_LENGTH_M
                    recordedSummary = "Recording · ${committedPath.size + recorder.points.size} points · %.0f m".format(length)

                    if (pendingTap[2] == 1f) {
                        pendingTap[2] = 0f
                        handleTap(frame, session, pendingTap[0], pendingTap[1])
                    }
                    if (captureReq[0]) {
                        // Wait for a steady frame so the saved reference image is sharp, not motion-blurred.
                        if (steady) {
                            captureReq[0] = false
                            captureReferencePoint(frame, session)
                        }
                    }
                    maybeAutoCapture(frame, session)
                } else {
                    if (pendingTap[2] == 1f) showToast("Tracking is limited right now. Hold still for a moment, then tap again.", 2_500)
                    pendingTap[2] = 0f
                    recent.clear()
                    if (steady) steady = false
                }
            },
            onSessionFailed = { error -> showToast("AR isn't available: ${error.message}", 8_000) },
        ) {
            val cameraPosition = { Position(camera[0], camera[1], camera[2]) }
            // Already-converted objects live in the venue frame; newer ones in the session frame (identity root).
            ObjectsScene(committedObjects.toList(), alignment, cameraPosition)
            ObjectsScene(sessionObjects.toList(), Mat4.IDENTITY, cameraPosition)
            // Reference points captured this session, shown as green pins with their names.
            ReferencePointsScene(sessionRefPins.toList(), alignment, cameraPosition)
        }

        // Taps on the camera view place the selected object; the controls below sit on top and take their own taps.
        if (lockedIn || bootstrapped) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { offset ->
                        pendingTap[0] = offset.x; pendingTap[1] = offset.y; pendingTap[2] = 1f
                    }
                },
            )
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Auto-points switch sits above the banner (its own row) so they never overlap.
            if (lockedIn || bootstrapped) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.background(Color(0xAA000000), RoundedCornerShape(20.dp))
                            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Auto points", color = Color.White, fontSize = 13.sp)
                        Switch(checked = autoPoints, onCheckedChange = { autoPoints = it })
                    }
                }
            }
            when {
                startMarker == null -> AlignmentPromptView(
                    trackingMessage ?: "Look for a sign like these near entrances, elevators and escalators. " +
                        "Face it from 1–2.5 m and move the phone slowly side to side; scanning it starts recording your walk.",
                    signs,
                )
                !lockedIn && !bootstrapped -> GuidanceBannerView(
                    GuidanceBanner(
                        instruction = "Locking onto the sign…",
                        glyph = "◎",
                        detail = trackingMessage
                            ?: "Face the sign, 1–2.5 m away, and move the phone slowly side to side ($lockProgress/${AlignmentFilter.LOCK_IN_SIGHTINGS})",
                    ),
                )
                else -> {
                    val objectCount = committedObjects.count { !it.isReference } + sessionObjects.count { !it.isReference }
                    GuidanceBannerView(
                        GuidanceBanner(
                            instruction = if (canSave) "Walk to the destination, then save it" else "Walk to where the destination is",
                            glyph = "●",
                            detail = trackingMessage ?: listOfNotNull(
                                levelName.ifEmpty { null }, recordedSummary.ifEmpty { null }, signAgreement.ifEmpty { null },
                                if (objectCount > 0) "$objectCount object${if (objectCount == 1) "" else "s"}" else null,
                            ).joinToString(" · "),
                        ),
                    )
                }
            }
        }

        // Green confirmation flash, centered so it never overlaps the banner or the controls.
        greenFlash?.let { msg ->
            Box(
                Modifier.align(Alignment.Center)
                    .background(Color(0xFF34C759), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("✓  $msg", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        // One bottom panel holds every control, so nothing overlaps.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToastHost(toast)
            if (lockedIn || bootstrapped) {
                if (selectedKind == PlacedObjectKind.MESSAGE) {
                    OutlinedTextField(
                        value = messageText, onValueChange = { messageText = it.take(40) }, singleLine = true,
                        label = { Text("Message, e.g. Turn left here") },
                        modifier = Modifier.fillMaxWidth().background(Color(0xE6FFFFFF), RoundedCornerShape(4.dp)),
                    )
                }
                ObjectBar(selected = selectedKind, onSelect = { selectedKind = it })
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton("✕", "Cancel", onClick = onCancel)
                if (startMarker == null && !bootstrapped && trackingMessage == null) {
                    CircleButton("⊕", "Start here", onClick = { startWithoutSign() })
                }
                if (lockedIn || bootstrapped) {
                    CircleButton("◎", "Add point", onClick = {
                        captureReq[0] = true
                        showToast("Hold still — capturing reference point…", 1_500)
                    })
                    CircleButton("☰", "Points", onClick = { showPointsList = true })
                    CircleButton("◳", "Objects", onClick = { showObjectsList = true })
                }
                Spacer(Modifier.weight(1f))
                if ((lockedIn || bootstrapped) && hasUserObjects) {
                    TextButton(onClick = {
                        // Removes the last object the user placed; the automatic reference point stays.
                        val inSession = sessionObjects.indexOfLast { !it.isReference }
                        if (inSession >= 0) {
                            val removed = sessionObjects.removeAt(inSession)
                            anchors.remove(removed.id)?.detach()
                        } else {
                            val committed = committedObjects.indexOfLast { !it.isReference }
                            if (committed >= 0) committedObjects.removeAt(committed)
                        }
                    }) { Text("Undo", color = Color.White) }
                }
            }
            if (lockedIn || bootstrapped) {
                Button(
                    enabled = canSave && steady && trackingMessage == null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        // The destination is where the phone has been held, averaged over the last moment, so hand
                        // sway can't move it. It is fixed now, before the name is typed.
                        pendingDestination = averageOf(recent) ?: user.session
                        pendingLevel = user.level
                        pendingFloorSession = floorSessionY()
                        if (recorder.progress >= 8f && visits.count { it.filter.closeSightings >= 2 } < 2) {
                            showToast("Tip: scan a sign again at the end next time, to correct drift.", 3_500)
                        }
                        naming = true
                    },
                ) {
                    Text(if (canSave && !steady) "Hold steady…" else "Save destination here")
                }
            }
        }
    }

    if (showPointsList) {
        AlertDialog(
            onDismissRequest = { showPointsList = false },
            title = { Text("Reference points (${sessionRefPins.size})") },
            text = {
                if (sessionRefPins.isEmpty()) {
                    Text("None yet. Tap ◎ Add point to capture one.")
                } else {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        sessionRefPins.forEach { p ->
                            Text(
                                "📍 ${p.name}",
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        showPointsList = false
                                        guideToSession(alignment.transformPoint(Vec3(p.x, p.y, p.z)), p.name)
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPointsList = false }) { Text("Close") } },
        )
    }

    if (showObjectsList) {
        val items = sessionObjects.filter { !it.isReference }.map { it to false } +
            committedObjects.filter { !it.isReference }.map { it to true }
        AlertDialog(
            onDismissRequest = { showObjectsList = false },
            title = { Text("Objects (${items.size})") },
            text = {
                if (items.isEmpty()) {
                    Text("None yet. Pick an object, then tap the view to place it.")
                } else {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        items.forEach { (obj, inVenue) ->
                            val label = if (obj.kind == PlacedObjectKind.MESSAGE && obj.text.isNotBlank()) obj.text else obj.kind.label
                            Text(
                                "${obj.kind.glyph} $label",
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        showObjectsList = false
                                        val tgt = if (inVenue) alignment.transformPoint(obj.position) else obj.position
                                        guideToSession(tgt, label)
                                    }
                                    .padding(vertical = 12.dp),
                            )
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
                    label = { Text("e.g. Sofa") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && pendingDestination != null,
                    onClick = {
                        naming = false
                        // The destination is set automatically: where the user stood, held steady. End the path exactly
                        // there, then convert everything recorded in the session frame with the final alignment.
                        val spot = pendingDestination!!
                        recorder.recordAnchor(spot)
                        refreshFromAnchors()
                        val track = buildTrack()
                        // The destination and its floor use the alignment for the end of the walk (the last scan).
                        val venueFromSession = track.venueFromSessionAt(recorder.progress)
                        val here = venueFromSession.transformPoint(spot)
                        val floorY = venueFromSession.transformPoint(Vec3(spot.x, pendingFloorSession, spot.z)).y
                        val spreadCm = visits.firstOrNull()?.filter?.spreadCm
                        val drift = driftCm[0].takeIf { it > 0f }
                        val startId = startMarker?.id.orEmpty()
                        commit()
                        onSave(
                            CapturedDestination(
                                name.trim(), pendingLevel, here.x, here.z, committedPath.toList(), committedObjects.toList(),
                                floorY = floorY, startMarkerId = startId, alignmentSpreadCm = spreadCm, driftCorrectedCm = drift,
                            ),
                        )
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }

    pendingRef?.let { ref ->
        var refName by remember(ref) { mutableStateOf("") }
        val quality = when {
            !ref.accepted -> "Too plain to track — aim at a more detailed, flat spot."
            ref.detail >= 55 -> "Good detail — trackable"
            else -> "Low detail — may be unreliable"
        }
        AlertDialog(
            onDismissRequest = { pendingRef = null },
            title = { Text("Save reference point") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(
                        bitmap = ref.image.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    Text(quality, fontWeight = FontWeight.Bold)
                    Text("Quality ${ref.detail}/100 · about %.1f m away".format(ref.distanceM))
                    OutlinedTextField(
                        value = refName, onValueChange = { refName = it }, singleLine = true,
                        label = { Text("Name, e.g. Lobby poster") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = refName.isNotBlank() && ref.accepted,
                    onClick = {
                        onSaveReferencePoint(
                            CapturedReferencePoint(
                                name = refName.trim(), image = ref.image, physicalWidth = ref.physicalWidth,
                                level = ref.level, x = ref.x, y = ref.y, z = ref.z, facing = ref.facing,
                                mounting = Venue.MarkerMounting.WALL,
                            ),
                        )
                        sessionRefPins.add(RefPin(ref.x, ref.y, ref.z, refName.trim()))
                        greenFlash = "Reference point saved"
                        pendingRef = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pendingRef = null }) { Text("Retake") } },
        )
    }
}

/** The bar of objects a tap can place; the selected one is highlighted. */
@Composable
private fun ObjectBar(selected: PlacedObjectKind, onSelect: (PlacedObjectKind) -> Unit) {
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
                    .clickable { onSelect(kind) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}
