using GooglyProtocol;
using Xunit;

namespace GooglyProtocol.Tests;

public class FaceThrottleTests
{
    private static readonly DateTimeOffset T0 = new(2026, 10, 18, 12, 0, 0, TimeSpan.Zero);

    [Fact]
    public void Identical_face_within_one_second_is_skipped()
    {
        var throttle = new FaceThrottle();
        var face = FaceState.Create(0.1, -0.1, Mood.listening, 0.2);
        Assert.True(throttle.ShouldSend(face, T0));
        Assert.False(throttle.ShouldSend(face, T0.AddMilliseconds(300)));
        Assert.False(throttle.ShouldSend(face, T0.AddMilliseconds(999)));
    }

    [Fact]
    public void Identical_face_is_resent_after_one_second()
    {
        var throttle = new FaceThrottle();
        var face = FaceState.Create(0.1, -0.1, Mood.listening, 0.2);
        Assert.True(throttle.ShouldSend(face, T0));
        Assert.False(throttle.ShouldSend(face, T0.AddMilliseconds(999)));
        Assert.True(throttle.ShouldSend(face, T0.AddSeconds(1)));
        Assert.False(throttle.ShouldSend(face, T0.AddMilliseconds(1999)));
        Assert.True(throttle.ShouldSend(face, T0.AddSeconds(2)));
    }

    [Fact]
    public void Mood_change_is_never_skipped()
    {
        var throttle = new FaceThrottle();
        Assert.True(throttle.ShouldSend(FaceState.Create(0, 0, Mood.listening, 0), T0));
        Assert.True(throttle.ShouldSend(FaceState.Create(0, 0, Mood.happy, 0), T0.AddMilliseconds(100)));
    }

    [Theory]
    [InlineData(0.003, false)]
    [InlineData(0.0039, false)]
    [InlineData(0.004, true)]
    [InlineData(0.005, true)]
    public void GazeX_threshold_is_0_004(double delta, bool expectedSend)
    {
        var throttle = new FaceThrottle();
        Assert.True(throttle.ShouldSend(FaceState.Create(0, 0, Mood.listening, 0), T0));
        Assert.Equal(expectedSend, throttle.ShouldSend(FaceState.Create(delta, 0, Mood.listening, 0), T0.AddMilliseconds(500)));
    }

    [Theory]
    [InlineData(-0.003, false)]
    [InlineData(-0.004, true)]
    [InlineData(-0.005, true)]
    public void GazeY_threshold_is_0_004_both_directions(double delta, bool expectedSend)
    {
        var throttle = new FaceThrottle();
        Assert.True(throttle.ShouldSend(FaceState.Create(0, 0.2, Mood.listening, 0), T0));
        Assert.Equal(expectedSend, throttle.ShouldSend(FaceState.Create(0, 0.2 + delta, Mood.listening, 0), T0.AddMilliseconds(500)));
    }

    [Theory]
    [InlineData(0.01, false)]
    [InlineData(0.019, false)]
    [InlineData(0.02, true)]
    [InlineData(0.03, true)]
    public void Talk_threshold_is_0_02(double delta, bool expectedSend)
    {
        var throttle = new FaceThrottle();
        Assert.True(throttle.ShouldSend(FaceState.Create(0, 0, Mood.listening, 0.1), T0));
        Assert.Equal(expectedSend, throttle.ShouldSend(FaceState.Create(0, 0, Mood.listening, 0.1 + delta), T0.AddMilliseconds(500)));
    }

    [Fact]
    public void Slow_drift_accumulates_against_the_last_sent_face()
    {
        // The comparison is always against the last SENT face, so a slow drift
        // crosses the 0.004 threshold after a few skipped updates.
        var throttle = new FaceThrottle();
        Assert.True(throttle.ShouldSend(FaceState.Create(0, 0, Mood.listening, 0), T0));
        Assert.False(throttle.ShouldSend(FaceState.Create(0.001, 0, Mood.listening, 0), T0.AddMilliseconds(100)));
        Assert.False(throttle.ShouldSend(FaceState.Create(0.002, 0, Mood.listening, 0), T0.AddMilliseconds(200)));
        Assert.False(throttle.ShouldSend(FaceState.Create(0.003, 0, Mood.listening, 0), T0.AddMilliseconds(300)));
        Assert.True(throttle.ShouldSend(FaceState.Create(0.004, 0, Mood.listening, 0), T0.AddMilliseconds(400)));
    }

    [Fact]
    public void Skipped_faces_do_not_become_the_last_sent_face()
    {
        var throttle = new FaceThrottle();
        var first = FaceState.Create(0, 0, Mood.listening, 0);
        Assert.True(throttle.ShouldSend(first, T0));
        var skipped = FaceState.Create(0.001, 0.001, Mood.listening, 0.01);
        Assert.False(throttle.ShouldSend(skipped, T0.AddMilliseconds(100)));
        Assert.Same(first, throttle.LastSent);
    }
}
