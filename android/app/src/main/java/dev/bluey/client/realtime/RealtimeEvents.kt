package dev.bluey.client.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The messages the phone exchanges with the Gemini Live API over
 * BidiGenerateContentConstrained. The session config itself is locked inside the
 * PC-minted ephemeral token, so the phone only ever sends its setup (the model), mic
 * audio, the activity signals that frame a held question, tool responses, and the
 * context replay — never the personality or the tools.
 *
 * He never talks out loud: the model answers in audio (native-audio models can't answer
 * text-only) and the phone reads the output transcription as his bubble. The audio
 * itself is never played — his voice is the chirp, exactly like the original.
 */
object RealtimeEvents {

    val json = Json { ignoreUnknownKeys = true }

    const val DefaultModel = "gemini-3.8-live"

    // MARK: Building client messages

    /** The first message on the socket. The token's locked config is what actually runs;
     * the model is the one field the connection must state itself. */
    fun setup(model: String?): String = buildJsonObject {
        putJsonObject("setup") { put("model", "models/${model ?: DefaultModel}") }
    }.toString()

    /** One chunk of mic audio: raw little-endian PCM16 at 16 kHz, base64. */
    fun audioChunk(base64Pcm16: String): String = buildJsonObject {
        putJsonObject("realtimeInput") {
            putJsonObject("audio") {
                put("data", base64Pcm16)
                put("mimeType", "audio/pcm;rate=16000")
            }
        }
    }.toString()

    /** You pressed and held the screen: what you say now is the question. */
    val activityStart: String = """{"realtimeInput":{"activityStart":{}}}"""

    /** You let go: the turn completes and the model answers — the original's
     * input_audio_buffer.commit + response.create in one signal. */
    val activityEnd: String = """{"realtimeInput":{"activityEnd":{}}}"""

    /** The results of the tools the PC ran, matched by id. With blocking tools this
     * also resumes the model, which then replies. */
    fun toolResponse(results: List<ToolResult>): String = buildJsonObject {
        putJsonObject("toolResponse") {
            put("functionResponses", buildJsonArray {
                for (r in results) {
                    add(buildJsonObject {
                        put("id", r.callId)
                        put("name", r.name)
                        putJsonObject("response") { put("result", r.output) }
                    })
                }
            })
        }
    }.toString()

    /** A screenshot from a tool result, going back in so he can actually see the screen
     * (the original's input_image). It completes the turn, which starts his follow-up —
     * the original's input_image + response.create pair in one message. Sent after the
     * tool response, mirroring the original's outputs → images → reply order. */
    fun imageTurn(jpegBase64: String): String = clientContent(
        parts = buildJsonArray {
            add(buildJsonObject {
                putJsonObject("inlineData") {
                    put("mimeType", "image/jpeg")
                    put("data", jpegBase64)
                }
            })
        },
        turnComplete = true,
    )

    /** A quick hi (for checking the chirp volume) — the original's response.create with
     * instructions; here a user turn that asks for the reply. */
    fun sayHi(instructions: String): String = textTurn(instructions, turnComplete = true)

    /** The context replay after a reconnect — verbatim prefix from LiveVoice.swift.
     * No turn completion: he is reminded, not asked anything. */
    fun contextReplay(context: String): String = textTurn(
        "(Context: what I said earlier in this session, from my notes. Don't reply to this.)\n" + context,
        turnComplete = false,
    )

    private fun textTurn(text: String, turnComplete: Boolean): String = clientContent(
        parts = buildJsonArray {
            add(buildJsonObject { put("text", text) })
        },
        turnComplete = turnComplete,
    )

    private fun clientContent(parts: JsonArray, turnComplete: Boolean): String = buildJsonObject {
        putJsonObject("clientContent") {
            put("turns", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", parts)
                })
            })
            put("turnComplete", turnComplete)
        }
    }.toString()

    // MARK: Parsing server messages

    /** A parsed server message: the kind plus the fields LiveVoice reacts to. */
    sealed class Event {
        /** The locked session is live; queued sends flush and the mic starts. */
        data object SetupComplete : Event()

        /** More of his reply, as output transcription (the bubble's text). */
        data class TextDelta(val delta: String) : Event()

        /** Words the user said (input transcription), for the saved transcript. */
        data class UserTranscript(val text: String) : Event()

        /** His turn (reply and any tool calls) is fully over. */
        data object TurnComplete : Event()

        /** A new question cut his reply short. */
        data object Interrupted : Event()

        /** The model asked for tools; the phone relays them to the PC. */
        data class ToolCalls(val calls: List<FunctionCall>) : Event()

        /** Everything else (model audio, usage, goAway, …) is ignored, like the original. */
        data object Ignored : Event()
    }

    data class FunctionCall(val name: String, val callId: String, val arguments: String)

    data class ToolResult(val callId: String, val name: String, val output: String)

    fun parse(text: String): Event {
        val obj = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            return Event.Ignored
        } as? JsonObject ?: return Event.Ignored

        if (obj.containsKey("setupComplete")) return Event.SetupComplete

        obj["toolCall"]?.let { toolCall ->
            val calls = ((toolCall as? JsonObject)?.get("functionCalls") as? JsonArray)
                ?.filterIsInstance<JsonObject>().orEmpty()
                .mapNotNull { call ->
                    val name = call.string("name") ?: return@mapNotNull null
                    FunctionCall(
                        name = name,
                        callId = call.string("id") ?: call.string("callId") ?: "",
                        arguments = call["args"]?.toString() ?: "{}",
                    )
                }
            if (calls.isNotEmpty()) return Event.ToolCalls(calls)
            return Event.Ignored
        }

        obj["serverContent"]?.let { content ->
            val server = content as? JsonObject ?: return Event.Ignored
            // A serverContent may carry several flags; the interesting ones never share
            // a message, but the priority below stays safe if they ever do.
            (server["outputTranscription"] as? JsonObject)?.string("text")?.let { return Event.TextDelta(it) }
            (server["inputTranscription"] as? JsonObject)?.string("text")?.let { return Event.UserTranscript(it) }
            if (server.isTrue("turnComplete")) return Event.TurnComplete
            if (server.isTrue("interrupted")) return Event.Interrupted
            return Event.Ignored
        }

        return Event.Ignored
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.isTrue(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.content == "true"
}
