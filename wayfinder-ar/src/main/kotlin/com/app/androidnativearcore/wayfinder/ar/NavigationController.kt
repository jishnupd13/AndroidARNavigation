package com.app.androidnativearcore.wayfinder.ar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.app.androidnativearcore.wayfinder.core.AlignmentFilter
import com.app.androidnativearcore.wayfinder.core.ConnectionRoutePlanner
import com.app.androidnativearcore.wayfinder.core.ConnectionRouteResult
import com.app.androidnativearcore.wayfinder.core.CustomDestination
import com.app.androidnativearcore.wayfinder.core.PlacedObject
import com.app.androidnativearcore.wayfinder.core.REFERENCE_CATEGORY
import com.app.androidnativearcore.wayfinder.core.RouteEndpoint
import com.app.androidnativearcore.wayfinder.core.followsWalkedPaths
import com.app.androidnativearcore.wayfinder.core.MarkerAlignment
import com.app.androidnativearcore.wayfinder.core.Mat4
import com.app.androidnativearcore.wayfinder.core.NavigationProgress
import com.app.androidnativearcore.wayfinder.core.NavigationTracker
import com.app.androidnativearcore.wayfinder.core.Maneuver
import com.app.androidnativearcore.wayfinder.core.Route
import com.app.androidnativearcore.wayfinder.core.RoutePlanner
import com.app.androidnativearcore.wayfinder.core.RoutingError
import com.app.androidnativearcore.wayfinder.core.RouteProfile
import com.app.androidnativearcore.wayfinder.core.Vec2
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.VenuePackage
import com.app.androidnativearcore.wayfinder.core.WalkedPoint

/** Something a user reported from the navigation screen ("route blocked", "marker missing"…). */
data class NavigationFeedback(
    val reason: Reason,
    val venueID: String,
    val destinationID: String,
    /** The user's level and position in venue coordinates, when the session was aligned. */
    val level: String?,
    val position: Vec3?,
    val timeMillis: Long,
) {
    enum class Reason(val label: String) {
        ROUTE_BLOCKED("Route blocked"),
        MARKER_MISSING("AR sign missing or damaged"),
        WRONG_DIRECTIONS("Directions are wrong"),
        OTHER("Other problem"),
    }
}

/** Events from the navigation screen. All methods are optional. */
interface WayFinderNavigationListener {
    fun onAligned(marker: Venue.Marker) {}
    fun onRoutePlanned(route: Route, rerouted: Boolean) {}
    fun onArrived() {}
    fun onFeedback(feedback: NavigationFeedback) {}
    /** The user tapped Done or Close. Finish the screen here. */
    fun onFinished() {}
}

/** Banner shown at the top: next instruction, its glyph, and distances. */
data class GuidanceBanner(val instruction: String, val glyph: String, val detail: String)

data class Toast(val id: Int, val message: String, val durationMs: Long)

data class ArrivalInfo(val destinationName: String, val description: String?)

/** Everything the screen draws. Immutable snapshots, replaced on change. */
data class NavigationUiState(
    val isAligned: Boolean = false,
    /** Message of the "point your camera at an AR sign" prompt; null once aligned. */
    val alignmentPrompt: String? = null,
    val banner: GuidanceBanner? = null,
    val toast: Toast? = null,
    val route: Route? = null,
    /** Level whose part of the route is currently drawn. */
    val renderedLevel: String? = null,
    val user: UserPose? = null,
    val arrival: ArrivalInfo? = null,
    /** One-line diagnostics (tracking, alignment, route) shown when the screen's debug flag is on. */
    val debug: String = "",
)

/** The user in venue coordinates; [forward] is the horizontal-ish direction the camera looks. */
data class UserPose(val position: Vec3, val forward: Vec3)

/**
 * The logic of the iOS `WayFinderNavigationViewController`, independent of ARCore and of any UI so it can be
 * unit-tested:
 *
 * 1. The first marker seen aligns the session with the venue: [alignment] (the venue's pose in session
 *    coordinates) is set. ARKit calls `setWorldOrigin`; ARCore can't move its origin, so the renderer puts all
 *    route content under a root node posed by [alignment], and camera poses are converted with its inverse.
 * 2. A route is planned from the user's position and updated each frame: banner, tips, off-route and arrival.
 * 3. Every later marker sighting corrects drift if it moves the alignment by more than 5 cm / 1°.
 */
class NavigationController(
    val pkg: VenuePackage,
    initialDestination: Venue.POI,
    val profile: RouteProfile,
    var listener: WayFinderNavigationListener? = null,
    private val languages: List<String>? = null,
    /** Paths people walked; destinations the user added are routed along these instead of the venue network. */
    private val walkedPoints: List<WalkedPoint> = emptyList(),
    /** The sign this destination's path was recorded from, if known; scanning another one is less accurate. */
    private val recordedFromMarkerId: String = "",
    /** Objects placed while recording: an object chosen as destination is joined to the walk it was placed from. */
    private val placedObjects: List<PlacedObject> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val venue: Venue get() = pkg.venue
    private val planner = RoutePlanner(venue)
    private var tracker: NavigationTracker? = null
    private val alignmentFilter = AlignmentFilter()

    var destination: Venue.POI by mutableStateOf(initialDestination)
        private set

    /** The most recently recognized reference point / sign. */
    var lastAlignedMarker: Venue.Marker? by mutableStateOf(null)
        private set

    /** The latest route found, for the screen: "RP1 → Conference Room: 23 m via RP2 → RP3". Empty without one. */
    var lastRouteSummary: String by mutableStateOf("")
        private set

    var uiState by mutableStateOf(NavigationUiState())
        private set

    /** The venue's pose in session coordinates (`session = alignment * venue`). Identity until aligned. */
    var alignment: Mat4 = Mat4.IDENTITY
        private set

    /** Camera position in session coordinates, for orienting billboards. */
    var cameraSessionPosition: Vec3 = Vec3.ZERO
        private set

    val route: Route? get() = uiState.route

    /** True once enough close-up sign sightings have settled the alignment (before that, nothing is drawn). */
    val isLockedIn: Boolean get() = alignmentFilter.isLockedIn

    private var isAligned = false
    private var needsRoute = false
    private var currentLevel: String? = null
    private var offRouteSince: Long? = null
    private var hasArrived = false
    private val shownTipIDs = mutableSetOf<String>()
    private var lastUpdate = Long.MIN_VALUE / 2
    private var lastPosition: Vec3? = null
    private var toastCounter = 0
    private var arrivedSince: Long? = null

    private fun resolve(text: com.app.androidnativearcore.wayfinder.core.LocalizedText) =
        if (languages == null) text.resolved() else text.resolved(languages)

    init {
        // Bootstrap alignment immediately so directions arrows render right away even before scanning a sign;
        // scanning any reference point or AR sign will still refine/re-align the session.
        isAligned = true
        currentLevel = destination.level
        needsRoute = true
    }

    private fun recordedSignPrompt(): String {
        val recorded = venue.marker(recordedFromMarkerId)?.let { resolve(it.name) } ?: recordedFromMarkerId
        return "This path was recorded from “$recorded”. Scan that sign to start: face it, 1–${AlignmentFilter.CLOSE_DISTANCE_M} m away."
    }

    // region Session events

    /** A marker was detected: [markerInSession] is the pose of the image center in session coordinates. */
    fun onMarkerDetected(
        marker: Venue.Marker,
        markerInSession: Mat4,
        distanceMeters: Float = 1f,
        cameraInSession: Vec3? = null,
        viewCosine: Float = 1f,
    ) {
        // A recorded path is only accurate relative to the sign it was recorded from. Another sign is placed in the
        // venue by its surveyed position, which for a sign hung wherever it was convenient is wrong by far more than
        // the path's own accuracy, and blending it in would pull everything out of place. So it is not used.
        val isCapturedRef = marker.id.startsWith("ref-")
        val recordedMarkerExists = recordedFromMarkerId.isNotEmpty() && venue.marker(recordedFromMarkerId) != null
        if (recordedMarkerExists && marker.id != recordedFromMarkerId && !isCapturedRef) {
            if (!alignmentFilter.isAligned) showAlignmentPrompt(recordedSignPrompt())
            return
        }
        val candidate = MarkerAlignment.alignment(markerInSession, MarkerAlignment.transform(marker))
        // Sightings are blended in by distance (close-ups count more) and only applied when they move the alignment
        // by a meaningful amount, so the scene doesn't jitter. A captured photo reference point is weighted by its real
        // distance and angle too, but locks in after two readings and without side-to-side movement.
        val changed = alignmentFilter.update(
            candidate, distanceMeters, viewCosine, if (isCapturedRef) null else cameraInSession, countAsClose = isCapturedRef,
        )
        if (changed) alignment = alignmentFilter.alignment
        android.util.Log.d(
            ACCURACY_TAG,
            "Sighting ${marker.id}: %.2f m, cos %.2f -> spread ±%.1f cm/%.1f°, %d outliers, locked=%b".format(
                distanceMeters, viewCosine, alignmentFilter.spreadCm, alignmentFilter.spreadDegrees,
                alignmentFilter.rejectedSightings, alignmentFilter.isLockedIn,
            ),
        )
        // The first sighting can come from several meters away, where a sign's pose is least precise. Wait for a few
        // close-up ones before drawing anything, so everything starts out accurately placed.
        if (!alignmentFilter.isLockedIn) {
            showAlignmentPrompt(
                "Face the sign, 1–${AlignmentFilter.CLOSE_DISTANCE_M} m away, and move the phone slowly side to side " +
                    "(${alignmentFilter.closeSightings.coerceAtMost(AlignmentFilter.LOCK_IN_SIGHTINGS)}/${AlignmentFilter.LOCK_IN_SIGHTINGS}).",
            )
            return
        }
        val isFirstAlignment = !isAligned
        if (!isFirstAlignment && !changed) return
        isAligned = true
        lastAlignedMarker = marker
        currentLevel = marker.level
        if (isFirstAlignment || uiState.route == null) {
            needsRoute = true
            uiState = uiState.copy(isAligned = true, alignmentPrompt = null)
            showToast("Matched “${resolve(marker.name)}”. Follow the arrows or pick a destination above.")

        } else {
            uiState = uiState.copy(isAligned = true)
        }
        listener?.onAligned(marker)
    }

    /** Changes the target destination dynamically within the same AR camera session without restarting tracking. */
    fun selectDestination(newDestination: Venue.POI) {
        destination = newDestination
        hasArrived = false
        arrivedSince = null
        offRouteSince = null
        val pos = lastPosition
        val lvl = currentLevel ?: newDestination.level
        if (isAligned && pos != null) {
            uiState = uiState.copy(arrival = null)
            planRoute(pos, lvl, rerouted = false)
            showToast("Navigating to “${resolve(newDestination.name)}”", 2_500)
        } else {
            needsRoute = true
            uiState = uiState.copy(route = null, arrival = null)
        }
    }

    /** Clears the arrival overlay so the user stays in the live camera view and can select another destination. */
    fun dismissArrival() {
        uiState = uiState.copy(arrival = null)
    }

    /**
     * Called every AR frame. [cameraInSession] is the camera pose in session coordinates; [trackingMessage]
     * is null while ARCore tracks normally, otherwise what to tell the user.
     */
    fun onFrame(cameraInSession: Mat4, trackingMessage: String?) {
        cameraSessionPosition = cameraInSession.translation
        val now = clock()
        updateDebug(now, trackingMessage, cameraInSession)
        // About 10 updates per second is plenty for guidance.
        if (now - lastUpdate <= 100 || hasArrived) return
        lastUpdate = now

        if (!isAligned) {
            if (trackingMessage != null) showAlignmentPrompt(trackingMessage)
            return
        }
        if (trackingMessage != null) {
            uiState = uiState.copy(banner = GuidanceBanner("Hold on…", "◎", trackingMessage))
            return
        }

        val cameraInVenue = alignment.inverse() * cameraInSession
        val position = cameraInVenue.translation
        val forward = -cameraInVenue.column(2)
        lastPosition = position
        val level = if (destination.followsWalkedPaths && destination.level.isNotEmpty()) {
            destination.level
        } else {
            venue.level(forDeviceHeight = position.y)?.id ?: currentLevel ?: destination.level
        }
        currentLevel = level
        uiState = uiState.copy(user = UserPose(position, forward))

        if (needsRoute) planRoute(position, level, rerouted = uiState.route != null)
        val route = uiState.route ?: return
        val tracker = tracker ?: return
        if (uiState.renderedLevel != level) {
            uiState = uiState.copy(renderedLevel = level, banner = uiState.banner ?: placeholderBanner())
        }

        val progress = tracker.progress(position, level)
        if (progress.hasArrived) {
            // Arrived only after staying there for a moment, so a passing glance or a small jump of the alignment
            // can't pop up "You have arrived" early.
            val since = arrivedSince ?: now.also { arrivedSince = it }
            if (now - since >= ARRIVAL_DWELL_MS) {
                arrive()
                return
            }
        } else {
            arrivedSince = null
        }
        updateBanner(progress)
        showTips(position, level)

        if (progress.isOffRoute) {
            val since = offRouteSince ?: now.also { offRouteSince = it }
            if (now - since > 3_000) {
                offRouteSince = null
                showToast("Finding a new route…", 2_000)
                planRoute(position, level, rerouted = true)
            }
        } else {
            offRouteSince = null
        }
    }

    // endregion

    // region Routing and display

    private var lastDebugAt = Long.MIN_VALUE / 2

    private fun updateDebug(now: Long, trackingMessage: String?, cameraInSession: Mat4) {
        if (now - lastDebugAt < 500) return
        lastDebugAt = now
        val route = uiState.route
        val cam = cameraInSession.translation
        val user = uiState.user?.position
        val text = "tracking=${trackingMessage ?: "OK"}\naligned=$isAligned route=${route?.nodeIDs?.size ?: "none"} level=${uiState.renderedLevel}\n" +
            "sign agreement ±%.1f cm, %.1f°\n".format(alignmentFilter.spreadCm, alignmentFilter.spreadDegrees) +
            "cam(session)=%.2f,%.2f,%.2f".format(cam.x, cam.y, cam.z) +
            (user?.let { "\nuser(venue)=%.2f,%.2f,%.2f".format(it.x, it.y, it.z) } ?: "")
        android.util.Log.i("WayFinder", text.replace("\n", " | "))
        uiState = uiState.copy(debug = text)
    }

    private fun planRoute(position: Vec3, level: String, rerouted: Boolean) {
        needsRoute = false
        try {
            val route = if (destination.followsWalkedPaths) {
                // The first route starts at the reference point the user was located at; a re-route from where they are.
                planWalkedRoute(position, level, fromReference = !rerouted)
            } else {
                planner.route(position, level, toPOI = destination.id, profile = profile)
            }
            val renderedLvl = route.levels.firstOrNull() ?: level
            tracker = NavigationTracker(route).also {
                // A destination the user saved is where they stood, measured to centimeters; 2 m is several steps early.
                it.arrivalRadius = if (destination.followsWalkedPaths) CUSTOM_ARRIVAL_RADIUS_M else 2f
            }
            arrivedSince = null
            uiState = uiState.copy(route = route, renderedLevel = renderedLvl, banner = uiState.banner ?: placeholderBanner())
            listener?.onRoutePlanned(route, rerouted)
        } catch (e: Exception) {
            val message = e.message ?: "No route found."
            android.util.Log.w(ROUTE_TAG, "No route to ${destination.id}: $message")
            if (rerouted && uiState.route != null) {
                // Off the recorded path: keep showing the route found earlier and say how to get back to it.
                uiState = uiState.copy(banner = GuidanceBanner("Go back to the path", "⚠", message))
            } else {
                // Nothing valid to show: no route is drawn rather than a line through walls.
                tracker = null
                lastRouteSummary = ""
                uiState = uiState.copy(route = null, banner = GuidanceBanner("No route found", "⚠", message))
            }
        }
    }

    /** Captured reference points, reported when a route passes them ("via RP2, RP3"). */
    private val referenceLandmarks: List<Pair<String, Vec3>> by lazy {
        venue.markers.filter { it.id.startsWith("ref-") }.map { resolve(it.name) to Vec3(it.x, it.y, it.z) }
    }

    /**
     * The shortest route along the recorded walks ([ConnectionRoutePlanner]): from the reference point the user was
     * located at ([fromReference], while they are still near it) or else from their position, to the destination.
     * With no recorded connection, this throws: a clear message is shown instead of a line drawn through walls.
     */
    private fun planWalkedRoute(position: Vec3, level: String, fromReference: Boolean): Route {
        val targetLevel = destination.level.ifEmpty { level }
        val connections = ConnectionRoutePlanner(walkedPoints, referenceLandmarks)
        val goal = destinationEndpoint(targetLevel)
        val fromPosition = RouteEndpoint("your position", position, targetLevel, POSITION_JOIN_M)
        val fromRef = lastAlignedMarker?.takeIf {
            fromReference && (it.level == targetLevel || it.level.isEmpty()) &&
                Vec2(it.x - position.x, it.z - position.z).length <= START_AT_REFERENCE_M
        }?.let { RouteEndpoint(resolve(it.name), Vec3(it.x, position.y, it.z), targetLevel, REFERENCE_JOIN_M) }

        var start = fromRef ?: fromPosition
        var result = connections.route(start, goal)
        if (fromRef != null && result is ConnectionRouteResult.Failure && result.reason == ConnectionRouteResult.Reason.START_NOT_CONNECTED) {
            // The reference point itself isn't on a recorded walk: start from where the user stands instead.
            start = fromPosition
            result = connections.route(start, goal)
        }
        return when (result) {
            is ConnectionRouteResult.Success -> {
                android.util.Log.i(
                    ROUTE_TAG,
                    "Route ${start.name} → ${goal.name}: %.1f m, %d points, via %s".format(
                        result.lengthMeters, result.points.size, result.via.ifEmpty { listOf("-") }.joinToString(" → "),
                    ),
                )
                lastRouteSummary = "%s → %s: %s".format(
                    start.name, goal.name, Instructions.distance(result.lengthMeters),
                ) + if (result.via.isEmpty()) "" else " via ${result.via.joinToString(" → ")}"
                planner.routeAlong(result.points, targetLevel, profile)
            }
            is ConnectionRouteResult.Failure -> {
                android.util.Log.w(ROUTE_TAG, "No route ${start.name} → ${goal.name}: ${result.reason}")
                throw RoutingError.Other(result.reason.message)
            }
        }
    }

    /**
     * Where the destination joins the recorded walks: an object joins the walk it was placed from (it was seen from
     * there, so the last stretch is clear), a reference point the walks that lead to it, a saved destination the walk
     * that was recorded to it.
     */
    private fun destinationEndpoint(level: String): RouteEndpoint {
        val name = resolve(destination.name)
        val position = Vec3(destination.x, destination.elevation ?: 0f, destination.z)
        fun ownWalk(id: String?) = id?.takeIf { it.isNotEmpty() && walkedPoints.any { p -> p.destinationId == it } }
        return when {
            destination.id.startsWith(OBJECT_POI_PREFIX) -> {
                val obj = placedObjects.firstOrNull { OBJECT_POI_PREFIX + it.id == destination.id }
                RouteEndpoint(name, position, level, OBJECT_JOIN_M, ownWalk(obj?.destinationId))
            }
            destination.category == REFERENCE_CATEGORY -> RouteEndpoint(name, position, level, REFERENCE_JOIN_M)
            else -> RouteEndpoint(name, position, level, DESTINATION_JOIN_M, ownWalk(destination.id))
        }
    }

    private fun placeholderBanner() = GuidanceBanner("Head along the path", "↑", "")

    private fun updateBanner(progress: NavigationProgress) {
        val maneuver = progress.nextManeuver ?: return
        val instruction = if (maneuver.kind == Maneuver.Kind.Arrive) {
            "${resolve(destination.name)} is ahead"
        } else {
            Instructions.text(maneuver, venue, languages)
        }
        val detail = "In ${Instructions.distance(progress.distanceToNextManeuver)} · " +
            "${Instructions.distance(progress.remainingDistance)} left (${Instructions.walkingTime(progress.remainingDistance)})"
        uiState = uiState.copy(banner = GuidanceBanner(instruction, Instructions.glyph(maneuver), detail))
    }

    private fun showTips(position: Vec3, level: String) {
        for (tip in venue.tips) {
            if (tip.level != level || tip.id in shownTipIDs) continue
            val distance = Vec2(position.x, position.z).distanceTo(Vec2(tip.x, tip.z))
            if (distance <= tip.radius) {
                shownTipIDs += tip.id
                showToast(resolve(tip.text), 4_000)
                break
            }
        }
    }

    private fun arrive() {
        hasArrived = true
        uiState = uiState.copy(
            banner = null,
            arrival = ArrivalInfo(resolve(destination.name), destination.description?.let(::resolve)),
        )
        listener?.onArrived()
    }

    private fun showAlignmentPrompt(message: String) {
        uiState = uiState.copy(alignmentPrompt = message)
    }

    fun showToast(message: String, durationMs: Long = 3_000) {
        toastCounter += 1
        uiState = uiState.copy(toast = Toast(toastCounter, message, durationMs))
    }

    // endregion

    // region Actions

    fun submitFeedback(reason: NavigationFeedback.Reason) {
        val feedback = NavigationFeedback(
            reason = reason,
            venueID = venue.id,
            destinationID = destination.id,
            level = if (isAligned) currentLevel else null,
            position = if (isAligned) lastPosition else null,
            timeMillis = clock(),
        )
        listener?.onFeedback(feedback)
        showToast("Thanks — your report was sent.")
    }

    fun finish() {
        listener?.onFinished()
    }

    // endregion

    private companion object {
        /** How close (meters) the user must get to a destination they saved for it to count as reached. */
        const val CUSTOM_ARRIVAL_RADIUS_M = 1.0f

        /** Log tag for route calculation. */
        const val ROUTE_TAG = "WFRoute"

        /** POI ids of placed objects listed as destinations start with this, followed by the object's id. */
        const val OBJECT_POI_PREFIX = "objpoi-"

        /** The first route starts at the located reference point while the user is within this many meters of it. */
        const val START_AT_REFERENCE_M = 4f

        /** How far (meters) each kind of route end may be from the recorded walk it joins. */
        const val POSITION_JOIN_M = 3f
        const val REFERENCE_JOIN_M = 3f
        const val DESTINATION_JOIN_M = 1.5f

        /** Objects are tapped from up to 6 m away, so they may sit that far from the walk they were placed from. */
        const val OBJECT_JOIN_M = 6.5f

        /** How long (ms) the user must stay within the arrival radius before "You have arrived" appears. */
        const val ARRIVAL_DWELL_MS = 1_000L
    }
}
