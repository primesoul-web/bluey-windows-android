using System.Windows.Threading;
using GooglyProtocol;

namespace GooglyWindows;

/// <summary>
/// The M1 face stream: in follow mode the eyes track the real mouse, sending at the
/// original throttle rate (≥ 1 Hz) through the server. The full overlay — moods,
/// drowsiness, pointing gaze — lands in M3 and replaces this driver.
/// </summary>
public sealed class FaceDriver
{
    private readonly PhoneServer _server;
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromMilliseconds(33) };

    public FaceDriver(PhoneServer server)
    {
        _server = server;
        _timer.Tick += (_, _) => Tick();
    }

    public void Start() => _timer.Start();

    public void Stop() => _timer.Stop();

    private void Tick()
    {
        if (!NativeMethods.GetCursorPos(out var point)) return;

        // The phone sits under the screen (Phone Sits Under: Center for now — the setting
        // arrives with the full menu in M3). Gaze points from the phone toward the mouse.
        var width = System.Windows.SystemParameters.PrimaryScreenWidth;
        var height = System.Windows.SystemParameters.PrimaryScreenHeight;
        if (width <= 0 || height <= 0) return;

        const double phonePosition = 0.5;
        var phoneX = phonePosition * width;
        var phoneY = height;

        var gazeX = Math.Clamp((point.X - phoneX) / (width / 2), -1, 1);
        var gazeY = Math.Clamp((point.Y - phoneY) / (height / 2), -1, 1);

        _server.Send(FaceState.Create(gazeX, gazeY, Mood.listening, 0));
    }
}
