namespace GooglyRealtime;

/// <summary>
/// Pure geometry for the PC's speech bubble (v0.3.7): the text-width clamp, the font
/// tiers and where a bubble of a given size lands — anchored over the phone, or at
/// the spot the user dragged it to. Pure and WPF-free so the shape of the math is
/// testable; the window (GooglyWindows.CaptionWindow) does the painting and the
/// mouse work.
/// </summary>
public static class CaptionGeometry
{
    /// <summary>Fixed frame around the text: cloud Margin 2×16 + Border 2×2.5 + Padding 2×24.</summary>
    public const double Chrome = 85.0;

    /// <summary>A press must wander this far (DIPs) before it counts as a drag rather than a click.</summary>
    public const double DragThreshold = 4.0;

    /// <summary>When parked at a dragged spot, at least this much of the bubble stays on
    /// screen and the close badge (top-right) stays reachable — the box moves freely,
    /// but can't be lost off the edge.</summary>
    public const double KeepVisible = 96.0;

    /// <summary>Display font sized by length, as on the Mac: 21 / 19 / 17 DIPs.</summary>
    public static double FontSizeFor(int length) => length <= 50 ? 21 : length <= 110 ? 19 : 17;

    /// <summary>The text's wrap width: max(240, √len·50), clamped to 580 and half the
    /// work area (never below 240 even on narrow screens).</summary>
    public static double TextWidthFor(int length, double workWidth) =>
        Math.Clamp(Math.Sqrt(length) * 50, 240, Math.Max(240, Math.Min(580, workWidth * 0.5)));

    /// <summary>Where a bubble of this size lands. Anchored (no drag yet): bottom-center
    /// over where the phone sits, 24 DIPs above the work area's bottom, 8 DIPs inside
    /// its sides. Pinned: the dragged spot — every bubble after the drag lands there
    /// too, clamped to stay reachable.</summary>
    public static (double Left, double Top) Place(
        double width, double height, WorkArea work, double phonePosition, Spot? pinned)
    {
        if (pinned is { } pin)
        {
            var minLeft = Math.Min(work.Left - width + KeepVisible, work.Right - KeepVisible);
            var maxLeft = Math.Max(work.Left - width + KeepVisible, work.Right - KeepVisible);
            var maxTop = Math.Max(work.Top, work.Bottom - height - 8);
            return (Math.Clamp(pin.X, minLeft, maxLeft), Math.Clamp(pin.Y, work.Top, maxTop));
        }
        var left = Math.Clamp(work.Left + phonePosition * work.Width - width / 2,
            work.Left + 8, Math.Max(work.Left + 8, work.Right - width - 8));
        return (left, work.Bottom - height - 24);
    }

    /// <summary>A screen rectangle in DIPs (Left/Top/Right/Bottom), WPF-free so this
    /// library stays testable from a plain console test project.</summary>
    public readonly record struct WorkArea(double Left, double Top, double Right, double Bottom)
    {
        public double Width => Right - Left;
        public double Height => Bottom - Top;
    }

    /// <summary>A point in DIPs, for the dragged-and-pinned bubble position.</summary>
    public readonly record struct Spot(double X, double Y);
}
