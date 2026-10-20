package dev.bluey.client.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The events the phone exchanges with the OpenAI Realtime API. The session config
 * itself is baked into the PC-minted client secret, so the phone only ever sends
 * audio, commits, response requests, tool outputs and the context replay.
 */
object RealtimeEvents {

    val json = Json { ignoreUnknownKeys = true }

    // MARK: Building client events

    fun appendAudio(base64Pcm16: String): String = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", base64Pcm16)
    }.toString()

    val commitAudio: String = """{"type":"input_audio_buffer.commit"}"""

    fun createResponse(instructions: String? = null): String =
        if (instructions == null) {
            """{"type":"response.create"}"""
        } else {
            buildJsonObject {
                put("type", "response.create")
                put("response", buildJsonObject { put("instructions", instructions) })
            }.toString()
        }

    fun functionCallOutput(callId: String, output: String): String = buildJsonObject {
        put("type", "conversation.item.create")
        put("item", buildJsonObject {
            put("type", "function_call_output")
            put("call_id", callId)
            put("output", output)
        })
    }.toString()

    fun inputImage(jpegBase64: String): String = buildJsonObject {
        put("type", "conversation.item.create")
        put("item", buildJsonObject {
            put("type", "message")
            put("role", "user")
            put("content", JsonArray(listOf(buildJsonObject {
                put("type", "input_image")
                put("image_url", "data:image/jpeg;base64,$jpegBase64")
            })))
        })
    }.toString()

    /** The context replay after a reconnect — verbatim prefix from LiveVoice.swift. */
    fun contextReplay(context: String): String = buildJsonObject {
        put("type", "conversation.item.create")
        put("item", buildJsonObject {
            put("type", "message")
            put("role", "user")
            put("content", JsonArray(listOf(buildJsonObject {
                put("type", "input_text")
                put(
                    "text",
                    "(Context: what I said earlier in this session, from my notes. Don't reply to this.)\n" + context,
                )
            })))
        })
    }.toString()

    // MARK: Parsing server events

    /** A parsed server event: the type plus the fields LiveVoice reacts to. */
    sealed class Event {
        /** A response started: reset the transcript, no chirp yet. */
        data object ResponseCreated : Event()

        /** A text delta of his reply (either channel; text is what we show). */
        data class TextDelta(val delta: String) : Event()

        /** An audio buffer was committed; the item id says which turn it became. */
        data class BufferCommitted(val itemId: String) : Event()

        /** The words the user said in one turn (live transcription). */
        data class UserTranscript(val itemId: String, val transcript: String) : Event()

        /** A response finished; carries any function calls it ended with. */
        data class ResponseDone(val calls: List<FunctionCall>) : Event()

        /** API error; `commitEmpty` marks input_audio_buffer_commit_empty. */
        data class ApiError(val message: String, val commitEmpty: Boolean) : Event()

        /** Everything else (session.created, rate limits, …) is ignored, like the original. */
        data object Ignored : Event()
    }

    data class FunctionCall(val name: String, val callId: String, val arguments: String)

    fun parse(text: String): Event {
        val obj = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            return Event.Ignored
        } as? JsonObject ?: return Event.Ignored

        return when (obj.string("type")) {
            "response.created" -> Event.ResponseCreated

            "response.output_text.delta", "response.output_audio_transcript.delta" ->
                Event.TextDelta(obj.string("delta") ?: "")

            "input_audio_buffer.committed" ->
                Event.BufferCommitted(obj.string("item_id") ?: return Event.Ignored)

            "conversation.item.input_audio_transcription.completed" ->
                Event.UserTranscript(
                    obj.string("item_id") ?: return Event.Ignored,
                    obj.string("transcript") ?: "",
                )

            "response.done" -> {
                val output = ((obj["response"] as? JsonObject)?.get("output") as? JsonArray)
                    ?.filterIsInstance<JsonObject>().orEmpty()
                val calls = output.mapNotNull { item ->
                    if (item.string("type") == "function_call") {
                        FunctionCall(
                            name = item.string("name") ?: "",
                            callId = item.string("call_id") ?: "",
                            arguments = item.string("arguments") ?: "{}",
                        )
                    } else null
                }
                Event.ResponseDone(calls)
            }

            "error" -> {
                val error = obj["error"] as? JsonObject
                Event.ApiError(
                    message = error?.string("message") ?: "Something went wrong.",
                    commitEmpty = error?.string("code") == "input_audio_buffer_commit_empty",
                )
            }

            else -> Event.Ignored
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (this[key] as? JsonPrimitive)?.content
}
