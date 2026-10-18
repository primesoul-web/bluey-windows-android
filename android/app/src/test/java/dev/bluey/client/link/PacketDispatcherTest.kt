package dev.bluey.client.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketDispatcherTest {

    @Test
    fun `request mints a callID the reply echoes`() {
        val sent = mutableListOf<Packet>()
        var reply: Packet? = null
        val dispatcher = PacketDispatcher(send = { sent.add(it) }, onCommand = { })

        dispatcher.request(Packet(command = "realtimeToken")) { reply = it }
        assertEquals(1, sent.size)
        assertEquals(1, dispatcher.outstanding)
        val id = sent[0].callID
        requireNotNull(id)

        dispatcher.handle(sent[0].copy(command = "realtimeToken", text = "googly-live-abc"))
        assertEquals(0, dispatcher.outstanding)
        assertEquals("googly-live-abc", reply?.text)
        assertEquals(id, reply?.callID)
    }

    @Test
    fun `replies with unknown callIDs fall through to commands`() {
        var command: String? = null
        val dispatcher = PacketDispatcher(send = { }, onCommand = { command = it })
        dispatcher.handle(Packet(command = "toolResult", callID = "not-mine", text = "x"))
        assertEquals("toolResult", command)
    }

    @Test
    fun `non-command events are dropped quietly`() {
        var command: String? = null
        val dispatcher = PacketDispatcher(send = { }, onCommand = { command = it })
        dispatcher.handle(Packet(hello = "PC"))
        dispatcher.handle(Packet(face = FaceState.create()))
        assertNull(command)
    }

    @Test
    fun `failAll clears the waiters with null`() {
        var result: Packet? = Packet(hello = "sentinel")
        val dispatcher = PacketDispatcher(send = { }, onCommand = { })
        dispatcher.request(Packet(command = "tool", tool = "click", text = "{}")) { result = it }
        assertEquals(1, dispatcher.outstanding)

        dispatcher.failAll()
        assertEquals(0, dispatcher.outstanding)
        assertNull(result)
    }
}
