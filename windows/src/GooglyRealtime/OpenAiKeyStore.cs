using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace GooglyRealtime;

/// <summary>
/// API keys live in a private file under %APPDATA%\Googly, never in the project folder
/// or git. Port of <c>Keychain.swift</c> (which despite its name is a plain
/// ~/Library/Application Support/Googly/keys.json on the Mac); the Windows port stores
/// each value DPAPI-encrypted for the current user, per the locked porting decision.
/// </summary>
public sealed class OpenAiKeyStore
{
    private readonly object _gate = new();
    private readonly string _path;
    private JsonObject? _cache;

    public OpenAiKeyStore(string? path = null)
    {
        _path = path ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "Googly", "keys.json");
    }

    public static OpenAiKeyStore CreateForTest(string path) => new(path);

    /// <summary>The stored key for a service, or null when there isn't one.</summary>
    public string? Get(string service)
    {
        if (!OperatingSystem.IsWindows()) return null;
        lock (_gate)
        {
            var value = Load()[service];
            if (value is null) return null;
            var stored = value.GetValue<string>();
            if (string.IsNullOrEmpty(stored)) return null;
            try
            {
                return Encoding.UTF8.GetString(ProtectedData.Unprotect(
                    Convert.FromBase64String(stored), null, DataProtectionScope.CurrentUser));
            }
            catch (CryptographicException)
            {
                return null; // unreadable (different user, corrupted) behaves like missing
            }
            catch (FormatException)
            {
                return null;
            }
        }
    }

    /// <summary>Saves (or clears, when the value is null/empty) the key for a service.</summary>
    public void Set(string service, string? value)
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("API keys are stored with DPAPI, which is Windows-only.");
        lock (_gate)
        {
            var all = Load();
            if (string.IsNullOrEmpty(value))
            {
                all.Remove(service);
            }
            else
            {
                var sealed_ = Convert.ToBase64String(ProtectedData.Protect(
                    Encoding.UTF8.GetBytes(value), null, DataProtectionScope.CurrentUser));
                all[service] = sealed_;
            }
            _cache = all;
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
                File.WriteAllText(_path, all.ToJsonString(KeyFileJson.Writer));
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                // A key that can't be written still applies for this run.
            }
        }
    }

    private JsonObject Load()
    {
        if (_cache is not null) return _cache;
        JsonObject stored = [];
        try
        {
            if (File.Exists(_path))
            {
                var node = JsonNode.Parse(File.ReadAllText(_path));
                if (node is JsonObject obj) stored = obj;
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException
            or System.Text.Json.JsonException)
        {
            // unreadable → empty
        }
        _cache = stored;
        return stored;
    }
}

internal static class KeyFileJson
{
    public static readonly System.Text.Json.JsonSerializerOptions Writer = new()
    {
        WriteIndented = true,
    };
}
