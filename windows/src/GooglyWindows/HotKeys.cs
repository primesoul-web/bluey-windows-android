namespace GooglyWindows;

/// <summary>
/// Global hotkeys via RegisterHotKey. M1 registers the wake/sleep key (Ctrl+Alt+Space —
/// the Mac ⌥Space stand-in, PARITY.md D1). The full ⌃⌥ set (P/F/D/T/H/S) arrives in M3
/// with the pointing overlay.
/// </summary>
public sealed class HotKeys : IDisposable
{
    private readonly IntPtr _hwnd;
    private const int WakeSleepId = 1;
    private bool _disposed;

    /// <summary>Raised when Ctrl+Alt+Space is pressed.</summary>
    public event Action? WakeSleepPressed;

    public HotKeys(IntPtr hwnd)
    {
        _hwnd = hwnd;
        NativeMethods.RegisterHotKey(hwnd, WakeSleepId,
            NativeMethods.MOD_CONTROL | NativeMethods.MOD_ALT | NativeMethods.MOD_NOREPEAT,
            NativeMethods.VK_SPACE);
    }

    /// <summary>Feeds a WM_HOTKEY id (forwarded by the tray window); raises the event when it matches.</summary>
    public void Handle(int id)
    {
        if (id == WakeSleepId)
            WakeSleepPressed?.Invoke();
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        NativeMethods.UnregisterHotKey(_hwnd, WakeSleepId);
    }
}
