using System.Text.Json;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>Semantic JSON comparison: numbers compare numerically, everything else exactly.</summary>
internal static class JsonSemantics
{
    public static void AssertEqual(string expectedJson, string actualJson)
    {
        using var expected = JsonDocument.Parse(expectedJson);
        using var actual = JsonDocument.Parse(actualJson);
        AssertEqual(expected.RootElement, actual.RootElement, "$");
    }

    public static void AssertEqual(JsonElement expected, JsonElement actual, string path)
    {
        if (expected.ValueKind != actual.ValueKind)
            Assert.Fail($"kind mismatch at {path}: {expected.ValueKind} vs {actual.ValueKind}");

        switch (expected.ValueKind)
        {
            case JsonValueKind.Object:
            {
                var expectedKeys = expected.EnumerateObject().Select(p => p.Name).OrderBy(k => k).ToList();
                var actualKeys = actual.EnumerateObject().Select(p => p.Name).OrderBy(k => k).ToList();
                if (!expectedKeys.SequenceEqual(actualKeys))
                    Assert.Fail($"keys mismatch at {path}: [{string.Join(",", expectedKeys)}] vs [{string.Join(",", actualKeys)}]");
                foreach (var key in expectedKeys)
                    AssertEqual(expected.GetProperty(key), actual.GetProperty(key), $"{path}.{key}");
                break;
            }
            case JsonValueKind.Array:
            {
                if (expected.GetArrayLength() != actual.GetArrayLength())
                    Assert.Fail($"array length mismatch at {path}");
                for (var i = 0; i < expected.GetArrayLength(); i++)
                    AssertEqual(expected[i], actual[i], $"{path}[{i}]");
                break;
            }
            case JsonValueKind.Number:
            {
                if (expected.GetDouble() != actual.GetDouble())
                    Assert.Fail($"number mismatch at {path}: {expected.GetDouble()} vs {actual.GetDouble()}");
                break;
            }
            case JsonValueKind.String:
            {
                if (expected.GetString() != actual.GetString())
                    Assert.Fail($"string mismatch at {path}: {expected.GetString()!} vs {actual.GetString()!}");
                break;
            }
            case JsonValueKind.True:
            case JsonValueKind.False:
            case JsonValueKind.Null:
                break;
            default:
                Assert.Fail($"unexpected kind at {path}");
                break;
        }
    }

    /// <summary>Parses a line back into a JsonElement for comparison (wraps the strict-parse duplication).</summary>
    public static JsonElement Parse(string json)
    {
        using var doc = JsonDocument.Parse(json);
        return doc.RootElement.Clone();
    }

    /// <summary>Enumerates the property names of a JSON object.</summary>
    public static IEnumerable<string> EnumeratePropertyNames(this JsonElement element)
    {
        foreach (var property in element.EnumerateObject())
            yield return property.Name;
    }
}
