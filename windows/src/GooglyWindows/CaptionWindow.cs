using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using GooglyRealtime;

namespace GooglyWindows;

/// <summary>
/// His speech bubble on the PC. M2 stand-in: from M3 on the bubble lives on the cursor
/// overlay (beside his cursor, or above the pointed-at thing); until then it floats just
/// above where the phone sits under the screen. The bubble's own look is the final one:
/// white→#EEF0FF gradient cloud, berry2 border, display font sized by length
/// (21/19/17 for ≤50/≤110/more words' worth of characters), width
/// max(240, √len·50) clamped to 580 and half the screen, (24,15) padding.
///
/// Interactive since v0.3.7 (the user asked; the original's bubble was click-through):
/// a click anywhere on the bubble closes it, the ✕ badge in its corner closes it, and
/// dragging moves it — the dragged spot is where every later bubble lands too. Only
/// the bubble itself takes the pointer: everything around it (the shadow margin, the
/// rounded corners' cut-outs) passes clicks straight through to whatever is behind.
/// The window never activates, so it can't steal focus from real work.
/// </summary>
public sealed class CaptionWindow : Window
{
    private const int WM_NCHITTEST = 0x0084;
    private static readonly IntPtr HTTRANSPARENT = new(-1);

    private readonly TextBlock _text;
    private readonly Border _cloud;
    private readonly Border _close;

    /// <summary>0 = left edge of the screen, 1 = right (where the phone sits).</summary>
    public double PhonePosition { get; set; } = 0.5;

    /// <summary>Raised when a press becomes a drag — the reading timer re-arms so the
    /// bubble can't fade out from under the user's hand.</summary>
    public event Action? DragBegun;

    /// <summary>The spot the user dragged the bubble to; null until they do. Every
    /// bubble after the drag lands there (until the app restarts).</summary>
    private CaptionGeometry.Spot? _pinned;

    private bool _pressing;
    private bool _dragging;
    private Point _pressPoint;
    private Point _lastPoint;

    public CaptionWindow()
    {
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = null; // not Transparent: null keeps the margins click-through
        ShowInTaskbar = false;
        ShowActivated = false;
        Topmost = true;
        Focusable = false;
        ResizeMode = ResizeMode.NoResize;
        SizeToContent = SizeToContent.Height;
        Opacity = 0;
        Visibility = Visibility.Hidden;

        _text = new TextBlock
        {
            FontFamily = Fonts.Display,
            TextWrapping = TextWrapping.Wrap,
            Foreground = new SolidColorBrush(Palette.Ink),
        };

        _cloud = new Border
        {
            Child = _text,
            Padding = new Thickness(24, 15, 24, 15),
            CornerRadius = new CornerRadius(18),
            BorderThickness = new Thickness(2.5),
            BorderBrush = new SolidColorBrush(Palette.Berry2),
            Background = new LinearGradientBrush(
                new GradientStopCollection
                {
                    new(Color.FromArgb(0xFF, 0xFF, 0xFF, 0xFF), 0),
                    new(Color.FromArgb(0xFF, 0xEE, 0xF0, 0xFF), 1),
                },
                new Point(0, 0), new Point(0, 1)),
            Margin = new Thickness(16), // room for the drop shadow and the ✕ badge
            Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                Color = Color.FromArgb(0x80, 0, 0, 0),
                BlurRadius = 18,
                ShadowDepth = 4,
                Opacity = 0.5,
            },
            Cursor = Cursors.SizeAll, // the affordance: the whole bubble is draggable
        };

        _close = new Border
        {
            Width = 22,
            Height = 22,
            CornerRadius = new CornerRadius(11),
            Background = Brushes.White,
            BorderBrush = new SolidColorBrush(Palette.Berry2),
            BorderThickness = new Thickness(1.5),
            Opacity = 0.55,
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 9, 9, 0), // on the cloud's top-right corner curve
            Cursor = Cursors.Hand,
            ToolTip = "Close",
            Child = CrossGlyph(),
        };
        _close.MouseEnter += (_, _) => _close.Opacity = 1.0;
        _close.MouseLeave += (_, _) => _close.Opacity = 0.55;
        _close.MouseLeftButtonDown += (_, e) =>
        {
            e.Handled = true;
            FadeOut();
        };

        var root = new Grid();
        root.Children.Add(_cloud);
        root.Children.Add(_close);
        Content = root;

        _cloud.MouseLeftButtonDown += OnCloudDown;
        _cloud.MouseMove += OnCloudMove;
        _cloud.MouseLeftButtonUp += OnCloudUp;
        _cloud.LostMouseCapture += (_, _) => _pressing = false;

        SourceInitialized += (_, _) => InstallPointerRules();
    }

    /// <summary>Shows (or, with null, hides) the bubble. Runs on the UI thread.</summary>
    public void Show(string? text)
    {
        if (string.IsNullOrEmpty(text))
        {
            FadeOut();
            return;
        }

        var length = text.Length;
        _text.FontSize = CaptionGeometry.FontSizeFor(length);

        var work = SystemParameters.WorkArea;
        var width = CaptionGeometry.TextWidthFor(length, work.Width);
        _text.MaxWidth = width;

        _text.Text = text;

        // Measure under the same finite width the final layout will use, so the wrap we
        // measure is the wrap we render (v0.3.6). The old dance — Measure with infinite
        // width, then Arrange + UpdateLayout — re-ran layout from the window root at the
        // window's STALE width (NaN before the first caption ever laid out), which
        // re-wrapped the text at ~0 px: DesiredSize came back one character wide and
        // dozens of lines tall, Width locked to that, and every caption after rendered
        // as a vertical letter-stack in the middle of the screen.
        _cloud.Measure(new Size(width + CaptionGeometry.Chrome, double.PositiveInfinity));
        var size = _cloud.DesiredSize;

        Width = size.Width;
        var (left, top) = CaptionGeometry.Place(
            size.Width, size.Height,
            new CaptionGeometry.WorkArea(work.Left, work.Top, work.Right, work.Bottom),
            PhonePosition, _pinned);
        Left = left;
        Top = top;

        Visibility = Visibility.Visible;
        Opacity = 0;

        var fadeIn = new DoubleAnimation(0, 1, TimeSpan.FromSeconds(0.12)) { EasingFunction = null };
        BeginAnimation(OpacityProperty, fadeIn);
    }

    private void FadeOut()
    {
        if (Visibility != Visibility.Visible) return;
        _pressing = false;
        _dragging = false;
        var fade = new DoubleAnimation(0, TimeSpan.FromSeconds(0.22));
        fade.Completed += (_, _) => Visibility = Visibility.Hidden;
        BeginAnimation(OpacityProperty, fade);
    }

    // MARK: Pointer rules — interactive on the bubble, invisible to clicks around it

    /// <summary>Drops the click-through style (v0.3.6 and earlier) but keeps the window
    /// out of the taskbar, Alt+Tab and the focus chain, and answers WM_NCHITTEST so
    /// only the bubble itself ever takes the pointer.</summary>
    private void InstallPointerRules()
    {
        var hwnd = new WindowInteropHelper(this).Handle;
        const int gwlExStyle = -20;
        const int wsExToolWindow = 0x00000080;
        const int wsExNoActivate = 0x08000000;
        _ = SetWindowLong(hwnd, gwlExStyle,
            GetWindowLong(hwnd, gwlExStyle) | wsExToolWindow | wsExNoActivate);
        HwndSource.FromHwnd(hwnd)?.AddHook(WndProc);
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg != WM_NCHITTEST) return IntPtr.Zero;
        // Screen pixels, signed shorts so multi-monitor negative coordinates work.
        var x = (short)((long)lParam & 0xFFFF);
        var y = (short)(((long)lParam >> 16) & 0xFFFF);
        try
        {
            if (!IsBubbleHit(PointFromScreen(new Point(x, y))))
            {
                handled = true;
                return HTTRANSPARENT; // clicks on the margins pass through to what's behind
            }
        }
        catch (InvalidOperationException)
        {
            // not rendered yet — let the default answer stand
        }
        return IntPtr.Zero;
    }

    /// <summary>True when the point lands on the cloud or the ✕ badge (their real
    /// shapes — a null window background keeps the rest of the window unhittable).</summary>
    private bool IsBubbleHit(Point point)
    {
        var visual = VisualTreeHelper.HitTest(this, point)?.VisualHit;
        while (visual != null)
        {
            if (ReferenceEquals(visual, _cloud) || ReferenceEquals(visual, _close)) return true;
            visual = VisualTreeHelper.GetParent(visual);
        }
        return false;
    }

    // MARK: Click to close, drag to move

    private void OnCloudDown(object sender, MouseButtonEventArgs e)
    {
        _pressing = true;
        _dragging = false;
        _pressPoint = _lastPoint = e.GetPosition(this);
        _cloud.CaptureMouse();
    }

    private void OnCloudMove(object sender, MouseEventArgs e)
    {
        if (!_pressing) return;
        var p = e.GetPosition(this);
        if (!_dragging &&
            (Math.Abs(p.X - _pressPoint.X) > CaptionGeometry.DragThreshold ||
             Math.Abs(p.Y - _pressPoint.Y) > CaptionGeometry.DragThreshold))
        {
            _dragging = true;
            DragBegun?.Invoke(); // the reading timer re-arms — no fading out mid-drag
        }
        if (_dragging)
        {
            Left += p.X - _lastPoint.X;
            Top += p.Y - _lastPoint.Y;
            PinToWorkArea();
        }
        _lastPoint = p;
    }

    private void OnCloudUp(object sender, MouseButtonEventArgs e)
    {
        if (!_pressing) return;
        _pressing = false;
        var wasDragging = _dragging;
        _dragging = false;
        _cloud.ReleaseMouseCapture();
        if (wasDragging) return;
        // A click that never became a drag closes the bubble — but a release out in
        // the margins (the press left the cloud while captured) is a cancel, not a close.
        var r = e.GetPosition(_cloud);
        if (r.X >= 0 && r.Y >= 0 && r.X <= _cloud.RenderSize.Width && r.Y <= _cloud.RenderSize.Height)
        {
            FadeOut();
        }
    }

    /// <summary>Keeps the dragged bubble reachable — at least [CaptionGeometry.KeepVisible]
    /// DIPs on screen and the ✕ badge inside the work area — and remembers the spot.</summary>
    private void PinToWorkArea()
    {
        var work = SystemParameters.WorkArea;
        Left = Math.Clamp(Left, work.Left - ActualWidth + CaptionGeometry.KeepVisible,
            Math.Max(work.Left - ActualWidth + CaptionGeometry.KeepVisible, work.Right - CaptionGeometry.KeepVisible));
        Top = Math.Clamp(Top, work.Top, Math.Max(work.Top, work.Bottom - 32));
        _pinned = new CaptionGeometry.Spot(Left, Top);
    }

    /// <summary>The ✕ in the close badge — drawn, not typed, so it never depends on a
    /// font having the glyph.</summary>
    private static Canvas CrossGlyph()
    {
        var glyph = new Canvas
        {
            Width = 8,
            Height = 8,
            IsHitTestVisible = false,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        foreach (var (x1, y1, x2, y2) in new[] { (1.0, 1.0, 7.0, 7.0), (7.0, 1.0, 1.0, 7.0) })
        {
            glyph.Children.Add(new Line
            {
                X1 = x1, Y1 = y1, X2 = x2, Y2 = y2,
                Stroke = new SolidColorBrush(Palette.Berry2),
                StrokeThickness = 1.6,
                StrokeStartLineCap = PenLineCap.Round,
                StrokeEndLineCap = PenLineCap.Round,
            });
        }
        return glyph;
    }

    [DllImport("user32.dll")]
    private static extern int GetWindowLong(IntPtr hwnd, int index);

    [DllImport("user32.dll")]
    private static extern int SetWindowLong(IntPtr hwnd, int index, int newStyle);
}

/// <summary>The bundled display faces (port of the Mac's Fonts.swift registerBundled).</summary>
internal static class Fonts
{
    private static FontFamily? _display;

    /// <summary>Fredoka — the rounded display face from the design.</summary>
    public static FontFamily Display => _display ??= new FontFamily(
        new Uri("pack://application:,,,/"), "/Resources/Fredoka.ttf#Fredoka");
}
