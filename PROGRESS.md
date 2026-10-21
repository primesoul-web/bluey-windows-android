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
| M2 | Realtime voice + character (mint, WSS, hold-to-ask, chirp, FaceView, sessions) | ✅ done (`eebac91` → retargeted to Gemini Live in `cbbd489`, CI green — 93 Windows + 87 Android tests, EXE + APK artifacts) |
| M2-hardening | Round 1: fixed port 51826, typed PC address (USB tethering), adb-reverse probe, error wire field, phone-side error surfacing, 3-strikes transport cap | ✅ done (`f57c324`, CI green — 96 Windows + 97 Android) |
| M2-hardening-2 | Round 2: original-parity wake (mic at wake, connect-time chirp/listening), 20 s wake watchdog, 15 s mint timeout + actionable errors, sticky errors, Live-model field | ✅ done (2026-10-21, after the user's second test — 99 Windows + 100 Android) |
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

**Update — first hardware round (2026-10-21):** the user's AI Studio key was tested
from the build sandbox: a garbage key returns 401, the user's key returned
“User location is not supported” — meaning **the key itself passes authentication**
(the sandbox's egress region is blocked, the user's isn't). The mint endpoint, body
shape, WSS endpoint and `gemini-3.8-live` model were re-verified against
ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens the same day. The user's
reported “no answer, no mic” turned out to be the **phone↔PC link** (USB tethering
breaks mDNS discovery) plus fully silent failure paths — both fixed in the M2
hardening commit (D12). Items below are still worth watching on the next live test.

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

## First hardware test round — what happened (2026-10-21)

The user ran the real app on real hardware: entered their AI Studio key on the PC,
held the phone screen, granted mic permission — but no answer and no mic indicator.
Diagnosis from the code (all confirmed by reading, fixed in the D12 commit):

1. **USB tethering kills mDNS discovery** — Android's NsdManager never browses across
   the tether interface, so the phone never found the PC; `PcLink.request` then
   returned null instantly and `LiveVoice.wake()` went back to sleep **silently**.
2. **Mic only starts on `setupComplete`** — so no green mic light, exactly as reported.
3. **No error was visible anywhere**: the phone had no error surface, and the PC's
   mint-failure caption never fired because the request never reached the PC.

Fixes (all tested; see PARITY.md D12): fixed port **51826** + ephemeral fallback,
 typed PC address on the pairing screen, ~7.5 s loopback probe for
 `adb reverse tcp:51826 tcp:51826`, new `error` wire field on failed mint replies,
 phone-side `onSessionError` surface (“I can't find your PC…”, the PC's mint reason
 verbatim, 3-strikes transport give-up), “PC: not connected” status on the face
 screen, tray “Listening on port 51826 — IPs” line, mint/phone logging to
 `%APPDATA%\Googly\log.txt`.

**Next live test checklist for the user** (in order):
1. PC: start Bluey, add the key, open the tray menu → note the `Listening on port … — IPs` line.
2. Try Wi-Fi first (both on the same network); if the phone doesn't connect in ~10 s,
   allow Bluey through Windows Firewall (see README “Connecting the phone”).
3. USB tethering: keep it on, type the PC's tether IP (from the tray line, not the
   Wi-Fi one) into the pairing screen's address box.
4. Hold the screen ≥ 0.3 s and talk; the phone should chirp on wake (mic on, green
   light), and replies appear as the PC caption bubble + phone transcript.
5. If anything fails, the phone now says why; `%APPDATA%\Googly\log.txt` has the PC side.

## Second hardware test round — what happened (2026-10-21)

The user ran the `f57c324` build: tray showed **“Phone connected: Redmi Note 10S”** and
`Listening on port 51826 — 10.237.187.192` — i.e. the phone↔PC link works over USB
tethering (mDNS crossed it on this device), so the pairing screen (and its address
box) never appeared — it only shows while disconnected. The failure was the Gemini
session: hold-to-ask → smile (Waking) → no mic, no reply, no visible error.

**How the original owner did it** (read from `study/bluey-by-riley/iOS/Sources/LiveVoice.swift`):
- The mic starts **inside `connect(token)`** — the moment the token arrives — together
  with `onSessionStart`, the two-syllable chirp and the listening state, all BEFORE any
  server acknowledgement. No “session ready” gating existed.
- The Mac minted keys locally in ~1 s, so a wake either came up in a blink or (on
  failure) the Mac showed his error bubble (“I need an OpenAI API key…”).
- Setup was just: same Wi-Fi, Bonjour, key in the menu bar (original README).

**The port's drift (D13):** everything was gated on `setupComplete` — a Gemini endpoint
  requirement for the wire — but the mic/chirp/state must not be. Fixed: mic at wake,
  session+chirp+Listening at connect-time, 20 s wake watchdog, 15 s mint timeout,
  actionable mint errors, sticky phone errors, “Waking up — connecting…” note,
  Live-model field in the key sheet. A hung/unreachable mint can no longer smile forever.

**Still unknown until the next test:** which of these the user actually hit — the
log.txt (`mint ok` / `mint failed: <reason>`) will say. The likely candidates: rejected
key (typed with an I/l mix-up — the chat-pasted key authenticated fine in the sandbox
probe, so whatever is stored on the PC should be re-copied with **Copy key**), a slow
mint over mobile-data tethering (now times out at 15 s), or the phone's WSS to Gemini
(now covered by the strikes + watchdog with visible messages).

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

## M2.6 — the mint wire-shape fix (2026-10-21)

The first Gemini port minted with the docs' curl shape and every hold-to-ask died
with `400 Unknown name "liveConnectConstraints" at 'auth_token'`. Probing the live
endpoint (AI Studio key, masked) showed the deployed REST API differs from the
published examples: `POST /v1beta/auth_tokens` takes a **flat `AuthToken` body**
whose `bidiGenerateContentSetup` carries the model and the whole session. The docs'
`liveConnectConstraints` wrapper doesn't exist on the wire, and `uses` is a *union
sibling* of the setup so it can't ride along either (unset = the default 1, which is
what we want). Inside the setup, `responseModalities` and `speechConfig` nest under
`generationConfig` (the SDK flattens them; the raw REST does not).

`RealtimeSpec.Setup()` now emits exactly that shape and `MintRequestBody` wraps it
flat; the phone side (Constrained endpoint, access_token, manual activity,
16 kHz audio, output-transcription bubbles) was already correct and is untouched.
99 Windows + 100 Android tests green.
