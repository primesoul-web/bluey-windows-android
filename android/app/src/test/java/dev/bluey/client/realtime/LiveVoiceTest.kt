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
 * streaming replies, tool relay, go_to_sleep and the reconnect with context replay.
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
        fun drop() = onClosed!!.invoke()
        fun sentText(type: String) = sent.filter { it.contains("\"$type\"") }
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
        override var pendingBuffers: Int = 0
        override var level: Double = 0.0

        override fun play(samples: FloatArray, volume: Float, onDone: () -> Unit) {
            played.add(samples.size)
            onDone() // completes immediately; finishIfQuiet sees a quiet chirp
        }

        override fun stopAll() {
            played.clear()
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
        token: String? = "minted-secret",
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
            requestToken = { done -> done(token) }
            resumeContext = { context }
            runTool = toolRunner
        }
    }

    private fun TestScope.pump() = runCurrent()

    @Test
    fun `wake starts the mic the session and a two-syllable chirp`() = runTest {
        var sessionStarted = 0
        val live = build()
        live.onSessionStart = { sessionStarted++ }

        live.wake()

        assertTrue(transport.open)
        assertEquals(1, mic.started)
        assertEquals(1, sessionStarted)
        assertEquals(1, chirp.played.size) // chirp(syllables: 2) on connect
        assertEquals(LiveVoice.State.Listening, live.state)
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
            requestToken = { done -> done("token") }
            onCaption = { text, done -> caption = text to done }
        }

        live.wake()

        assertEquals("I need the microphone. Turn it on for Bluey in the phone's Settings.", caption?.first)
        assertEquals(true, caption?.second)
        assertEquals(LiveVoice.State.Asleep, live.state)
        assertFalse(transport.open)
    }

    @Test
    fun `a missing token goes back to sleep`() = runTest {
        val live = build(token = null)
        live.wake()
        assertEquals(LiveVoice.State.Asleep, live.state)
        assertFalse(transport.open)
    }

    @Test
    fun `hold to ask commits the buffer and requests a response`() = runTest {
        val live = build()
        live.wake()

        live.beginAsk()
        assertEquals(LiveVoice.State.Asking, live.state)

        live.endAsk()
        assertEquals(LiveVoice.State.Thinking, live.state)
        assertEquals(1, transport.sentText("input_audio_buffer.commit").size)
        assertEquals(1, transport.sentText("response.create").size)
    }

    @Test
    fun `holding while asleep wakes him straight into asking`() = runTest {
        val live = build()
        live.beginAsk() // before wake: wakes with the finger still down
        assertEquals(LiveVoice.State.Asking, live.state)
        live.endAsk()
        assertEquals(LiveVoice.State.Thinking, live.state)
        assertTrue(transport.sentText("response.create").isNotEmpty())
    }

    @Test
    fun `released before the session is ready asks as soon as it is`() = runTest {
        var handBack: ((String?) -> Unit)? = null
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
        handBack?.invoke("slowly-minted")
        assertEquals(LiveVoice.State.Thinking, live.state)
        assertTrue(transport.sentText("response.create").isNotEmpty())
    }

    @Test
    fun `streamed text chirps once captions as it lands and finishes back to listening`() = runTest {
        val captions = mutableListOf<Pair<String, Boolean>>()
        val replies = mutableListOf<String>()
        val live = build()
        live.onCaption = { text, done -> captions.add(text to done) }
        live.onReply = { replies.add(it) }
        live.wake()
        chirp.played.clear()

        transport.deliver("""{"type":"response.created"}""")
        transport.deliver("""{"type":"response.output_text.delta","delta":"That's the"}""")
        transport.deliver("""{"type":"response.output_text.delta","delta":" prompt box."}""")

        assertEquals(LiveVoice.State.Speaking, live.state)
        assertEquals(1, chirp.played.size) // chirped on the first non-empty delta
        assertEquals("That's the", captions[0].first)
        assertEquals("That's the prompt box.", captions[1].first)

        transport.deliver("""{"type":"response.done","response":{"output":[]}}""")

        assertEquals("That's the prompt box.", captions.last().first)
        assertEquals(true, captions.last().second)
        assertEquals(listOf("That's the prompt box."), replies)
        assertEquals(LiveVoice.State.Listening, live.state)
    }

    @Test
    fun `committed turns remember whether they were questions`() = runTest {
        val turns = mutableListOf<Pair<String, Boolean>>()
        val words = mutableListOf<Triple<String, String, Boolean>>()
        val live = build()
        live.onUserTurn = { item, asked -> turns.add(item to asked) }
        live.onUserWords = { item, text, asked -> words.add(Triple(item, text, asked)) }
        live.wake()

        live.beginAsk()
        live.endAsk() // awaitingQuestion = true
        transport.deliver("""{"type":"input_audio_buffer.committed","item_id":"item_q"}""")
        transport.deliver(
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_q","transcript":"what's this?"}""",
        )

        assertEquals("item_q" to true, turns[0])
        assertEquals(Triple("item_q", "what's this?", true), words[0])

        // A later overheard turn (no question in flight) is not a question.
        transport.deliver("""{"type":"input_audio_buffer.committed","item_id":"item_bg"}""")
        transport.deliver(
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_bg","transcript":"background chatter"}""",
        )
        assertEquals("item_bg" to false, turns[1])
        assertEquals(Triple("item_bg", "background chatter", false), words[1])
    }

    @Test
    fun `an empty commit clears the pending question`() = runTest {
        val turns = mutableListOf<Pair<String, Boolean>>()
        val live = build()
        live.onUserTurn = { item, asked -> turns.add(item to asked) }
        live.wake()

        live.beginAsk()
        live.endAsk()
        transport.deliver(
            """{"type":"error","error":{"code":"input_audio_buffer_commit_empty","message":"empty"}}""",
        )
        transport.deliver("""{"type":"input_audio_buffer.committed","item_id":"item_x"}""")
        assertEquals("item_x" to false, turns[0])
    }

    @Test
    fun `function calls run on the PC and their results plus screenshots go back in`() = runTest {
        val live = build(
            toolRunner = { name, arguments, done ->
                assertEquals("point_at", name)
                done("Pointing at \"Googly\".", "c2NyZWVu")
            },
        )
        live.wake()
        transport.sent.clear()

        transport.deliver(
            """
            {"type":"response.done","response":{"output":[
              {"type":"function_call","name":"point_at","call_id":"call_1","arguments":"{\"target_id\":\"W3\"}"}
            ]}}
            """.trimIndent(),
        )

        // Thinking until the follow-up response he asked for arrives.
        assertEquals(LiveVoice.State.Thinking, live.state)
        val output = transport.sentText("function_call_output").single()
        assertTrue(output.contains("\"call_id\":\"call_1\""))
        assertTrue(output.contains("Pointing at \\\"Googly\\\"."))
        val image = transport.sentText("input_image").single()
        assertTrue(image.contains("data:image/jpeg;base64,c2NyZWVu"))
        assertEquals(1, transport.sentText("response.create").size)
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

        transport.deliver(
            """
            {"type":"response.done","response":{"output":[
              {"type":"function_call","name":"web_research","call_id":"call_r","arguments":"{}"}
            ]}}
            """.trimIndent(),
        )
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

        transport.deliver(
            """
            {"type":"response.done","response":{"output":[
              {"type":"function_call","name":"go_to_sleep","call_id":"call_z","arguments":"{}"}
            ]}}
            """.trimIndent(),
        )

        assertEquals(LiveVoice.State.Thinking, live.state) // goodbye pending

        transport.deliver("""{"type":"response.created"}""")
        transport.deliver("""{"type":"response.output_text.delta","delta":"Night!"}""")
        transport.deliver("""{"type":"response.done","response":{"output":[]}}""")

        assertEquals(LiveVoice.State.Asleep, live.state)
        assertEquals(1, ended)
        assertEquals(1, mic.stopped)
        assertFalse(transport.open)
    }

    @Test
    fun `the connection drops and reconnects with the context replay`() = runTest {
        val live = build(context = "Bluey: hi\nYou: what's this?")
        live.wake()
        transport.sent.clear()

        transport.drop()
        pump() // flush the reconnect chain queued on the scheduler

        assertTrue(transport.open) // already reconnected (the token was instant)
        val replay = transport.sentText("input_text").single()
        assertTrue(replay.contains("(Context: what I said earlier in this session, from my notes. Don't reply to this.)"))
        assertTrue(replay.contains("Bluey: hi"))
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
                done(token)
            }
        }
        live.wake()
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
        live.beginAsk()
        transport.deliver("""{"type":"response.created"}""")

        live.sleep()

        assertEquals(LiveVoice.State.Asleep, live.state)
        assertFalse(transport.open)
        assertEquals(1, mic.stopped)
        // Holding state is gone: asking again starts fresh.
        live.beginAsk()
        live.endAsk()
        assertEquals(LiveVoice.State.Thinking, live.state)
    }

    @Test
    fun `say hi sends the exact test instructions`() = runTest {
        val live = build()
        live.wake()
        transport.sent.clear()
        live.sayHi()
        val event = transport.sentText("response.create").single()
        assertTrue(event.contains("Reply with a quick, cheerful hi in under eight words."))
    }

    @Test
    fun `say hi while asleep just wakes him`() = runTest {
        val live = build()
        live.sayHi()
        assertEquals(LiveVoice.State.Listening, live.state)
    }

    @Test
    fun `mic audio streams as append events`() = runTest {
        val live = build()
        live.wake()
        transport.sent.clear()
        mic.speak("QUJD")
        pump()
        val append = transport.sentText("input_audio_buffer.append").single()
        assertTrue(append.contains("\"audio\":\"QUJD\""))
    }

    @Test
    fun `a chirp still playing keeps him from settling back to listening`() = runTest {
        val live = build()
        live.wake()
        chirp.played.clear()
        chirp.pendingBuffers = 1 // the chirp is mid-flight

        transport.deliver("""{"type":"response.created"}""")
        transport.deliver("""{"type":"response.output_text.delta","delta":"Hello there"}""")
        transport.deliver("""{"type":"response.done","response":{"output":[]}}""")

        assertEquals(LiveVoice.State.Speaking, live.state) // waiting on the chirp

        chirp.pendingBuffers = 0
        transport.deliver("""{"type":"response.done","response":{"output":[]}}""")
        assertEquals(LiveVoice.State.Listening, live.state)
    }
}
