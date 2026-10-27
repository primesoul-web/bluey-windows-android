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
/// dragging moves it — the dragged spot is where every later bubble lands too. Only the
/// bubble itself takes the pointer: everything around it passes clicks straight through
/// to whatever is behind. The window never activates, so it can't steal focus.
///
/// Smooth since v0.3.8: the window is laid out once over the whole work area and NEVER
/// moves or resizes again — dragging translates the cloud inside it. v0.3.7 dragged by
/// moving the window itself (Left then Top: two native moves per mouse-move), and a
/// per-pixel-transparent window is fully re-composited at every position, so the render
/// thread fell behind the input thread and the screen kept stale copies — the box
/// "duplicated" into ghosts and tracked the cursor in stutters. A cached visual moving
/// inside a still window is pure composition: cursor-exact, ghost-free — and it is the
/// M3 shape anyway, where the bubble rides the cursor overlay the same way.
/// </summary>
public sealed class CaptionWindow : Window
{
    private const int WM_NCHITTEST = 0x0084;
    private static readonly IntPtr HTTRANSPARENT = new(-1);

    private readonly TextBlock _text;
    private readonly Border _cloud;
    private readonly Border _close;

    /// <summary>The bubble (cloud + badge) as one draggable unit, parked at the
    /// top-left of the window and moved by [_spot] — never by moving the window.</summary>
    private readonly Grid _holder;

    /// <summary>Where the bubble sits inside the work-area-sized window. The one
    /// thing a drag changes.</summary>
    private readonly TranslateTransform _spot = new();

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
        Opacity = 0;
        Visibility = Visibility.Hidden;
        // No SizeToContent and no per-caption Width: the window is fitted to the work
        // area once and stays put — moving or resizing a layered window mid-drag is
        // exactly what smeared it into ghosts (v0.3.7).

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

        _holder = new Grid
        {
            HorizontalAlignment = HorizontalAlignment.Left, // sizes to the bubble, not
            VerticalAlignment = VerticalAlignment.Top,      // to the whole work area
            RenderTransform = _spot,
            // The cloud (shadow included) is rasterized once into a cached bitmap:
            // dragging it is a compositor-side translate, not a re-render.
            CacheMode = new BitmapCache(),
        };
        _holder.Children.Add(_cloud);
        _holder.Children.Add(_close);

        var root = new Grid();
        root.Children.Add(_holder);
        Content = root;

        _cloud.MouseLeftButtonDown += OnCloudDown;
        _cloud.MouseMove += OnCloudMove;
        _cloud.MouseLeftButtonUp += OnCloudUp;
        _cloud.LostMouseCapture += (_, _) => _pressing = false;

        SourceInitialized += (_, _) =>
        {
            FitWorkArea();
            InstallPointerRules();
        };
    }

    /// <summary>Shows (or, with null, hides) the bubble. Runs on the UI thread.</summary>
    public void Show(string? text)
    {
        if (string.IsNullOrEmpty(text))
        {
            FadeOut();
            return;
        }

        FitWorkArea(); // the taskbar may have moved since the last bubble

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

        var (left, top) = CaptionGeometry.Place(
            size.Width, size.Height,
            new CaptionGeometry.WorkArea(work.Left, work.Top, work.Right, work.Bottom),
            PhonePosition, _pinned);
        _spot.X = left - work.Left; // park the bubble: the window itself never moves
        _spot.Y = top - work.Top;

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

    /// <summary>Lays the window over the work area — the only geometry the window ever
    /// gets. Cheap while hidden (between bubbles), and nothing about a visible bubble
    /// depends on it: the cloud keeps its [Place]d spot until the next [Show].</summary>
    private void FitWorkArea()
    {
        var work = SystemParameters.WorkArea;
        if (Left != work.Left || Top != work.Top ||
            Width != work.Width || Height != work.Height)
        {
            Left = work.Left;
            Top = work.Top;
            Width = work.Width;
            Height = work.Height;
        }
    }

    // MARK: Pointer rules — interactive on the bubble, invisible to clicks around it

    /// <summary>Keeps the window out of the taskbar, Alt+Tab and the focus chain, and
    /// answers WM_NCHITTEST so only the bubble itself ever takes the pointer. The
    /// window covers the whole work area, so this hook is what makes everything
    /// outside the cloud click-through.</summary>
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
        if (_dragging) return IntPtr.Zero; // the capture owns the input for now
        if (Visibility != Visibility.Visible)
        {
            handled = true;
            return HTTRANSPARENT; // nothing to hit while hidden
        }
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
            // Not rendered yet — click-through is the safe answer for an overlay,
            // never the default (which would eat the whole work area).
            handled = true;
            return HTTRANSPARENT;
        }
        return IntPtr.Zero;
    }

    /// <summary>True when the point lands on the cloud or the ✕ badge (their real
    /// shapes — a null window background keeps the rest of the window unhittable).
    /// The [RenderTransform] on the holder is part of the hit test, so this tracks
    /// the bubble wherever a drag has parked it.</summary>
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
            // The window never moves, so p - _lastPoint is the exact screen delta: the
            // bubble tracks the cursor 1:1. (v0.3.7 measured against a window that was
            // itself being moved — the reference frame shifted between samples, which
            // is what made drags lag and stutter.)
            var work = SystemParameters.WorkArea;
            var (left, top) = CaptionGeometry.ClampDrag(
                work.Left + _spot.X + p.X - _lastPoint.X,
                work.Top + _spot.Y + p.Y - _lastPoint.Y,
                _holder.RenderSize.Width,
                new CaptionGeometry.WorkArea(work.Left, work.Top, work.Right, work.Bottom));
            _spot.X = left - work.Left;
            _spot.Y = top - work.Top;
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
        if (wasDragging)
        {
            // The drop becomes the anchor: every later bubble lands here too.
            var work = SystemParameters.WorkArea;
            _pinned = new CaptionGeometry.Spot(work.Left + _spot.X, work.Top + _spot.Y);
            return;
        }
        // A click that never became a drag closes the bubble — but a release out in
        // the margins (the press left the cloud while captured) is a cancel, not a close.
        var r = e.GetPosition(_cloud);
        if (r.X >= 0 && r.Y >= 0 && r.X <= _cloud.RenderSize.Width && r.Y <= _cloud.RenderSize.Height)
        {
            FadeOut();
        }
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
