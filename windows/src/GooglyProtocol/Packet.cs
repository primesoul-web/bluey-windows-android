using System.Text.Json;
using System.Text.Json.Serialization;

namespace GooglyProtocol;

/// <summary>
/// Everything the PC and the phone agree on: the Bonjour/mDNS service name.
/// Port of <c>GooglyService</c> in Shared/GooglyLink.swift.
/// </summary>
public static class GooglyService
{
    public const string Type = "_googly._tcp";
}

/// <summary>
/// What the phone's face should show. Enum raw values are the exact wire strings
/// (Swift rawValues are lowercase; <see cref="JsonStringEnumConverter{T}"/> emits them verbatim).
/// Port of <c>Mood</c> in Shared/GooglyLink.swift.
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter<Mood>))]
public enum Mood
{
    listening,
    resting,
    thinking,
    talking,
    pointing,
    happy,
    sleepy,
}

public static class MoodExtensions
{
    /// <summary>Capitalized display name, matching Swift's <c>Mood.title</c>.</summary>
    public static string Title(this Mood mood) => mood switch
    {
        Mood.listening => "Listening",
        Mood.resting => "Resting",
        Mood.thinking => "Thinking",
        Mood.talking => "Talking",
        Mood.pointing => "Pointing",
        Mood.happy => "Happy",
        Mood.sleepy => "Sleepy",
        _ => mood.ToString(),
    };
}

/// <summary>
/// What the phone should show right now. All four fields are required on the wire —
/// a face object missing any of them makes the whole packet invalid, exactly like
/// the original's synthesized Codable. Port of <c>FaceState</c> in Shared/GooglyLink.swift.
/// </summary>
public sealed class FaceState
{
    /// <summary>Where the eyes look. -1 left … 1 right.</summary>
    [JsonPropertyName("gazeX")]
    public required double GazeX { get; init; }

    /// <summary>Where the eyes look. -1 up … 1 down.</summary>
    [JsonPropertyName("gazeY")]
    public required double GazeY { get; init; }

    [JsonPropertyName("mood")]
    public required Mood Mood { get; init; }

    /// <summary>Voice level, 0…1. Drives the talking bounce.</summary>
    [JsonPropertyName("talk")]
    public required double Talk { get; init; }

    public static FaceState Create(double gazeX = 0, double gazeY = 0, Mood mood = Mood.listening, double talk = 0) =>
        new() { GazeX = gazeX, GazeY = gazeY, Mood = mood, Talk = talk };
}

/// <summary>
/// One newline-delimited JSON message. All fields optional; unset fields are omitted,
/// never null. Port of <c>Packet</c> in Shared/GooglyLink.swift (declaration order kept
/// so encodings match the original field-for-field).
/// </summary>
public sealed class Packet
{
    /// <summary>What the phone's face should show right now (PC → phone).</summary>
    [JsonPropertyName("face")]
    public FaceState? Face { get; init; }

    /// <summary>Sent once by each side after connecting: the sender's device name.</summary>
    [JsonPropertyName("hello")]
    public string? Hello { get; init; }

    /// <summary>Legacy (speech-audio era). Accepted, never emitted.</summary>
    [JsonPropertyName("volume")]
    public double? Volume { get; init; }

    /// <summary>A request, reply, or event (wake, sleep, tool, toolResult, …).</summary>
    [JsonPropertyName("command")]
    public string? Command { get; init; }

    /// <summary>Base64 audio: AAC meeting-notes chunk, or legacy mp3 speech.</summary>
    [JsonPropertyName("audio")]
    public string? Audio { get; init; }

    /// <summary>Legacy: which speech an audio packet or playing/done event belonged to.</summary>
    [JsonPropertyName("speech")]
    public int? Speech { get; init; }

    /// <summary>Pairs a request with its reply. Minted by the requester; echoed verbatim.</summary>
    [JsonPropertyName("callID")]
    public string? CallID { get; init; }

    /// <summary>Tool name for a "tool" request.</summary>
    [JsonPropertyName("tool")]
    public string? Tool { get; init; }

    /// <summary>Free text: tool arguments or output, a minted client secret, a caption, or JSON metadata.</summary>
    [JsonPropertyName("text")]
    public string? Text { get; init; }

    /// <summary>Base64 JPEG that goes with a tool result.</summary>
    [JsonPropertyName("image")]
    public string? Image { get; init; }
}

/// <summary>
/// Encodes and decodes packets. Decoding is deliberately strict in the same places the
/// original Swift decoder is strict (required face fields, known moods, exact types);
/// anything that fails to decode is dropped by the caller, keeping the connection alive.
/// </summary>
public static class PacketCodec
{
    private static readonly JsonSerializerOptions ReadOptions = new()
    {
        // Unknown JSON properties are ignored; `required` members are enforced;
        // type mismatches throw JsonException.
    };

    private static readonly JsonSerializerOptions WriteOptions = new()
    {
        // Matches Swift JSONEncoder: no HTML escaping, optional fields omitted.
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    /// <summary>Decodes one line (without the newline). Returns null when the line is not a valid packet.</summary>
    public static Packet? TryDecode(ReadOnlySpan<byte> line)
    {
        try
        {
            return JsonSerializer.Deserialize<Packet>(line, ReadOptions);
        }
        catch (JsonException)
        {
            return null;
        }
        catch (NotSupportedException)
        {
            return null;
        }
    }

    /// <summary>Decodes one line of UTF-8 text (without the newline). Returns null when invalid.</summary>
    public static Packet? TryDecode(string line)
    {
        try
        {
            return JsonSerializer.Deserialize<Packet>(line, ReadOptions);
        }
        catch (JsonException)
        {
            return null;
        }
        catch (NotSupportedException)
        {
            return null;
        }
    }

    /// <summary>Encodes a packet as a single JSON line terminated by '\n'.</summary>
    public static byte[] Encode(Packet packet)
    {
        var json = JsonSerializer.SerializeToUtf8Bytes(packet, WriteOptions);
        var line = new byte[json.Length + 1];
        json.CopyTo(line, 0);
        line[^1] = (byte)'\n';
        return line;
    }
}
