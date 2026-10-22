package dev.bluey.client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.bluey.client.link.FaceState
import dev.bluey.client.link.Mood
import dev.bluey.client.link.Packet
import dev.bluey.client.link.PcLink
import dev.bluey.client.realtime.ChirpPlayer
import dev.bluey.client.realtime.LiveVoice
import dev.bluey.client.realtime.MicStreamer
import dev.bluey.client.realtime.OkHttpTransport
import dev.bluey.client.session.SessionStore
import dev.bluey.client.ui.BlueyAppView
import dev.bluey.client.ui.FaceAnimator
import dev.bluey.client.ui.FaceView
import dev.bluey.client.ui.ModeIndicator
import dev.bluey.client.ui.PairingView
import dev.bluey.client.ui.SoundButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private lateinit var link: PcLink
    private lateinit var live: LiveVoice
    private lateinit var store: SessionStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val faceState = mutableStateOf(FaceState.create())
    private val liveState = mutableStateOf(LiveVoice.State.Asleep)
    private val sessionError = mutableStateOf<String?>(null)
    private var micPermissionAnswer: ((Boolean) -> Unit)? = null

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micPermissionAnswer?.invoke(granted)
        micPermissionAnswer = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        link = PcLink(this)
        store = SessionStore(this, appScope)
        val animator = FaceAnimator()

        live = LiveVoice(
            scope = appScope,
            makeTransport = { OkHttpTransport() },
            mic = MicStreamer(appScope),
            chirps = ChirpPlayer(appScope),
            volumePersistence = prefsVolume(),
            requestMicPermission = { done ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    done(true)
                } else {
                    micPermissionAnswer = done
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )
        wireLiveVoice(animator)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            BlueyRoot(link, live, store, animator, faceState, liveState, sessionError)
        }
    }

    /** Connects the live voice to the PC: keys, tools, captions and wake/sleep. */
    private fun wireLiveVoice(animator: FaceAnimator) {
        link.onFace = { face -> faceState.value = face }
        link.onCommand = { command ->
            when (command) {
                "wake" -> live.wake()
                "sleep" -> live.sleep()
            }
        }
        animator.localTalk = { live.level }

        live.requestToken = { done ->
            appScope.launch {
                val reply = obtainToken()
                when {
                    reply?.text != null -> done(reply.text, reply.model, null)
                    reply?.error != null -> done(null, null, reply.error)
                    else -> done(null, null, LiveVoice.NO_PC)
                }
            }
        }
        live.runTool = { name, arguments, done ->
            link.request(Packet(command = "tool", tool = name, text = arguments)) { reply ->
                done(reply?.text ?: "The PC didn't answer.", reply?.image)
            }
        }
        live.onSessionStart = {
            store.start()
            live.recorder.start(store.current?.id ?: "")
        }
        live.resumeContext = { store.recentText() }
        live.onSessionEnd = { store.end() }
        live.onUserTurn = { item, asked -> store.placeholder(item, asked) }
        live.onUserWords = { item, text, asked -> store.heard(item, text, asked) }
        live.onReply = { text -> store.reply(text) }
        live.onReport = { text -> store.report(text) }
        live.onCaption = { text, finished ->
            link.send(Packet(command = if (finished) "captionDone" else "caption", text = text))
        }
        live.onSessionError = { message -> sessionError.value = message }
        live.onStateChange = { state ->
            liveState.value = state
            if (state != LiveVoice.State.Asleep) sessionError.value = null // a new attempt is under way
            when (state) {
                LiveVoice.State.Asleep -> {
                    animator.awake = false
                    animator.localMood = null
                    link.send(Packet(command = "asleep"))
                }
                LiveVoice.State.Waking -> {
                    animator.awake = true
                    animator.localMood = Mood.HAPPY
                }
                LiveVoice.State.Listening -> {
                    animator.awake = true
                    animator.localMood = null
                    link.send(Packet(command = "awake"))
                }
                LiveVoice.State.Asking -> {
                    animator.awake = true
                    animator.localMood = Mood.LISTENING // all ears while you hold
                    link.send(Packet(command = "awake"))
                }
                LiveVoice.State.Thinking -> animator.localMood = Mood.THINKING
                LiveVoice.State.Speaking -> animator.localMood = Mood.TALKING
            }
        }
    }

    /** Asks the PC for a live token with the patience a flaky Wi-Fi link needs
     * (v0.3.3). The whole budget stays inside LiveVoice's 20-second wake watchdog:
     * wait briefly for a link that may still be landing (fresh from the background),
     * ask with a 6-second reply timeout, and if the PC stays silent on a link that
     * claims to be up — the phone→PC black hole seen on 2026-10-22, where faces kept
     * flowing but the token request never reached the PC — kill that zombie link,
     * reconnect and ask once more on the fresh, handshake-verified connection. */
    private suspend fun obtainToken(): Packet? {
        awaitLink(2_500)
        var reply = askPcForToken()
        if (reply == null) {
            link.forceReconnect()
            if (awaitLink(3_500)) reply = askPcForToken()
        }
        return reply
    }

    /** Waits up to `timeoutMs` for the PC link to report connected. */
    private suspend fun awaitLink(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            link.connected.first { it }
            true
        } ?: false

    /** One realtimeToken request, null when the PC didn't answer in time. The
     * continuation and the reply callback both live on the main dispatcher, so the
     * isActive check makes a late reply after cancellation a quiet no-op. */
    private suspend fun askPcForToken(): Packet? =
        suspendCancellableCoroutine { cont ->
            link.request(Packet(command = "realtimeToken"), timeoutMs = 6_000) { reply ->
                if (cont.isActive) cont.resumeWith(Result.success(reply))
            }
        }

    private fun prefsVolume(): LiveVoice.VolumePersistence {
        val prefs = getSharedPreferences("bluey", MODE_PRIVATE)
        return object : LiveVoice.VolumePersistence {
            override fun get(): Double = prefs.getFloat("volume", 1f).toDouble()
            override fun set(value: Double) {
                prefs.edit().putFloat("volume", value.toFloat()).apply()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        link.start()
        store.resyncRecent()
    }

    override fun onStop() {
        // The link stops (like the original's scenePhase handler). The session itself
        // stays alive; Android silences background mics — a foreground mic service is
        // planned for M6 polish (see PROGRESS.md known issues).
        link.stop()
    }

    override fun onDestroy() {
        if (isFinishing) live.sleep()
        super.onDestroy()
    }
}

@Composable
private fun BlueyRoot(
    link: PcLink,
    live: LiveVoice,
    store: SessionStore,
    animator: FaceAnimator,
    faceState: androidx.compose.runtime.State<FaceState>,
    liveState: androidx.compose.runtime.State<LiveVoice.State>,
    sessionError: androidx.compose.runtime.MutableState<String?>,
) {
    val connected by link.connected.collectAsState()
    val target by link.target.collectAsState()
    val showPairing = remember { mutableStateOf(true) }
    var showApp by remember { mutableStateOf(false) }
    val view = LocalView.current

    // One clock for the animator: face packets stamp arrivals, frames step the springs.
    val now: () -> Double = remember {
        val start = System.nanoTime()
        return@remember { (System.nanoTime() - start) / 1_000_000_000.0 }
    }

    LaunchedEffect(connected) {
        if (connected) showPairing.value = false
    }

    LaunchedEffect(faceState.value) {
        animator.receive(faceState.value, now())
    }

    // A session problem is never silent: it stays up until the next attempt (and a
    // 30-second tidy in case the phone gets pocketed mid-error).
    LaunchedEffect(sessionError.value) {
        if (sessionError.value != null) {
            delay(30_000)
            sessionError.value = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // His face: double tap wakes/sleeps, press-and-hold asks.
        FaceView(
            animator,
            now,
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { live.toggle() }, // double tap: wake up / back to follow mode
                    )
                }
                .pointerInput(live) {
                    holdToAsk(
                        onHold = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            live.beginAsk()
                        },
                        onRelease = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            live.endAsk()
                        },
                    )
                },
        )

        ModeIndicator(liveState.value)

        SoundButton(link, live)

        // Port addition: while the PC link is down, a tiny honest note — without it a
        // dropped link looks exactly like him ignoring you.
        if (!connected) {
            Text(
                text = target?.let { "PC: trying $it…" } ?: "PC: not connected — looking…",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp,
                fontFamily = dev.bluey.client.ui.BlueyFonts.plexMono,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = 18.dp),
            )
        }

        // While the session is still coming up, say so — a smile alone tells you nothing.
        if (liveState.value == LiveVoice.State.Waking) {
            Text(
                text = "Waking up — connecting to Gemini… (the mic is already on)",
                color = Color(0xFFF4F1FA).copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontFamily = dev.bluey.client.ui.BlueyFonts.plexSans,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 28.dp, vertical = 30.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // A session error (no PC, bad key, lost session) — bottom-center, above his face.
        // Stays until the next wake attempt, so it can't be missed.
        sessionError.value?.let { message ->
            Text(
                text = message,
                color = Color(0xFFF4F1FA),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = dev.bluey.client.ui.BlueyFonts.plexSans,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 28.dp, vertical = 30.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        // Bluey's app: sessions, transcripts and settings (a faint button, top-right).
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 62.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
                .pointerInput(Unit) {
                    detectTapGestures { showApp = true }
                }
                .padding(10.dp),
        ) {
            Text("▦", color = Color.White.copy(alpha = 0.35f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }

        if (showApp) {
            BlueyAppView(store, live, link) { showApp = false }
        }

        AnimatedVisibility(
            visible = showPairing.value && !connected,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PairingView(link, onPlay = { showPairing.value = false })
        }
    }
}

/** Press and hold the screen to ask him something; let go and he answers.
 *  A quick tap isn't a hold: 0.3 s, exactly like the original. */
private suspend fun PointerInputScope.holdToAsk(
    onHold: () -> Unit,
    onRelease: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val releasedEarly = withTimeoutOrNull(300) {
            var up = false
            while (!up) {
                val event = awaitPointerEvent()
                up = event.changes.none { it.id == down.id && it.pressed }
            }
            true
        }
        if (releasedEarly == null) {
            // The finger is still down after 300 ms: this is a hold.
            onHold()
            var up = false
            while (!up) {
                val event = awaitPointerEvent()
                up = event.changes.none { it.id == down.id && it.pressed }
            }
            onRelease()
        }
    }
}
