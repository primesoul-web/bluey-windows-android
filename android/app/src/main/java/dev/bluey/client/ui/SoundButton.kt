package dev.bluey.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bluey.client.link.PcLink
import dev.bluey.client.realtime.LiveVoice
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * A tiny, quiet speaker button in the top-right corner. Tap it for the chirp volume,
 * a test hi, and which PC to pair with. Port of <c>SoundButton.swift</c> — the panel
 * tucks itself away after six quiet seconds so it never ends up in a shot.
 */
@Composable
fun SoundButton(link: PcLink, live: LiveVoice, modifier: Modifier = Modifier) {
    var volume by remember { mutableStateOf(live.volume.toFloat()) }
    var open by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val connected by link.connected.collectAsState()
    val pcName by link.pcName.collectAsState()
    val pcs by link.pcs.collectAsState()
    val currentPc by link.currentPc.collectAsState()

    fun touch() {
        lastTouch = System.currentTimeMillis()
    }

    // Tuck the panel away after a few quiet seconds so it never ends up in a shot.
    LaunchedEffect(lastTouch) {
        delay(6_000)
        open = false
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(top = 10.dp, end = 14.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (open) 0.14f else 0.06f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    open = !open
                    touch()
                },
            contentAlignment = Alignment.Center,
        ) {
            SpeakerIcon(
                level = volume.toDouble(),
                crossed = open,
                tint = Color.White.copy(alpha = if (open) 0.9f else 0.35f),
            )
        }

        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.9f, animationSpec = tween(300)),
            exit = fadeOut(tween(250)) + scaleOut(targetScale = 0.9f),
        ) {
            Column(
                Modifier
                    .padding(top = 10.dp)
                    .width(260.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.panel.copy(alpha = 0.95f))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "CHIRP VOLUME",
                        color = Palette.inkSoft,
                        fontSize = 12.sp,
                        fontFamily = BlueyFonts.plexMono,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(volume * 100).roundToInt()}%",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontFamily = BlueyFonts.plexMono,
                    )
                }
                Slider(
                    value = volume,
                    onValueChange = {
                        volume = it
                        live.volume = it.toDouble()
                        touch()
                    },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Palette.berry2,
                        activeTrackColor = Palette.berry2,
                        inactiveTrackColor = Color.White.copy(alpha = 0.12f),
                    ),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BlobShape.gradientBrush(260f, 44f))
                        .alpha(if (connected) 1f else 0.4f)
                        .clickable(enabled = connected) {
                            live.sayHi()
                            touch()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (live.state == LiveVoice.State.Asleep) "Wake him up" else "Say hi",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontFamily = BlueyFonts.plexSans,
                    )
                }

                if (pcs.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "PC",
                        color = Palette.inkSoft,
                        fontSize = 12.sp,
                        fontFamily = BlueyFonts.plexMono,
                    )
                    for (name in pcs) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .clickable {
                                    link.choose(name)
                                    touch()
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                name,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontFamily = BlueyFonts.plexSans,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            if (name == currentPc) {
                                if (connected) {
                                    CheckIcon(Palette.berry2)
                                } else {
                                    Text("•••", color = Palette.berry2, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }

                if (!connected) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Connect to your PC to talk to him.",
                        color = Palette.inkSoft,
                        fontSize = 12.sp,
                        fontFamily = BlueyFonts.plexSans,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeakerIcon(level: Double, crossed: Boolean, tint: Color) {
    Canvas(Modifier.size(15.dp)) {
        val s = size.width
        val body = Path().apply {
            moveTo(0f, s * 0.32f)
            lineTo(s * 0.3f, s * 0.32f)
            lineTo(s * 0.58f, s * 0.05f)
            lineTo(s * 0.58f, s * 0.95f)
            lineTo(s * 0.3f, s * 0.68f)
            lineTo(0f, s * 0.68f)
            close()
        }
        drawPath(body, tint)
        when {
            level < 0.01 -> {
                drawLine(
                    tint,
                    Offset(s * 0.68f, s * 0.28f),
                    Offset(s * 1.0f, s * 0.72f),
                    strokeWidth = s * 0.12f,
                    cap = StrokeCap.Round,
                )
            }
            level < 0.5 -> {
                drawArc(
                    tint,
                    startAngle = -50f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(s * 0.62f, s * 0.3f),
                    size = Size(s * 0.36f, s * 0.4f),
                    style = Stroke(s * 0.11f, cap = StrokeCap.Round),
                )
            }
            else -> {
                drawArc(
                    tint,
                    startAngle = -50f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(s * 0.62f, s * 0.3f),
                    size = Size(s * 0.36f, s * 0.4f),
                    style = Stroke(s * 0.11f, cap = StrokeCap.Round),
                )
                drawArc(
                    tint,
                    startAngle = -50f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(s * 0.52f, s * 0.18f),
                    size = Size(s * 0.56f, s * 0.64f),
                    style = Stroke(s * 0.11f, cap = StrokeCap.Round),
                )
            }
        }
        if (crossed) {
            // An × in place of the speaker body while the panel is open (xmark).
            drawLine(
                tint,
                Offset(s * 0.15f, s * 0.15f),
                Offset(s * 0.85f, s * 0.85f),
                strokeWidth = s * 0.14f,
                cap = StrokeCap.Round,
            )
            drawLine(
                tint,
                Offset(s * 0.85f, s * 0.15f),
                Offset(s * 0.15f, s * 0.85f),
                strokeWidth = s * 0.14f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun CheckIcon(tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val s = size.width
        drawLine(
            tint,
            Offset(s * 0.1f, s * 0.55f),
            Offset(s * 0.38f, s * 0.85f),
            strokeWidth = s * 0.14f,
            cap = StrokeCap.Round,
        )
        drawLine(
            tint,
            Offset(s * 0.38f, s * 0.85f),
            Offset(s * 0.92f, s * 0.18f),
            strokeWidth = s * 0.14f,
            cap = StrokeCap.Round,
        )
    }
}

