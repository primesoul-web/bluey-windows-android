# Bluey — Android client

Kotlin + Jetpack Compose, **minSdk 29** (Android 10). Replaces the original's iPhone app: a landscape-only, always-on black screen with his face.

## Status

M0 skeleton — builds an APK; no behavior yet. See [PARITY.md](../PARITY.md) for the roadmap.

## Component mapping

| Original (iOS) | Here (Android) |
| --- | --- |
| `NWBrowser` (Bonjour) | `NsdManager` discovering `_googly._tcp` |
| `NWConnection` (the link) | `java.net.Socket` — **raw TCP with NDJSON framing, not a WebSocket** |
| `URLSessionWebSocketTask` (Realtime) | OkHttp WebSocket — **no `OpenAI-Beta` header** (the API dropped it 2026-05-12) |
| `AVAudioEngine` mic tap | One `AudioRecord` (24 kHz PCM16 mono, echo-cancelled) feeding **both** the Realtime stream and the notes recorder |
| `AVAudioFile` AAC chunks | `MediaCodec`/`MediaMuxer` → AAC-LC 48 kbps, 300-second chunks |
| Chirp via `AVAudioPlayerNode` | The same waveform synthesized in code, played via `AudioTrack` (exact constants in PARITY.md) |
| `UIImpactFeedbackGenerator` | `Vibrator` / `HapticFeedbackConstants` |
| `UIPasteboard` / `ShareLink` | `ClipboardManager` / `ACTION_SEND` |
| `UserDefaults` | `SharedPreferences` |
| `sessions.json` in Documents | App-private storage (`filesDir`) |
| Idle timer disabled | `FLAG_KEEP_SCREEN_ON` |

## Permissions (manifest)

Network + discovery: `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`.
Live voice (M2): `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`.

## Build

JDK 17 + Android SDK (platform 35) + Gradle 8.9+:

```
cd android && gradle assembleDebug
```

CI builds it on every push.
