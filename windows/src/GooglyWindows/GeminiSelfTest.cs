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
/// "Test Gemini Connection" (port addition, diagnostics): runs the PC's whole half of a
/// live session with no phone involved, mapping exactly which leg is broken in one tray
/// click. Leg A mints with the stored key and the real spec, then performs the phone's
/// exact WSS handshake (Constrained endpoint + access_token + setup frame) and a text
/// turn. If it fails, leg B retries with his say-hi envelope (clientContent), leg C with
/// his exact hold-to-ask wire flow (a synthesized tone standing in for the mic), leg D
/// on the alternate endpoint spelling. Server closes are reported with their code and
/// reason — never swallowed (v0.3.0 turned a close frame into JsonReaderException ""
/// and hid the server's actual complaint). Every step lands in the log; the last state
/// shows as a caption.
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
    private DispatcherTimer? _clearTimer;

    private const string Ask = "Say hello in exactly five words.";
    private const string ActivityStart = """{"realtimeInput":{"activityStart":{}}}""";
    private const string ActivityEnd = """{"realtimeInput":{"activityEnd":{}}}""";

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
        Show("Gemini test: minting a token…", seconds: 50);
        Task.Run(async () =>
        {
            string report;
            try
            {
                report = await RunAsync(s => ShowFromBackground(s, 50)).ConfigureAwait(false);
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
                Show(done, seconds: 50);
            });
        });
    }

    /// <summary>The full diagnostic sequence. Returns a multi-line user-facing report.</summary>
    public async Task<string> RunAsync(Action<string>? progress = null)
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
        var minimal = false;
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
            // Fallback: the minimal body, to name the offending optional field.
            try
            {
                token = await MintMinimalAsync(key).ConfigureAwait(false);
                model = RealtimeSpec.ModelName(_settings.RealtimeModel);
                minimal = true;
                lines.Add($"mint with minimal body: OK — the full spec's optional fields are the problem");
            }
            catch (Exception e2)
            {
                lines.Add($"mint with minimal body: FAILED — {e2.Message}");
                return string.Join('\n', lines);
            }
        }

        // 2. Leg A — the docs' text envelope (what v0.3.0 sent; now the close reason
        //    survives instead of dying as JsonReaderException).
        progress?.Invoke("Gemini test: leg A — text turn");
        var legA = await TrySessionAsync(RealtimeSpec.ConstrainedEndpoint, token, model,
            "A, text turn (realtimeInput), primary endpoint",
            new[] { ActivityStart, TextTurnRealtime(Ask), ActivityEnd }, 0).ConfigureAwait(false);
        lines.AddRange(legA.Lines);
        if (legA.Ok) return string.Join('\n', lines);

        // Legs B–D each need their own token (minted tokens are single-use).

        // 3. Leg B — his say-hi envelope: a clientContent text turn.
        var freshB = await MintForLegAsync(key, minimal, lines).ConfigureAwait(false);
        if (freshB is null) return string.Join('\n', lines);
        progress?.Invoke("Gemini test: leg B — say-hi envelope");
        var legB = await TrySessionAsync(RealtimeSpec.ConstrainedEndpoint, freshB.Value.Token, freshB.Value.Model,
            "B, text turn (clientContent), primary endpoint",
            new[] { TextTurnClientContent(Ask) }, 0).ConfigureAwait(false);
        lines.AddRange(legB.Lines);
        if (legB.Ok) return string.Join('\n', lines);

        // 4. Leg C — his exact hold-to-ask wire flow: activityStart, two tone chunks in
        //    place of the mic, activityEnd.
        var freshC = await MintForLegAsync(key, minimal, lines).ConfigureAwait(false);
        if (freshC is null) return string.Join('\n', lines);
        progress?.Invoke("Gemini test: leg C — audio ask flow");
        var legC = await TrySessionAsync(RealtimeSpec.ConstrainedEndpoint, freshC.Value.Token, freshC.Value.Model,
            "C, audio turn (his ask flow), primary endpoint",
            new[]
            {
                ActivityStart,
                AudioFrame(ToneBase64(0.6, 440)),
                AudioFrame(ToneBase64(0.6, 587)),
                ActivityEnd,
            }, 150).ConfigureAwait(false);
        lines.AddRange(legC.Lines);
        if (legC.Ok) return string.Join('\n', lines);

        // 5. Leg D — the alternate endpoint spelling, in case the primary route itself
        //    is the problem.
        var freshD = await MintForLegAsync(key, minimal, lines).ConfigureAwait(false);
        if (freshD is null) return string.Join('\n', lines);
        progress?.Invoke("Gemini test: leg D — alternate endpoint");
        var legD = await TrySessionAsync(RealtimeSpec.ConstrainedEndpointAlternate, freshD.Value.Token, freshD.Value.Model,
            "D, text turn (clientContent), alternate endpoint",
            new[] { TextTurnClientContent(Ask) }, 0).ConfigureAwait(false);
        lines.AddRange(legD.Lines);
        if (!legD.Ok)
        {
            lines.Add("All four legs failed — copy this report into the chat with the log.");
        }
        return string.Join('\n', lines);
    }

    /// <summary>A fresh single-use token for a later leg — the full spec unless the
    /// full mint was the thing that failed (then the minimal body that worked).</summary>
    private async Task<(string Token, string Model)?> MintForLegAsync(string key, bool minimal, List<string> lines)
    {
        try
        {
            if (minimal)
                return (await MintMinimalAsync(key).ConfigureAwait(false),
                    RealtimeSpec.ModelName(_settings.RealtimeModel));
            return await _minter.MintAsync(
                key, _settings.Personality, _settings.ComputerControl, _settings.RealtimeModel)
                .ConfigureAwait(false);
        }
        catch (Exception e)
        {
            lines.Add($"  a later mint failed — {e.Message}");
            return null;
        }
    }

    private async Task<(bool Ok, List<string> Lines)> TrySessionAsync(
        string endpoint, string token, string model, string label, string[] turnFrames, int interleaveMs)
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

            var first = await ReceiveMessageAsync(ws, TimeSpan.FromSeconds(12)).ConfigureAwait(false);
            if (!first.Ok)
            {
                lines.Add($"  handshake FAILED — {first.CloseInfo}{FriendlyClose(first.CloseInfo)}");
                return (false, lines);
            }
            // The Live API delivers its JSON as BINARY frames (found on the wire
            // 2026-10-22) — a text-only reader sees "first frame was binary" and the
            // session looks dead while the server is talking the whole time. Decode
            // either frame type as UTF-8 and accept anything brace-flanked.
            if (!LiveWire.IsJsonObject(first.Json))
            {
                lines.Add($"  handshake FAILED — first frame wasn't JSON " +
                    $"({(first.Binary ? "binary" : "text")}, {first.Length} bytes): {first.Hex}");
                return (false, lines);
            }
            if (!first.Json.Contains("setupComplete"))
            {
                lines.Add($"  handshake FAILED — first frame: {Brief(first.Json)}");
                return (false, lines);
            }
            lines.Add($"  handshake OK (setupComplete{(first.Binary ? ", as a binary frame" : "")}): {Brief(first.Json)}");

            for (var i = 0; i < turnFrames.Length; i++)
            {
                if (i > 0 && interleaveMs > 0)
                    await Task.Delay(interleaveMs, session.Token).ConfigureAwait(false);
                await SendAsync(ws, turnFrames[i], session.Token).ConfigureAwait(false);
            }

            var sw = Stopwatch.StartNew();
            var said = "";
            string last = first.Json;
            var notedBinary = false;
            var notedBinaryJson = false;
            var notedEmpty = false;
            var notedNonJson = false;
            while (sw.Elapsed < TimeSpan.FromSeconds(20))
            {
                var remaining = TimeSpan.FromSeconds(20) - sw.Elapsed;
                var frame = await ReceiveMessageAsync(ws, remaining).ConfigureAwait(false);
                if (!frame.Ok)
                {
                    if (frame.CloseInfo.StartsWith("timeout", StringComparison.Ordinal))
                        lines.Add($"  no reply text within 20 s — last frame: {Brief(last)}");
                    else
                        lines.Add($"  {frame.CloseInfo}{FriendlyClose(frame.CloseInfo)}");
                    return (false, lines);
                }
                if (frame.Binary)
                {
                    // Binary frames carry the server's JSON (2026-10-22): decode and
                    // parse anything brace-flanked through the same path as text. The
                    // model's audio is binary too and fails the brace check — it drops
                    // (Bluey speaks in transcriptions), hex-noted once for the log.
                    if (!LiveWire.IsJsonObject(frame.Json))
                    {
                        if (!notedBinary)
                        {
                            notedBinary = true;
                            lines.Add($"  note: binary frames that aren't JSON — model audio (last {frame.Length} bytes): {frame.Hex}");
                        }
                        last = "(binary audio frame)";
                        continue;
                    }
                    if (!notedBinaryJson)
                    {
                        notedBinaryJson = true;
                        lines.Add($"  note: server JSON arriving as binary frames ({frame.Length} bytes) — decoded as UTF-8");
                    }
                    // fall through: parse it below like any text frame
                }
                if (string.IsNullOrWhiteSpace(frame.Json))
                {
                    if (!notedEmpty)
                    {
                        notedEmpty = true;
                        lines.Add("  note: empty text frames arriving — skipped");
                    }
                    last = "(empty frame)";
                    continue;
                }
                last = frame.Json;
                JsonNode? node;
                try
                {
                    node = JsonNode.Parse(frame.Json);
                }
                catch (System.Text.Json.JsonException)
                {
                    if (!notedNonJson)
                    {
                        notedNonJson = true;
                        lines.Add($"  note: non-JSON text frames arriving — {Brief(frame.Json)}");
                    }
                    continue;
                }
                var content = node?["serverContent"];
                var transcription = content?["outputTranscription"]?["text"]?.GetValue<string>();
                if (transcription is { Length: > 0 })
                    said += transcription;
                if (content?["turnComplete"]?.GetValue<bool>() == true)
                    break;
                if (frame.Json.Contains("toolCall"))
                {
                    lines.Add($"  session works — the model called a tool: {Brief(frame.Json)}");
                    return (true, lines);
                }
            }
            if (said.Length > 0)
            {
                lines.Add($"  turn OK — he said: \"{said}\"");
                return (true, lines);
            }
            lines.Add($"  no reply text within 20 s — last frame: {Brief(last)}");
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

    /// <summary>One whole message. Close frames are checked FIRST — a close always
    /// carries EndOfMessage=true and zero bytes, so v0.3.0's EndOfMessage-first loop
    /// turned them into an empty string that JsonNode.Parse choked on, hiding the
    /// server's actual close reason (the bug this version exists to fix). The payload
    /// is ALWAYS decoded as UTF-8, binary or text — the Live API sends its JSON as
    /// binary frames, so a binary message's bytes are the JSON text. The first bytes
    /// also ride along as hex so a non-JSON frame is shown, never discarded.</summary>
    private static async Task<Frame> ReceiveMessageAsync(ClientWebSocket ws, TimeSpan timeout)
    {
        using var cts = new CancellationTokenSource(timeout);
        var buffer = new byte[256 * 1024];
        var builder = new StringBuilder();
        var hex = new StringBuilder();
        var binary = false;
        var length = 0;
        try
        {
            while (true)
            {
                var result = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), cts.Token).ConfigureAwait(false);
                if (result.MessageType == WebSocketMessageType.Close)
                    return new Frame(false, "", CloseDescription(result), false, 0, "");
                if (result.MessageType == WebSocketMessageType.Binary) binary = true;
                length += result.Count;
                builder.Append(Encoding.UTF8.GetString(buffer, 0, result.Count));
                for (var i = 0; i < result.Count && hex.Length < 96; i++)
                    hex.Append(buffer[i].ToString("x2")).Append(' ');
                if (result.EndOfMessage) break;
            }
            return new Frame(true, builder.ToString(), "", binary, length, hex.ToString().TrimEnd());
        }
        catch (OperationCanceledException)
        {
            return new Frame(false, "", "timeout waiting for a frame", false, 0, "");
        }
    }

    /// <summary>The close frame's code and reason, spelled out — the server's actual
    /// complaint (v0.3.0 discarded this and crashed on the empty payload instead).</summary>
    private static string CloseDescription(WebSocketReceiveResult result)
    {
        var code = result.CloseStatus?.ToString() ?? "NormalClosure";
        var number = (int?)result.CloseStatus ?? 1000;
        var reason = result.CloseStatusDescription;
        return string.IsNullOrEmpty(reason)
            ? $"socket closed by server (close {number} {code})"
            : $"socket closed by server (close {number} {code}): {Truncate(reason, 140)}";
    }

    /// <summary>Turns the close reasons Google actually sends into the words the user
    /// needs (probe-verified 2026-10-22: the location close, the unregistered-callers
    /// close on the plain endpoint, the auth-token close).</summary>
    private static string FriendlyClose(string closeInfo)
    {
        if (closeInfo.Contains("location is not supported", StringComparison.OrdinalIgnoreCase))
            return " — this network's region isn't supported by the Live API; try a different network (another Wi-Fi, a hotspot or a VPN).";
        if (closeInfo.Contains("unregistered callers", StringComparison.OrdinalIgnoreCase))
            return " — that endpoint wants an API key, not a minted token.";
        if (closeInfo.Contains("auth token", StringComparison.OrdinalIgnoreCase))
            return " — the minted token wasn't accepted.";
        return "";
    }

    /// <summary>A short soft tone as little-endian PCM16 at 16 kHz, base64 — stands in
    /// for the mic in leg C (the phone's exact ask flow, minus a microphone).</summary>
    private static string ToneBase64(double seconds, double frequency, int rate = 16000)
    {
        var count = (int)(seconds * rate);
        var bytes = new byte[count * 2];
        for (var i = 0; i < count; i++)
        {
            var t = i / (double)rate;
            var fade = Math.Min(1.0, Math.Min(i / (rate * 0.02), (count - i) / (rate * 0.02)));
            var sample = (short)(6000 * fade * Math.Sin(2 * Math.PI * frequency * t));
            bytes[2 * i] = (byte)sample;
            bytes[2 * i + 1] = (byte)(sample >> 8);
        }
        return Convert.ToBase64String(bytes);
    }

    private static string TextTurnRealtime(string text) =>
        new JsonObject { ["realtimeInput"] = new JsonObject { ["text"] = text } }.ToJsonString();

    private static string TextTurnClientContent(string text) => new JsonObject
    {
        ["clientContent"] = new JsonObject
        {
            ["turns"] = new JsonArray(new JsonObject
            {
                ["role"] = "user",
                ["parts"] = new JsonArray(new JsonObject { ["text"] = text }),
            }),
            ["turnComplete"] = true,
        },
    }.ToJsonString();

    private static string AudioFrame(string base64Pcm16) => new JsonObject
    {
        ["realtimeInput"] = new JsonObject
        {
            ["audio"] = new JsonObject
            {
                ["data"] = base64Pcm16,
                ["mimeType"] = "audio/pcm;rate=16000",
            },
        },
    }.ToJsonString();

    private static string Brief(string json)
    {
        // Compact, safe preview of a server message for the caption/log.
        var trimmed = json.Trim();
        return trimmed.Length <= 160 ? trimmed : trimmed[..160] + "…";
    }

    private static string Truncate(string s, int max) =>
        string.IsNullOrEmpty(s) ? "" : (s.Length <= max ? s : s[..max] + "…");

    private void ShowFromBackground(string text, double seconds) =>
        _ = _ui.BeginInvoke(() => Show(text, seconds));

    private void Show(string text, double seconds)
    {
        _clearTimer?.Stop(); // a later step replaced the caption; its timer must not clear this one early
        _captions.Show(text);
        _clearTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(seconds) };
        _clearTimer.Tick += (_, _) =>
        {
            _clearTimer?.Stop();
            if (_running) return; // a later step replaced the caption; leave it be
            _captions.Show(null);
        };
        _clearTimer.Start();
    }

    /// <summary>One received WebSocket message, with the info the caller needs to
    /// survive every frame shape the server sends. [Hex] is the first bytes spelled
    /// out, for frames that aren't JSON — nothing the server sends is ever thrown
    /// away unnamed again.</summary>
    private readonly record struct Frame(bool Ok, string Json, string CloseInfo, bool Binary, int Length, string Hex);
}
