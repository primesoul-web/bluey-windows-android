package dev.bluey.client.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Everything the phone and the PC agree on: the mDNS service name. Port of GooglyService in Shared/GooglyLink.swift. */
object GooglyService {
    const val TYPE = "_googly._tcp"
}

/** The seven moods; SerialNames are the exact wire strings. Port of Mood in Shared/GooglyLink.swift. */
@Serializable
enum class Mood {
    @SerialName("listening") LISTENING,
    @SerialName("resting") RESTING,
    @SerialName("thinking") THINKING,
    @SerialName("talking") TALKING,
    @SerialName("pointing") POINTING,
    @SerialName("happy") HAPPY,
    @SerialName("sleepy") SLEEPY,
}

/** What the phone's face should show right now. All four fields are required on the wire. */
@Serializable
data class FaceState(
    val gazeX: Double,
    val gazeY: Double,
    val mood: Mood,
    val talk: Double,
) {
    companion object {
        fun create(
            gazeX: Double = 0.0,
            gazeY: Double = 0.0,
            mood: Mood = Mood.LISTENING,
            talk: Double = 0.0,
        ) = FaceState(gazeX, gazeY, mood, talk)
    }
}

/**
 * One newline-delimited JSON message. All fields optional; unset fields are omitted,
 * never null. Port of Packet in Shared/GooglyLink.swift (declaration order kept so
 * encodings match the original field-for-field).
 */
@Serializable
data class Packet(
    val face: FaceState? = null,
    val hello: String? = null,
    /** Legacy (speech-audio era). Accepted, never emitted. */
    val volume: Double? = null,
    val command: String? = null,
    /** Base64 audio: AAC meeting-notes chunk, or legacy mp3 speech. */
    val audio: String? = null,
    /** Legacy: which speech an audio packet or playing/done event belonged to. */
    val speech: Int? = null,
    @SerialName("callID") val callID: String? = null,
    val tool: String? = null,
    /** Free text: tool arguments or output, a minted secret, a caption, or JSON metadata. */
    val text: String? = null,
    /** Base64 JPEG that goes with a tool result. */
    val image: String? = null,
)

/**
 * Encodes and decodes packets. Strict in the same places the original Swift decoder is
 * strict (required face fields, known moods, exact types); callers drop anything that
 * fails to decode, keeping the connection alive.
 */
object PacketCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        // nulls have defaults → omitted on encode; missing required fields still throw.
    }

    /** Decodes one line (without the newline); null when the line is not a valid packet. */
    fun tryDecode(line: String): Packet? = try {
        json.decodeFromString(Packet.serializer(), line)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Encodes a packet as a single JSON line terminated by '\n'. */
    fun encode(packet: Packet): ByteArray =
        (json.encodeToString(Packet.serializer(), packet) + "\n").encodeToByteArray()

    /** Encodes a packet as a JSON string without the newline (for tests and logging). */
    fun encodeText(packet: Packet): String = json.encodeToString(Packet.serializer(), packet)
}
