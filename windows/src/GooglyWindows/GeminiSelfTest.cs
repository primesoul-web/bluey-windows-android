using System.Diagnostics;
using System.Net.Http;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json.Nodes;
using System.Windows.Threading;
using GooglyProtocol;
using GooglyRealtime;

namespace GooglyWindows;

/// <summary>
/// "Test Gemini Connection" (port addition, diagnostics): runs the PC's half of a live
/// session with no phone involved — mint with the stored key and the real spec, then the
/// exact WSS handshake the phone performs (Constrained endpoint + access_token + the
/// setup frame), then a manual-activity text turn to confirm output transcription
/// actually streams back. Every step lands in the log; the last state shows as a caption.
/// Fallbacks (minimal mint body, alternate endpoint spelling) run only after the real
/// path fails, so one user round trip names the broken leg.
/// </summary>
public sealed class GeminiSelfTest
{
    private readonly HostSettings _settings;
    private readonly ApiKeyStore _keys;
    private readonly RealtimeMinter _minter;
    private readonly CaptionWindow _captions;
    private readonly Action<string> _log;
    private readonly System.Windows.Threading.Dispatcher _ui;

    private bool _running;

    public GeminiSelfTest(HostSettings settings, ApiKeyStore keys, RealtimeMinter minter,
        CaptionWindow captions, Action<string> log)
    {
        _settings = settings;
        _keys = keys;
        _minter = minter;
        _captions = captions;
        _log = log;
        _ui = System.Windows.Threading.Dispatcher.CurrentDispatcher; // constructed on the UI thread
    }

    /// <summary>Called on the UI thread from the tray menu. Fire-and-forget.</summary>
    public void RunFromTray()
    {
        if (_running)
        {
            Show("Gemini test: already running — one moment.", seconds: 6);
            return;
        }
        _running = true;
        Show("Gemini test: minting a token…", seconds: 45);
        Task.Run(async () =>
        {
            string report;
            try
            {
                report = await RunAsync().ConfigureAwait(false);
            }
            catch (Exception e)
            {
                report = $"Gemini test crashed: {e.GetType().Name}: {e.Message}";
            }
            _log("gemini self-test: " + report.Replace('\n', ' '));
            var done = report;
            _ = _ui.BeginInvoke(() =>
            {
                _running = false;
                Show(done, seconds: 45);
            });
        });
    }

    /// <summary>The full diagnostic sequence. Returns a multi-line user-facing report.</summary>
    public async Task<string> RunAsync()
    {
        var lines = new List<string>();
        var key = _keys.Get("gemini");
        if (string.IsNullOrEmpty(key))
        {
            lines.Add("No Gemini API key is stored — add one under Gemini API Key… in the tray menu.");
            return string.Join('\n', lines);
        }
        lines.Add($"key stored: yes ({key.Length} chars)");

        // 1. The real mint path, exactly what a phone request triggers.
        var sw = Stopwatch.StartNew();
        string token;
        string model;
        try
        {
            (token, model) = await _minter.MintAsync(
                key, _settings.Personality, _settings.ComputerControl, _settings.RealtimeModel)
                .ConfigureAwait(false);
            sw.Stop();
            lines.Add($"mint with full session spec: OK ({sw.ElapsedMilliseconds} ms, model {model})");
        }
        catch (RealtimeMintException e)
        {
            sw.Stop();
            lines.Add($"mint with full session spec: FAILED ({sw.ElapsedMilliseconds} ms)");
            lines.Add("  " + e.Message);
            // Fallback A: the minimal body, to name the offending optional field.
            try
            {
                var minimal = await MintMinimalAsync(key).ConfigureAwait(false);
                lines.Add($"mint with minimal body: OK — the full spec's optional fields are the problem");
                token = minimal;
                model = RealtimeSpec.ModelName(_settings.RealtimeModel);
            }
            catch (Exception e2)
            {
                lines.Add($"mint with minimal body: FAILED — {e2.Message}");
                return string.Join('\n', lines);
            }
        }

        // 2. The phone's exact session leg: Constrained endpoint + access_token + setup frame.
        var session = await TrySessionAsync(RealtimeSpec.ConstrainedEndpoint, token, model, "primary endpoint").ConfigureAwait(false);
        lines.AddRange(session.Lines);
        if (session.Ok) return string.Join('\n', lines);

        var alternate = await TrySessionAsync(RealtimeSpec.ConstrainedEndpointAlternate, token, model, "alternate endpoint").ConfigureAwait(false);
        lines.AddRange(alternate.Lines);
        if (!alternate.Ok)
        {
            lines.Add("Both endpoint spellings failed — copy this report into the chat with the log.");
        }
        return string.Join('\n', lines);
    }

    private async Task<(bool Ok, List<string> Lines)> TrySessionAsync(
        string endpoint, string token, string model, string label)
    {
        var lines = new List<string> { $"session leg, {label}: connecting…" };
        try
        {
            using var ws = new ClientWebSocket();
            using var session = new CancellationTokenSource(TimeSpan.FromSeconds(75));
            await ws.ConnectAsync(
                new Uri(endpoint + "?access_token=" + Uri.EscapeDataString(token)), session.Token)
                .ConfigureAwait(false);

            // The phone's exact first frame: the setup naming the model.
            var setup = new JsonObject
            {
                ["setup"] = new JsonObject { ["model"] = "models/" + model },
            };
            await SendAsync(ws, setup.ToString(), session.Token).ConfigureAwait(false);

            var (first, _) = await ReceiveMessageAsync(ws, TimeSpan.FromSeconds(12)).ConfigureAwait(false);
            var brief = Brief(first);
            if (!first.Contains("setupComplete"))
            {
                lines.Add($"  handshake FAILED — first message: {brief}");
                return (false, lines);
            }
            lines.Add($"  handshake OK (setupComplete): {brief}");

            // The phone's exact ask flow: activityStart + text + activityEnd, then the
            // output transcription should stream back as his bubble text.
            await SendAsync(ws, """{"realtimeInput":{"activityStart":{}}}""", session.Token).ConfigureAwait(false);
            await SendAsync(ws, """{"realtimeInput":{"text":"Say hello in exactly five words."}}""",
                session.Token).ConfigureAwait(false);
            await SendAsync(ws, """{"realtimeInput":{"activityEnd":{}}}""", session.Token).ConfigureAwait(false);

            var sw = Stopwatch.StartNew();
            var said = "";
            string last = first;
            while (sw.Elapsed < TimeSpan.FromSeconds(20))
            {
                var remaining = TimeSpan.FromSeconds(20) - sw.Elapsed;
                var (message, received) = await ReceiveMessageAsync(ws, remaining).ConfigureAwait(false);
                if (!received) break;
                last = message;
                var node = JsonNode.Parse(message);
                var content = node?["serverContent"];
                var transcription = content?["outputTranscription"]?["text"]?.GetValue<string>();
                if (transcription is { Length: > 0 })
                    said += transcription;
                if (content?["turnComplete"]?.GetValue<bool>() == true)
                    break;
                if (message.Contains("toolCall"))
                {
                    lines.Add($"  session works — the model called a tool: {Brief(message)}");
                    return (true, lines);
                }
            }
            if (said.Length > 0)
            {
                lines.Add($"  turn OK — he said: \"{said}\"");
                return (true, lines);
            }
            lines.Add($"  no reply text within 20 s — last message: {Brief(last)}");
            return (false, lines);
        }
        catch (Exception e)
        {
            lines.Add($"  FAILED — {e.GetType().Name}: {Truncate(e.Message, 160)}");
            return (false, lines);
        }
    }

    /// <summary>The minimal-spec mint (diagnostics only) — its own POST so the shipped
    /// minter's behavior stays untouched.</summary>
    private async Task<string> MintMinimalAsync(string key)
    {
        var body = RealtimeSpec.MintRequestBodyMinimal(_settings.RealtimeModel, DateTime.UtcNow);
        using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        using var request = new HttpRequestMessage(HttpMethod.Post, RealtimeMinter.Endpoint);
        request.Headers.TryAddWithoutValidation("x-goog-api-key", key);
        request.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
        using var response = await client.SendAsync(request).ConfigureAwait(false);
        var text = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
        var value = JsonNode.Parse(text)?["name"]?.GetValue<string>();
        if (!response.IsSuccessStatusCode || string.IsNullOrEmpty(value))
            throw new RealtimeMintException($"HTTP {(int)response.StatusCode}: {Truncate(text.Trim(), 160)}");
        return value!;
    }

    private static async Task SendAsync(ClientWebSocket ws, string json, CancellationToken ct)
    {
        var bytes = Encoding.UTF8.GetBytes(json);
        await ws.SendAsync(bytes, WebSocketMessageType.Text, true, ct).ConfigureAwait(false);
    }

    /// <summary>Reads one whole text message; (json, received=false) on timeout.</summary>
    private static async Task<(string Json, bool Received)> ReceiveMessageAsync(
        ClientWebSocket ws, TimeSpan timeout)
    {
        using var cts = new CancellationTokenSource(timeout);
        var buffer = new byte[256 * 1024];
        var builder = new StringBuilder();
        try
        {
            while (true)
            {
                var result = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), cts.Token).ConfigureAwait(false);
                builder.Append(Encoding.UTF8.GetString(buffer, 0, result.Count));
                if (result.EndOfMessage) break;
                if (result.MessageType == WebSocketMessageType.Close)
                    return ("(socket closed by server)", false);
            }
            return (builder.ToString(), true);
        }
        catch (OperationCanceledException)
        {
            return ("", false);
        }
    }

    private static string Brief(string json)
    {
        // Compact, safe preview of a server message for the caption/log.
        var trimmed = json.Trim();
        return trimmed.Length <= 160 ? trimmed : trimmed[..160] + "…";
    }

    private static string Truncate(string s, int max) =>
        string.IsNullOrEmpty(s) ? "" : (s.Length <= max ? s : s[..max] + "…");

    private void Show(string text, double seconds)
    {
        _captions.Show(text);
        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(seconds) };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            if (_running) return; // a later step replaced the caption; leave it be
            _captions.Show(null);
        };
        timer.Start();
    }
}
