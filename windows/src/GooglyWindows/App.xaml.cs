using System.IO;
using System.Windows;
using GooglyProtocol;

namespace GooglyWindows;

/// <summary>
/// The tray application — the port of GooglyMac's AppDelegate. Wires the phone server,
/// the mDNS advertiser, the face driver, the tray icon, and the wake/sleep hotkey
/// (Ctrl+Alt+Space, the Mac ⌥Space stand-in per PARITY.md D1).
/// </summary>
public partial class App : Application
{
    private PhoneServer _server = null!;
    private MdnsAdvertiser _advertiser = null!;
    private TrayIcon _tray = null!;
    private FaceDriver _faceDriver = null!;
    private HotKeys _hotKeys = null!;

    private bool _awake;
    private IReadOnlyList<string> _phones = Array.Empty<string>();
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

        _server = new PhoneServer(hostName);
        _server.Started += port => Dispatcher.BeginInvoke(() =>
        {
            try { _advertiser.Dispose(); } catch { /* not started yet */ }
            _advertiser = new MdnsAdvertiser(hostName, port);
            Log($"advertising {GooglyService.Type} as \"{hostName}\" on port {port}");
        });
        _server.PhonesChanged += names => Dispatcher.BeginInvoke(() =>
        {
            _phones = names;
            if (names.Count == 0 && _awake)
            {
                // Auto-sleep when the last phone disconnects while he's awake,
                // exactly like the original.
                Log("last phone disconnected — going to sleep");
                _awake = false;
            }
            UpdateTray();
        });
        _server.RequestReceived += (packet, _) => Dispatcher.BeginInvoke(() =>
        {
            switch (packet.Command)
            {
                case "awake":
                    _awake = true;
                    UpdateTray();
                    break;
                case "asleep":
                    _awake = false;
                    UpdateTray();
                    break;
            }
        });
        _server.Start();

        _tray = new TrayIcon();
        _tray.WakeToggleRequested += ToggleAwake;
        _tray.QuitRequested += Quit;
        UpdateTray();

        _hotKeys = new HotKeys(_tray.Hwnd);
        _tray.HotKeyMessage += _hotKeys.Handle;
        _hotKeys.WakeSleepPressed += ToggleAwake;

        _faceDriver = new FaceDriver(_server);
        _faceDriver.Start();
    }

    /// <summary>Ctrl+Alt+Space or the menu item — same as double-tapping him on the phone.</summary>
    private void ToggleAwake()
    {
        if (_phones.Count == 0) return; // the menu item is disabled without a phone; the key is a no-op
        Log(_awake ? "broadcast sleep" : "broadcast wake");
        _server.Broadcast(new Packet { Command = _awake ? "sleep" : "wake" });
    }

    private void UpdateTray()
    {
        _tray.UpdatePhones(_phones, _awake);
    }

    private void Quit()
    {
        Log("quitting");
        _faceDriver.Stop();
        _hotKeys.Dispose();
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
