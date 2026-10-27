using GooglyRealtime;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>
/// The bubble's pure geometry (v0.3.7; drag clamping unified in v0.3.8): the wrap-width
/// clamp, the font tiers, the anchored placement over the phone and the clamping of a
/// user-dragged position — the math behind "click it away, drag it anywhere, never
/// lose it off the edge".
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

        // Parked low on purpose: where you dropped it is where the next one lands —
        // with the ✕ badge's row still reachable above the bottom (v0.3.8).
        var low = new CaptionGeometry.Spot(700, 5000);
        var (_, lowTop) = CaptionGeometry.Place(300, 120, Work, phonePosition: 0.5, pinned: low);
        Assert.Equal(1040 - 32, lowTop, 3);
    }

    [Fact]
    public void A_live_drag_clamps_at_every_edge()
    {
        // Free movement in the middle — the bubble tracks the cursor 1:1.
        var (freeLeft, freeTop) = CaptionGeometry.ClampDrag(700, 300, 300, Work);
        Assert.Equal(700, freeLeft, 3);
        Assert.Equal(300, freeTop, 3);

        // Off the left: at least KeepVisible DIPs of box stay on screen.
        var (left, _) = CaptionGeometry.ClampDrag(-5000, 400, 300, Work);
        Assert.Equal(0 - 300 + CaptionGeometry.KeepVisible, left, 3);

        // Off the right: the ✕ badge's row stays on screen.
        var (right, _) = CaptionGeometry.ClampDrag(5000, 400, 300, Work);
        Assert.Equal(1920 - CaptionGeometry.KeepVisible, right, 3);

        // Above: the top edge can't leave the work area.
        var (_, top) = CaptionGeometry.ClampDrag(700, -500, 300, Work);
        Assert.Equal(0, top, 3);

        // Below: at least the top 32 DIPs — the badge's row — stay reachable.
        var (_, bottom) = CaptionGeometry.ClampDrag(700, 5000, 300, Work);
        Assert.Equal(1040 - 32, bottom, 3);
    }

    [Fact]
    public void Drag_clamping_works_on_negative_coordinate_monitors()
    {
        // A second monitor left of the primary: its work area starts below zero.
        var leftMonitor = new CaptionGeometry.WorkArea(-1920, 0, 0, 1040);
        var (left, top) = CaptionGeometry.ClampDrag(-900, 300, 300, leftMonitor);
        Assert.Equal(-900, left, 3);
        Assert.Equal(300, top, 3);

        var (clampedLeft, _) = CaptionGeometry.ClampDrag(-9999, 300, 300, leftMonitor);
        Assert.Equal(-1920 - 300 + CaptionGeometry.KeepVisible, clampedLeft, 3);
    }

    [Fact]
    public void A_work_area_narrower_than_two_keep_visibles_still_clamps()
    {
        // min-left would exceed max-left on a tiny work area — the Math.Min/Max
        // guards keep Math.Clamp from throwing (the v0.3.6 hold-timer lesson).
        var tiny = new CaptionGeometry.WorkArea(0, 0, 100, 400);
        var (left, _) = CaptionGeometry.ClampDrag(50, 100, 300, tiny);
        Assert.True(left >= -300 + CaptionGeometry.KeepVisible - 0.001);
        Assert.True(left <= 100 - CaptionGeometry.KeepVisible + 0.001);
    }

    [Fact]
    public void A_dropped_spot_and_a_pinned_bubble_obey_the_same_rule()
    {
        // Where you release the drag is exactly where the next bubble lands —
        // the live clamp and the pinned placement are one function (v0.3.8).
        foreach (var pin in new[]
                 {
                     new CaptionGeometry.Spot(700, 300),
                     new CaptionGeometry.Spot(-5000, 400),
                     new CaptionGeometry.Spot(5000, 400),
                     new CaptionGeometry.Spot(700, 5000),
                 })
        {
            var placed = CaptionGeometry.Place(300, 120, Work, phonePosition: 0.5, pinned: pin);
            var dragged = CaptionGeometry.ClampDrag(pin.X, pin.Y, 300, Work);
            Assert.Equal(dragged.Left, placed.Left, 3);
            Assert.Equal(dragged.Top, placed.Top, 3);
        }
    }

    [Fact]
    public void Chrome_covers_the_full_fixed_frame_around_the_text()
    {
        // cloud Margin 2×16 + Border 2×2.5 + Padding 2×24 — the window must be wide
        // enough for the measured wrap plus all of it, or the text re-wraps.
        Assert.Equal(16 * 2 + 2.5 * 2 + 24 * 2, CaptionGeometry.Chrome, 3);
    }
}
