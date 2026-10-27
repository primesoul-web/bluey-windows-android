using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Shapes;
using System.Windows.Threading;
using GooglyProtocol;
using GooglyRealtime;

namespace GooglyWindows;

/// <summary>
/// The M3 overlay: a click-through, transparent, topmost window over the whole main
/// screen that shows the googly-eyed cursor character. Port of <c>CursorOverlay</c> +
/// <c>CursorView</c> + <c>TeardropLayer</c> in Mac/CursorOverlay.swift.
///
/// The window itself is fitted to the screen once and never moves or resizes while
/// visible (the M2.17 ghost-free lesson); the character inside it is positioned purely
/// with render transforms, and its body art is cached (<see cref="BitmapCache"/>) so
/// per-frame work is transform-and-opacity only. The M2 caption bubble stays in its own
/// window until the M3.3 choreography moves it in here.
/// </summary>
public sealed class OverlayWindow : Window
{
    private readonly PhoneServer _server;
    private readonly HostSettings _settings;
    private readonly CursorEngine _engine = new();

    /// <summary>The cursor engine — the realtime host drives awake/mood/talk from M3.3 on.</summary>
    public CursorEngine Engine => _engine;

    // Visual tree: root canvas → cursor container (transforms) → body host (cached art) + eye sockets.
    private readonly Canvas _root = new();
    private readonly Canvas _cursor = new();
    private readonly Canvas _bodyHost = new();
    private readonly List<Canvas> _sockets = new();
    private readonly List<Ellipse> _pupils = new();
    private RotateTransform _cursorRotate = new(0);
    private ScaleTransform _cursorScale = new(1, 1);
    private TranslateTransform _cursorPosition = new(-1000, -1000);
    private readonly List<ScaleTransform> _socketScales = new();
    private readonly List<TranslateTransform> _pupilOffsets = new();

    private (double side, bool glow, double dpi)? _builtArt;

    // Frame clock.
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private double _lastFrame;
    private double _lastFaceSend;
    private bool _running;

    /// <summary>
    /// The heartbeat backstop (v0.4.1). The original steps its engine on a
    /// CADisplayLink, which fires every vsync forever. WPF's
    /// <see cref="CompositionTarget.Rendering"/> only fires when something actually
    /// invalidated — and a settled overlay invalidates nothing: every transform has
    /// converged bit-exactly, the body is tucked below the screen edge at opacity 0,
    /// and property writes that don't change a value schedule no render pass. The
    /// loop would stall, the ~1 Hz face heartbeat would stop, and the phone — whose
    /// 2.5 s liveness falls back to an awake, wandering-eyed listening face, exactly
    /// like the original iOS — would "wake up by itself" and never doze again (the
    /// M3.1 hardware report). This timer notices a stalled loop and drives the frames
    /// itself; the moment anything visual changes, the transform writes invalidate,
    /// the render pass resumes, and the vsync loop takes over seamlessly.
    /// </summary>
    private readonly DispatcherTimer _heartbeat = new() { Interval = TimeSpan.FromMilliseconds(100) };

    // Where the eyes look (engine-space unit vector), eased every frame.
    private Pt _look = new(-0.7, -0.7);

    public OverlayWindow(PhoneServer server, HostSettings settings)
    {
        _server = server;
        _settings = settings;

        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        ShowInTaskbar = false;
        ShowActivated = false;
        Topmost = true;
        AllowsTransparency = true;
        Background = null;
        Focusable = false;
        IsHitTestVisible = false; // managed-side: nothing here is clickable in M3.1

        Content = _root;

        _cursor.RenderTransform = new TransformGroup
        {
            Children =
            {
                _cursorRotate, // around the tip (container origin)
                _cursorScale, // press squish, also around the tip
                _cursorPosition, // outermost: where the tip sits
            },
        };
        _root.Children.Add(_cursor);

        _engine.OnLand += point => { }; // soft-land effects arrive in M3.2
        _engine.OnLaunch += point => { }; // puffs arrive in M3.2
    }

    /// <summary>Applies every setting the overlay consumes; safe to call any time.</summary>
    public void ApplySettings()
    {
        _engine.Size = _settings.CursorSize;
        _engine.PhonePosition = _settings.PhonePosition;
        _engine.BaseMood = MapMood(_settings.Mood);
        if (_engine.IsHome)
        {
            // Idle resting spot: tucked away with eyes on your mouse, or parked above the phone.
            _engine.SetMode(_settings.FollowMouse ? CursorMode.Following : CursorMode.Docked);
        }
        RebuildArt(); // size/glow may have changed
    }

    internal static CursorMood MapMood(string mood) => mood switch
    {
        "resting" => CursorMood.Resting,
        "thinking" => CursorMood.Thinking,
        "talking" => CursorMood.Talking,
        "pointing" => CursorMood.Pointing,
        "happy" => CursorMood.Happy,
        "sleepy" => CursorMood.Sleepy,
        _ => CursorMood.Listening,
    };

    internal static Mood MapMood(CursorMood mood) => mood switch
    {
        CursorMood.Resting => Mood.resting,
        CursorMood.Thinking => Mood.thinking,
        CursorMood.Talking => Mood.talking,
        CursorMood.Pointing => Mood.pointing,
        CursorMood.Happy => Mood.happy,
        CursorMood.Sleepy => Mood.sleepy,
        _ => Mood.listening,
    };

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        // Click-through at the OS level for the whole window (nothing on the overlay is
        // interactive in M3.1 — the caption bubble keeps its own window). M3.3 replaces
        // this with the surgical hit-test hook when the bubble moves in.
        const int gwlExStyle = -20;
        const int wsExToolWindow = 0x00000080;
        const int wsExNoActivate = 0x08000000;
        const int wsExTransparent = 0x00000020;
        var hwnd = new System.Windows.Interop.WindowInteropHelper(this).Handle;
        _ = SetWindowLong(hwnd, gwlExStyle,
            GetWindowLong(hwnd, gwlExStyle) | wsExToolWindow | wsExNoActivate | wsExTransparent);
    }

    [DllImport("user32.dll")]
    private static extern int GetWindowLong(IntPtr hwnd, int index);

    [DllImport("user32.dll")]
    private static extern int SetWindowLong(IntPtr hwnd, int index, int newStyle);

    public new void Show()
    {
        base.Show();
        FitScreen();
        ApplySettings();
        if (_running) return;
        _running = true;
        _lastFrame = 0;
        CompositionTarget.Rendering += OnFrame;
        _heartbeat.Tick += OnHeartbeat;
        _heartbeat.Start();
        Microsoft.Win32.SystemEvents.DisplaySettingsChanged += OnDisplayChanged;
    }

    protected override void OnClosed(EventArgs e)
    {
        CompositionTarget.Rendering -= OnFrame;
        _heartbeat.Stop();
        _heartbeat.Tick -= OnHeartbeat;
        Microsoft.Win32.SystemEvents.DisplaySettingsChanged -= OnDisplayChanged;
        _running = false;
        base.OnClosed(e);
    }

    /// <summary>True when the render loop has gone quiet — the settled-overlay stall
    /// the heartbeat exists to cover.</summary>
    private void OnHeartbeat(object? sender, EventArgs e)
    {
        if (!_running) return;
        if (_clock.Elapsed.TotalSeconds - _lastFrame < 0.15) return; // vsync loop alive
        OnFrame(sender, e);
    }

    private void OnDisplayChanged(object? sender, EventArgs e) =>
        Dispatcher.BeginInvoke(FitScreen);

    /// <summary>Full primary screen, taskbar included, exactly like the original's
    /// screenSaver-level window over NSScreen.screens.first.</summary>
    private void FitScreen()
    {
        var width = SystemParameters.PrimaryScreenWidth;
        var height = SystemParameters.PrimaryScreenHeight;
        if (width <= 0 || height <= 0) return;
        Left = 0;
        Top = 0;
        Width = width;
        Height = height;
    }

    private void OnFrame(object? sender, EventArgs e)
    {
        if (!_running) return;
        var now = _clock.Elapsed.TotalSeconds;
        var dt = _lastFrame == 0 ? 1.0 / 60 : CursorClock.ClampDt(now - _lastFrame);
        _lastFrame = now;

        if (!NativeMethods.GetCursorPos(out var point)) return;
        var mouse = ScreenToDip(point.X, point.Y);
        var bounds = new Sz(SystemParameters.PrimaryScreenWidth, SystemParameters.PrimaryScreenHeight);
        if (bounds.Width <= 0 || bounds.Height <= 0) return;

        _engine.Step(dt, now, mouse, bounds);
        Render(now, dt);

        // The face goes to the phone at the proven ~30 Hz cadence (the original sends
        // every display-link frame; PhoneServer's write thread coalesces either way).
        if (now - _lastFaceSend >= 1.0 / 30)
        {
            _lastFaceSend = now;
            var face = _engine.Face(bounds, now);
            _server.Send(FaceState.Create(face.GazeX, face.GazeY, MapMood(face.Mood), face.Talk));
        }
    }

    private Pt ScreenToDip(int x, int y)
    {
        if (PresentationSource.FromVisual(this)?.CompositionTarget is { } target)
        {
            var dip = target.TransformFromDevice.Transform(new Point(x, y));
            return new Pt(dip.X, dip.Y);
        }
        return new Pt(x, y);
    }

    // MARK: Art

    /// <summary>Rebuilds the teardrop + eyes when size, glow or DPI changes — the port
    /// of <c>buildArtIfNeeded</c>. Everything static is rasterized once into the body's
    /// <see cref="BitmapCache"/>; frames after that only touch transforms.</summary>
    private void RebuildArt()
    {
        var side = _engine.Size;
        var glow = _settings.Glow;
        var dpi = VisualTreeHelper.GetDpi(this).DpiScaleX;
        if (_builtArt is { } art && art.side == side && art.glow == glow && Math.Abs(art.dpi - dpi) < 0.01) return;
        _builtArt = (side, glow, dpi);

        var k = side / 96;
        _cursor.Width = side;
        _cursor.Height = side;

        // The body: a square with three fully rounded corners and one sharp one (the
        // tip), drawn top-down (y down) so the art matches the engine's coordinates.
        var padding = side * 0.45;
        var path = new System.Windows.Shapes.Path
        {
            Data = TeardropGeometry(side, 6 * k),
            Fill = BodyBrush(side),
            Stroke = Brushes.White,
            StrokeThickness = 8 * k, // half is clipped away, leaving a 4 pt rim
            Clip = TeardropGeometry(side, 6 * k),
            Effect = glow
                ? new DropShadowEffect
                {
                    Color = Palette.Berry2, Opacity = 0.7, BlurRadius = 30 * k, ShadowDepth = 0,
                }
                : new DropShadowEffect
                {
                    Color = Palette.Berry4, Opacity = 0.45, BlurRadius = 16 * k, ShadowDepth = 0,
                },
        };
        Canvas.SetLeft(path, 0);
        Canvas.SetTop(path, 0);
        _bodyHost.Width = side;
        _bodyHost.Height = side;
        _bodyHost.Children.Clear();
        _bodyHost.Children.Add(path);
        // The shine rides on top of the gradient inside the same cached host.
        var shine = new System.Windows.Shapes.Path
        {
            Data = TeardropGeometry(side, 6 * k),
            Fill = ShineBrush(side),
        };
        _bodyHost.Children.Add(shine);
        _bodyHost.CacheMode = new BitmapCache(dpi);
        Canvas.SetLeft(_bodyHost, -padding);
        Canvas.SetTop(_bodyHost, -padding);
        if (!_cursor.Children.Contains(_bodyHost)) _cursor.Children.Insert(0, _bodyHost);

        // The eyes: two sockets at the design positions, white + pupil, blinking via
        // scaleY and looking via pupil offsets (never cached — they move every frame).
        foreach (var socket in _sockets) _cursor.Children.Remove(socket);
        _sockets.Clear();
        _pupils.Clear();
        _socketScales.Clear();
        _pupilOffsets.Clear();
        for (var i = 0; i < 2; i++)
        {
            var d = 18 * k;
            var socket = new Canvas { Width = d, Height = d, RenderTransformOrigin = new Point(0.5, 0.5) };
            var scale = new ScaleTransform(1, 1);
            socket.RenderTransform = scale;
            Canvas.SetLeft(socket, (34 + i * 24) * k);
            Canvas.SetTop(socket, 40 * k);

            var white = new Ellipse { Width = d, Height = d, Fill = Brushes.White };
            socket.Children.Add(white);

            var pupil = new Ellipse
            {
                Width = 9 * k,
                Height = 9 * k,
                Fill = new SolidColorBrush(Palette.Ink),
            };
            var offset = new TranslateTransform();
            pupil.RenderTransform = offset;
            Canvas.SetLeft(pupil, (d - 9 * k) / 2);
            Canvas.SetTop(pupil, (d - 9 * k) / 2);
            socket.Children.Add(pupil);

            _cursor.Children.Add(socket);
            _sockets.Add(socket);
            _pupils.Add(pupil);
            _socketScales.Add(scale);
            _pupilOffsets.Add(offset);
        }
    }

    /// <summary>A square with three fully rounded corners and one sharp one (the tip)
    /// at the origin. Port of <c>Teardrop.path</c> — y down, clockwise.</summary>
    internal static StreamGeometry TeardropGeometry(double s, double tipRadius)
    {
        var g = new StreamGeometry();
        using (var ctx = g.Open())
        {
            var big = s / 2;
            ctx.BeginFigure(new Point(tipRadius, 0), isFilled: true, isClosed: true);
            ctx.LineTo(new Point(big, 0), isStroked: true, isSmoothJoin: false);
            ctx.ArcTo(new Point(s, big), new Size(big, big), 0, false, SweepDirection.Clockwise, true, false);
            ctx.ArcTo(new Point(big, s), new Size(big, big), 0, false, SweepDirection.Clockwise, true, false);
            ctx.ArcTo(new Point(0, big), new Size(big, big), 0, false, SweepDirection.Clockwise, true, false);
            ctx.LineTo(new Point(0, tipRadius), isStroked: true, isSmoothJoin: false);
            ctx.ArcTo(new Point(tipRadius, 0), new Size(tipRadius, tipRadius), 0, false, SweepDirection.Clockwise, true, false);
        }
        g.Freeze();
        return g;
    }

    private static Brush BodyBrush(double s)
    {
        // berry1 → berry2 → berry3 → berry4 along the diagonal, exactly like the
        // original's linear gradient from (0.25s, 0.067s) to (0.75s, 0.933s).
        var brush = new LinearGradientBrush
        {
            StartPoint = new Point(0.25 * s, 0.067 * s),
            EndPoint = new Point(0.75 * s, 0.933 * s),
            MappingMode = BrushMappingMode.Absolute,
            GradientStops =
            {
                new GradientStop(Palette.Berry1, 0),
                new GradientStop(Palette.Berry2, 0.34),
                new GradientStop(Palette.Berry3, 0.68),
                new GradientStop(Palette.Berry4, 1),
            },
            SpreadMethod = GradientSpreadMethod.Pad,
        };
        brush.Freeze();
        return brush;
    }

    private static Brush ShineBrush(double s)
    {
        // A soft radial shine centered at (0.3s, 0.24s), radius 0.38s.
        var brush = new RadialGradientBrush
        {
            Center = new Point(0.3 * s / s, 0.24 * s / s),
            GradientOrigin = new Point(0.3 * s / s, 0.24 * s / s),
            RadiusX = 0.38,
            RadiusY = 0.38,
            MappingMode = BrushMappingMode.RelativeToBoundingBox,
            GradientStops =
            {
                new GradientStop(Color.FromArgb(0x8C, 0xFF, 0xFF, 0xFF), 0), // white @ 0.55
                new GradientStop(Color.FromArgb(0x00, 0xFF, 0xFF, 0xFF), 1),
            },
        };
        brush.Freeze();
        return brush;
    }

    // MARK: Frame

    /// <summary>The per-frame render: transforms and opacity only — the port of
    /// <c>CursorView.render</c> minus trails/effects/bubbles (M3.2) and the speech
    /// cloud (M3.3).</summary>
    private void Render(double now, double dt)
    {
        RebuildArt();

        // The cursor itself.
        _cursorPosition.X = _engine.Tip.X;
        _cursorPosition.Y = _engine.Tip.Y + _engine.Hover(now);
        var squish = _engine.PressScale(now);
        _cursorScale.ScaleX = squish;
        _cursorScale.ScaleY = squish;
        _cursorRotate.Angle = _engine.Angle * 180 / Math.PI; // y-down engine space = WPF's
        _cursor.Opacity = _settings.ShowCursor ? _engine.Opacity : 0;

        // Its eyes look where it's heading, and back at the tip when it stops.
        // They blink now and then.
        var speed = _engine.Velocity.Length;
        var wantLook = new Pt(-0.7, -0.7);
        if (speed > 90)
        {
            var a = -_engine.Angle;
            var local = new Pt(
                _engine.Velocity.X * Math.Cos(a) - _engine.Velocity.Y * Math.Sin(a),
                _engine.Velocity.X * Math.Sin(a) + _engine.Velocity.Y * Math.Cos(a));
            var n = Math.Max(local.Length, 1);
            wantLook = new Pt(local.X / n, local.Y / n);
        }
        var follow = Math.Min(1, dt * 12);
        _look = new Pt(
            _look.X + (wantLook.X - _look.X) * follow,
            _look.Y + (wantLook.Y - _look.Y) * follow);
        var blinking = now < _engine.BlinkUntil;
        for (var i = 0; i < _sockets.Count; i++)
        {
            var k = _engine.Size / 96;
            _pupilOffsets[i].X = _look.X * 3.4 * k;
            _pupilOffsets[i].Y = _look.Y * 3.4 * k;
            _socketScales[i].ScaleY = blinking ? 0.12 : 1;
        }
    }
}
