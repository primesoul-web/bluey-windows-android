using System.Net.Http;
using System.Text;
using System.Text.Json.Nodes;

namespace GooglyRealtime;

/// <summary>
/// Mints short-lived Realtime client secrets for the phone, so the real OpenAI key never
/// leaves the PC. Port of <c>RealtimeHost.mintToken()</c>: POST
/// /v1/realtime/client_secrets with a 600-second expiry and the full session config.
/// </summary>
public sealed class RealtimeMinter
{
    public const string Endpoint = "https://api.openai.com/v1/realtime/client_secrets";

    private readonly HttpClient _client;

    public RealtimeMinter(HttpMessageHandler? handler = null)
    {
        _client = handler is null ? new HttpClient() : new HttpClient(handler);
    }

    /// <summary>Why a mint failed, with the exact user-facing wording (shown as a caption).</summary>
    public static class TokenError
    {
        public const string NoKey =
            "I need an OpenAI API key. Add one under OpenAI Key in the tray menu.";

        public static string Failed(string why) =>
            "OpenAI wouldn't start a voice session: " + Truncate(why, 160);

        private static string Truncate(string s, int max) =>
            s.Length <= max ? s : s[..max];
    }

    /// <summary>
    /// Mints a client secret. Throws <see cref="RealtimeMintException"/> with the exact
    /// caption-worthy message from <see cref="TokenError"/> on failure.
    /// </summary>
    public async Task<string> MintAsync(
        string? apiKey, string? savedPersonality, bool computerControl, CancellationToken ct = default)
    {
        if (string.IsNullOrEmpty(apiKey)) throw new RealtimeMintException(TokenError.NoKey);

        var body = RealtimeSpec.MintRequestBody(savedPersonality, computerControl);
        using var request = new HttpRequestMessage(HttpMethod.Post, Endpoint);
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + apiKey);
        request.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");

        HttpResponseMessage response;
        try
        {
            response = await _client.SendAsync(request, ct).ConfigureAwait(false);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException
            and not OperationCanceledException)
        {
            throw new RealtimeMintException(TokenError.Failed(e.Message));
        }
        using (response)
        {
            var text = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                throw new RealtimeMintException(TokenError.Failed($"{(int)response.StatusCode} {text}"));
            }
            var json = JsonNode.Parse(text);
            var value = json?["value"]?.GetValue<string>();
            if (string.IsNullOrEmpty(value))
            {
                throw new RealtimeMintException(TokenError.Failed($"{(int)response.StatusCode} {text}"));
            }
            return value!;
        }
    }
}

/// <summary>Carries the user-facing message for a failed mint (the original uses LocalizedError).</summary>
public sealed class RealtimeMintException(string message) : Exception(message);
