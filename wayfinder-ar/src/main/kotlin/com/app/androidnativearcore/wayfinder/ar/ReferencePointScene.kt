package com.app.androidnativearcore.wayfinder.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.app.androidnativearcore.wayfinder.core.Mat4
import io.github.sceneview.SceneScope
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.math.Position

/** A reference point to draw in the camera: its venue position, name, and optional marker ID. */
data class RefPin(val x: Float, val y: Float, val z: Float, val name: String, val id: String = "")

/**
 * Draws captured reference points in the camera as blue pins with a blue name label, under the same root node as the
 * route and objects so they line up with the real world.
 */
@Composable
fun ARSceneScope.ReferencePointsScene(
    points: List<RefPin>,
    alignment: Mat4,
    cameraPosition: () -> Position,
) {
    if (points.isEmpty()) return
    val (position, rotation) = rootPose(alignment)
    Node(position = position, rotation = rotation) {
        for (p in points) key(p.name, p.x, p.z) { RefPinNode(p, cameraPosition) }
    }
}

/** Reference points and their floating text labels are rendered in blue so they stand out from objects. */
private val PIN_BLUE = Color(0xFF0A84FF)

@Composable
private fun SceneScope.RefPinNode(p: RefPin, cameraPosition: () -> Position) {
    val mat = remember(materialLoader) { materialLoader.createColorInstance(PIN_BLUE) }
    Node(position = Position(p.x, p.y, p.z)) {
        CylinderNode(radius = 0.02f, height = 0.3f, position = Position(y = 0.15f), materialInstance = mat)
        SphereNode(radius = 0.06f, position = Position(y = 0.36f), materialInstance = mat)
        val bitmap = remember(p.name) { Badges.bitmap(p.name, "📍", PIN_BLUE.toArgb()) }
        val height = 0.14f
        BillboardNode(
            bitmap = bitmap,
            widthMeters = height * bitmap.width / bitmap.height,
            heightMeters = height,
            position = Position(y = 0.58f),
            cameraPositionProvider = cameraPosition,
        )
    }
}
