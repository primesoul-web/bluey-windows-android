using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using GooglyProtocol;

namespace GooglyWindows;

/// <summary>
/// The system-tray icon — the port of GooglyMac's status item. Left or right click opens
/// the menu (rebuilt on every open, like menuNeedsUpdate). The icon is the hand-drawn
/// blueberry blob with googly eyes, rendered disabled while no phone is connected.
/// </summary>
public sealed class TrayIcon : IDisposable
{
    private HwndSource? _source;
    private Grid? _root;
    private IntPtr _hIcon = IntPtr.Zero;
    private bool _iconAdded;
    private bool _disposed;

    private IReadOnlyList<string> _phones = Array.Empty<string>();
    private bool _awake;
    private bool _connectedIconState;
    private string _connectionInfo = "";

    public event Action? WakeToggleRequested;
    public event Action? QuitRequested;
    public event Action? EditKeysRequested;
    public event Action? EditPersonalityRequested;
    /// <summary>Port addition (diagnostics): the user picked “Test Gemini Connection…”.</summary>
    public event Action? TestGeminiRequested;
    /// <summary>Forwards WM_HOTKEY (the argument is the hotkey id).</summary>
    public event Action<int>? HotKeyMessage;

    // The M3.1 overlay slice of the menu (the exact original order lands with the
    // full menu in M3.4).
    /// <summary>The user toggled “Eyes Follow My Mouse” (idle: tucked + phone tracks
    /// your mouse) off/on — off parks the character docked above the phone.</summary>
    public event Action<bool>? FollowMouseToggled;
    /// <summary>The user picked a cursor size (48/72/96/120 points).</summary>
    public event Action<double>? CursorSizeSelected;
    public event Action<bool>? GlowToggled;
    public event Action<bool>? ShowCursorToggled;

    private bool _followMouse = true;
    private double _cursorSize = 72;
    private bool _glow;
    private bool _showCursor = true;

    /// <summary>Syncs the overlay settings' checkmarks (called whenever settings change).</summary>
    public void UpdateOverlaySettings(bool followMouse, double cursorSize, bool glow, bool showCursor)
    {
        _followMouse = followMouse;
        _cursorSize = cursorSize;
        _glow = glow;
        _showCursor = showCursor;
    }

    public IntPtr Hwnd => _source?.Handle ?? throw new InvalidOperationException("tray icon not shown");

    public TrayIcon()
    {
        var parameters = new HwndSourceParameters("BlueyTray")
        {
            Width = 0,
            Height = 0,
            WindowStyle = unchecked((int)0x80000000), // WS_POPUP — a hidden, non-activating window
        };
        _source = new HwndSource(parameters);
        _root = new Grid();
        _source.RootVisual = _root;
        _source.AddHook(WndProc);

        _hIcon = RenderIcon(connected: false);
        _connectedIconState = false;

        var data = new NativeMethods.NOTIFYICONDATA
        {
            cbSize = (uint)System.Runtime.InteropServices.Marshal.SizeOf<NativeMethods.NOTIFYICONDATA>(),
            hWnd = _source.Handle,
            uID = 1,
            uFlags = NativeMethods.NIF_MESSAGE | NativeMethods.NIF_ICON | NativeMethods.NIF_TIP,
            uCallbackMessage = (uint)NativeMethods.WM_APP_TRAY,
            hIcon = _hIcon,
            szTip = "Bluey",
        };
        _iconAdded = NativeMethods.Shell_NotifyIcon(NativeMethods.NIM_ADD, ref data);
    }

    /// <summary>Refreshes the status line, the wake/sleep item, and the icon's enabled look.</summary>
    public void UpdatePhones(IReadOnlyList<string> phones, bool awake)
    {
        _phones = phones;
        _awake = awake;
        var connected = phones.Count > 0;
        if (connected != _connectedIconState)
        {
            _connectedIconState = connected;
            SwapIcon(connected);
        }
    }

    /// <summary>Port addition: the listening port and LAN IPs, shown under the status
    /// line so a USB-cable setup can type them into the phone app.</summary>
    public void UpdateConnectionInfo(string info)
    {
        _connectionInfo = info;
    }

    private void SwapIcon(bool connected)
    {
        if (!_iconAdded || _source is null) return;
        var fresh = RenderIcon(connected);
        var data = new NativeMethods.NOTIFYICONDATA
        {
            cbSize = (uint)System.Runtime.InteropServices.Marshal.SizeOf<NativeMethods.NOTIFYICONDATA>(),
            hWnd = _source.Handle,
            uID = 1,
            uFlags = NativeMethods.NIF_ICON,
            hIcon = fresh,
        };
        if (NativeMethods.Shell_NotifyIcon(1 /* NIM_MODIFY */, ref data))
        {
            if (_hIcon != IntPtr.Zero) NativeMethods.DestroyIcon(_hIcon);
            _hIcon = fresh;
        }
        else
        {
            NativeMethods.DestroyIcon(fresh);
        }
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == NativeMethods.WM_APP_TRAY)
        {
            var evt = (uint)(lParam.ToInt64() & 0xFFFF);
            if (evt is NativeMethods.WM_LBUTTONUP or NativeMethods.WM_RBUTTONUP)
            {
                // Let the click release before the menu opens, like the status item does.
                Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.Input, ShowMenu);
                handled = true;
            }
        }
        else if (msg == NativeMethods.WM_HOTKEY)
        {
            HotKeyMessage?.Invoke((int)(wParam.ToInt64() & 0xFFFF));
            handled = true;
        }
        return IntPtr.Zero;
    }

    /// <summary>Builds and opens the menu — the exact M1 slice of GooglyMac's menuNeedsUpdate.</summary>
    private void ShowMenu()
    {
        if (_root is null) return;

        var menu = new ContextMenu();
        var status = new MenuItem
        {
            Header = _phones.Count == 0
                ? "Waiting for the phone app…"
                : $"Phone connected: {string.Join(", ", _phones)}",
            IsEnabled = false,
        };
        menu.Items.Add(status);

        // Port addition: what to type on the phone when connecting over a USB cable
        // (mDNS doesn't cross Android's USB-tethering interface).
        if (_connectionInfo.Length > 0)
        {
            menu.Items.Add(new MenuItem
            {
                Header = _connectionInfo,
                IsEnabled = false,
                FontSize = 11,
            });
        }

        var wake = new MenuItem
        {
            Header = _awake ? "Go to Sleep (Follow Mode)" : "Wake Up and Talk",
            InputGestureText = "Ctrl+Alt+Space",
            IsEnabled = _phones.Count > 0,
        };
        wake.Click += (_, _) => WakeToggleRequested?.Invoke();
        menu.Items.Add(wake);

        menu.Items.Add(new Separator());

        var personality = new MenuItem { Header = "Personality…" };
        personality.Click += (_, _) => EditPersonalityRequested?.Invoke();
        menu.Items.Add(personality);

        var key = new MenuItem { Header = "Gemini API Key…" };
        key.Click += (_, _) => EditKeysRequested?.Invoke();
        menu.Items.Add(key);

        // Port addition (diagnostics): mint + session-leg check with no phone involved.
        var test = new MenuItem { Header = "Test Gemini Connection…" };
        test.Click += (_, _) => TestGeminiRequested?.Invoke();
        menu.Items.Add(test);

        menu.Items.Add(new Separator());

        // The M3.1 overlay slice: everything the character rendering consumes, so the
        // drop is checkable on hardware before the full menu lands (M3.4).
        var follow = new MenuItem
        {
            Header = "Eyes Follow My Mouse",
            IsCheckable = true,
            IsChecked = _followMouse,
        };
        follow.Click += (_, _) => FollowMouseToggled?.Invoke(!follow.IsChecked);
        menu.Items.Add(follow);

        var sizeMenu = new MenuItem { Header = "Cursor Size" };
        foreach (var s in (double[])[48, 72, 96, 120])
        {
            var size = s;
            var item = new MenuItem
            {
                Header = $"{size:F0} pt",
                IsCheckable = true,
                IsChecked = Math.Abs(_cursorSize - size) < 0.01,
            };
            item.Click += (_, _) => CursorSizeSelected?.Invoke(size);
            sizeMenu.Items.Add(item);
        }
        menu.Items.Add(sizeMenu);

        var glowItem = new MenuItem
        {
            Header = "Cursor Glow",
            IsCheckable = true,
            IsChecked = _glow,
        };
        glowItem.Click += (_, _) => GlowToggled?.Invoke(!glowItem.IsChecked);
        menu.Items.Add(glowItem);

        var showItem = new MenuItem
        {
            Header = "Show Cursor",
            IsCheckable = true,
            IsChecked = _showCursor,
        };
        showItem.Click += (_, _) => ShowCursorToggled?.Invoke(!showItem.IsChecked);
        menu.Items.Add(showItem);

        menu.Items.Add(new Separator());
        var quit = new MenuItem { Header = "Quit Bluey" };
        quit.Click += (_, _) => QuitRequested?.Invoke();
        menu.Items.Add(quit);

        NativeMethods.SetForegroundWindow(Hwnd);
        menu.PlacementTarget = _root;
        menu.Placement = System.Windows.Controls.Primitives.PlacementMode.MousePoint;
        menu.IsOpen = true;
    }

    /// <summary>
    /// Draws the blueberry blob with googly eyes (MenuIcon.make in the original):
    /// gradient body, two white eyes, two ink pupils, on a transparent 32×32 canvas.
    /// </summary>
    private static IntPtr RenderIcon(bool connected)
    {
        const int size = 32;
        var visual = new DrawingVisual();
        using (var context = visual.RenderOpen())
        {
            // The original draws in a 22×18 flipped box; scale into 32×32.
            const double s = 32.0 / 22.0;
            var bodyRect = new Rect(1 * s, 2 * s, 20 * s, 16 * s);

            var gradient = new LinearGradientBrush(
                new GradientStopCollection
                {
                    new(Palette.Berry1, 0), new(Palette.Berry2, 0.34),
                    new(Palette.Berry3, 0.68), new(Palette.Berry4, 1),
                },
                new Point(0.25, 0.067), new Point(0.75, 0.933));

            context.DrawEllipse(connected ? gradient : Brushes.DimGray, null, new Point(bodyRect.X + bodyRect.Width / 2, bodyRect.Y + bodyRect.Height / 2), bodyRect.Width / 2, bodyRect.Height / 2);

            var eyeBrush = connected ? Brushes.White : Brushes.Silver;
            var pupilBrush = connected ? new SolidColorBrush(Palette.Ink) : Brushes.Gray;

            var leftEye = new Rect(5 * s, 6 * s, 5.5 * s, 5.5 * s);
            var rightEye = new Rect(11.5 * s, 6 * s, 5.5 * s, 5.5 * s);
            context.DrawEllipse(eyeBrush, null, Center(leftEye), leftEye.Width / 2, leftEye.Height / 2);
            context.DrawEllipse(eyeBrush, null, Center(rightEye), rightEye.Width / 2, rightEye.Height / 2);

            var leftPupil = new Rect(6.3 * s, 6.8 * s, 3 * s, 3 * s);
            var rightPupil = new Rect(12.8 * s, 6.8 * s, 3 * s, 3 * s);
            context.DrawEllipse(pupilBrush, null, Center(leftPupil), leftPupil.Width / 2, leftPupil.Height / 2);
            context.DrawEllipse(pupilBrush, null, Center(rightPupil), rightPupil.Width / 2, rightPupil.Height / 2);
        }

        var bitmap = new RenderTargetBitmap(size, size, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);

        using var stream = new MemoryStream();
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        encoder.Save(stream);
        stream.Position = 0;
        using var drawing = new System.Drawing.Bitmap(stream);
        return drawing.GetHicon();
    }

    private static Point Center(Rect r) => new(r.X + r.Width / 2, r.Y + r.Height / 2);

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        if (_iconAdded && _source is not null)
        {
            var data = new NativeMethods.NOTIFYICONDATA
            {
                cbSize = (uint)System.Runtime.InteropServices.Marshal.SizeOf<NativeMethods.NOTIFYICONDATA>(),
                hWnd = _source.Handle,
                uID = 1,
            };
            NativeMethods.Shell_NotifyIcon(NativeMethods.NIM_DELETE, ref data);
        }
        if (_hIcon != IntPtr.Zero)
        {
            NativeMethods.DestroyIcon(_hIcon);
            _hIcon = IntPtr.Zero;
        }
        _source?.RemoveHook(WndProc);
        _source?.Dispose();
        _source = null;
    }
}

/// <summary>The blueberry palette from Shared/Palette.swift.</summary>
internal static class Palette
{
    public static readonly Color Berry1 = FromHex(0xA9BCFF); // light periwinkle
    public static readonly Color Berry2 = FromHex(0x6C86F5);
    public static readonly Color Berry3 = FromHex(0x4254D6);
    public static readonly Color Berry4 = FromHex(0x2B2F8F); // deep indigo
    public static readonly Color Nose = FromHex(0x1C1F66);
    public static readonly Color Ink = FromHex(0x17151F);
    public static readonly Color InkSoft = FromHex(0xB9B2CC);
    public static readonly Color Panel = FromHex(0x1E1B29);

    private static Color FromHex(uint hex) =>
        Color.FromArgb(0xFF, (byte)(hex >> 16), (byte)(hex >> 8), (byte)hex);
}
