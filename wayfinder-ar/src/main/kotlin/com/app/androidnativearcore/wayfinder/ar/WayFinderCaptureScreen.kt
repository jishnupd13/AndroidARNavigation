package com.app.androidnativearcore.wayfinder.ar

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.androidnativearcore.wayfinder.core.AlignmentFilter
import com.app.androidnativearcore.wayfinder.core.MarkerAlignment
import com.app.androidnativearcore.wayfinder.core.Mat4
import com.app.androidnativearcore.wayfinder.core.PlacedObject
import com.app.androidnativearcore.wayfinder.core.PlacedObjectKind
import com.app.androidnativearcore.wayfinder.core.SessionPathRecorder
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.VenuePackage
import com.app.androidnativearcore.wayfinder.core.WalkedPoint
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
)

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

/** The reference point is shown as this object, where the user stood when the path started. */
private val REFERENCE_KIND = PlacedObjectKind.STAR

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
 * 2. The **reference point** appears by itself: an object at the spot the user scanned from. They walk to the
 *    destination, picking objects from the bar at the bottom and **tapping the screen** where each one goes.
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
    // ARCore keeps anchors glued to the real world as its tracking improves; objects follow their anchors.
    val anchors = remember(pkg) { HashMap<String, Anchor>() }
    val camera = remember { FloatArray(3) }       // camera position in the session
    val user = remember { LiveUser() }
    val pendingTap = remember { FloatArray(3) }   // x, y, and 1 when a tap waits to be handled in the next frame
    var lostSince by remember { mutableStateOf<Long?>(null) }
    val referenceAdded = remember(pkg) { BooleanArray(1) } // the automatic reference object exists
    val recent = remember(pkg) { ArrayDeque<Vec3>() }       // the phone's latest positions, to tell when it is held steady

    // --- What the user sees (only things that change rarely) ---
    var lockedIn by remember { mutableStateOf(false) }
    var lockProgress by remember { mutableStateOf(0) }
    var levelName by remember { mutableStateOf("") }
    var recordedSummary by remember { mutableStateOf("") }
    var canSave by remember { mutableStateOf(false) }
    var steady by remember { mutableStateOf(false) }
    var pendingDestination by remember { mutableStateOf<Vec3?>(null) } // session position chosen when Save was tapped
    var pendingLevel by remember { mutableStateOf("") }
    var trackingMessage by remember { mutableStateOf<String?>("Starting the camera… move the phone slowly.") }
    var toast by remember { mutableStateOf<Toast?>(null) }
    var naming by remember { mutableStateOf(false) }
    var selectedKind by remember { mutableStateOf(PlacedObjectKind.CUP) } // what the next tap places
    var messageText by remember { mutableStateOf("") }

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

    /**
     * Converts what was recorded in the session frame to venue coordinates with the current alignment and keeps it.
     * Done when saving, and when tracking is lost (after which the session frame can't be trusted any more).
     */
    fun commit() {
        val marker = startMarker ?: return
        val venueFromSession = filter.alignment.inverse()
        val raw = recorder.points
        if (raw.isNotEmpty()) {
            val converted = raw.toWalkedPoints(venue, venueFromSession, marker.level)
            if (committedPath.isEmpty()) {
                // Start the path at the sign itself, then straight to where the user first stood: they just looked at
                // the sign, so the way between is clear. This lets routes begin at the sign later.
                val first = converted.first()
                val floor = venue.level(first.level)?.elevation ?: 0f
                val lead = straightWalk(Vec3(marker.x, floor, marker.z), Vec3(first.x, floor, first.z), first.run, first.level, floor)
                    .let { points -> points.mapIndexed { i, p -> p.copy(sequence = i - points.size) } }
                committedPath += lead
            }
            committedPath += converted
            recorder.clear()
        }
        refreshFromAnchors()
        val yaw = yawDegrees(filter.alignment)
        committedObjects += sessionObjects.map { it.toVenue(venue, venueFromSession, yaw, marker.level) }
        sessionObjects.clear()
        anchors.values.forEach { it.detach() }
        anchors.clear()
    }

    /** What a tap does: puts the selected object (or the destination pin) on the surface under the finger. */
    fun handleTap(frame: com.google.ar.core.Frame, session: com.google.ar.core.Session, x: Float, y: Float) {
        if (selectedKind == PlacedObjectKind.MESSAGE && messageText.isBlank()) {
            showToast("Type your message first, then tap where it goes.")
            return
        }
        val hit = markerTracker.surfaceAt(frame, session, x, y, MAX_OBJECT_DISTANCE_M)
        if (hit == null) {
            showToast("No surface found there. Move the phone slowly so ARCore can scan it, then tap again.", 2_500)
            return
        }
        val cameraPosition = Vec3(camera[0], camera[1], camera[2])
        val spot = spreadObjectPosition(hit.position, sessionObjects.map { it.position }, MIN_OBJECT_SEPARATION_M)
        val id = "object-" + java.util.UUID.randomUUID()
        // Anchor to the tapped surface itself; only an object nudged off its spot gets a free-standing anchor.
        anchors[id] = if (spot == hit.position) hit.createAnchor()
        else session.createAnchor(Pose(floatArrayOf(spot.x, spot.y, spot.z), floatArrayOf(0f, 0f, 0f, 1f)))
        // Turn the object's front toward the user who is placing it.
        val yaw = Math.toDegrees(kotlin.math.atan2((cameraPosition.x - spot.x).toDouble(), (cameraPosition.z - spot.z).toDouble())).toFloat()
        sessionObjects += PlacedObject(
            id = id, kind = selectedKind, level = "", x = spot.x, y = spot.y, z = spot.z, yawDegrees = yaw,
            text = if (selectedKind == PlacedObjectKind.MESSAGE) messageText.trim() else "",
            createdAtMillis = System.currentTimeMillis(),
        )
        showToast("${selectedKind.label} added", 1_500)
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
                        lockedIn = false
                        lockProgress = 0
                        user.valid = false
                        recent.clear()
                    }
                } else {
                    lostSince = null
                }

                val interval = if (lockedIn) ArMarkerTracker.REDETECT_INTERVAL_MS else LOCK_IN_INTERVAL_MS
                for (sighting in markerTracker.sightings(frame, now, interval)) {
                    // The first sign aligns; later sightings are blended in by distance (close-ups count more).
                    val candidate = MarkerAlignment.alignment(sighting.markerInSession, MarkerAlignment.transform(sighting.marker))
                    if (filter.update(candidate, sighting.distanceMeters)) alignment = filter.alignment
                    if (startMarker == null) startMarker = sighting.marker
                }
                lockProgress = filter.closeSightings.coerceAtMost(AlignmentFilter.LOCK_IN_SIGHTINGS)
                lockedIn = filter.isLockedIn

                val marker = startMarker
                if (marker != null && lockedIn && message == null) {
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

                    recorder.record(cameraSession)
                    recent.addLast(cameraSession)
                    while (recent.size > STEADY_FRAMES) recent.removeFirst()
                    val mean = averageOf(recent)
                    val isSteady = mean != null && recent.size >= STEADY_FRAMES &&
                        recent.all { hypot(it.x - mean.x, it.z - mean.z) <= STEADY_RADIUS_M }
                    if (isSteady != steady) steady = isSteady
                    if (!referenceAdded[0]) {
                        // The reference point: an object on the floor where the user scanned from.
                        referenceAdded[0] = true
                        val spot = Vec3(cameraSession.x, cameraSession.y - PHONE_HEIGHT_M, cameraSession.z)
                        val id = "reference-" + java.util.UUID.randomUUID()
                        anchors[id] = session.createAnchor(Pose(floatArrayOf(spot.x, spot.y, spot.z), floatArrayOf(0f, 0f, 0f, 1f)))
                        sessionObjects += PlacedObject(
                            id = id, kind = REFERENCE_KIND, level = "", x = spot.x, y = spot.y, z = spot.z,
                            createdAtMillis = System.currentTimeMillis(), isReference = true,
                        )
                    }
                    refreshFromAnchors()
                    val length = committedLength() + recorder.lengthMeters
                    canSave = length >= MIN_PATH_LENGTH_M
                    recordedSummary = "Recording · ${committedPath.size + recorder.points.size} points · %.0f m".format(length)

                    if (pendingTap[2] == 1f) {
                        pendingTap[2] = 0f
                        handleTap(frame, session, pendingTap[0], pendingTap[1])
                    }
                } else {
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
        }

        // Taps on the camera view place the selected object; the controls below sit on top and take their own taps.
        if (lockedIn) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { offset ->
                        pendingTap[0] = offset.x; pendingTap[1] = offset.y; pendingTap[2] = 1f
                    }
                },
            )
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            when {
                startMarker == null -> AlignmentPromptView(
                    trackingMessage ?: "Look for a sign like these near entrances, elevators and escalators. " +
                        "Scanning it starts recording your walk.",
                    signs,
                )
                !lockedIn -> GuidanceBannerView(
                    GuidanceBanner(
                        instruction = "Locking onto the sign…",
                        glyph = "◎",
                        detail = trackingMessage
                            ?: "Hold the phone steady, 1–3 m from the sign ($lockProgress/${AlignmentFilter.LOCK_IN_SIGHTINGS})",
                    ),
                )
                else -> {
                    val objectCount = committedObjects.count { !it.isReference } + sessionObjects.count { !it.isReference }
                    GuidanceBannerView(
                        GuidanceBanner(
                            instruction = if (canSave) "Walk to the destination, then save it" else "Walk to where the destination is",
                            glyph = "●",
                            detail = trackingMessage ?: listOfNotNull(
                                levelName.ifEmpty { null }, recordedSummary.ifEmpty { null },
                                if (objectCount > 0) "$objectCount object${if (objectCount == 1) "" else "s"}" else null,
                            ).joinToString(" · "),
                        ),
                    )
                }
            }
        }

        Row(Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(16.dp)) {
            CircleButton("✕", "Cancel", onClick = onCancel)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToastHost(toast)
            if (lockedIn) {
                if (selectedKind == PlacedObjectKind.MESSAGE) {
                    OutlinedTextField(
                        value = messageText, onValueChange = { messageText = it.take(40) }, singleLine = true,
                        label = { Text("Message, e.g. Turn left here") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Color(0xE6FFFFFF), RoundedCornerShape(4.dp)),
                    )
                }
                ObjectBar(selected = selectedKind, onSelect = { selectedKind = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hasUserObjects) {
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
                    Button(
                        enabled = canSave && steady && trackingMessage == null,
                        onClick = {
                            // The destination is where the phone has been held, averaged over the last moment, so
                            // hand sway can't move it. It is fixed now, before the name is typed.
                            pendingDestination = averageOf(recent) ?: user.session
                            pendingLevel = user.level
                            naming = true
                        },
                    ) {
                        Text(if (canSave && !steady) "Hold steady…" else "Save destination here")
                    }
                }
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
                    enabled = name.isNotBlank() && pendingDestination != null,
                    onClick = {
                        naming = false
                        // The destination is set automatically: where the user stood, held steady. End the path exactly
                        // there, then convert everything recorded in the session frame with the final alignment.
                        val spot = pendingDestination!!
                        recorder.recordAnchor(spot)
                        refreshFromAnchors()
                        val here = filter.alignment.inverse().transformPoint(spot)
                        commit()
                        onSave(
                            CapturedDestination(
                                name.trim(), pendingLevel, here.x, here.z, committedPath.toList(), committedObjects.toList(),
                            ),
                        )
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
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
