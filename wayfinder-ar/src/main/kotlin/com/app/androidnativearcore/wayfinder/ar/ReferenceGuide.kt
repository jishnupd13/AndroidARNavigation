package com.app.androidnativearcore.wayfinder.ar

import android.graphics.Bitmap
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
 * The chosen reference photo, faded, exactly where and how big it was framed when the admin took it. A photo of a
 * real scene is only recognized from about the spot it was taken from; lining the live view up with this outline puts
 * the user there, so ARCore recognizes it in moments instead of after a long search.
 */
@Composable
fun ReferenceGuideOverlay(photo: Bitmap, frame: FloatArray) {
    val density = LocalDensity.current
    val width = with(density) { (frame[2] - frame[0]).toDp() }
    val height = with(density) { (frame[3] - frame[1]).toDp() }
    Box(Modifier.fillMaxSize()) {
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
        Text(
            "Line up the view with this photo",
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .offset { IntOffset(frame[0].toInt(), (frame[3] + 8.dp.toPx()).toInt()) }
                .background(Color(0x99000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
