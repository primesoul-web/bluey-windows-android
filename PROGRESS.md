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
- **Realtime (D11 — switched 2026-10-21)**: the *phone* runs the **Gemini Live API**
  session (`wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=<token>`,
  16 kHz PCM in, AUDIO out shown via output transcription — his voice is the chirp,
  replies are text bubbles) using a **PC-minted single-use ephemeral token**
  (`POST /v1beta/auth_tokens` with `x-goog-api-key`, `newSessionExpireTime` +600 s,
  `expireTime` +30 min, the whole session config locked inside via
  `liveConnectConstraints`: personality, the 13 tools as blocking functionDeclarations,
  manual activity detection, both transcriptions, sliding-window compression).
  The real AI Studio key never leaves the PC (DPAPI-encrypted under
  `%APPDATA%\Googly`, service `gemini`). The mint reply also carries the model in the
  new optional `model` packet field. Manual activity detection is the Live API's
  push-to-talk: the server never answers on its own — hold-to-ask sends
  `activityStart`/`activityEnd` (the original's server-VAD `create_response:false` +
  commit + `response.create`, mapped). The PC executes tool calls relayed by the phone
  and returns text (+ optional JPEG) results.

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
| M2 | Realtime voice + character (mint, WSS, hold-to-ask, chirp, FaceView, sessions) | ✅ done (`eebac91` → retargeted to Gemini Live 2026-10-21, see below) |
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
- Live-API E2E (actual Gemini WSS traffic) is untestable without an API key — the state
  machine is covered by 87 unit tests against a fake transport instead.

## Gemini Live API — hardware-verification list (needs a real AI Studio key)

The protocol shapes below are taken verbatim from Google's docs (ai.google.dev,
2026-10-21); these semantics could not be exercised without a key and should be
watched on first real use:

1. **Minting** — `POST /v1beta/auth_tokens` with `liveConnectConstraints.config`
   carrying `systemInstruction`/`tools`/`realtimeInputConfig`/transcriptions/
   compression. If the endpoint rejects any locked field (400), trim it from
   `RealtimeSpec.LiveConfig` and pass it via the phone's setup instead.
2. **Constrained connect** — phone sends only `setup{model}`; the token's config
   should win (`fieldMask` empty + `bidiGenerateContentSetup` present → connection
   setup ignored). If the server complains about the minimal setup, mirror the full
   config in it.
3. **Background audio accumulation** — with manual VAD, mic audio streamed outside
   activityStart/activityEnd is expected to accumulate as context (the original
   overheard everything). If the server drops un-framed audio, asks still work; only
   the overheard-context feature degrades.
4. **Blocking tools auto-resume** — after `toolResponse`, BLOCKING function calling
   should resume the model. If a reply never follows a tool result, add an explicit
   `clientContent{turnComplete:true}` after the last tool response in
   `LiveVoice.runTools` (one line).
5. **Output transcription granularity** — both delta and running-text transcriptions
   are merged defensively; verify captions don't duplicate on hardware.
6. **Session resets** — the Live API resets connections every few minutes (`goAway`);
   the reconnect path (new token + context replay) covers it — verify on a long
   session.

## M2 detailed checklist (working copy — PARITY.md is the contract)

- Windows `GooglyRealtime` lib: `RealtimeSpec` (the locked Live session config — the 13
  tools as `functionDeclarations` with `behavior:"BLOCKING"` + proto-enum schema types,
  personality/toolGuide/computerGuide with D7/D10 adaptations, manual activity detection,
  both transcriptions, sliding-window compression, mint body `uses:1` + 600 s
  new-session + 30 min message windows), `RealtimeMinter`
  (`POST /v1beta/auth_tokens`, `x-goog-api-key`, reads `name`), `HostSettings` (JSON at
  `%APPDATA%\Googly\settings.json`, keys/defaults exactly like `Mac/Settings.swift` +
  `realtimeModel`), `ApiKeyStore` (DPAPI, `%APPDATA%\Googly\keys.json`, service
  `gemini`).
- Windows app: `RealtimeHost` handles `realtimeToken|awake|asleep|caption|captionDone|tool`
  (serial tool chain; the mint reply also carries `model`); `transcribe`/`notes` are M5
  (failure-shaped replies for now). Caption bubble window (Fredoka, white→#EEF0FF,
  berry2 border, read-time `clamp(2.8, 1.2 + 0.32·words, 12) + 3` s). Tray gains
  `Personality…` + `Gemini API Key…` (auto-opens 0.5 s after launch when no key;
  hint mentions aistudio.google.com/api-keys). Personality save → broadcast sleep then
  wake 1 s later (re-mint).
- Android: `LiveVoice` (state machine asleep→waking→listening→asking→thinking→speaking;
  OkHttp WSS to `BidiGenerateContentConstrained?access_token=`; sends a minimal
  `setup{model}` first and gates everything on `setupComplete`; mic 16 k PCM16 →
  `realtimeInput.audio`; hold-to-ask = `activityStart` on press + `activityEnd` on
  release; reconnect backoff `min(30, 2+3·attempt)`; context replay via `clientContent`
  with `turnComplete:false` — "(Context: what I said earlier in this session, from my
  notes. Don't reply to this.)\n" + ≤6000 chars; tool relay → `toolResponse` + screenshot
  `inlineData` turns with `turnComplete:true`; `go_to_sleep` = sleep-after-reply),
  `ChirpSynth` (exact constants — see PARITY), `MicStreamer` (AudioRecord
  VOICE_COMMUNICATION, 16 kHz), `FaceView`+`FaceAnimator` verbatim port,
  `ModeIndicator`, `SoundButton`, `SessionStore` + `BlueyAppView`
  (sessions/transcripts UI).
- M2 stand-ins (honest): `ChunkRecorder` is a no-op stub (real AAC chunking = M5);
  screen/control tools return "not in this build yet" results (M3/M4); caption bubble
  shows bottom-center above the phone position (M3 moves it to his cursor).

## Build & test (on the build machine)

Toolchains live in **`/home/z/my-project/toolchains/`** (persisted with the workspace;
recreate from this section if lost): `dotnet/` (SDK 10.0.402), `gradle-8.9/`,
`android-sdk/` (platform 35 + build-tools 34.0.0), `nuget/` + `gradle-home/` (caches).
System Java 21 is used for Gradle.

```bash
# Windows (Linux cross-build)
cd /home/z/my-project/bluey-windows-android/windows
export NUGET_PACKAGES=/home/z/my-project/toolchains/nuget
/home/z/my-project/toolchains/dotnet/dotnet build GooglyWindows.sln -p:EnableWindowsTargeting=true
/home/z/my-project/toolchains/dotnet/dotnet test  GooglyWindows.sln -p:EnableWindowsTargeting=true

# Android (Gradle 8.9; system JDK 21; SDK via android/local.properties)
cd /home/z/my-project/bluey-windows-android/android
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 GRADLE_USER_HOME=/home/z/my-project/toolchains/gradle-home
/home/z/my-project/toolchains/gradle-8.9/bin/gradle testDebugUnitTest assembleDebug
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
