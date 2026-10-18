package dev.bluey.client.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineFramerTest {

    @Test
    fun `multiple lines in one chunk all decode`() {
        val framer = LineFramer()
        val packets = framer.feed(
            """{"hello":"A"}{"hello":"B"}{"command":"wake"}""".let { json ->
                // inject the newlines between the objects
                json.replace("}{", "}\n{").encodeToByteArray() + "\n".encodeToByteArray()
            },
        )
        assertEquals(3, packets.size)
        assertEquals("A", packets[0].hello)
        assertEquals("B", packets[1].hello)
        assertEquals("wake", packets[2].command)
    }

    @Test
    fun `partial lines accumulate until the newline`() {
        val framer = LineFramer()
        assertTrue(framer.feed("""{"hel""".encodeToByteArray()).isEmpty())
        assertTrue(framer.feed("""lo":"Pixel""".encodeToByteArray()).isEmpty())
        val packets = framer.feed(""" 9"}""".encodeToByteArray() + byteArrayOf('\n'.code.toByte()))
        assertEquals(1, packets.size)
        assertEquals("Pixel 9", packets[0].hello)
    }

    @Test
    fun `a line without trailing newline is held`() {
        val framer = LineFramer()
        val first = framer.feed(
            """{"hello":"A"}""" .encodeToByteArray() + byteArrayOf('\n'.code.toByte()) +
                """{"hello":"B"}""".encodeToByteArray(),
        )
        assertEquals(1, first.size)
        assertEquals("A", first[0].hello)
        val second = framer.feed(
            byteArrayOf('\n'.code.toByte()) + """{"command":"sleep"}""".encodeToByteArray() +
                byteArrayOf('\n'.code.toByte()),
        )
        assertEquals(2, second.size)
        assertEquals("B", second[0].hello)
        assertEquals("sleep", second[1].command)
    }

    @Test
    fun `invalid lines are dropped but valid ones survive`() {
        val framer = LineFramer()
        val chunk = ("garbage\n" +
            """{"hello":"A"}""" + "\n" +
            "\n" +
            """{"face":{"gazeX":0,"gazeY":0,"mood":"excited","talk":0}}""" + "\n" +
            """{"command":"wake"}""" + "\n").encodeToByteArray()
        val packets = framer.feed(chunk)
        assertEquals(2, packets.size)
        assertEquals("A", packets[0].hello)
        assertEquals("wake", packets[1].command)
    }

    @Test
    fun `large audio packets survive fragmentation`() {
        val payload = "A".repeat(300_000)
        val framer = LineFramer()
        val line = """{"audio":"$payload","speech":1}""".encodeToByteArray() + byteArrayOf('\n'.code.toByte())
        assertTrue(framer.feed(line.copyOfRange(0, 100_000)).isEmpty())
        assertTrue(framer.feed(line.copyOfRange(100_000, 200_000)).isEmpty())
        val packets = framer.feed(line.copyOfRange(200_000, line.size))
        assertEquals(1, packets.size)
        assertEquals(300_000, packets[0].audio!!.length)
        assertEquals(1, packets[0].speech)
    }

    @Test
    fun `a packet may arrive byte by byte`() {
        val framer = LineFramer()
        val bytes =
            """{"face":{"gazeX":0.5,"gazeY":0,"mood":"happy","talk":0}}""".encodeToByteArray() +
                byteArrayOf('\n'.code.toByte())
        var packets: List<Packet> = emptyList()
        for (b in bytes) {
            packets = framer.feed(byteArrayOf(b))
        }
        assertEquals(1, packets.size)
        assertEquals(Mood.HAPPY, packets[0].face!!.mood)
        assertEquals(0.5, packets[0].face!!.gazeX, 0.0)
    }
}
