package dev.bluey.client.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The LiveVoice state machine, driven through a fake transport: wake, hold-to-ask,
 * streaming replies, tool relay, go_to_sleep and the reconnect with context replay —
 * all against the Gemini Live API's message shapes (setup gating, activity signals,
 * transcription turns, tool calls).
 *
 * The live scope runs on the test scheduler (UnconfinedTestDispatcher), so everything
 * the machine does inline executes eagerly and queued continuations flush with
 * [TestScope.runCurrent]; reconnect backoffs advance with virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveVoiceTest {

    private class FakeTransport : RealtimeTransport {
        var open = false
        val sent = mutableListOf<String>()
        var onEvent: ((String) -> Unit)? = null
        var onClosed: (() -> Unit)? = null

        override fun connect(token: String, onEvent: (String) -> Unit, onClosed: () -> Unit) {
            open = true
            this.onEvent = onEvent
            this.onClosed = onClosed
        }

        override fun send(json: String): Boolean {
            sent.add(json)
            return true
        }

        override fun close() {
            open = false
        }

        fun deliver(text: String) = onEvent!!.invoke(text)

        /** The server accepting the locked session — what the phone waits for. */
        fun ready(model: String? = null) {
            deliver("""{"setupComplete":{}}""")
        }

        fun drop() = onClosed!!.invoke()
        fun sentText(part: String) = sent.filter { it.contains("\"$part\"") }
    }

    private class FakeMic : MicSource {
        var started = 0
        var stopped = 0
        override var onChunk: ((ShortArray, Int) -> Unit)? = null
        private val senders = mutableListOf<(String) -> Unit>()
        override val active get() = started > stopped
        override fun onAudioBase64(sender: (String) -> Unit) {
            senders.add(sender)
        }

        override fun start() {
            started++
        }

        override fun stop() {
            stopped++
        }

        fun speak(base64: String) = senders.forEach { it(base64) }
    }

    private class FakeChirp : ChirpSink {
        val played = mutableListOf<Int>()
        val dones = mutableListOf<() -> Unit>()
        var autoComplete = true
        override var pendingBuffers: Int = 0
        override var level: Double = 0.0

        override fun play(samples: FloatArray, volume: Float, onDone: () -> Unit) {
            played.add(samples.size)
            if (autoComplete) {
                onDone() // completes immediately; finishIfQuiet sees a quiet chirp
            } else {
                pendingBuffers++ // mid-flight until finishAll
                dones.add(onDone)
            }
        }

        override fun stopAll() {
            played.clear()
        }

        fun finishAll() {
            val pending = dones.toList()
            dones.clear()
            pendingBuffers = 0
            pending.forEach { it() }
        }
    }

    private lateinit var transport: FakeTransport
    private lateinit var mic: FakeMic
    private lateinit var chirp: FakeChirp

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        transport = FakeTransport()
        mic = FakeMic()
        chirp = FakeChirp()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.build(
        token: String? = "auth_tokens/minted",
        model: String? = "gemini-3.8-live",
        error: String? = null,
        context: String? = null,
        toolRunner: ((String, String, (String, String?) -> Unit) -> Unit)? = null,
    ): LiveVoice {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return LiveVoice(
            scope = scope,
            makeTransport = { transport },
            mic = mic,
            chirps = chirp,
        ).apply {
            requestToken = { done -> done(token, model, error) }
            resumeContext = { context }
            runTool = toolRunner
        }
    }

    private fun TestScope.pump() = runCurrent()

    @Test
    fun `wake brings him up the moment the token arrives - mic, session, chirp, listening`() = runTest {
        var sessionStarted = 0
        val live = build()
        live.onSessionStart = { sessionStarted++ }

        live.wake()

        // The setup naming the minted model is the first thing on the wire.
        assertEquals("""{"setup":{"model":"models/gemini-3.8-live"}}""", transport.sent.first())
        // Original parity: with the token in hand he's already up — mic on (it started
        // at wake, before the token), session started, two-syllable chirp, listening —
        // without waiting for the server to acknowledge the setup.
        assertEquals(1, mic.started)
        assertEquals(1, sessionStarted)
        assertEquals(1, chirp.played.size)
        assertEquals(LiveVoice.State.Listening, live.state)

        transport.ready() // setupComplete just flushes what queued meanwhile

        assertEquals(LiveVoice.State.Listening, live.state)
    }

    @Test
    fun `the mic comes on the moment he wakes, before the token arrives`() = runTest {
        var handBack: ((String?, String?, String?) -> Unit)? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val live = LiveVoice(scope = scope, makeTransport = { transport }, mic = mic, chirps = chirp).apply {
            requestToken = { done -> handBack = done } // the PC is slow to mint
        }

        live.wake()

        assertEquals(1, mic.started) // green light right away, like the original
        assertEquals(LiveVoice.State.Waking, live.state)

        handBack?.invoke("auth_tokens/slowly-minted", "gemini-3.8-live", null)

        assertEquals(LiveVoice.State.Listening, live.state) // up on the token, pre-ack
    }

    @Test
    fun `a wake that never gets a token gives up after thirty seconds`() = runTest {
        var sessionError: String? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val live = LiveVoice(scope = scope, makeTransport = { transport }, mic = mic, chirps = chirp).apply {
            requestToken = { _ -> } // the mint never comes back
            onSessionError = { sessionError = it }
        }

        live.wake()
        assertEquals(1, mic.started) // the mic was already on
        assertEquals(LiveVoice.State.Waking, live.state)

        advanceTimeBy(30_000)
        pump()

        assertTrue(sessionError!!.contains("taking too long"))
        assertEquals(LiveVoice.State.Asleep, live.state)
        assertEquals(1, mic.stopped) // tidied away, ready to try again
        assertFalse(transport.open)
    }

    @Test
    fun `a stale wake watchdog cannot fire into a later wake`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var token: String? = null
        val live = LiveVoice(scope = scope, makeTransport = { transport }, mic = mic, chirps = chirp).apply {
            requestToken = { done -> done(token, null, null) }
        }

        live.wake() // wake 1: no token, stays Waking…
        token = "second-attempt-token"
        // …but before the watchdog fires the user tries again (sleep then wake).
        live.sleep()
        live.wake()
        assertEquals(LiveVoice.State.Listening, live.state)

        advanceTimeBy(25_000) // the first wake's watchdog comes due
        pump()

        assertEquals(LiveVoice.State.Listening, live.state) // the session lives on
    }

    @Test
    fun `mic denied says so and goes back to sleep`() = runTest {
        var caption: Pair<String, Boolean>? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val live = LiveVoice(
            scope = scope,
            makeTransport = { transport },
            mic = mic,
            chirps = chirp,
            requestMicPermission = { done -> done(false) },
        ).apply {
            requestToken = { done -> done("token", null, null) }
            onCaption = { text, done -> caption = text to done }
        }

        live.wake()

        assertEquals("I need the microphone. Turn it on for Bluey in the phone's Settings.", caption?.first)
        assertEquals(true, caption?.second)
        assertEquals(LiveVoice.State.Asleep, live.state)
        assertFalse(transport.open)
    }

    @Test
    fun `a missing token goes back to sleep without a session`() = runTest {
        val live = build(token = null)
        live.wake()
        assertEquals(LiveVoice.State.Asleep, live.state)
        assertFalse(transport.open)
    }

    @Test
    fun `a missing token with no PC says so on the phone`() = runTest {
        var sessionError: String? = null
        var caption: Pair<String, Boolean>? = null
        val live = build(token = null, error = LiveVoice.NO_PC)
        live.onSessionError = { sessionError = it }
        live.onCaption = { text, done -> caption = text to done }

        live.wake()

        // Never silent: the phone itself explains what's wrong.
        assertTrue(sessionError!!.contains("can't find your PC"))
        assertEquals(sessionError, caption?.first)
        assertEquals(true, caption?.second)
        assertEquals(LiveVoice.State.Asleep, live.state)
    }

    @Test
    fun `a mint failure forwards the PC's reason to the phone`() = runTest {
        var sessionError: String? = null
        val reason = "Gemini wouldn't start a voice session: 401 API key not valid."
        val live = build(token = null, error = reason)
        live.onSessionError = { sessionError = it }

        live.wake()

        assertEquals(reason, sessionError)
        assertEquals(LiveVoice.State.Asleep, live.state)
    }

    @Test
    fun `three connections in a row that never complete give up with a message`() = runTest {
        var sessionError: String? = null
        val live = build()
        live.onSessionError = { sessionError = it }
        live.wake()
        transport.ready()

        // Three drops without a setupComplete between them — a genuinely dead path.
        transport.drop(); pump()
        transport.drop(); pump()
        transport.drop(); pump()

        assertTrue(sessionError!!.contains("can't get into the Gemini session"))
        assertEquals(LiveVoice.State.Asleep, live.state)
        assertEquals(1, mic.stopped) // everything torn down, ready to try again
    }

    @Test
    fun `a dropped connection that recovers resets the failure count`() = runTest {
        val live = build()
        live.wake()
        transport.ready()

        transport.drop(); pump() // failure 1
        transport.ready()        // back in — the count resets
        transport.drop(); pump() // failure 1 again, not 2
        transport.ready()

        assertEquals(LiveVoice.State.Listening, live.state) // still going, no give-up
    }

    @Test
    fun `hold to ask frames the question with the activity signals`() = runTest {
        val live = build()
        live.wake()
        transport.ready()

        live.beginAsk()
        assertEquals(LiveVoice.State.Asking, live.state)
        assertEquals(1, transport.sentText("activityStart").size)

        live.endAsk()
        assertEquals(LiveVoice.State.Thinking, live.state)
        assertEquals(1, transport.sentText("activityEnd").size)
        // The Live API has no commit or response.create — the activity end is the ask.
        assertTrue(transport.sent.none { it.contains("commit") || it.contains("response.create") })
    }

    @Test
    fun `holding while asleep wakes him straight into asking`() = runTest {
        val live = build()
        live.beginAsk() // before wake: wakes with the finger still down
        // The token arrives synchronously in tests — he's up and asking already
        // (connect-time transition, like the original).
        assertEquals(LiveVoice.State.Asking, live.state)
        live.endAsk() // let go: the ask goes out
        assertEquals(LiveVoice.State.Thinking, live.state)
        transport.ready()
        assertEquals(LiveVoice.State.Thinking, live.state)
        assertTrue(transport.sentText("activityEnd").isNotEmpty())
    }

    @Test
    fun `released before the token arrives asks as soon as the session is ready`() = runTest {
        var handBack: ((String?, String?, String?) -> Unit)? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val live = LiveVoice(
            scope = scope,
            makeTransport = { transport },
            mic = mic,
            chirps = chirp,
        ).apply {
            requestToken = { done -> handBack = done } // the PC is slow to mint
        }

        live.beginAsk() // wakes; still Waking while the token is pending
        assertEquals(LiveVoice.State.Waking, live.state)
        live.endAsk() // let go early: askWhenReady
        handBack?.invoke("auth_tokens/slowly-minted", "gemini-3.8-live", null)
        transport.ready()
        assertEquals(LiveVoice.State.Thinking, live.state)
        assertTrue(transport.sentText("activityEnd").isNotEmpty())
    }

    @Test
    fun `streamed text chirps once captions as it lands and finishes back to listening`() = runTest {
        val captions = mutableListOf<Pair<String, Boolean>>()
        val replies = mutableListOf<String>()
        val live = build()
        live.onCaption = { text, done -> captions.add(text to done) }
        live.onReply = { replies.add(it) }
        live.wake()
        transport.ready()
        chirp.played.clear()

        transport.deliver("""{"serverContent":{"outputTranscription":{"text":"That's the"}}}""")
        transport.deliver("""{"serverContent":{"outputTranscription":{"text":" prompt box."}}}""")

        assertEquals(LiveVoice.State.Speaking, live.state)
        assertEquals(1, chirp.played.size) // chirped on the first non-empty transcription
        assertEquals("That's the", captions[0].first)
        assertEquals("That's the prompt box.", captions[1].first)

        transport.deliver("""{"serverContent":{"turnComplete":true}}""")

        assertEquals("That's the prompt box.", captions.last().first)
        assertEquals(true, captions.last().second)
        assertEquals(listOf("That's the prompt box."), replies)
        assertEquals(LiveVoice.State.Listening, live.state)
    }

    @Test
    fun `running transcriptions replace instead of duplicating`() = runTest {
        val captions = mutableListOf<String>()
        val live = build()
        live.onCaption = { text, _ -> captions.add(text) }
        live.wake()
        transport.ready()

        transport.deliver("""{"serverContent":{"outputTranscription":{"text":"Hi"}}}""")
        transport.deliver("""{"serverContent":{"outputTranscription":{"text":"Hi there"}}}""")

        assertEquals(listOf("Hi", "Hi there"), captions)
    }

    @Test
    fun `heard turns remember whether they were questions`() = runTest {
        val turns = mutableListOf<Pair<String, Boolean>>()
        val words = mutableListOf<Triple<String, String, Boolean>>()
        val live = build()
        live.onUserTurn = { item, asked -> turns.add(item to asked) }
        live.onUserWords = { item, text, asked -> words.add(Triple(item, text, asked)) }
        live.wake()
        transport.ready()

        live.beginAsk()
        transport.deliver("""{"serverContent":{"inputTranscription":{"text":"what's"}}}""")
        transport.deliver("""{"serverContent":{"inputTranscription":{"text":" this?"}}}""")
        live.endAsk() // awaitingQuestion = true; the response follows

        transport.deliver("""{"serverContent":{"outputTranscription":{"text":"The prompt box."}}}""")
        transport.deliver("""{"serverContent":{"turnComplete":true}}""")

        assertEquals("u0" to true, turns[0])
        assertEquals(Triple("u0", "what's", true), words[0])
        assertEquals(Triple("u0", "what's this?", true), words[1])

        // Words overheard after the exchange start a fresh turn, not a question.
        transport.deliver("""{"serverContent":{"inputTranscription":{"text":"background chatter"}}}""")
        assertEquals("u1" to false, turns[1])
        assertEquals(Triple("u1", "background chatter", false), words[2])
    }

    @Test
    fun `interrupted mid reply settles back to asking while you hold`() = runTest {
        val live = build()
        live.wake()
        transport.ready()

        transport.deliver("""{"serverContent":{"outputTranscription":{"text":"Well"}}}""")
        assertEquals(LiveVoice.State.Speaking, live.state)

        live.beginAsk() // a new question cuts him off
        transport.deliver("""{"serverContent":{"interrupted":true}}""")

        assertEquals(LiveVoice.State.Asking, live.state)
    }

    @Test
    fun `function calls run on the PC and their results plus screenshots go back in`() = runTest {
        val live = build(
            toolRunner = { name, arguments, done ->
                assertEquals("point_at", name)
                assertEquals("{\"target_id\":\"W3\"}", arguments)
                done("Pointing at \"Googly\".", "c2NyZWVu")
            },
        )
        live.wake()
        transport.ready()
        transport.sent.clear()

        transport.deliver(
            """{"toolCall":{"functionCalls":[{"name":"point_at","id":"call_1","args":{"target_id":"W3"}}]}}""",
        )

        // Thinking until the follow-up reply he asked for arrives.
        assertEquals(LiveVoice.State.Thinking, live.state)
        val output = transport.sentText("toolResponse").single()
        assertTrue(output.contains("\"id\":\"call_1\""))
        assertTrue(output.contains("\"name\":\"point_at\""))
        assertTrue(output.contains("Pointing at \\\"Googly\\\"."))
        // The screenshot goes back in as a user turn that completes it — the original's
        // input_image + response.create pair.
        val image = transport.sentText("clientContent").single()
        assertTrue(image.contains("\"mimeType\":\"image/jpeg\""))
        assertTrue(image.contains("\"data\":\"c2NyZWVu\""))
        assertTrue(image.contains("\"turnComplete\":true"))
        // Outputs first, then the image — the original's order.
        assertTrue(transport.sent.indexOfFirst { it.contains("toolResponse") } <
            transport.sent.indexOfFirst { it.contains("clientContent") })
    }

    @Test
    fun `a web research report is split off for the transcript`() = runTest {
        val reports = mutableListOf<String>()
        val live = build(
            toolRunner = { _, _, done ->
                done(
                    "The full report is now in a card on the user's screen. Reply with ONE short line.\n---REPORT---\nTitle\n\nParagraph one",
                    null,
                )
            },
        )
        live.onReport = { reports.add(it) }
        live.wake()
        transport.ready()

        transport.deliver("""{"toolCall":{"functionCalls":[{"name":"web_research","id":"call_r"}]}}""")
        pump()

        assertEquals(listOf("Title\n\nParagraph one"), reports)
    }

    @Test
    fun `go_to_sleep ends the session after the goodbye`() = runTest {
        var ended = 0
        val live = build(
            toolRunner = { _, _, done -> done("Going to sleep. Say a very short goodbye.", null) },
        )
        live.onSessionEnd = { ended++ }
        live.wake()
        transport.ready()

        transport.deliver("""{"toolCall":{"functionCalls":[{"name":"go_to_sleep","id":"call_z"}]}}""")

        assertEquals(LiveVoice.State.Thinking, live.state) // goodbye pending

        transport.deliver("""{"serverContent":{"outputTranscription":{"text":"Night!"}}}""")
        transport.deliver("""{"serverContent":{"turnComplete":true}}""")

        assertEquals(LiveVoice.State.Asleep, live.state)
        assertEquals(1, ended)
        assertEquals(1, mic.stopped)
        assertFalse(transport.open)
    }

    @Test
    fun `the connection drops and reconnects with the context replay`() = runTest {
        val live = build(context = "Bluey: hi\nYou: what's this?")
        live.wake()
        transport.ready()
        transport.sent.clear()

        transport.drop()
        pump() // flush the reconnect chain queued on the scheduler

        assertTrue(transport.open) // already reconnected (the token was instant)
        transport.ready()
        val replay = transport.sentText("clientContent").single()
        assertTrue(replay.contains("(Context: what I said earlier in this session, from my notes. Don't reply to this.)"))
        assertTrue(replay.contains("Bluey: hi"))
        assertTrue(replay.contains("\"turnComplete\":false")) // a reminder, not a question
        assertEquals(1, mic.started) // the mic never stopped
    }

    @Test
    fun `a missing token backs off with the exact schedule and tries again`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var token: String? = "first"
        var mints = 0
        val live = LiveVoice(scope = scope, makeTransport = { transport }, mic = mic, chirps = chirp).apply {
            requestToken = { done ->
                mints++
                done(token, null, null)
            }
        }
        live.wake()
        transport.ready() // the locked session goes live; the mic starts
        assertEquals(1, mints)

        token = null
        transport.drop() // reconnect attempt 0: null → 2 s backoff
        pump()
        assertEquals(2, mints)
        advanceTimeBy(2_000)
        pump()
        assertEquals(3, mints) // attempt 1: null → 5 s backoff
        advanceTimeBy(5_000)
        pump()
        assertEquals(4, mints) // attempt 2: null → 8 s backoff
        token = "back"
        advanceTimeBy(8_000)
        pump()
        assertEquals(5, mints) // attempt 3: connects
        assertTrue(transport.open)
        assertEquals(1, mic.started) // the mic and recording carry on
    }

    @Test
    fun `sleep resets everything`() = runTest {
        val live = build()
        live.wake()
        transport.ready()
        live.beginAsk()
        transport.deliver("""{"serverContent":{"outputTranscription":{"text":"Hello"}}}""")

        live.sleep()

        assertEquals(LiveVoice.State.Asleep, live.state)
        assertFalse(transport.open)
        assertEquals(1, mic.stopped)
        // Holding state is gone: asking again starts fresh.
        live.beginAsk()
        live.endAsk()
        transport.ready()
        assertEquals(LiveVoice.State.Thinking, live.state)
    }

    @Test
    fun `say hi sends the exact test instructions`() = runTest {
        val live = build()
        live.wake()
        transport.ready()
        transport.sent.clear()
        live.sayHi()
        val event = transport.sentText("clientContent").single()
        assertTrue(event.contains("Reply with a quick, cheerful hi in under eight words."))
        assertTrue(event.contains("\"turnComplete\":true"))
    }

    @Test
    fun `say hi while asleep just wakes him`() = runTest {
        val live = build()
        live.sayHi()
        transport.ready()
        assertEquals(LiveVoice.State.Listening, live.state)
    }

    @Test
    fun `mic audio streams as realtime input at 16 kHz`() = runTest {
        val live = build()
        live.wake()
        transport.ready()
        transport.sent.clear()
        mic.speak("QUJD")
        pump()
        val chunk = transport.sentText("realtimeInput").single()
        assertTrue(chunk.contains("\"audio\":{\"data\":\"QUJD\",\"mimeType\":\"audio/pcm;rate=16000\"}"))
    }

    @Test
    fun `a chirp still playing keeps him from settling back to listening`() = runTest {
        chirp.autoComplete = false
        val live = build()
        live.wake()
        transport.ready()
        chirp.played.clear()

        transport.deliver("""{"serverContent":{"outputTranscription":{"text":"Hello there"}}}""")
        transport.deliver("""{"serverContent":{"turnComplete":true}}""")

        assertEquals(LiveVoice.State.Speaking, live.state) // waiting on the chirp

        chirp.finishAll()
        assertEquals(LiveVoice.State.Listening, live.state)
    }

    // MARK: The silence watchdog (v0.3.7) — a zombie session must never stick him
    // in Thinking. The 2026-10-25 log: a session died mid-answer, then five holds
    // over 24 s sent empty captions into the void until a sleep-wake cycle
    // re-minted. Now the phone heals itself.

    @Test
    fun `an ask that never gets an answer recovers the session instead of sticking`() = runTest {
        val captions = mutableListOf<Pair<String, Boolean>>()
        val live = build()
        live.onCaption = { text, done -> captions.add(text to done) }
        live.wake()
        transport.ready()
        transport.sent.clear()

        live.beginAsk()
        live.endAsk()
        assertEquals(LiveVoice.State.Thinking, live.state)

        advanceTimeBy(20_000)
        pump()

        // The honest message went out, and a fresh session is already minted — the
        // wake-time setup was cleared above, so this one is the recovery's.
        assertTrue(captions.any { it.first == LiveVoice.LOST_SESSION_MESSAGE && it.second })
        assertEquals(LiveVoice.State.Waking, live.state) // reconnect in flight
        assertEquals(1, transport.sentText("setup").size)

        transport.ready()
        assertEquals(LiveVoice.State.Listening, live.state) // back — never stuck
    }

    @Test
    fun `re-asking inside the same silence does not extend the watchdog`() = runTest {
        val live = build()
        live.wake()
        transport.ready()

        live.beginAsk(); live.endAsk() // ask 1 at t=0 — arms t+20 s
        advanceTimeBy(12_000)
        assertEquals(LiveVoice.State.Thinking, live.state)
        live.beginAsk(); live.endAsk() // ask 2 at t=12 s — must NOT re-arm
        advanceTimeBy(8_000) // t=20 s: the first ask's deadline
        pump()

        assertEquals(2, transport.sentText("setup").size) // recovered anyway
    }

    @Test
    fun `a later ask after a real answer gets a fresh watchdog`() = runTest {
        val live = build()
        live.wake()
        transport.ready()

        live.beginAsk(); live.endAsk()
        transport.deliver("""{"serverContent":{"outputTranscription":{"text":"On it"}}}""")
        transport.deliver("""{"serverContent":{"turnComplete":true}}""")
        assertEquals(LiveVoice.State.Listening, live.state)

        advanceTimeBy(25_000) // the first ask's watchdog is long dead
        assertEquals(1, transport.sentText("setup").size) // no spurious recovery
    }

    @Test
    fun `server words while thinking stand the watchdog down`() = runTest {
        val live = build()
        live.wake()
        transport.ready()

        live.beginAsk(); live.endAsk()
        // A partial reply — proof of life, even though the answer is slow.
        transport.deliver("""{"serverContent":{"outputTranscription":{"text":"Diving into the tech"}}}""")

        advanceTimeBy(25_000)
        pump()

        assertEquals(LiveVoice.State.Speaking, live.state) // same session, mid-reply
        assertEquals(1, transport.sentText("setup").size)
    }

    @Test
    fun `a stale reply tail during the new hold does not refill the bubble`() = runTest {
        val captions = mutableListOf<String>()
        val live = build()
        live.onCaption = { text, _ -> captions.add(text) }
        live.wake()
        transport.ready()

        transport.deliver("""{"serverContent":{"outputTranscription":{"text":"Usarías Next.js para la página"}}}""")
        live.beginAsk() // cuts him off; the bubble clears for the question
        transport.deliver("""{"serverContent":{"outputTranscription":{"text":" para elementos"}}}""") // in flight

        assertEquals(listOf("Usarías Next.js para la página", ""), captions)
        assertEquals(LiveVoice.State.Asking, live.state) // not dragged back to Speaking
    }

    @Test
    fun `holding while he thinks shows the asking face`() = runTest {
        val live = build()
        live.wake()
        transport.ready()
        live.beginAsk(); live.endAsk()
        assertEquals(LiveVoice.State.Thinking, live.state)

        live.beginAsk()
        assertEquals(LiveVoice.State.Asking, live.state)
    }
}
