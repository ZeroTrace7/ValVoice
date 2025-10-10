# ValVoice

Lightweight Valorant chat narrator that converts in-game chat messages to spoken audio (Windows + JavaFX). Chat messages are acquired via an external XMPP bridge process (required) or a fallback embedded Node.js stub for demo only.

## Quick Start Guide (MANDATORY SETUP)

### ⚠️ CRITICAL: Install VB-Audio Virtual Cable FIRST
**This application WILL NOT WORK without VB-Audio Virtual Cable installed!**

1. **Download VB-Audio Virtual Cable**: https://vb-audio.com/Cable/
2. **Install the driver** and **reboot Windows** (reboot is mandatory)
3. **Verify installation**: 
   - Open Windows Sound Settings
   - Check that you have:
     - Playback device: `CABLE Input (VB-Audio Virtual Cable)`
     - Recording device: `CABLE Output (VB-Audio Virtual Cable)`

### ✅ Audio Routing (Automatic - No Additional Software Needed!)
ValVoice now includes **built-in audio routing** that automatically configures your system to send TTS output to VB-CABLE.

- ✨ **No external tools required** - Audio routing happens automatically
- 🔧 **Automatic detection** - The app will detect and configure VB-CABLE on startup
- ⚡ **Instant setup** - No manual configuration needed

**Note:** The old SoundVolumeView.exe is no longer required. If you have it, the app will use it as a fallback, but the built-in routing works better.

### 🔧 Build valvoice-xmpp.exe
The XMPP bridge is required for real Valorant chat integration.

**Option A: Automatic Build (Easiest)**
- Just run the application! If Node.js is installed, it will auto-build `valvoice-xmpp.exe` on first launch.

**Option B: Manual Build**
```bat
cd xmpp-bridge
npm install
npm run build:exe
cd ..
```
This creates `valvoice-xmpp.exe` in the root folder.

**Prerequisites for building:**
- Node.js 18+ installed (https://nodejs.org/)
- npm (comes with Node.js)

### 🎮 Configure Valorant Audio Settings
**After installing VB-Audio Virtual Cable:**

1. Launch Valorant
2. Go to **Settings** → **Audio** → **Voice Chat**
3. Set **Input Device** to: `CABLE Output (VB-Audio Virtual Cable)`
4. Leave your normal headset as the output device

**How it works:**
```
ValVoice TTS → CABLE Input → CABLE Output → Valorant Mic → Your Teammates Hear It
```

### ▶️ Running ValVoice
```bat
java -jar target\valvoice-1.0.0.jar
```

Or use Maven:
```bat
mvn javafx:run
```

Check the status bar for "XMPP: Ready" to confirm the bridge is running.

---

## 1. Architecture Overview

Java (ValVoice) does NOT open Riot's XMPP socket directly.
Instead:
1. External executable `valvoice-xmpp.exe` connects to Riot's XMPP server.
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
| XMPP Bridge (native) | `valvoice-xmpp.exe` | Strongly Recommended | Real Riot XMPP connection + stanza → JSON stream |
| XMPP Stub (fallback) | Embedded `xmpp-node.js` | Automatic fallback | Demo / test only (simulated messages) |
| Virtual Audio Driver | VB-Audio Virtual Cable | REQUIRED (MUST INSTALL) | Provides `CABLE Input` / `CABLE Output` virtual loopback |

### 2.1 Building the Bridge Executable (valvoice-xmpp.exe)
A minimal stub project is included under `xmpp-bridge/` that you can package into a Windows executable. This stub emits simulated events; replace it with a real XMPP client for live chat.

Prereqs:
- Install Node.js 18+ (https://nodejs.org/)

Steps (Windows cmd.exe):
```bat
cd xmpp-bridge
npm install
npm run build:exe
cd ..
```
This produces `valvoice-xmpp.exe` in the repository root (same folder as the JAR).

Place `valvoice-xmpp.exe` next to your runnable JAR before launching. The app only supports this filename.

## 3. Message Processing Flow
1. Read JSON line from bridge stdout.
2. If `type == incoming`:
   - Extract raw XML string `data`.
   - If it starts with `<message`, construct `Message`.
   - Pass to `ChatDataHandler.message(msg)` → filtering & narration.
3. If `<iq ... id="_xmpp_bind1" ...>` stanza encountered: extract `<jid>` and update self ID (player identity).
4. Other event types currently logged: `error`, `close-riot`, `close-valorant`, `heartbeat`, `startup`, `shutdown`.

## 4. Key Classes
- `Main`: Launch point; spawns bridge process. Uses `valvoice-xmpp.exe`, falls back to `xmpp-node.js` stub only if the exe is missing (or build fails).
- `ValVoiceController`: UI + initialization + dependency checks; auto-routes audio via SoundVolumeView if present.
- `ChatDataHandler`: Self ID management, message entry point.
- `Message`: Parses XML stanza and determines channel, author, content, own-message state.
- `Chat`: Configuration + statistics.
- `TtsEngine`: Simple queued SAPI voice execution.
- `InbuiltVoiceSynthesizer`: Persistent System.Speech engine + optional audio routing attempt.

## 5. Running
### 5.1 Prerequisites
- JDK 17 (runtime)
- Windows (PowerShell + SAPI voice stack)
- VB-Audio Virtual Cable installed (mandatory)
- `valvoice-xmpp.exe` placed with the JAR (see 2.1) or allow auto-build on app start
- (Optional) `SoundVolumeView.exe` present for automatic routing (see 2.2)

### 5.2 Build
```bat
mvn clean package -DskipTests
```
Output JAR: `target\valvoice-1.0.0.jar`

### 5.3 Run
```bat
mvn javafx:run
:: or
java -jar target\valvoice-1.0.0.jar
```
Check status bar:
- XMPP: Ready (means bridge exe found/started)
- Mode: external-exe (real messages) or embedded-script (stub only)
- VB-Cable: Detected
- AudioRouting: Ready

## 6. Troubleshooting
| Issue | Cause | Action |
|-------|-------|--------|
| XMPP shows "Stub (demo only)" | Bridge exe missing or build failed | Ensure Node/npm installed and let app auto-build; or run steps in 2.1 |
| No sound in Valorant | Audio not routed to VB-CABLE | Place `SoundVolumeView.exe` next to JAR or route manually in Windows |
| No VB-CABLE devices | Driver not installed | Install VB-CABLE and reboot |
| Self ID not shown | Bind stanza or session lookup failed | Ensure Valorant is running; check logs |

## 7. Security / Notes
- Do not log secrets from the Riot lockfile.
- The included bridge is a stub; implement a real XMPP client for production use.
