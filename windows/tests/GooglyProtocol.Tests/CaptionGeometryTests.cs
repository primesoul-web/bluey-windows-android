using GooglyRealtime;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>
/// The bubble's pure geometry (v0.3.7): the wrap-width clamp, the font tiers, the
/// anchored placement over the phone and the clamping of a user-dragged position —
/// the math behind "click it away, drag it anywhere, never lose it off the edge".
/// </summary>
public class CaptionGeometryTests
{
    private static readonly CaptionGeometry.WorkArea Work =
        new(0, 0, 1920, 1040); // a typical 1920×1080 screen minus the taskbar

    [Fact]
    public void Font_sizes_step_down_as_replies_get_longer()
    {
        Assert.Equal(21, CaptionGeometry.FontSizeFor(0));
        Assert.Equal(21, CaptionGeometry.FontSizeFor(50));
        Assert.Equal(19, CaptionGeometry.FontSizeFor(51));
        Assert.Equal(19, CaptionGeometry.FontSizeFor(110));
        Assert.Equal(17, CaptionGeometry.FontSizeFor(111));
        Assert.Equal(17, CaptionGeometry.FontSizeFor(4000));
    }

    [Fact]
    public void Text_width_grows_with_the_square_root_and_clamps()
    {
        Assert.Equal(240, CaptionGeometry.TextWidthFor(1, Work.Width)); // the floor
        Assert.Equal(240, CaptionGeometry.TextWidthFor(16, Work.Width)); // √16·50 = 200 < floor
        Assert.Equal(500, CaptionGeometry.TextWidthFor(100, Work.Width)); // √100·50
        Assert.Equal(580, CaptionGeometry.TextWidthFor(400, Work.Width)); // the cap
        // A narrow screen clamps to half the work area, but never below the floor.
        Assert.Equal(240, CaptionGeometry.TextWidthFor(400, 300));
        Assert.Equal(250, CaptionGeometry.TextWidthFor(400, 500));
    }

    [Fact]
    public void Anchored_bubble_sits_bottom_center_over_the_phone()
    {
        var (left, top) = CaptionGeometry.Place(300, 120, Work, phonePosition: 0.5, pinned: null);
        Assert.Equal(1920 / 2.0 - 150, left, 3);
        Assert.Equal(1040 - 120 - 24, top, 3);

        var (phoneLeft, _) = CaptionGeometry.Place(300, 120, Work, phonePosition: 0.0, pinned: null);
        Assert.Equal(8, phoneLeft, 3); // clamped inside the left edge
    }

    [Fact]
    public void A_wide_anchored_bubble_still_fits_the_screen()
    {
        var (left, _) = CaptionGeometry.Place(600, 120, Work, phonePosition: 1.0, pinned: null);
        Assert.Equal(1920 - 600 - 8, left, 3); // clamped inside the right edge
    }

    [Fact]
    public void A_dragged_bubble_lands_where_it_was_pinned()
    {
        var pin = new CaptionGeometry.Spot(700, 300);
        var (left, top) = CaptionGeometry.Place(300, 120, Work, phonePosition: 0.5, pinned: pin);
        Assert.Equal(700, left, 3);
        Assert.Equal(300, top, 3);
    }

    [Fact]
    public void A_pinned_bubble_cannot_be_lost_off_the_left_edge()
    {
        var pin = new CaptionGeometry.Spot(-5000, 400);
        var (left, _) = CaptionGeometry.Place(300, 120, Work, phonePosition: 0.5, pinned: pin);
        Assert.Equal(0 - 300 + CaptionGeometry.KeepVisible, left, 3); // 96 px still visible
    }

    [Fact]
    public void A_pinned_bubble_keeps_its_close_badge_reachable_on_the_right()
    {
        var pin = new CaptionGeometry.Spot(5000, 400);
        var (left, _) = CaptionGeometry.Place(300, 120, Work, phonePosition: 0.5, pinned: pin);
        Assert.Equal(1920 - CaptionGeometry.KeepVisible, left, 3); // the ✕ stays on screen
    }

    [Fact]
    public void A_pinned_bubble_stays_inside_the_top_of_the_work_area()
    {
        var pin = new CaptionGeometry.Spot(700, -500);
        var (_, top) = CaptionGeometry.Place(300, 120, Work, phonePosition: 0.5, pinned: pin);
        Assert.Equal(0, top, 3);

        var low = new CaptionGeometry.Spot(700, 5000);
        var (_, lowTop) = CaptionGeometry.Place(300, 120, Work, phonePosition: 0.5, pinned: low);
        Assert.Equal(1040 - 120 - 8, lowTop, 3); // bottom margin, not off the work area
    }

    [Fact]
    public void Chrome_covers_the_full_fixed_frame_around_the_text()
    {
        // cloud Margin 2×16 + Border 2×2.5 + Padding 2×24 — the window must be wide
        // enough for the measured wrap plus all of it, or the text re-wraps.
        Assert.Equal(16 * 2 + 2.5 * 2 + 24 * 2, CaptionGeometry.Chrome, 3);
    }
}
