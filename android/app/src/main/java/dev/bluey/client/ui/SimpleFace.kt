package dev.bluey.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bluey.client.link.FaceState
import kotlinx.coroutines.android.awaitFrame

/**
 * The M1 connected face: the blob with open eyes whose pupils follow the incoming
 * gaze — live proof the face stream is driving. The full FaceView (springs, brows,
 * crown, blinks, moods) is M2 and replaces this.
 */
@Composable
fun SimpleFace(face: FaceState, pcName: String?) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            t = System.nanoTime() / 1_000_000_000f
            awaitFrame()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val breathe = 1f + 0.02f * kotlin.math.sin(1.8f * t)
            val blobW = 300f * breathe
            val blobH = 260f * breathe
            val left = w / 2f - blobW / 2f
            val top = h / 2f - blobH / 2f

            translate(left, top) {
                val path = BlobShape.path(blobW, blobH)
                drawPath(path, BlobShape.gradientBrush(blobW, blobH))
                drawPath(path, BlobShape.shineBrush(blobW, blobH, radius = blobH * 0.42f))

                // Two open eyes with pupils that follow the gaze.
                val eyeY = blobH * 0.42f
                val eyeR = blobW * 0.13f
                val eyeSpread = blobW * 0.18f
                val pupilR = eyeR * 0.46f
                val reach = eyeR - pupilR - 2f
                val gazeX = face.gazeX.toFloat()
                val gazeY = face.gazeY.toFloat()

                for (side in -1..1 step 2) {
                    val center = Offset(blobW / 2f + side * eyeSpread, eyeY)
                    drawCircle(Color.White, eyeR, center)
                    val pupil = Offset(
                        center.x + gazeX * reach - side * 3f, // slight convergence
                        center.y + gazeY * reach,
                    )
                    drawCircle(Palette.ink, pupilR, pupil)
                    drawCircle(
                        Color.White.copy(alpha = 0.9f),
                        pupilR * 0.28f,
                        Offset(pupil.x - pupilR * 0.35f, pupil.y - pupilR * 0.35f),
                    )
                }

                // Nose below the eyes.
                drawOval(
                    Palette.nose,
                    topLeft = Offset(blobW / 2f - 9f, eyeY + eyeR + 14f),
                    size = Size(18f, 12f),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
        ) {
            if (pcName != null) {
                Text(
                    text = pcName,
                    color = Palette.inkSoft,
                    fontSize = 13.sp,
                    fontFamily = BlueyFonts.plexMono,
                )
            }
            Text(
                text = face.mood.name.lowercase(),
                color = Palette.berry1,
                fontSize = 13.sp,
                fontFamily = BlueyFonts.plexMono,
            )
        }
    }
}
