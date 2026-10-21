package com.app.androidnativearcore.wayfinder.ar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.androidnativearcore.wayfinder.core.PlacedObject
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.Venue
import com.app.androidnativearcore.wayfinder.core.WalkedPoint
import kotlin.math.floor
import kotlin.math.hypot

/** A point shown on the in-camera 2D Map dialog (Reference Point, Destination, or Placed Object). */
internal data class MapItem2D(
    val id: String,
    val label: String,
    val glyph: String,
    val kindLabel: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val color: Color,
)

/**
 * In-camera 2D Map dialog displaying a top-down 2D plane of all points added so far:
 * - Walked paths (saved + live session path)
 * - Reference points (`📍` blue)
 * - Saved & venue destinations (`⭐` orange)
 * - Placed 3D objects (`☕`/`🎁`/`⭐`/`🎈`/`💬` purple)
 * - Current user position (`You` green dot)
 *
 * Supports pinch-to-zoom, drag-to-pan, and tapping any point on the 2D plane to Navigate or Connect to it.
 */
@Composable
internal fun Live2DMapDialog(
    walkedPoints: List<WalkedPoint>,
    liveWalkPoints: List<Vec3>,
    refPins: List<RefPin>,
    destinations: List<Venue.POI>,
    objects: List<PlacedObject>,
    userPos: Vec3,
    activeRoutePoints: List<Vec3> = emptyList(),
    currentFromName: String = "",
    onDismiss: () -> Unit,
    onSelectNavigate: ((MapItem2D) -> Unit)? = null,
    onSelectConnect: ((MapItem2D) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val refColor = Color(0xFF0A84FF)
    val destColor = Color(0xFFFF9500)
    val objColor = Color(0xFFAF52DE)
    val userColor = Color(0xFF34C759)
    val pathColor = Color(0xFF8E8E93)
    val livePathColor = Color(0xFF5AC8FA)
    val routeColor = Color(0xFFFF453A)

    val items = remember(refPins, destinations, objects) {
        val list = mutableListOf<MapItem2D>()
        for (pin in refPins) {
            list += MapItem2D(
                id = pin.id.ifEmpty { "refpin-${pin.name}" },
                label = pin.name,
                glyph = "📍",
                kindLabel = "Reference point",
                x = pin.x,
                y = pin.y,
                z = pin.z,
                color = refColor,
            )
        }
        for (poi in destinations) {
            if (list.none { it.label.equals(poi.name.resolved(), ignoreCase = true) && hypot(it.x - poi.x, it.z - poi.z) < 0.5f }) {
                list += MapItem2D(
                    id = poi.id,
                    label = poi.name.resolved(),
                    glyph = "⭐",
                    kindLabel = "Destination",
                    x = poi.x,
                    y = poi.elevation ?: 0f,
                    z = poi.z,
                    color = destColor,
                )
            }
        }
        for (obj in objects.filter { !it.isReference }) {
            val label = obj.text.ifBlank { obj.kind.label }
            list += MapItem2D(
                id = obj.id,
                label = label,
                glyph = obj.kind.glyph,
                kindLabel = "Object (${obj.kind.label})",
                x = obj.x,
                y = obj.y,
                z = obj.z,
                color = objColor,
            )
        }
        list
    }

    val runs = remember(walkedPoints) {
        walkedPoints.groupBy { it.run }.values.map { run -> run.sortedBy { it.sequence } }
    }

    val bounds = remember(walkedPoints, liveWalkPoints, items, userPos, activeRoutePoints) {
        val xs = ArrayList<Float>()
        val zs = ArrayList<Float>()
        walkedPoints.forEach { xs += it.x; zs += it.z }
        liveWalkPoints.forEach { xs += it.x; zs += it.z }
        items.forEach { xs += it.x; zs += it.z }
        activeRoutePoints.forEach { xs += it.x; zs += it.z }
        xs += userPos.x; zs += userPos.z
        if (xs.isEmpty()) floatArrayOf(-3f, 3f, -3f, 3f)
        else floatArrayOf(xs.min() - 1f, xs.max() + 1f, zs.min() - 1f, zs.max() + 1f)
    }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var selectedItem by remember { mutableStateOf<MapItem2D?>(null) }

    val padPx = with(density) { 36.dp.toPx() }
    val tapRadiusPx = with(density) { 28.dp.toPx() }

    fun baseScale(size: Size): Float {
        val w = (bounds[1] - bounds[0]).coerceAtLeast(3f)
        val d = (bounds[3] - bounds[2]).coerceAtLeast(3f)
        return minOf((size.width - 2 * padPx) / w, (size.height - 2 * padPx) / d).coerceAtLeast(1f)
    }

    fun toScreen(size: Size, x: Float, z: Float): Offset {
        val scale = baseScale(size) * zoom
        val cx = (bounds[0] + bounds[1]) / 2f
        val cz = (bounds[2] + bounds[3]) / 2f
        return Offset(
            size.width / 2f + (x - cx) * scale + pan.x,
            size.height / 2f + (z - cz) * scale + pan.y,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("2D Floor Map", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) {
                    Text("Reset view", fontSize = 12.sp)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Legend row
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LegendDot(refColor, "📍 Ref (${refPins.size})")
                    LegendDot(destColor, "⭐ Dest (${destinations.size})")
                    LegendDot(objColor, "◳ Obj (${objects.count { !it.isReference }})")
                    LegendDot(userColor, "● You")
                }

                // 2D Plane Canvas
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF141416)),
                ) {
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, panChange, zoomChange, _ ->
                                    zoom = (zoom * zoomChange).coerceIn(0.4f, 10f)
                                    pan += panChange
                                }
                            }
                            .pointerInput(items) {
                                detectTapGestures { tap ->
                                    val size = Size(this.size.width.toFloat(), this.size.height.toFloat())
                                    selectedItem = items
                                        .minByOrNull { hypot(toScreen(size, it.x, it.z).x - tap.x, toScreen(size, it.x, it.z).y - tap.y) }
                                        ?.takeIf { hypot(toScreen(size, it.x, it.z).x - tap.x, toScreen(size, it.x, it.z).y - tap.y) <= tapRadiusPx }
                                }
                            },
                    ) {
                        fun at(x: Float, z: Float) = toScreen(size, x, z)
                        val unit = density.density
                        val metersToPx = baseScale(size) * zoom

                        // 1m / 5m grid lines
                        val grid = if (metersToPx > 14f) 1f else if (metersToPx > 4f) 5f else 10f
                        var gx = floor(bounds[0] / grid) * grid - grid
                        while (gx <= bounds[1] + grid) {
                            drawLine(Color.White.copy(alpha = 0.07f), at(gx, bounds[2] - 40f), at(gx, bounds[3] + 40f), 1f)
                            gx += grid
                        }
                        var gz = floor(bounds[2] / grid) * grid - grid
                        while (gz <= bounds[3] + grid) {
                            drawLine(Color.White.copy(alpha = 0.07f), at(bounds[0] - 40f, gz), at(bounds[1] + 40f, gz), 1f)
                            gz += grid
                        }

                        // Saved walked paths
                        for (run in runs) {
                            if (run.size < 2) continue
                            val p = Path()
                            run.forEachIndexed { idx, pt ->
                                val s = at(pt.x, pt.z)
                                if (idx == 0) p.moveTo(s.x, s.y) else p.lineTo(s.x, s.y)
                            }
                            drawPath(p, pathColor.copy(alpha = 0.55f), style = Stroke(width = 6f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }

                        // Live session walked path
                        if (liveWalkPoints.size >= 2) {
                            val p = Path()
                            liveWalkPoints.forEachIndexed { idx, pt ->
                                val s = at(pt.x, pt.z)
                                if (idx == 0) p.moveTo(s.x, s.y) else p.lineTo(s.x, s.y)
                            }
                            drawPath(p, livePathColor.copy(alpha = 0.8f), style = Stroke(width = 5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }

                        // Active navigation route
                        if (activeRoutePoints.size >= 2) {
                            val p = Path()
                            activeRoutePoints.forEachIndexed { idx, pt ->
                                val s = at(pt.x, pt.z)
                                if (idx == 0) p.moveTo(s.x, s.y) else p.lineTo(s.x, s.y)
                            }
                            drawPath(p, routeColor, style = Stroke(width = 4f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }

                        // Reference points, Destinations, and Placed Objects
                        drawIntoCanvas { canvas ->
                            val textPaint = android.graphics.Paint().apply {
                                isAntiAlias = true
                                textSize = 10.5f * unit
                                color = android.graphics.Color.WHITE
                                textAlign = android.graphics.Paint.Align.CENTER
                                isFakeBoldText = true
                            }
                            for (item in items) {
                                val c = at(item.x, item.z)
                                val isSel = selectedItem?.id == item.id && selectedItem?.label == item.label
                                if (isSel) {
                                    drawCircle(item.color.copy(alpha = 0.35f), 16f * unit, c)
                                }
                                drawCircle(Color.White, 7.5f * unit, c)
                                drawCircle(item.color, 5.5f * unit, c)
                                canvas.nativeCanvas.drawText("${item.glyph} ${item.label}", c.x, c.y - 11f * unit, textPaint)
                            }

                            // User position ("You")
                            val u = at(userPos.x, userPos.z)
                            drawCircle(userColor.copy(alpha = 0.28f), 14f * unit, u)
                            drawCircle(Color.White, 7f * unit, u)
                            drawCircle(userColor, 5f * unit, u)
                            val youPaint = android.graphics.Paint(textPaint).apply {
                                color = userColor.toArgb()
                            }
                            canvas.nativeCanvas.drawText("You", u.x, u.y + 18f * unit, youPaint)
                        }
                    }

                    Text(
                        "Pinch to zoom · Drag to pan · Tap any pin",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    )
                }

                // Selected pin card with Navigate & Connect buttons
                selectedItem?.let { sel ->
                    val dist = hypot(sel.x - userPos.x, sel.z - userPos.z)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2C2C2E))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${sel.glyph} ${sel.label}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                            Text(
                                "${sel.kindLabel} · %.1f m".format(dist),
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (onSelectNavigate != null) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0A84FF))
                                        .clickable {
                                            onSelectNavigate(sel)
                                            onDismiss()
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("➤ Navigate here", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                            if (onSelectConnect != null) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF34C759))
                                        .clickable {
                                            onSelectConnect(sel)
                                            onDismiss()
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val fromPrefix = if (currentFromName.isNotBlank()) "$currentFromName → " else ""
                                    Text("🔗 Connect $fromPrefix${sel.label}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.height(8.dp).padding(end = 2.dp).clip(CircleShape).background(color))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
