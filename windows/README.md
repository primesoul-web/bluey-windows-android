# Bluey — Windows host

C# / **.NET 10 (LTS)** + WPF tray application. Minimum **Windows 10 1903** (build 18362). Replaces the original's Mac menu-bar app.

## Status

M0 skeleton — builds `Bluey.exe`; no behavior yet. See [PARITY.md](../PARITY.md) for the roadmap.

## Component mapping

| Original (macOS) | Here (Windows) |
| --- | --- |
| `NSStatusItem` menu-bar app (`LSUIElement`) | Tray icon via `Shell_NotifyIcon` — no taskbar button |
| Carbon `RegisterEventHotKey` | Win32 `RegisterHotKey` |
| ScreenCaptureKit (excludes own process) | `Windows.Graphics.Capture` |
| Vision `VNRecognizeTextRequest` | `Windows.Media.Ocr` + reading-order sort (400 line / 600 word caps) |
| Accessibility (AX) API, `AXManualAccessibility` | UI Automation (Chromium exposes UIA by default — no opt-in needed) |
| `CGEvent` mouse/keyboard synthesis | `SendInput` |
| `CGWarpMouseCursorPosition` | `SetCursorPos` |
| `NSPanel` overlays (screen-saver level, click-through) | Topmost borderless WPF windows with `WS_EX_LAYERED + WS_EX_TRANSPARENT + WS_EX_NOACTIVATE` |
| `CADisplayLink` | `CompositionTarget.Rendering` (vsync-paced) |
| Bonjour `NWListener` | TCP listener + mDNS advertise (Zeroconf) |
| `UserDefaults` | `%APPDATA%\Googly\settings.json` |
| `keys.json` (0600 file) | `%APPDATA%\Googly\keys.json`, DPAPI-encrypted (CurrentUser scope) |

## Hotkeys (locked — see PARITY.md D1/D2)

| Action | Key |
| --- | --- |
| Wake up to talk / back to follow mode | **Ctrl+Alt+Space** |
| Point here | Ctrl+Alt+P |
| Eyes follow my mouse | Ctrl+Alt+F |
| Stop pointing / go home | Ctrl+Alt+D |
| Talk test | Ctrl+Alt+T |
| Hide / show cursor | Ctrl+Alt+H |
| **Stop him** (emergency) | Ctrl+Alt+S |

## Security

- The OpenAI key is entered in the tray menu, stored DPAPI-encrypted at `%APPDATA%\Googly\keys.json`, and never committed.
- The phone only ever receives ten-minute client secrets (`POST /v1/realtime/client_secrets`) — the real key never leaves the PC.
- The LAN link is plaintext TCP, exactly like the original; optional TLS-PSK hardening is discussed in the [backlog](../BACKLOG.md).

## Build

```
dotnet publish windows/src/GooglyWindows/GooglyWindows.csproj -c Release -r win-x64 --self-contained false -o build/windows
```

or open `windows/GooglyWindows.sln` in Visual Studio 2022+ / Rider. CI builds it on every push.
