package com.app.androidnativearcore.wayfinder.ar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.androidnativearcore.wayfinder.core.Route
import com.app.androidnativearcore.wayfinder.core.Vec2
import com.app.androidnativearcore.wayfinder.core.Venue
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

private val panel = Color(0xCC1C1C1E)
private val cyan = Color(0xFF32ADE6)

/** Top banner with the next instruction, its distance, and the remaining distance and time. */
@Composable
fun GuidanceBannerView(banner: GuidanceBanner, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(panel, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = "${banner.instruction}. ${banner.detail}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(banner.glyph, color = cyan, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(banner.instruction, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            if (banner.detail.isNotEmpty()) Text(banner.detail, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp)
        }
    }
}

/** Shown until the session is aligned: asks the user to point the camera at an AR sign and shows what they look like. */
@Composable
fun AlignmentPromptView(message: String, signs: List<Pair<String, android.graphics.Bitmap>>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(panel, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Point your camera at an AR sign", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(message, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).height(72.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, bitmap) in signs) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = name,
                    modifier = Modifier
                        .height(72.dp)
                        .background(Color.White, RoundedCornerShape(6.dp)),
                )
            }
        }
    }
}

/** Small north-up map of the route on the current level, with the user's position and heading. */
@Composable
fun MiniMapView(route: Route?, level: String?, destination: Venue.POI, venue: Venue, user: UserPose?, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .size(130.dp)
            .background(Color(0x8C000000), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Route map" },
    ) {
        if (route == null || level == null) return@Canvas
        val onLevel = route.points.zip(route.levels).filter { it.second == level }.map { Vec2(it.first.x, it.first.z) }
        val other = route.points.zip(route.levels).filter { it.second != level }.map { Vec2(it.first.x, it.first.z) }
        val userFlat = user?.let { Vec2(it.position.x, it.position.z) }
        val all = onLevel + other + listOfNotNull(userFlat)
        if (all.isEmpty()) return@Canvas

        // Fit everything with padding; venue +x is right, +z is down.
        val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }
        val minZ = all.minOf { it.y }; val maxZ = all.maxOf { it.y }
        val span = max(max(maxX - minX, maxZ - minZ), 5f)
        val inset = 14.dp.toPx()
        val scale = (min(size.width, size.height) - inset * 2) / span
        val cx = (minX + maxX) / 2; val cz = (minZ + maxZ) / 2
        fun point(p: Vec2) = Offset(size.width / 2 + (p.x - cx) * scale, size.height / 2 + (p.y - cz) * scale)

        fun strokePath(points: List<Vec2>, color: Color, width: Float, dashed: Boolean) {
            if (points.size < 2) return
            val path = Path().apply {
                moveTo(point(points[0]).x, point(points[0]).y)
                points.drop(1).forEach { lineTo(point(it).x, point(it).y) }
            }
            drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null))
        }
        strokePath(other, Color.White.copy(alpha = 0.35f), 3.dp.toPx(), dashed = true)
        strokePath(onLevel, cyan, 4.dp.toPx(), dashed = false)

        if (destination.level == level) {
            val p = venue.position(destination)
            drawCircle(Color(0xFFFF3B30), 6.dp.toPx(), point(Vec2(p.x, p.z)))
        }
        if (user != null && userFlat != null) {
            val center = point(userFlat)
            val flat = Vec2(user.forward.x, user.forward.z)
            val forward = if (flat.length > 0.01f) flat.normalized() else Vec2(0f, -1f)
            val side = Vec2(-forward.y, forward.x)
            fun at(f: Float, s: Float) = Offset(center.x + (forward.x * f + side.x * s) * 1f, center.y + (forward.y * f + side.y * s) * 1f)
            val d = 1.dp.toPx()
            val path = Path().apply {
                moveTo(at(10 * d, 0f).x, at(10 * d, 0f).y)
                lineTo(at(-6 * d, 6 * d).x, at(-6 * d, 6 * d).y)
                lineTo(center.x, center.y)
                lineTo(at(-6 * d, -6 * d).x, at(-6 * d, -6 * d).y)
                close()
            }
            drawPath(path, Color.White)
        }
    }
}

/** Short messages (tips, rerouting) shown in the lower part of the screen. */
@Composable
fun ToastHost(toast: Toast?, modifier: Modifier = Modifier) {
    if (toast == null) return
    val visible = androidx.compose.runtime.remember(toast.id) { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(toast.id) {
        visible.value = true
        delay(toast.durationMs)
        visible.value = false
    }
    if (visible.value) {
        Text(
            toast.message,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = modifier
                .background(Color(0xBF000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { contentDescription = toast.message },
        )
    }
}

/** Full-screen arrival confirmation. */
@Composable
fun ArrivalOverlay(info: ArrivalInfo, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .background(Color(0xD9000000))
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✓", color = Color(0xFF34C759), fontSize = 96.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("You have arrived", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            listOfNotNull(info.destinationName, info.description).joinToString("\n\n"),
            color = Color.White.copy(alpha = 0.9f), fontSize = 17.sp,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
            modifier = Modifier.testTag("arrival-done"),
        ) { Text("Done", fontSize = 17.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
    }
}

/** A round translucent button with a text glyph. */
@Composable
fun CircleButton(glyph: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier
            .size(44.dp)
            .background(Color(0x8C000000), CircleShape)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}
