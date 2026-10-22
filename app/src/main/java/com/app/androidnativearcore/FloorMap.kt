package com.app.androidnativearcore

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.app.androidnativearcore.wayfinder.core.REFERENCE_CATEGORY
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.WalkedPoint
import kotlin.math.hypot

/** The user on the map: a dot at their position in venue coordinates. */
data class MapUser(val x: Float, val z: Float)

/**
 * A top-down 2D plan of one level painted on a Canvas: the paths people walked, reference points, destinations,
 * the user, and the route to the selected destination. Pinch to zoom, drag to pan, tap a pin to select it.
 *
 * North-up: venue +x is to the right and +z is down the screen.
 */
@Composable
fun FloorMap(
    level: String,
    walked: List<WalkedPoint>,
    markers: List<Venue.Marker>,
    destinations: List<Venue.POI>,
    user: MapUser?,
    route: List<Vec3>?,
    selectedId: String?,
    onSelect: (Venue.POI) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val pathColor = colors.onSurface.copy(alpha = 0.18f)
    val routeColor = colors.primary
    val destinationColor = Color(0xFFFF9500)
    val referenceColor = Color(0xFF34C759)
    val markerColor = colors.onSurfaceVariant
    val userColor = Color(0xFF0A84FF)
    val labelColor = colors.onSurface.toArgb()

    val points = remember(level, walked) { walked.filter { it.level == level } }
    val runs = remember(points) { points.groupBy { it.run }.values.map { run -> run.sortedBy { it.sequence } } }
    val levelPois = remember(level, destinations) { destinations.filter { it.level == level } }
    val levelMarkers = remember(level, markers) { markers.filter { it.level == level } }

    // Everything that should be in view at the start.
    val bounds = remember(points, levelPois, levelMarkers, user, route) {
        val xs = ArrayList<Float>(); val zs = ArrayList<Float>()
        points.forEach { xs += it.x; zs += it.z }
        levelPois.forEach { xs += it.x; zs += it.z }
        levelMarkers.forEach { xs += it.x; zs += it.z }
        user?.let { xs += it.x; zs += it.z }
        route?.forEach { xs += it.x; zs += it.z }
        if (xs.isEmpty()) floatArrayOf(-5f, 5f, -5f, 5f)
        else floatArrayOf(xs.min(), xs.max(), zs.min(), zs.max())
    }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val padPx = with(density) { 48.dp.toPx() }
    val tapRadiusPx = with(density) { 28.dp.toPx() }

    // Meters → pixels for the current canvas size, and the pixel for a venue point.
    fun baseScale(size: Size): Float {
        val width = (bounds[1] - bounds[0]).coerceAtLeast(4f)
        val depth = (bounds[3] - bounds[2]).coerceAtLeast(4f)
        return minOf((size.width - 2 * padPx) / width, (size.height - 2 * padPx) / depth)
    }
    fun toScreen(size: Size, x: Float, z: Float): Offset {
        val scale = baseScale(size) * zoom
        val cx = (bounds[0] + bounds[1]) / 2f
        val cz = (bounds[2] + bounds[3]) / 2f
        return Offset(size.width / 2f + (x - cx) * scale + pan.x, size.height / 2f + (z - cz) * scale + pan.y)
    }

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(0.4f, 8f)
                    pan += panChange
                }
            }
            .pointerInput(levelPois) {
                detectTapGestures { tap ->
                    val size = Size(this.size.width.toFloat(), this.size.height.toFloat())
                    levelPois
                        .minByOrNull { hypot(toScreen(size, it.x, it.z).x - tap.x, toScreen(size, it.x, it.z).y - tap.y) }
                        ?.takeIf { hypot(toScreen(size, it.x, it.z).x - tap.x, toScreen(size, it.x, it.z).y - tap.y) <= tapRadiusPx }
                        ?.let(onSelect)
                }
            },
    ) {
        fun at(x: Float, z: Float) = toScreen(size, x, z)
        val unit = density.density
        val metersToPx = baseScale(size) * zoom

        // A faint 1 m grid so scale is readable.
        val grid = if (metersToPx > 12f) 1f else if (metersToPx > 4f) 5f else 10f
        var gx = kotlin.math.floor(bounds[0] / grid) * grid - grid
        while (gx <= bounds[1] + grid) {
            drawLine(pathColor.copy(alpha = 0.05f), at(gx, bounds[2] - 50f), at(gx, bounds[3] + 50f), 1f)
            gx += grid
        }
        var gz = kotlin.math.floor(bounds[2] / grid) * grid - grid
        while (gz <= bounds[3] + grid) {
            drawLine(pathColor.copy(alpha = 0.05f), at(bounds[0] - 50f, gz), at(bounds[1] + 50f, gz), 1f)
            gz += grid
        }

        // The walked paths.
        for (run in runs) {
            val path = Path()
            run.forEachIndexed { i, p -> at(p.x, p.z).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            drawPath(path, pathColor, style = Stroke(width = 10f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // The route to the selected destination.
        route?.takeIf { it.size >= 2 }?.let { line ->
            val path = Path()
            line.forEachIndexed { i, p -> at(p.x, p.z).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            drawPath(path, routeColor, style = Stroke(width = 5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // Reference signs and photos as small squares.
        for (marker in levelMarkers) {
            val c = at(marker.x, marker.z)
            val half = 4f * unit
            drawRect(markerColor, Offset(c.x - half, c.y - half), Size(half * 2, half * 2))
        }

        // Destinations and reference points as pins, with their names.
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 11f * unit * 1.1f; color = labelColor; textAlign = android.graphics.Paint.Align.CENTER
            }
            for (poi in levelPois) {
                val c = at(poi.x, poi.z)
                val selected = poi.id == selectedId
                val color = if (poi.category == REFERENCE_CATEGORY) referenceColor else destinationColor
                if (selected) drawCircle(color.copy(alpha = 0.25f), 18f * unit, c)
                drawCircle(Color.White, 8f * unit, c)
                drawCircle(color, 6f * unit, c)
                canvas.nativeCanvas.drawText(poi.name.resolved(), c.x, c.y - 14f * unit, paint)
            }
        }

        // The user: a blue dot with a halo.
        user?.let {
            val c = at(it.x, it.z)
            drawCircle(userColor.copy(alpha = 0.2f), 16f * unit, c)
            drawCircle(Color.White, 8f * unit, c)
            drawCircle(userColor, 6f * unit, c)
        }
    }
}
