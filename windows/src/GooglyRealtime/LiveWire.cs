namespace GooglyRealtime;

/// <summary>
/// Wire-level facts about the Gemini Live API that every receiver of its WebSocket
/// frames must agree on. Kept in one place because both halves of the app learned
/// them the hard way, at the same time (2026-10-22): the session leg looks completely
/// dead — hold-to-ask times out after 20 seconds, the PC self-test reports "first
/// frame was binary (26 bytes)" — when the server is fine and talking the whole time.
/// </summary>
public static class LiveWire
{
    /// <summary>
    /// True when a received frame's bytes are a JSON object. The Live API server
    /// delivers its JSON (setupComplete, serverContent, toolCall, …) as BINARY
    /// WebSocket frames carrying UTF-8, not text frames — corroborated by other Live
    /// API integrations ("a client matching only text frames has setup accepted and
    /// then hears nothing"). Receivers therefore decode binary frames as UTF-8 and
    /// route them through the same JSON path as text frames. The model's audio also
    /// arrives as binary and fails this check — Bluey never plays it (his voice is
    /// the chirp), so it drops. Whitespace is tolerated because JSON payloads may
    /// carry trailing newlines; anything brace-flanked that isn't really JSON (an
    /// audio frame aliasing braces, odds ~1 in 2^16 per frame) is dropped safely by
    /// the parsers' catch-all paths.
    /// </summary>
    public static bool IsJsonObject(ReadOnlySpan<char> text)
    {
        var trimmed = text.Trim();
        return trimmed.Length >= 2 && trimmed[0] == '{' && trimmed[^1] == '}';
    }
}
