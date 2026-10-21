using System.IO;
using System.Windows;
using GooglyProtocol;
using GooglyRealtime;

namespace GooglyWindows;

/// <summary>
/// The tray application — the port of GooglyMac's AppDelegate. Wires the phone server,
/// the mDNS advertiser, the face driver, the tray icon, the wake/sleep hotkey
/// (Ctrl+Alt+Space, the Mac ⌥Space stand-in per PARITY.md D1) and, from M2 on, the
/// Realtime half: key minting, captions and the tool relay.
/// </summary>
public partial class App : Application
{
    private PhoneServer _server = null!;
    private MdnsAdvertiser _advertiser = null!;
    private TrayIcon _tray = null!;
    private FaceDriver _faceDriver = null!;
    private HotKeys _hotKeys = null!;

    private HostSettings _settings = null!;
    private ApiKeyStore _keys = null!;
    private RealtimeMinter _minter = null!;
    private CaptionWindow _captions = null!;
    private RealtimeHost _host = null!;

    private string _logPath = null!;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        _logPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Googly", "log.txt");
        try { Directory.CreateDirectory(Path.GetDirectoryName(_logPath)!); } catch { /* best effort */ }
        Log($"Bluey {GetType().Assembly.GetName().Version} starting");

        DispatcherUnhandledException += (_, ev) =>
        {
            Log("unhandled: " + ev.Exception);
            ev.Handled = true;
        };
        AppDomain.CurrentDomain.UnhandledException += (_, ev) => Log("unhandled: " + ev.ExceptionObject);

        var hostName = HostName;
        Log($"host name: {hostName}");

        _settings = new HostSettings();
        _keys = new ApiKeyStore();
        _minter = new RealtimeMinter();
        _captions = new CaptionWindow { PhonePosition = _settings.PhonePosition };
        _settings.Changed += () => Dispatcher.BeginInvoke(() =>
            _captions.PhonePosition = _settings.PhonePosition);
        _host = new RealtimeHost(_settings, _keys, _minter, _captions);
        _host.Changed += UpdateTray;

        _server = new PhoneServer(hostName);
        _server.Started += port => Dispatcher.BeginInvoke(() =>
        {
            try { _advertiser.Dispose(); } catch { /* not started yet */ }
            _advertiser = new MdnsAdvertiser(hostName, port);
            Log($"advertising {GooglyService.Type} as \"{hostName}\" on port {port}");
        });
        _server.PhonesChanged += names => Dispatcher.BeginInvoke(() =>
        {
            if (names.Count == 0 && _host.Awake)
            {
                // Auto-sleep when the last phone disconnects while he's awake,
                // exactly like the original.
                Log("last phone disconnected — going to sleep");
                _host.SetAwake(false);
            }
            UpdateTray();
        });
        _server.RequestReceived += (packet, reply) => Dispatcher.BeginInvoke(() =>
            _host.Handle(packet, reply));
        _server.Start();

        _tray = new TrayIcon();
        _tray.WakeToggleRequested += ToggleAwake;
        _tray.QuitRequested += Quit;
        _tray.EditKeysRequested += EditKeys;
        _tray.EditPersonalityRequested += EditPersonality;
        UpdateTray();

        _hotKeys = new HotKeys(_tray.Hwnd);
        _tray.HotKeyMessage += _hotKeys.Handle;
        _hotKeys.WakeSleepPressed += ToggleAwake;

        _faceDriver = new FaceDriver(_server);
        _faceDriver.Start();

        // The original asks for an API key on first launch, half a second in.
        if (_keys.Get("gemini") is null)
        {
            var prompt = new System.Windows.Threading.DispatcherTimer
                { Interval = TimeSpan.FromSeconds(0.5) };
            prompt.Tick += (_, _) =>
            {
                prompt.Stop();
                if (_keys.Get("gemini") is null) EditKeys();
            };
            prompt.Start();
        }
    }

    /// <summary>Ctrl+Alt+Space or the menu item — same as double-tapping him on the phone.</summary>
    private void ToggleAwake()
    {
        if (_server.PhoneNames.Count == 0) return; // the menu item is disabled without a phone; the key is a no-op
        Log(_host.Awake ? "broadcast sleep" : "broadcast wake");
        _server.Broadcast(new Packet { Command = _host.Awake ? "sleep" : "wake" });
    }

    /// <summary>Wakes him again so a new personality or set of tools takes effect right away.</summary>
    private void RestartIfAwake()
    {
        if (!_host.Awake) return;
        _server.Broadcast(new Packet { Command = "sleep" });
        var wake = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        wake.Tick += (_, _) =>
        {
            wake.Stop();
            _server.Broadcast(new Packet { Command = "wake" });
        };
        wake.Start();
    }

    private void EditKeys()
    {
        var window = new KeyWindow(_keys);
        window.ShowDialog();
    }

    private void EditPersonality()
    {
        var window = new PersonalityWindow(RealtimeSpec.Personality(_settings.Personality));
        window.ShowDialog();
        if (window.Result is null) return; // cancelled
        _settings.Personality = window.Result;
        RestartIfAwake(); // his instructions change with the personality
    }

    private void UpdateTray()
    {
        _tray.UpdatePhones(_server.PhoneNames, _host.Awake);
    }

    private void Quit()
    {
        Log("quitting");
        _faceDriver.Stop();
        _hotKeys.Dispose();
        _settings.Dispose();
        _tray.Dispose();
        try { _advertiser.Dispose(); } catch { /* not started */ }
        _server.Dispose();
        Shutdown();
    }

    private void Log(string message)
    {
        try { File.AppendAllText(_logPath, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {message}\n"); }
        catch { /* logging must never crash the app */ }
    }

    private static string HostName
    {
        get
        {
            try
            {
                var name = System.Net.Dns.GetHostName();
                if (!string.IsNullOrWhiteSpace(name)) return name;
            }
            catch { /* fall through to MachineName */ }
            return Environment.MachineName;
        }
    }
}
