using System.Text;
using System.Text.Json;
using GooglyProtocol;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>
/// Conformance against the fixtures in shared-protocol/fixtures — the same files the
/// Android tests and the CI schema validator consume. If all three agree, the two
/// platforms speak the same wire protocol as the original.
/// </summary>
public class FixtureTests
{
    public static IEnumerable<object[]> ValidFixtureFiles()
    {
        var dir = Path.Combine(AppContext.BaseDirectory, "fixtures");
        if (!Directory.Exists(dir))
            throw new InvalidOperationException($"fixtures directory missing: {dir}");
        return Directory.GetFiles(dir, "*.ndjson")
            .Where(f => !Path.GetFileName(f).StartsWith("07-invalid"))
            .OrderBy(f => Path.GetFileName(f), StringComparer.Ordinal)
            .Select(f => new object[] { Path.GetFileName(f) });
    }

    public static IEnumerable<string> LinesOf(string fileName)
    {
        var path = Path.Combine(AppContext.BaseDirectory, "fixtures", fileName);
        return File.ReadAllLines(path, Encoding.UTF8).Where(l => l.Trim().Length > 0);
    }

    [Theory]
    [MemberData(nameof(ValidFixtureFiles))]
    public void Every_valid_line_decodes(string fileName)
    {
        foreach (var line in LinesOf(fileName))
        {
            Assert.NotNull(PacketCodec.TryDecode(line));
        }
    }

    private static readonly string[] KnownFields =
        { "face", "hello", "volume", "command", "audio", "speech", "callID", "tool", "text", "image", "model" };

    private static readonly string[] KnownFaceFields =
        { "gazeX", "gazeY", "mood", "talk" };

    [Theory]
    [MemberData(nameof(ValidFixtureFiles))]
    public void Every_valid_line_round_trips_semantically(string fileName)
    {
        foreach (var line in LinesOf(fileName))
        {
            var packet = PacketCodec.TryDecode(line);
            Assert.NotNull(packet);
            var bytes = PacketCodec.Encode(packet!);
            var encoded = Encoding.UTF8.GetString(bytes, 0, bytes.Length - 1);

            using var original = JsonDocument.Parse(line);
            using var roundTripped = JsonDocument.Parse(encoded);

            // The re-encoding may drop unknown fields (they're ignored on read), but every
            // known field must survive with a semantically equal value, and nothing else
            // may appear.
            var outputKeys = roundTripped.RootElement.EnumeratePropertyNames().ToHashSet();
            Assert.Subset(new HashSet<string>(KnownFields), outputKeys);
            foreach (var property in original.RootElement.EnumerateObject())
            {
                if (!KnownFields.Contains(property.Name)) continue;
                Assert.True(roundTripped.RootElement.TryGetProperty(property.Name, out var value),
                    $"known field {property.Name} was lost in round-trip");
                if (property.Name == "face")
                {
                    Assert.Subset(new HashSet<string>(KnownFaceFields),
                        value.EnumeratePropertyNames().ToHashSet());
                    foreach (var faceProp in property.Value.EnumerateObject())
                    {
                        if (!KnownFaceFields.Contains(faceProp.Name)) continue;
                        Assert.True(value.TryGetProperty(faceProp.Name, out var faceValue),
                            $"face field {faceProp.Name} was lost in round-trip");
                        JsonSemantics.AssertEqual(faceProp.Value, faceValue, $"face.{faceProp.Name}");
                    }
                }
                else
                {
                    JsonSemantics.AssertEqual(property.Value, value, property.Name);
                }
            }
        }
    }

    [Fact]
    public void Every_invalid_line_fails_to_decode()
    {
        foreach (var line in LinesOf("07-invalid-lines.ndjson"))
        {
            Assert.Null(PacketCodec.TryDecode(line));
        }
    }

    [Fact]
    public void Fixture_suite_covers_every_packet_field()
    {
        var allText = string.Join("\n",
            Directory.GetFiles(Path.Combine(AppContext.BaseDirectory, "fixtures"), "*.ndjson")
                .Where(f => !Path.GetFileName(f).StartsWith("07-invalid"))
                .SelectMany(File.ReadAllLines));
        foreach (var field in new[] { "face", "hello", "volume", "command", "audio", "speech", "callID", "tool", "text", "image", "model" })
        {
            Assert.Contains($"\"{field}\":", allText);
        }
    }
}
