namespace GooglyRealtime;

/// <summary>A point in cursor space: top-left origin, y down, in points/DIPs
/// (the Swift port's <c>CGPoint</c>).</summary>
public readonly record struct Pt(double X, double Y)
{
    public static Pt operator +(Pt a, Pt b) => new(a.X + b.X, a.Y + b.Y);
    public static Pt operator -(Pt a, Pt b) => new(a.X - b.X, a.Y - b.Y);
    public static Pt operator *(Pt a, double s) => new(a.X * s, a.Y * s);
    public static Pt operator -(Pt a) => new(-a.X, -a.Y);
    public double Length => Math.Sqrt(X * X + Y * Y);
}

/// <summary>A size for screen bounds (the Swift port's <c>CGSize</c>).</summary>
public readonly record struct Sz(double Width, double Height);

/// <summary>
/// A CSS-style cubic-bezier timing curve: maps time (0…1) to progress (0…1).
/// Faithful port of <c>TimingCurve</c> in Mac/CursorOverlay.swift (Newton solve
/// with a bisection fallback, so it never divides by a flat derivative).
/// </summary>
public readonly struct TimingCurve
{
    public double X1 { get; }
    public double Y1 { get; }
    public double X2 { get; }
    public double Y2 { get; }

    public TimingCurve(double x1, double y1, double x2, double y2)
    {
        X1 = x1; Y1 = y1; X2 = x2; Y2 = y2;
    }

    /// <summary>Starts gently, then arrives with a long, soft deceleration.</summary>
    public static TimingCurve Launch => new(0.42, 0, 0.22, 1);

    /// <summary>Keeps the speed it already had (for a change of plans mid-flight),
    /// then settles the same way.</summary>
    public static TimingCurve Redirect => new(0.25, 0.25, 0.22, 1);

    /// <summary>Even and deliberate, for drags.</summary>
    public static TimingCurve Steady => new(0.42, 0, 0.38, 1);

    private static double Coordinate(double t, double a, double b)
    {
        var u = 1 - t;
        return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t;
    }

    private static double Derivative(double t, double a, double b)
    {
        var u = 1 - t;
        return 3 * u * u * a + 6 * u * t * (b - a) + 3 * t * t * (1 - b);
    }

    private double Parameter(double x)
    {
        var t = x;
        for (var i = 0; i < 8; i++)
        {
            var error = Coordinate(t, X1, X2) - x;
            if (Math.Abs(error) < 1e-7) return t;
            var d = Derivative(t, X1, X2);
            if (Math.Abs(d) < 1e-7) break;
            t -= error / d;
        }
        double low = 0, high = 1;
        t = x;
        for (var i = 0; i < 40; i++)
        {
            var value = Coordinate(t, X1, X2);
            if (Math.Abs(value - x) < 1e-7) break;
            if (value < x) low = t; else high = t;
            t = (low + high) / 2;
        }
        return t;
    }

    /// <summary>Progress at time <paramref name="x"/> (clamped to 0…1 outside).</summary>
    public double Value(double x)
    {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        return Coordinate(Parameter(x), Y1, Y2);
    }

    /// <summary>How fast progress changes with time at <paramref name="x"/>.</summary>
    public double Rate(double x)
    {
        var t = Parameter(Math.Clamp(x, 0, 1));
        var dx = Derivative(t, X1, X2);
        var dy = Derivative(t, Y1, Y2);
        return dx > 1e-7 ? dy / dx : 0;
    }
}

/// <summary>Where the big character cursor wants to be. Port of <c>CursorMode</c>.</summary>
public enum CursorModeKind
{
    /// <summary>Parked at the bottom edge of the screen, peeking up above the phone.</summary>
    Docked,
    /// <summary>Tucked out of sight below the screen; the phone's eyes follow your own mouse instead.</summary>
    Following,
    /// <summary>Flew to a spot and stays there.</summary>
    Pinned,
}

/// <summary>The mode plus, when pinned, the spot. Port of the Swift enum with payload.</summary>
public readonly record struct CursorMode(CursorModeKind Kind, double X, double Y)
{
    public static CursorMode Docked => new(CursorModeKind.Docked, 0, 0);
    public static CursorMode Following => new(CursorModeKind.Following, 0, 0);
    public static CursorMode Pinned(double x, double y) => new(CursorModeKind.Pinned, x, y);
}

/// <summary>One planned trip: a smooth cubic curve walked with an easing curve.
/// Port of the private <c>Flight</c> struct.</summary>
public sealed class Flight
{
    public required Pt From { get; init; }
    public required Pt C1 { get; init; }
    public required Pt C2 { get; init; }
    public required Pt To { get; init; }
    public required double Start { get; init; }
    public required double Duration { get; init; }
    public required TimingCurve Timing { get; init; }
    /// <summary>It was sent to point at something (not heading home).</summary>
    public required bool Pointing { get; init; }
    public bool Landed { get; set; }

    public double Progress(double now) =>
        Duration <= 0 ? 1 : Math.Clamp((now - Start) / Duration, 0, 1);

    public Pt Position(double e)
    {
        var u = 1 - e;
        var a = u * u * u;
        var b = 3 * u * u * e;
        var c = 3 * u * e * e;
        var d = e * e * e;
        return new Pt(
            a * From.X + b * C1.X + c * C2.X + d * To.X,
            a * From.Y + b * C1.Y + c * C2.Y + d * To.Y);
    }

    public Pt Tangent(double e)
    {
        var u = 1 - e;
        var a = 3 * u * u;
        var b = 6 * u * e;
        var c = 3 * e * e;
        return new Pt(
            a * (C1.X - From.X) + b * (C2.X - C1.X) + c * (To.X - C2.X),
            a * (C1.Y - From.Y) + b * (C2.Y - C1.Y) + c * (To.Y - C2.Y));
    }
}

/// <summary>The mood the face shows, mirrored from GooglyProtocol.Mood so this
/// WPF-free project can compute the face without a protocol dependency.</summary>
public enum CursorMood
{
    Listening, Resting, Thinking, Talking, Pointing, Happy, Sleepy,
}

/// <summary>The face the phone should show for one frame (pure half of FaceState).</summary>
public readonly record struct FaceSnapshot(double GazeX, double GazeY, CursorMood Mood, double Talk);

/// <summary>
/// Where the cursor is and how it moves. Coordinates are top-left origin, y down,
/// in points. Faithful port of <c>CursorEngine</c> in Mac/CursorOverlay.swift —
/// the flight planner (duration, bow, velocity carry), the attitude spring, the
/// blink schedule, the press squish and the phone-face computation, all pure so
/// the console tests can drive it.
/// </summary>
public sealed class CursorEngine
{
    private readonly Random _random;

    public CursorMode Mode { get; private set; } = CursorMode.Following;
    public Pt Tip { get; private set; } = new(-1000, -1000);
    public Pt Velocity { get; private set; } = default;
    public double Angle { get; private set; } = Math.PI / 4;
    private double _angleVelocity;
    public double Opacity { get; private set; }
    private Flight? _flight;
    private bool _placed;
    public Sz Bounds { get; private set; } = new(1440, 900);
    /// <summary>Drags move slower and in a straight, steady line.</summary>
    public bool Dragging;

    /// <summary>Fired when a flight lands on a spot it was sent to point at.</summary>
    public event Action<Pt>? OnLand;
    /// <summary>Fired when it pops out of the phone or dives back in
    /// (a point on the bottom edge).</summary>
    public event Action<Pt>? OnLaunch;

    public double LandedAt { get; private set; } = -10;
    private double _pressStart = -10;
    private double _pressDepth;

    /// <summary>Talk Test runs until this timestamp (engine clock).</summary>
    public double TalkUntil;
    /// <summary>The live session's mood while he's listening, thinking or talking.</summary>
    public CursorMood? BrainMood;
    /// <summary>Where the phone's eyes should look instead of at the cursor
    /// (e.g. at you while he listens), in face-gaze units.</summary>
    public (double X, double Y)? GazeOverride;
    /// <summary>True while he's awake and talking with you (no dozing off then).</summary>
    public bool Awake;
    private Pt _mouse;
    private double _lastMouseMove = double.NaN;
    /// <summary>Live voice loudness, 0…1.</summary>
    public Func<double> TalkLevel { get; set; } = static () => 0;
    public double BlinkUntil { get; private set; }
    private double _nextBlink = double.NaN;

    /// <summary>Cursor size in points (Settings.cursorSize).</summary>
    public double Size { get; set; } = 72;
    /// <summary>Where the phone sits under the screen, 0 = left, 1 = right.</summary>
    public double PhonePosition { get; set; } = 0.5;
    /// <summary>The mood picked by hand in the menu (Settings.mood).</summary>
    public CursorMood BaseMood { get; set; } = CursorMood.Listening;

    public CursorEngine(Random? random = null) => _random = random ?? new Random();

    public void SetMode(CursorMode newMode) => Mode = newMode;

    /// <summary>Anything other than pinned is a kind of home.</summary>
    public bool IsHome => Mode.Kind != CursorModeKind.Pinned;

    /// <summary>Where the phone sits, just below the bottom edge of the screen.</summary>
    public Pt PhonePoint(Sz b) => new(b.Width * PhonePosition, b.Height + b.Height * 0.18);

    /// <summary>Peeking up from the bottom edge, right above the phone.</summary>
    public Pt DockPoint(Sz b) => new(b.Width * PhonePosition, b.Height - Size * 0.42);

    /// <summary>Just below the bottom edge, out of sight, as if tucked into the phone.</summary>
    public Pt TuckedPoint(Sz b) => new(b.Width * PhonePosition, b.Height + Size * 0.35);

    private Pt Goal() => Mode.Kind switch
    {
        CursorModeKind.Docked => DockPoint(Bounds),
        CursorModeKind.Following => TuckedPoint(Bounds),
        CursorModeKind.Pinned => new Pt(Mode.X, Mode.Y),
        _ => default,
    };

    /// <summary>Seconds until the current trip arrives.</summary>
    public double TimeToArrive(double now) =>
        _flight is null ? 0 : Math.Max(0, _flight.Start + _flight.Duration - now);

    /// <summary>A little squish toward the tip, like pressing a button.</summary>
    public void Press(double depth, double now)
    {
        _pressStart = now;
        _pressDepth = depth;
    }

    /// <summary>The per-frame step. <paramref name="dt"/> is pre-clamped by the caller
    /// (see <see cref="ClampDt"/>); <paramref name="now"/> is the shared clock.</summary>
    public void Step(double dt, double now, Pt mouse, Sz bounds)
    {
        Bounds = bounds;
        if ((mouse - _mouse).Length > 1.5) _lastMouseMove = now;
        _mouse = mouse;
        if (!_placed)
        {
            Tip = TuckedPoint(bounds);
            _placed = true;
        }

        var target = Goal();
        bool needsPlan;
        if (_flight is { } flight)
        {
            needsPlan = (flight.To - target).Length > 0.5;
        }
        else
        {
            needsPlan = (Tip - target).Length > 0.5;
        }
        if (needsPlan) Plan(target, now);
        Advance(now);

        // Fades in the moment it leaves home; in follow mode it fades out once it
        // has tucked itself away.
        var settled = (_flight?.Progress(now) ?? 1) >= 1;
        var wantOpacity = Mode.Kind == CursorModeKind.Following && settled ? 0 : 1;
        var rate = wantOpacity > Opacity ? 12.0 : 6.0;
        Opacity += (wantOpacity - Opacity) * Math.Min(1, dt * rate);

        // Upright like a normal cursor while out and about (tipped up when parked),
        // leaning a touch into the motion.
        var lean = Math.Clamp(Velocity.X / 2800, -0.2, 0.2);
        var targetAngle = (IsHome ? Math.PI / 4 : 0) + lean;
        const double k = 24;
        _angleVelocity += (k * (targetAngle - Angle) - 2 * Math.Sqrt(k) * _angleVelocity) * dt;
        Angle += _angleVelocity * dt;

        if (double.IsNaN(_nextBlink)) _nextBlink = now + 3; // first blink 3 s in, like CACurrentMediaTime()+3
        if (now > _nextBlink)
        {
            BlinkUntil = now + 0.13;
            _nextBlink = now + (2.5 + _random.NextDouble() * (6 - 2.5));
        }
    }

    /// <summary>
    /// Plans a natural-looking trip: a gentle curve, timed like a real hand movement
    /// (longer trips take a bit longer, but not proportionally), with a smooth start
    /// and a long, soft landing.
    /// </summary>
    private void Plan(Pt goal, double now)
    {
        var from = Tip;
        var dxy = goal - from;
        var distance = dxy.Length;
        var pointing = !IsHome;
        var dock = DockPoint(Bounds);
        var fromHome = (from - dock).Length < Size * 1.2 || from.Y > Bounds.Height - 2;
        if (distance <= 0.5)
        {
            _flight = new Flight
            {
                From = from, C1 = from, C2 = goal, To = goal,
                Start = now, Duration = 0, Timing = TimingCurve.Launch, Pointing = pointing,
            };
            return;
        }

        var duration = Math.Min(1.2, Math.Max(0.45, 0.4 + 0.12 * Math.Log2(1 + distance / 24)));
        if (Dragging) duration = Math.Min(1.6, Math.Max(0.6, duration * 1.45));

        var direction = new Pt(dxy.X / distance, dxy.Y / distance);
        var normal = new Pt(-direction.Y, direction.X);
        if (Math.Abs(direction.X) > 0.4)
        {
            if (normal.Y > 0) normal = -normal; // sideways trips bow gently upward
        }
        else
        {
            var towardMiddle = Bounds.Width / 2 - (from.X + goal.X) / 2 >= 0 ? 1 : -1;
            if (normal.X * towardMiddle < 0) normal = -normal; // vertical trips bow toward the middle
        }
        var bow = Dragging ? 0 : Math.Min(distance * 0.1, 70) * 0.75;
        var reach = distance * 0.3;
        var c2 = new Pt(
            goal.X - direction.X * reach + normal.X * bow,
            goal.Y - direction.Y * reach + normal.Y * bow);

        var speed = Velocity.Length;
        Pt c1;
        TimingCurve timing;
        if (speed > 60)
        {
            // A change of plans mid-flight: carry on from the current speed and
            // heading, no kink.
            var carry = Velocity * (duration / 3);
            var length = carry.Length;
            if (length > distance * 0.6) carry = carry * (distance * 0.6 / length);
            c1 = from + carry;
            timing = TimingCurve.Redirect;
        }
        else
        {
            c1 = new Pt(
                from.X + direction.X * reach + normal.X * bow,
                from.Y + direction.Y * reach + normal.Y * bow);
            timing = Dragging ? TimingCurve.Steady : TimingCurve.Launch;
        }
        _flight = new Flight
        {
            From = from, C1 = c1, C2 = c2, To = goal,
            Start = now, Duration = duration, Timing = timing, Pointing = pointing,
        };
        if (pointing && fromHome) OnLaunch?.Invoke(new Pt(dock.X, Bounds.Height));
    }

    private void Advance(double now)
    {
        if (_flight is null)
        {
            Velocity = default;
            return;
        }
        var p = _flight.Progress(now);
        var e = _flight.Timing.Value(p);
        Tip = _flight.Position(e);
        if (p >= 1)
        {
            Velocity = default;
            if (!_flight.Landed)
            {
                _flight.Landed = true;
                if (_flight.Pointing)
                {
                    LandedAt = now;
                    OnLand?.Invoke(Tip);
                }
                else if (Mode.Kind == CursorModeKind.Following)
                {
                    OnLaunch?.Invoke(new Pt(Tip.X, Bounds.Height));
                }
            }
        }
        else
        {
            var rate = _flight.Timing.Rate(p) / _flight.Duration;
            var d = _flight.Tangent(e);
            Velocity = d * rate;
        }
    }

    /// <summary>Scale for the press squish: a quick press, then a spring back with
    /// the tiniest rebound.</summary>
    public double PressScale(double now)
    {
        var t = now - _pressStart;
        if (t < 0 || t >= 0.42) return 1;
        if (t < 0.12) return 1 - _pressDepth * Math.Sin(t / 0.12 * Math.PI / 2);
        var r = (t - 0.12) / 0.3;
        return 1 - _pressDepth * (Math.Cos(r * Math.PI * 1.5) * Math.Exp(-r * 3.2));
    }

    /// <summary>A slow, gentle hover while it holds a point (eased in so it never jumps).</summary>
    public double Hover(double now)
    {
        if (IsHome || (_flight?.Progress(now) ?? 1) < 1) return 0;
        var since = now - LandedAt;
        var amount = Math.Clamp((since - 0.35) / 0.6, 0, 1);
        return Math.Sin(since * 2.4) * 1.6 * amount;
    }

    /// <summary>The body's center: the teardrop hangs down-right of the tip at the
    /// current angle; this is where trails and the string attach.</summary>
    public Pt BodyCenter
    {
        get
        {
            var r = Size * 0.5 * Math.Sqrt(2);
            return new Pt(
                Tip.X + Math.Cos(Angle + Math.PI / 4) * r,
                Tip.Y + Math.Sin(Angle + Math.PI / 4) * r);
        }
    }

    /// <summary>What the phone's face should do: look at the cursor (or at your
    /// mouse in follow mode). Port of <c>face(in:now:)</c>.</summary>
    public FaceSnapshot Face(Sz bounds, double now)
    {
        var phone = PhonePoint(bounds);
        var c = Mode.Kind == CursorModeKind.Following ? _mouse : BodyCenter;
        var gx = Math.Clamp((c.X - phone.X) / (bounds.Width * 0.5), -1, 1);
        var gy = -Math.Max(0.15, Math.Min(1, (phone.Y - c.Y) / (bounds.Height * 1.1)));
        if (GazeOverride is { } g)
        {
            gx = g.X;
            gy = g.Y;
        }

        var testing = now < TalkUntil;
        var talk = testing ? 0.5 + 0.5 * Math.Sin(now * 19) * Math.Sin(now * 7.3) : TalkLevel();
        var mood = BaseMood;
        if (BrainMood is { } brain)
        {
            mood = brain;
        }
        else if (testing)
        {
            mood = CursorMood.Talking;
        }
        else if (Mode.Kind == CursorModeKind.Pinned && mood == CursorMood.Listening)
        {
            mood = CursorMood.Pointing;
        }
        else if (Mode.Kind == CursorModeKind.Following && !Awake && mood == CursorMood.Listening)
        {
            // Your mouse sat still: he gets drowsy after 5 seconds and dozes off after 15.
            var idle = now - _lastMouseMove;
            if (idle > 15) mood = CursorMood.Resting;
            else if (idle > 5) mood = CursorMood.Sleepy;
        }
        return new FaceSnapshot(gx, gy, mood, talk);
    }
}

/// <summary>The clock discipline every overlay frame obeys (vsync jitter tamed).</summary>
public static class CursorClock
{
    /// <summary>dt clamped to [1/480, 1/20] s, exactly like the original's CADisplayLink guard.</summary>
    public static double ClampDt(double dt) => Math.Clamp(dt, 1.0 / 480, 1.0 / 20);
}
