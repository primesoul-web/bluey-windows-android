using System.Net.Http;
using System.Text;
using System.Text.Json.Nodes;

namespace GooglyRealtime;

/// <summary>
/// Mints short-lived ephemeral Live tokens for the phone, so the real Gemini API key
/// (from Google AI Studio) never leaves the PC. Port of <c>RealtimeHost.mintToken()</c>:
/// POST /v1beta/auth_tokens with a 600-second new-session window (the original's
/// 600 s secret TTL) and the full session config locked into the token.
/// </summary>
public sealed class RealtimeMinter
{
    public const string Endpoint = "https://generativelanguage.googleapis.com/v1beta/auth_tokens";

    /// <summary>How long a mint may take before giving up with a clear message
    /// (port addition: HttpClient's 100 s default meant an unreachable Google could
    /// leave the phone smiling in "Waking" forever).</summary>
    public TimeSpan Timeout { get; } = TimeSpan.FromSeconds(15);

    private readonly HttpClient _client;
    private readonly Func<DateTime> _clock;

    public RealtimeMinter(HttpMessageHandler? handler = null, Func<DateTime>? clock = null,
        TimeSpan? timeout = null)
    {
        _client = handler is null ? new HttpClient() : new HttpClient(handler);
        Timeout = timeout ?? TimeSpan.FromSeconds(15);
        _client.Timeout = Timeout;
        _clock = clock ?? (() => DateTime.UtcNow);
    }

    /// <summary>Why a mint failed, with the exact user-facing wording (shown as a caption
    /// on the PC and, via the error wire field, on the phone).</summary>
    public static class TokenError
    {
        public const string NoKey =
            "I need a Gemini API key. Add one under Gemini API Key in the tray menu.";

        public static string Failed(string why) =>
            "Gemini wouldn't start a voice session: " + Truncate(why, 200);

        public static string TimeoutExceeded =>
            "Gemini wouldn't start a voice session: this PC couldn't reach Google in 15 seconds — check the internet connection.";

        private static string Truncate(string s, int max) =>
            s.Length <= max ? s : s[..max];
    }

    /// <summary>
    /// Mints an ephemeral token. Throws <see cref="RealtimeMintException"/> with the exact
    /// caption-worthy message from <see cref="TokenError"/> on failure. Returns the token
    /// itself (the response's <c>name</c>) plus the model it was minted for.
    /// </summary>
    public async Task<(string Token, string Model)> MintAsync(
        string? apiKey, string? savedPersonality, bool computerControl,
        string? model = null, CancellationToken ct = default)
    {
        if (string.IsNullOrEmpty(apiKey)) throw new RealtimeMintException(TokenError.NoKey);

        var modelName = RealtimeSpec.ModelName(model);
        var body = RealtimeSpec.MintRequestBody(savedPersonality, computerControl, modelName, _clock());
        using var request = new HttpRequestMessage(HttpMethod.Post, Endpoint);
        request.Headers.TryAddWithoutValidation("x-goog-api-key", apiKey);
        request.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");

        HttpResponseMessage response;
        try
        {
            response = await _client.SendAsync(request, ct).ConfigureAwait(false);
        }
        catch (TaskCanceledException) // the 15-second Timeout elapsed
        {
            throw new RealtimeMintException(TokenError.TimeoutExceeded);
        }
        catch (HttpRequestException e)
        {
            throw new RealtimeMintException(TokenError.Failed(e.Message));
        }
        using (response)
        {
            var text = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            string? value = null;
            try
            {
                value = JsonNode.Parse(text)?["name"]?.GetValue<string>();
            }
            catch (Exception e) when (e is InvalidOperationException or System.Text.Json.JsonException)
            {
                // not a string / wrong shape / not JSON at all (an HTML proxy page, say)
                // → treated as a missing token below
            }
            if (!response.IsSuccessStatusCode || string.IsNullOrEmpty(value))
            {
                throw new RealtimeMintException(TokenError.Failed(
                    Explain((int)response.StatusCode, text)));
            }
            return (value!, modelName);
        }
    }

    /// <summary>Turns a rejected mint into the words the user needs to act on — a bad
    /// key says “re-copy it”, a blocked region says “try another network”, anything
    /// else shows the raw status and body (port addition).</summary>
    private static string Explain(int status, string body)
    {
        if (status is 401 or 403)
        {
            return $"HTTP {status}: that Gemini API key was rejected. Re-copy it from " +
                "aistudio.google.com/api-keys (Copy key) and paste it under Gemini API Key in the tray menu.";
        }
        var trimmed = body.Trim();
        if (trimmed.Contains("location", StringComparison.OrdinalIgnoreCase) ||
            trimmed.Contains("FAILED_PRECONDITION", StringComparison.Ordinal))
        {
            return $"HTTP {status}: this network's region isn't supported by the Gemini API — " +
                "try a different network (Wi-Fi, another hotspot, or a VPN).";
        }
        return $"{status} {trimmed}";
    }
}

/// <summary>Carries the user-facing message for a failed mint (the original uses LocalizedError).</summary>
public sealed class RealtimeMintException(string message) : Exception(message);
