using System.Text.Json.Nodes;
using GooglyRealtime;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>
/// The minted token's locked session config is a wire contract with the Gemini Live API
/// AND a behavior contract with the original app: model, modalities, manual VAD,
/// transcriptions, tool list and prompt text must match RealtimeHost.swift's intent
/// (D7/D10 adaptations, D11 provider switch aside).
/// </summary>
public class RealtimeSpecTests
{
    private static readonly DateTime FixedNow = new(2026, 10, 21, 12, 0, 0, DateTimeKind.Utc);

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
    public void ModelName_FallsBackToTheDefaultLiveModel()
    {
        Assert.Equal("gemini-3.8-live", RealtimeSpec.ModelName(null));
        Assert.Equal("gemini-3.8-live", RealtimeSpec.ModelName("  "));
        Assert.Equal("gemini-3.1-flash-live-preview", RealtimeSpec.ModelName("gemini-3.1-flash-live-preview"));
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
    public void Setup_HasTheExactShape()
    {
        var setup = RealtimeSpec.Setup(null, computerControl: true, model: null);

        // The setup names the model — required, and what the token locks the session to.
        Assert.Equal("models/gemini-3.8-live", (string?)setup["model"]);

        // He never talks out loud: the model answers in audio and the phone shows the
        // output transcription as his bubble (native-audio models can't answer text-only).
        // On the wire, modalities and voice are generationConfig fields.
        var generation = Assert.IsType<JsonObject>(setup["generationConfig"]);
        var modalities = Assert.IsType<JsonArray>(generation["responseModalities"]);
        Assert.Equal(["AUDIO"], modalities.Select(m => (string?)m));

        var speech = Assert.IsType<JsonObject>(Assert.IsType<JsonObject>(generation["speechConfig"])["voiceConfig"]);
        Assert.Equal(RealtimeSpec.Voice,
            (string?)Assert.IsType<JsonObject>(speech["prebuiltVoiceConfig"])["voiceName"]);

        // The system instruction is the original's instructions, verbatim.
        var parts = Assert.IsType<JsonArray>(Assert.IsType<JsonObject>(setup["systemInstruction"])["parts"]);
        Assert.Equal(RealtimeSpec.Instructions(null, true), (string?)parts[0]?["text"]);

        // Manual activity detection: the server never answers on its own — the phone
        // signals activity around a held question (the create_response:false stand-in).
        var vad = Assert.IsType<JsonObject>(
            Assert.IsType<JsonObject>(setup["realtimeInputConfig"])["automaticActivityDetection"]);
        Assert.Equal(true, (bool?)vad["disabled"]);

        // Both transcriptions feed the saved session transcript.
        Assert.NotNull(setup["inputAudioTranscription"]);
        Assert.NotNull(setup["outputAudioTranscription"]);

        // All-day sessions stay inside the context window.
        var compression = Assert.IsType<JsonObject>(setup["contextWindowCompression"]);
        Assert.NotNull(compression["slidingWindow"]);
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
        Assert.StartsWith("Take a fresh look at the user's screen.", (string?)look["description"]);
        // Blocking keeps the original's tool-first flow: he waits for the PC's result.
        Assert.Equal("BLOCKING", (string?)look["behavior"]);
        var lookParams = Assert.IsType<JsonObject>(look["parameters"]);
        Assert.Equal("OBJECT", (string?)lookParams["type"]);
        Assert.Empty(Assert.IsType<JsonObject>(lookParams["properties"]));
        Assert.Empty(Assert.IsType<JsonArray>(lookParams["required"]));

        var point = byName["point_at"];
        var props = Assert.IsType<JsonObject>(Assert.IsType<JsonObject>(point["parameters"])["properties"]);
        Assert.Equal("An id from look_at_screen, like W12, L3 or C4.", (string?)props["target_id"]?["description"]);
        Assert.Equal("STRING", (string?)props["target_id"]?["type"]);

        var spot = byName["point_at_spot"];
        var spotProps = Assert.IsType<JsonObject>(Assert.IsType<JsonObject>(spot["parameters"])["properties"]);
        Assert.Equal("0 = left edge, 1000 = right edge of the screen", (string?)spotProps["x"]?["description"]);
        Assert.Equal("0 = top edge, 1000 = bottom edge of the screen", (string?)spotProps["y"]?["description"]);
        Assert.Equal("NUMBER", (string?)spotProps["x"]?["type"]);

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
    public void MintRequestBody_IsAFlatAuthTokenWithTheBakedSetup()
    {
        var body = RealtimeSpec.MintRequestBody(null, computerControl: false, null, FixedNow);

        // The original's 600 s secret TTL: ten minutes to start a session…
        Assert.Equal("2026-10-21T12:10:00Z", (string?)body["newSessionExpireTime"]);
        // …and the documented 30-minute default for messages on the connection.
        Assert.Equal("2026-10-21T12:30:00Z", (string?)body["expireTime"]);

        // The deployed REST shape (probe-verified): a flat AuthToken whose
        // bidiGenerateContentSetup carries the model and the whole session. The docs'
        // liveConnectConstraints wrapper is rejected by the live endpoint, and "uses"
        // is a union sibling of the setup, so neither may appear here.
        var setup = Assert.IsType<JsonObject>(body["bidiGenerateContentSetup"]);
        Assert.Equal("models/gemini-3.8-live", (string?)setup["model"]);
        Assert.Null(body["liveConnectConstraints"]);
        Assert.Null(body["uses"]);
        Assert.Equal(RealtimeSpec.Instructions(null, false),
            (string?)((JsonArray?)setup["systemInstruction"]?["parts"])?[0]?["text"]);
    }

    [Fact]
    public void ReportMarker_MatchesTheOriginal()
    {
        Assert.Equal("\n---REPORT---\n", RealtimeSpec.ReportMarker);
    }

    [Fact]
    public void ConstrainedEndpoint_MatchesThePhonesTransport()
    {
        // The phone's LiveVoiceTransport.kt builds this exact URL; the PC's self-test
        // must exercise the identical leg, byte for byte.
        Assert.Equal(
            "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained",
            RealtimeSpec.ConstrainedEndpoint);
        Assert.StartsWith("wss://generativelanguage.googleapis.com/", RealtimeSpec.ConstrainedEndpointAlternate);
        Assert.EndsWith("/constrained", RealtimeSpec.ConstrainedEndpointAlternate);
        Assert.NotEqual(RealtimeSpec.ConstrainedEndpoint, RealtimeSpec.ConstrainedEndpointAlternate);
    }

    [Fact]
    public void MinimalMintBody_IsAFlatAuthTokenWithTheEssentialsOnly()
    {
        // Port addition (diagnostics): the self-test's fallback mint carries the model,
        // AUDIO modality, the voice and manual activity — and nothing the full spec
        // could have gotten wrong (no tools, no transcriptions, no compression).
        var body = RealtimeSpec.MintRequestBodyMinimal(null, FixedNow);
        Assert.Equal("2026-10-21T12:30:00Z", (string?)body["expireTime"]);
        Assert.Equal("2026-10-21T12:10:00Z", (string?)body["newSessionExpireTime"]);
        var setup = Assert.IsType<JsonObject>(body["bidiGenerateContentSetup"]);
        Assert.Equal("models/gemini-3.8-live", (string?)setup["model"]);
        Assert.Equal("AUDIO", (string?)((JsonArray?)setup["generationConfig"]?["responseModalities"])?[0]);
        Assert.Equal(
            (string?)RealtimeSpec.Setup(null, false, null)?["generationConfig"]?["speechConfig"]?["voiceConfig"]?["prebuiltVoiceConfig"]?["voiceName"],
            (string?)setup["generationConfig"]?["speechConfig"]?["voiceConfig"]?["prebuiltVoiceConfig"]?["voiceName"]);
        Assert.Equal(true, (bool?)setup["realtimeInputConfig"]?["automaticActivityDetection"]?["disabled"]);
        Assert.Null(setup["tools"]);
        Assert.Null(setup["systemInstruction"]);
        Assert.Null(setup["contextWindowCompression"]);
    }
}
