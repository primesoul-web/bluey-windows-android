package dev.bluey.client.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** SocketLink over a real localhost ServerSocket: connect, hello, faces, close. */
class SocketLinkTest {

    @Test
    fun `connects sends hello and receives packets`() = runBlocking {
        val server = ServerSocket(0)
        val receivedByServer = CopyOnWriteArrayList<Packet>()
        val sentFace = AtomicBoolean(false)
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val serverJob = serverScope.launch {
            val client = server.accept()
            val output: OutputStream = client.getOutputStream()
            val framer = LineFramer()
            val buffer = ByteArray(64 * 1024)
            val input = client.getInputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                framer.feed(buffer, n).forEach { receivedByServer.add(it) }
                // echo a face back once the phone has said hello
                if (receivedByServer.isNotEmpty() && sentFace.compareAndSet(false, true)) {
                    output.write(PacketCodec.encode(Packet(face = FaceState.create(0.25, -0.5, Mood.POINTING, 0.0))))
                    output.flush()
                }
            }
        }

        val link = SocketLink("127.0.0.1", server.localPort, "Pixel 9 (test)")
        val faces = CopyOnWriteArrayList<FaceState>()
        var ready = false
        var closed = false
        link.onReady = { ready = true }
        link.onPacket = { packet -> packet.face?.let { faces.add(it) } }
        link.onClosed = { closed = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        link.start(scope)

        withTimeout(10_000) {
            while (!ready) delay(10)
            while (faces.isEmpty()) delay(10)
        }

        assertEquals("Pixel 9 (test)", receivedByServer.first().hello)
        assertEquals(Mood.POINTING, faces.first().mood)
        assertEquals(0.25, faces.first().gazeX, 0.0)

        link.close()
        withTimeout(10_000) { while (!closed) delay(10) }
        assertTrue(closed)

        server.close()
        serverJob.cancel()
        serverScope.cancel()
        scope.cancel()
    }

    @Test
    fun `a refused connection still reports closed for the retry loop`() = runBlocking {
        // occupy a port, then close it so connects are refused
        val probe = java.net.ServerSocket(0)
        val port = probe.localPort
        probe.close()

        val link = SocketLink("127.0.0.1", port, "Pixel 9 (test)")
        var closed = false
        link.onClosed = { closed = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        link.start(scope)

        withTimeout(15_000) { while (!closed) delay(10) }
        assertTrue(closed)
        scope.cancel()
    }
}
