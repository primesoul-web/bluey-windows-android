# PARITY.md — the feature contract

**The port is finished when every item here is checked and all seven acceptance tests pass.**

Original: [`rbrown101010/bluey-by-riley`](https://github.com/rbrown101010/bluey-by-riley) @ `6d84d91` (2026-10-08, "Add Unlicense (public domain) and README license note") — public domain, Unlicense. **When in doubt, the original source is the spec.** File references below point into that repo.

## Locked porting decisions

| # | Topic | Decision |
|---|---|---|
| D1 | Wake/sleep hotkey | `Ctrl+Alt+Space` (the Mac's ⌥Space can't carry over: Alt+Space is the Windows system-menu key) |
| D2 | Other hotkeys | `Ctrl+Alt+P / F / D / T / H / S`, 1:1 with the Mac's ⌃⌥ set |
| D3 | Windows host | C# / **.NET 10 (LTS)** + WPF |
| D4 | Windows baseline | **Windows 10 1903 (build 18362) or newer** |
| D5 | Android client | Kotlin + Jetpack Compose, **minSdk 29** (Android 10) |
| D6 | Scope | Original **main branch only** — unmerged upstream PRs are logged in `BACKLOG.md`, never implemented as part of parity |
| D7 | "Riley" strings | **Kept byte-for-byte** (`Riley asked Bluey`, `Riley's Bluey app`) for exact meeting-notes output parity |
| D8 | Wire protocol | **Byte-compatible** with the original — see [`shared-protocol/README.md`](shared-protocol/README.md) |
| D9 | Realtime API | ~~`gpt-realtime-2.1`, text output only, no `OpenAI-Beta` header~~ — **superseded by D11** (provider switched before first release) |
| D10 | Prompt text | Platform references inside minted prompts adapted (`iPhone`→`phone`, `Mac`→`PC`; shortcut examples like `cmd+t`→`ctrl+t`), everything else verbatim. Behavior parity beats string parity: `cmd`/`meta`/`super` still parse on Windows (mapped to Ctrl) so nothing the model emits can slip through |
| D11 | Provider switch (2026-10-21, user) | **Gemini Live API** (Google AI Studio key) replaces OpenAI Realtime. Model `gemini-3.8-live` (configurable via `realtimeModel` setting; the mint reply carries it in the new optional `model` packet field). PC mints single-use ephemeral tokens (`POST /v1beta/auth_tokens`, `x-goog-api-key`) with the **whole session config locked inside** (`liveConnectConstraints`), new-session window 600 s (the original's secret TTL), message window 30 min. Phone connects to `BidiGenerateContentConstrained?access_token=…` and sends a minimal setup (the model). Behavior-parity mappings: text-out → AUDIO out + **output transcription** shown as his bubble (native-audio models can't answer text-only; his voice stays the chirp); server-VAD `create_response:false` → **manual activity detection** (the server never answers on its own; hold-to-ask = `activityStart`/`activityEnd`); function calling → `functionDeclarations` with `behavior: BLOCKING` (tool-first, reply after results); context replay → `clientContent` with `turnComplete:false`; input/output transcription + sliding-window compression for all-day sessions |
| D12 | Connection hardening (2026-10-21, after first real hardware test) | The original could stay silent about failures because Bonjour+iPhone Wi-Fi just worked; Windows + Android needed honesty. **Fixed listen port 51826** (the original used NWListener's ephemeral ports resolved via Bonjour; a fixed port is what makes Windows Firewall rules, USB tethering and `adb reverse` work — falls back to ephemeral if taken, mDNS still resolves it). **Typed PC address** on the pairing screen (Android's NsdManager can't see across a USB-tethering link) + a loopback probe every ~7.5 s so `adb reverse tcp:51826 tcp:51826` just works. **New optional `error` packet field** (wire rule 5): a failed `realtimeToken` reply carries the reason. **Phone-side error surfacing**: a session that can't start says why on the phone itself (`onSessionError` → bottom-center note; no PC → “I can't find your PC…”; mint failure → the PC's message verbatim), plus a tiny “PC: not connected” status while the link is down. **3-strikes transport cap**: three connections in a row that never reach `setupComplete` stop and say so instead of minting/failing silently forever (mint retries while a session is live stay unlimited, exactly like the original). Tray shows the port + LAN IPs |

## Acceptance tests (definition of done)

1. **Pairing** — phone finds the PC on the same Wi-Fi via `_googly._tcp`; zero manual IP entry.
2. **Follow** — the eyes follow the mouse; double-tap on the phone sleeps/wakes him.
3. **Pointing** — ask "what's this?" and he points at the element under the mouse.
4. **Asking** — hold the phone screen, ask, release → speech bubble beside his cursor + cartoon chirp.
5. **Acting** — "Open Chrome, go to …, post …" → he asks for confirmation out loud first, completes it with his own cursor, then restores the real pointer.
6. **Notes** — a 10-minute session writes `notes.md`, `session.json` and `audio\` under `Documents\Bluey Notes\<date> <id>\`.
7. **Stop** — the stop hotkey (Ctrl+Alt+S) halts any action immediately, and further actions are refused for six seconds.

**Test each milestone before starting the next.**

---

## M1 — Protocol + pairing
*Ports: `Shared/GooglyLink.swift`, `Mac/PhoneServer.swift`, `iOS/MacLink.swift`, `iOS/PairingView.swift`.*

- [x] NDJSON `Packet` codec: all 10 original fields + the port's `model` (D11), camelCase, unset fields omitted, unknown fields ignored — validated against `shared-protocol/packet.schema.json` fixtures in CI on both platforms
- [x] `FaceState` + the 7 moods with exact ranges
- [x] PC advertises `_googly._tcp` (service instance name = PC hostname); TCP listener with `TCP_NODELAY` + keepalive (2 s idle); auto-restart 2 s after failure
- [x] Phone browses (`NsdManager`), connects; remembers the preferred PC and auto-switches when it appears; 1.5 s retry loop
- [x] `hello` exchange right after the socket is ready (device names both ways); PC re-sends the current face on reconnect
- [x] `face` stream with the original throttle: skip if < 1 s since the last AND same mood AND |ΔgazeX|, |ΔgazeY| < 0.004 AND |Δtalk| < 0.02 — which still yields ≥ 1 packet/second so the phone knows the PC is driving
- [x] `callID` request/reply correlation (requester mints a UUID; replier echoes verbatim; unknown ids dropped)
- [x] Legacy fields/commands (`volume`, `speech`, speech-`audio`, `testVoice`, `playing`, `done`, `stopSpeech`) accepted and ignored, never emitted
- [x] Windows tray icon + menu skeleton: status line ("Waiting for the phone app…" / "Phone connected: <names>"), icon renders disabled with no phone, auto-sleep when the phone disconnects while awake
- [x] Android PairingView: sleepy breathing blob with bobbing z's, "Wake me up from your PC", spinner "Looking for your PC", "Play without the PC" escape hatch

## M2 — Realtime voice + character
*Ports: `iOS/LiveVoice.swift`, `iOS/FaceView.swift`, `iOS/GooglyApp.swift`, `iOS/BlueyAppView.swift`, `iOS/SessionStore.swift`, `iOS/SoundButton.swift`, session-mint half of `Mac/RealtimeHost.swift`.*

- [x] Secret mint: `POST /v1beta/auth_tokens` (`x-goog-api-key`) with `{uses:1, expireTime:+30 min, newSessionExpireTime:+600 s, liveConnectConstraints:{model, config:{…}}}`; the whole session config is locked inside the token, so the phone only ever holds the token; errors surfaced as captions (D11)
- [x] Exact locked session JSON: model `gemini-3.8-live`; `responseModalities:["AUDIO"]` (native-audio models can't answer text-only — his reply is the **output transcription**, shown as his bubble; voice = the chirp); voice `Puck`; **manual activity detection** (`automaticActivityDetection.disabled:true` — the server never answers on its own, the phone signals activity around a held question, the `create_response:false` equivalent); `inputAudioTranscription` + `outputAudioTranscription`; `contextWindowCompression.slidingWindow`; the 13 tools as `functionDeclarations` with `behavior:"BLOCKING"` and the original descriptions/schemas (proto-enum types, e.g. `OBJECT`/`STRING`/`NUMBER`/`BOOLEAN`); instructions = personality + tool guide (+ computer guide when control is on) — personality text per D10
- [x] Phone WSS to `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=<minted token>`; first message = minimal `setup` naming the model (from the reply's `model` field) — everything else comes from the token; nothing before `setupComplete`
- [x] Event handling: `setupComplete` · `serverContent.outputTranscription` (his reply; deltas or running text — both merge correctly) · `serverContent.inputTranscription` (the user's words) · `serverContent.turnComplete` · `serverContent.interrupted` · `toolCall.functionCalls` (args as JSON, `{}` when absent); model audio, usage, `goAway`, resumption notices ignored
- [x] Hold-to-ask: 0.3 s threshold (a quick tap isn't a hold), soft/light haptics, `beginAsk` clears the last bubble and sends `activityStart`, release → `activityEnd` (the turn completes and the model answers — the original's commit + `response.create` in one signal); released-before-ready asks as soon as the session is up
- [x] Auto-reconnect: mint a new token, replay context (`clientContent` with `turnComplete:false`, verbatim prefix `"(Context: what I said earlier in this session, from my notes. Don't reply to this.)\n"` + last 6 000 chars, replies prefixed `Bluey: `, reports excluded), backoff `min(30, 2 + 3·attempt)` s, mic and recording never stop (all-day sessions; the Live API resets connections every few minutes — `goAway`/close triggers the same path)
- [x] Tool relay: `tool` packets to the PC, `toolResult` → one `toolResponse` with all `functionResponses` (blocking tools resume the model on it); screenshots re-sent as user `inlineData` turns with `turnComplete:true` (the original's `input_image` + `response.create` pair, same order: outputs → images); `go_to_sleep` sets sleep-after-reply
- [x] Chirp synthesis — exact constants: pentatonic table `[1046.5, 1174.7, 1318.5, 1568.0, 1760.0, 2093.0, 2349.3, 2637.0]` × 0.5; random-walk start 1–3, steps `[-1,1,1,2]`; 2 syllables on wake, 3–5 (word-count scaled) on reply; 0.07–0.09 s notes, 0.13 s wobbling tail (18 Hz, ±0.012); 25 ms scoop `1 − 0.18·e^(−t/12ms)`; 6 ms attack, `e^(−3.2t)·(1 − t⁶)` envelope; wave `sinφ + 0.12·sin2φ`; amplitude 0.26; 28 ms gaps. Volume 0–1 setting (default 1); the phone's talk-bounce reads the chirp's own RMS × 5
- [x] Mic: one `AudioRecord` 16 kHz PCM16 mono (the Live API's native input rate; echo cancellation on) feeding both the Live stream (`realtimeInput.audio`, base64, `audio/pcm;rate=16000`) and the recorder
- [x] Phone face — `FaceAnimator` + `FaceView` ported verbatim: springs gaze 260/0.82 · pupil 160/0.5 · browLift 180/0.55 · browTilt 150/0.6 · lean 60/0.8 · hop 260/0.35 · blush 40/1 · squint 150/0.7; talk smoothing 25/s; mood-change blink + hop(+260)/pupil(+3) kick; saccade wander every 0.6–2.2 s when no packets for 2.5 s; mood jitter (asleep 0.015 / talking 0.07 / thinking 0.05 / else 0.02); thinking gaze `(0.6 + 0.08·sin(1.3t), −0.85)`; idle hops 7–14 s (velocity 180–320, only when quiet and not drowsy); blinks 2–5 s (drowsy 1.6–3 s, slow 0.7 s), 25% doubles; breathing `sin(1.8t)` (resting 1.1t); drawing in the 844×390 design space (body 820×700 @ (12, 44+bounce), bounce `−talk·16 + breathe·3 − hop`, lean around bottom-center, squash ±0.03; blob radii 52/48/46/54 % ∥ 58/56/44/42 %; gradient at 150°; crown; brows; blush cheeks; droop lids; z's; thinking dots; nose-mouth)
- [x] ModeIndicator: asleep (eye icon, no border) · waking (#FFD66B "Waking up", 6 pt, 1.6 Hz) · listening (#4F8BFF "Listening · hold to ask", 10 pt) · asking ("I'm all ears", mic, 16 pt, 1.2 Hz) · thinking (#C79BFF "Thinking", 8 pt, 1.1 Hz) · speaking (#FF9AD0 "Replying", 8 pt) — border hugs the screen's corner radius
- [x] Double-tap wake/sleep; `wake`/`sleep` broadcasts from the PC (⌥ equivalent: Ctrl+Alt+Space, menu item, auto-sleep on phone disconnect)
- [x] SoundButton panel: chirp volume + %, "Wake him up"/"Say hi" (`clientContent` turn with "Reply with a quick, cheerful hi in under eight words."), PC picker (checkmark/ellipsis) when > 1 PC, "Connect to your PC to talk to him." when offline, 6 s idle auto-close
- [x] BlueyAppView: sidebar (MiniBluey, connection dot #5BE49B/grey + PC name, Start/End session, description, chirp volume, PC picker, "Back to his face") + session list (LIVE badge, summary = first question or first utterance, duration, "N asked", swipe-delete) + detail (chat entry kinds transcript/heard/asked/reply/report with exact colors and bubble styles; "Copy prompt for agent" verbatim incl. `~/Documents/Bluey Notes` path formatting; share as plain text)
- [x] SessionStore: `sessions.json` (newest first; crash-orphaned sessions closed at their last line; < 20 s empty sessions dropped; placeholder-then-words turn entries; `recentText` 6 000-char context; 1 s debounced save; 6 s debounced sync; 3-day resync on reconnect)
- [x] **Connection hardening (D12, after the first real hardware test):** fixed listen port **51826** with ephemeral fallback when taken (PhoneServer; GooglyService.DEFAULT_PORT on Android); typed PC address entry on the pairing screen (`ManualHost` parse: `host` or `host:port`, default port 51826, persisted) tried whenever mDNS sees nothing, plus a ~7.5 s loopback probe for `adb reverse`; tray menu shows “Listening on port 51826 — <LAN IPs>”; failed mint replies carry the reason in the new `error` packet field and the phone shows it on its own screen (`LiveVoice.onSessionError`; no PC → the find-your-PC message); “PC: not connected — looking…” status while the link is down; three consecutive connections that never reach `setupComplete` give up with a message (mint retries while a session is live stay unlimited, like the original); mint attempts/success/failure and phone connect/disconnect logged to `%APPDATA%\Googly\log.txt`

## M3 — Pointing + overlay
*Ports: `Mac/CursorOverlay.swift`, `Mac/Settings.swift`, `Mac/HotKeys.swift`, `Mac/AppDelegate.swift` (menu), `Mac/ReportPanel.swift`, `Mac/WebResearch.swift`.*

- [ ] Click-through transparent topmost overlay across the main screen; vsync-driven tick; dt clamped to [1/480, 1/20]; rebuild on display changes
- [ ] Cursor engine: modes docked / following / pinned; home geometry (dock `h − 0.42·size`, tucked `h + 0.35·size`, phone `h + 0.18·h`, all at `phonePosition`)
- [ ] Flights: cubic Bézier + easing `launch (0.42,0,0.22,1)` · `redirect (0.25,0.25,0.22,1)` (velocity carry, no kink) · `steady (0.42,0,0.38,1)` for drags; duration `clamp(0.45–1.2, 0.4 + 0.12·log₂(1 + d/24))`, drag × 1.45 (≤ 1.6); bow `min(d·0.1, 70)·0.75`, up for sideways trips, toward middle for vertical; zero bow while dragging
- [ ] Teardrop body: 3 rounded corners + sharp tip (6k, k = size/96); berry gradient + radial shine + 4 pt white rim; glow shadow berry2@0.7 r30k vs berry4@0.45 r16k
- [ ] Googly eyes: 18k whites at (34,40)/(58,40)k, 9k ink pupils ±3.4k toward the look direction; pupils chase the flight direction above 90 pt/s else settle to the tip; blink scaleY 0.12 for 0.13 s every 2.5–6 s
- [ ] Attitude: π/4 docked, 0 upright, lean `±0.2·(vx/2800)` with spring k=24; opacity fade 12/s in, 6/s out; hover bob `sin(2.4t)·1.6` (eases in over 0.6 s after 0.35 s); press squish (0.12 s in, spring rebound)
- [ ] Trails: comet — 0.2 s ribbon of body-center samples above 260 pt/s, width `size·0.42·(i/n)^1.3`, opacity `(v−260)/900·0.26`, twinkles (3–5.5 pt stars every 50 ms above 900 pt/s); string — quadratic from the phone anchor with spring mid (k=22, damp 0.55), slack `max(0, 160 − d·0.12)`, sway `sin(1.3t)·6`; none
- [ ] Effects: expand+fade rings (0.3→1 grow, 3.5 pt) · 8-point stars (move/spin/shrink) · puff on launch/dock (ring r26 + 3 rising stars) · click burst (press 0.14, ring r38 — berry3 for right-click — 6 stars, 34–58 pt) · typed glyphs (float up ~46 pt, white-stroked berry3, 0.9 s)
- [ ] Label bubbles: ink@0.92 bg, berry2 2 pt border, corner 14, white display font 19; spring pop (damping 11, v 6); anchored right of the tip or above the phone; newest replaces; Windows key labels ("Ctrl+T", "↩", arrows, "Stopped")
- [ ] Speech bubble: white→#EEF0FF gradient cloud, berry2 2.5 pt border, curved tail (18 pt, half 13, lean ±1.4·tail); font 21/19/17 by length (≤50/≤110/more); width `max(240, √len·50)` clamped to 580 and 50 % screen; pads (24,15); pop spring (damping 12, stiffness 260, v 4) + 0.12 s fade-in; 0.22 s fade-out + shrink 0.85; bob `sin(2.2t)·2`; placement above the pointed-at thing → below the cursor with tail up when no room → above the phone when not pointing; 14 pt margins; on `captionDone` hold `clamp(2.8, 1.2 + 0.32·words, 12) + 3` s
- [ ] Choreography: FIFO queue of (spot, thing); 1.0 s flight + 2.2 s minimum hold; home 1.2 s after quiet once stop_pointing, or 5 s idle
- [ ] Tray menu, exact items and order (status line → Wake/Sleep → Let Him Use the Computer (+ Accessibility prompt) → Stop Him → Point Here / Eyes Follow My Mouse / Stop Pointing / Talk Test → Mood ▸ / Cursor Size ▸ / Phone Sits Under ▸ → Open Meeting Notes → Personality… → Gemini API Key… (D11; the original says "OpenAI Key…") → Pointer Trail ▸ / Cursor Glow / Show Cursor → Quit) with checkmarks and shortcut hints
- [ ] Settings with exact keys/defaults: `cursorSize 72` (48/72/96/120) · `glow false` · `showCursor true` · `phonePosition 0.5` (L/C/R) · `followMouse true` · `computerControl true` · `trail comet` · `mood listening` · `personality ""`; Personality editor (Save/Cancel/Reset) + restart-if-awake to re-mint
- [ ] ReportPanel research card: top-right 24 pt margins, above the overlay, drag-by-background; 400 pt collapsed (140 loading / 190 preview), 520 pt expanded (~58 chars/line, ≤ 75 % screen); white→#EEF0FF, corner 22, berry2 2.5 pt border; loading spinner + "Doing some research on "X""; 3-line preview + "Click to read more"; expanded scroll + SOURCES (≤ 5 links); minus/chevron buttons; stays until minus
- [ ] `web_research`: Responses API `gpt-5.5`, `web_search`, reasoning effort low, 90 s timeout; instructions: title < 8 words + 1–4 short paragraphs, plain text, no markdown/citations; screen text (first 40 lines) as context; sources from citation annotations (≤ 5, utm stripped); regex-strips inline citations/markdown; tool result = "The full report is now in a card on the user's screen. Reply with ONE short line…" + `\n---REPORT---\n` + plain text (phone splits on the marker for the transcript) — **M3 note: with D11 the realtime side is Gemini; decide then whether research also moves to the Gemini API (e.g. googleSearch grounding) so only one key is needed**

## M4 — Computer control
*Ports: `Mac/ComputerControl.swift`, `Mac/ControlsReader.swift`, `Mac/ScreenReader.swift`, action half of `RealtimeHost.swift`.*

- [ ] `look_at_screen`: `Windows.Graphics.Capture` of the main display excluding the host's own process, no cursor; `Windows.Media.Ocr` + reading-order sort (top-to-bottom 1 % bands, then left-to-right; 400 line / 600 word caps; `L#`/`W#` ids); UIA controls (15 kinds, 0.45 s / 5 000 element / 140 control budgets, title→description→placeholder→help labels, "contains:" for fields, frame dedupe); `focusedField()` with secure flag; the exact targetList text shape; mouse pointer line `@mx,my` + what's under it; JPEG max edge 1 100 px @ 0.72
- [ ] The 7 control tools with exact pacing: click (fly → squish 90 ms → click → 60 ms → warp back → 380 ms → look with prefix) · type_text (1 char/22 ms, or 6 chars/12 ms over 160 chars; `\n`/`\r`/`\t` as key events; glyph animation; optional return + "↩" bubble) · press_keys (label bubble, 380 ms, look) · scroll (amount 1–10 × 120 px, 12 × 16 ms steps, arrow bubble, center default) · drag (press 0.1, real drag follows the animated cursor to landing) · open_app (activate if running, else exact→prefix→contains search; 900 ms) · open_url (https prefix, http/https only, default browser; 1 100 ms)
- [ ] Serial tool chain (requests execute strictly in arrival order)
- [ ] Refusal gates, exact wording: control off → "The user has turned off computer control in the menu bar. You can still look and point." · within 6 s of stop → "The user pressed stop. Don't do anything else until they ask again." · untrusted → how-to-enable text
- [ ] Password-field refusal: "That's a password field. Ask the user to type it themselves."
- [ ] Refused shortcuts: the Mac set (`cmd+shift+q`, `cmd+option+shift+q`, `ctrl+cmd+q`, `cmd+option+escape`) still parsed + Windows equivalents (`win+l`, `ctrl+alt+delete`, `alt+f4`, `ctrl+shift+escape`) refused with "I won't press that one (it logs out, locks or force-quits). Ask the user to do it."
- [ ] `press_keys` parser: the original key vocabulary + `cmd`/`meta`/`super`/`command` map to **Ctrl** on Windows (D10); unknown key → "I don't know the key "x"."
- [ ] Pointer save + warp-back (`SetCursorPos`) after every click/scroll/drag
- [ ] Emergency stop: clear queue, abort drag, 6 s refusal window, cursor home, "Stopped" bubble 1.6 s
- [ ] `open_app` search folders for Windows (Start Menu entries + `shell:AppsFolder` + standard install dirs); `open_url` scheme rules

## M5 — Meeting notes
*Ports: `iOS/ChunkRecorder.swift` (+`NotesSync`), `Mac/MeetingNotes.swift`.*

- [ ] 300 s AAC-LC 48 kbps chunks `chunk-%03d.m4a` from the same mic stream; format-change safe; chunk files under the session id
- [ ] `transcribe` flow: in-flight dedup, 30 s retry on failure, delete local after ack (the PC keeps the audio), retry-pending on reconnect
- [ ] Diarized transcription `gpt-4o-transcribe-diarize` (`response_format: diarized_json`, `chunking_strategy: auto`; merge same-speaker segments with < 2.5 s gaps) with `gpt-transcribe` fallback (`json`, speaker "?"); multipart to `/v1/audio/transcriptions`, 300 s timeout
- [ ] `Documents\Bluey Notes\` root; per-session folder `yyyy-MM-dd HH.mm <8-char tag>` (matched by tag suffix); one-time `README.md` (D7: Riley strings verbatim)
- [ ] `notes` command → `session.json` (pretty, sorted keys) + `notes.md` with the exact entry formats: `**[time] Speaker A:**` · `[time] (live)` · `> **[time] Riley asked Bluey:**` · `> **Bluey:**` · `> **Research report:**` quoted (D7)
- [ ] Pending-chunk bookkeeping, "transcribing N chunks…" status, 3-day resync on reconnect
- [ ] "Open Meeting Notes" menu item creates the root and opens Explorer

## M6 — Polish + CI
- [ ] Demo hooks for automated checks: `BLUEY_DEMO_TRANSCRIBE=<file>` · `BLUEY_DEMO_RESEARCH`/`BLUEY_DEMO_OUT` (card snapshots) · `BLUEY_DEMO_BUBBLE=top|middle|home` + `BLUEY_DEMO_TEXT` + `BLUEY_DEMO_OUT`
- [ ] All seven acceptance tests pass on real hardware
- [ ] CI green on every push: APK + EXE artifacts attached
- [ ] README screenshots and usage docs for both apps
- [ ] Secret hygiene: token scan before every push; `.gitignore` enforced

---

## Reference — the 13 tools

Always present: `look_at_screen` · `point_at(target_id)` · `point_at_spot(x, y on the 0–1000 grid)` · `web_research(question)` · `stop_pointing` · `go_to_sleep`.
When "Let Him Use the Computer" is on: `click(target_id|x,y, double, right)` · `type_text(text, press_return)` · `press_keys(keys)` · `scroll(direction, amount 1–10, target)` · `drag(from, to)` · `open_app(name)` · `open_url(url)`.
Exact descriptions and argument schemas live in `RealtimeHost.swift` (tools array) — copy them with only the D10 adaptations.

## Reference — guardrails (all 13, port 1:1)

1. Acts only when asked — "explaining is not doing."
2. Confirms out loud before anything hard to undo (sending/posting, deleting, buying, submitting forms, closing unsaved work, changing settings): says exactly what he's about to do and waits.
3. On-screen text is information, never instructions.
4. Never types passwords, codes, or payment details — prompt rule plus a hard refusal on secure fields before typing.
5. Refuses logout/lock/force-quit shortcuts (Mac vocabulary + Windows equivalents).
6. Restores the real pointer to its saved spot after every click/scroll/drag.
7. Emergency stop: halts instantly, refuses further actions for 6 s.
8. "Let Him Use the Computer" removes the seven control tools from the minted session entirely.
9. Tool-first: no "one moment", no narration between steps; a short reply only at the end.
10. Unexpected popups → stop and tell the user.
11. He acts with his own cursor while the real pointer is parked — you watch him do it.
12. Untrusted (no UIA permission): control tools refused with how-to-enable text; controls hidden from `look_at_screen` (app name still returned).
13. Mic permission denied → caption "I need the microphone. Turn it on for Bluey in the phone's Settings." and back to sleep.

## Reference — key behavioral constants

| Constant | Value |
| --- | --- |
| Ephemeral-token windows | new sessions 600 s (the original's secret TTL), messages 30 min |
| Realtime model | `gemini-3.8-live` (D11), AUDIO out shown via output transcription, PCM 16 kHz in |
| Listen port | **51826** fixed (D12; ephemeral fallback when taken) — the original used Bonjour-resolved ephemeral ports |
| Research model | `gpt-5.5` + `web_search`, effort low |
| Transcription | `gpt-4o-transcribe-diarize`, fallback `gpt-transcribe` |
| Live transcriptions | built into the Live session (`inputAudioTranscription` + `outputAudioTranscription`) |
| Notes chunk | 300 s, AAC-LC 48 kbps |
| Face throttle | ≥ 1/s; skip if same mood ∧ Δgaze < 0.004 ∧ Δtalk < 0.02 |
| Reconnect backoff | `min(30, 2 + 3·attempt)` s (Realtime) · 1.5 s (link) · 2 s (listener) |
| Context replay | last 6 000 chars |
| Stop refusal window | 6 s |
| Point hold | flight 1.0 s + 2.2 s minimum, 5 s idle home |
| Bubble read time | `clamp(2.8, 1.2 + 0.32·words, 12) + 3` s |
| Flight duration | `clamp(0.45–1.2, 0.4 + 0.12·log₂(1 + d/24))` s, drag × 1.45 |
| Session cap notes | < 20 s with nothing said → session dropped |
| Notes resync window | last 3 days on reconnect |
