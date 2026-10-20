# PROGRESS.md — living context for the Bluey Windows + Android port

> This file exists so nobody (human or agent) ever loses the thread. Update it at every
> milestone. The authoritative feature contract is [`PARITY.md`](PARITY.md); the original
> source is vendored for study at `../original-bluey` / `../study/bluey-by-riley` on the
> build machine (clone of `rbrown101010/bluey-by-riley` @ `6d84d91`).

## What this project is

A 100%-parity port of **Bluey** (Mac menu-bar host + iPhone client) to **Windows host +
Android client**. Repo: `primesoul-web/bluey-windows-android` (public, Unlicense).

- **Windows host**: .NET 10 WPF (`windows/`), Windows 10 1903+ baseline.
- **Android client**: Kotlin + Compose (`android/`), minSdk 29.
- **Wire protocol**: newline-delimited JSON `Packet`s over TCP, `_googly._tcp` mDNS,
  byte-compatible with the original (see `shared-protocol/`).
- **Realtime**: the *phone* runs the OpenAI Realtime session
  (`wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1`, text-only output, server
  VAD with `create_response:false`) using a **PC-minted 600 s client secret**
  (`POST /v1/realtime/client_secrets`, full session config baked in). The real OpenAI
  key never leaves the PC (DPAPI-encrypted under `%APPDATA%\Googly`). The PC executes
  tool calls relayed by the phone and returns text (+ optional JPEG) results.

## Locked decisions (from the user)

1. Wake/sleep hotkey `Ctrl+Alt+Space` (Mac ⌥Space can't carry over).
2. Other hotkeys `Ctrl+Alt+P/F/D/T/H/S` 1:1 with the Mac ⌃⌥ set.
3. .NET 10 LTS + WPF; Kotlin/Compose minSdk 29.
4. Parity = original **main branch only** (unmerged PRs → `BACKLOG.md`).
5. Keep the "Riley" strings byte-for-byte in meeting-notes output.
6. Wire protocol byte-compatible; no `OpenAI-Beta` header (removed from API 2026-05-12).
7. Prompt text: platform refs adapted (`iPhone`→`phone`, `Mac`→`PC`, `cmd+t`→`ctrl+t`,
   Windows app-name examples), everything else verbatim. `cmd`/`meta`/`super` still parse
   on Windows (map to Ctrl).
8. One commit per milestone on `main`; CI must be green before moving on; scan for
   secrets before every push; GitHub auth token comes from the user in chat — never
   print, log, commit, or write it to any file.

## Milestone status

| Milestone | Scope | State |
|---|---|---|
| M0 | Repo skeleton, PARITY.md, shared-protocol spec + fixtures, CI | ✅ done (`189c4e5`) |
| CI | Windows EXE publish + Android APK build (+ protocol job) | ✅ done (`6672c3a`) |
| M1 | Protocol + pairing (codec, mDNS, tray skeleton, PairingView, 96 tests) | ✅ done (`ad108a9`, CI green) |
| M2 | Realtime voice + character (mint, WSS, hold-to-ask, chirp, FaceView, sessions) | ✅ done (`eebac91`, CI green — 90 Windows + 81 Android tests, EXE + APK artifacts) |
| M3 | Pointing + overlay + research cards | ⬜ next |
| M4 | Computer control (click/type/scroll/drag/open, SendInput) | ⬜ |
| M5 | Meeting notes (AAC chunks, diarized transcription, `Documents\Bluey Notes\`) | ⬜ |
| M6 | Polish + Release v0.1.0 (EXE + APK attached) | ⬜ |

## Known M2 stand-ins (honest, resolved in later milestones)

- `ChunkRecorder` records nothing yet — the mic tap and lifecycle are wired exactly like
  the original; the 300 s AAC chunking + upload is M5.
- Screen/control tools return "not in this build yet" results (M3/M4); the minted
  session still contains all 13 tools, exactly like the original.
- The PC caption bubble floats above the phone position (bottom-center); M3 moves it
  onto the cursor overlay with the full speech-bubble choreography.
- Android mic stops being captured when the app is backgrounded (no foreground service
  yet — planned for M6 polish; the session itself survives).
- Live-Realtime E2E (actual OpenAI WSS traffic) is untestable without an API key —
  the state machine is covered by 81 unit tests against a fake transport instead.

## M2 detailed checklist (working copy — PARITY.md is the contract)

- Windows `GooglyRealtime` lib: `RealtimeSpec` (exact minted session JSON incl. the 13
  tools + personality/toolGuide/computerGuide with D7/D10 adaptations), `RealtimeMinter`
  (`POST /v1/realtime/client_secrets`, `{expires_after:{anchor:"created_at",seconds:600}, session:{…}}`,
  reads `value`), `HostSettings` (JSON at `%APPDATA%\Googly\settings.json`, keys/defaults
  exactly like `Mac/Settings.swift`), `OpenAiKeyStore` (DPAPI, `%APPDATA%\Googly\keys.json`).
- Windows app: `RealtimeHost` handles `realtimeToken|awake|asleep|caption|captionDone|tool`
  (serial tool chain); `transcribe`/`notes` are M5 (failure-shaped replies for now).
  Caption bubble window (Fredoka, white→#EEF0FF, berry2 border, read-time
  `clamp(2.8, 1.2 + 0.32·words, 12) + 3` s). Tray gains `Personality…` + `OpenAI Key…`
  (auto-opens 0.5 s after launch when no key). Personality save → broadcast sleep then
  wake 1 s later (re-mint).
- Android: `LiveVoice` (state machine asleep→waking→listening→asking→thinking→speaking;
  OkHttp WSS; `input_audio_buffer.append` base64 PCM 24 k; hold-to-ask =
  commit + `response.create`; reconnect backoff `min(30, 2+3·attempt)`; context replay
  "(Context: what I said earlier in this session, from my notes. Don't reply to this.)\n" + ≤6000 chars);
  tool relay → `function_call_output` + `input_image` + `response.create`; `go_to_sleep` =
  sleep-after-reply), `ChirpSynth` (exact constants — see PARITY), `MicStreamer`
  (AudioRecord VOICE_COMMUNICATION), `FaceView`+`FaceAnimator` verbatim port, `ModeIndicator`,
  `SoundButton`, `SessionStore` + `BlueyAppView` (sessions/transcripts UI).
- M2 stand-ins (honest): `ChunkRecorder` is a no-op stub (real AAC chunking = M5);
  screen/control tools return "not in this build yet" results (M3/M4); caption bubble
  shows bottom-center above the phone position (M3 moves it to his cursor).

## Build & test (on the build machine)

```bash
# Windows (Linux cross-build; .NET 10 at ~/.dotnet)
cd /home/z/my-project/bluey-windows-android/windows
~/.dotnet/dotnet build GooglyWindows.sln -p:EnableWindowsTargeting=true
~/.dotnet/dotnet test  GooglyWindows.sln -p:EnableWindowsTargeting=true

# Android (Gradle 8.9 + JDK 21 at ~/tools, SDK at ~/android-sdk)
cd /home/z/my-project/bluey-windows-android/android
~/tools/gradle/bin/gradle testDebugUnitTest assembleDebug
```

CI (`.github/workflows/ci.yml`) runs the same on every push to `main`; poll
`actions/runs` until both jobs are green before starting the next milestone.

## Recovery notes for future sessions

- The local repo is at `/home/z/my-project/bluey-windows-android` (branch `main`).
  Run `git config core.fileMode false` after any workspace restore (mode noise).
- Shell cwd/env do not persist between commands — every Bash call must be self-contained.
- Study notes: `../study/feature-inventory.md` (full feature contract with constants).
- Multi-agent work log: `../worklog.md` (append, never overwrite).
- Secrets: the GitHub token arrives in chat only. Extract with
  `grep -oE 'ghp_…' <upload file>` inside a single command; redact from all output.
