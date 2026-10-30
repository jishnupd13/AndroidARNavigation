package com.app.androidnativearcore.wayfinder.ar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.androidnativearcore.wayfinder.core.ReferenceGeometry
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame

/**
 * Where the A4 reference-photo frame is on screen (left, top, right, bottom in view pixels): the same centered crop
 * the admin's ◎ photographs, mapped through ARCore from camera-image pixels to the screen. Null if ARCore can't map it
 * yet.
 */
fun a4FrameOnScreen(frame: Frame, view: android.view.View): FloatArray? = runCatching {
    val dims = frame.camera.imageIntrinsics.imageDimensions
    // In portrait the sensor's landscape image is shown turned a quarter, so the photo's width runs along its height.
    val rotation = view.display?.rotation ?: Surface.ROTATION_0
    val rotated = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
    val (w, h) = ReferenceGeometry.a4Crop(dims[0], dims[1], rotated)
    val cx = dims[0] / 2f
    val cy = dims[1] / 2f
    val corners = floatArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy - h / 2, cx - w / 2, cy + h / 2, cx + w / 2, cy + h / 2)
    val onScreen = FloatArray(corners.size)
    frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, corners, Coordinates2d.VIEW, onScreen)
    val xs = floatArrayOf(onScreen[0], onScreen[2], onScreen[4], onScreen[6])
    val ys = floatArrayOf(onScreen[1], onScreen[3], onScreen[5], onScreen[7])
    floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
}.getOrNull()

/**
 * Where the whole camera image is on screen (left, top, right, bottom in view pixels). The screen shows a cropped
 * camera image, so this reaches past the screen's edges on one side. Null if ARCore can't map it yet.
 */
fun cameraViewOnScreen(frame: Frame): FloatArray? = runCatching {
    val dims = frame.camera.imageIntrinsics.imageDimensions
    val w = dims[0].toFloat()
    val h = dims[1].toFloat()
    val corners = floatArrayOf(0f, 0f, w, 0f, 0f, h, w, h)
    val onScreen = FloatArray(corners.size)
    frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, corners, Coordinates2d.VIEW, onScreen)
    val xs = floatArrayOf(onScreen[0], onScreen[2], onScreen[4], onScreen[6])
    val ys = floatArrayOf(onScreen[1], onScreen[3], onScreen[5], onScreen[7])
    floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
}.getOrNull()

/**
 * Where [photo] has to appear on screen to line up with the camera view it was taken from. A photo cut with this
 * app's A4 frame goes in that frame ([a4Frame]). Any other photo, such as one from the iPhone app, which saves the
 * whole camera view, spans the whole camera view ([cameraView]) across its shorter side, centered: shown any smaller,
 * lining it up would put the user too far away.
 */
fun guideRect(photo: Bitmap, a4Frame: FloatArray?, cameraView: FloatArray?): FloatArray? {
    val aspect = photo.height.toFloat() / photo.width.coerceAtLeast(1)
    val cutWithA4Frame = kotlin.math.abs(aspect - ReferenceGeometry.A4_ASPECT) < 0.02f
    if (cutWithA4Frame || cameraView == null) return a4Frame
    val cx = (cameraView[0] + cameraView[2]) / 2f
    val cy = (cameraView[1] + cameraView[3]) / 2f
    val viewW = cameraView[2] - cameraView[0]
    val viewH = cameraView[3] - cameraView[1]
    // The photo's shorter side covers the camera view's shorter side (both cameras see about the same that way).
    val (w, h) = if (viewW <= viewH) {
        if (aspect >= 1f) viewW to viewW * aspect else viewW / aspect to viewW
    } else {
        if (aspect >= 1f) viewH / aspect to viewH else viewH to viewH * aspect
    }
    return floatArrayOf(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
}

/**
 * The chosen reference photo, faded, where and how big it was framed when it was taken ([guideRect]). A photo of a
 * real scene is only recognized from about the spot it was taken from; lining the live view up with it puts the user
 * there, so ARCore recognizes it in moments instead of after a long search.
 *
 * Another phone's lens sees the scene bigger or smaller, so the photo rarely matches the live view in size exactly;
 * − / + resize it (around its middle) until it does. [sizeUnknown] says the photo has no saved real-world size: ARCore
 * then has to measure it, which it can only do while the phone moves, so the user is told to move slowly.
 */
@Composable
fun ReferenceGuideOverlay(photo: Bitmap, a4Frame: FloatArray?, cameraView: FloatArray?, sizeUnknown: Boolean = false) {
    val base = guideRect(photo, a4Frame, cameraView) ?: return
    var zoom by remember(photo) { mutableStateOf(1f) }
    val cx = (base[0] + base[2]) / 2f
    val cy = (base[1] + base[3]) / 2f
    val halfW = (base[2] - base[0]) / 2f * zoom
    val halfH = (base[3] - base[1]) / 2f * zoom
    val frame = floatArrayOf(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    val density = LocalDensity.current
    val width = with(density) { (frame[2] - frame[0]).toDp() }
    val height = with(density) { (frame[3] - frame[1]).toDp() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenHeightPx = with(density) { maxHeight.toPx() }
        Image(
            bitmap = photo.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            alpha = 0.45f,
            modifier = Modifier.offset { IntOffset(frame[0].toInt(), frame[1].toInt()) }.size(width, height),
        )
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.85f),
                topLeft = Offset(frame[0], frame[1]),
                size = Size(frame[2] - frame[0], frame[3] - frame[1]),
                cornerRadius = CornerRadius(10.dp.toPx()),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
            )
        }
        Column(
            Modifier
                // Just under the photo, or near the bottom of the screen when the photo covers the whole view.
                .offset {
                    IntOffset(
                        12.dp.roundToPx(),
                        (frame[3] + 8.dp.toPx()).coerceIn(0f, screenHeightPx - 200.dp.toPx()).toInt(),
                    )
                }
                .background(Color(0x99000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Line up the view with this photo", color = Color.White, fontSize = 12.sp)
                GuideSizeButton("−") { zoom = (zoom / 1.1f).coerceAtLeast(0.4f) }
                Text("%d%%".format((zoom * 100).roundToInt()), color = Color.White, fontSize = 12.sp)
                GuideSizeButton("+") { zoom = (zoom * 1.1f).coerceAtMost(2.5f) }
            }
            Text(
                if (sizeUnknown) "No size saved for this photo: once lined up, move the phone slowly side to side."
                else "Then hold steady; a small side-to-side movement helps.",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun GuideSizeButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(Color(0x66FFFFFF), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}
