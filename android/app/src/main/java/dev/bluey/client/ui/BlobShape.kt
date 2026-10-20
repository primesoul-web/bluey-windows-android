package dev.bluey.client.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path

/**
 * The blob outline — the port of BlobShape.swift: CSS
 * `border-radius: 52% 48% 46% 54% / 58% 56% 44% 42%`, drawn with four corner curves
 * (cubic approximation of quarter-ellipses, k = 0.5523).
 */
object BlobShape {

    fun path(w: Float, h: Float): Path = Path().apply {
        val tlw = 0.52f * w; val tlh = 0.58f * h
        val trw = 0.48f * w; val trh = 0.56f * h
        val brw = 0.46f * w; val brh = 0.44f * h
        val blw = 0.54f * w; val blh = 0.42f * h
        val k = 0.5523f

        moveTo(tlw, 0f)
        lineTo(w - trw, 0f)
        cubicTo(w - trw * (1 - k), 0f, w, trh * (1 - k), w, trh)
        lineTo(w, h - brh)
        cubicTo(w, h - brh * (1 - k), w - brw * (1 - k), h, w - brw, h)
        lineTo(blw, h)
        cubicTo(blw * (1 - k), h, 0f, h - blh * (1 - k), 0f, h - blh)
        lineTo(0f, tlh)
        cubicTo(0f, tlh * (1 - k), tlw * (1 - k), 0f, tlw, 0f)
        close()
    }

    /** The blueberry gradient at 150°, like the design (BlobShape.fill). */
    fun gradientBrush(w: Float, h: Float): Brush = Brush.linearGradient(
        colorStops = Palette.gradientStops.zip(Palette.gradient).toTypedArray(),
        start = Offset(0.25f * w, 0.067f * h),
        end = Offset(0.75f * w, 0.933f * h),
    )

    /** The radial shine across the upper-left of the blob. */
    fun shineBrush(w: Float, h: Float, radius: Float): Brush = Brush.radialGradient(
        colors = listOf(
            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f),
            androidx.compose.ui.graphics.Color.White.copy(alpha = 0f),
        ),
        center = Offset(0.3f * w, 0.22f * h),
        radius = radius,
    )
}

/** The little blueberry crown on top of his head (SVG path from the design, 60×40 box). */
object Crown {
    private val points = listOf(
        30f to 4f, 36f to 16f, 52f to 12f, 40f to 24f, 46f to 36f, 30f to 28f,
        14f to 36f, 20f to 24f, 8f to 12f, 24f to 16f,
    )

    fun path(w: Float, h: Float): Path = Path().apply {
        points.forEachIndexed { i, (x, y) ->
            val px = x / 60f * w
            val py = y / 40f * h
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}
