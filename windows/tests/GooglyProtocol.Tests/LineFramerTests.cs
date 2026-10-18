using System.Text;
using GooglyProtocol;
using Xunit;

namespace GooglyProtocol.Tests;

public class LineFramerTests
{
    private static Packet? One(string json) => PacketCodec.TryDecode(json);

    [Fact]
    public void Multiple_lines_in_one_chunk_all_decode()
    {
        var framer = new LineFramer();
        var chunk = Encoding.UTF8.GetBytes("{\"hello\":\"A\"}\n{\"hello\":\"B\"}\n{\"command\":\"wake\"}\n");
        var packets = framer.Feed(chunk);
        Assert.Equal(3, packets.Count);
        Assert.Equal("A", packets[0].Hello);
        Assert.Equal("B", packets[1].Hello);
        Assert.Equal("wake", packets[2].Command);
    }

    [Fact]
    public void Partial_lines_accumulate_until_the_newline()
    {
        var framer = new LineFramer();
        Assert.Empty(framer.Feed(Encoding.UTF8.GetBytes("{\"hel")));
        Assert.Empty(framer.Feed(Encoding.UTF8.GetBytes("lo\":\"Pixel")));
        var packets = framer.Feed(Encoding.UTF8.GetBytes(" 9\"}\n"));
        var packet = Assert.Single(packets);
        Assert.Equal("Pixel 9", packet.Hello);
    }

    [Fact]
    public void A_line_without_trailing_newline_is_held()
    {
        var framer = new LineFramer();
        // The complete first line decodes; the partial second line is held back.
        var first = framer.Feed(Encoding.UTF8.GetBytes("{\"hello\":\"A\"}\n{\"hello\":\"B\"}"));
        var packetA = Assert.Single(first);
        Assert.Equal("A", packetA.Hello);
        // Feeding the rest plus another packet completes it.
        var second = framer.Feed(Encoding.UTF8.GetBytes("\n{\"command\":\"sleep\"}\n"));
        Assert.Equal(2, second.Count);
        Assert.Equal("B", second[0].Hello);
        Assert.Equal("sleep", second[1].Command);
    }

    [Fact]
    public void Invalid_lines_are_dropped_but_valid_ones_survive()
    {
        var framer = new LineFramer();
        var chunk = Encoding.UTF8.GetBytes(
            "garbage\n" +
            "{\"hello\":\"A\"}\n" +
            "\n" + // empty line is also invalid
            "{\"face\":{\"gazeX\":0,\"gazeY\":0,\"mood\":\"excited\",\"talk\":0}}\n" +
            "{\"command\":\"wake\"}\n");
        var packets = framer.Feed(chunk);
        Assert.Equal(2, packets.Count);
        Assert.Equal("A", packets[0].Hello);
        Assert.Equal("wake", packets[1].Command);
    }

    [Fact]
    public void Large_audio_packets_survive_fragmentation()
    {
        var payload = new string('A', 300_000); // ~300 KB of base64, like an AAC chunk
        var framer = new LineFramer();
        var line = Encoding.UTF8.GetBytes($"{{\"audio\":\"{payload}\",\"speech\":1}}\n");
        Assert.Empty(framer.Feed(line.AsSpan(0, 100_000).ToArray()));
        Assert.Empty(framer.Feed(line.AsSpan(100_000, 100_000).ToArray()));
        var packets = framer.Feed(line.AsSpan(200_000, line.Length - 200_000).ToArray());
        var packet = Assert.Single(packets);
        Assert.Equal(300_000, packet.Audio!.Length);
        Assert.Equal(1, packet.Speech);
    }

    [Fact]
    public void A_packet_may_arrive_byte_by_byte()
    {
        var framer = new LineFramer();
        var bytes = Encoding.UTF8.GetBytes("{\"face\":{\"gazeX\":0.5,\"gazeY\":0,\"mood\":\"happy\",\"talk\":0}}\n");
        IReadOnlyList<Packet> received = Array.Empty<Packet>();
        foreach (var b in bytes)
            received = framer.Feed(new[] { b });
        var packet = Assert.Single(received);
        Assert.Equal(Mood.happy, packet.Face!.Mood);
        Assert.Equal(0.5, packet.Face.GazeX);
    }
}
