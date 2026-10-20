package dev.bluey.client.realtime

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * One live connection to OpenAI's Realtime API. An interface so the state machine can
 * be driven by a fake in tests; [OkHttpTransport] is the real one. The URL carries the
 * model and the minted secret rides in the Authorization header — never a beta header
 * (removed from the API 2026-05-12, PARITY.md D9).
 *
 * Callbacks arrive on OkHttp's threads; [LiveVoice] marshals them to the main
 * dispatcher, matching the original's main-queue receive loop.
 */
interface RealtimeTransport {
    /** Opens the socket. Events and closure are reported as they happen. */
    fun connect(token: String, onEvent: (String) -> Unit, onClosed: () -> Unit)

    /** Sends one JSON event; false when the socket isn't open. */
    fun send(json: String): Boolean

    /** Closes the socket (graceful; no reconnect is attempted by the transport). */
    fun close()
}

class OkHttpTransport(
    private val url: String = "wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1",
) : RealtimeTransport {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // keeps all-day sessions alive through NATs
        .build()

    private var socket: WebSocket? = null

    override fun connect(token: String, onEvent: (String) -> Unit, onClosed: () -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = onEvent(text).let { }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onClosed().let { }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed().let { }
        })
    }

    override fun send(json: String): Boolean = socket?.send(json) ?: false

    override fun close() {
        socket?.close(1000, null)
        socket = null
    }
}
