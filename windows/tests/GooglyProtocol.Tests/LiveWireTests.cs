using GooglyRealtime;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>
/// The Live API server delivers its JSON as BINARY WebSocket frames carrying UTF-8
/// (found on the wire 2026-10-22, after a month of "it's taking too long"). These
/// tests pin the one predicate every receiver — the PC self-test today, anything
/// else tomorrow — must agree on: brace-flanked text is JSON, everything else drops.
/// </summary>
public class LiveWireTests
{
    [Theory]
    [InlineData("""{"setupComplete":{}}""")]
    [InlineData("""{"serverContent":{"outputTranscription":{"text":"hi"}}}""")]
    [InlineData("  {\"goAway\":{\"timeLeft\":\"3s\"}}  ")]   // leading/trailing whitespace
    [InlineData("{\"a\":1}\n")]                              // trailing newline
    public void BraceFlankedText_IsJson(string frame)
        => Assert.True(LiveWire.IsJsonObject(frame));

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("{")]
    [InlineData("}")]
    [InlineData("{}x")]                                     // ends past the brace — not flanked
    [InlineData("[1,2,3]")]
    [InlineData("\"just a string\"")]
    [InlineData("json but not braced")]
    public void AnythingElse_IsNotJson(string frame)
        => Assert.False(LiveWire.IsJsonObject(frame));

    [Fact]
    public void Decoded_AudioNoise_IsNotJson()
    {
        // Rising-byte noise, the shape PCM16 audio takes on the wire.
        var noise = new string(Enumerable.Range(0, 512)
            .Select(i => (char)((i * 31 + 7) & 0xFF))
            .ToArray());
        Assert.False(LiveWire.IsJsonObject(noise));
    }

    [Fact]
    public void Audio_Aliasing_Braces_Still_Reaches_The_Parser_Safely()
    {
        // The one-in-many audio frame whose bytes happen to be brace-flanked passes
        // the check — by design, because JSON event parsers must already survive
        // garbage (they answer Ignored), and rejecting it would risk dropping real
        // JSON. This pins that the contract is "check, then parse defensively".
        var sneaky = "{zzzz}";
        Assert.True(LiveWire.IsJsonObject(sneaky));
    }
}
