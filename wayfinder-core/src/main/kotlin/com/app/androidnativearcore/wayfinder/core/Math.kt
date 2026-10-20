package com.app.androidnativearcore.wayfinder.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Minimal vector/matrix types standing in for Swift's `simd`, so the core stays free of Android. */
data class Vec2(val x: Float, val y: Float) {
    val length: Float get() = sqrt(x * x + y * y)
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    operator fun div(s: Float) = Vec2(x / s, y / s)
    infix fun dot(o: Vec2) = x * o.x + y * o.y
    fun normalized(): Vec2 = this / length
    fun distanceTo(o: Vec2) = (this - o).length
}

data class Vec3(val x: Float, val y: Float, val z: Float) {
    val length: Float get() = sqrt(x * x + y * y + z * z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun distanceTo(o: Vec3) = (this - o).length

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
    }
}

/**
 * 4x4 transform, column-major like `simd_float4x4` and OpenGL/ARCore: element (row, column) is
 * `m[column * 4 + row]`, and the translation is the fourth column.
 */
class Mat4(val m: FloatArray) {
    init {
        require(m.size == 16) { "Mat4 needs 16 elements" }
    }

    operator fun get(column: Int, row: Int): Float = m[column * 4 + row]

    /** Column [index] as (x, y, z) — the transformed axis for columns 0..2, the position for column 3. */
    fun column(index: Int) = Vec3(m[index * 4], m[index * 4 + 1], m[index * 4 + 2])

    val translation: Vec3 get() = column(3)

    operator fun times(o: Mat4): Mat4 {
        val r = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += this[k, row] * o[col, k]
            r[col * 4 + row] = sum
        }
        return Mat4(r)
    }

    /** Transforms a point (w = 1). */
    fun transformPoint(p: Vec3) = Vec3(
        this[0, 0] * p.x + this[1, 0] * p.y + this[2, 0] * p.z + this[3, 0],
        this[0, 1] * p.x + this[1, 1] * p.y + this[2, 1] * p.z + this[3, 1],
        this[0, 2] * p.x + this[1, 2] * p.y + this[2, 2] * p.z + this[3, 2],
    )

    val determinant: Float get() {
        val a = m
        val s0 = a[0] * a[5] - a[4] * a[1]
        val s1 = a[0] * a[9] - a[8] * a[1]
        val s2 = a[0] * a[13] - a[12] * a[1]
        val s3 = a[4] * a[9] - a[8] * a[5]
        val s4 = a[4] * a[13] - a[12] * a[5]
        val s5 = a[8] * a[13] - a[12] * a[9]
        val c5 = a[10] * a[15] - a[14] * a[11]
        val c4 = a[6] * a[15] - a[14] * a[7]
        val c3 = a[6] * a[11] - a[10] * a[7]
        val c2 = a[2] * a[15] - a[14] * a[3]
        val c1 = a[2] * a[11] - a[10] * a[3]
        val c0 = a[2] * a[7] - a[6] * a[3]
        return s0 * c5 - s1 * c4 + s2 * c3 + s3 * c2 - s4 * c1 + s5 * c0
    }

    /** General 4x4 inverse (Gauss-Jordan with partial pivoting). */
    fun inverse(): Mat4 {
        val n = 4
        val a = Array(n) { row -> DoubleArray(2 * n) { col -> if (col < n) this[col, row].toDouble() else if (col - n == row) 1.0 else 0.0 } }
        for (i in 0 until n) {
            var pivot = i
            for (r in i + 1 until n) if (kotlin.math.abs(a[r][i]) > kotlin.math.abs(a[pivot][i])) pivot = r
            require(kotlin.math.abs(a[pivot][i]) > 1e-12) { "Matrix is not invertible" }
            val tmp = a[i]; a[i] = a[pivot]; a[pivot] = tmp
            val p = a[i][i]
            for (c in 0 until 2 * n) a[i][c] /= p
            for (r in 0 until n) if (r != i) {
                val f = a[r][i]
                if (f != 0.0) for (c in 0 until 2 * n) a[r][c] -= f * a[i][c]
            }
        }
        val out = FloatArray(16)
        for (col in 0 until n) for (row in 0 until n) out[col * 4 + row] = a[row][n + col].toFloat()
        return Mat4(out)
    }

    companion object {
        val IDENTITY get() = Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))

        /** Builds a matrix from its four columns, like `simd_float4x4(columns:)`. */
        fun fromColumns(c0: FloatArray, c1: FloatArray, c2: FloatArray, c3: FloatArray) =
            Mat4(c0 + c1 + c2 + c3)

        fun fromColumns(right: Vec3, up: Vec3, back: Vec3, translation: Vec3) = fromColumns(
            floatArrayOf(right.x, right.y, right.z, 0f),
            floatArrayOf(up.x, up.y, up.z, 0f),
            floatArrayOf(back.x, back.y, back.z, 0f),
            floatArrayOf(translation.x, translation.y, translation.z, 1f),
        )

        /** Rotation about +Y by [degrees] (maps +x to (cos, 0, -sin)), plus a translation. */
        fun yaw(degrees: Float, translation: Vec3 = Vec3.ZERO): Mat4 {
            val r = Math.toRadians(degrees.toDouble())
            val c = cos(r).toFloat()
            val s = sin(r).toFloat()
            return fromColumns(Vec3(c, 0f, -s), Vec3(0f, 1f, 0f), Vec3(s, 0f, c), translation)
        }

        /** Rotation about +X by [degrees]. */
        fun pitch(degrees: Float): Mat4 {
            val r = Math.toRadians(degrees.toDouble())
            val c = cos(r).toFloat()
            val s = sin(r).toFloat()
            return fromColumns(Vec3(1f, 0f, 0f), Vec3(0f, c, s), Vec3(0f, -s, c), Vec3.ZERO)
        }
    }
}

internal fun horizontalDistance(a: Vec3, b: Vec3): Float = Vec2(a.x, a.z).distanceTo(Vec2(b.x, b.z))
