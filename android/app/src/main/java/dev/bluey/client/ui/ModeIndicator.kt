package dev.bluey.client.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bluey.client.realtime.LiveVoice
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shows which mode he's in: a solid border hugging the screen's own rounded corners
 * (blue while he listens), plus a small label chip in the corner. Port of
 * <c>ModeIndicator</c> in iOS/Sources/GooglyApp.swift — same states, colors, sizes
 * and pulse rates. No glows.
 */
@Composable
fun ModeIndicator(state: LiveVoice.State, modifier: Modifier = Modifier) {
    val look = lookFor(state)

    val phase = if (look.pulse > 0) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween((1000 / look.pulse).toInt(), easing = LinearEasing)),
            label = "phase",
        ).value
    } else 0f
    val wave = if (look.pulse > 0) 0.5f + 0.5f * sin(2 * PI * phase).toFloat() else 1f

    Box(modifier.fillMaxSize()) {
        if (look.border > 0.dp) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .padding(look.border / 2f),
            ) {
                drawRoundRect(
                    color = look.color.copy(alpha = if (look.pulse > 0) 0.65f + 0.35f * wave else 1f),
                    cornerRadius = CornerRadius(55.dp.toPx(), 55.dp.toPx()),
                    style = Stroke(look.border.toPx()),
                )
            }
        }

        if (look.label == null) {
            Box(
                Modifier
                    .offset(x = 24.dp + look.border, y = 14.dp + look.border)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                ModeIconCanvas(look.icon, look.color.copy(alpha = 0.7f))
            }
        } else {
            Row(
                Modifier
                    .offset(x = 24.dp + look.border, y = 14.dp + look.border)
                    .clip(RoundedCornerShape(50))
                    .background(look.color)
                    .padding(horizontal = 14.dp)
                    .height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeIconCanvas(look.icon, Color.White)
                Spacer(Modifier.width(7.dp))
                Text(look.label, color = Color.White, fontSize = 16.sp, fontFamily = BlueyFonts.fredoka)
            }
        }
    }
}

private data class Look(
    val color: Color,
    val label: String?,
    val icon: ModeIcon,
    val border: Dp,
    val pulse: Double, // pulses per second (opacity only)
)

private val blue = Color(0xFF4F8BFF)

private fun lookFor(state: LiveVoice.State) = when (state) {
    LiveVoice.State.Asleep -> Look(Palette.inkSoft, null, ModeIcon.Eye, 0.dp, 0.0)
    LiveVoice.State.Waking -> Look(Color(0xFFFFD66B), "Waking up", ModeIcon.Sun, 6.dp, 1.6)
    LiveVoice.State.Listening -> Look(blue, "Listening · hold to ask", ModeIcon.Ear, 10.dp, 0.0)
    LiveVoice.State.Asking -> Look(blue, "I'm all ears", ModeIcon.Mic, 16.dp, 1.2)
    LiveVoice.State.Thinking -> Look(Color(0xFFC79BFF), "Thinking", ModeIcon.Sparkles, 8.dp, 1.1)
    LiveVoice.State.Speaking -> Look(Color(0xFFFF9AD0), "Replying", ModeIcon.Bubble, 8.dp, 0.0)
}

enum class ModeIcon { Eye, Sun, Ear, Mic, Sparkles, Bubble }

@Composable
private fun ModeIconCanvas(icon: ModeIcon, tint: Color) {
    Canvas(Modifier.size(14.dp)) {
        drawIcon(icon, tint, Offset.Zero, size.width)
    }
}

private fun DrawScope.drawIcon(icon: ModeIcon, tint: Color, topLeft: Offset, size: Float) {
    val right = topLeft.x + size
    val bottom = topLeft.y + size
    val cx = topLeft.x + size / 2
    val cy = topLeft.y + size / 2
    val stroke = Stroke(width = size / 9f, cap = StrokeCap.Round)
    when (icon) {
        ModeIcon.Eye -> {
            val eye = Path().apply {
                moveTo(topLeft.x, cy)
                cubicTo(cx - size / 6, topLeft.y, cx + size / 6, topLeft.y, right, cy)
                cubicTo(cx + size / 6, bottom, cx - size / 6, bottom, topLeft.x, cy)
                close()
            }
            drawPath(eye, tint, style = stroke)
            drawCircle(tint, radius = size / 7f, center = Offset(cx, cy))
        }
        ModeIcon.Sun -> {
            drawCircle(tint, radius = size / 4f, center = Offset(cx, cy))
            for (i in 0 until 8) {
                val a = PI / 4 * i
                val inner = size * 0.38f
                val outer = size * 0.5f
                drawLine(
                    tint,
                    Offset(cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat()),
                    Offset(cx + (outer * cos(a)).toFloat(), cy + (outer * sin(a)).toFloat()),
                    strokeWidth = size / 10f,
                    cap = StrokeCap.Round,
                )
            }
        }
        ModeIcon.Ear -> {
            val ear = Path().apply {
                moveTo(cx - size * 0.05f, bottom - size * 0.05f)
                cubicTo(topLeft.x, bottom - size * 0.45f, topLeft.x + size * 0.1f, topLeft.y, cx, topLeft.y + size * 0.05f)
                cubicTo(right - size * 0.05f, topLeft.y + size * 0.15f, right - size * 0.05f, cy, cx, cy)
            }
            drawPath(ear, tint, style = stroke)
        }
        ModeIcon.Mic -> {
            val r = size * 0.16f
            drawRoundRect(
                tint,
                topLeft = Offset(cx - r, topLeft.y),
                size = androidx.compose.ui.geometry.Size(r * 2, size * 0.55f),
                cornerRadius = CornerRadius(r, r),
            )
            val arm = Path().apply {
                moveTo(cx - size * 0.28f, cy)
                cubicTo(cx - size * 0.28f, bottom - size * 0.18f, cx, bottom - size * 0.12f, cx, bottom - size * 0.05f)
                cubicTo(cx, bottom - size * 0.12f, cx + size * 0.28f, bottom - size * 0.18f, cx + size * 0.28f, cy)
            }
            drawPath(arm, tint, style = stroke)
        }
        ModeIcon.Sparkles -> {
            fun star(cx: Float, cy: Float, r: Float) {
                val star = Path()
                for (i in 0 until 8) {
                    val a = PI / 4 * i - PI / 2
                    val radius = if (i % 2 == 0) r else r * 0.35f
                    val x = cx + (radius * cos(a)).toFloat()
                    val y = cy + (radius * sin(a)).toFloat()
                    if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
                }
                star.close()
                drawPath(star, tint)
            }
            star(cx - size * 0.12f, cy - size * 0.06f, size * 0.34f)
            star(cx + size * 0.24f, cy + size * 0.22f, size * 0.18f)
        }
        ModeIcon.Bubble -> {
            drawRoundRect(
                tint,
                topLeft = Offset(topLeft.x, topLeft.y),
                size = androidx.compose.ui.geometry.Size(size, size * 0.72f),
                cornerRadius = CornerRadius(size * 0.2f, size * 0.2f),
            )
            val tail = Path().apply {
                moveTo(cx - size * 0.2f, topLeft.y + size * 0.7f)
                lineTo(cx - size * 0.28f, bottom)
                lineTo(cx + size * 0.05f, topLeft.y + size * 0.7f)
                close()
            }
            drawPath(tail, tint)
        }
    }
}
