package com.app.androidnativearcore.wayfinder.core

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

/**
 * One recorded point of a walked path, on the floor, in venue coordinates.
 *
 * Port of the iOS prototype's `WalkedPathPoint`. Points of one [run] (one recording) are linked in [sequence].
 */
@Serializable
data class WalkedPoint(
    val run: String,
    val sequence: Int,
    val level: String,
    val x: Float,
    val y: Float,
    val z: Float,
    /** The saved destination this walk was recorded for ("" in data saved before paths were linked to destinations). */
    val destinationId: String = "",
) {
    val position: Vec3 get() = Vec3(x, y, z)
}

/** Why no route along the walked path could be found. */
enum class WalkedRouteError(val message: String) {
    /** The user isn't close enough to any walked point. */
    START_NOT_ON_PATH("You're not on a recorded path. Walk back to where the path was recorded, or scan its sign."),

    /** The destination isn't close enough to any walked point. */
    DESTINATION_NOT_ON_PATH("This destination has no recorded path to it on this level."),

    /** Both are near walked points, but those walks aren't connected to each other. */
    NOT_CONNECTED("The recorded paths don't connect your position to this destination."),
}

sealed interface WalkedRouteResult {
    /** A polyline on the floor from the start to the goal. */
    data class Success(val points: List<Vec3>) : WalkedRouteResult
    data class Failure(val error: WalkedRouteError) : WalkedRouteResult
}

/**
 * Finds routes over the walked path with A*. Routes only use walked points: the start and the destination must
 * each be close to a walked point, and there is no straight-line fallback.
 *
 * Points are linked to the next point of the same recording run, and to points of any run that are very close
 * (where walks crossed or overlapped). The cross-link distance is kept small so points on opposite sides of a
 * wall — which people walk at least ~0.3 m away from — aren't joined through it.
 */
class WalkedPathRouter(
    /** Maximum gap between consecutive points of one run (larger gaps are tracking jumps). */
    var sequentialLinkDistance: Float = 2f,
    /** Maximum distance for linking points of different runs or non-consecutive points. */
    var crossLinkDistance: Float = 0.6f,
    /** Points more than this apart vertically are on different floors (or stairs) and aren't cross-linked. */
    var maxHeightDifference: Float = 0.8f,
    /**
     * How close the user must be to a walked point for a route to start. A little more generous than the iOS
     * prototype's 1 m, because here the user usually starts from the sign they just scanned, at up to a few meters.
     */
    var startJoinDistance: Float = 3f,
    /** How close the destination must be to a walked point. */
    var destinationJoinDistance: Float = 1.5f,
    /** Straight-line tolerance when simplifying the route; small, so the route stays on the walked line. */
    var simplifyTolerance: Float = 0.1f,
) {
    /**
     * Route from [start] to [goal] through walked points only. [startJoins] and [goalJoins] limit which walked
     * points the start and the goal may join (for example only the walk an object was placed from); by default any.
     */
    fun route(
        start: Vec3,
        goal: Vec3,
        along: List<WalkedPoint>,
        startJoins: (WalkedPoint) -> Boolean = { true },
        goalJoins: (WalkedPoint) -> Boolean = { true },
    ): WalkedRouteResult {
        if (along.isEmpty()) return WalkedRouteResult.Failure(WalkedRouteError.START_NOT_ON_PATH)

        val positions = along.map { it.position } + start + goal
        val startIndex = along.size
        val goalIndex = along.size + 1
        val neighbors = List(positions.size) { mutableListOf<Int>() }
        fun link(a: Int, b: Int) {
            neighbors[a].add(b)
            neighbors[b].add(a)
        }

        // Consecutive points of each run.
        val ordered = along.indices.sortedWith(compareBy({ along[it].run }, { along[it].sequence }))
        for ((a, b) in ordered.zipWithNext()) {
            if (along[a].run == along[b].run &&
                along[b].sequence - along[a].sequence <= 2 &&
                positions[a].distanceTo(positions[b]) <= sequentialLinkDistance &&
                abs(positions[a].y - positions[b].y) <= 1f
            ) link(a, b)
        }

        // Nearby points (where walks crossed), found with a grid so this stays fast for many points.
        val cellSize = crossLinkDistance
        fun cell(p: Vec3) = Pair(floor(p.x / cellSize).toInt(), floor(p.z / cellSize).toInt())
        val grid = HashMap<Pair<Int, Int>, MutableList<Int>>()
        for (index in along.indices) grid.getOrPut(cell(positions[index])) { mutableListOf() }.add(index)
        for (index in along.indices) {
            val (cx, cz) = cell(positions[index])
            for (dx in -1..1) for (dz in -1..1) {
                for (other in grid[Pair(cx + dx, cz + dz)].orEmpty()) {
                    if (other > index &&
                        horizontalDistance(positions[index], positions[other]) <= crossLinkDistance &&
                        abs(positions[index].y - positions[other].y) <= maxHeightDifference &&
                        other !in neighbors[index]
                    ) link(index, other)
                }
            }
        }

        // Join the start and the destination to nearby walked points (never to each other directly).
        if (!joinToPath(startIndex, startJoinDistance, positions, along.indices.filter { startJoins(along[it]) }, ::link)) {
            return WalkedRouteResult.Failure(WalkedRouteError.START_NOT_ON_PATH)
        }
        if (!joinToPath(goalIndex, destinationJoinDistance, positions, along.indices.filter { goalJoins(along[it]) }, ::link)) {
            return WalkedRouteResult.Failure(WalkedRouteError.DESTINATION_NOT_ON_PATH)
        }

        val indices = shortestPath(startIndex, goalIndex, positions, neighbors)
            ?: return WalkedRouteResult.Failure(WalkedRouteError.NOT_CONNECTED)
        return WalkedRouteResult.Success(simplify(indices.map { positions[it] }))
    }

    private fun joinToPath(index: Int, maxDistance: Float, positions: List<Vec3>, candidates: List<Int>, link: (Int, Int) -> Unit): Boolean {
        val distances = candidates.map { it to horizontalDistance(positions[index], positions[it]) }
        val nearest = distances.minByOrNull { it.second } ?: return false
        if (nearest.second > maxDistance) return false
        // Only points about as close as the nearest one: the nearest points are almost always on the user's side
        // of a wall, while points just beyond the wall are farther away. A few candidates let the route leave in
        // the best direction.
        val limit = min(nearest.second + 0.3f, maxDistance)
        distances.filter { it.second <= limit }.sortedBy { it.second }.take(4).forEach { link(index, it.first) }
        return true
    }

    /** A* over the linked points, returning point indices from start to goal. */
    private fun shortestPath(start: Int, goal: Int, positions: List<Vec3>, neighbors: List<List<Int>>): List<Int>? {
        class Entry(val index: Int, val f: Float)
        val open = PriorityQueue<Entry>(compareBy { it.f })
        val cameFrom = HashMap<Int, Int>()
        val gScore = HashMap<Int, Float>().apply { put(start, 0f) }
        open.add(Entry(start, positions[start].distanceTo(positions[goal])))

        while (open.isNotEmpty()) {
            val current = open.poll().index
            if (current == goal) {
                val path = mutableListOf(goal)
                while (true) path.add(0, cameFrom[path[0]] ?: break)
                return path
            }
            for (next in neighbors[current]) {
                val tentative = (gScore[current] ?: Float.POSITIVE_INFINITY) + positions[current].distanceTo(positions[next])
                if (tentative < (gScore[next] ?: Float.POSITIVE_INFINITY)) {
                    cameFrom[next] = current
                    gScore[next] = tentative
                    open.add(Entry(next, tentative + positions[next].distanceTo(positions[goal])))
                }
            }
        }
        return null
    }

    /** Douglas–Peucker simplification in the horizontal plane, keeping the ends. */
    fun simplify(points: List<Vec3>): List<Vec3> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true

        fun simplifyRange(first: Int, last: Int) {
            if (last <= first + 1) return
            val a = Vec2(points[first].x, points[first].z)
            val b = Vec2(points[last].x, points[last].z)
            var farthestIndex = first
            var farthestDistance = 0f
            for (index in first + 1 until last) {
                val d = distanceToSegment(Vec2(points[index].x, points[index].z), a, b)
                if (d > farthestDistance) { farthestDistance = d; farthestIndex = index }
            }
            if (farthestDistance > simplifyTolerance) {
                keep[farthestIndex] = true
                simplifyRange(first, farthestIndex)
                simplifyRange(farthestIndex, last)
            }
        }
        simplifyRange(0, points.size - 1)
        return points.indices.filter { keep[it] }.map { points[it] }
    }

    private fun distanceToSegment(p: Vec2, a: Vec2, b: Vec2): Float {
        val segment = b - a
        val lengthSquared = segment dot segment
        val t = if (lengthSquared > 0) max(0f, min(1f, ((p - a) dot segment) / lengthSquared)) else 0f
        return p.distanceTo(a + segment * t)
    }
}

/** A recorded position in the AR session's own frame, before it is converted to venue coordinates. */
data class RawWalkPoint(
    val run: String,
    val sequence: Int,
    val position: Vec3,
    /** How far the user had walked (meters) when this point was recorded: the "time" of the walk, for drift correction. */
    val progress: Float = 0f,
)

/**
 * Records the user's walk in the AR session's own frame, one point every [spacing] meters, like the iOS
 * `WalkedPathController` (whose points are ARKit anchors that follow the session).
 *
 * Why the session frame and not venue coordinates: the venue alignment keeps being refined as signs are scanned,
 * and every refinement would shift later points but not earlier ones, kinking the path. The session frame is
 * stable, so the whole walk is converted once with [toWalkedPoints], using the final, best alignment.
 */
class SessionPathRecorder(
    private val spacing: Float = 0.25f,
    private val maxPoints: Int = 3000,
    runId: String = newRunId(),
    /**
     * How much of each new position is taken in (0..1). A phone held in the hand sways by several centimeters
     * with every step; blending positions evens that out so the recorded path is the line walked, not the wobble.
     */
    private val smoothing: Float = 0.5f,
) {
    private var run = runId
    private var nextSequence = 0
    private var last: Vec3? = null
    private var smoothed: Vec3? = null
    private var totalProgress = 0f
    private val recorded = mutableListOf<RawWalkPoint>()

    val points: List<RawWalkPoint> get() = recorded

    /** How far the user has walked since the recording began (meters, tracking jumps excluded). */
    val progress: Float get() = totalProgress

    /** Length of everything recorded so far, in meters. */
    val lengthMeters: Float
        get() = recorded.zipWithNext().sumOf { (a, b) ->
            if (a.run == b.run) horizontalDistance(a.position, b.position).toDouble() else 0.0
        }.toFloat()

    /** Call with the user's position in the session frame; adds a point once they have walked far enough. */
    fun record(cameraInSession: Vec3) {
        if (recorded.size >= maxPoints) return
        val previousSmoothed = smoothed
        val position = if (previousSmoothed == null) cameraInSession else Vec3(
            previousSmoothed.x + (cameraInSession.x - previousSmoothed.x) * smoothing,
            previousSmoothed.y + (cameraInSession.y - previousSmoothed.y) * smoothing,
            previousSmoothed.z + (cameraInSession.z - previousSmoothed.z) * smoothing,
        )
        smoothed = position
        val previous = last
        if (previous != null) {
            val moved = horizontalDistance(previous, position)
            if (moved < spacing) return
            // A big jump means tracking was corrected; don't link across the gap (or count it as walked).
            if (moved > 3f) {
                startNewRun()
                smoothed = cameraInSession
            } else {
                totalProgress += moved
            }
        }
        val point = smoothed ?: position
        recorded += RawWalkPoint(run, nextSequence, point, totalProgress)
        nextSequence += 1
        last = point
    }

    /** Adds a point at [position] regardless of spacing (e.g. exactly where the user stands when saving). */
    fun recordAnchor(position: Vec3) {
        last?.let { totalProgress += horizontalDistance(it, position).takeIf { moved -> moved <= 3f } ?: 0f }
        recorded += RawWalkPoint(run, nextSequence, position, totalProgress)
        nextSequence += 1
        last = position
        smoothed = position
    }

    fun clear() {
        recorded.clear()
        last = null
        smoothed = null
        nextSequence = 0
        totalProgress = 0f
        run = newRunId()
    }

    private fun startNewRun() {
        run = newRunId()
        nextSequence = 0
    }

    companion object {
        fun newRunId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
    }
}

/**
 * Converts a session-frame walk to venue coordinates with [venueFromSession] (the inverse of the final
 * alignment). Each point goes on the level its height belongs to, at the real floor height when it is known.
 */
fun List<RawWalkPoint>.toWalkedPoints(
    venue: Venue,
    venueFromSession: Mat4,
    fallbackLevel: String,
    /** Height of the real floor in the session frame, if known; otherwise the level's elevation is used. */
    floorSessionY: Float? = null,
): List<WalkedPoint> =
    map { raw ->
        val v = venueFromSession.transformPoint(raw.position)
        val level = venue.level(forDeviceHeight = v.y)?.id ?: fallbackLevel
        val floorY = floorSessionY?.let { venueFromSession.transformPoint(Vec3(raw.position.x, it, raw.position.z)).y }
            ?: venue.level(level)?.elevation ?: 0f
        WalkedPoint(raw.run, raw.sequence, level, v.x, floorY, v.z)
    }

/**
 * Like [toWalkedPoints] with one alignment, but each point is converted with the alignment [track] gives for the
 * moment of the walk it was recorded at, so drift that built up while walking is corrected (see [AlignmentTrack]).
 */
fun List<RawWalkPoint>.toWalkedPoints(
    venue: Venue,
    track: AlignmentTrack,
    fallbackLevel: String,
    floorSessionY: Float? = null,
): List<WalkedPoint> =
    map { raw ->
        val venueFromSession = track.venueFromSessionAt(raw.progress)
        val v = venueFromSession.transformPoint(raw.position)
        val level = venue.level(forDeviceHeight = v.y)?.id ?: fallbackLevel
        val floorY = floorSessionY?.let { venueFromSession.transformPoint(Vec3(raw.position.x, it, raw.position.z)).y }
            ?: venue.level(level)?.elevation ?: 0f
        WalkedPoint(raw.run, raw.sequence, level, v.x, floorY, v.z)
    }

/**
 * A straight run of points every [spacing] meters from [from] up to (not including) [to], on one floor. Used to
 * start a recorded path at the sign itself: the user just looked at it, so the way between is clear.
 */
fun straightWalk(from: Vec3, to: Vec3, run: String, level: String, floorY: Float, spacing: Float = 0.5f): List<WalkedPoint> {
    val length = horizontalDistance(from, to)
    val steps = kotlin.math.ceil(length / spacing).toInt().coerceAtLeast(1)
    return (0 until steps).map { i ->
        val t = i / steps.toFloat()
        WalkedPoint(run, i, level, from.x + (to.x - from.x) * t, floorY, from.z + (to.z - from.z) * t)
    }
}
