using System.Text;
using GooglyProtocol;
using Xunit;

namespace GooglyProtocol.Tests;

public class PacketCodecTests
{
    private static string EncodeText(Packet p)
    {
        var bytes = PacketCodec.Encode(p);
        Assert.Equal((byte)'\n', bytes[^1]);
        Assert.DoesNotContain((byte)'\n', bytes[..^1]);
        return Encoding.UTF8.GetString(bytes, 0, bytes.Length - 1);
    }

    [Fact]
    public void Hello_round_trips()
    {
        var packet = PacketCodec.TryDecode("{\"hello\":\"Studio PC\"}");
        Assert.NotNull(packet);
        Assert.Equal("Studio PC", packet!.Hello);
        Assert.Null(packet.Command);
        var encoded = EncodeText(packet);
        JsonSemantics.AssertEqual("{\"hello\":\"Studio PC\"}", encoded);
    }

    [Fact]
    public void Unset_fields_are_omitted_never_null()
    {
        var encoded = EncodeText(new Packet { Hello = "PC" });
        Assert.Equal("{\"hello\":\"PC\"}", encoded);
        Assert.DoesNotContain("null", encoded);
    }

    [Fact]
    public void Face_round_trips_with_all_fields()
    {
        var packet = PacketCodec.TryDecode(
            "{\"face\":{\"gazeX\":0.42,\"gazeY\":-0.31,\"mood\":\"pointing\",\"talk\":0}}");
        Assert.NotNull(packet);
        var face = packet!.Face!;
        Assert.Equal(0.42, face.GazeX);
        Assert.Equal(-0.31, face.GazeY);
        Assert.Equal(Mood.pointing, face.Mood);
        Assert.Equal(0, face.Talk);
        JsonSemantics.AssertEqual(
            "{\"face\":{\"gazeX\":0.42,\"gazeY\":-0.31,\"mood\":\"pointing\",\"talk\":0}}",
            EncodeText(packet));
    }

    [Fact]
    public void All_seven_moods_round_trip()
    {
        foreach (var mood in new[] { "listening", "resting", "thinking", "talking", "pointing", "happy", "sleepy" })
        {
            var packet = PacketCodec.TryDecode($"{{\"face\":{{\"gazeX\":0,\"gazeY\":0,\"mood\":\"{mood}\",\"talk\":0}}}}");
            Assert.NotNull(packet);
            Assert.Equal(mood, ((Mood)Enum.Parse(typeof(Mood), mood)).ToString());
            Assert.Contains($"\"mood\":\"{mood}\"", EncodeText(packet!));
        }
    }

    [Fact]
    public void Integer_literals_decode_into_double_fields()
    {
        var packet = PacketCodec.TryDecode("{\"volume\":1}");
        Assert.NotNull(packet);
        Assert.Equal(1.0, packet!.Volume);
        var face = PacketCodec.TryDecode("{\"face\":{\"gazeX\":0,\"gazeY\":1,\"mood\":\"happy\",\"talk\":1}}");
        Assert.NotNull(face);
        Assert.Equal(1.0, face!.Face!.GazeY);
        Assert.Equal(1.0, face.Face.Talk);
    }

    [Fact]
    public void CallID_keeps_its_exact_casing()
    {
        var packet = PacketCodec.TryDecode("{\"command\":\"tool\",\"callID\":\"ABC-123\",\"tool\":\"click\",\"text\":\"{}\"}");
        Assert.NotNull(packet);
        Assert.Equal("ABC-123", packet!.CallID);
        var encoded = EncodeText(packet);
        Assert.Contains("\"callID\":\"ABC-123\"", encoded);
        Assert.DoesNotContain("callId", encoded);
    }

    [Fact]
    public void Model_rides_along_on_realtimeToken_replies()
    {
        // Port addition (wire rule 5): the PC tells the phone which Live model to address.
        var packet = PacketCodec.TryDecode(
            "{\"command\":\"realtimeToken\",\"callID\":\"U1\",\"text\":\"auth_tokens/xyz\",\"model\":\"gemini-3.8-live\"}");
        Assert.NotNull(packet);
        Assert.Equal("gemini-3.8-live", packet!.Model);
        JsonSemantics.AssertEqual(
            "{\"command\":\"realtimeToken\",\"callID\":\"U1\",\"text\":\"auth_tokens/xyz\",\"model\":\"gemini-3.8-live\"}",
            EncodeText(packet));

        // Absent on failure replies and unknown to the original — simply unset.
        var failure = PacketCodec.TryDecode("{\"command\":\"realtimeToken\",\"callID\":\"U2\"}");
        Assert.NotNull(failure);
        Assert.Null(failure!.Model);
        Assert.DoesNotContain("model", EncodeText(failure));
    }

    [Fact]
    public void Error_carries_the_reason_on_a_failed_realtimeToken_reply()
    {
        // Port addition (wire rule 5): why a mint failed, shown instead of silence.
        var packet = PacketCodec.TryDecode(
            "{\"command\":\"realtimeToken\",\"callID\":\"U3\",\"error\":\"Gemini wouldn't start a voice session: 401 API key not valid.\"}");
        Assert.NotNull(packet);
        Assert.Equal("Gemini wouldn't start a voice session: 401 API key not valid.", packet!.Error);
        JsonSemantics.AssertEqual(
            "{\"command\":\"realtimeToken\",\"callID\":\"U3\",\"error\":\"Gemini wouldn't start a voice session: 401 API key not valid.\"}",
            EncodeText(packet));

        // Absent on successful replies — simply unset.
        var ok = PacketCodec.TryDecode("{\"command\":\"realtimeToken\",\"callID\":\"U4\",\"text\":\"auth_tokens/xyz\"}");
        Assert.NotNull(ok);
        Assert.Null(ok!.Error);
        Assert.DoesNotContain("error", EncodeText(ok));
    }

    [Fact]
    public void Unknown_fields_are_ignored()
    {
        var packet = PacketCodec.TryDecode("{\"hello\":\"PC\",\"futureField\":42,\"nested\":{\"a\":[1,2,3]}}");
        Assert.NotNull(packet);
        Assert.Equal("PC", packet!.Hello);
        JsonSemantics.AssertEqual("{\"hello\":\"PC\"}", EncodeText(packet));
    }

    [Fact]
    public void Legacy_fields_decode_and_re_encode()
    {
        var packet = PacketCodec.TryDecode("{\"volume\":0.75,\"speech\":3,\"command\":\"playing\"}");
        Assert.NotNull(packet);
        Assert.Equal(0.75, packet!.Volume);
        Assert.Equal(3, packet.Speech);
        Assert.Equal("playing", packet.Command);
        JsonSemantics.AssertEqual("{\"volume\":0.75,\"speech\":3,\"command\":\"playing\"}", EncodeText(packet));
    }

    [Fact]
    public void Escaping_matches_the_original_no_html_escapes()
    {
        const string text = "café 中文 <b>&</b> \"quotes\" back\\slash";
        var packet = PacketCodec.TryDecode($"{{\"command\":\"caption\",\"text\":{System.Text.Json.JsonSerializer.Serialize(text)}}}");
        Assert.NotNull(packet);
        Assert.Equal(text, packet!.Text);
        var encoded = EncodeText(packet);
        Assert.DoesNotContain("\\u003c", encoded);
        Assert.DoesNotContain("\\u0026", encoded);
        Assert.Contains("café", encoded);
    }

    [Fact]
    public void Field_order_matches_the_original_declaration()
    {
        var packet = new Packet
        {
            Face = FaceState.Create(0, 0, Mood.listening, 0),
            Hello = "PC",
            Volume = 0.5,
            Command = "tool",
            Audio = "AAAA",
            Speech = 1,
            CallID = "X",
            Tool = "click",
            Text = "hi",
            Image = "aGk=",
            Model = "gemini-3.8-live",
        };
        var encoded = EncodeText(packet);
        var keys = new List<string>();
        using (var doc = System.Text.Json.JsonDocument.Parse(encoded))
            keys.AddRange(doc.RootElement.EnumerateObject().Select(p => p.Name));
        Assert.Equal(
            new[] { "face", "hello", "volume", "command", "audio", "speech", "callID", "tool", "text", "image", "model" },
            keys);
    }

    [Theory]
    [InlineData("not json at all")]
    [InlineData("")]
    [InlineData("{\"hello\":123}")]
    [InlineData("{\"face\":{\"gazeX\":\"left\",\"gazeY\":0,\"mood\":\"listening\",\"talk\":0}}")]
    [InlineData("{\"face\":{\"gazeX\":0,\"gazeY\":0,\"mood\":\"excited\",\"talk\":0}}")]
    [InlineData("{\"face\":{\"gazeY\":0,\"mood\":\"listening\",\"talk\":0}}")]       // missing gazeX
    [InlineData("{\"face\":{\"gazeX\":0,\"gazeY\":0,\"mood\":\"listening\"}}")]      // missing talk
    [InlineData("{\"face\":{\"gazeX\":0,\"gazeY\":0}}")]                             // missing mood+talk
    [InlineData("{\"command\":42}")]
    [InlineData("{\"speech\":\"three\"}")]
    [InlineData("{\"volume\":\"loud\"}")]
    [InlineData("{\"face\":\"sleepy\"}")]
    [InlineData("[]")]
    [InlineData("\"just a string\"")]
    public void Invalid_lines_are_rejected(string line)
    {
        Assert.Null(PacketCodec.TryDecode(line));
    }

    [Fact]
    public void Null_valued_fields_decode_as_unset()
    {
        // The original Swift decoder treats JSON null as nil for optional fields,
        // and the schema forbids emitting them — we accept null on read, omit on write.
        var packet = PacketCodec.TryDecode("{\"hello\":null,\"command\":\"wake\"}");
        Assert.NotNull(packet);
        Assert.Null(packet!.Hello);
        Assert.Equal("wake", packet.Command);
    }
}
