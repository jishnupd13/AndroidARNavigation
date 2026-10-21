package com.app.androidnativearcore.wayfinder.ar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream
import kotlin.math.min

/** Helpers to turn an ARCore camera frame into a cropped, upright [Bitmap] usable as a reference image. */
object CameraImage {

    /** The ARCore camera image (YUV_420_888) as an ARGB bitmap, in the sensor's landscape orientation. */
    fun toBitmap(image: Image): Bitmap {
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 95, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** A centered square crop covering [fraction] of the shorter side. */
    fun centerSquare(bitmap: Bitmap, fraction: Float = 0.6f): Bitmap {
        val side = (min(bitmap.width, bitmap.height) * fraction).toInt().coerceAtLeast(16)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        return Bitmap.createBitmap(bitmap, left, top, side, side)
    }

    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    /** A simple sharpness/contrast proxy (0–100): the standard deviation of luma, scaled. Low = too plain to track. */
    fun detailScore(bitmap: Bitmap): Int {
        val step = (min(bitmap.width, bitmap.height) / 64).coerceAtLeast(1)
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                val luma = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                sum += luma; sumSq += luma * luma; n++
                x += step
            }
            y += step
        }
        if (n == 0) return 0
        val mean = sum / n
        val variance = (sumSq / n) - mean * mean
        val stdDev = if (variance > 0) kotlin.math.sqrt(variance) else 0.0
        // ~64 stddev is already rich; scale so that maps to 100.
        return (stdDev / 64.0 * 100.0).toInt().coerceIn(0, 100)
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + ySize / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Y
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var pos = 0
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }

        // Interleave V and U as NV21 (VU order).
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }
}
