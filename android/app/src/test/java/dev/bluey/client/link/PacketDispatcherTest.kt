package dev.bluey.client.link

import kotlinx.coroutines.test.runTest
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

    @Test
    fun `a request times out to null when no reply comes`() = runTest {
        // The phone→PC black hole (2026-10-22): a request sent into a link that never
        // delivers must answer null on its own — not hang until a vague 20 s watchdog.
        val dispatcher = PacketDispatcher(send = { }, onCommand = { }, timeoutScope = backgroundScope)
        var reply: Packet? = Packet(hello = "sentinel")
        dispatcher.request(Packet(command = "realtimeToken"), timeoutMs = 6_000) { reply = it }
        assertEquals(1, dispatcher.outstanding)

        testScheduler.advanceTimeBy(6_000)
        testScheduler.runCurrent()

        assertEquals(0, dispatcher.outstanding)
        assertNull(reply)
    }

    @Test
    fun `a reply before the timeout wins and the timer stays quiet`() = runTest {
        val sent = mutableListOf<Packet>()
        val dispatcher = PacketDispatcher(send = { sent.add(it) }, onCommand = { }, timeoutScope = backgroundScope)
        var reply: Packet? = null
        dispatcher.request(Packet(command = "realtimeToken"), timeoutMs = 6_000) { reply = it }

        dispatcher.handle(sent[0].copy(command = "realtimeToken", text = "googly-live-abc"))
        assertEquals("googly-live-abc", reply?.text)

        testScheduler.advanceUntilIdle() // the cancelled timer must not overwrite the reply
        assertEquals(0, dispatcher.outstanding)
        assertEquals("googly-live-abc", reply?.text)
    }

    @Test
    fun `requests without a timeout wait forever like before`() = runTest {
        // Tools (look_at_screen, web_research) may take longer than any fixed budget;
        // they keep the old behavior.
        val dispatcher = PacketDispatcher(send = { }, onCommand = { }, timeoutScope = backgroundScope)
        var reply: Packet? = Packet(hello = "sentinel")
        dispatcher.request(Packet(command = "tool", tool = "click")) { reply = it }

        testScheduler.advanceUntilIdle()

        assertEquals(1, dispatcher.outstanding)
        assertEquals("sentinel", reply?.hello)
    }
}
