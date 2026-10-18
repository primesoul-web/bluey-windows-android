package dev.bluey.client.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One line-protocol TCP connection to the PC — the port of LineConnection over
 * NWConnection: hello right when the socket is ready, NDJSON framing, callbacks for
 * packets and for the connection dying. TCP_NODELAY on; connect timeout so a silent
 * network can't hang the retry loop (NWConnection's .waiting state did this on iOS).
 */
class SocketLink(
    private val host: String,
    private val port: Int,
    private val ourName: String,
) {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_CHUNK = 1024 * 1024 // 1 MiB reads, like the original
    }

    var onPacket: ((Packet) -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    /** Fires right after the TCP connection is established — NWConnection's .ready. */
    var onReady: (() -> Unit)? = null

    val isOpen: Boolean get() = socket?.isConnected == true && !closed

    private var socket: Socket? = null
    private val writeLock = Any()
    @Volatile private var closed = false

    /** Connects on a background thread; when it settles (either way) callbacks fire. */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            var local: Socket? = null
            try {
                val s = Socket()
                local = s
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                synchronized(writeLock) { socket = s }
                onReady?.invoke()
                send(Packet(hello = ourName)) // right when the socket is ready
                readLoop(s)
            } catch (_: Exception) {
                // fall through to close — the retry loop takes it from here
            } finally {
                local?.close()
                close()
            }
        }
    }

    fun send(packet: Packet) {
        val bytes = PacketCodec.encode(packet)
        synchronized(writeLock) {
            val s = socket ?: return
            try {
                val out = s.getOutputStream()
                out.write(bytes)
                out.flush()
            } catch (_: Exception) {
                // a dead socket is noticed by the read loop; writes just fail quietly
            }
        }
    }

    /** Idempotent; fires onClosed exactly once. */
    fun close() {
        if (closed) return
        closed = true
        try { socket?.close() } catch (_: Exception) { /* already gone */ }
        onClosed?.invoke()
    }

    private suspend fun readLoop(s: Socket) {
        val framer = LineFramer()
        val buffer = ByteArray(READ_CHUNK)
        val input = s.getInputStream()
        while (true) {
            val n = withContext(Dispatchers.IO) { input.read(buffer) }
            if (n < 0) break
            if (n == 0) continue
            val packets = framer.feed(buffer, n)
            for (packet in packets) {
                onPacket?.invoke(packet)
            }
        }
    }
}
