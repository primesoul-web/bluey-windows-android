package dev.bluey.client.realtime

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Realtime event codec: builders and parser, against hand-written API payloads. */
class RealtimeEventsTest {

    @Test
    fun `builds append audio events`() {
        val json = RealtimeEvents.appendAudio("QUJD")
        assertEquals("""{"type":"input_audio_buffer.append","audio":"QUJD"}""", json)
    }

    @Test
    fun `builds commit and response events`() {
        assertEquals("""{"type":"input_audio_buffer.commit"}""", RealtimeEvents.commitAudio)
        assertEquals("""{"type":"response.create"}""", RealtimeEvents.createResponse())
        val withInstructions = RealtimeEvents.createResponse("Reply with a quick, cheerful hi in under eight words.")
        val parsed = Json.parseToJsonElement(withInstructions).toString()
        assertTrue(parsed.contains("\"response\":{\"instructions\":\"Reply with a quick, cheerful hi"))
    }

    @Test
    fun `builds tool outputs and screenshot images`() {
        val output = RealtimeEvents.functionCallOutput("call_1", "Pointing at \"Googly\".")
        assertTrue(output.contains("\"type\":\"function_call_output\""))
        assertTrue(output.contains("\"call_id\":\"call_1\""))
        assertTrue(output.contains("\"output\":\"Pointing at \\\"Googly\\\".\""))

        val image = RealtimeEvents.inputImage("aGk=")
        assertTrue(image.contains("\"type\":\"input_image\""))
        assertTrue(image.contains("data:image/jpeg;base64,aGk="))
    }

    @Test
    fun `builds the context replay with the verbatim prefix`() {
        val json = RealtimeEvents.contextReplay("He said hello")
        assertTrue(json.startsWith("""{"type":"conversation.item.create","item":{"type":"message","role":"user","content":[{"type":"input_text","text":"(Context: what I said earlier in this session, from my notes. Don't reply to this.)\nHe said hello"}]}}"""))
    }

    @Test
    fun `parses the events LiveVoice reacts to`() {
        assertEquals(RealtimeEvents.Event.ResponseCreated, RealtimeEvents.parse("""{"type":"response.created"}"""))

        assertEquals(
            RealtimeEvents.Event.TextDelta("Hi"),
            RealtimeEvents.parse("""{"type":"response.output_text.delta","delta":"Hi"}"""),
        )
        assertEquals(
            RealtimeEvents.Event.TextDelta("Hi"),
            RealtimeEvents.parse("""{"type":"response.output_audio_transcript.delta","delta":"Hi"}"""),
        )
        assertEquals(
            RealtimeEvents.Event.BufferCommitted("item_1"),
            RealtimeEvents.parse("""{"type":"input_audio_buffer.committed","item_id":"item_1"}"""),
        )
        assertEquals(
            RealtimeEvents.Event.UserTranscript("item_1", "what's this"),
            RealtimeEvents.parse(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_1","transcript":"what's this"}""",
            ),
        )
    }

    @Test
    fun `parses response done with and without function calls`() {
        val plain = RealtimeEvents.parse("""{"type":"response.done","response":{"output":[]}}""")
        assertEquals(RealtimeEvents.Event.ResponseDone(emptyList()), plain)

        val withCall = RealtimeEvents.parse(
            """
            {"type":"response.done","response":{"output":[
              {"type":"message","role":"assistant"},
              {"type":"function_call","name":"point_at","call_id":"call_9","arguments":"{\"target_id\":\"W12\"}"}
            ]}}
            """.trimIndent(),
        ) as RealtimeEvents.Event.ResponseDone
        assertEquals(1, withCall.calls.size)
        assertEquals("point_at", withCall.calls[0].name)
        assertEquals("call_9", withCall.calls[0].callId)
        assertEquals("{\"target_id\":\"W12\"}", withCall.calls[0].arguments)
    }

    @Test
    fun `parses errors including the empty-commit code`() {
        val plain = RealtimeEvents.parse(
            """{"type":"error","error":{"code":"server_error","message":"boom"}}""",
        ) as RealtimeEvents.Event.ApiError
        assertEquals("boom", plain.message)
        assertFalse(plain.commitEmpty)

        val empty = RealtimeEvents.parse(
            """{"type":"error","error":{"code":"input_audio_buffer_commit_empty","message":"nothing to commit"}}""",
        ) as RealtimeEvents.Event.ApiError
        assertTrue(empty.commitEmpty)
    }

    @Test
    fun `ignores everything else`() {
        assertEquals(RealtimeEvents.Event.Ignored, RealtimeEvents.parse("""{"type":"session.created"}"""))
        assertEquals(RealtimeEvents.Event.Ignored, RealtimeEvents.parse("""{"type":"rate_limits.updated"}"""))
        assertEquals(RealtimeEvents.Event.Ignored, RealtimeEvents.parse("not json"))
        assertEquals(RealtimeEvents.Event.Ignored, RealtimeEvents.parse("[1,2,3]"))
        // An event type we handle but missing its field is ignored, not crashed.
        assertEquals(RealtimeEvents.Event.Ignored, RealtimeEvents.parse("""{"type":"input_audio_buffer.committed"}"""))
    }
}
