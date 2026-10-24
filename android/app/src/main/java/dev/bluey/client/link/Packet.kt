package dev.bluey.client.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Everything the phone and the PC agree on: the mDNS service name. Port of GooglyService in Shared/GooglyLink.swift. */
object GooglyService {
    const val TYPE = "_googly._tcp"

    /** Port addition (D12): the port the Windows PC listens on. The original's
     * Bonjour always resolved NWListener's ephemeral ports; Android's NsdManager
     * can't see across a USB-tethering link, so a well-known port is what makes
     * typed addresses and `adb reverse` work. */
    const val DEFAULT_PORT = 51826

    /** The TXT marker our PC advertises (v0.3.5): `go=pc`. The other Bluey fork
     * on this Wi-Fi advertises under the same service NAME without it, so a
     * resolved instance carrying the marker is definitely ours. See
     * [ImpostorPolicy] — the greeting check remains the hard guarantee. */
    const val MARKER_KEY = "go"
    const val MARKER_VALUE = "pc"
}

/** A PC address typed by hand — "192.168.42.196" or "192.168.42.196:51826".
 * For networks where mDNS doesn't reach (USB tethering, strict Wi-Fi). */
data class ManualHost(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"

    companion object {
        /** Parses a typed address; null when it can't be understood (empty clears separately). */
        fun parse(address: String): ManualHost? {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) return null
            var host = trimmed
            var port = GooglyService.DEFAULT_PORT
            val colon = trimmed.lastIndexOf(':')
            if (colon > 0) {
                host = trimmed.substring(0, colon).trim()
                val typed = trimmed.substring(colon + 1).trim().toIntOrNull() ?: return null
                if (typed !in 1..65535) return null
                port = typed
            }
            if (host.isEmpty() || host.contains(':')) return null // no bare IPv6 — bracket forms only
            return ManualHost(host, port)
        }
    }
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
    /** Port addition: on a realtimeToken reply, the Live model the secret was minted
     * for, so the phone can address its setup message. Older peers ignore it. */
    val model: String? = null,
    /** Port addition: why a request failed, shown to the user instead of silence
     * (e.g. a realtimeToken reply with no token carries the mint error here).
     * Older peers ignore it. */
    val error: String? = null,
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
