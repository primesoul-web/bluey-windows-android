using System.Net;
using GooglyRealtime;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>
/// The minter talks to POST /v1beta/auth_tokens with the PC's AI Studio key and hands
/// back the token's "name". These tests drive it against a fake transport.
/// </summary>
public class MinterTests
{
    private sealed class FakeHandler : HttpMessageHandler
    {
        public HttpRequestMessage? LastRequest;
        public string? LastBody;
        public HttpStatusCode Status = HttpStatusCode.OK;
        public string Response = """{"name":"auth_tokens/abc123","expireTime":"2026-10-21T12:30:00Z"}""";

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken ct)
        {
            LastRequest = request;
            LastBody = request.Content is null ? null
                : await request.Content.ReadAsStringAsync(ct);
            return new HttpResponseMessage(Status) { Content = new StringContent(Response) };
        }
    }

    private static readonly DateTime FixedNow = new(2026, 10, 21, 12, 0, 0, DateTimeKind.Utc);

    [Fact]
    public async Task Mints_WithKeyHeaderAndTheLockedSessionBody()
    {
        var handler = new FakeHandler();
        var minter = new RealtimeMinter(handler, () => FixedNow);

        var (token, model) = await minter.MintAsync("AIza-real-key", null, computerControl: false);

        Assert.Equal("auth_tokens/abc123", token);
        Assert.Equal("gemini-3.8-live", model);
        Assert.Equal(HttpMethod.Post, handler.LastRequest!.Method);
        Assert.Equal(RealtimeMinter.Endpoint, handler.LastRequest.RequestUri!.ToString());
        Assert.Equal("AIza-real-key",
            handler.LastRequest.Headers.GetValues("x-goog-api-key").Single());
        Assert.Contains("\"uses\":1", handler.LastBody);
        Assert.Contains("\"newSessionExpireTime\":\"2026-10-21T12:10:00Z\"", handler.LastBody);
        Assert.Contains("\"model\":\"models/gemini-3.8-live\"", handler.LastBody);
        Assert.Contains("\"functionDeclarations\"", handler.LastBody);
        Assert.Contains("\"automaticActivityDetection\":{\"disabled\":true}", handler.LastBody);
    }

    [Fact]
    public async Task NoKey_ThrowsTheExactCaption()
    {
        var minter = new RealtimeMinter(new FakeHandler());
        var e = await Assert.ThrowsAsync<RealtimeMintException>(
            () => minter.MintAsync(null, null, computerControl: false));
        Assert.Equal("I need a Gemini API key. Add one under Gemini API Key in the tray menu.", e.Message);

        var e2 = await Assert.ThrowsAsync<RealtimeMintException>(
            () => minter.MintAsync("", null, computerControl: false));
        Assert.Equal(RealtimeMinter.TokenError.NoKey, e2.Message);
    }

    [Fact]
    public async Task HttpError_ThrowsWithCodeBodyAndThe160CharacterCap()
    {
        var handler = new FakeHandler
        {
            Status = HttpStatusCode.Unauthorized,
            Response = "{\"error\":{\"message\":\"" + new string('x', 400) + "\"}}",
        };
        var minter = new RealtimeMinter(handler);

        var e = await Assert.ThrowsAsync<RealtimeMintException>(
            () => minter.MintAsync("AIza-bad", null, computerControl: true));

        Assert.StartsWith("Gemini wouldn't start a voice session: 401 ", e.Message);
        // "401 " + body, capped at 160 characters of the "why".
        Assert.True(e.Message.Length <=
            "Gemini wouldn't start a voice session: ".Length + 160);
    }

    [Fact]
    public async Task SuccessWithoutAName_Throws()
    {
        var handler = new FakeHandler { Response = """{"expireTime":"2026-10-21T12:30:00Z"}""" };
        var minter = new RealtimeMinter(handler);
        await Assert.ThrowsAsync<RealtimeMintException>(
            () => minter.MintAsync("AIza-real-key", null, computerControl: false));
    }

    [Fact]
    public async Task CustomModel_IsMintedAndReported()
    {
        var handler = new FakeHandler();
        var minter = new RealtimeMinter(handler, () => FixedNow);

        var (_, model) = await minter.MintAsync(
            "AIza-real-key", null, computerControl: false, "gemini-3.1-flash-live-preview");

        Assert.Equal("gemini-3.1-flash-live-preview", model);
        Assert.Contains("\"model\":\"models/gemini-3.1-flash-live-preview\"", handler.LastBody);
    }
}

/// <summary>
/// Settings keep the original's keys and defaults; DPAPI key storage round-trips
/// (Windows only — the Linux build skips that half).
/// </summary>
public class HostSettingsTests : IDisposable
{
    private readonly string _path = Path.Combine(
        Path.GetTempPath(), $"googly-settings-{Guid.NewGuid():N}.json");

    [Fact]
    public void Defaults_MatchTheOriginal()
    {
        using var settings = new HostSettings(_path);
        Assert.Equal(72.0, settings.CursorSize);
        Assert.False(settings.Glow);
        Assert.True(settings.ShowCursor);
        Assert.Equal(0.5, settings.PhonePosition);
        Assert.True(settings.FollowMouse);
        Assert.True(settings.Captions);
        Assert.True(settings.ComputerControl);
        Assert.Equal("comet", settings.Trail);
        Assert.Equal("listening", settings.Mood);
        Assert.Equal("", settings.Personality);
        Assert.Equal("gemini-3.8-live", settings.RealtimeModel);
    }

    [Fact]
    public void Values_PersistAcrossInstances()
    {
        using (var settings = new HostSettings(_path))
        {
            settings.ComputerControl = false;
            settings.CursorSize = 96;
            settings.Personality = "A friendly goose.";
            settings.RealtimeModel = "gemini-3.1-flash-live-preview";
        }
        using (var reloaded = new HostSettings(_path))
        {
            Assert.False(reloaded.ComputerControl);
            Assert.Equal(96.0, reloaded.CursorSize);
            Assert.Equal("A friendly goose.", reloaded.Personality);
            Assert.Equal("gemini-3.1-flash-live-preview", reloaded.RealtimeModel);
        }
    }

    [Fact]
    public void CorruptOrWrongTypedValues_FallBackToDefaults()
    {
        File.WriteAllText(_path, """{"cursorSize":"huge","glow":"yes","mood":7}""");
        using var settings = new HostSettings(_path);
        Assert.Equal(72.0, settings.CursorSize);
        Assert.False(settings.Glow);
        Assert.Equal("listening", settings.Mood);

        File.WriteAllText(_path, "not json at all");
        using var again = new HostSettings(_path);
        Assert.Equal(0.5, again.PhonePosition);
    }

    [Fact]
    public void ChangeEvent_FiresWithTheNewValue()
    {
        using var settings = new HostSettings(_path);
        var fired = 0;
        settings.Changed += () => fired++;
        settings.Glow = true;
        Assert.Equal(1, fired);
        settings.Glow = true; // same value still notifies, like the original
        Assert.Equal(2, fired);
    }

    public void Dispose()
    {
        try { File.Delete(_path); } catch { /* temp cleanup */ }
    }
}

public class ApiKeyStoreTests : IDisposable
{
    private readonly string _path = Path.Combine(
        Path.GetTempPath(), $"googly-keys-{Guid.NewGuid():N}.json");

    [Fact]
    public void RoundTrips_OnWindows()
    {
        if (!OperatingSystem.IsWindows()) return; // DPAPI is Windows-only
        var store = new ApiKeyStore(_path);
        Assert.Null(store.Get("gemini"));

        store.Set("gemini", "AIza-test-key-123");
        Assert.Equal("AIza-test-key-123", store.Get("gemini"));

        // The file on disk never contains the raw key.
        var raw = File.ReadAllText(_path);
        Assert.DoesNotContain("AIza-test-key-123", raw);

        // A fresh store reads it back (cache cleared by new instance).
        var fresh = new ApiKeyStore(_path);
        Assert.Equal("AIza-test-key-123", fresh.Get("gemini"));

        // Clearing removes it.
        store.Set("gemini", "");
        Assert.Null(store.Get("gemini"));
        Assert.DoesNotContain("gemini", File.ReadAllText(_path));
    }

    [Fact]
    public void UnreadableValue_BehavesLikeMissing()
    {
        if (!OperatingSystem.IsWindows()) return;
        File.WriteAllText(_path, """{"gemini":"not-base64!!"}""");
        var store = new ApiKeyStore(_path);
        Assert.Null(store.Get("gemini"));
    }

    public void Dispose()
    {
        try { File.Delete(_path); } catch { /* temp cleanup */ }
    }
}
