using GooglyRealtime;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>
/// The cursor engine's pure math (M3.1): the CSS-style timing curves, the home
/// geometry, the flight planner's duration/bow, the attitude spring, the blink
/// schedule, the press squish, the hover ease-in, the clock clamp and the
/// phone-face computation — the numbers behind "he sits, follows, looks and blinks".
/// </summary>
public class CursorMathTests
{
    /// <summary>An engine driven on a persistent clock, so Face/Hover read the same
    /// "now" the frames advanced.</summary>
    private sealed class Rig
    {
        public CursorEngine Engine;
        public double Now = 100.0;
        public Pt Mouse = new(960, 500);
        public readonly Sz Bounds = new(1920, 1080);

        public Rig(CursorEngine? engine = null) => Engine = engine ?? new CursorEngine();

        public void Step(int frames, double dt = 1.0 / 60, Pt? mouse = null)
        {
            if (mouse is { } m) Mouse = m;
            for (var i = 0; i < frames; i++)
            {
                Now += dt;
                Engine.Step(dt, Now, Mouse, Bounds);
            }
        }
    }

    // MARK: Timing curves

    [Fact]
    public void Timing_curves_start_at_zero_and_finish_at_one()
    {
        foreach (var curve in new[] { TimingCurve.Launch, TimingCurve.Redirect, TimingCurve.Steady })
        {
            Assert.Equal(0, curve.Value(-0.5), 9);
            Assert.Equal(0, curve.Value(0), 9);
            Assert.Equal(1, curve.Value(1), 9);
            Assert.Equal(1, curve.Value(1.5), 9);
        }
    }

    [Fact]
    public void Timing_curves_are_monotonic_with_finite_nonnegative_rates()
    {
        foreach (var curve in new[] { TimingCurve.Launch, TimingCurve.Redirect, TimingCurve.Steady })
        {
            var previous = 0.0;
            for (var x = 0.0; x <= 1.0001; x += 0.05)
            {
                var clamped = Math.Min(x, 1);
                var v = curve.Value(clamped);
                Assert.True(v >= previous - 1e-9, "curve not monotonic");
                Assert.True(double.IsFinite(curve.Rate(clamped)), $"rate not finite at {x}");
                Assert.True(curve.Rate(clamped) >= -1e-9, $"negative rate at {x}");
                previous = v;
            }
        }
    }

    [Fact]
    public void Launch_eases_in_like_the_original()
    {
        // (0.42, 0, 0.22, 1): slow start, long soft landing. At a tenth of the time
        // it has barely moved; the middle of the trip is where it pours on speed.
        Assert.True(TimingCurve.Launch.Value(0.1) < 0.05, $"got {TimingCurve.Launch.Value(0.1)}");
        Assert.True(TimingCurve.Launch.Value(0.25) < 0.3, $"got {TimingCurve.Launch.Value(0.25)}");
        Assert.True(TimingCurve.Launch.Value(0.5) is > 0.3 and < 0.9);
        Assert.True(TimingCurve.Launch.Rate(0.05) < TimingCurve.Launch.Rate(0.5));
        // It is also symmetric enough to settle: the speed at the very end is low again.
        Assert.True(TimingCurve.Launch.Rate(0.99) < TimingCurve.Launch.Rate(0.5));
    }

    [Fact]
    public void Redirect_carries_speed_from_the_start()
    {
        // (0.25, 0.25, 0.22, 1): unlike Launch, it is already moving at t=0.
        Assert.True(TimingCurve.Redirect.Rate(0.01) > 0.5);
        Assert.True(TimingCurve.Launch.Rate(0.01) < 0.5);
    }

    // MARK: Clock discipline

    [Fact]
    public void Dt_is_clamped_to_the_originals_window()
    {
        Assert.Equal(1.0 / 480, CursorClock.ClampDt(0.0001), 9);
        Assert.Equal(1.0 / 480, CursorClock.ClampDt(1.0 / 480), 9);
        Assert.Equal(1.0 / 60, CursorClock.ClampDt(1.0 / 60), 9);
        Assert.Equal(1.0 / 20, CursorClock.ClampDt(5), 9);
    }

    // MARK: Home geometry

    [Fact]
    public void Dock_tucked_and_phone_points_follow_the_phone_position()
    {
        var engine = new CursorEngine { Size = 96, PhonePosition = 0.5 };
        var b = new Sz(1920, 1080);
        Assert.Equal(960, engine.PhonePoint(b).X, 6);
        Assert.Equal(1080 + 1080 * 0.18, engine.PhonePoint(b).Y, 6); // below the bottom edge
        Assert.Equal(1080 - 96 * 0.42, engine.DockPoint(b).Y, 6); // peeking above the edge
        Assert.Equal(1080 + 96 * 0.35, engine.TuckedPoint(b).Y, 6); // hidden below it

        engine.PhonePosition = 0; // Phone Sits Under: Left
        Assert.Equal(0, engine.DockPoint(b).X, 6);
        engine.PhonePosition = 1; // Right
        Assert.Equal(1920, engine.DockPoint(b).X, 6);
    }

    // MARK: Flight planning

    /// <summary>Duration of a flight whose length is exactly <paramref name="distance"/>:
    /// land somewhere first, then pin to a spot that far away and read the fresh plan.</summary>
    private static double FlightDurationFor(double distance, bool dragging = false)
    {
        var rig = new Rig(new CursorEngine { Size = 72, PhonePosition = 0.5, Dragging = dragging });
        rig.Step(1); // places at tucked
        rig.Engine.SetMode(CursorMode.Pinned(960, 900));
        rig.Step(300); // land at the start spot
        rig.Engine.SetMode(CursorMode.Pinned(960, 900 - distance));
        rig.Step(1); // plans the trip; the flight starts on this very frame
        return rig.Engine.TimeToArrive(rig.Now);
    }

    [Fact]
    public void Duration_grows_logarithmically_then_clamps()
    {
        // clamp(0.45, 1.2, 0.4 + 0.12·log2(1 + d/24)); a 0-length plan is instantaneous.
        Assert.Equal(0, FlightDurationFor(0), 6);
        Assert.Equal(0.45, FlightDurationFor(1), 6); // the floor wins for short hops
        Assert.Equal(0.4 + 0.12 * Math.Log2(1 + 12.0 / 24), FlightDurationFor(12), 6);
        Assert.Equal(0.4 + 0.12 * Math.Log2(1 + 240.0 / 24), FlightDurationFor(240), 6);
        Assert.Equal(1.2, FlightDurationFor(100000), 6); // the cap
    }

    [Fact]
    public void Drag_trips_are_slower_and_capped_lower_than_free_flights()
    {
        Assert.True(FlightDurationFor(240, dragging: true) > FlightDurationFor(240));
        Assert.Equal(1.6, FlightDurationFor(100000, dragging: true), 6); // drag cap
        // The original's 0.6 drag floor is shadowed: free duration is clamped to
        // ≥ 0.45 BEFORE the ×1.45, so the shortest possible drag is 0.45·1.45.
        Assert.Equal(0.45 * 1.45, FlightDurationFor(1, dragging: true), 6);
    }

    [Fact]
    public void Sideway_trips_bow_gently_upward_and_vertical_ones_toward_the_middle()
    {
        // A horizontal trip: the tip leaves the straight line upward (y is down).
        var up = MaxSignedDeviation(400, 500, 1400, 500);
        Assert.True(up < -10, $"sideways bow should lift off, got {up}");

        // A vertical trip on the left half bows toward the middle (to the right).
        var right = MaxSignedDeviation(200, 200, 200, 800, axisX: true);
        Assert.True(right > 10, $"left-half vertical bow should head middle-ward, got {right}");

        // On the right half it bows left.
        var left = MaxSignedDeviation(1700, 200, 1700, 800, axisX: true);
        Assert.True(left < -10, $"right-half vertical bow should head middle-ward, got {left}");
    }

    [Fact]
    public void Drags_go_straight_with_no_bow()
    {
        var deviation = MaxSignedDeviation(400, 500, 1400, 500, dragging: true);
        Assert.True(Math.Abs(deviation) < 1, $"drag should be a straight line, got {deviation}");
    }

    /// <summary>Flies start→goal and returns the signed perpendicular deviation from
    /// the chord that the tip reaches (negative y = up; x when <paramref name="axisX"/>).</summary>
    private static double MaxSignedDeviation(double x0, double y0, double x1, double y1,
        bool dragging = false, bool axisX = false)
    {
        var rig = new Rig(new CursorEngine { Size = 72, PhonePosition = 0.5, Dragging = dragging });
        var start = new Pt(x0, y0);
        var goal = new Pt(x1, y1);
        rig.Step(1);
        rig.Engine.SetMode(CursorMode.Pinned(start.X, start.Y));
        rig.Step(300); // land at the start
        rig.Engine.SetMode(CursorMode.Pinned(goal.X, goal.Y));

        var best = 0.0;
        var chord = goal - start;
        var length = Math.Max(chord.Length, 1);
        for (var i = 0; i < 400; i++)
        {
            rig.Step(1);
            var along = ((rig.Engine.Tip.X - start.X) * chord.X + (rig.Engine.Tip.Y - start.Y) * chord.Y) / length;
            var proj = new Pt(start.X + chord.X * along / length, start.Y + chord.Y * along / length);
            var off = rig.Engine.Tip - proj;
            var signed = axisX ? off.X : off.Y;
            if (Math.Abs(signed) > Math.Abs(best)) best = signed;
            if (rig.Engine.TimeToArrive(rig.Now) <= 0) break;
        }
        return best;
    }

    [Fact]
    public void Pinned_flights_land_and_report_the_spot()
    {
        var rig = new Rig { Engine = new CursorEngine { Size = 72, PhonePosition = 0.5 } };
        rig.Step(1);
        rig.Engine.SetMode(CursorMode.Pinned(300, 300));
        Pt? landed = null;
        rig.Engine.OnLand += p => landed = p;
        for (var i = 0; i < 300 && landed is null; i++) rig.Step(1);
        Assert.NotNull(landed);
        Assert.Equal(300, landed!.Value.X, 1);
        Assert.Equal(300, landed.Value.Y, 1);
        Assert.Equal(rig.Engine.Tip, landed.Value);
    }

    // MARK: Opacity, attitude, hover

    [Fact]
    public void Docked_fades_in_and_following_fades_out_once_tucked()
    {
        var rig = new Rig { Engine = new CursorEngine { Size = 72, PhonePosition = 0.5 } };
        rig.Engine.SetMode(CursorMode.Docked);
        rig.Step(240); // 4 s
        Assert.True(rig.Engine.Opacity > 0.95, $"docked should be visible, got {rig.Engine.Opacity}");

        rig.Engine.SetMode(CursorMode.Following);
        rig.Step(240);
        Assert.True(rig.Engine.Opacity < 0.05, $"tucked should fade out, got {rig.Engine.Opacity}");
    }

    [Fact]
    public void The_attitude_spring_settles_tipped_up_at_home_and_upright_when_pinned()
    {
        var rig = new Rig { Engine = new CursorEngine { Size = 72, PhonePosition = 0.5 } };
        rig.Engine.SetMode(CursorMode.Docked);
        rig.Step(240);
        Assert.Equal(Math.PI / 4, rig.Engine.Angle, 2); // parked: 45° like a real cursor

        rig.Engine.SetMode(CursorMode.Pinned(960, 500));
        rig.Step(300);
        Assert.Equal(0, rig.Engine.Angle, 2); // out and about: upright
    }

    [Fact]
    public void Hover_eases_in_only_after_a_pinned_landing_and_never_exceeds_the_bob()
    {
        var rig = new Rig { Engine = new CursorEngine { Size = 72, PhonePosition = 0.5 } };
        rig.Engine.SetMode(CursorMode.Docked);
        rig.Step(60);
        Assert.Equal(0, rig.Engine.Hover(rig.Now), 6); // home never hovers

        rig.Engine.SetMode(CursorMode.Pinned(960, 500));
        rig.Step(120); // lands
        // Before the 0.35 s delay it stays at zero (the ease-in never jumps).
        var landedAt = rig.Engine.LandedAt;
        Assert.True(landedAt > 0, "the flight should have landed");
        Assert.Equal(0, rig.Engine.Hover(landedAt + 0.1), 6);
        // After that it eases up to its full ±1.6 amplitude and never beyond.
        for (var i = 0; i < 200; i++)
        {
            rig.Step(3);
            Assert.True(Math.Abs(rig.Engine.Hover(rig.Now)) <= 1.6 + 1e-9,
                $"hover exceeded the bob: {rig.Engine.Hover(rig.Now)}");
        }
    }

    // MARK: Blink + press

    [Fact]
    public void Blinks_are_short_and_the_gap_between_them_is_2_5_to_6_seconds()
    {
        var random = new Random(1234); // seeded: deterministic gaps
        var rig = new Rig(new CursorEngine(random) { Size = 72, PhonePosition = 0.5 });
        rig.Engine.SetMode(CursorMode.Docked);
        var gaps = new List<double>();
        var lastFire = double.NaN;
        var previousBlink = rig.Engine.BlinkUntil;
        for (var i = 0; i < 60 * 60; i++) // a minute of frames
        {
            rig.Step(1, mouse: new Pt(1 + i % 2, 1)); // keep the mouse alive
            if (rig.Engine.BlinkUntil != previousBlink)
            {
                if (!double.IsNaN(lastFire)) gaps.Add(rig.Now - lastFire);
                lastFire = rig.Now;
                previousBlink = rig.Engine.BlinkUntil;
            }
        }
        Assert.True(gaps.Count >= 8, $"a minute should blink a dozen times, got {gaps.Count}");
        Assert.All(gaps, g => Assert.True(g >= 2.5 - 0.1 && g <= 6.0 + 0.1, $"gap {g} out of range"));
    }

    [Fact]
    public void The_press_squish_dips_then_springs_back()
    {
        var engine = new CursorEngine { Size = 72, PhonePosition = 0.5 };
        engine.Press(0.14, 100.0);
        Assert.Equal(1, engine.PressScale(99.9), 6); // not yet
        Assert.Equal(1 - 0.14 * Math.Sin(0.06 / 0.12 * Math.PI / 2), engine.PressScale(100.06), 6);
        Assert.True(engine.PressScale(100.06) < engine.PressScale(100.001)); // diving in
        var min = double.MaxValue;
        for (var t = 0.0; t < 0.42; t += 0.01) min = Math.Min(min, engine.PressScale(100 + t));
        Assert.True(min < 1 - 0.1, $"the dip should be deep, got {min}");
        Assert.Equal(1, engine.PressScale(100.43), 6); // settled back
    }

    // MARK: The phone's face

    [Fact]
    public void The_face_tracks_your_mouse_from_the_phone_while_tucked()
    {
        var rig = new Rig { Engine = new CursorEngine { Size = 72, PhonePosition = 0.5 } };
        rig.Engine.SetMode(CursorMode.Following);
        rig.Step(30, mouse: new Pt(0, 0)); // look hard left
        var face = rig.Engine.Face(rig.Bounds, rig.Now);
        Assert.True(face.GazeX < -0.9);
        Assert.True(face.GazeY <= -0.15 + 1e-9, "tucked eyes always look up at the screen");

        rig.Step(5, mouse: new Pt(1920, 0)); // hard right
        face = rig.Engine.Face(rig.Bounds, rig.Now);
        Assert.True(face.GazeX > 0.9);
    }

    [Fact]
    public void He_gets_drowsy_when_your_mouse_sits_still_and_never_while_awake()
    {
        var rig = new Rig { Engine = new CursorEngine { Size = 72, PhonePosition = 0.5 } };
        rig.Engine.SetMode(CursorMode.Following);
        var still = new Pt(960, 500);
        rig.Step(6 * 60, mouse: still); // 6 s idle
        Assert.Equal(CursorMood.Sleepy, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
        rig.Step(12 * 60, mouse: still); // 18 s idle total
        Assert.Equal(CursorMood.Resting, rig.Engine.Face(rig.Bounds, rig.Now).Mood);

        rig.Engine.Awake = true;
        Assert.Equal(CursorMood.Listening, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
    }

    [Fact]
    public void A_still_mouse_keeps_him_asleep_through_a_full_minute()
    {
        // The M3.1 hardware report: with Eyes Follow My Mouse on, he dozed off after
        // 15 s of stillness and then "woke up by himself, and the sleep cycle never
        // repeated". The engine math can never do that — pinned here over a full
        // minute of simulated time with the mouse parked: drowsy by 5 s, resting by
        // 15 s, and resting for the whole rest of the minute, with no frame, jitter
        // or clock trick flipping him back. (The real culprit was WPF's render loop
        // stalling once the overlay settled, which stopped the face packets — fixed
        // by the overlay's heartbeat backstop, not by the engine.)
        var rig = new Rig { Engine = new CursorEngine { Size = 72, PhonePosition = 0.5 } };
        rig.Engine.SetMode(CursorMode.Following);
        var still = new Pt(960, 500);
        rig.Step(60, mouse: still);
        Assert.Equal(CursorMood.Listening, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
        rig.Step(5 * 60, mouse: still); // 6 s idle: drowsy
        Assert.Equal(CursorMood.Sleepy, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
        rig.Step(10 * 60, mouse: still); // 16 s idle: dozed off
        Assert.Equal(CursorMood.Resting, rig.Engine.Face(rig.Bounds, rig.Now).Mood);

        // The long haul: 60 s total of stillness, sampled every half-second — he
        // must still be resting at every single sample. No spontaneous wake exists.
        for (var i = 0; i < 88; i++)
        {
            rig.Step(30, mouse: still); // 0.5 s per sample
            Assert.Equal(CursorMood.Resting, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
        }
        Assert.True(rig.Now >= 60.0, "the simulated minute must actually elapse");

        // And the wake is honest: a real mouse move beyond the 1.5 pt jitter
        // threshold brings him straight back to listening.
        rig.Step(10, mouse: new Pt(1200, 500));
        Assert.Equal(CursorMood.Listening, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
        // ...but a sub-threshold jitter does not (no phantom wakes from rounding).
        rig.Step(10, mouse: new Pt(1200.7, 500.7));
        Assert.Equal(CursorMood.Listening, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
        rig.Step(10 * 60, mouse: new Pt(1200.7, 500.7)); // parked again 10 s
        Assert.Equal(CursorMood.Sleepy, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
    }

    [Fact]
    public void Pinned_upgrades_listening_to_pointing_and_gaze_can_be_overridden()
    {
        var rig = new Rig { Engine = new CursorEngine { Size = 72, PhonePosition = 0.5 } };
        rig.Engine.SetMode(CursorMode.Pinned(960, 300));
        rig.Step(120);
        Assert.Equal(CursorMood.Pointing, rig.Engine.Face(rig.Bounds, rig.Now).Mood);

        rig.Engine.GazeOverride = (0.5, -0.5); // "look at me", in face units
        var face = rig.Engine.Face(rig.Bounds, rig.Now);
        Assert.Equal(0.5, face.GazeX, 6);
        Assert.Equal(-0.5, face.GazeY, 6);
        rig.Engine.GazeOverride = null;
    }
}
