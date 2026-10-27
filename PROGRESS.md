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

## M2.8 — the self-test tells the truth (2026-10-22)

The 0.3.0 diagnostic build's own bug hid the session-leg answer. Its
`ReceiveMessageAsync` checked `EndOfMessage` before `MessageType`, so the server's
close frame (always `EndOfMessage=true`, zero bytes) came back as an EMPTY message
marked "received" and `JsonNode.Parse("")` threw `JsonReaderException: The input
does not contain any JSON tokens` — the exact line in the user's log. The server's
real close reason (code + description) was discarded. v0.3.1 checks Close first and
reports it, maps the known reasons to advice (region block, token rejection,
unregistered callers), and survives binary / empty / non-JSON frames with a note
instead of a crash.

One tray click now maps the whole session leg, stopping at the first leg that
works — each with its own fresh single-use token: A realtimeInput text turn (what
0.3.0 sent), B clientContent text turn (his say-hi envelope), C an audio turn (his
exact hold-to-ask flow; a synthesized 16 kHz PCM16 tone stands in for the mic),
D the alternate endpoint.

Live probes with the user's working key (2026-10-22, scripts/e2e-probe/ws_frame_probe.py):

- Mint confirmed end-to-end: flat `AuthToken` shape, 300–800 ms — matches the
  user's PC (their log: mint OK in 1076 ms, key stored 39 chars).
- This sandbox's egress region is blocked on the WS leg: close 1007 "User location
  is not supported for the API use" before setupComplete — so the user's post-turn
  close can't be reproduced here. Their PC is NOT region-blocked (it reached
  setupComplete); the 0.3.1 self-test will name their actual close reason.
- Route map: primary v1beta `BidiGenerateContentConstrained` valid; **v1alpha
  spelling also valid** (same token auth) → new `ConstrainedEndpointAlternate`;
  the docs' `/constrained` suffix 404s on upgrade (dead); the plain
  `BidiGenerateContent` rejects minted tokens (close 1008 "unregistered callers"
  — it wants an API key, so it is not a token fallback).
- `realtimeInput.text` IS documented (live-guide), as is `clientContent`; the
  phone's envelopes are all docs-correct → **the APK is unchanged**.

Toolchain note: `toolchains/` was wiped again; .NET 10 SDK now lives at
`~/.dotnet/dotnet` (installed 2026-10-22, version 10.0.402).

Deliverable: `download/Bluey-windows-0.3.1.zip` (+ WHATS-NEW.txt inside).
102 Windows tests green. Not pushed — no token this session; commit is local.

## v0.3.3 — the phone's token request vanished in transit (2026-10-22)

User evidence (log.txt + screenshots, build 0.3.2 CI exe): self-test mint OK,
WSS OK, binary frames decoded, leg B turn OK ("Hello there, how are you?");
phone connected + listed ("phones: Phone" is just the placeholder before its
hello; "phones: Redmi Note 10S" is the same phone after hello). But during
hold-to-ask the PC log shows **zero** `realtimeToken` requests and **zero**
mints — only hotkey `broadcast wake` lines — while the phone showed the 20 s
wake timeout (which only fires when the phone believes the link is healthy;
a known-dead link shows "I can't find your PC" instead). Conclusion: the
request went into a phone→PC black hole on a link the phone trusted.

Fixes (both platforms, version 0.3.3):
- **Phone — self-healing token request.** `PacketDispatcher` gains per-request
  reply timeouts (tools keep waiting forever: slow tools are legitimate).
  MainActivity's `requestToken` now: waits up to 2.5 s for a link that's still
  landing (fresh from background) → asks with a 6 s timeout → on silence,
  **kills the zombie link, reconnects, asks once more** — all inside LiveVoice's
  20 s wake watchdog. `SocketLink.send` failures now tear the link down instead
  of being swallowed. `PcLink.forceReconnect()` added.
- **PC — forensic logging.** PhoneServer logs any packet that carries no
  Command (previously decoded then silently dropped, invisible in the log).
  Nothing a phone sends is ever invisible again.
- **PC — self-test now tests the real path first.** Leg A is the phone's exact
  hold-to-ask wire flow (activityStart + tone chunks + activityEnd). The old
  first leg (text inside realtimeInput) is gone: gemini-3.8-live rejects that
  envelope outright (close 1007 "Precondition check failed"), so it always
  failed and leg B's success masked the audio path, which never got run.
- Versions: Windows 0.3.3, Android versionCode 3 / 0.3.3.

Tests: 117 Windows + 110 Android green (3 new dispatcher-timeout tests, 1 new
PhoneServer forensic-log test). APK + full sln build clean.

Sandbox-built binaries remain untrustworthy on the user's PC (the 0.3.2
sandbox exe never opened; the CI exe runs) — **deliverables are CI artifacts
only**: `bluey-windows-framework.zip`, `bluey-windows-selfcontained.zip`,
`bluey-android-apk`.

## M2.11 — two Blueys, one Wi-Fi (v0.3.4, 2026-10-24)

The 0.3.3 hardware test produced a NEW symptom — "when I click phone to
talk it got disconnected every time from PC" — and the two test logs nailed
both remaining problems at once.

**Problem 1 — the disconnect churn (the real month-long root cause).** The
user's other project, avatar_phone_PC_integration, is a second Bluey
(PC + phone) that ALSO advertises `_googly._tcp` under
`Environment.MachineName` — on their PC, the SAME service name ours uses
("DESKTOP-NT34JG2"; ours on port 51826, theirs on 47821). Android's
NsdManager cannot tell two same-named services apart, so every resolve is a
coin flip between the two PC apps. Worse, their PC streams faces at 5 Hz in
the SAME JSON shape as ours, so a wrong-PC link looks perfectly healthy to
our phone's face watchdog — while every `realtimeToken` request we send it
vanishes (their app doesn't know that command). Result: 6 s timeout →
force-reconnect → coin flip again → the churn in the log (9 reconnects in
23 s), zero requests ever reaching our PC, and the "phone→PC black hole" of
0.3.2 explained in full — the phone was talking to the wrong Bluey.

**Problem 2 — the self-test's audio leg lied.** Leg A sent pure sine tones
as the ask. The server took them fine (its VAD measured exactly 1.2 s —
the audio path works), but gemini-3.8-live has nothing to say to a tone and
stays silent, so leg A "failed" while the path was healthy. Leg B (text)
turns green, proving the session spec and transcriptions are fine.

Fixes (both platforms, version 0.3.4):
- **Phone — greeting verification.** Our PC answers a new connection with a
  packet whose `hello` field is set (always has, since 0.3.0); the other
  fork answers with `{"command":"hello"}`. SocketLink now verifies this
  before the link counts as connected: greet in our voice → connected;
  greet in theirs or stay silent past 1.5 s → reject, blacklist that
  host:port for 5 min, retry fast (250 ms). Nothing an impostor sends is
  ever routed.
- **Phone — sticky verified address.** The last address that passed
  verification is remembered and tried FIRST, before mDNS (the coin flip).
  Dropped automatically once it stops working.
- **PC — TXT marker.** The advertised service carries `go=pc` (new
  AdvertisedService in GooglyProtocol, next to the service type); phones
  that know it can skip us-not candidates at discovery. Harmless to every
  existing resolver.
- **PC — self-test leg A speaks.** Real speech via Windows SAPI TTS
  ("Hello Bluey, how are you today?", 16 kHz PCM16, the phone's exact
  150 ms chunks), tone fallback when no voice is installed — and the
  report says which audio was used, so a tone result is never read as a
  clean verdict on the speech path.
- Versions: Windows 0.3.4, Android versionCode 4 / 0.3.4.

Tests: +4 Android (greeting accept/reject/silent over a real localhost
socket, HostBlacklist TTL) and +6 Windows (LiveAudio chunking, TXT marker),
on top of the existing suites.

Deliverables remain CI-artifacts-only (sandbox exes don't run on the user's
PC). The user should also avoid running both PC apps at once while testing,
though the phone now survives even that.

## M2.12 — the coin flip that never flipped (0.3.5, 2026-10-25)

Symptom after 0.3.4 hit real hardware: "the phone isn't disconnecting
anymore — it isn't even connecting." With greeting verification in place,
ANY TCP connection that reaches our PC becomes a verified link (the server
greets `{hello: hostname}` unconditionally on accept), so the phone's
connections weren't reaching our PC at all. Three candidates, all producing
exactly that symptom:

- **The lockout (fixed).** Both PC apps still running, and Android's
  NsdManager kept resolving the shared `_googly._tcp` name to the impostor.
  0.3.4 could only refuse it — blacklist, retry, resolve the impostor again.
  The coin flip never flipped, and a fresh install has no sticky address to
  fall back on. Fix: stop treating mDNS as the only candidate. A resolved
  instance without our `go=pc` TXT marker is the other fork — and the other
  fork runs on the SAME HOST as our PC, so the phone now dials our default
  port (51826) on that host directly, both after a marker-less resolve and
  immediately after a "not-ours" greeting rejection (a dial beats a 250 ms
  timer). First attempt lands on our PC either way; the sticky address then
  locks it in.
- **The silent blacklist self-own (fixed).** A "silent" peer (no greeting
  within 1.5 s) was blacklisted for 5 minutes just like a proven impostor —
  but our own PC can be briefly late to greet (busy instant, GC, Wi-Fi),
  which would lock the phone out of its own PC. Silent now only drops a
  matching sticky address; "not-ours" (deterministic: a `command` greeting)
  is the only blacklisting rejection.
- **The invisible advertiser (made visible).** PhoneServer swallows
  exceptions thrown by `Started` handlers, so a dead mDNS advertiser used
  to leave the app running but undiscoverable, silently. The app now logs
  "advertising FAILED (...)" with the exception, plus a firewall hint line:
  a new exe folder is a new app to Windows Firewall, and blocked inbound
  also presents as "phone never connects" (log shows advertising, zero
  phone connects — that one is a 2-minute firewall allow, no code needed).

- Versions: Windows 0.3.5, Android versionCode 5 / 0.3.5.
- Tests: +10 Android (ImpostorPolicy — marker detection, resolve routing,
  alternate-port-after-rejection), existing suites unchanged.

## M2.14 — the wake that hung up on itself (v0.3.6, 2026-10-26)

Symptom (user's 0.3.5 log.txt, the same signature as every version before
it): every wake was followed by the phone link dropping and a burst of 3–6
reconnects; the PC log never showed a single `phone #N -> <command>` line —
not under 0.3.3, not under 0.3.4's greeting verification, not under 0.3.5's
ask patience. The link was stable while idle and died exactly when the
phone first had something to say.
Root cause: the phone→PC sends (realtimeToken, tool, awake, asleep,
caption…) run on the main thread — Compose UI callbacks, lifecycle, the
LiveVoice state changes — and `SocketLink.send` wrote to the socket inline.
Android (targetSdk 35) throws NetworkOnMainThreadException for that; the
write's catch-all swallowed it and tore the link down, so the request died
before a single byte left the phone. The hello at connect worked only
because it is sent from the IO thread — which is also why the PC-side
Gemini self-test could stay green through all of it: it never exercises
the phone's send path.
Fix: `SocketLink` now queues writes onto one dedicated writer thread
(ordered, safe from any thread). Android 0.3.6 / versionCode 6 — 0.3.5
had already shipped broken, and a second different "0.3.5" would be
indistinguishable from it on the phone. Windows unchanged (0.3.5).
Untested on hardware at time of writing — verify: wake → PC log shows
`phone #N -> realtimeToken` and `mint ok`.

## M2.15 — the bubble that stacked its letters (v0.3.6, 2026-10-26)

The first hardware session with working captions (M2.14 finally let the
phone's sends through — log: realtimeToken, mint ok, tool calls, captions,
zero reconnect bursts) exposed two PC-side bugs that had been invisible
until then, because no caption had ever arrived from a phone before.

- **The caption bubble rendered as a vertical letter-stack** (user's
  screenshot: one character per line, ~1:6 tall, screen center).
  `CaptionWindow.Show` measured its cloud with an infinite width, then
  called `Arrange` + `UpdateLayout` — which re-ran layout from the window
  root at the window's STALE width (NaN before the first caption ever
  laid out). DesiredSize came back one character wide, `Width` locked to
  it, and every caption after re-wrapped at that. Now the text is
  measured under the exact finite constraint the final layout uses, so
  the wrap measured is the wrap rendered; the clamp's screen bound can no
  longer drop below its floor either.
- **Long replies stayed on screen forever** ("it stays long and I can't
  close it"): the captionDone hold timer ported the original's
  `min(max(2.8, 1.2 + words·0.32), 12) + 3` with the value and lower
  bound swapped, so `Math.Clamp(2.8, computed, 12)` threw
  ArgumentOutOfRangeException on any reply over ~33 words and the clear
  timer never armed. Args restored: the bubble now holds 5.8–15 s by
  length, exactly like the Mac.
- Tool names join the command trace (`phone #N -> tool tool=look_at_screen
  text="{}"`) — the first hardware session's mystery `{}` call is legible
  next time.

Windows 0.3.6 (Android unchanged at 0.3.6 from M2.14). The
chirp-instead-of-speech replies are the original's design, not a bug:
"He never talks out loud: replies are text (speech bubbles on the Mac)
with a little cartoon chirp here." The screen/web tool stubs answering
"I can't see / I can't browse" are the M3/M4 stand-ins as planned — the
tool loop itself round-tripped on hardware for the first time.

## M2.16 — the thinking that never ended, and the bubble you couldn't grab (v0.3.7, 2026-10-27)

The first long hardware session (90 minutes, log of 2026-10-25) worked —
and showed two real defects. Both fixed; both now have regression tests.

- **"Stuck in thinking"** (the phone's hold-to-talk freezing). The log's
  signature: a session dies mid-answer (23:48:34 — a double-tap and a
  re-hold crossing a streaming reply), the socket stays open, and every
  send after that "succeeds" into a server that no longer answers. Five
  holds over 24 minutes' worth of seconds produce empty captions and
  nothing else, `Thinking` has no timeout, and holding during `Thinking`
  changed no visible state — the button felt dead. Only a PC sleep-wake
  cycle (fresh token) cured it; the logs show that cure working every
  time, so the fix makes the phone apply it itself:
  - a **silence watchdog** — an ask (or say-hi) unanswered for 20 s, with
    re-asks inside the same silence NOT extending the deadline, tears the
    zombie session down, mints a fresh token, reconnects, and shows
    "I lost the line to Gemini for a moment — hold the screen and ask me
    again." Twenty seconds sits well above the slowest honest leg on
    record (~11 s for a web_research round trip) and well below the dead
    zone it cures. Any server word stands the watchdog down.
  - holding during `Thinking` now shows the all-ears `Asking` face, so a
    slow answer never reads as a dead button;
  - a stale reply tail landing mid-hold (the " para elementos" fragment
    that re-filled the just-cleared bubble) is dropped — replies only
    render after the question is released.

- **The bubble you couldn't dismiss or move** ("it still does not
  disappear when I click… add that I can move the box freely"). The M2.15
  bubble was click-through by design, like the original's. By request,
  v0.3.7 departs: a click anywhere on the bubble closes it, a drawn ✕
  badge sits on its top-right corner, and dragging moves it — the dragged
  spot is where every later bubble lands (clamped so at least 96 DIPs and
  the ✕ stay reachable; resets on app restart). Only the bubble itself
  takes the pointer: `WM_NCHITTEST` returns `HTTRANSPARENT` everywhere
  outside it (shadow margins, corner cut-outs), and the window still
  never activates or steals focus. Dragging re-arms the reading timer so
  the bubble can't fade out under the user's hand mid-drag.

Phone 0.3.7 (versionCode 7) and Windows 0.3.7. Tests: Android 134/134
(six new: watchdog recovery, no-extension-on-re-ask, fresh-watchdog-after-
answer, stand-down-on-server-words, stale-tail drop, asking-face-while-
thinking), Windows 132/132 (nine new CaptionGeometry tests: font tiers,
width clamp, anchored placement, pin clamping, chrome accounting).

## M2.17 — the bubble that multiplied when you dragged it (v0.3.8, 2026-10-28)

- **Ghost boxes and stuttery dragging** ("it doesn't properly drag and get
  the box duplicate or quintuplets etc… does not smoothly drag anywhere
  in the screen"). v0.3.7's drag moved the *window* — `Left` then `Top`,
  two native moves on every mouse-move. A per-pixel-transparent layered
  window is fully re-composited at each position (DropShadowEffect and
  all), the render thread falls behind the input thread, and the screen
  keeps stale copies: the box smears into "duplicates or quintuplets".
  Worse, the drag deltas were measured against the window's own moving
  origin (`GetPosition(this)`), so under load the reference frame shifted
  between samples and the box lagged and stuttered instead of tracking
  the cursor.
  - the window is now laid out **once** over the whole work area and
    never moves or resizes again — dragging translates the bubble
    *inside* it via a `TranslateTransform` on a `BitmapCache`d holder, a
    pure compositor operation: cursor-exact, ghost-free, nothing
    re-rendered mid-drag;
  - the hit-test hook keeps everything outside the cloud click-through
    (now also on its failure path, and skipped while hidden or mid-drag);
  - the drag clamp is one pure function, `CaptionGeometry.ClampDrag`,
    shared with `Place`'s pinned branch — where you drop the bubble is
    exactly where the next reply lands (v0.3.7 re-clamped pinned spots
    fully inside, quietly moving a bubble the user had parked low);
  - this is also the M3 architecture — the bubble riding the cursor
    overlay is the same still-window + inner-visual trick.

- **The watchdog that stood down too early** (the "fixed to some point"
  residue). v0.3.7's silence watchdog stood down for good on the first
  streamed word — so a reply that died *mid-stream* left him stuck in
  `Speaking` with no self-healing. Now it's a quiescence clock: every
  server word while the exchange is open merely **moves the deadline
  forward**; only exchange-closing events (turn complete, interrupted)
  stand it down, and the PC's tool turn pauses it (no server words arrive
  while tools run by design) with a re-arm the moment the results go
  back. A stream silent for the full 20 s window is a dead session no
  matter how hopefully it started — recover, re-mint, carry on.

Windows 0.3.8 and Android 0.3.8 (versionCode 8). Tests: Windows 136/136
(four new ClampDrag tests: every-edge clamping, negative-coordinate
monitors, degenerate narrow work areas, drop-spot ≡ pinned-spot
equivalence), Android 136/136 (three new: mid-stream death recovers,
slow answers keep postponing, tool legs pause and re-arm the clock).
