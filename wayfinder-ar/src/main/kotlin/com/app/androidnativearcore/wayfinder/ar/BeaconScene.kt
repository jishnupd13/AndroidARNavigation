package com.app.androidnativearcore.wayfinder.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.app.androidnativearcore.wayfinder.core.Mat4
import com.app.androidnativearcore.wayfinder.core.RegisteredBeacon
import com.app.androidnativearcore.wayfinder.core.Vec3
import com.app.androidnativearcore.wayfinder.core.position
import io.github.sceneview.SceneScope
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.math.Position

/**
 * Draws the registered Bluetooth beacons in the camera: the beacon logo with its name, standing on a post at the
 * beacon's spot on the floor plan, under the same root node as the route and reference points so they line up with
 * the real world. A beacon has no height in the plan, so it is drawn standing on the floor ([floorY], venue frame).
 */
@Composable
fun ARSceneScope.BeaconsScene(
    beacons: List<RegisteredBeacon>,
    floorY: Float,
    alignment: Mat4,
    cameraPosition: () -> Position,
) {
    if (beacons.isEmpty()) return
    val (position, rotation) = rootPose(alignment)
    Node(position = position, rotation = rotation) {
        // Beacons registered without a position have no spot to stand on.
        for (beacon in beacons) {
            val spot = beacon.position ?: continue
            key(beacon.beaconId) { BeaconNode(beacon, spot, floorY, cameraPosition) }
        }
    }
}

/** Beacons are purple, as on the 2D map. */
private val BEACON_PURPLE = Color(0xFFAF52DE)

@Composable
private fun SceneScope.BeaconNode(beacon: RegisteredBeacon, spot: Vec3, floorY: Float, cameraPosition: () -> Position) {
    val mat = remember(materialLoader) { materialLoader.createColorInstance(BEACON_PURPLE) }
    Node(position = Position(spot.x, floorY, spot.z)) {
        // A disc on the floor marks the beacon's spot; a thin post carries the picture up to eye level.
        CylinderNode(radius = 0.18f, height = 0.01f, position = Position(y = 0.005f), materialInstance = mat)
        CylinderNode(radius = 0.012f, height = 0.95f, position = Position(y = 0.475f), materialInstance = mat)
        val context = LocalContext.current
        val icon = remember { BeaconLogo.bitmap(context) }
        val iconHeight = 0.36f
        BillboardNode(
            bitmap = icon,
            widthMeters = iconHeight * icon.width / icon.height,
            heightMeters = iconHeight,
            position = Position(y = 0.95f + iconHeight / 2f),
            cameraPositionProvider = cameraPosition,
        )
        val label = remember(beacon.name) { Badges.bitmap(beacon.name, "📶", BEACON_PURPLE.toArgb()) }
        val labelHeight = 0.12f
        BillboardNode(
            bitmap = label,
            widthMeters = labelHeight * label.width / label.height,
            heightMeters = labelHeight,
            position = Position(y = 0.95f + iconHeight + 0.1f),
            cameraPositionProvider = cameraPosition,
        )
    }
}

/** The beacon logo: a blue beacon sending out signal waves, on a see-through background. Decoded once. */
internal object BeaconLogo {
    private var cached: Bitmap? = null

    fun bitmap(context: Context): Bitmap =
        cached ?: BitmapFactory.decodeResource(context.resources, R.drawable.beacon_logo).also { cached = it }
}

/** How far (meters) the logo floats above the beacon device a visitor tapped. */
private const val LOGO_LIFT_M = 0.22f

/**
 * The beacon logo floating just above [spot] (the beacon device, in the scene's coordinates), bobbing gently, with
 * the beacon's [name] above it; both always turn toward the camera.
 */
@Composable
fun SceneScope.FloatingBeaconLogo(name: String, spot: Vec3, cameraPosition: () -> Position) {
    val context = LocalContext.current
    val logo = remember { BeaconLogo.bitmap(context) }
    val transition = rememberInfiniteTransition(label = "beacon")
    val bob by transition.animateFloat(
        0f, 0.03f, infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Reverse), label = "bob",
    )
    val logoHeight = 0.26f
    Node(position = Position(spot.x, spot.y, spot.z)) {
        BillboardNode(
            bitmap = logo,
            widthMeters = logoHeight * logo.width / logo.height,
            heightMeters = logoHeight,
            position = Position(y = LOGO_LIFT_M + logoHeight / 2f + bob),
            cameraPositionProvider = cameraPosition,
        )
        val label = remember(name) { Badges.bitmap(name, "📶", BEACON_PURPLE.toArgb()) }
        val labelHeight = 0.08f
        BillboardNode(
            bitmap = label,
            widthMeters = labelHeight * label.width / label.height,
            heightMeters = labelHeight,
            position = Position(y = LOGO_LIFT_M + logoHeight + 0.07f + bob),
            cameraPositionProvider = cameraPosition,
        )
    }
}
