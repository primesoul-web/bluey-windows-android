using System.Net;
using GooglyRealtime;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>
/// The minter talks to POST /v1/realtime/client_secrets with the PC's key and hands
/// back the client secret's "value". These tests drive it against a fake transport.
/// </summary>
public class MinterTests
{
    private sealed class FakeHandler : HttpMessageHandler
    {
        public HttpRequestMessage? LastRequest;
        public string? LastBody;
        public HttpStatusCode Status = HttpStatusCode.OK;
        public string Response = """{"value":"secret123"}""";

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken ct)
        {
            LastRequest = request;
            LastBody = request.Content is null ? null
                : await request.Content.ReadAsStringAsync(ct);
            return new HttpResponseMessage(Status) { Content = new StringContent(Response) };
        }
    }

    [Fact]
    public async Task Mints_WithKeyAuthorizationAndTheSessionBody()
    {
        var handler = new FakeHandler();
        var minter = new RealtimeMinter(handler);

        var secret = await minter.MintAsync("sk-real-key", null, computerControl: false);

        Assert.Equal("secret123", secret);
        Assert.Equal(HttpMethod.Post, handler.LastRequest!.Method);
        Assert.Equal(RealtimeMinter.Endpoint, handler.LastRequest.RequestUri!.ToString());
        Assert.Equal("Bearer sk-real-key",
            handler.LastRequest.Headers.GetValues("Authorization").Single());
        Assert.Contains("\"seconds\":600", handler.LastBody);
        Assert.Contains("gpt-realtime-2.1", handler.LastBody);
    }

    [Fact]
    public async Task NoKey_ThrowsTheExactCaption()
    {
        var minter = new RealtimeMinter(new FakeHandler());
        var e = await Assert.ThrowsAsync<RealtimeMintException>(
            () => minter.MintAsync(null, null, computerControl: false));
        Assert.Equal("I need an OpenAI API key. Add one under OpenAI Key in the tray menu.", e.Message);

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
            () => minter.MintAsync("sk-bad", null, computerControl: true));

        Assert.StartsWith("OpenAI wouldn't start a voice session: 401 ", e.Message);
        // "401 " + body, capped at 160 characters of the "why".
        Assert.True(e.Message.Length <=
            "OpenAI wouldn't start a voice session: ".Length + 160);
    }

    [Fact]
    public async Task SuccessWithoutAValue_Throws()
    {
        var handler = new FakeHandler { Response = """{"id":"rt_1"}""" };
        var minter = new RealtimeMinter(handler);
        await Assert.ThrowsAsync<RealtimeMintException>(
            () => minter.MintAsync("sk-real-key", null, computerControl: false));
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
    }

    [Fact]
    public void Values_PersistAcrossInstances()
    {
        using (var settings = new HostSettings(_path))
        {
            settings.ComputerControl = false;
            settings.CursorSize = 96;
            settings.Personality = "A friendly goose.";
        }
        using (var reloaded = new HostSettings(_path))
        {
            Assert.False(reloaded.ComputerControl);
            Assert.Equal(96.0, reloaded.CursorSize);
            Assert.Equal("A friendly goose.", reloaded.Personality);
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

public class OpenAiKeyStoreTests : IDisposable
{
    private readonly string _path = Path.Combine(
        Path.GetTempPath(), $"googly-keys-{Guid.NewGuid():N}.json");

    [Fact]
    public void RoundTrips_OnWindows()
    {
        if (!OperatingSystem.IsWindows()) return; // DPAPI is Windows-only
        var store = new OpenAiKeyStore(_path);
        Assert.Null(store.Get("openai"));

        store.Set("openai", "sk-test-key-123");
        Assert.Equal("sk-test-key-123", store.Get("openai"));

        // The file on disk never contains the raw key.
        var raw = File.ReadAllText(_path);
        Assert.DoesNotContain("sk-test-key-123", raw);

        // A fresh store reads it back (cache cleared by new instance).
        var fresh = new OpenAiKeyStore(_path);
        Assert.Equal("sk-test-key-123", fresh.Get("openai"));

        // Clearing removes it.
        store.Set("openai", "");
        Assert.Null(store.Get("openai"));
        Assert.DoesNotContain("openai", File.ReadAllText(_path));
    }

    [Fact]
    public void UnreadableValue_BehavesLikeMissing()
    {
        if (!OperatingSystem.IsWindows()) return;
        File.WriteAllText(_path, """{"openai":"not-base64!!"}""");
        var store = new OpenAiKeyStore(_path);
        Assert.Null(store.Get("openai"));
    }

    public void Dispose()
    {
        try { File.Delete(_path); } catch { /* temp cleanup */ }
    }
}
