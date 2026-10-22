package dev.bluey.client.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Live API server delivers its JSON as BINARY WebSocket frames (found on the wire
 * 2026-10-22). OkHttpTransport must decode those as UTF-8 and hand them to the state
 * machine, while dropping the model's audio (also binary). These tests pin that split.
 */
class BinaryFrameTest {

    @Test
    fun `json object frame decodes`() {
        val json = """{"setupComplete":{}}"""
        assertEquals(json, OkHttpTransport.binaryFrameAsJson(json.toByteArray()))
    }

    @Test
    fun `json with trailing newline decodes`() {
        val json = "{\"serverContent\":{\"outputTranscription\":{\"text\":\"hi\"}}}"
        assertEquals(json, OkHttpTransport.binaryFrameAsJson((json + "\n").toByteArray()))
    }

    @Test
    fun `json with leading whitespace decodes`() {
        val json = """{"goAway":{"timeLeft":"3s"}}"""
        assertEquals(json, OkHttpTransport.binaryFrameAsJson(("  " + json).toByteArray()))
    }

    @Test
    fun `pcm audio drops`() {
        // Rising-byte noise: never valid UTF-8 text shaped like an object.
        val audio = ByteArray(512) { ((it * 31 + 7) and 0xFF).toByte() }
        assertNull(OkHttpTransport.binaryFrameAsJson(audio))
    }

    @Test
    fun `text that is not an object drops`() {
        assertNull(OkHttpTransport.binaryFrameAsJson("\"just a string\"".toByteArray()))
        assertNull(OkHttpTransport.binaryFrameAsJson("[1,2,3]".toByteArray()))
    }

    @Test
    fun `empty and tiny frames drop`() {
        assertNull(OkHttpTransport.binaryFrameAsJson(ByteArray(0)))
        assertNull(OkHttpTransport.binaryFrameAsJson(byteArrayOf(0x7B))) // lone '{'
    }

    @Test
    fun `brace flanked noise survives the parser as ignored`() {
        // The one-in-many audio frame that aliases braces must not crash the event loop.
        val sneaky = "{${String(CharArray(200) { 'z' })}}"
        val decoded = OkHttpTransport.binaryFrameAsJson(sneaky.toByteArray())
        assertEquals(sneaky, decoded)
        assertEquals(RealtimeEvents.Event.Ignored, RealtimeEvents.parse(decoded!!))
    }
}
