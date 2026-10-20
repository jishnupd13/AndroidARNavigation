package com.app.androidnativearcore.wayfinder.ar

import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.app.androidnativearcore.wayfinder.core.Mat4
import com.app.androidnativearcore.wayfinder.core.Maneuver
import com.app.androidnativearcore.wayfinder.core.Route
import com.app.androidnativearcore.wayfinder.core.Vec2
import com.app.androidnativearcore.wayfinder.core.Venue
import dev.romainguy.kotlin.math.Float2
import io.github.sceneview.SceneScope
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.material.setColor
import kotlin.math.atan2

/**
 * The SceneView content for a route on one level: animated-style floor arrows, a destination pin,
 * level-change signs and floating POI labels (iOS `RouteRenderer`).
 *
 * Everything is positioned in venue coordinates under one root node posed by [alignment] (the venue's pose in
 * session coordinates), which is what `ARSession.setWorldOrigin` achieves on iOS.
 */
object RouteStyle {
    const val ARROW_SPACING = 0.8f
    const val PULSE_PERIOD = 1.6f
    const val ARROW_MIN_ALPHA = 0.35f

    /** POIs farther than this from the route aren't labelled. */
    const val POI_LABEL_RANGE = 30f

    /** The route arrows (and the mini-map route). */
    val arrow = Color(0xFFFF2D2D)
    val red = Color(0xFFFF3B30)
    val orange = Color(0xFFFF9500)
    val darkGray = 0xFF555555.toInt()

    /** A chevron in the x-y plane pointing to +y, counter-clockwise (so it faces +z before being laid flat). */
    val chevron: List<Float2> = listOf(
        Float2(-0.16f, -0.13f), Float2(0f, 0.03f), Float2(0.16f, -0.13f),
        Float2(0.16f, -0.06f), Float2(0f, 0.1f), Float2(-0.16f, -0.06f),
    )
}

@Composable
fun ARSceneScope.RouteScene(
    route: Route?,
    level: String?,
    venue: Venue,
    destination: Venue.POI,
    alignment: Mat4,
    cameraPosition: () -> Position,
    languages: List<String>? = null,
) {
    if (route == null || level == null) return

    // Root pose: venue -> session. Only yaw and translation (MarkerAlignment.levelled guarantees that).
    val (rootPosition, rootRotation) = rootPose(alignment)
    val floor = venue.level(level)?.elevation ?: 0f

    Node(position = rootPosition, rotation = rootRotation) {
        // Floor arrows along every route segment on this level, with a brightness wave running along them.
        ArrowField(remember(route, level) { arrows(route, level) }, floor)

        // Level-change signs.
        for (maneuver in route.maneuvers) {
            val kind = maneuver.kind as? Maneuver.Kind.ChangeLevel ?: continue
            if (route.levels[maneuver.pointIndex] != level) continue
            val point = route.points[maneuver.pointIndex]
            val bitmap = remember(maneuver) {
                Badges.bitmap(Instructions.text(maneuver, venue, languages), Instructions.glyph(maneuver), RouteStyle.orange.toArgbInt())
            }
            Badge(bitmap, height = 0.14f, position = Position(point.x, point.y + 2.0f, point.z), cameraPosition = cameraPosition)
        }

        // Destination pin: bobbing, with a pulsing ring.
        if (destination.level == level) {
            val p = venue.position(destination)
            DestinationPin(
                position = Position(p.x, p.y, p.z),
                name = destination.name.resolvedFor(languages),
                cameraPosition = cameraPosition,
            )
        }

        // Labels of other POIs near the route.
        val routePoints = route.points.zip(route.levels).filter { it.second == level }.map { it.first }
        if (routePoints.isNotEmpty()) {
            for (poi in venue.pois) {
                if (poi.level != level || poi.id == destination.id) continue
                val position = venue.position(poi)
                val distanceToRoute = routePoints.minOf { Vec2(it.x, it.z).distanceTo(Vec2(position.x, position.z)) }
                if (distanceToRoute > RouteStyle.POI_LABEL_RANGE) continue
                val bitmap = remember(poi) { Badges.bitmap(poi.name.resolvedFor(languages), null, RouteStyle.darkGray) }
                Badge(bitmap, height = 0.1f, position = Position(position.x, position.y + 2.2f, position.z), cameraPosition = cameraPosition)
            }
        }
    }
}

/** Pulse of one arrow over a 1.6 s period: fade up in 0.25 s, down in 0.5 s, then rest (iOS `RouteRenderer`). */
private fun arrowAlpha(seconds: Float): Float {
    val t = ((seconds % RouteStyle.PULSE_PERIOD) + RouteStyle.PULSE_PERIOD) % RouteStyle.PULSE_PERIOD
    return when {
        t < 0.25f -> RouteStyle.ARROW_MIN_ALPHA + (1f - RouteStyle.ARROW_MIN_ALPHA) * (t / 0.25f)
        t < 0.75f -> 1f - (1f - RouteStyle.ARROW_MIN_ALPHA) * ((t - 0.25f) / 0.5f)
        else -> RouteStyle.ARROW_MIN_ALPHA
    }
}

@Composable
private fun SceneScope.ArrowField(arrows: List<ArrowPlacement>, floor: Float) {
    // One material per arrow so each can have its own opacity.
    val materials = remember(arrows, materialLoader) {
        arrows.map { materialLoader.createColorInstance(RouteStyle.arrow.copy(alpha = RouteStyle.ARROW_MIN_ALPHA)) }
    }
    androidx.compose.runtime.LaunchedEffect(materials) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val seconds = (androidx.compose.runtime.withFrameNanos { it } - start) / 1_000_000_000f
            materials.forEachIndexed { index, material ->
                // Each arrow starts 0.12 s after the previous one, so the wave runs toward the destination.
                material.setColor(RouteStyle.arrow.copy(alpha = arrowAlpha(seconds - index * 0.12f % RouteStyle.PULSE_PERIOD)))
            }
        }
    }
    arrows.forEachIndexed { index, arrow ->
        Node(position = Position(arrow.x, floor + 0.02f, arrow.z), rotation = Rotation(y = arrow.yawDegrees)) {
            // Lay the chevron flat (its +y becomes -z); the parent turns it along the segment.
            ShapeNode(
                polygonPath = RouteStyle.chevron,
                rotation = Rotation(x = -90f),
                materialInstance = materials[index],
            )
        }
    }
}

@Composable
private fun SceneScope.DestinationPin(position: Position, name: String, cameraPosition: () -> Position) {
    val redMaterial = remember(materialLoader) { materialLoader.createColorInstance(RouteStyle.red) }
    val ringMaterial = remember(materialLoader) { materialLoader.createColorInstance(RouteStyle.red.copy(alpha = 0.7f)) }
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pin")
    val spec = androidx.compose.animation.core.infiniteRepeatable<Float>(
        animation = androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.LinearEasing),
        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
    )
    val bob by transition.animateFloat(0f, 0.05f, spec, label = "bob")      // whole pin moves up 5 cm and back
    val ringScale by transition.animateFloat(1f, 1.4f, spec, label = "ring") // ring grows to 1.4x and back
    val bitmap = remember(name) { Badges.bitmap(name, "⌖", RouteStyle.red.toArgbInt()) }

    Node(position = Position(position.x, position.y + bob, position.z)) {
        CylinderNode(radius = 0.015f, height = 1.2f, position = Position(y = 0.6f), materialInstance = redMaterial)
        SphereNode(radius = 0.09f, position = Position(y = 1.25f), materialInstance = redMaterial)
        // SceneView's torus already lies flat, like the SceneKit ring on the floor.
        TorusNode(
            majorRadius = 0.5f, minorRadius = 0.02f, position = Position(y = 0.02f),
            scale = Scale(ringScale, ringScale, ringScale), materialInstance = ringMaterial,
        )
        Badge(bitmap, height = 0.16f, position = Position(y = 1.6f), cameraPosition = cameraPosition)
    }
}

@Composable
private fun SceneScope.Badge(
    bitmap: android.graphics.Bitmap,
    height: Float,
    position: Position,
    cameraPosition: () -> Position,
) {
    BillboardNode(
        bitmap = bitmap,
        widthMeters = height * bitmap.width / bitmap.height,
        heightMeters = height,
        position = position,
        cameraPositionProvider = cameraPosition,
    )
}

private class ArrowPlacement(val x: Float, val z: Float, val yawDegrees: Float)

private fun arrows(route: Route, level: String): List<ArrowPlacement> {
    val result = mutableListOf<ArrowPlacement>()
    for (index in 0 until maxOf(route.points.size - 1, 0)) {
        if (route.levels[index] != level || route.levels[index + 1] != level) continue
        val start = route.points[index]
        val end = route.points[index + 1]
        val delta = Vec2(end.x - start.x, end.z - start.z)
        val length = delta.length
        if (length <= 0.05f) continue
        val direction = delta / length
        val yaw = Math.toDegrees(atan2(-direction.x, -direction.y).toDouble()).toFloat() // rotates -z onto the segment
        var distance = RouteStyle.ARROW_SPACING / 2
        while (distance < length) {
            result += ArrowPlacement(start.x + direction.x * distance, start.z + direction.y * distance, yaw)
            distance += RouteStyle.ARROW_SPACING
        }
    }
    return result
}

private fun Color.toArgbInt(): Int = this.toArgb()

private fun com.app.androidnativearcore.wayfinder.core.LocalizedText.resolvedFor(languages: List<String>?) =
    if (languages == null) resolved() else resolved(languages)
