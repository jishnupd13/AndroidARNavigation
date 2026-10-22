package com.app.androidnativearcore.wayfinder.ar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.app.androidnativearcore.wayfinder.core.Mat4
import com.app.androidnativearcore.wayfinder.core.PlacedObject
import com.app.androidnativearcore.wayfinder.core.PlacedObjectKind
import dev.romainguy.kotlin.math.Float2
import io.github.sceneview.SceneScope
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import kotlin.math.cos
import kotlin.math.sin

/**
 * The 3D objects users placed while recording a path, drawn at their venue positions under the same root node as
 * the route (so they line up with the real world once a sign has aligned the session).
 */
@Composable
fun ARSceneScope.ObjectsScene(
    objects: List<PlacedObject>,
    alignment: Mat4,
    cameraPosition: () -> Position,
) {
    // Only the objects the user placed. Paths saved by an earlier version may hold an automatic "reference point"
    // object (a star at the start); it is no longer wanted, so it is never drawn.
    val shown = objects.filter { !it.isReference }
    if (shown.isEmpty()) return
    val (position, rotation) = rootPose(alignment)
    Node(position = position, rotation = rotation) {
        for (obj in shown) key(obj.id) { PlacedObjectNode(obj, cameraPosition) }
    }
}

/**
 * The venue's pose in the session as a SceneView position and rotation. Alignments are levelled, so only yaw and
 * translation are needed.
 */
internal fun rootPose(alignment: Mat4): Pair<Position, Rotation> {
    val xAxis = alignment.column(0)
    val yawDegrees = Math.toDegrees(kotlin.math.atan2(-xAxis.z, xAxis.x).toDouble()).toFloat()
    val t = alignment.translation
    return Position(t.x, t.y, t.z) to Rotation(y = yawDegrees)
}

private object ObjectColors {
    val porcelain = Color(0xFFE6E6E6)
    val navy = Color(0xFF14233C)
    val rimGrey = Color(0xFFBDBDBD)
    val giftRed = Color(0xFFD62828)
    val gold = Color(0xFFFFC300)
    val balloon = Color(0xFFE63946)
    val string = Color(0xFFF1F1F1)
    val stem = Color(0xFF8D99AE)
    val messageBlue = Color(0xFF1D6FE0)
}

@Composable
private fun SceneScope.PlacedObjectNode(obj: PlacedObject, cameraPosition: () -> Position) {
    Node(
        position = Position(obj.x, obj.y, obj.z), rotation = Rotation(y = obj.yawDegrees),
        scale = Scale(obj.scale, obj.scale, obj.scale),
    ) {
        when (obj.kind) {
            PlacedObjectKind.CUP -> Mug()
            PlacedObjectKind.GIFT -> Gift()
            PlacedObjectKind.STAR -> SpinningStar()
            PlacedObjectKind.BALLOON -> Balloon()
            PlacedObjectKind.MESSAGE -> MessageSign(obj.text, cameraPosition)
        }
        // The name the user gave the object, floating above it (a message already is its own text).
        if (obj.kind != PlacedObjectKind.MESSAGE && obj.text.isNotBlank()) {
            ObjectLabel(obj.text, labelHeightFor(obj.kind), cameraPosition)
        }
    }
}

@Composable
private fun SceneScope.material(color: Color) = remember(materialLoader, color) { materialLoader.createColorInstance(color) }

/** A mug like the iOS demo's cup: porcelain body with a dark band, a rim and a handle. */
@Composable
private fun SceneScope.Mug() {
    val porcelain = material(ObjectColors.porcelain)
    val navy = material(ObjectColors.navy)
    val rim = material(ObjectColors.rimGrey)
    CylinderNode(radius = 0.06f, height = 0.12f, position = Position(y = 0.06f), materialInstance = porcelain)
    CylinderNode(radius = 0.0615f, height = 0.07f, position = Position(y = 0.07f), materialInstance = navy)
    // SceneView's torus lies flat (in the x-z plane): right for the rim, stood upright for the handle.
    TorusNode(majorRadius = 0.06f, minorRadius = 0.006f, position = Position(y = 0.12f), materialInstance = rim)
    TorusNode(
        majorRadius = 0.035f, minorRadius = 0.009f, position = Position(x = 0.075f, y = 0.065f),
        rotation = Rotation(x = 90f), materialInstance = porcelain,
    )
}

/** A red gift box with a gold ribbon and bow. */
@Composable
private fun SceneScope.Gift() {
    val red = material(ObjectColors.giftRed)
    val gold = material(ObjectColors.gold)
    CubeNode(size = Size(0.2f, 0.2f, 0.2f), position = Position(y = 0.1f), materialInstance = red)
    CubeNode(size = Size(0.206f, 0.206f, 0.04f), position = Position(y = 0.1f), materialInstance = gold)
    CubeNode(size = Size(0.04f, 0.206f, 0.206f), position = Position(y = 0.1f), materialInstance = gold)
    SphereNode(radius = 0.04f, position = Position(y = 0.22f), materialInstance = gold)
}

/** A gold star floating at about waist height, turning slowly. */
@Composable
private fun SceneScope.SpinningStar() {
    val gold = material(ObjectColors.gold)
    val transition = rememberInfiniteTransition(label = "star")
    val yaw by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(4_000, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )
    val bob by transition.animateFloat(
        0f, 0.06f, infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Reverse), label = "bob",
    )
    Node(position = Position(y = 0.7f + bob), rotation = Rotation(y = yaw)) {
        // A flat shape is invisible from behind, so draw it twice, back to back.
        ShapeNode(polygonPath = STAR_POINTS, materialInstance = gold)
        ShapeNode(polygonPath = STAR_POINTS, rotation = Rotation(y = 180f), materialInstance = gold)
    }
}

/** A red balloon on a string, bobbing in place. */
@Composable
private fun SceneScope.Balloon() {
    val red = material(ObjectColors.balloon)
    val string = material(ObjectColors.string)
    val transition = rememberInfiniteTransition(label = "balloon")
    val bob by transition.animateFloat(
        0f, 0.08f, infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Reverse), label = "bob",
    )
    CylinderNode(radius = 0.003f, height = 0.9f, position = Position(y = 0.45f), materialInstance = string)
    Node(position = Position(y = 1.1f + bob)) {
        SphereNode(radius = 0.15f, scale = Scale(1f, 1.25f, 1f), materialInstance = red)
        ConeNode(radius = 0.025f, height = 0.04f, position = Position(y = -0.2f), rotation = Rotation(x = 180f), materialInstance = red)
    }
}

/** Height (meters, before the object's own scaling) at which the name floats: just above the top of each shape. */
private fun labelHeightFor(kind: PlacedObjectKind): Float = when (kind) {
    PlacedObjectKind.CUP -> 0.3f
    PlacedObjectKind.GIFT -> 0.42f
    PlacedObjectKind.STAR -> 1.05f
    PlacedObjectKind.BALLOON -> 1.6f
    PlacedObjectKind.MESSAGE -> 0.8f
}

/** The object's name as a pill that always turns toward the camera. */
@Composable
private fun SceneScope.ObjectLabel(text: String, height: Float, cameraPosition: () -> Position) {
    val bitmap = remember(text) { Badges.bitmap(text, null, ObjectColors.messageBlue.toArgb()) }
    val labelHeight = 0.1f
    BillboardNode(
        bitmap = bitmap,
        widthMeters = labelHeight * bitmap.width / bitmap.height,
        heightMeters = labelHeight,
        position = Position(y = height),
        cameraPositionProvider = cameraPosition,
    )
}

/** A text label on a short post, always turned toward the camera. */
@Composable
private fun SceneScope.MessageSign(text: String, cameraPosition: () -> Position) {
    val stem = material(ObjectColors.stem)
    val label = text.ifBlank { "Hello!" }
    val bitmap = remember(label) { Badges.bitmap(label, "💬", ObjectColors.messageBlue.toArgb()) }
    CylinderNode(radius = 0.01f, height = 0.5f, position = Position(y = 0.25f), materialInstance = stem)
    val height = 0.14f
    BillboardNode(
        bitmap = bitmap,
        widthMeters = height * bitmap.width / bitmap.height,
        heightMeters = height,
        position = Position(y = 0.6f),
        cameraPositionProvider = cameraPosition,
    )
}

/** A five-pointed star outline, counter-clockwise, outer radius 0.15 m. */
private val STAR_POINTS: List<Float2> = (0 until 10).map { i ->
    val radius = if (i % 2 == 0) 0.15f else 0.06f
    val angle = Math.toRadians(90.0 + i * 36.0)
    Float2((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
}
