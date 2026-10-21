package dev.bluey.client.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A live session with the Gemini Live API, running on the phone. Once he's awake the
 * mic is on the whole time, so everything you say becomes context, but he stays quiet
 * until you hold the screen and ask — manual activity detection means the server only
 * answers when the phone signals the end of a held question. He never talks out loud:
 * the model's output transcription becomes his text bubble, with a little cartoon chirp
 * here. Tools (look at the screen, point) run on the PC. Full port of
 * <c>iOS/Sources/LiveVoice.swift</c> on the Gemini Live API (D11).
 */
class LiveVoice(
    private val scope: CoroutineScope,
    private val makeTransport: () -> RealtimeTransport,
    val recorder: ChunkRecorder = ChunkRecorder(),
    private val mic: MicSource,
    private val chirps: ChirpSink,
    private val volumePersistence: VolumePersistence = VolumePersistence.None,
    private val requestMicPermission: (granted: (Boolean) -> Unit) -> Unit = { it(true) },
) {
    enum class State { Asleep, Waking, Listening, Asking, Thinking, Speaking }

    /** Where the chirp volume lives between launches (UserDefaults "volume" in the original). */
    interface VolumePersistence {
        fun get(): Double
        fun set(value: Double)

        companion object {
            val None = object : VolumePersistence {
                private var stored = 1.0
                override fun get() = stored
                override fun set(value: Double) {
                    stored = value
                }
            }
        }
    }

    var state: State = State.Asleep
        private set

    /** Asks the PC for a short-lived token, and the model it was minted for.
     * Calls back with a null token on failure; `error` carries the user-facing reason
     * ([NO_PC] when the PC couldn't be reached, the PC's mint message otherwise). */
    var requestToken: ((callback: (token: String?, model: String?, error: String?) -> Unit) -> Unit)? = null

    /** Runs a tool on the PC: (name, JSON arguments) → (output text, optional JPEG base64). */
    var runTool: ((name: String, arguments: String, done: (output: String, image: String?) -> Unit) -> Unit)? = null

    /** What he's saying, as it streams in. `done` is true when the reply finished. */
    var onCaption: ((text: String, done: Boolean) -> Unit)? = null
    var onStateChange: ((State) -> Unit)? = null

    /** A session couldn't start or was lost — the reason, shown on the phone itself
     * (port addition: the original had no phone-side error surface, so failures on
     * mDNS-blind networks looked like silence). */
    var onSessionError: ((message: String) -> Unit)? = null

    /** For the saved transcript: a session started or ended, something you said, his replies. */
    var onSessionStart: (() -> Unit)? = null
    var onSessionEnd: (() -> Unit)? = null
    var onUserTurn: ((itemID: String, asked: Boolean) -> Unit)? = null
    var onUserWords: ((itemID: String, text: String, asked: Boolean) -> Unit)? = null
    var onReply: ((text: String) -> Unit)? = null
    var onReport: ((text: String) -> Unit)? = null

    /** Recent transcript, handed to a fresh connection so he keeps context in long sessions. */
    var resumeContext: (() -> String?)? = null

    /** True from wake until you end the session. Dropped connections (the Live API resets
     * them every few minutes) reconnect on their own while this is set, and the recording
     * carries on. */
    private var wantAwake = false
    private var reconnecting = false

    /** Loudness of his chirp right now, 0…1. */
    val level: Double get() = chirps.level

    var volume: Double
        get() = volumePersistence.get()
        set(value) = volumePersistence.set(value)

    private var transport: RealtimeTransport? = null
    private var transcript = ""
    private var responseActive = false
    private var sleepAfterReply = false

    /** Holding the screen to ask. Released before the session was ready: ask as soon as it is. */
    private var holding = false
    private var askWhenReady = false
    private var chirpedThisResponse = false

    /** The locked session is live: nothing but the setup goes on the wire before this. */
    private var sessionReady = false
    private val pendingSends = mutableListOf<String>()

    /** Connects/mints that failed in a row without a setupComplete between them.
     * Three in a row means something's properly wrong — tell the user instead of
     * silently looping forever (port addition; the original retried endlessly). */
    private var consecutiveFailures = 0

    /** Turns that were questions (said while holding, or asked when you let go). The Live
     * API has no item ids, so turns are numbered here and sealed at each exchange's end. */
    private val askedItems = mutableSetOf<String>()
    private var userTurnSerial = 0
    private var heardText = ""
    private var turnAnnounced = false
    private var awaitingQuestion = false
    private var toolsRunning = 0

    init {
        mic.onChunk = { samples, count -> recorder.append(samples, count) }
        mic.onAudioBase64 { base64 ->
            scope.launch { send(RealtimeEvents.audioChunk(base64)) }
        }
    }

    private fun setState(new: State) {
        if (new == state) return
        state = new
        onStateChange?.invoke(new)
    }

    // MARK: Wake and sleep

    fun toggle() = if (state == State.Asleep) wake() else sleep()

    fun wake() {
        if (state != State.Asleep) return
        wantAwake = true
        setState(State.Waking)
        requestMicPermission { granted ->
            if (!granted) {
                onCaption?.invoke("I need the microphone. Turn it on for Bluey in the phone's Settings.", true)
                wantAwake = false
                setState(State.Asleep)
                return@requestMicPermission
            }
            val request = requestToken
            if (request == null) {
                wantAwake = false
                setState(State.Asleep)
                return@requestMicPermission
            }
            request { token, model, error ->
                if (state != State.Waking) return@request
                if (token == null) {
                    wantAwake = false
                    setState(State.Asleep)
                    val message = sessionErrorMessage(error)
                    onCaption?.invoke(message, true)
                    onSessionError?.invoke(message)
                } else {
                    connect(token, model)
                }
            }
        }
    }

    fun sleep() {
        val wasRunning = wantAwake || transport != null
        wantAwake = false
        reconnecting = false
        recorder.finish()
        if (wasRunning) onSessionEnd?.invoke()
        askedItems.clear()
        awaitingQuestion = false
        transport?.close()
        transport = null
        sessionReady = false
        pendingSends.clear()
        stopAudio()
        transcript = ""
        responseActive = false
        sleepAfterReply = false
        holding = false
        askWhenReady = false
        toolsRunning = 0
        heardText = ""
        turnAnnounced = false
        chirps.stopAll()
        setState(State.Asleep)
    }

    /** Says a quick hi (for checking the chirp volume). */
    fun sayHi() {
        if (transport == null) {
            wake()
            return
        }
        responseActive = true
        transcript = ""
        chirpedThisResponse = false
        send(RealtimeEvents.sayHi("Reply with a quick, cheerful hi in under eight words."))
    }

    // MARK: Hold to ask

    /** You pressed and held the screen: what you say now is the question. */
    fun beginAsk() {
        holding = true
        onCaption?.invoke("", false) // clears his last bubble
        if (state == State.Asleep) {
            wake()
            return
        }
        if (transport == null) return
        if (sessionReady) send(RealtimeEvents.activityStart)
        if (state == State.Listening || state == State.Speaking) setState(State.Asking)
    }

    /** You let go: he answers (or does the thing) using everything he's heard as context. */
    fun endAsk() {
        if (!holding) return
        holding = false
        if (transport == null || state == State.Waking) {
            askWhenReady = true
            return
        }
        ask()
    }

    private fun ask() {
        askWhenReady = false
        setState(State.Thinking)
        awaitingQuestion = true
        // A response is now expected — the Live API has no response.created to mark it.
        responseActive = true
        transcript = ""
        chirpedThisResponse = false
        // Close off what you just said (it may still be mid-sentence) and ask for a reply.
        send(RealtimeEvents.activityEnd)
    }

    // MARK: Connection

    private fun connect(token: String, model: String?) {
        val fresh = makeTransport()
        transport = fresh
        sessionReady = false
        pendingSends.clear()
        fresh.connect(
            token,
            onEvent = { text -> scope.launch { handle(text) } },
            onClosed = { scope.launch { if (transport === fresh) onTransportClosed() } },
        )
        // The setup names the model; everything else about the session is locked in the
        // token the PC minted. OkHttp queues it until the socket opens, so it stays first.
        fresh.send(RealtimeEvents.setup(model))
    }

    /** The locked session is live: flush anything queued, then carry the old connect()
     * behavior — first connections start the mic and chirp, reconnects replay context. */
    private fun onSessionReady() {
        sessionReady = true
        consecutiveFailures = 0
        flushPending()
        if (reconnecting) {
            // Same session, new connection: the mic and recording never stopped. Remind him what was said.
            reconnecting = false
            val context = resumeContext?.invoke()
            if (!context.isNullOrEmpty()) send(RealtimeEvents.contextReplay(context))
        } else {
            try {
                startAudio()
            } catch (e: Exception) {
                onCaption?.invoke("I couldn't start the microphone: ${e.message}", true)
                sleep()
                return
            }
            onSessionStart?.invoke()
            chirp(syllables = 2)
        }
        if (askWhenReady) ask() else setState(if (holding) State.Asking else State.Listening)
    }

    /** The connection dropped mid-session: get a new token and carry on, keeping the mic and recording running. */
    private fun reconnect(attempt: Int = 0) {
        transport = null
        sessionReady = false
        responseActive = false
        toolsRunning = 0
        if (!wantAwake) return
        val request = requestToken ?: return
        reconnecting = true
        request { token, model, _ ->
            scope.launch {
                if (!wantAwake || transport != null) return@launch
                if (token != null) {
                    connect(token, model)
                } else {
                    // The PC may be briefly away; keep trying, exactly like the original
                    // (the recording continues meanwhile). A dead WSS — not a dead PC —
                    // is what onTransportClosed caps.
                    val backoff = (2_000L + attempt * 3_000L).coerceAtMost(30_000L)
                    delay(backoff)
                    if (wantAwake) reconnect(attempt + 1)
                }
            }
        }
    }

    private fun onTransportClosed() {
        transport = null
        sessionReady = false
        responseActive = false
        toolsRunning = 0
        if (!wantAwake) {
            sleep()
            return
        }
        // Three connections in a row that never reached setupComplete means something
        // is properly wrong (no internet, blocked region, dead token) — say so and
        // stop, instead of minting and failing silently forever (port addition).
        if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            giveUp("I can't get into the Gemini session — check the phone's internet" +
                " (and the API key on the PC), then hold the screen to try again.")
            return
        }
        reconnect()
    }

    /** Stops trying and says why — the anti-silence guarantee for a dead session. */
    private fun giveUp(message: String) {
        onCaption?.invoke(message, true)
        onSessionError?.invoke(message)
        sleep()
    }

    /** The user-facing text for a token that never came. */
    private fun sessionErrorMessage(error: String?): String = when (error) {
        NO_PC -> "I can't find your PC. Check Bluey is running on the computer and both are on the same Wi-Fi — or type the PC's address on the pairing screen (a USB cable works too)."
        null -> "The Gemini session couldn't start — try again in a moment."
        else -> error
    }

    private fun send(json: String) {
        if (!sessionReady) {
            pendingSends.add(json) // nothing but the setup goes before setupComplete
            return
        }
        transport?.send(json)
    }

    private fun flushPending() {
        if (pendingSends.isEmpty()) return
        val queued = pendingSends.toList()
        pendingSends.clear()
        queued.forEach { transport?.send(it) }
    }

    // MARK: Events

    private fun handle(text: String) {
        when (val event = RealtimeEvents.parse(text)) {
            RealtimeEvents.Event.SetupComplete -> onSessionReady()

            is RealtimeEvents.Event.TextDelta -> {
                // Output transcriptions may arrive as deltas or as running text.
                transcript = if (event.delta.length >= transcript.length &&
                    event.delta.startsWith(transcript)
                ) event.delta else transcript + event.delta
                if (!chirpedThisResponse && transcript.isNotBlank()) {
                    chirpedThisResponse = true
                    // A reply is streaming in — whether it follows an ask, a say-hi or a
                    // tool result — so he's busy until its turn completes.
                    responseActive = true
                    setState(State.Speaking)
                    chirp(syllables = transcript.split(" ").size.coerceIn(3, 5))
                }
                onCaption?.invoke(transcript, false)
            }

            is RealtimeEvents.Event.UserTranscript -> {
                if (!turnAnnounced) {
                    turnAnnounced = true
                    onUserTurn?.invoke(currentTurnId(), holding || awaitingQuestion)
                }
                heardText = if (event.text.length >= heardText.length && event.text.startsWith(heardText)) {
                    event.text
                } else {
                    heardText + event.text
                }
                val asked = holding || awaitingQuestion
                if (asked) askedItems.add(currentTurnId())
                onUserWords?.invoke(currentTurnId(), heardText, asked)
            }

            RealtimeEvents.Event.TurnComplete -> {
                responseActive = false
                if (transcript.isNotEmpty()) {
                    onCaption?.invoke(transcript, true)
                    onReply?.invoke(transcript)
                }
                awaitingQuestion = false
                sealUserTurn()
                finishIfQuiet()
            }

            RealtimeEvents.Event.Interrupted -> {
                // You started a new ask mid-reply; the bubble was already cleared.
                responseActive = false
                finishIfQuiet()
            }

            is RealtimeEvents.Event.ToolCalls -> {
                responseActive = false
                if (state != State.Speaking) setState(State.Thinking)
                runTools(event.calls)
            }

            RealtimeEvents.Event.Ignored -> {}
        }
    }

    private fun currentTurnId() = "u$userTurnSerial"

    /** One exchange is over: what you said next starts a fresh turn in the transcript. */
    private fun sealUserTurn() {
        turnAnnounced = false
        userTurnSerial++
        heardText = ""
    }

    private fun runTools(calls: List<RealtimeEvents.FunctionCall>) {
        var remaining = calls.size
        toolsRunning += 1
        val results = mutableListOf<RealtimeEvents.ToolResult>()
        val images = mutableListOf<String>()
        for (call in calls) {
            if (call.name == "go_to_sleep") sleepAfterReply = true
            // The PC's link answers asynchronously; this hop back onto the machine's
            // single thread mirrors the original's DispatchQueue.main hop from the
            // tool callback.
            val finish: (String, String?) -> Unit = { output, image ->
                scope.launch {
                    if (transport == null) return@launch
                    if (call.name == "web_research") {
                        val marker = "\n---REPORT---\n"
                        val at = output.indexOf(marker)
                        if (at >= 0) onReport?.invoke(output.substring(at + marker.length))
                    }
                    results.add(RealtimeEvents.ToolResult(call.callId, call.name, output))
                    image?.let { images.add(it) }
                    remaining -= 1
                    if (remaining == 0) {
                        toolsRunning = maxOf(0, toolsRunning - 1)
                        // The outputs go back first; blocking tools resume the model on
                        // them. Screenshots follow as a user turn that completes it, so
                        // he can actually see the screen for the reply — the original's
                        // input_image + response.create order.
                        send(RealtimeEvents.toolResponse(results))
                        for (image in images) send(RealtimeEvents.imageTurn(image))
                    }
                }
            }
            val runner = runTool
            if (runner != null) runner(call.name, call.arguments, finish) else finish("The PC isn't connected.", null)
        }
    }

    /** Back to listening once he's replied and chirped (or asleep, if he said goodbye). */
    private fun finishIfQuiet() {
        if (chirps.pendingBuffers > 0 || responseActive || toolsRunning > 0) return
        if (sleepAfterReply) {
            sleep()
            return
        }
        if (state == State.Speaking || state == State.Thinking) {
            setState(if (holding) State.Asking else State.Listening)
        }
    }

    // MARK: Audio

    private fun startAudio() {
        if (!mic.active) mic.start()
    }

    private fun stopAudio() {
        mic.stop()
    }

    companion object {
        /** Sentinel error for "the PC link wasn't there at all". */
        const val NO_PC = "no-pc"

        /** Failed connects/mints in a row before giving up and saying so. */
        const val MAX_CONSECUTIVE_FAILURES = 3
    }

    private fun chirp(syllables: Int) {
        chirps.play(ChirpSynth.synthesize(syllables), volume.toFloat()) {
            scope.launch { finishIfQuiet() }
        }
    }
}
