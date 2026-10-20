package dev.bluey.client.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A live session with OpenAI's Realtime API, running on the phone. Once he's awake the
 * mic is on the whole time, so everything you say becomes context, but he stays quiet
 * until you hold the screen and ask. He never talks out loud: replies are text (speech
 * bubbles on the PC) with a little cartoon chirp here. Tools (look at the screen, point)
 * run on the PC. Full port of <c>iOS/Sources/LiveVoice.swift</c>.
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

    /** Asks the PC for a short-lived key. Calls back with null on failure. */
    var requestToken: ((callback: (String?) -> Unit) -> Unit)? = null

    /** Runs a tool on the PC: (name, JSON arguments) → (output text, optional JPEG base64). */
    var runTool: ((name: String, arguments: String, done: (output: String, image: String?) -> Unit) -> Unit)? = null

    /** What he's saying, as it streams in. `done` is true when the reply finished. */
    var onCaption: ((text: String, done: Boolean) -> Unit)? = null
    var onStateChange: ((State) -> Unit)? = null

    /** For the saved transcript: a session started or ended, something you said, his replies. */
    var onSessionStart: (() -> Unit)? = null
    var onSessionEnd: (() -> Unit)? = null
    var onUserTurn: ((itemID: String, asked: Boolean) -> Unit)? = null
    var onUserWords: ((itemID: String, text: String, asked: Boolean) -> Unit)? = null
    var onReply: ((text: String) -> Unit)? = null
    var onReport: ((text: String) -> Unit)? = null

    /** Recent transcript, handed to a fresh connection so he keeps context in long sessions. */
    var resumeContext: (() -> String?)? = null

    /** True from wake until you end the session. Dropped connections (sessions time out after an hour)
     * reconnect on their own while this is set, and the recording carries on. */
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

    /** Turns that were questions (said while holding, or committed when you let go). */
    private val askedItems = mutableSetOf<String>()
    private var awaitingQuestion = false
    private var toolsRunning = 0

    init {
        mic.onChunk = { samples, count -> recorder.append(samples, count) }
        mic.onAudioBase64 { base64 ->
            scope.launch { send(RealtimeEvents.appendAudio(base64)) }
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
            request { token ->
                if (state != State.Waking) return@request
                if (token == null) {
                    wantAwake = false
                    setState(State.Asleep)
                } else {
                    connect(token)
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
        stopAudio()
        transcript = ""
        responseActive = false
        sleepAfterReply = false
        holding = false
        askWhenReady = false
        toolsRunning = 0
        chirps.stopAll()
        setState(State.Asleep)
    }

    /** Says a quick hi (for checking the chirp volume). */
    fun sayHi() {
        if (transport == null) {
            wake()
            return
        }
        send(RealtimeEvents.createResponse("Reply with a quick, cheerful hi in under eight words."))
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
        // Close off what you just said (it may still be mid-sentence) and ask for a reply.
        send(RealtimeEvents.commitAudio)
        send(RealtimeEvents.createResponse())
    }

    // MARK: Connection

    private fun connect(token: String) {
        val fresh = makeTransport()
        transport = fresh
        fresh.connect(
            token,
            onEvent = { text -> scope.launch { handle(text) } },
            onClosed = { scope.launch { if (transport === fresh) onTransportClosed() } },
        )
        if (reconnecting) {
            // Same session, new connection: the mic and recording never stopped. Remind him what was said.
            reconnecting = false
            val context = resumeContext?.invoke()
            if (!context.isNullOrEmpty()) send(RealtimeEvents.contextReplay(context))
            if (askWhenReady) ask() else setState(if (holding) State.Asking else State.Listening)
            return
        }
        try {
            startAudio()
        } catch (e: Exception) {
            onCaption?.invoke("I couldn't start the microphone: ${e.message}", true)
            sleep()
            return
        }
        onSessionStart?.invoke()
        chirp(syllables = 2)
        if (askWhenReady) ask() else setState(if (holding) State.Asking else State.Listening)
    }

    /** The connection dropped mid-session: get a new key and carry on, keeping the mic and recording running. */
    private fun reconnect(attempt: Int = 0) {
        transport = null
        responseActive = false
        toolsRunning = 0
        if (!wantAwake) return
        val request = requestToken ?: return
        reconnecting = true
        request { token ->
            scope.launch {
                if (!wantAwake || transport != null) return@launch
                if (token != null) {
                    connect(token)
                } else {
                    // The PC may be briefly away; keep trying (the recording continues meanwhile).
                    val backoff = (2_000L + attempt * 3_000L).coerceAtMost(30_000L)
                    delay(backoff)
                    if (wantAwake) reconnect(attempt + 1)
                }
            }
        }
    }

    private fun onTransportClosed() {
        transport = null
        responseActive = false
        toolsRunning = 0
        if (wantAwake) reconnect() else sleep()
    }

    private fun send(json: String) {
        transport?.send(json)
    }

    // MARK: Events

    private fun handle(text: String) {
        when (val event = RealtimeEvents.parse(text)) {
            RealtimeEvents.Event.ResponseCreated -> {
                responseActive = true
                transcript = ""
                chirpedThisResponse = false
            }

            is RealtimeEvents.Event.TextDelta -> {
                transcript += event.delta
                if (!chirpedThisResponse && transcript.isNotBlank()) {
                    chirpedThisResponse = true
                    setState(State.Speaking)
                    chirp(syllables = transcript.split(" ").size.coerceIn(3, 5))
                }
                onCaption?.invoke(transcript, false)
            }

            is RealtimeEvents.Event.BufferCommitted -> {
                val asked = holding || awaitingQuestion
                if (asked) askedItems.add(event.itemId)
                if (awaitingQuestion && !holding) awaitingQuestion = false
                onUserTurn?.invoke(event.itemId, asked)
            }

            is RealtimeEvents.Event.UserTranscript ->
                onUserWords?.invoke(event.itemId, event.transcript, askedItems.contains(event.itemId))

            is RealtimeEvents.Event.ResponseDone -> {
                responseActive = false
                if (transcript.isNotEmpty()) {
                    onCaption?.invoke(transcript, true)
                    onReply?.invoke(transcript)
                }
                if (event.calls.isNotEmpty()) {
                    if (state != State.Speaking) setState(State.Thinking)
                    runTools(event.calls)
                }
                finishIfQuiet()
            }

            is RealtimeEvents.Event.ApiError -> {
                if (event.commitEmpty) awaitingQuestion = false
                // The original logs and moves on; so do we.
            }

            RealtimeEvents.Event.Ignored -> {}
        }
    }

    private fun runTools(calls: List<RealtimeEvents.FunctionCall>) {
        var remaining = calls.size
        toolsRunning += 1
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
                    send(RealtimeEvents.functionCallOutput(call.callId, output))
                    image?.let { images.add(it) }
                    remaining -= 1
                    if (remaining == 0) {
                        toolsRunning = maxOf(0, toolsRunning - 1)
                        // Screenshots go in as a user image so he can actually see the screen.
                        for (image in images) send(RealtimeEvents.inputImage(image))
                        send(RealtimeEvents.createResponse())
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

    private fun chirp(syllables: Int) {
        chirps.play(ChirpSynth.synthesize(syllables), volume.toFloat()) {
            scope.launch { finishIfQuiet() }
        }
    }
}
