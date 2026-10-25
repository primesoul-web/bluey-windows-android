using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using GooglyRealtime;

namespace GooglyWindows;

/// <summary>
/// His speech bubble on the PC. M2 stand-in: from M3 on the bubble lives on the cursor
/// overlay (beside his cursor, or above the pointed-at thing); until then it floats just
/// above where the phone sits under the screen. The bubble's own look is the final one:
/// white→#EEF0FF gradient cloud, berry2 border, display font sized by length
/// (21/19/17 for ≤50/≤110/more words' worth of characters), width
/// max(240, √len·50) clamped to 580 and half the screen, (24,15) padding.
/// </summary>
public sealed class CaptionWindow : Window
{
    private const double Gw = 96.0; // design DIPs per inch (matches the Mac's point space)

    /// <summary>Fixed frame around the text: cloud Margin 2×6 + Border 2×2.5 + Padding 2×24.</summary>
    private const double Chrome = 65.0;

    private readonly TextBlock _text;
    private readonly Border _cloud;

    /// <summary>0 = left edge of the screen, 1 = right (where the phone sits).</summary>
    public double PhonePosition { get; set; } = 0.5;

    public CaptionWindow()
    {
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        ShowInTaskbar = false;
        ShowActivated = false;
        Topmost = true;
        IsHitTestVisible = false;
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
            Margin = new Thickness(6), // room for the drop shadow
            Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                Color = Color.FromArgb(0x80, 0, 0, 0),
                BlurRadius = 18,
                ShadowDepth = 4,
                Opacity = 0.5,
            },
        };

        Content = _cloud;
        SourceInitialized += (_, _) => MakeClickThrough();
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
        _text.FontSize = length <= 50 ? 21 : length <= 110 ? 19 : 17;

        var work = SystemParameters.WorkArea;
        var width = Math.Clamp(
            Math.Sqrt(length) * 50,
            240,
            Math.Max(240, Math.Min(580, work.Width * 0.5)));
        _text.MaxWidth = width;

        _text.Text = text;

        // Measure under the same finite width the final layout will use, so the wrap we
        // measure is the wrap we render (v0.3.6). The old dance — Measure with infinite
        // width, then Arrange + UpdateLayout — re-ran layout from the window root at the
        // window's STALE width (NaN before the first caption ever laid out), which
        // re-wrapped the text at ~0 px: DesiredSize came back one character wide and
        // dozens of lines tall, Width locked to that, and every caption after rendered
        // as a vertical letter-stack in the middle of the screen. Invisible until M2.14
        // because no caption had ever arrived from a phone before it.
        _cloud.Measure(new Size(width + Chrome, double.PositiveInfinity));
        var size = _cloud.DesiredSize;

        Width = size.Width;
        Left = Math.Clamp(work.Left + PhonePosition * work.Width - size.Width / 2,
            work.Left + 8, Math.Max(work.Left + 8, work.Right - size.Width - 8));
        Top = work.Bottom - size.Height - 24;

        Visibility = Visibility.Visible;
        Opacity = 0;

        var fadeIn = new DoubleAnimation(0, 1, TimeSpan.FromSeconds(0.12)) { EasingFunction = null };
        BeginAnimation(OpacityProperty, fadeIn);
    }

    private void FadeOut()
    {
        if (Visibility != Visibility.Visible) return;
        var fade = new DoubleAnimation(0, TimeSpan.FromSeconds(0.22));
        fade.Completed += (_, _) => Visibility = Visibility.Hidden;
        BeginAnimation(OpacityProperty, fade);
    }

    private void MakeClickThrough()
    {
        // WS_EX_TRANSPARENT | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW: the bubble never
        // steals the pointer or the focus — same deal as the M3 overlay it will join.
        var hwnd = new WindowInteropHelper(this).Handle;
        const int gwlExStyle = -20;
        const int wsExTransparent = 0x00000020;
        const int wsExToolWindow = 0x00000080;
        const int wsExNoActivate = 0x08000000;
        _ = SetWindowLong(hwnd, gwlExStyle,
            GetWindowLong(hwnd, gwlExStyle) | wsExTransparent | wsExToolWindow | wsExNoActivate);
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
