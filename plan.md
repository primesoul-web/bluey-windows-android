# Bluey M3 — Execution Plan (living document)

> **Context-restore file.** If a new session/sandbox starts: read this + `report.md` +
> `worklog.md` at the project root, then continue at the first unfinished item in §4.
> Update the verification table (§4) after every increment. Ask the user before
> starting the next increment.

---

## 0. Project quick-facts (for a fresh context)

- **Repo:** `primesoul-web/bluey-windows-android`, local clone at
  `/home/z/my-project/bluey-windows-android`. **Never push to the `companion` remote.**
- **What it is:** Windows PC host + Android phone companion. Phone does hold-to-talk;
  the PC mints the Gemini realtime token and renders the character: captions today,
  the googly-eyed cursor + pointing + research cards in M3.
- **Original (reference, Unlicense):** `rbrown101010/bluey-by-riley`, local at
  `/home/z/my-project/study/bluey-by-riley`. Port Mac/iOS Swift 1:1 per `PARITY.md`
  (the contract). D10 = Mac→Windows key/menu adaptations, D11 = Gemini replaces
  OpenAI realtime, D12+ = hardening notes.
- **Sandbox survival:**
  - Toolchain wipes happen (4× so far): rebuild via `scripts/toolchain-setup.sh`.
  - Windows tests on Linux need `-p:EnableWindowsTargeting=true`; run via
    `~/.dotnet/dotnet` (not on PATH).
  - Android tests: verify via `**/test-results/**/*.xml`, never the gradle exit code.
  - CI watch: `GH_TOKEN=… python3 scripts/watch-ci.py <run_id>`.
- **Push protocol:** the user pastes a fresh GitHub token in chat when asked. Use it
  via git credential-helper, never in the URL, never printed, never committed.
- **Version protocol:** bump Windows `<Version>` and Android `versionName` together
  when both change; Android `versionCode` +1 every release. M3 = 0.4.x line.
- **M3 source files (original):** `CursorOverlay.swift` (1,100) · `AppDelegate.swift`
  menu half (343) · `ReportPanel.swift` (202) · `WebResearch.swift` (87) ·
  `Settings.swift` (61) · `HotKeys.swift` (48). Tool half of `RealtimeHost.swift`.

## 1. Verified state — M2 closed (2026-10-28)

User hardware-confirmed on v0.3.8 (`d8e9c93`, CI run 40547691567, artifacts installed
both sides). Log evidence (0.3.8.0 session 22:53): wake → mint 3.1 s → hexagon / git /
weather Q&A round-trips → `web_research` tool call → `captionDone`. All flows green.

| M2 deliverable | Status |
|---|---|
| Voice pipeline (wake, hold-to-talk, mint, stream) | ✅ hardware |
| Tool loop round-trip (function calls + results) | ✅ hardware |
| Captions: readable, dismissible, draggable, ghost-free | ✅ hardware (M2.15/17) |
| Silence watchdog → quiescence clock, self-heal ≤20 s | ✅ hardware (M2.17) |

**Non-blocking observations from the 0.3.7/0.3.8 logs:**
1. 21:07–21:08 (0.3.7): PC DNS outage → 16 mint failures in ~2 min; the phone
   re-asked every 1–10 s (asleep → immediate `realtimeToken` retry). Self-recovered.
   → Candidate fix: mint-failure backoff on the phone (§5 Q4).
2. `web_research` is a stub — Gemini answered "Narendra Modi" from its own knowledge
   after the stub text. Real research + card = M3.5.
3. `look_at_screen`, `point_at`, `point_at_spot`, `stop_pointing` are stubs → M3.3/M4.

## 2. M3 scope (contract: PARITY.md §M3)

1. Click-through transparent topmost overlay across the main screen; vsync tick;
   dt clamped [1/480, 1/20]; rebuild on display changes.
2. Cursor engine: modes docked / following / pinned; home geometry (dock / tucked /
   phone, all at `phonePosition`).
3. Flights: cubic Bézier + launch/redirect/steady easings (velocity carry, no kink);
   duration `clamp(0.45–1.2, 0.4 + 0.12·log₂(1 + d/24))`, drag ×1.45 ≤1.6;
   bow `min(d·0.1, 70)·0.75` up for sideways / toward middle for vertical; 0 while dragging.
4. Teardrop body: 3 rounded corners + sharp tip (6k, k = size/96); berry gradient +
   radial shine + 4 pt white rim; glow shadow berry2@0.7 r30k vs berry4@0.45 r16k.
5. Googly eyes: 18k whites, 9k pupils ±3.4k toward look; pupils chase flight
   direction above 90 pt/s else settle to tip; blink scaleY 0.12 / 0.13 s / 2.5–6 s.
6. Attitude: π/4 docked, 0 upright, lean ±0.2·(vx/2800) spring k=24; opacity 12/s in,
   6/s out; hover bob sin(2.4t)·1.6; press squish 0.12 s + spring rebound.
7. Trails: comet (0.2 s ribbon above 260 pt/s) / string (quadratic from phone anchor,
   spring mid k=22 damp 0.55, slack, sway) / none.
8. Effects: rings, 8-point stars, launch/dock puff, click burst (right = berry3),
   typed glyphs.
9. Label bubbles: ink@0.92, berry2 border, corner 14, display font 19; spring pop;
   anchored right of tip or above phone; newest replaces.
10. Speech bubble moves onto the cursor (cloud spec as shipped in M2, plus): curved
    tail, placement above the pointed thing → below cursor tail-up → above phone;
    FIFO choreography (1.0 s flight + 2.2 s min hold; home 1.2 s after quiet /
    5 s idle).
11. Tray menu exact items/order + settings (cursorSize 72, glow, showCursor,
    phonePosition, followMouse, trail, mood, personality editor with re-mint).
12. ReportPanel research card + real `web_research` (§5 Q1 backend decision);
    `---REPORT---` marker; phone splits transcript on the marker.

## 3. Architecture decisions (locked, from M2.17 groundwork)

- **One overlay window** hosts everything visual (cursor, trails, effects, label
  bubbles, speech cloud) — mirrors the original's single `CursorOverlay` NSWindow.
  The M2.17 CaptionWindow architecture carries over: window fitted to the screen
  once, never moves/resizes while visible; children move via `TranslateTransform` +
  `BitmapCache` (compositor-only, ghost-free — proven on hardware).
- **Interaction stays surgical:** `WS_EX_NOACTIVATE|TOOLWINDOW` window; hit-test hook
  returns HTTRANSPARENT everywhere except the speech cloud / close badge / report
  card (pattern already shipped in M2.16/17).
- **Render loop:** `CompositionTarget.Rendering` (per-frame, vsync-aligned) replaces
  DispatcherTimer; dt clamped [1/480, 1/20] exactly as PARITY.md.
- **Pure math goes to `GooglyRealtime`** (WPF-free, console-testable): TimingCurve
  (Bézier easing), flight duration/bow formulas, dock/tucked/phone geometry, pupil
  chase, blink schedule, trail sampling. WPF side stays thin drawing + wiring.
- **FaceDriver (M1 stand-in) is replaced** by the overlay: the character's look
  direction drives the phone face packets.
- **DPI:** all art sized in DIPs via k = cursorSize/96 like the original's size/96.

## 4. Workstreams (each = one version + push + CI + hardware gate)

| # | Version | Delivers | Tests (GooglyRealtime/console) | Hardware gate (user checks) | Status |
|---|---|---|---|---|---|
| M3.1 | 0.4.0 | Overlay window (click-through, vsync tick, display-change rebuild) + teardrop body + googly eyes (blink, pupil settle) + attitude (roll/lean/opacity/bob) + docked/following modes + FaceDriver replaced by overlay gaze | TimingCurve (identity/linear + 3 real curves, monotonicity), home geometry, dt clamp, blink schedule, pupil clamp | Character sits docked bottom-center; follows the mouse smoothly; eyes track + blink; phone face tracks cursor; no ghosting at any speed | 🔄 shipped 0.4.0 — awaiting hardware check |
| M3.2 | 0.4.1 | Flights (launch/redirect/steady + velocity carry + duration/bow formulas) + trails (comet + string) + effects (rings/stars/puff/click burst/typed glyphs) + label bubbles | Flight duration + bow formulas, redirect velocity carry, trail width/opacity envelopes, effect lifetimes | Demo hooks (`BLUEY_DEMO_BUBBLE/TEXT/OUT`) move him: arced flight with bow, comet at speed, string from phone, click bursts, key label bubbles | ⬜ |
| M3.3 | 0.4.2 | `point_at_spot` (0–1000 grid) + `point_at` + `stop_pointing` real tools + speech-bubble-on-cursor choreography (FIFO queue, hold times, placement ladder above→below→phone) | FIFO queue order + min-hold math, placement ladder, captionDone hold integration | Ask "point at the top-left corner" → he flies there, bubble lands next to him, holds, goes home; captions while pointing appear beside him | ⬜ |
| M3.4 | 0.4.3 | Full tray menu (exact items/order/checkmarks) + Mood ▸ / Cursor Size ▸ / Phone Sits Under ▸ / Pointer Trail ▸ / Glow / Show Cursor + Personality editor (Save/Cancel/Reset, re-mint if awake) | Settings persistence round-trip, mood list, defaults | Every menu item works live; personality edit + re-mint; trail/size/glow toggles immediate | ⬜ |
| M3.5 | 0.4.4 | Real `web_research` (backend per §5 Q1) + ReportPanel card (collapsed 140/190, expanded 520, loading, preview, sources ≤5, minus/chevron, drag) + `---REPORT---` marker + phone transcript split (+ optional mint-failure backoff per §5 Q4) | Marker split (phone-side unit test), source parsing/strip regexes, card state machine | Ask "who is the PM of India" → card pops top-right, spinner, 3-line preview, expand to read + sources; phone transcript shows the one-line reply only | ⬜ |

Order rationale: engine before motion polish (M3.2 needs M3.1's render loop), tools
after motion (M3.3 needs flights), menu after the features it toggles (M3.4), research
last (independent backend + the only other phone-side change, so one APK reinstall).

`look_at_screen` (real capture) and the 7 control tools stay **M4** per PARITY.md.
M3's `point_at` accepts spot targets; rich `target_id` lists arrive with M4's reader.

## 5. Decision points (asked 2026-10-28 — answers below)

- **Q1 research backend:** Gemini `googleSearch` grounding on the existing key
  (recommended — one key, D11 note) / OpenAI Responses `gpt-5.5` parity (needs a new
  OpenAI key) / keep stub.
  → **Answer: Gemini googleSearch grounding, one key.**
- **Q2 cadence:** staged 5 drops with hardware gates (recommended) / two drops /
  one drop.
  → **Answer: Staged 5 drops.**
- **Q3 character fidelity:** faithful port (exact proportions/colors/easings from
  Swift, recommended) / simplified first.
  → **Answer: Faithful port.**
- **Q4 mint-failure backoff (from log observation 1):** add in M3.5 (recommended) /
  skip.
  → **Answer: Add it in M3.5.**

**User requirements (binding, from the 2026-10-28 planning round):**
1. Every drop gets a **short hardware checklist** (Windows PC + Redmi Note 10S) —
   the §4 gate column is that checklist; expand it in the handoff message.
2. **No M2 regressions** — full M2 gate (voice, tools, captions, watchdog, drag)
   re-checked on every drop before sign-off.
3. When the speech bubble moves onto the cursor overlay (M3.3): stays dismissible
   (click or ✕) and **never blocks clicks on what's behind it** — hit-test
   exceptions ONLY on the cloud/badge/card; everywhere else HTTRANSPARENT.
4. Every drop is **pushed only after CI is green** (3 artifacts verified).

## 6. Risks & mitigations

- **Overlay over games/fullscreen apps** (topmost fights): original uses plain
  topmost; keep plain topmost, no SetWindowRgn exotica. If a user reports fights,
  revisit later (not M3 scope).
- **Perf:** everything cached (`BitmapCache`, frozen brushes/geometry); per-frame
  work = transforms + pupil/blink math only. If FPS dips on the user's PC, drop
  trails first (setting exists), effects second.
- **Multi-monitor:** original = main screen only; same for M3 (PARITY says "across
  the main screen"). Display-change rebuild handles dock/undock.
- **WPF animation drift vs CA:** Swift uses CALayer springs; WPF has no damped
  springs out of the box → implement the spring integrator in pure math
  (GooglyRealtime, testable) and drive transforms from it. Same for Bézier easing.
- **Sandbox wipes:** every increment is committed + pushed immediately after CI
  green; plan.md/worklog.md updated in the same commit.

## 7. Per-increment protocol (repeat exactly)

1. Read this plan + `PARITY.md` § for the slice; read the original Swift file(s)
   top-to-bottom before writing C#.
2. Port with pure math in `GooglyRealtime` + thin WPF; tests for every formula.
3. Run FULL suites both sides (136/136 + 136/136 baseline, grow with new tests).
4. Update `PROGRESS.md` (M3.n section), this file's table, `report.md` §5/§7,
   `worklog.md`; bump versions both sides.
5. Commit → push → watch CI → verify 3 artifacts → hand the user the gate checklist
   from §4 → wait for hardware confirmation before the next increment.
