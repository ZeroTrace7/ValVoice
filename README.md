# ValVoice

Lightweight Valorant chat narrator that converts in-game chat messages to spoken audio (Windows + JavaFX). Chat messages are acquired via an external XMPP bridge process (preferred) or a fallback embedded Node.js stub.

## 1. Architecture Overview

Java (ValVoice) does NOT open Riot's XMPP socket directly.
Instead:
1. External executable `valorantNarrator-xmpp.exe` (preferred) OR embedded `xmpp-node.js` stub connects to Riot's XMPP server.
2. XMPP stanzas are wrapped into line-delimited JSON objects:
   ```json
   { "type": "incoming", "time": 1730000000000, "data": "<message ...>...</message>" }
   ```
3. Java spawns the bridge process at startup (see `Main.startXmppNodeProcess()`).
4. Java reads stdout line-by-line, parses JSON, and for `<message ...>` stanzas constructs `new Message(xml)` and routes it via `ChatDataHandler.message(...)`.
5. Filtered, permitted messages are narrated via SAPI / PowerShell (queued `TtsEngine`) or persistent System.Speech synthesizer.

## 2. External Components (Critical)

| Component | File | Required? | Purpose |
|-----------|------|-----------|---------|
| XMPP Bridge (native) | `valorantNarrator-xmpp.exe` | Strongly Recommended | Real Riot XMPP connection + stanza → JSON stream |
| XMPP Stub (fallback) | Embedded `xmpp-node.js` | Automatic fallback | Demo / test only (simulated messages) |
| Audio Routing Tool | `SoundVolumeView.exe` | Optional (recommended) | Route TTS output to VB-CABLE device automatically |
| Virtual Audio Driver | VB-Audio Virtual Cable | REQUIRED (MUST INSTALL) | Provides `CABLE Input` / `CABLE Output` virtual loopback |

### 2.1 XMPP Bridge Executable
- Place `valorantNarrator-xmpp.exe` in the SAME working directory as the launched JAR (or the directory you run `java -jar` from).
- Detected by name constant in `ValVoiceController` and by `Main.startXmppNodeProcess()`.
- If missing, the log will show: `External valorantNarrator-xmpp.exe not found; falling back to embedded xmpp-node.js stub`.
- Without it you will NOT receive real chat; only simulated sample messages from the stub.

### 2.2 Fallback Stub (Development Only)
- Resource: `src/main/resources/com/someone/valvoice/xmpp-node.js`.
- Emits: startup → bind iq (simulated self ID) → sample `<message>` stanzas → heartbeats → error + close events.
- Use only for development / UI + TTS verification.

### 2.3 SoundVolumeView Integration
- If `SoundVolumeView.exe` is present (working directory or `%ProgramFiles%/ValorantNarrator/`), the inbuilt synthesizer attempts to route audio to `CABLE Input` automatically.
- Otherwise, manual routing is required (Windows Sound Settings or per‑app audio routing utilities).

### 2.4 VB-Audio Virtual Cable (MANDATORY)
**You MUST install VB-Audio Virtual Cable** to inject narration into Valorant voice chat.
- Download: https://vb-audio.com/Cable/
- Install and reboot.
- Devices created:
  - Playback device: `CABLE Input (VB-Audio Virtual Cable)` – this is where ValVoice must send TTS.
  - Recording device: `CABLE Output (VB-Audio Virtual Cable)` – configure Valorant to use this as microphone.
- Audio Flow:
  ```text
  ValVoice TTS → (routed output) CABLE Input  →  CABLE Output (loopback)  →  Valorant Mic
  ```
- If either device name is missing at startup, ValVoice will log a WARNING: missing VB-Audio virtual cable devices.

### 2.5 Quick Verification
| Step | Expected Result |
|------|-----------------|
| Run `mmsys.cpl` (Sound control panel) | See CABLE Input (Playback) & CABLE Output (Recording) |
| Launch ValVoice | Log shows detection of XMPP bridge + (optionally) SoundVolumeView |
| Speak test (select voice) | Audio heard only if not routed; once routed Valorant teammates hear narration |
| Valorant Settings → Audio → Voice Input | Set to CABLE Output |

## 3. Message Processing Flow
1. Read JSON line from bridge stdout.
2. If `type == incoming`:
   - Extract raw XML string `data`.
   - If it starts with `<message`, construct `Message`.
   - Pass to `ChatDataHandler.message(msg)` → filtering & narration.
3. If `<iq ... id="_xmpp_bind1" ...>` stanza encountered: extract `<jid>` and update self ID (player identity) for own-message discrimination.
4. Other event types currently logged: `error`, `close-riot`, `close-valorant`, `heartbeat`, `startup`, `shutdown`.

## 4. Key Classes
- `Main`: Launch point; spawns bridge process.
- `ValVoiceController`: UI + initialization + dependency verification.
- `ChatDataHandler`: Self ID management, message entry point.
- `Message`: Parses XML stanza to determine channel, author, body, own-message state.
- `Chat`: Configuration (which channels to narrate) + statistics.
- `TtsEngine`: Simple queued SAPI voice execution.
- `InbuiltVoiceSynthesizer`: Persistent System.Speech engine + optional audio routing attempt.

## 5. Running
### 5.1 Prerequisites
- JDK 17 (runtime)
- Windows (PowerShell + SAPI voice stack)
- VB-Audio Virtual Cable installed (mandatory)
- (Recommended) `valorantNarrator-xmpp.exe` placed with the JAR
- (Optional) `SoundVolumeView.exe` present for automatic routing

### 5.2 Build
```bash
mvn clean package -DskipTests
```
Output JAR: `target/valvoice-1.0.0.jar`

### 5.3 Run
```bash
java -jar target/valvoice-1.0.0.jar
```
Check logs for:
- `Started XMPP bridge (mode: external-exe)` (real chat)
- OR `embedded-script` (simulation only)
- VB-Cable presence warnings if driver devices not found

### 5.4 Common Log Indicators
| Log Snippet | Meaning |
|-------------|---------|
| Self ID (bind) detected: <id> | Successful bind iq processed (player ID set) |
| Received message: (PARTY)ally123@... | Parsed message ready for possible narration |
| XMPP error event: ... | Bridge reported an error (transient / connectivity) |
| Valorant closed event received | Bridge observed client shutdown |
| WARNING missing VB-Audio virtual cable devices | Driver not installed / not enumerated |

## 6. Troubleshooting
| Issue | Cause | Action |
|-------|-------|--------|
| No messages, only heartbeats | Missing external bridge exe; stub only | Place `valorantNarrator-xmpp.exe` and restart |
| Voices list empty | SAPI / System.Speech enumeration failed | Reinstall voices, run PowerShell test manually |
| Not narrating own messages | SELF not included in source selection | Select a source combination including SELF |
| Wrong device output | Audio not routed to VB-CABLE | Use SoundVolumeView or set default playback manually |
| Self ID never detected | Bind stanza not received | Confirm bridge auth / connectivity; check lockfile credentials |
| VB-Cable warning at startup | Driver not installed / service not ready | Reinstall / reboot, verify devices in Sound panel |

## 7. Extending
- Add new event types: extend switch in `Main` → `handleIncomingStanza` for additional stanza categories.
- Implement reconnect/backoff: monitor exit code and relaunch bridge with exponential delay.
- Add presence/roster handling: parse non-`<message>` stanzas when needed.

## 8. Security / Notes
- Lockfile-based local auth should never be exfiltrated—avoid logging secrets.
- Current stub is NOT a secure implementation—replace with production-grade XMPP client.

## 9. License
(Insert license details here.)

---
If you add or rename the executable, update `XMPP_EXE_NAME` in `Main` and the documentation constant in `ValVoiceController`.
