using System.IO;
using System.Windows.Threading;
using GooglyProtocol;
using GooglyRealtime;

namespace GooglyWindows;

/// <summary>
/// The PC's half of live voice. The phone runs the Gemini Live session (its mic, its
/// speaker); the PC hands it a short-lived token, runs its tools and shows captions.
/// Port of the M2 slice of <c>RealtimeHost.swift</c> — the cursor-driven parts (pointing
/// choreography, control pacing) land with M3/M4. Everything here runs on the UI thread,
/// alongside the tray it drives.
/// </summary>
public sealed class RealtimeHost
{
    /// <summary>Tools that act on the computer (the rest only look and point).</summary>
    private static readonly HashSet<string> ActionNames =
        new(StringComparer.Ordinal) { "click", "type_text", "press_keys", "scroll", "drag", "open_app", "open_url" };

    private readonly HostSettings _settings;
    private readonly ApiKeyStore _keys;
    private readonly RealtimeMinter _minter;
    private readonly CaptionWindow _captions;

    /// <summary>Tools run one at a time, in the order he asked for them.</summary>
    private Task _toolChain = Task.CompletedTask;

    /// <summary>Optional diagnostics sink (App wires it to %APPDATA%\Googly\log.txt).</summary>
    public Action<string>? Log { get; set; }

    private DispatcherTimer? _captionClear;
    private DispatcherTimer? _readingDone;

    /// <summary>True while he's awake and talking with you (false = follow mode).</summary>
    public bool Awake { get; private set; }

    public event Action? Changed;

    public RealtimeHost(HostSettings settings, ApiKeyStore keys, RealtimeMinter minter, CaptionWindow captions)
    {
        _settings = settings;
        _keys = keys;
        _minter = minter;
        _captions = captions;
    }

    /// <summary>Handles a request from the phone. <paramref name="reply"/> sends a packet back to that phone.</summary>
    public void Handle(Packet packet, Action<Packet> reply)
    {
        switch (packet.Command)
        {
            case "realtimeToken":
                MintFor(packet, reply);
                break;

            case "transcribe":
                // M5 (meeting notes): a recorded chunk. Failure-shaped reply until then,
                // exactly like the original's decode-failure path.
                reply(new Packet { Command = "transcribe", CallID = packet.CallID, Text = null });
                break;

            case "notes":
                // M5 (meeting notes): the phone's copy of a session.
                reply(new Packet { Command = "notes", CallID = packet.CallID, Text = null });
                break;

            case "awake":
                SetAwake(true);
                break;

            case "asleep":
                SetAwake(false);
                break;

            case "caption":
                // His reply, streaming in. An empty text hides the last bubble (he's being asked
                // something new). It shows beside his cursor from M3 on; above where the phone
                // sits until then.
                var text = (packet.Text ?? "").Trim();
                _readingDone?.Stop();
                _readingDone = null;
                ShowCaption(text.Length == 0 ? null : text, forSeconds: 0);
                if (text.Length > 0) Speaking = true;
                break;

            case "captionDone":
                // Keep the bubble (and his pointing) up long enough to read, then tidy away.
                var done = packet.Text ?? "";
                if (done.Length > 0) ShowCaption(done, forSeconds: 0);
                var words = (double)done.Split(' ', StringSplitOptions.RemoveEmptyEntries).Length;
                var readTime = Math.Clamp(2.8, 1.2 + words * 0.32, 12) + 3;
                _readingDone?.Stop();
                var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(readTime) };
                timer.Tick += (_, _) =>
                {
                    _readingDone = null;
                    timer.Stop();
                    Speaking = false;
                    ShowCaption(null, forSeconds: 0);
                };
                _readingDone = timer;
                timer.Start();
                break;

            case "tool":
                var previous = _toolChain;
                _toolChain = Task.Run(async () =>
                {
                    await previous.ConfigureAwait(false);
                    var result = await RunTool(packet.Tool ?? "", packet.Text ?? "{}").ConfigureAwait(false);
                    reply(new Packet { Command = "toolResult", CallID = packet.CallID, Text = result.Text, Image = result.Image });
                });
                break;
        }
    }

    /// <summary>True while a reply is up (drives pointing choreography from M3 on).</summary>
    public bool Speaking { get; private set; }

    private async void MintFor(Packet packet, Action<Packet> reply)
    {
        try
        {
            Log?.Invoke($"minting live token (model {_settings.RealtimeModel}, control {_settings.ComputerControl})");
            var (token, model) = await _minter.MintAsync(
                _keys.Get("gemini"), _settings.Personality, _settings.ComputerControl,
                _settings.RealtimeModel).ConfigureAwait(true);
            Log?.Invoke($"mint ok for model {model}");
            reply(new Packet { Command = "realtimeToken", CallID = packet.CallID, Text = token, Model = model });
        }
        catch (RealtimeMintException e)
        {
            Log?.Invoke($"mint failed: {e.Message}");
            // The phone shows the reason on its screen (error is a port addition);
            // the PC keeps its own caption too.
            reply(new Packet { Command = "realtimeToken", CallID = packet.CallID, Text = null, Error = e.Message });
            ShowCaption(e.Message, forSeconds: 6);
        }
    }

    public void SetAwake(bool on)
    {
        Awake = on;
        if (!on)
        {
            _readingDone?.Stop();
            _readingDone = null;
            Speaking = false;
            ShowCaption(null, forSeconds: 0);
        }
        Changed?.Invoke();
    }

    // MARK: Captions

    private void ShowCaption(string? text, double forSeconds)
    {
        _captionClear?.Stop();
        _captionClear = null;
        _captions.Show(text);
        if (text is not null && forSeconds > 0)
        {
            var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(forSeconds) };
            timer.Tick += (_, _) =>
            {
                _captionClear = null;
                timer.Stop();
                _captions.Show(null);
            };
            _captionClear = timer;
            timer.Start();
        }
    }

    // MARK: Tools (M2 stand-ins — see PROGRESS.md; M3/M4 bring the real ones)

    private Task<(string Text, string? Image)> RunTool(string name, string arguments) => Task.Run(() =>
    {
        if (ActionNames.Contains(name))
        {
            if (!_settings.ComputerControl)
            {
                return ("The user has turned off computer control in the tray menu. You can still look and point.", (string?)null);
            }
            return ($"That action ({name}) isn't wired up in this build yet — computer control arrives in the next milestone. Tell the user in a few words.", (string?)null);
        }
        return name switch
        {
            "look_at_screen" => ("I can't see the screen yet — screen viewing arrives in the next milestone. Tell the user in a few words.", (string?)null),
            "point_at" => ("Call look_at_screen first.", (string?)null),
            "point_at_spot" => ("I can't point at the screen yet — my on-screen cursor arrives in the next milestone.", (string?)null),
            "stop_pointing" => ("There's no cursor to send home in this build yet (the on-screen cursor arrives in the next milestone).", (string?)null),
            "web_research" => ("Web research isn't in this build yet — research cards arrive in the next milestone. Tell the user in a few words.", (string?)null),
            // The phone closes the session after this call; the PC just tidies up.
            "go_to_sleep" => ("Going to sleep. Say a very short goodbye.", (string?)null),
            _ => ($"Unknown tool {name}.", (string?)null),
        };
    });
}
