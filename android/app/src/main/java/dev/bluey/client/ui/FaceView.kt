package dev.bluey.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import dev.bluey.client.link.Mood
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The landscape face on pure black: he fills the screen and peeks up from the bottom
 * edge. Full port of <c>FaceView</c> in iOS/Sources/FaceView.swift — same 844×390
 * design space, same geometry, same colors. [now] is the shared clock the animator's
 * springs and packet arrivals run on.
 */
@Composable
fun FaceView(animator: FaceAnimator, now: () -> Double, modifier: Modifier = Modifier) {
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    var frame by remember { mutableStateOf<FaceAnimator.Frame?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { frame = animator.step(now()) }
        }
    }

    Canvas(modifier) {
        frame?.let { drawFace(it, textMeasurer) }
    }
}

private val designSize = Size(844f, 390f)

private val cheekColor = Color(0xFFF3A6D8)
private val tongueColor = Color(0xFFE58BC4)

private fun DrawScope.drawFace(f: FaceAnimator.Frame, textMeasurer: TextMeasurer) {
    drawRect(Color.Black, size = size)

    val scale = max(size.width / designSize.width, size.height / designSize.height)
    withTransform({
        translate(
            (size.width - designSize.width * scale) / 2f,
            size.height - designSize.height * scale,
        )
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        // Whole-body motion: breathing, talking bounce, idle hops, and leaning toward what he looks at.
        val bounce = (-f.talk * 16 + f.breathe * 3 - f.hop).toFloat()
        val bodyLeft = 12f
        val bodyTop = 44f + bounce
        val pivot = Offset(bodyLeft + 820f / 2f, bodyTop + 700f)
        val stretch = (1 + (f.hop / 900).coerceIn(-0.03, 0.03)).toFloat()
        withTransform({
            rotate(Math.toDegrees(f.lean).toFloat(), pivot)
            scale(2f - stretch, stretch, pivot)
        }) {
            drawBody(f, bodyLeft, bodyTop, textMeasurer, scale)
        }
    }
}

private fun DrawScope.drawBody(
    f: FaceAnimator.Frame,
    bodyLeft: Float,
    bodyTop: Float,
    textMeasurer: TextMeasurer,
    deviceScale: Float,
) {
    val w = 820f
    val h = 700f
    withTransform({ translate(bodyLeft, bodyTop) }) {
        val blob = BlobShape.path(w, h)
        val bodyGradient = BlobShape.gradientBrush(w, h)
        val skinShine = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0f)),
            center = Offset(246f, 126f),
            radius = 243f,
        )

        // Fills a shape with his skin (gradient plus the same soft shine), for eyelids.
        // A lambda (not a local fun) so it can see this drawing scope's receiver.
        val fillSkin: (Path) -> Unit = { path ->
            drawPath(path, bodyGradient)
            drawPath(path, skinShine)
        }

        // Glow: his body plus a soft berry shadow, drawn through the framework canvas
        // so the shadow layer rides along with the design-space transform.
        drawContext.canvas.nativeCanvas.drawPath(
            blob.asAndroidPath(),
            android.graphics.Paint().apply {
                isAntiAlias = true
                color = Palette.berry3.toArgb()
                setShadowLayer(
                    45f * deviceScale, 0f, -10f * deviceScale,
                    Palette.berry2.copy(alpha = 0.5f).toArgb(),
                )
            },
        )

        drawPath(blob, bodyGradient)
        clipPath(blob) {
            drawPath(blob, skinShine)
            // A little glossy sparkle on his head.
            drawOval(
                Color.White.copy(alpha = 0.35f),
                topLeft = Offset(190f, 40f),
                size = Size(46f, 22f),
            )
        }

        val navy = Palette.nose
        val ink = Palette.ink
        withTransform({
            translate(368f, -20f - (f.browLift * 0.3).toFloat())
        }) {
            drawPath(Crown.path(84f, 56f), color = navy)
        }

        val eyeY = 157f
        val eyes = listOf(Offset(280f, eyeY), Offset(540f, eyeY))
        val squash = (1 - f.talk * 0.12).toFloat()

        // Blushy cheeks, rosier when he's happy.
        for ((i, c) in eyes.withIndex()) {
            val side = if (i == 0) -1f else 1f
            val soft = Paint().apply {
                color = cheekColor.copy(alpha = (0.25 + 0.45 * f.blush).toFloat())
                asFrameworkPaint().maskFilter = android.graphics.BlurMaskFilter(
                    10f * deviceScale, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            drawIntoCanvas { canvas ->
                canvas.drawOval(
                    Rect(
                        c.x - 45f + side * 62f, c.y + 96f,
                        c.x - 45f + side * 62f + 90f, c.y + 96f + 42f,
                    ),
                    soft,
                )
            }
        }

        for ((i, c) in eyes.withIndex()) {
            val side = if (i == 0) -1f else 1f

            // Little arched eyebrows do a lot of the acting.
            val browY = c.y - 112f - min(18f, max(-10f, f.browLift.toFloat())) * 0.7f
            withTransform({
                translate(c.x + side * 6f, browY)
                rotate(
                    Math.toDegrees(
                        f.browTilt * side * -1 + if (f.mood == Mood.THINKING && i == 1) -0.25 else 0.0,
                    ).toFloat(),
                )
            }) {
                val arch = Path().apply {
                    moveTo(-38f, 6f)
                    quadraticBezierTo(0f, -12f, 38f, 6f)
                }
                drawPath(arch, color = navy, style = Stroke(width = 14f, cap = StrokeCap.Round))
            }

            when {
                f.mood == Mood.RESTING -> {
                    // Peacefully closed: a soft downward curve.
                    val shut = Path().apply {
                        moveTo(c.x - 62f, c.y + 6f)
                        quadraticBezierTo(c.x, c.y + 52f, c.x + 62f, c.y + 6f)
                    }
                    drawPath(shut, color = ink, style = Stroke(width = 18f, cap = StrokeCap.Round))
                }

                f.mood == Mood.HAPPY && f.squint > 0.6 -> {
                    val arc = Path().apply {
                        moveTo(c.x - 60f, c.y + 34f)
                        quadraticBezierTo(c.x, c.y - 54f, c.x + 60f, c.y + 34f)
                    }
                    drawPath(arc, color = ink, style = Stroke(width = 24f, cap = StrokeCap.Round))
                }

                else -> {
                    val open = max(0.06f, 1f - f.closed.toFloat()) * squash
                    val whiteLeft = c.x - 95f
                    val whiteTop = c.y - 95f * open
                    val whiteW = 190f
                    val whiteH = 190f * open
                    val whitePath = Path().apply {
                        addOval(Rect(whiteLeft, whiteTop, whiteLeft + whiteW, whiteTop + whiteH))
                    }
                    drawPath(whitePath, color = Color.White)
                    if (open <= 0.2f) return@withTransform

                    var gx = f.gazeX.toFloat()
                    var gy = f.gazeY.toFloat()
                    val len = hypot(gx, gy)
                    if (len > 1) {
                        gx /= len
                        gy /= len
                    }
                    // Eyes converge a little when looking down close, and reach further for a livelier look.
                    val reach = 50f
                    val r = 46f * f.pupil.toFloat()
                    var pupilX = c.x + gx * reach - side * 3f
                    var pupilY = c.y + gy * reach * open
                    if (f.mood == Mood.SLEEPY) pupilY = max(pupilY, c.y) + 28f // eyes sink under heavy lids
                    clipPath(whitePath) {
                        drawCircle(ink, radius = r, center = Offset(pupilX, pupilY))
                        // Two catchlights make them shine.
                        drawOval(
                            Color.White,
                            topLeft = Offset(pupilX - r * 0.58f, pupilY - r * 0.72f),
                            size = Size(r * 0.56f, r * 0.56f),
                        )
                        drawOval(
                            Color.White.copy(alpha = 0.85f),
                            topLeft = Offset(pupilX + r * 0.28f, pupilY + r * 0.22f),
                            size = Size(r * 0.24f, r * 0.24f),
                        )
                        // Happy cheeks push up from below as he smiles.
                        if (f.squint > 0.02) {
                            fillSkin(Path().apply {
                                addOval(Rect(c.x - 110f, c.y + 95f * open - 70f * f.squint.toFloat(), c.x + 110f, c.y + 95f * open - 70f * f.squint.toFloat() + 160f))
                            })
                        }
                        // Heavy, droopy lids when he's getting sleepy.
                        if (f.mood == Mood.SLEEPY) {
                            val droop = 0.5 + 0.06 * sin(f.time * 1.3)
                            fillSkin(Path().apply {
                                addRect(Rect(c.x - 100f, whiteTop - 10f, c.x + 100f, whiteTop - 10f + whiteH * droop.toFloat() + 10f))
                            })
                            val lidY = whiteTop + whiteH * droop.toFloat()
                            drawLine(
                                navy.copy(alpha = 0.7f),
                                Offset(c.x - 92f, lidY),
                                Offset(c.x + 92f, lidY),
                                strokeWidth = 8f,
                                cap = StrokeCap.Round,
                            )
                        }
                        // A soft upper lid when he's focused on pointing.
                        if (f.mood == Mood.POINTING) {
                            fillSkin(Path().apply {
                                addRect(Rect(c.x - 100f, whiteTop - 20f, c.x + 100f, whiteTop + 20f))
                            })
                        }
                    }
                }
            }
        }

        if (f.mood == Mood.RESTING) {
            // Little z's floating up while he naps.
            repeat(3) { i ->
                val phase = ((f.time * 0.35 + i / 3.0) % 1.0).toFloat()
                val fontSize = 26f + i * 8f
                val point = Offset(
                    660f + phase * 70f + (sin(phase * 6f) * 8f).toFloat(),
                    90f - phase * 110f,
                )
                val alpha = (0.9 * sin(PI * phase)).toFloat().coerceIn(0f, 1f)
                drawText(
                    textMeasurer = textMeasurer,
                    text = "z",
                    topLeft = point,
                    style = TextStyle(
                        fontFamily = BlueyFonts.fredoka,
                        fontSize = designSp(fontSize),
                        color = Color.White.copy(alpha = alpha),
                    ),
                )
            }
        }

        if (f.mood == Mood.THINKING) {
            repeat(3) { i ->
                val wobble = (sin(f.time * 3 + i) * 4f).toFloat()
                drawOval(
                    Color.White.copy(alpha = 1f - i * 0.3f),
                    topLeft = Offset(690f + i * 26f, 40f - i * 12f + wobble),
                    size = Size(16f, 16f),
                )
            }
        }

        // His little mouth-nose: opens as he talks, curls into a smile when he's happy.
        val noseW = (60 - f.talk * 8).toFloat()
        val noseH = (38 + f.talk * 34).toFloat()
        val noseLeft = 410f - noseW / 2f
        val noseTop = (272 - f.talk * 6).toFloat()
        if (f.mood == Mood.HAPPY && f.talk < 0.1) {
            val smile = Path().apply {
                moveTo(noseLeft - 8f, noseTop + 8f)
                quadraticBezierTo(noseLeft + noseW / 2f, noseTop + 58f, noseLeft + noseW + 8f, noseTop + 8f)
                close()
            }
            drawPath(smile, color = navy)
        } else {
            drawOval(navy, topLeft = Offset(noseLeft, noseTop), size = Size(noseW, noseH))
            if (f.talk > 0.25) {
                drawOval(
                    tongueColor.copy(alpha = 0.9f),
                    topLeft = Offset(noseLeft + noseW / 2f - noseW * 0.28f, noseTop + noseH - noseH * 0.38f),
                    size = Size(noseW * 0.56f, noseH * 0.3f),
                )
            }
        }
    }
}

/** Design px → sp so text drawn inside the scaled canvas lands at the intended device size. */
private fun DrawScope.designSp(size: Float) =
    (size / (density * fontScale)).sp
