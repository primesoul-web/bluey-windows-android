namespace GooglyProtocol;

/// <summary>
/// The face-update throttle from <c>PhoneServer.send</c> in Mac/Sources/GooglyMac/PhoneServer.swift:
/// skip an update when the last one went out less than a second ago AND the mood is
/// unchanged AND neither gaze moved by 0.004 AND the talk level moved by less than 0.02.
/// The one-second force-resend keeps the phone informed that the PC is still driving.
/// </summary>
public sealed class FaceThrottle
{
    private FaceState? _last;
    private DateTimeOffset _lastAt = DateTimeOffset.MinValue;

    /// <summary>The last face that was actually sent (re-sent to reconnecting phones).</summary>
    public FaceState? LastSent => _last;

    /// <summary>Returns true when this face should go out, and remembers it as the last sent face.</summary>
    public bool ShouldSend(FaceState face, DateTimeOffset now)
    {
        if (_last is { } last
            && (now - _lastAt).TotalSeconds < 1
            && last.Mood == face.Mood
            && Math.Abs(last.GazeX - face.GazeX) < 0.004
            && Math.Abs(last.GazeY - face.GazeY) < 0.004
            && Math.Abs(last.Talk - face.Talk) < 0.02)
        {
            return false;
        }

        _last = face;
        _lastAt = now;
        return true;
    }
}
