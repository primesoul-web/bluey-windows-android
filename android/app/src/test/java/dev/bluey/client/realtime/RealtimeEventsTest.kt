package dev.bluey.client.realtime

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Live API message codec: builders and parser, against hand-written API payloads. */
class RealtimeEventsTest {

    @Test
    fun `builds the setup naming the minted model`() {
        assertEquals(
            """{"setup":{"model":"models/gemini-3.8-live"}}""",
            RealtimeEvents.setup("gemini-3.8-live"),
        )
        assertEquals(
            """{"setup":{"model":"models/gemini-3.8-live"}}""",
            RealtimeEvents.setup(null),
        )
    }

    @Test
    fun `builds realtime audio chunks at the native input rate`() {
        assertEquals(
            """{"realtimeInput":{"audio":{"data":"QUJD","mimeType":"audio/pcm;rate=16000"}}}""",
            RealtimeEvents.audioChunk("QUJD"),
        )
    }

    @Test
    fun `builds the activity signals that frame a held question`() {
        assertEquals("""{"realtimeInput":{"activityStart":{}}}""", RealtimeEvents.activityStart)
        assertEquals("""{"realtimeInput":{"activityEnd":{}}}""", RealtimeEvents.activityEnd)
    }

    @Test
    fun `builds tool responses matched by id`() {
        val json = RealtimeEvents.toolResponse(
            listOf(RealtimeEvents.ToolResult("call_1", "point_at", "Pointing at \"Googly\".")),
        )
        assertEquals(
            """{"toolResponse":{"functionResponses":[{"id":"call_1","name":"point_at",""" +
                """"response":{"result":"Pointing at \"Googly\"."}}]}}""",
            json,
        )
    }

    @Test
    fun `builds screenshot turns that complete the turn like the original's input image`() {
        val json = RealtimeEvents.imageTurn("aGk=")
        assertEquals(
            """{"clientContent":{"turns":[{"role":"user","parts":[{"inlineData":{""" +
                """"mimeType":"image/jpeg","data":"aGk="}}]}],"turnComplete":true}}""",
            json,
        )
    }

    @Test
    fun `builds say hi as an asking user turn`() {
        val json = RealtimeEvents.sayHi("Reply with a quick, cheerful hi in under eight words.")
        val parsed = Json.parseToJsonElement(json).toString()
        assertTrue(parsed.contains("\"turnComplete\":true"))
        assertTrue(parsed.contains("Reply with a quick, cheerful hi"))
    }

    @Test
    fun `builds the context replay with the verbatim prefix and no turn completion`() {
        val json = RealtimeEvents.contextReplay("He said hello")
        assertTrue(json.startsWith("""{"clientContent":{"turns":[{"role":"user","parts":[{"text":"""))
        assertTrue(json.contains("(Context: what I said earlier in this session, from my notes. Don't reply to this.)\\nHe said hello"))
        assertTrue(json.contains("\"turnComplete\":false"))
    }

    @Test
    fun `parses setup complete`() {
        assertEquals(RealtimeEvents.Event.SetupComplete, RealtimeEvents.parse("""{"setupComplete":{}}"""))
    }

    @Test
    fun `parses his reply as output transcription and the user's words as input transcription`() {
        assertEquals(
            RealtimeEvents.Event.TextDelta("Hi"),
            RealtimeEvents.parse("""{"serverContent":{"outputTranscription":{"text":"Hi"}}}"""),
        )
        assertEquals(
            RealtimeEvents.Event.UserTranscript("what's this"),
            RealtimeEvents.parse("""{"serverContent":{"inputTranscription":{"text":"what's this"}}}"""),
        )
    }

    @Test
    fun `parses turn complete and interrupted`() {
        assertEquals(
            RealtimeEvents.Event.TurnComplete,
            RealtimeEvents.parse("""{"serverContent":{"turnComplete":true,"interactionStatus":"IDLE"}}"""),
        )
        assertEquals(
            RealtimeEvents.Event.Interrupted,
            RealtimeEvents.parse("""{"serverContent":{"interrupted":true}}"""),
        )
    }

    @Test
    fun `parses tool calls with args as json and without`() {
        val withArgs = RealtimeEvents.parse(
            """{"toolCall":{"functionCalls":[{"name":"point_at","id":"call_9","args":{"target_id":"W12"}}]}}""",
        ) as RealtimeEvents.Event.ToolCalls
        assertEquals(1, withArgs.calls.size)
        assertEquals("point_at", withArgs.calls[0].name)
        assertEquals("call_9", withArgs.calls[0].callId)
        assertEquals("{\"target_id\":\"W12\"}", withArgs.calls[0].arguments)

        val withoutArgs = RealtimeEvents.parse(
            """{"toolCall":{"functionCalls":[{"name":"go_to_sleep","id":"call_z"}]}}""",
        ) as RealtimeEvents.Event.ToolCalls
        assertEquals("{}", withoutArgs.calls[0].arguments)
    }

    @Test
    fun `ignores model audio usage resumption notices and junk`() {
        // His voice is the chirp — the model's audio is never played.
        assertEquals(
            RealtimeEvents.Event.Ignored,
            RealtimeEvents.parse(
                """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"QUJD"}}]}}}""",
            ),
        )
        assertEquals(RealtimeEvents.Event.Ignored, RealtimeEvents.parse("""{"usageMetadata":{"totalTokenCount":42}}"""))
        assertEquals(RealtimeEvents.Event.Ignored, RealtimeEvents.parse("""{"goAway":{"timeLeft":"30s"}}"""))
        assertEquals(RealtimeEvents.Event.Ignored, RealtimeEvents.parse("""{"sessionResumptionUpdate":{"newHandle":"h","resumable":true}}"""))
        assertEquals(RealtimeEvents.Event.Ignored, RealtimeEvents.parse("not json"))
        assertEquals(RealtimeEvents.Event.Ignored, RealtimeEvents.parse("[1,2,3]"))
        // A kind we handle but missing its fields is ignored, not crashed.
        assertEquals(RealtimeEvents.Event.Ignored, RealtimeEvents.parse("""{"toolCall":{"functionCalls":[]}}"""))
        assertEquals(RealtimeEvents.Event.Ignored, RealtimeEvents.parse("""{"serverContent":{"generationComplete":true}}"""))
    }
}
