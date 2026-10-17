# The Googly wire protocol

What the Windows host and the Android client say to each other. **Frozen to be byte-compatible with the original app** (`Shared/GooglyLink.swift`, `Mac/PhoneServer.swift`, `iOS/MacLink.swift` in [rbrown101010/bluey-by-riley](https://github.com/rbrown101010/bluey-by-riley) @ `6d84d91`). If this document and the original code ever disagree, the original code wins.

## Transport

| Property | Value |
| --- | --- |
| Discovery | Bonjour/mDNS, service type **`_googly._tcp`** |
| Who advertises | The **PC** (service instance name = the PC's hostname); phones browse |
| Link | Plain TCP socket |
| Socket options | `TCP_NODELAY` on; TCP keepalive, 2 s idle |
| Framing | Newline-delimited JSON — one UTF-8 JSON object per line, terminated by `0x0A` |
| Read chunking | Up to 1 MiB per read; partial lines buffered until the newline arrives |
| Field encoding | camelCase; **unset fields are omitted** (never `null`); decoders **must ignore unknown fields** |

The original also enabled Apple peer-to-peer (AWDL); the port uses ordinary LAN TCP, which the original accepts as well.

## The Packet

Every line is a `Packet`. All fields are optional; a packet usually carries just one or two.

| Field | Type | Purpose |
| --- | --- | --- |
| `face` | FaceState | What the phone's face should show right now (PC → phone) |
| `hello` | string | Sent once by each side right after the connection is ready: the sender's device name |
| `volume` | number 0–1 | **Legacy** (speech-audio era). Accept, never emit |
| `command` | string | A request, reply, or event — see the table below |
| `audio` | base64 string | AAC meeting-notes chunk (with `transcribe`); legacy mp3 speech otherwise |
| `speech` | integer | **Legacy**: which speech an audio packet or playing/done event belonged to. Accept, never emit |
| `callID` | string | Correlates a request with its reply (UUID minted by the requester, echoed verbatim) |
| `tool` | string | On a `tool` request: the tool name (e.g. `look_at_screen`) |
| `text` | string | Free text: tool arguments or output, a minted client secret, a caption, or JSON metadata |
| `image` | base64 string | JPEG that accompanies a `toolResult` (the screenshot from `look_at_screen`) |

## FaceState

| Field | Range | Meaning |
| --- | --- | --- |
| `gazeX` | −1 … 1 | −1 looks left, 1 looks right |
| `gazeY` | −1 … 1 | −1 looks up, 1 looks down |
| `mood` | enum | `listening` · `resting` · `thinking` · `talking` · `pointing` · `happy` · `sleepy` |
| `talk` | 0 … 1 | Voice level; drives the talking bounce |

## Commands

| Command | Direction | Payload | Reply | Notes |
| --- | --- | --- | --- | --- |
| `hello` | both, once | `hello` = device name | — | Sent right after the socket is ready; the PC follows with the current face |
| `face` | PC → phone | `face` | — | Throttled (rules below); ≥ 1/s so the phone knows the PC is driving |
| `wake` / `sleep` | PC → phone (broadcast) | — | — | Wake/sleep hotkey, menu item, or auto-sleep when the phone disconnects while awake |
| `awake` / `asleep` | phone → PC | — | — | Session state sync |
| `realtimeToken` | phone → PC | `callID` | same command + `callID` + `text` = minted client secret (absent on failure) | PC calls `POST /v1/realtime/client_secrets` (600 s TTL, full session config) with its real key |
| `tool` | phone → PC | `callID`, `tool`, `text` = JSON arguments | `toolResult` + `callID` + `text` (+ `image`) | Tools execute strictly in arrival order |
| `toolResult` | PC → phone | `callID`, `text`, `image`? | — | |
| `transcribe` | phone → PC | `callID`, `audio` = AAC chunk, `text` = JSON `{session, index, started, sessionStarted}` | `transcribed` + `callID` + `text` = JSON `{segments: [{speaker, start, text}], notesPath}` | |
| `transcribed` | PC → phone | `callID`, `text` | — | |
| `notes` | phone → PC | `callID`, `text` = session JSON | same command + `callID` + `text` = notes folder path | PC writes `notes.md` + `session.json` |
| `caption` | phone → PC | `text` = his reply so far | — | Speech bubble updates live while he "speaks" |
| `captionDone` | phone → PC | `text` = final reply | — | Bubble stays for the read-time formula, then clears |
| `testVoice` · `playing` · `done` · `stopSpeech` | phone ↔ PC | — | — | **Legacy.** Accept and ignore; never emit |

## Rules

1. **Correlation** — the requester mints a UUID `callID`; the replier echoes it verbatim; a reply with an unknown `callID` is dropped.
2. **Face throttle** — skip a face packet when the last was < 1 s ago AND the mood is unchanged AND |ΔgazeX|, |ΔgazeY| < 0.004 AND |Δtalk| < 0.02; otherwise send. The 1 s force-resend keeps the phone informed that the PC is alive.
3. **(Re)connect handshake** — PC sends `hello` (its hostname), then the current `face`; phone replies `hello` (its device name).
4. **Reconnection** — phone retries every 1.5 s and reconnects to the preferred PC when it reappears; the PC's listener restarts 2 s after failure. Both sides re-send `hello`.
5. **No version negotiation** exists in the original. New fields must be optional and ignored by older peers; never change existing names.

## Examples

```
{"hello":"Studio PC"}
{"hello":"Pixel 9"}
{"face":{"gazeX":0.42,"gazeY":-0.31,"mood":"pointing","talk":0}}
{"command":"realtimeToken","callID":"9F13C2E4-…"}
{"command":"realtimeToken","callID":"9F13C2E4-…","text":"<client secret>"}
{"command":"tool","callID":"C2D4…","tool":"look_at_screen","text":"{}"}
{"command":"toolResult","callID":"C2D4…","text":"Frontmost app: Chrome\n…","image":"<base64 jpeg>"}
{"command":"caption","text":"That's the prompt box"}
{"command":"captionDone","text":"That's the prompt box, where you type to Claude. Cheeky little thing."}
```

## Fonts

`fonts/` carries Fredoka and IBM Plex (Sans, Mono Regular/Medium) from the original repository. They are **SIL Open Font License**, not Unlicense — the `OFL-*.txt` files must stay next to them. Both apps bundle these (Windows: WPF `Resource`; Android: `res/font/`).

## Conformance

[`packet.schema.json`](packet.schema.json) formally describes the Packet. M1 adds example fixtures and validates both platform codecs against the schema in CI.
