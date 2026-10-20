using System.Text.Json.Nodes;
using GooglyRealtime;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>
/// The minted session JSON is a wire contract with OpenAI AND with the original app:
/// shape, model, modalities, VAD knobs, tool list and prompt text must match
/// RealtimeHost.swift exactly (D7/D10 adaptations aside).
/// </summary>
public class RealtimeSpecTests
{
    [Fact]
    public void Instructions_ArePersonalityPlusToolGuidePlusComputerGuide()
    {
        var withControl = RealtimeSpec.Instructions(null, computerControl: true);
        Assert.StartsWith(RealtimeSpec.DefaultPersonality, withControl);
        Assert.Contains("\n\n" + RealtimeSpec.ToolGuide, withControl);
        Assert.EndsWith("\n\n" + RealtimeSpec.ComputerGuide, withControl);

        var withoutControl = RealtimeSpec.Instructions(null, computerControl: false);
        Assert.DoesNotContain(RealtimeSpec.ComputerGuide, withoutControl);
        Assert.EndsWith(RealtimeSpec.ToolGuide, withoutControl);

        var custom = RealtimeSpec.Instructions("  Grumpy plum.  ", computerControl: false);
        Assert.StartsWith("Grumpy plum.", custom); // trimmed saved personality wins
    }

    [Fact]
    public void Personality_FallsBackToDefaultWhenBlank()
    {
        Assert.Equal(RealtimeSpec.DefaultPersonality, RealtimeSpec.Personality(null));
        Assert.Equal(RealtimeSpec.DefaultPersonality, RealtimeSpec.Personality(""));
        Assert.Equal(RealtimeSpec.DefaultPersonality, RealtimeSpec.Personality("   \n "));
        Assert.Equal("Custom", RealtimeSpec.Personality("Custom"));
    }

    [Fact]
    public void DefaultPersonality_IsTheOriginalWithPhoneForIPhone()
    {
        // Verbatim from RealtimeHost.swift except D10: "an iPhone" → "a phone".
        Assert.StartsWith("You are a small blueberry with big googly eyes who lives on a phone", RealtimeSpec.DefaultPersonality);
        Assert.Contains("under fifteen words", RealtimeSpec.DefaultPersonality);
        Assert.DoesNotContain("iPhone", RealtimeSpec.DefaultPersonality);
        Assert.DoesNotContain("Mac", RealtimeSpec.DefaultPersonality);
    }

    [Fact]
    public void ToolGuide_HasTheExactRules()
    {
        Assert.StartsWith("How the conversation works:", RealtimeSpec.ToolGuide);
        Assert.Contains("holds the phone screen", RealtimeSpec.ToolGuide);
        Assert.Contains("call the tool FIRST with no words before it", RealtimeSpec.ToolGuide);
        Assert.Contains("ends with \"doing some research…\"", RealtimeSpec.ToolGuide);
        Assert.DoesNotContain("Mac", RealtimeSpec.ToolGuide);
    }

    [Fact]
    public void Session_HasTheExactShape()
    {
        var session = RealtimeSpec.Session(null, computerControl: true);

        Assert.Equal("realtime", (string?)session["type"]);
        Assert.Equal("gpt-realtime-2.1", (string?)session["model"]);
        Assert.Equal(RealtimeSpec.Instructions(null, true), (string?)session["instructions"]);

        var modalities = Assert.IsType<JsonArray>(session["output_modalities"]);
        Assert.Equal(["text"], modalities.Select(m => (string?)m));

        var audio = Assert.IsType<JsonObject>(session["audio"]);
        var input = Assert.IsType<JsonObject>(audio["input"]);
        var format = Assert.IsType<JsonObject>(input["format"]);
        Assert.Equal("audio/pcm", (string?)format["type"]);
        Assert.Equal(24000, (int?)format["rate"]);

        var vad = Assert.IsType<JsonObject>(input["turn_detection"]);
        Assert.Equal("server_vad", (string?)vad["type"]);
        Assert.Equal(0.5, (double?)vad["threshold"]);
        Assert.Equal(300, (int?)vad["prefix_padding_ms"]);
        Assert.Equal(500, (int?)vad["silence_duration_ms"]);
        Assert.Equal(false, (bool?)vad["create_response"]);
        Assert.Equal(false, (bool?)vad["interrupt_response"]);

        var noise = Assert.IsType<JsonObject>(input["noise_reduction"]);
        Assert.Equal("near_field", (string?)noise["type"]);

        var transcription = Assert.IsType<JsonObject>(input["transcription"]);
        Assert.Equal("gpt-4o-mini-transcribe", (string?)transcription["model"]);

        Assert.Equal("auto", (string?)session["tool_choice"]);
    }

    [Fact]
    public void Tools_SixWithoutControl_ThirteenWith()
    {
        var base_ = RealtimeSpec.Tools(computerControl: false).Select(t => (string?)t["name"]).ToArray();
        Assert.Equal(
            ["look_at_screen", "point_at", "point_at_spot", "web_research", "stop_pointing", "go_to_sleep"],
            base_);

        var full = RealtimeSpec.Tools(computerControl: true).Select(t => (string?)t["name"]).ToArray();
        Assert.Equal(
            ["look_at_screen", "point_at", "point_at_spot", "web_research", "stop_pointing", "go_to_sleep",
             "click", "type_text", "press_keys", "scroll", "drag", "open_app", "open_url"],
            full);
    }

    [Fact]
    public void Tools_CarryTheExactDescriptionsAndSchemas()
    {
        var tools = RealtimeSpec.Tools(computerControl: true);
        var byName = tools.ToDictionary(t => (string?)t["name"]);

        var look = byName["look_at_screen"];
        Assert.Equal("function", (string?)look["type"]);
        Assert.StartsWith("Take a fresh look at the user's screen.", (string?)look["description"]);
        Assert.Empty(Assert.IsType<JsonObject>(Assert.IsType<JsonObject>(look["parameters"])["properties"]));
        Assert.Empty(Assert.IsType<JsonArray>(Assert.IsType<JsonObject>(look["parameters"])["required"]));

        var point = byName["point_at"];
        var props = Assert.IsType<JsonObject>(Assert.IsType<JsonObject>(point["parameters"])["properties"]);
        Assert.Equal("An id from look_at_screen, like W12, L3 or C4.", (string?)props["target_id"]?["description"]);

        var spot = byName["point_at_spot"];
        var spotProps = Assert.IsType<JsonObject>(Assert.IsType<JsonObject>(spot["parameters"])["properties"]);
        Assert.Equal("0 = left edge, 1000 = right edge of the screen", (string?)spotProps["x"]?["description"]);
        Assert.Equal("0 = top edge, 1000 = bottom edge of the screen", (string?)spotProps["y"]?["description"]);

        // D10: Windows shortcut examples, no cmd.
        var keys = byName["press_keys"];
        Assert.Contains("ctrl+t", (string?)keys["description"]);
        Assert.DoesNotContain("cmd+", (string?)keys["description"]);

        // D10: Windows app examples.
        Assert.Contains("\"Chrome\"", (string?)byName["open_app"]?["description"]);

        var scroll = byName["scroll"];
        var scrollProps = Assert.IsType<JsonObject>(Assert.IsType<JsonObject>(scroll["parameters"])["properties"]);
        var direction = Assert.IsType<JsonObject>(scrollProps["direction"]);
        var allowed = Assert.IsType<JsonArray>(direction["enum"]);
        Assert.Equal(["up", "down", "left", "right"], allowed.Select(e => (string?)e));

        var click = byName["click"];
        var clickProps = Assert.IsType<JsonObject>(Assert.IsType<JsonObject>(click["parameters"])["properties"]);
        Assert.Equal("Double-click instead of a single click.", (string?)clickProps["double"]?["description"]);
        Assert.Equal("Right-click (for context menus).", (string?)clickProps["right"]?["description"]);
        Assert.Equal("An id from look_at_screen (C, L or W). Leave out to use x and y.", (string?)clickProps["target_id"]?["description"]);
    }

    [Fact]
    public void MintRequestBody_WrapsTheSessionInATenMinuteExpiry()
    {
        var body = RealtimeSpec.MintRequestBody(null, computerControl: false);
        var expires = Assert.IsType<JsonObject>(body["expires_after"]);
        Assert.Equal("created_at", (string?)expires["anchor"]);
        Assert.Equal(600, (int?)expires["seconds"]);

        var session = Assert.IsType<JsonObject>(body["session"]);
        Assert.Equal("gpt-realtime-2.1", (string?)session["model"]);
    }

    [Fact]
    public void ReportMarker_MatchesTheOriginal()
    {
        Assert.Equal("\n---REPORT---\n", RealtimeSpec.ReportMarker);
    }
}
