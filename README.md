# Bluey for Windows + Android

A blueberry with big googly eyes who lives on your phone and points at things on your PC with his own big cursor. Ask "what's this?" and he points at it. Ask him to open a site, type something or post a reply and he does it with his own cursor — after asking you out loud first. He never speaks: his answers pop up as tiny speech bubbles next to his cursor, with a little cartoon chirp from the phone. While he listens he can also take meeting notes for you, speaker-labeled and saved as plain files.

This is a port of **[Bluey](https://github.com/rbrown101010/bluey-by-riley)** by **[@rbrown101010](https://github.com/rbrown101010)** — Riley's app — from **Mac + iPhone to Windows + Android**, aiming for 100% feature parity. The original is public domain under the [Unlicense](LICENSE); this port carries the same license. All credit for the idea, the character, the design and the original implementation belongs to the original author.

## How it works

1. The PC runs a tray app that advertises `_googly._tcp` on your Wi-Fi. The phone finds it by itself — no IPs, no pairing codes.
2. Double-tap him on the phone (or press **Ctrl+Alt+Space** on the PC) to start a session. The phone's mic stays on and everything you say becomes context, but he stays quiet.
3. Press and hold the phone screen to ask him something; let go and he answers — a speech bubble beside his cursor on the PC, plus a chirp from the phone.
4. When he needs to see, the PC sends him a screenshot, the clickable controls, and every piece of text with ids — and he points at things with his own cursor.
5. When you ask him to act (click, type, press keys, scroll, drag, open apps and sites) he does it with his own cursor while your real pointer is parked where you left it. He confirms out loud before anything hard to undo, refuses password fields and log-out shortcuts, and there is a stop key that halts him instantly.
6. While a session runs, the phone records the room in five-minute chunks; the PC transcribes them with speaker labels into `Documents\Bluey Notes\` — `notes.md`, `session.json`, and the raw `audio\`.

The Gemini API key (from [Google AI Studio](https://aistudio.google.com/api-keys)) lives only on the PC (DPAPI-encrypted `%APPDATA%\Googly\keys.json`). The phone only ever receives single-use ten-minute ephemeral tokens with the whole session setup locked inside them — the real key never leaves the PC.

## Connecting the phone (troubleshooting)

The PC listens on **TCP 51826** and advertises `_googly._tcp` over mDNS. Three ways the phone gets to it, in the order the app tries:

1. **Same Wi-Fi (the normal way)** — the phone discovers the PC automatically. If it doesn't appear within a few seconds, Windows Firewall is the usual suspect: allow `Bluey.exe` (or port 51826) for private networks — one `netsh advfirewall firewall add rule name="Bluey" dir=in action=allow protocol=TCP localport=51826` in an admin prompt, or the Windows Security prompt on first run.
2. **USB cable / USB tethering** — mDNS does not cross Android's USB-tethering link, so open Bluey's tray menu on the PC, read the line `Listening on port 51826 — <IPs>`, and type that IP into **“Can't find it?”** on the phone's pairing screen (the `192.168.x.x` that isn't your Wi-Fi's). Tethering must stay on; the phone still uses its own internet for the Gemini session.
3. **USB cable + `adb reverse tcp:51826 tcp:51826`** — the phone then finds the PC on `127.0.0.1` by itself (probed every few seconds; no typing needed).

Nothing fails silently: if a session can't start, the phone says why on its own screen (no PC found, or the reason the PC's mint failed — a bad key, a blocked region, no internet), and the PC writes the details to `%APPDATA%\Googly\log.txt`.

## Status

- [x] Step 1 — full feature inventory of the original (every file, every issue/PR)
- [x] Step 2 — repo skeleton, parity contract, wire-protocol spec, CI
- [x] M1 — protocol + pairing (NDJSON codec on both platforms, mDNS advertise/browse, hello/face streams, throttling, tray skeleton, PairingView)
- [x] M2 — realtime voice + character (Gemini Live API: ephemeral-token minting, WSS client, hold-to-ask, chirp synthesis, FaceView, sessions) + connection hardening from the first hardware test (fixed port 51826, typed PC addresses for USB tethering, phone-side error surfacing)
- [ ] M3 — pointing + overlay + research card
- [ ] M4 — computer control
- [ ] M5 — meeting notes
- [ ] M6 — polish + release

CI builds the **Android APK** and the **Windows EXE** on every push: see [Actions](../../actions).

## Layout

| Path | What |
| --- | --- |
| [`/windows`](windows) | C# / .NET 10 (WPF) tray host — replaces the Mac menu-bar app |
| [`/android`](android) | Kotlin + Jetpack Compose phone client — replaces the iPhone app |
| [`/shared-protocol`](shared-protocol) | The wire protocol spec, JSON schema, and the shared fonts |
| [`PARITY.md`](PARITY.md) | The feature contract: locked decisions, milestone checklists, acceptance tests |
| [`BACKLOG.md`](BACKLOG.md) | Good ideas from unmerged upstream PRs — deliberately out of scope |

## Locked porting decisions

| Topic | Decision |
| --- | --- |
| Wake/sleep hotkey | **Ctrl+Alt+Space** (the Mac's ⌥Space can't carry over; Alt+Space is the Windows system-menu key) |
| Other hotkeys | Ctrl+Alt+P / F / D / T / H / S — 1:1 with the Mac's ⌃⌥ set |
| Windows host | C# / **.NET 10 (LTS)** + WPF, minimum **Windows 10 1903** |
| Android client | Kotlin + Jetpack Compose, **minSdk 29** (Android 10) |
| Parity scope | Original `main` branch only; unmerged upstream PRs go to the backlog |
| Meeting-notes strings | "Riley asked Bluey" etc. kept byte-for-byte for exact output parity |
| Wire protocol | Byte-compatible with the original (`shared-protocol/`) |
| Realtime API | **Gemini Live API** (`gemini-3.8-live`, from an AI Studio key) — switched from OpenAI Realtime per the user's decision; replies arrive as text bubbles via output transcription, his voice is the chirp |

Full detail and rationale: [`PARITY.md`](PARITY.md).

## Building

**Windows** (needs the .NET 10 SDK):

```
dotnet publish windows/src/GooglyWindows/GooglyWindows.csproj -c Release -r win-x64 --self-contained false -o build/windows
```

**Android** (needs JDK 17, Android SDK platform 35, Gradle 8.9+):

```
cd android && gradle assembleDebug
```

Or grab the artifacts from the latest [CI run](../../actions).

## Security

- The Gemini API key is entered in the tray menu, stored DPAPI-encrypted at `%APPDATA%\Googly\keys.json`, and never committed (`.gitignore` blocks `keys.json`, `*.env`, and build output).
- The phone↔PC link is plaintext TCP on the LAN, exactly like the original. Optional TLS-PSK hardening is discussed in [`BACKLOG.md`](BACKLOG.md).
- Secrets are scanned before every push.

## License

[Unlicense](LICENSE) — public domain, same as the original. The fonts in [`shared-protocol/fonts`](shared-protocol/fonts) (Fredoka, IBM Plex) are SIL Open Font License; their license files sit next to them.
