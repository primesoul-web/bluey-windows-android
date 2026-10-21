using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace GooglyRealtime;

/// <summary>
/// User choices from the tray menu, kept between launches. Port of
/// <c>Mac/Settings.swift</c> — same keys, same defaults — persisted as JSON under
/// %APPDATA%\Googly\settings.json (the original uses UserDefaults).
/// </summary>
public sealed class HostSettings : IDisposable
{
    private readonly object _gate = new();
    private readonly string _path;
    private JsonObject _values;
    private bool _disposed;

    /// <summary>Fired (from a background-safe context) after any value changes.</summary>
    public event Action? Changed;

    public HostSettings(string? path = null)
    {
        _path = path ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "Googly", "settings.json");
        _values = Load();
    }

    public static HostSettings CreateForTest(string path) => new(path);

    private JsonObject Load()
    {
        try
        {
            if (File.Exists(_path))
            {
                var node = JsonNode.Parse(File.ReadAllText(_path));
                if (node is JsonObject obj) return obj;
            }
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException)
        {
            // A unreadable settings file behaves like a missing one: defaults everywhere.
        }
        return [];
    }

    private T Get<T>(string key, T fallback)
    {
        lock (_gate)
        {
            var node = _values[key];
            if (node is null) return fallback;
            try
            {
                if (typeof(T) == typeof(double)) return (T)(object)node.GetValue<double>();
                if (typeof(T) == typeof(bool)) return (T)(object)node.GetValue<bool>();
                if (typeof(T) == typeof(string)) return (T)(object)node.GetValue<string>()!;
                return fallback;
            }
            catch (Exception e) when (e is FormatException or InvalidOperationException)
            {
                return fallback; // a value of the wrong type falls back to the default
            }
        }
    }

    private void Set<T>(string key, T value)
    {
        lock (_gate)
        {
            if (value is double d) _values[key] = d;
            else if (value is bool b) _values[key] = b;
            else if (value is string s) _values[key] = s;
            else _values[key] = JsonValue.Create(value);
            SaveLocked();
        }
        try { Changed?.Invoke(); } catch { /* listeners must not break settings */ }
    }

    private void SaveLocked()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            File.WriteAllText(_path, _values.ToJsonString(SettingsJson.Writer));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // Settings that can't be written still apply for this run.
        }
    }

    /// <summary>Cursor size in points (the design's default is 72).</summary>
    public double CursorSize { get => Get("cursorSize", 72.0); set => Set("cursorSize", value); }

    public bool Glow { get => Get("glow", false); set => Set("glow", value); }

    public bool ShowCursor { get => Get("showCursor", true); set => Set("showCursor", value); }

    /// <summary>Where the phone sits under the screen, 0 = left edge, 1 = right edge.</summary>
    public double PhonePosition { get => Get("phonePosition", 0.5); set => Set("phonePosition", value); }

    /// <summary>When idle, hide the big cursor and let the phone's eyes follow your own mouse.</summary>
    public bool FollowMouse { get => Get("followMouse", true); set => Set("followMouse", value); }

    /// <summary>Legacy in the original (declared, never wired) — kept stored, unused.</summary>
    public bool Captions { get => Get("captions", true); set => Set("captions", value); }

    /// <summary>Lets him click, type, press keys and open things when you ask.</summary>
    public bool ComputerControl { get => Get("computerControl", true); set => Set("computerControl", value); }

    /// <summary>How the cursor's path shows while it flies.</summary>
    public string Trail { get => Get("trail", "comet"); set => Set("trail", value); }

    /// <summary>The mood picked by hand in the menu.</summary>
    public string Mood { get => Get("mood", "listening"); set => Set("mood", value); }

    /// <summary>The saved personality; empty string means "use the default".</summary>
    public string Personality
    {
        get => Get("personality", "");
        set => Set("personality", value);
    }

    /// <summary>Port addition (D11): the Gemini Live model sessions are minted for.</summary>
    public string RealtimeModel
    {
        get => Get("realtimeModel", "gemini-3.8-live");
        set => Set("realtimeModel", value);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        lock (_gate) SaveLocked();
    }
}

internal static class SettingsJson
{
    public static readonly JsonSerializerOptions Writer = new()
    {
        WriteIndented = true,
    };
}
