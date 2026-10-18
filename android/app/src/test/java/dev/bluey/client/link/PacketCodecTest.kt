package dev.bluey.client.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketCodecTest {

    private fun encodeText(packet: Packet): String =
        PacketCodec.encode(packet).decodeToString().trimEnd('\n')

    @Test
    fun `hello round trips`() {
        val packet = PacketCodec.tryDecode("""{"hello":"Studio PC"}""")
        assertEquals("Studio PC", packet?.hello)
        assertEquals("""{"hello":"Studio PC"}""", encodeText(packet!!))
    }

    @Test
    fun `unset fields are omitted never null`() {
        val encoded = encodeText(Packet(hello = "PC"))
        assertEquals("""{"hello":"PC"}""", encoded)
        assertTrue(!encoded.contains("null"))
    }

    @Test
    fun `face round trips with all fields`() {
        val packet = PacketCodec.tryDecode(
            """{"face":{"gazeX":0.42,"gazeY":-0.31,"mood":"pointing","talk":0}}""",
        )
        val face = packet?.face!!
        assertEquals(0.42, face.gazeX, 0.0)
        assertEquals(-0.31, face.gazeY, 0.0)
        assertEquals(Mood.POINTING, face.mood)
        assertEquals(0.0, face.talk, 0.0)
        assertEquals(
            """{"face":{"gazeX":0.42,"gazeY":-0.31,"mood":"pointing","talk":0.0}}""".replace("0.0}", "0}"),
            encodeText(packet).replace("0.0}", "0}"), // number formatting tolerance
        )
    }

    @Test
    fun `all seven moods round trip`() {
        listOf(
            "listening", "resting", "thinking", "talking",
            "pointing", "happy", "sleepy",
        ).forEach { mood ->
            val packet = PacketCodec.tryDecode(
                """{"face":{"gazeX":0,"gazeY":0,"mood":"$mood","talk":0}}""",
            )
            assertNotNull(packet)
            assertTrue(encodeText(packet!!).contains(""""mood":"$mood""""))
        }
    }

    private fun assertNotNull(packet: Packet?) = org.junit.Assert.assertNotNull(packet)

    @Test
    fun `integer literals decode into double fields`() {
        assertEquals(1.0, PacketCodec.tryDecode("""{"volume":1}""")?.volume!!, 0.0)
        val face = PacketCodec.tryDecode("""{"face":{"gazeX":0,"gazeY":1,"mood":"happy","talk":1}}""")?.face!!
        assertEquals(1.0, face.gazeY, 0.0)
        assertEquals(1.0, face.talk, 0.0)
    }

    @Test
    fun `callID keeps its exact casing`() {
        val packet = PacketCodec.tryDecode("""{"command":"tool","callID":"ABC-123","tool":"click","text":"{}"}""")
        assertEquals("ABC-123", packet?.callID)
        val encoded = encodeText(packet!!)
        assertTrue(encoded.contains(""""callID":"ABC-123""""))
        assertTrue(!encoded.contains("callId"))
    }

    @Test
    fun `unknown fields are ignored`() {
        val packet = PacketCodec.tryDecode("""{"hello":"PC","futureField":42,"nested":{"a":[1,2,3]}}""")
        assertEquals("PC", packet?.hello)
        assertEquals("""{"hello":"PC"}""", encodeText(packet!!))
    }

    @Test
    fun `legacy fields decode and re-encode`() {
        val packet = PacketCodec.tryDecode("""{"volume":0.75,"speech":3,"command":"playing"}""")
        assertEquals(0.75, packet?.volume!!, 0.0)
        assertEquals(3, packet.speech)
        assertEquals("playing", packet.command)
        // fields encode in declaration order, like the original
        assertEquals("""{"volume":0.75,"command":"playing","speech":3}""", encodeText(packet))
    }

    @Test
    fun `field order matches the original declaration`() {
        val packet = Packet(
            face = FaceState.create(),
            hello = "PC",
            volume = 0.5,
            command = "tool",
            audio = "AAAA",
            speech = 1,
            callID = "X",
            tool = "click",
            text = "hi",
            image = "aGk=",
        )
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(encodeText(packet))
            .let { it as kotlinx.serialization.json.JsonObject }
            .keys.toList()
        assertEquals(
            listOf("face", "hello", "volume", "command", "audio", "speech", "callID", "tool", "text", "image"),
            keys,
        )
    }

    @Test
    fun `invalid lines are rejected`() {
        listOf(
            "not json at all",
            "",
            """{"hello":123}""",
            """{"face":{"gazeX":"left","gazeY":0,"mood":"listening","talk":0}}""",
            """{"face":{"gazeX":0,"gazeY":0,"mood":"excited","talk":0}}""",
            """{"face":{"gazeY":0,"mood":"listening","talk":0}}""",
            """{"face":{"gazeX":0,"gazeY":0,"mood":"listening"}}""",
            """{"face":{"gazeX":0,"gazeY":0}}""",
            """{"command":42}""",
            """{"speech":"three"}""",
            """{"volume":"loud"}""",
            """{"face":"sleepy"}""",
            "[]",
            "\"just a string\"",
        ).forEach { line ->
            assertNull("expected rejection: $line", PacketCodec.tryDecode(line))
        }
    }
}
