package dev.bluey.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bluey.client.link.ManualHost
import dev.bluey.client.link.PcLink
import kotlinx.coroutines.android.awaitFrame

/**
 * Shown until the phone finds the PC — the port of iOS/Sources/PairingView.swift:
 * a sleepy breathing blob with bobbing z's, how to wake him, a spinner while looking,
 * and the "Play without the PC" escape hatch. Port addition (D12): a typed PC
 * address for networks where mDNS can't see the PC (USB tethering, strict Wi-Fi).
 */
@Composable
fun PairingView(link: PcLink, onPlay: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var trying by remember { mutableStateOf<String?>(null) }
    var invalid by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(56.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 72.dp),
    ) {
        SleepyBlob(
            modifier = Modifier
                .width(220.dp)
                .height(190.dp)
                .align(Alignment.CenterVertically),
        )

        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxHeight(),
        ) {
            Text(
                text = "Wake me up from your PC",
                color = Color(0xFFF4F1FA),
                fontSize = 32.sp,
                fontFamily = BlueyFonts.fredoka,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Open Bluey in your PC's menu bar. Keep both on the same Wi-Fi and I'll find it.",
                color = Palette.inkSoft,
                fontSize = 16.sp,
                fontFamily = BlueyFonts.plexSans,
                modifier = Modifier.widthIn(max = 380.dp),
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(
                    color = Palette.berry1,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .width(20.dp)
                        .height(20.dp),
                )
                Text(
                    text = "Looking for your PC",
                    color = Palette.inkSoft,
                    fontSize = 13.sp,
                    fontFamily = BlueyFonts.plexMono,
                )
            }
            Spacer(Modifier.height(22.dp))
            Box(
                modifier = Modifier
                    .clickable(onClick = onPlay)
                    .background(Palette.panel, RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp)
                    .height(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Play without the PC",
                    color = Color(0xFFF4F1FA),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = BlueyFonts.plexSans,
                )
            }

            // Port addition (D12): connect by address — the way in on USB tethering,
            // where mDNS never crosses the cable. The PC shows its address in its tray menu.
            Spacer(Modifier.height(26.dp))
            Text(
                text = "Can't find it? (USB cable, strict Wi-Fi)",
                color = Palette.inkSoft,
                fontSize = 12.sp,
                fontFamily = BlueyFonts.plexSans,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        invalid = false
                    },
                    singleLine = true,
                    placeholder = {
                        Text("192.168.42.196", color = Palette.inkSoft, fontSize = 13.sp)
                    },
                    isError = invalid,
                    textStyle = TextStyle(fontSize = 13.sp, color = Color(0xFFF4F1FA)),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Palette.berry1,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                        errorBorderColor = Color(0xFFE5547A),
                        cursorColor = Palette.berry1,
                    ),
                    modifier = Modifier.width(210.dp),
                )
                Box(
                    modifier = Modifier
                        .clickable {
                            if (link.setManualHost(address)) {
                                trying = ManualHost.parse(address.trim())?.toString()
                                invalid = false
                            } else {
                                invalid = true
                            }
                        }
                        .background(Palette.panel, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp)
                        .height(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Connect",
                        color = Color(0xFFF4F1FA),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = BlueyFonts.plexSans,
                    )
                }
            }
            if (invalid) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "That doesn't look like an address — try 192.168.42.196 or 192.168.42.196:51826",
                    color = Color(0xFFE5547A),
                    fontSize = 11.sp,
                    fontFamily = BlueyFonts.plexSans,
                )
            }
            trying?.let { addressText ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Trying $addressText… (the address is in Bluey's tray menu on the PC)",
                    color = Palette.inkSoft,
                    fontSize = 11.sp,
                    fontFamily = BlueyFonts.plexSans,
                )
            }
        }
    }
}

/**
 * The breathing, sleeping blob with closed eyes, a nose, and bobbing z's — the whole
 * thing breathes together (scaleEffect around the bottom, like the original).
 */
@Composable
fun SleepyBlob(modifier: Modifier = Modifier) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            t = System.nanoTime() / 1_000_000_000f
            awaitFrame()
        }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val breathe = 1f + 0.02f * sin(1.1f * t)
        val bw = w * breathe
        val bh = h * breathe
        // scaleEffect anchored at the bottom-center
        translate(left = (w - bw) / 2f, top = h - bh) {
            val path = BlobShape.path(bw, bh)
            drawPath(path, BlobShape.gradientBrush(bw, bh))
            drawPath(path, BlobShape.shineBrush(bw, bh, radius = 80f * (bh / 190f)))
            // Closed eyes: two capsules, 46×7, centered 26 apart, 18 above center.
            val eyeY = bh / 2f - 18f
            drawRoundRect(
                color = Palette.ink,
                topLeft = Offset(bw / 2f - 13f - 23f, eyeY - 3.5f),
                size = Size(46f, 7f),
                cornerRadius = CornerRadius(3.5f, 3.5f),
            )
            drawRoundRect(
                color = Palette.ink,
                topLeft = Offset(bw / 2f + 13f, eyeY - 3.5f),
                size = Size(46f, 7f),
                cornerRadius = CornerRadius(3.5f, 3.5f),
            )
            // Nose: 18×12 ellipse at (4, 21) from center.
            drawOval(
                color = Palette.nose,
                topLeft = Offset(bw / 2f + 4f - 9f, bh / 2f + 21f - 6f),
                size = Size(18f, 12f),
            )
            // Bobbing z's, centered at (110, -95) and (128, -122) from the middle.
            drawZ(
                center = Offset(bw / 2f + 110f * (bw / 220f), bh / 2f - 95f * (bh / 190f) - 4f * sin(1.5f * t)),
                sizePx = 26f * (bh / 190f),
                color = Palette.berry3,
            )
            drawZ(
                center = Offset(bw / 2f + 128f * (bw / 220f), bh / 2f - 122f * (bh / 190f) - 4f * sin(1.5f * t + 1f)),
                sizePx = 18f * (bh / 190f),
                color = Palette.berry4,
            )
        }
    }
}

/** A little "z" drawn with three strokes — the sleepy-time classic. */
private fun DrawScope.drawZ(center: Offset, sizePx: Float, color: Color) {
    val strokeWidth = sizePx * 0.16f
    val half = sizePx / 2f
    val a = Offset(center.x - half, center.y - half)
    val b = Offset(center.x + half, center.y - half)
    val c = Offset(center.x - half, center.y + half)
    val d = Offset(center.x + half, center.y + half)
    drawLine(color, a, b, strokeWidth, StrokeCap.Round)
    drawLine(color, b, c, strokeWidth, StrokeCap.Round)
    drawLine(color, c, d, strokeWidth, StrokeCap.Round)
}

private fun sin(v: Float): Float = kotlin.math.sin(v)
