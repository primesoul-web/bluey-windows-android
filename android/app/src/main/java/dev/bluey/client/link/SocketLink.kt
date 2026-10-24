package dev.bluey.client.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One line-protocol TCP connection to the PC — the port of LineConnection over
 * NWConnection: hello right when the socket is ready, NDJSON framing, callbacks for
 * packets and for the connection dying. TCP_NODELAY on; connect timeout so a silent
 * network can't hang the retry loop (NWConnection's .waiting state did this on iOS).
 *
 * Greeting verification (v0.3.4): with [verifyGreeting] set, the link only stays up
 * if the peer's FIRST packet is our PC app's greeting — a packet carrying `hello`.
 * Another Bluey fork shares this Wi-Fi and the `_googly._tcp` service NAME
 * (2026-10-24: two PC apps, one hostname, ports 51826 and 47821 — Android's
 * NsdManager can't tell them apart and hands out whichever answered last), and its
 * face stream is byte-compatible with ours, so a wrong-PC link looks perfectly
 * healthy while every request we send it disappears. Our PC greets with
 * `{"hello":"<hostname>"}` the instant it accepts; the impostor greets with
 * `{"command":"hello",...}` — no `hello` field. That's the tell. A peer that stays
 * silent past [greetingTimeoutMs] is rejected too: our PC never does.
 */
class SocketLink(
    private val host: String,
    private val port: Int,
    private val ourName: String,
    private val verifyGreeting: Boolean = false,
    private val greetingTimeoutMs: Int = 1_500,
) {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_CHUNK = 1024 * 1024 // 1 MiB reads, like the original

        /** Rejection reasons, for [onRejected]. */
        const val REASON_SILENT = "silent"
        const val REASON_NOT_OURS = "not-ours"
    }

    var onPacket: ((Packet) -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    /** Fires right after the TCP connection is established — NWConnection's .ready. */
    var onReady: (() -> Unit)? = null

    /** With [verifyGreeting]: our PC's greeting arrived and named it. The greeting
     * itself is consumed here — [onPacket] only ever sees what follows it. */
    var onVerified: ((pcName: String?) -> Unit)? = null

    /** With [verifyGreeting]: the peer is NOT our PC app. "not-ours" — it greeted
     * without a `hello` field (the other fork); "silent" — nothing arrived in time. */
    var onRejected: ((reason: String) -> Unit)? = null

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
                if (verifyGreeting) s.soTimeout = greetingTimeoutMs
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
                // A write that throws means the socket is locally dead — tear the link
                // down now so the retry loop replaces it. Swallowing this (v0.3.2 and
                // earlier) let requests queue into a black hole while `connected` still
                // said true, which surfaced only as a vague 20-second timeout on the
                // phone (found in the wild 2026-10-22).
                try { s.close() } catch (_: Exception) { /* already gone */ }
                close()
                return
            }
        }
    }

    /** Idempotent; fires onClosed exactly once. */
    fun close() {
        if (closed) return
        closed = true
        try { socket?.close() } catch (_: Exception) { /* already closed */ }
        onClosed?.invoke()
    }

    private suspend fun readLoop(s: Socket) {
        val framer = LineFramer()
        val buffer = ByteArray(READ_CHUNK)
        val input = s.getInputStream()
        var greetingDone = !verifyGreeting
        while (true) {
            val n = try {
                withContext(Dispatchers.IO) { input.read(buffer) }
            } catch (e: SocketTimeoutException) {
                // Only reachable while the greeting timeout is armed; a verified link
                // runs with soTimeout back at zero.
                if (greetingDone) continue
                onRejected?.invoke(REASON_SILENT)
                return
            }
            if (n < 0) break
            if (n == 0) continue
            val packets = framer.feed(buffer, n)
            for (packet in packets) {
                if (!greetingDone) {
                    greetingDone = true
                    s.soTimeout = 0
                    if (packet.hello != null) {
                        onVerified?.invoke(packet.hello)
                    } else {
                        // Greeted, but not in our voice — the other fork. Don't send
                        // another byte to it; the finally in start() closes us.
                        onRejected?.invoke(REASON_NOT_OURS)
                        return
                    }
                    continue // the greeting is consumed, never routed
                }
                onPacket?.invoke(packet)
            }
        }
    }
}
