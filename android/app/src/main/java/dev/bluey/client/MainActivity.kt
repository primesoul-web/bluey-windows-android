package dev.bluey.client

import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.bluey.client.link.FaceState
import dev.bluey.client.link.Packet
import dev.bluey.client.link.PcLink
import dev.bluey.client.ui.PairingView
import dev.bluey.client.ui.SimpleFace

class MainActivity : ComponentActivity() {

    private lateinit var link: PcLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        link = PcLink(this)
        link.onFace = { face -> faceState.value = face }
        link.onCommand = { command ->
            // M1 stand-in for LiveVoice's state sync (M2 replaces this): the PC's
            // wake/sleep broadcasts flip our awake flag and are acknowledged back.
            when (command) {
                "wake" -> {
                    awake.value = true
                    link.send(Packet(command = "awake"))
                }
                "sleep" -> {
                    awake.value = false
                    link.send(Packet(command = "asleep"))
                }
            }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            BlueyRoot(link, faceState, awake)
        }
    }

    override fun onStart() {
        super.onStart()
        link.start()
    }

    override fun onStop() {
        super.onStop()
        link.stop()
    }

    private val faceState = mutableStateOf(FaceState.create())
    private val awake = mutableStateOf(false)
}

@Composable
private fun BlueyRoot(
    link: PcLink,
    faceState: androidx.compose.runtime.State<FaceState>,
    awake: androidx.compose.runtime.State<Boolean>,
) {
    val connected by link.connected.collectAsState()
    val pcName by link.pcName.collectAsState()
    val face by faceState
    val showPairing = remember { mutableStateOf(true) }

    LaunchedEffect(connected) {
        if (connected) showPairing.value = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        SimpleFace(face = face, pcName = if (connected) pcName else null)

        AnimatedVisibility(
            visible = showPairing.value && !connected,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PairingView(onPlay = { showPairing.value = false })
        }
    }
}
