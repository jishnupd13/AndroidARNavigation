package com.app.androidnativearcore.wayfinder.ar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Camera-facing labels rendered as images (crisp text, always readable): a rounded pill with an optional
 * glyph and a text, like the iOS `BadgeRenderer`.
 */
object Badges {
    private const val HEIGHT = 96

    fun bitmap(text: String, glyph: String?, color: Int): Bitmap {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            textSize = 44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val glyphPaint = Paint(textPaint).apply { textSize = 48f }
        val textWidth = minOf(textPaint.measureText(text), 900f)
        val glyphWidth = if (glyph == null) 0f else 56f
        val width = (textWidth + glyphWidth + 56f).toInt()

        val bitmap = Bitmap.createBitmap(width, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bounds = RectF(3f, 3f, width - 3f, HEIGHT - 3f)
        val radius = bounds.height() / 2
        canvas.drawRoundRect(bounds, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = (color and 0x00FFFFFF) or (0xE0 shl 24) // 88% opacity
        })
        val border = RectF(bounds).apply { inset(2f, 2f) }
        canvas.drawRoundRect(border, border.height() / 2, border.height() / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xE6FFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f
        })

        var x = 28f
        val baseline = HEIGHT / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        if (glyph != null) {
            canvas.drawText(glyph, x, HEIGHT / 2f - (glyphPaint.descent() + glyphPaint.ascent()) / 2f, glyphPaint)
            x += glyphWidth
        }
        val visible = if (textPaint.measureText(text) > 900f) {
            text.take((text.length * 900f / textPaint.measureText(text)).toInt() - 1) + "…"
        } else text
        canvas.drawText(visible, x, baseline, textPaint)
        return bitmap
    }
}
