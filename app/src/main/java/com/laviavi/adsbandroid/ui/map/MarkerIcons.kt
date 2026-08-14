package com.laviavi.adsbandroid.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * Generates the plane/square/circle/triangle marker glyphs as SDF (signed-distance-field)
 * bitmaps — solid white shape on a transparent background — so MapLibre's `icon-color`
 * can recolor the same bitmap per feature state (airborne/ground/stale/emergency) instead
 * of needing one bitmap per color. Same path geometry the old Canvas-based overlay used.
 */
internal object MarkerIcons {
    const val PLANE = "aircraft-plane"
    const val SQUARE = "aircraft-square"
    const val CIRCLE = "aircraft-circle"
    const val TRIANGLE = "aircraft-triangle"

    /** Size of each generated icon bitmap, in px, at the given density. */
    private const val HALF = 12f

    fun all(density: Float): Map<String, Bitmap> = mapOf(
        PLANE to planeBitmap(density),
        SQUARE to squareBitmap(density),
        CIRCLE to circleBitmap(density),
        TRIANGLE to triangleBitmap(density),
    )

    private fun newBitmap(density: Float): Pair<Bitmap, Canvas> {
        val size = (HALF * 2 * density).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.translate(size / 2f, size / 2f)
        return bmp to canvas
    }

    private fun fillPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    /** Same silhouette as the old `AircraftOverlay.drawPlane` — nose pointing up (0deg = north), rotated per-feature by `icon-rotate`. */
    private fun planeBitmap(density: Float): Bitmap {
        val (bmp, canvas) = newBitmap(density)
        val s = density
        val path = Path().apply {
            moveTo(0f, -9f * s)
            lineTo(1.6f * s, -3f * s)
            lineTo(9f * s, 2.2f * s)
            lineTo(9f * s, 4.2f * s)
            lineTo(1.6f * s, 2.2f * s)
            lineTo(1.6f * s, 6.5f * s)
            lineTo(4f * s, 8.6f * s)
            lineTo(4f * s, 9.6f * s)
            lineTo(0f, 8.2f * s)
            lineTo(-4f * s, 9.6f * s)
            lineTo(-4f * s, 8.6f * s)
            lineTo(-1.6f * s, 6.5f * s)
            lineTo(-1.6f * s, 2.2f * s)
            lineTo(-9f * s, 4.2f * s)
            lineTo(-9f * s, 2.2f * s)
            lineTo(-1.6f * s, -3f * s)
            close()
        }
        canvas.drawPath(path, fillPaint())
        return bmp
    }

    private fun squareBitmap(density: Float): Bitmap {
        val (bmp, canvas) = newBitmap(density)
        val h = 5f * density
        canvas.drawRect(-h, -h, h, h, fillPaint())
        return bmp
    }

    private fun circleBitmap(density: Float): Bitmap {
        val (bmp, canvas) = newBitmap(density)
        canvas.drawCircle(0f, 0f, 5f * density, fillPaint())
        return bmp
    }

    private fun triangleBitmap(density: Float): Bitmap {
        val (bmp, canvas) = newBitmap(density)
        val s = density
        val path = Path().apply {
            moveTo(0f, -8f * s)
            lineTo(6f * s, 7f * s)
            lineTo(-6f * s, 7f * s)
            close()
        }
        canvas.drawPath(path, fillPaint())
        return bmp
    }
}
