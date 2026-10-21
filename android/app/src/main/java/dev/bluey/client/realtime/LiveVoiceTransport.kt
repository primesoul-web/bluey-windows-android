package dev.bluey.client.realtime

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * One live connection to the Gemini Live API. An interface so the state machine can
 * be driven by a fake in tests; [OkHttpTransport] is the real one. The PC mints an
 * ephemeral token with the whole session config locked inside it, and the phone rides
 * that token in the access_token query parameter of the constrained endpoint — the
 * real AI Studio key never leaves the PC.
 *
 * Callbacks arrive on OkHttp's threads; [LiveVoice] marshals them to the main
 * dispatcher, matching the original's main-queue receive loop.
 */
interface RealtimeTransport {
    /** Opens the socket. Events and closure are reported as they happen. */
    fun connect(token: String, onEvent: (String) -> Unit, onClosed: () -> Unit)

    /** Sends one JSON message; false when the socket isn't open. Messages sent before
     * the socket opens (the setup) are queued by OkHttp and stay first. */
    fun send(json: String): Boolean

    /** Closes the socket (graceful; no reconnect is attempted by the transport). */
    fun close()
}

class OkHttpTransport(
    private val endpoint: String =
        "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained",
) : RealtimeTransport {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // keeps long sessions alive through NATs
        .build()

    private var socket: WebSocket? = null

    override fun connect(token: String, onEvent: (String) -> Unit, onClosed: () -> Unit) {
        val request = Request.Builder()
            .url("$endpoint?access_token=${URLEncoder.encode(token, "UTF-8")}")
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
