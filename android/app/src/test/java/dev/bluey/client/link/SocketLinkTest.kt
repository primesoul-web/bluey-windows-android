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

    // MARK: Greeting verification (v0.3.4) — the other-Bluey-on-the-Wi-Fi defense.

    @Test
    fun `verification accepts our PC greeting and routes what follows it`() = runBlocking {
        val server = ServerSocket(0)
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val serverJob = serverScope.launch {
            val client = server.accept()
            val output = client.getOutputStream()
            // our PC greets the instant it accepts, then streams faces
            output.write(PacketCodec.encode(Packet(hello = "DESKTOP-TEST")))
            output.flush()
            delay(50)
            output.write(PacketCodec.encode(Packet(face = FaceState.create(0.0, 0.0, Mood.HAPPY, 0.0))))
            output.flush()
            while (true) delay(100)
        }

        val link = SocketLink("127.0.0.1", server.localPort, "Pixel 9 (test)", verifyGreeting = true)
        var verifiedName: String? = null
        val faces = CopyOnWriteArrayList<FaceState>()
        val routedHello = AtomicBoolean(false)
        link.onVerified = { name -> verifiedName = name }
        link.onPacket = { packet ->
            if (packet.hello != null) routedHello.set(true) // the greeting must be consumed, not routed
            packet.face?.let { faces.add(it) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        link.start(scope)

        withTimeout(10_000) {
            while (verifiedName == null || faces.isEmpty()) delay(10)
        }
        assertEquals("DESKTOP-TEST", verifiedName)
        assertEquals(Mood.HAPPY, faces.first().mood)
        assertTrue("the greeting itself must not be routed as a packet", !routedHello.get())

        link.close()
        server.close()
        serverJob.cancel()
        serverScope.cancel()
        scope.cancel()
    }

    @Test
    fun `verification rejects a greeting without our hello field — the other fork`() = runBlocking {
        val server = ServerSocket(0)
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val serverJob = serverScope.launch {
            val client = server.accept()
            val output = client.getOutputStream()
            // the other fork greets with a command instead of a hello field
            output.write(PacketCodec.encode(Packet(command = "hello", text = "DESKTOP-TEST")))
            output.flush()
            delay(50)
            output.write(PacketCodec.encode(Packet(face = FaceState.create(0.0, 0.0, Mood.HAPPY, 0.0))))
            output.flush()
            while (true) delay(100)
        }

        val link = SocketLink("127.0.0.1", server.localPort, "Pixel 9 (test)", verifyGreeting = true)
        var rejection: String? = null
        var closed = false
        var anythingRouted = false
        link.onRejected = { reason -> rejection = reason }
        link.onPacket = { _ -> anythingRouted = true }
        link.onClosed = { closed = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        link.start(scope)

        withTimeout(10_000) {
            while (!closed) delay(10)
        }
        assertEquals(SocketLink.REASON_NOT_OURS, rejection)
        assertTrue("nothing from an impostor may be routed", !anythingRouted)

        server.close()
        serverJob.cancel()
        serverScope.cancel()
        scope.cancel()
    }

    @Test
    fun `verification rejects a peer that stays silent`() = runBlocking {
        val server = ServerSocket(0)
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val serverJob = serverScope.launch {
            server.accept() // accept, then say nothing — our PC never does this
            while (true) delay(100)
        }

        val link = SocketLink("127.0.0.1", server.localPort, "Pixel 9 (test)",
            verifyGreeting = true, greetingTimeoutMs = 300)
        var rejection: String? = null
        var closed = false
        link.onRejected = { reason -> rejection = reason }
        link.onClosed = { closed = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = System.currentTimeMillis()
        link.start(scope)

        withTimeout(10_000) {
            while (!closed) delay(10)
        }
        assertEquals(SocketLink.REASON_SILENT, rejection)
        assertTrue("the silent rejection should be prompt, not a 5 s connect timeout",
            System.currentTimeMillis() - started < 5_000)

        server.close()
        serverJob.cancel()
        serverScope.cancel()
        scope.cancel()
    }
}
