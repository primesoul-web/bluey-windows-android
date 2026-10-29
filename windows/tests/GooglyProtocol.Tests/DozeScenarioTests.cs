using GooglyRealtime;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>
/// The stillness scenarios behind the v0.4.1 hardware report, at the level the
/// test host can reach: the engine plus the awake flag the phone's packets drive
/// (RealtimeHost.SetAwake → App.UpdateTray → Engine.Awake). The report: with Eyes
/// Follow My Mouse on and the mouse untouched for 60 s, no drowsiness and no sleep —
/// while the phone held a live session (a stream of "awake" packets in the log).
/// These tests pin both halves: a live session holds the cursor awake by design
/// (parity with the original — "True while he's awake and talking with you (no
/// dozing off then)"), and a sleeping phone (double-tap → "asleep") lets the exact
/// original timing through — drowsy at 5 s, resting at 15 s, resting for the whole
/// minute — under both frame cadences the overlay can run at (the vsync loop and
/// the 100 ms heartbeat backstop that drives it when the loop stalls).
/// </summary>
public class DozeScenarioTests
{
    private sealed class Rig
    {
        public CursorEngine Engine;
        public double Now = 100.0;
        public readonly Sz Bounds = new(1920, 1080);

        public Rig(CursorEngine? engine = null) => Engine = engine ?? new CursorEngine();

        public void Step(int frames, double dt = 1.0 / 60, Pt? mouse = null)
        {
            for (var i = 0; i < frames; i++)
            {
                Now += dt;
                Engine.Step(dt, Now, mouse ?? new Pt(960, 500), Bounds);
            }
        }
    }

    [Fact]
    public void A_live_session_holds_the_cursor_awake_through_the_stillness_test()
    {
        // The exact v0.4.1 observation: the phone's live session keeps
        // Engine.Awake true (every "awake" packet re-asserts it), so the same
        // stillness that dozes him in follow mode must show a listening face —
        // by design, like the original. Not a bug: the fix is on the phone
        // (double-tap him to sleep), not in the engine.
        var rig = new Rig { Engine = new CursorEngine { Size = 72, PhonePosition = 0.5 } };
        rig.Engine.SetMode(CursorMode.Following);
        rig.Engine.Awake = true; // what UpdateTray copies while host.Awake is true
        var still = new Pt(960, 500);
        for (var i = 0; i < 120; i++)
        {
            rig.Step(30, mouse: still); // 0.5 s per sample
            if (i % 10 == 0) rig.Engine.Awake = true; // the "awake" packet flood
            Assert.Equal(CursorMood.Listening, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
        }
        Assert.True(rig.Now >= 60.0, "the simulated minute must actually elapse");
    }

    [Fact]
    public void A_sleeping_phone_lets_him_doze_on_the_original_schedule_and_stay_down()
    {
        // The asked-for case: phone asleep (double-tap → "asleep" →
        // SetAwake(false) → Engine.Awake = false), then the 60-second stillness
        // test with the mouse untouched. Drowsy by 5 s, resting by 15 s, resting
        // at every sample through the whole minute, and an honest wake on a real
        // move afterwards.
        var rig = new Rig { Engine = new CursorEngine { Size = 72, PhonePosition = 0.5 } };
        rig.Engine.SetMode(CursorMode.Following);
        rig.Engine.Awake = false; // what UpdateTray copies after the "asleep" packet
        var still = new Pt(960, 500);

        rig.Step(60, mouse: still); // 1 s: wide awake
        Assert.Equal(CursorMood.Listening, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
        rig.Step(5 * 60, mouse: still); // 6 s idle: drowsy
        Assert.Equal(CursorMood.Sleepy, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
        rig.Step(10 * 60, mouse: still); // 16 s idle: dozed off
        Assert.Equal(CursorMood.Resting, rig.Engine.Face(rig.Bounds, rig.Now).Mood);

        // The full minute, sampled every half-second: resting at every single one.
        for (var i = 0; i < 88; i++)
        {
            rig.Step(30, mouse: still);
            Assert.Equal(CursorMood.Resting, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
        }
        Assert.True(rig.Now >= 60.0, "the simulated minute must actually elapse");

        // The wake is honest: a real move beyond the 1.5 pt jitter threshold
        // brings him straight back, and a sub-threshold jitter does not.
        rig.Step(10, mouse: new Pt(1200, 500));
        Assert.Equal(CursorMood.Listening, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
        rig.Step(10, mouse: new Pt(1200.7, 500.7));
        Assert.Equal(CursorMood.Listening, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
        rig.Step(6 * 60, mouse: new Pt(1200.7, 500.7)); // parked again 6 s
        Assert.Equal(CursorMood.Sleepy, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
    }

    [Fact]
    public void The_heartbeat_cadence_dozes_identically_to_the_vsync_loop()
    {
        // The v0.4.1 heartbeat backstop drives frames at ~10 Hz when the vsync
        // loop stalls (a settled overlay invalidates nothing). Dozing must not
        // care: the idle clock is wall time, not a frame count, and a still
        // mouse at either cadence stamps nothing. This is the "did the
        // heartbeat break the sleep cycle" check — it can't.
        var rig = new Rig { Engine = new CursorEngine { Size = 72, PhonePosition = 0.5 } };
        rig.Engine.SetMode(CursorMode.Following);
        rig.Engine.Awake = false;
        var still = new Pt(960, 500);

        // Heartbeat cadence: a 100 ms step with the dt the overlay would clamp
        // it to (CursorClock.ClampDt(0.1) = 1/20).
        var heartbeatDt = CursorClock.ClampDt(0.1);
        Assert.Equal(1.0 / 20, heartbeatDt, 9);

        void Beat(int count)
        {
            for (var i = 0; i < count; i++)
            {
                rig.Now += heartbeatDt;
                rig.Engine.Step(heartbeatDt, rig.Now, still, rig.Bounds);
            }
        }

        Beat(10); // 0.5 s of beats (the first one stamps the idle clock)
        Assert.Equal(CursorMood.Listening, rig.Engine.Face(rig.Bounds, rig.Now).Mood);

        Beat(110); // 6 s idle total: drowsy
        Assert.Equal(CursorMood.Sleepy, rig.Engine.Face(rig.Bounds, rig.Now).Mood);

        Beat(200); // 16 s idle total: dozed off
        Assert.Equal(CursorMood.Resting, rig.Engine.Face(rig.Bounds, rig.Now).Mood);

        // And the whole minute at heartbeat cadence stays down.
        Beat(880); // 60 s idle total
        Assert.Equal(CursorMood.Resting, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
        Assert.True(rig.Now >= 160.0, "the simulated minute must actually elapse");
    }

    [Fact]
    public void A_mouse_parked_at_the_exact_origin_still_dozes()
    {
        // The parity gap this fix closes: the original stamps lastMouseMove at
        // construction, the port left it NaN until the first real move — and a
        // mouse resting at exactly (0,0) (where _mouse starts) never moves
        // "from" anywhere, so idle stayed NaN and he could never doze. The first
        // step now stamps the clock, like the original's init.
        var rig = new Rig { Engine = new CursorEngine { Size = 72, PhonePosition = 0.5 } };
        rig.Engine.SetMode(CursorMode.Following);
        rig.Engine.Awake = false;
        var corner = new Pt(0, 0);
        rig.Step(16 * 60, mouse: corner); // 16 s parked at the exact origin
        Assert.Equal(CursorMood.Resting, rig.Engine.Face(rig.Bounds, rig.Now).Mood);
    }
}
