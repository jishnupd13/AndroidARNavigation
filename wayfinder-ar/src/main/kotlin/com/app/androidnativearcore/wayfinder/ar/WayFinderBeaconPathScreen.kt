package com.app.androidnativearcore.wayfinder.ar

import android.os.SystemClock
import android.util.Log
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.androidnativearcore.wayfinder.core.BeaconFix
import com.app.androidnativearcore.wayfinder.core.BeaconPosition
import com.app.androidnativearcore.wayfinder.core.BeaconDetector
import com.app.androidnativearcore.wayfinder.core.SignalLevel
import com.app.androidnativearcore.wayfinder.core.BeaconReading
import com.app.androidnativearcore.wayfinder.core.isCalibrated
import com.app.androidnativearcore.wayfinder.core.HeardBeacon
import com.app.androidnativearcore.wayfinder.core.ProximityTrend
import com.app.androidnativearcore.wayfinder.core.RegisteredBeacon
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.detectRssi
import com.app.androidnativearcore.wayfinder.core.signalLevel
import com.app.androidnativearcore.wayfinder.core.thresholdCalibrated
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.matches
import com.app.androidnativearcore.wayfinder.core.position
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

private val panel = Color(0xCC1C1C1E)
private val closerGreen = Color(0xFF34C759)
private val furtherOrange = Color(0xFFFF9F0A)

/**
 * "Beacon path": the camera with Bluetooth guidance, without a reference point or a recorded walk. The phone finds
 * the visitor from the signal strength of the registered beacons ([fix], [position] and [heard], worked out by the
 * caller), the visitor picks a beacon to go to, and the screen says whether they are getting closer and when they
 * arrive.
 *
 * "Closer" comes from the target beacon's own signal getting stronger. When both the visitor's position and the
 * target's are known (beacons registered with X and Y), the straight-line distance is shown and used instead.
 *
 * A beacon's icon follows three rules:
 * 1. Detection by signal strength: a beacon merely heard is not detected; its filtered signal must reach its 0.5 m
 *    threshold (calibrated at 0.5 m by the admin) and hold there ([BeaconDetector]).
 * 2. The icon shows only while the beacon is detected, within about 0.5 m: never for a weak or medium signal. It
 *    hides when the visitor walks away.
 * 3. Turning around doesn't move or lose it: detection stays while the phone stays put (the camera tracks it), and
 *    the icon is pinned to the room with an AR anchor. Detected again from about the same spot in this session, it
 *    goes to the same place, once. Its first place is where the camera points within 0.5 m (the desk the beacon
 *    lies on), else just in front of the camera: Bluetooth can't tell direction, so the visitor faces the beacon
 *    when first detecting it. Across sessions the place isn't kept: that needs a registered position in a frame the
 *    camera can find again (cloud anchors or reference points).
 */
@Composable
fun WayFinderBeaconPathScreen(
    venue: Venue,
    beacons: List<RegisteredBeacon>,
    fix: BeaconFix?,
    position: BeaconPosition?,
    /** Every registered beacon heard now, strongest first. */
    heard: List<HeardBeacon>,
    /** Subscribes to every advertisement heard (unsmoothed) and returns how to stop: logged, for tuning. */
    listenToReadings: ((BeaconReading) -> Unit) -> () -> Unit,
    /** Why the beacons can't be heard ("Turn on Bluetooth…"), or null. */
    problem: String?,
    /** Fixes [problem] (asks for the permission, turns Bluetooth on…); null when it can't be fixed here. */
    onFixProblem: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Debug builds: a panel to check the beacon rules on a real device, case by case ([BeaconTestPanel]). */
    testPanel: Boolean = false,
) {
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    val target = beacons.firstOrNull { it.beaconId == targetId }
    // The beacons detected within 0.5 m (rule 1–2): arriving means detecting the target, not merely hearing it best.
    var detected by remember { mutableStateOf(emptySet<String>()) }
    val arrived = target != null && target.beaconId in detected
    // How far the target is: a straight-line distance when both positions are known, else its signal strength.
    val distance = target?.let { t -> t.position?.let { spot -> position?.takeIf { it.floorId == t.floorId }?.position?.distanceTo(spot) } }
    val targetSignal = heard.firstOrNull { it.beacon.beaconId == targetId }
    val distanceTrend = remember(targetId) { ProximityTrend() }
    val signalTrend = remember(targetId) { ProximityTrend(threshold = ProximityTrend.THRESHOLD_DB) }
    var direction by remember(targetId) { mutableStateOf(ProximityTrend.Direction.STEADY) }
    LaunchedEffect(heard, position, targetId) {
        val now = SystemClock.elapsedRealtime()
        direction = when {
            distance != null -> distanceTrend.update(distance, now)
            targetSignal != null -> signalTrend.update(-targetSignal.rssi.toFloat(), now)
            else -> ProximityTrend.Direction.STEADY
        }
    }
    fun floorName(id: String) = venue.level(id)?.name?.resolved() ?: id
    val hereFloor = position?.floorId ?: fix?.beacon?.floorId

    val camera = remember { FloatArray(3) } // camera position in the session, for turning the logos toward it
    val forward = remember { FloatArray(3) } // the direction the camera looks
    // The surface at the middle of the camera view (a desk, the beacon on it): x, y, z, and 1 when there is one.
    val aim = remember { FloatArray(4) }
    val frameCount = remember { IntArray(1) }
    val tracking = remember { BooleanArray(1) } // whether the camera knows where it is right now
    val view = LocalView.current
    // Names this camera session in the log (its coordinates start over each time the screen opens).
    val session = remember { java.util.UUID.randomUUID().toString().take(6) }
    // Why the camera doesn't know where it is ("too dark"…), or null: a logo can't be drawn in place until it does.
    var trackingMessage by remember { mutableStateOf<String?>("Starting the camera… move the phone slowly.") }

    // Rules 1–2: which beacons are detected within 0.5 m, by their filtered signal ([BeaconDetector]); turning around
    // on the spot doesn't lose one, walking away does. Rule 3: each beacon's icon is pinned to the room with an AR
    // anchor for this session; detected again from about the same spot, it reuses that place (turning around and
    // scanning again shows it where it was, once), instead of placing it again by the camera's direction.
    val detector = remember { BeaconDetector() }
    val logos = remember { mutableStateMapOf<String, Vec3>() }
    val placements = remember { mutableMapOf<String, Placement>() }
    val arSession = remember { arrayOfNulls<Session>(1) }
    val currentHeard by rememberUpdatedState(heard)

    // The rule tests (debug builds): each case records what the screen shows a few seconds after it is tapped.
    var testOpen by remember { mutableStateOf(false) }
    val testResults = remember { mutableStateListOf<BeaconTestResult>() }
    var testRecording by remember { mutableStateOf<BeaconTestCase?>(null) }
    var testReference by remember { mutableStateOf<Vec3?>(null) }
    val currentTarget by rememberUpdatedState(target)
    val currentFix by rememberUpdatedState(fix)
    val currentDetected by rememberUpdatedState(detected)
    val scope = rememberCoroutineScope()
    fun runTest(case: BeaconTestCase) {
        scope.launch {
            testRecording = case
            delay(TEST_RECORD_AFTER_MS)
            val beacon = currentTarget ?: currentFix?.beacon
            val snapshot = BeaconTestSnapshot(
                beacon = beacon,
                signal = beacon?.let { b -> currentHeard.firstOrNull { it.beacon.beaconId == b.beaconId } },
                detected = currentDetected,
                icon = beacon?.let { logos[it.beaconId] },
                reference = testReference,
            )
            val (result, reference) = judge(case, snapshot)
            testReference = reference
            testResults += result
            Log.i(TEST_TAG, "result $session ${case.name} ${if (result.passed) "PASS" else "FAIL"} ${result.detail}")
            testRecording = null
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(DETECT_EVERY_MS)
            val now = SystemClock.elapsedRealtime()
            val phone = if (tracking[0]) Vec3(camera[0], camera[1], camera[2]) else null
            val nowDetected = detector.update(currentHeard, phone, now)
            if (nowDetected != detected) {
                for (id in detected - nowDetected) Log.i(SPOT_TAG, "lost $session $id")
                detected = nowDetected
            }
            for (id in logos.keys.toList()) if (id !in nowDetected) logos.remove(id)
            if (phone == null) continue // an icon can't be put in place until the camera knows where it is
            for (id in nowDetected) {
                if (id in logos) continue
                val signal = currentHeard.firstOrNull { it.beacon.beaconId == id }
                val earlier = placements[id]?.takeIf { hypot(it.from.x - phone.x, it.from.z - phone.z) <= SAME_SPOT_M }
                val spot = if (earlier != null) {
                    earlier.spot
                } else {
                    val surface = if (aim[3] == 1f) Vec3(aim[0], aim[1], aim[2]) else null
                    val placed = logoSpot(phone, Vec3(forward[0], forward[1], forward[2]), surface)
                    placements.remove(id)?.anchor?.detach()
                    val anchor = arSession[0]?.let { s -> runCatching { s.createAnchor(Pose.makeTranslation(placed.x, placed.y, placed.z)) }.getOrNull() }
                    placements[id] = Placement(anchor, placed, phone)
                    placed
                }
                logos[id] = spot
                Log.i(
                    SPOT_TAG,
                    "detected $session $id rssi=${signal?.rssi} threshold=${signal?.detectRssi}${if (signal?.thresholdCalibrated == true) "" else " (projected)"}" +
                        " icon %.2f %.2f %.2f %s".format(spot.x, spot.y, spot.z, if (earlier != null) "same place as before" else "placed"),
                )
            }
        }
    }
    val currentBeacons by rememberUpdatedState(beacons)
    DisposableEffect(Unit) {
        val stop = listenToReadings { reading ->
            val beacon = currentBeacons.firstOrNull { it.matches(reading.identity, reading.address) } ?: return@listenToReadings
            // Every reading with where the phone was, to tune distances with real walks (adb logcat -s WFBeaconSpot).
            Log.d(
                SPOT_TAG,
                "reading $session ${beacon.beaconId} ${reading.timeMillis} %.3f %.3f %.3f ${reading.rssi} ${if (tracking[0]) "tracked" else "untracked"}"
                    .format(camera[0], camera[1], camera[2]),
            )
        }
        onDispose { stop() }
    }
    var toast by remember { mutableStateOf<Toast?>(null) }
    fun showToast(message: String, durationMs: Long = 2_500) {
        toast = Toast((toast?.id ?: 0) + 1, message, durationMs)
    }

    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            placements.values.forEach { it.anchor?.detach() }
            placements.clear()
        }
    }
    BackHandler(onBack = onClose)

    Box(modifier.fillMaxSize().background(Color.Black)) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            sessionConfiguration = { _, config ->
                config.focusMode = Config.FocusMode.AUTO
                // Flat surfaces (a desk) say where the camera points even when plain and white; they are found but not
                // drawn (no dots).
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                // Depth fails on some phones (it floods the log and costs CPU), and isn't needed.
                config.depthMode = Config.DepthMode.DISABLED
            },
            planeRenderer = false,
            onSessionUpdated = { session, frame ->
                arSession[0] = session
                val pose = frame.camera.pose
                camera[0] = pose.tx(); camera[1] = pose.ty(); camera[2] = pose.tz()
                // The camera looks along its −z axis.
                val z = pose.zAxis
                forward[0] = -z[0]; forward[1] = -z[1]; forward[2] = -z[2]
                tracking[0] = frame.camera.trackingState == TrackingState.TRACKING
                // A few times a second: the surface in the middle of the view, from the points the camera tracks, or
                // where the line of sight comes down to a desk it found.
                if (tracking[0] && frameCount[0]++ % 10 == 0) {
                    val hit = frame.hitTest(view.width / 2f, view.height / 2f).firstOrNull { result ->
                        result.distance in MIN_SURFACE_M..MAX_SURFACE_M && when (val trackable = result.trackable) {
                            is Plane -> trackable.isPoseInPolygon(result.hitPose)
                            is Point -> true
                            else -> false
                        }
                    }
                    val spot = hit?.hitPose?.let { Vec3(it.tx(), it.ty(), it.tz()) }
                        ?: surfaceAhead(session.getAllTrackables(Plane::class.java), Vec3(camera[0], camera[1], camera[2]), Vec3(forward[0], forward[1], forward[2]))
                    if (spot != null) {
                        aim[0] = spot.x; aim[1] = spot.y; aim[2] = spot.z; aim[3] = 1f
                    } else {
                        aim[3] = 0f
                    }
                }
                // The camera corrects its map of the room as it learns it: the anchors, and the icons on them, move along.
                if (tracking[0] && frameCount[0] % 10 == 1) {
                    for ((id, placement) in placements) {
                        val anchor = placement.anchor ?: continue
                        if (anchor.trackingState != TrackingState.TRACKING) continue
                        val p = anchor.pose
                        val spot = Vec3(p.tx(), p.ty(), p.tz())
                        if (spot.distanceTo(placement.spot) > 0.01f) {
                            placement.spot = spot
                            if (id in logos) logos[id] = spot
                        }
                    }
                }
                val message = ArMarkerTracker.trackingMessage(frame)
                if (message != trackingMessage) trackingMessage = message
            },
            onSessionFailed = { error -> showToast("AR isn't available: ${error.message}", 8_000) },
        ) {
            val cameraPosition = { Position(camera[0], camera[1], camera[2]) }
            for ((id, spot) in logos) {
                val beacon = beacons.firstOrNull { it.beaconId == id } ?: continue
                key(id) { FloatingBeaconLogo(beacon.name, spot, cameraPosition) }
            }
        }

        // Where the visitor is.
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            when {
                problem != null -> {
                    // Something to turn on, or nothing the visitor can do here (no beacons, no Bluetooth LE).
                    val title = if (onFixProblem != null) "Bluetooth is needed" else "Beacons aren't available"
                    GuidanceBannerView(GuidanceBanner(title, "📶", problem))
                    if (onFixProblem != null) Button(onClick = onFixProblem, modifier = Modifier.padding(top = 8.dp)) { Text("Turn on") }
                }
                fix == null -> GuidanceBannerView(
                    GuidanceBanner(
                        "Looking for beacons…", "📶",
                        "Hold the phone and walk slowly. Beacons are found automatically by their signal.",
                    ),
                )
                else -> {
                    val at = beacons.firstOrNull { it.beaconId in detected && (target == null || it.beaconId == target.beaconId) }
                        ?: beacons.firstOrNull { it.beaconId in detected }
                    val level = heard.firstOrNull { it.beacon.beaconId == fix.beacon.beaconId }?.signalLevel
                    GuidanceBannerView(
                        if (at != null) {
                            GuidanceBanner("At ${at.name}", "📍", "Within 0.5 m · ${floorName(at.floorId)}")
                        } else {
                            GuidanceBanner(
                                "Nearest beacon signal: ${fix.beacon.name}", "📶",
                                listOfNotNull(floorName(fix.beacon.floorId), level?.let(::levelWord), "${fix.rssi} dBm").joinToString(" · "),
                            )
                        },
                    )
                }
            }
            // The beacon being walked to, else the nearest: whether its logo shows, and how to make it show.
            (target ?: fix?.beacon)?.takeIf { !arrived || it.beaconId !in logos }?.let { beacon ->
                val signal = heard.firstOrNull { it.beacon.beaconId == beacon.beaconId }
                val approximate = when {
                    signal == null || signal.thresholdCalibrated -> ""
                    signal.isCalibrated -> " (from the 1 m calibration: approximate)"
                    else -> " (not calibrated: approximate; calibrate at 0.5 m for accuracy)"
                }
                val status = when {
                    beacon.beaconId in logos -> "✓ ${beacon.name}: within 0.5 m$approximate"
                    beacon.beaconId in detected && trackingMessage != null -> "📷 $trackingMessage Hold the phone up, facing the room."
                    signal != null -> "📍 ${beacon.name}: ${levelWord(signal.signalLevel)} · its icon shows only within 0.5 m$approximate"
                    else -> null
                } ?: return@let
                Text(
                    status,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp).background(panel, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        // Where to go, and how it is going.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ToastHost(toast)
            if (problem == null && beacons.isNotEmpty()) {
                if (target == null) {
                    BeaconPicker(beacons, detected, position, heard, ::floorName, onPick = { targetId = it.beaconId })
                } else if (!arrived) {
                    GoingTo(
                        target, distance, targetSignal, direction, floorName(target.floorId),
                        sameFloor = hereFloor == null || hereFloor == target.floorId, onChange = { targetId = null },
                    )
                } else {
                    Arrived(target, floorName(target.floorId), shown = target.beaconId in logos, onDone = { targetId = null })
                }
            }
            if (testPanel && testOpen) {
                BeaconTestPanel(
                    results = testResults,
                    recording = testRecording,
                    onRun = ::runTest,
                    onClear = {
                        testResults.clear()
                        testReference = null
                    },
                )
            }
            Box(Modifier.fillMaxWidth()) {
                if (testPanel) {
                    CircleButton(
                        glyph = "🧪",
                        description = "Beacon rule tests",
                        onClick = { testOpen = !testOpen },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
                CircleButton(glyph = "✕", description = "Close: back to the destination list", onClick = onClose)
            }
        }
    }
}

/** How often (ms) the detections are updated. */
private const val DETECT_EVERY_MS = 500L

/** Detected again within this distance (meters) of where its icon was placed from: the icon goes to the same place. */
private const val SAME_SPOT_M = 1f

/** Where a beacon's icon was put in this session: its AR anchor, the spot (kept up with the anchor), and from where. */
private class Placement(val anchor: Anchor?, var spot: Vec3, val from: Vec3)

/** A signal level in words. Only a strong signal (within 0.5 m) shows the icon. */
private fun levelWord(level: SignalLevel) = when (level) {
    SignalLevel.STRONG -> "strong signal"
    SignalLevel.MEDIUM -> "medium signal"
    SignalLevel.WEAK -> "weak signal"
}

/** A detected beacon is within this distance (meters) of the phone: its icon is never put farther. */
private const val ICON_RADIUS_M = 0.5f

/** With no surface to put the logo on, it is drawn this far (meters) in front of the camera, a bit below it. */
private const val LOGO_AHEAD_M = 0.45f
private const val LOGO_BELOW_M = 0.45f

/** A surface seen in the middle of the view counts when it is this near (meters, along the line of sight). */
private const val MIN_SURFACE_M = 0.15f
private const val MAX_SURFACE_M = 2.5f

/** The rule tests record this long (ms) after a case is tapped: time to hold still. */
private const val TEST_RECORD_AFTER_MS = 3_000L

/** Log tag for the rule tests' results. */
private const val TEST_TAG = "WFBeaconTest"

/** Log tag for the beacon logos: readings with phone positions, and when each logo shows and hides. */
private const val SPOT_TAG = "WFBeaconSpot"

/**
 * Where to draw a logo for a beacon within 0.5 m of the phone at [phone]: on the [surface] the camera points at when
 * that is within the radius (the desk the beacon lies on), else [LOGO_AHEAD_M] in front of the camera.
 */
private fun logoSpot(phone: Vec3, forward: Vec3, surface: Vec3?): Vec3 {
    if (surface != null && hypot(surface.x - phone.x, surface.z - phone.z) <= ICON_RADIUS_M) return surface
    val flat = hypot(forward.x, forward.z).coerceAtLeast(1e-3f)
    return Vec3(phone.x + forward.x / flat * LOGO_AHEAD_M, phone.y - LOGO_BELOW_M, phone.z + forward.z / flat * LOGO_AHEAD_M)
}

/**
 * Where the line of sight from [eye] along [forward] comes down to a flat surface the camera found (a desk, the
 * floor), within [MAX_SURFACE_M]: the first one it meets. The surface's found area is extended by [PLANE_MARGIN_M],
 * since a plain desk is found in patches; the beacon on it may be just past the patch.
 */
private fun surfaceAhead(planes: Collection<Plane>, eye: Vec3, forward: Vec3): Vec3? {
    if (forward.y > -0.05f) return null
    return planes.asSequence()
        .filter { it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.subsumedBy == null }
        .mapNotNull { plane ->
            val center = plane.centerPose
            val t = (center.ty() - eye.y) / forward.y
            if (t !in MIN_SURFACE_M..MAX_SURFACE_M) return@mapNotNull null
            val at = Vec3(eye.x + forward.x * t, center.ty(), eye.z + forward.z * t)
            val reach = maxOf(plane.extentX, plane.extentZ) / 2f + PLANE_MARGIN_M
            if (hypot(at.x - center.tx(), at.z - center.tz()) > reach) null else t to at
        }
        .minByOrNull { it.first }
        ?.second
}

/** How far past a found surface's patch (meters) the line of sight may meet it. */
private const val PLANE_MARGIN_M = 0.5f

/** Arrived at [target]: the camera stays visible, with the beacon's logo once the phone is within 0.5 m of it. */
@Composable
private fun Arrived(target: RegisteredBeacon, floor: String, shown: Boolean, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("✓ You've arrived", color = closerGreen, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("${target.name} · $floor", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (shown) "Its logo is shown: you're within 0.5 m of it."
            else "Come within 0.5 m of it and its logo appears.",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 15.sp,
        )
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = closerGreen),
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Done") }
    }
}

/** The registered beacons to choose from, the ones heard first (strongest first); the one the visitor is at is marked. */
@Composable
private fun BeaconPicker(
    beacons: List<RegisteredBeacon>,
    detected: Set<String>,
    position: BeaconPosition?,
    heard: List<HeardBeacon>,
    floorName: (String) -> String,
    onPick: (RegisteredBeacon) -> Unit,
) {
    val signals = heard.associateBy { it.beacon.beaconId }
    // Nearest first, each beacon judged by its own strength (calibrated beacons compare fairly with loud ones).
    val sorted = beacons.sortedWith(compareBy({ signals[it.beaconId] == null }, { -(signals[it.beaconId]?.nearness ?: -200) }, { it.name }))
    Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Where do you want to go?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.heightIn(max = 240.dp)) {
            items(sorted, key = { it.beaconId }) { beacon ->
                val here = beacon.beaconId in detected
                val meters = beacon.position?.let { spot -> position?.takeIf { it.floorId == beacon.floorId }?.position?.distanceTo(spot) }
                val signal = signals[beacon.beaconId]
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(beacon) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(beacon.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(floorName(beacon.floorId), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    }
                    Text(
                        when {
                            here -> "within 0.5 m"
                            meters != null -> distanceText(meters)
                            signal != null -> calibratedDistance(signal)?.let(::distanceText) ?: levelWord(signal.signalLevel)
                            else -> "not heard"
                        },
                        color = if (here) closerGreen else Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

/** On the way to [target]: how far it is (in meters, or by its signal) and whether the visitor is getting closer. */
@Composable
private fun GoingTo(
    target: RegisteredBeacon,
    distance: Float?,
    signal: HeardBeacon?,
    direction: ProximityTrend.Direction,
    floor: String,
    sameFloor: Boolean,
    onChange: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Going to ${target.name}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onChange) { Text("Change") }
        }
        when {
            !sameFloor -> Text("It's on $floor. Go to that floor first.", color = Color.White, fontSize = 16.sp)
            distance == null && signal == null -> {
                Text("Not heard yet", color = furtherOrange, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Walk around slowly: it's picked up automatically once you're in range.", color = Color.White, fontSize = 15.sp)
            }
            else -> {
                if (distance != null) {
                    Text(distanceText(distance) + " away", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                } else if (signal != null) {
                    val meters = calibratedDistance(signal)
                    if (meters != null) {
                        Text(distanceText(meters) + " away", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("From its signal (${signal.rssi} dBm), as calibrated", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                    } else {
                        Text(levelWord(signal.signalLevel).replaceFirstChar { it.uppercase() }, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("${signal.rssi} dBm: the stronger the signal, the closer you are", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                    }
                }
                val (text, color) = when (direction) {
                    ProximityTrend.Direction.CLOSER -> "⬆ Getting closer: keep going" to closerGreen
                    ProximityTrend.Direction.FURTHER -> "⬇ Moving away: turn around" to furtherOrange
                    ProximityTrend.Direction.STEADY -> "• Walk slowly and watch this" to Color.White.copy(alpha = 0.75f)
                }
                Text(text, color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun distanceText(meters: Float) = if (meters < 1f) "less than 1 m" else "about %.0f m".format(meters)

/** The distance from a beacon's signal when the admin calibrated it (then it means something), else null. */
private fun calibratedDistance(signal: HeardBeacon): Float? = signal.takeIf { it.beacon.rssiAt1m != null }?.roughDistanceMeters
