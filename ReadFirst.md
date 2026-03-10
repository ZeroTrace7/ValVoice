# ValVoice — AI Agent Context Document

> **PURPOSE:** This file is the primary context source for AI coding agents working on this codebase. It is structured for deterministic parsing: flat headers, explicit dependency graphs, tagged metadata, and zero ambiguity.

---

## IDENTITY

- **Name:** ValVoice
- **Type:** Desktop Application (Windows-only)
- **One-Line Summary:** Real-time XMPP MITM tactical TTS engine for Valorant in-game chat.
- **Primary Language:** Java 17
- **UI Framework:** JavaFX (FXML + CSS)
- **Build System:** Maven (Shade Plugin → fat JAR)
- **Secondary Runtime:** Node.js 18+ (TypeScript MITM proxy)
- **OS Target:** Windows 10+ (64-bit only)

---

## 🛑 Strict Architectural Rules (For AI Context)

> **CRITICAL — These rules are non-negotiable. Any AI agent generating code for this project MUST obey every rule below. Violations will break runtime behavior or trigger anti-cheat detection.**

### Rule 1 — Push-to-Talk (PTT) Key Simulation

- **NEVER** use `java.awt.Robot` for key simulation.
- PTT **must exclusively** use the native Windows `SendInput` API via **JNA** (`com.sun.jna`).
- **Reason:** Riot Vanguard's kernel-level anti-cheat masks `java.awt.Robot` synthetic input events. Only `SendInput` via JNA bypasses Vanguard's input filtering and delivers keypresses to the game process.
- **Affected file:** `VoiceGenerator.java`

### Rule 2 — Audio Routing

- **Do NOT** attempt to switch audio devices using Java `Mixer` APIs or `javax.sound.*`.
- Audio routing is **strictly handled** by executing `SoundVolumeView.exe` via `ProcessBuilder`.
- **Reason:** Java's `Mixer` API cannot target per-process audio output on Windows. `SoundVolumeView.exe` is the only mechanism that routes the JVM's audio output to VB-Audio Virtual Cable at the OS mixer level.
- **Affected file:** `InbuiltVoiceSynthesizer.java`

### Rule 3 — Engine Fallback (XTTS → SAPI)

- If the local XTTS engine fails (state becomes `EngineState.DEGRADED`), TTS requests **must instantly bypass** the HTTP queue.
- Fallback route: invoke `SapiVoiceEngine.java`, which uses **PowerShell** to call the Windows SAPI COM interface and generate a `.wav` file on disk.
- The fallback must be **synchronous within the consumer thread** — no additional queuing.
- **Affected files:** `VoiceGenerator.java`, `InbuiltVoiceSynthesizer.java`, `SapiVoiceEngine.java`

### Rule 4 — Thread Safety

- The JavaFX `MediaPlayer` **MUST** be instantiated inside `Platform.runLater()`. Creating it on a background thread will throw `IllegalStateException` or produce silent failures.
- All TTS HTTP requests and file caching **MUST** occur on a background daemon consumer thread (the `BlockingQueue` consumer in `VoiceGenerator.java`).
- **NEVER** block the JavaFX Application Thread with network I/O, file I/O, or `Thread.sleep()`.
- **Affected files:** `VoiceGenerator.java`, `InbuiltVoiceSynthesizer.java`, `ValVoiceController.java`

---

## TECH STACK

| Layer               | Technology                                    |
|---------------------|-----------------------------------------------|
| Language            | Java 17                                       |
| UI                  | JavaFX 21 (FXML + CSS)                        |
| Build               | Maven + Shade Plugin (fat JAR)                |
| Proxy Runtime       | Node.js 18+                                   |
| Proxy Language      | TypeScript → compiled to `valvoice-mitm.exe`  |
| Protocol            | XMPP over TLS                                 |
| XML Parsing         | StAX (javax.xml.stream)                       |
| JSON                | Gson                                          |
| Audio Playback      | JavaFX MediaPlayer                            |
| Audio Routing       | SoundVolumeView.exe (ProcessBuilder)          |
| Virtual Audio       | VB-Audio Virtual Cable                        |
| Key Simulation      | JNA → Windows SendInput API                   |
| HTTP Client         | java.net.http.HttpClient (SSL bypass)         |
| OS                  | Windows 10+ only                              |

---

## ARCHITECTURE — Pipeline Overview

```
Riot Client
    │
    │  [TLS / XMPP encrypted]
    ▼
valvoice-mitm.exe            ← Node.js MITM Proxy (TLS termination, XML capture)
    │
    │  [JSON via stdout pipe]
    ▼
ValVoiceBackend.java          ← Java process manager, state machine, packet reader
    │
    │  [raw XML string]
    ▼
XmppStreamParser.java         ← StAX pull-parser → ParsedMessage DTO
    │
    ▼
ChatDataHandler.java           ← Channel filter, game-state filter, validation
    │
    │  [Message object]
    ▼
VoiceGenerator.java            ← BlockingQueue consumer, PTT via JNA SendInput
    │
    ├──► InbuiltVoiceSynthesizer.java  ← HTTP POST to local XTTS engine, MD5 cache
    │        │
    │        ├──► SoundVolumeView.exe  ← Routes JVM audio → VB Cable
    │        └──► JavaFX MediaPlayer   ← Plays audio (instantiated in Platform.runLater)
    │
    └──► SapiVoiceEngine.java          ← Fallback: PowerShell SAPI → .wav file
    │
    ▼
VB Cable (Virtual Device)     ← Appears as microphone input
    │
    ▼
Valorant Voice Chat            ← Teammates hear TTS
```

---

## PROJECT STRUCTURE

```
ValVoice/
├── pom.xml                           # Maven build config (Java 17, Shade Plugin)
├── SoundVolumeView.exe               # Audio routing CLI tool (must be in project root)
├── dependency-reduced-pom.xml        # Auto-generated by Shade Plugin
├── LICENSE
├── README.md                         # User-facing README
├── ReadFirst.md                      # THIS FILE — AI agent context
│
├── mitm/                             # Node.js MITM proxy subsystem
│   ├── src/                          # TypeScript source files
│   ├── certs/                        # Locally generated TLS certificates
│   ├── package.json                  # npm manifest + build:exe script
│   ├── tsconfig.json                 # TypeScript compiler config
│   ├── valvoice-mitm.exe             # Compiled proxy binary
│   ├── QUICKSTART.md
│   └── README.md
│
├── engine/                           # Local XTTS engine runtime (Python-based)
├── installer/                        # Inno Setup installer assets
├── PowerShell/                       # Helper PowerShell scripts (incl. SAPI fallback)
│
└── src/main/
    ├── java/
    │   ├── module-info.java          # JPMS module descriptor
    │   └── com/someone/
    │       ├── valvoicebackend/      # Core backend logic
    │       │   ├── APIHandler.java
    │       │   ├── Chat.java
    │       │   ├── ChatDataHandler.java
    │       │   ├── ChatUtilityHandler.java
    │       │   ├── ConnectionHandler.java
    │       │   ├── EntitlementsTokenResponse.java
    │       │   ├── GameStateManager.java
    │       │   ├── HtmlEscape.java
    │       │   ├── InbuiltVoiceSynthesizer.java
    │       │   ├── LockFileHandler.java
    │       │   ├── Message.java
    │       │   ├── ParsedMessage.java
    │       │   ├── PlayerAccount.java
    │       │   ├── RiotClientDetails.java
    │       │   ├── RiotUtilityHandler.java
    │       │   ├── Roster.java
    │       │   ├── Source.java
    │       │   ├── VoiceGenerator.java
    │       │   └── XmppStreamParser.java
    │       └── valvoicegui/          # JavaFX GUI layer
    │           ├── Main.java
    │           ├── MessageType.java
    │           ├── ValVoiceApplication.java
    │           ├── ValVoiceBackend.java
    │           └── ValVoiceController.java
    └── resources/                    # FXML layouts, CSS themes, fonts
```

---

## FILE REFERENCE — Backend (`com.someone.valvoicebackend`)

Each entry follows the format: `File` → Responsibility → Key behavior → Dependencies → Pattern.

---

### `ValVoiceBackend.java`
- **Role:** Central orchestrator and lifecycle manager.
- **Behavior:** Launches `valvoice-mitm.exe` as a child process via `ProcessBuilder`. Reads JSON from stdout via `BufferedReader`. Implements state machine: `STOPPED → READY → RUNNING → DEGRADED`. Runs process reaper on startup to kill orphaned mitm instances. Registers JVM shutdown hook for child process cleanup.
- **Depends on:** `XmppStreamParser`, `ChatDataHandler`, `GameStateManager`, `valvoice-mitm.exe`
- **Depended on by:** `ValVoiceController` (via GUI `ValVoiceBackend` facade)
- **Pattern:** Orchestrator, State Machine, Process Manager

### `XmppStreamParser.java`
- **Role:** Streaming XML parser for XMPP packets.
- **Behavior:** Uses StAX pull-parser (`javax.xml.stream`) for incremental parsing. Extracts message body, sender JID, channel/MUC room, timestamp, stanza type. Discards malformed XML silently. Returns `ParsedMessage` DTOs.
- **Depends on:** `ParsedMessage`
- **Depended on by:** `ValVoiceBackend`
- **Pattern:** Parser, Data Transformer

### `ParsedMessage.java`
- **Role:** Immutable DTO carrying parsed XMPP data.
- **Fields:** message text, sender JID, channel identifier, timestamp.
- **Depends on:** nothing
- **Depended on by:** `XmppStreamParser`, `ChatDataHandler`
- **Pattern:** DTO

### `Message.java`
- **Role:** Runtime chat message model with HTML unescaping.
- **Fields:** sender display name, message content, source channel.
- **Behavior:** Calls `HtmlEscape` to convert `&amp;` → `&`, `&lt;` → `<`, etc.
- **Depends on:** `HtmlEscape`
- **Depended on by:** `ChatDataHandler`, `VoiceGenerator`
- **Pattern:** Domain Model

### `Chat.java`
- **Role:** Runtime config state for channel filtering.
- **Fields:** boolean flags — `self`, `party`, `team`, `all`.
- **Behavior:** Flags toggled by UI checkboxes. Read by `ChatDataHandler` to accept/reject messages.
- **Depends on:** nothing
- **Depended on by:** `ChatDataHandler`, `ValVoiceController`
- **Pattern:** Shared State Object

### `ChatDataHandler.java`
- **Role:** Message validation, filtering, and routing gateway.
- **Behavior:** Receives `ParsedMessage` → validates non-null, non-empty, not blocked → checks channel flag in `Chat` → checks `GameStateManager` for smart-mute → wraps into `Message` → dispatches to `VoiceGenerator`. Drops silently on failure.
- **Depends on:** `ParsedMessage`, `Message`, `Chat`, `GameStateManager`, `VoiceGenerator`, `ChatUtilityHandler`
- **Depended on by:** `ValVoiceBackend`
- **Pattern:** Middleware, Filter Chain

### `GameStateManager.java`
- **Role:** Singleton game state tracker.
- **Behavior:** Parses XMPP presence stanzas → detects state transitions `MENU → PREGAME → IN_MATCH`. Exposes `getCurrentState()` for smart-muting.
- **Depends on:** nothing
- **Depended on by:** `ChatDataHandler`, `ValVoiceBackend`
- **Pattern:** Singleton, State Machine

### `VoiceGenerator.java`
- **Role:** Serialized speech queue with PTT automation.
- **Behavior:** Maintains `BlockingQueue<Message>` consumed by a daemon thread. For each message: (1) presses PTT key via JNA `SendInput`, (2) waits pre-transmission delay, (3) calls `InbuiltVoiceSynthesizer` for audio, (4) waits for playback completion, (5) releases PTT key. Falls back to `SapiVoiceEngine` if engine state is `DEGRADED`. Persists config (PTT key, voice, delays) to disk.
- **Depends on:** `InbuiltVoiceSynthesizer`, `SapiVoiceEngine`, `Message`, JNA
- **Depended on by:** `ChatDataHandler`, `ValVoiceController`
- **Pattern:** Producer-Consumer, Command Queue

### `InbuiltVoiceSynthesizer.java`
- **Role:** Local TTS HTTP client with MD5 audio cache and OS audio routing.
- **Behavior:** HTTP POST to local XTTS engine (`engine/`) → downloads audio file → caches with MD5-hashed filename → LRU eviction on cache size limit → invokes `SoundVolumeView.exe` to route audio to VB Cable → plays via JavaFX `MediaPlayer` (instantiated in `Platform.runLater`).
- **Depends on:** `SoundVolumeView.exe`, JavaFX `MediaPlayer`, local XTTS engine
- **Depended on by:** `VoiceGenerator`
- **Pattern:** Service Client, Cache-Aside

### `LockFileHandler.java`
- **Role:** Riot Client lockfile reader.
- **Behavior:** Reads `%LOCALAPPDATA%/Riot Games/Riot Client/` lockfile → parses process name, PID, port, password, protocol. Exposes port + password for local API auth.
- **Depends on:** filesystem
- **Depended on by:** `APIHandler`, `ConnectionHandler`
- **Pattern:** Reader Utility

### `ConnectionHandler.java`
- **Role:** HTTP client factory with localhost SSL bypass.
- **Behavior:** Creates `HttpClient` that trusts all certificates. Bypass scoped to `127.0.0.1` only (required because Riot local API uses self-signed cert).
- **Depends on:** nothing (JDK APIs only)
- **Depended on by:** `APIHandler`
- **Pattern:** Factory

### `APIHandler.java`
- **Role:** Riot local API client.
- **Behavior:** Uses `ConnectionHandler` for SSL-bypass client + `LockFileHandler` for port/password. Calls entitlements + player identity endpoints. Returns PUUID, display name, entitlements token. Never logs credentials.
- **Depends on:** `ConnectionHandler`, `LockFileHandler`, `EntitlementsTokenResponse`, `PlayerAccount`, `RiotUtilityHandler`
- **Depended on by:** `ChatDataHandler`
- **Pattern:** Service Client

### `EntitlementsTokenResponse.java`
- **Role:** Gson DTO for Riot entitlements API response.
- **Fields:** entitlements JWT token.
- **Depends on:** Gson annotations
- **Depended on by:** `APIHandler`
- **Pattern:** Response DTO

### `PlayerAccount.java`
- **Role:** Local player identity model.
- **Fields:** PUUID, gameName, tagLine.
- **Depends on:** nothing
- **Depended on by:** `APIHandler`, `ChatDataHandler`
- **Pattern:** Domain Model

### `RiotClientDetails.java`
- **Role:** Value object for Riot Client connection params.
- **Fields:** port, password.
- **Depends on:** nothing
- **Depended on by:** `LockFileHandler`, `APIHandler`, `ConnectionHandler`
- **Pattern:** Value Object

### `RiotUtilityHandler.java`
- **Role:** Shared helpers for Riot API calls.
- **Behavior:** Builds Authorization headers, constructs local API base URLs.
- **Depends on:** nothing
- **Depended on by:** `APIHandler`
- **Pattern:** Utility

### `ChatUtilityHandler.java`
- **Role:** Shared helpers for chat processing.
- **Behavior:** JID normalization, channel name extraction from MUC room IDs, message length guards.
- **Depends on:** nothing
- **Depended on by:** `ChatDataHandler`
- **Pattern:** Utility

### `HtmlEscape.java`
- **Role:** HTML entity decoder.
- **Behavior:** Static methods: `&quot;` → `"`, `&#39;` → `'`, `&amp;` → `&`, `&lt;` → `<`, `&gt;` → `>`.
- **Depends on:** nothing
- **Depended on by:** `Message`
- **Pattern:** Utility

### `Roster.java`
- **Role:** In-memory player roster.
- **Behavior:** Stores known players keyed by JID. Used for display name resolution and filtering.
- **Depends on:** nothing
- **Depended on by:** `ValVoiceBackend`, `ChatDataHandler`
- **Pattern:** Domain Model, Registry

### `Source.java`
- **Role:** Enum for message channel types.
- **Values:** `SELF`, `PARTY`, `TEAM`, `ALL`
- **Depends on:** nothing
- **Depended on by:** `ParsedMessage`, `ChatDataHandler`, `Chat`
- **Pattern:** Enum

---

## FILE REFERENCE — GUI (`com.someone.valvoicegui`)

### `Main.java`
- **Role:** Application entry point.
- **Behavior:** (1) Checks for running Riot Client / Valorant processes. (2) Runs process reaper for orphaned `valvoice-mitm.exe`. (3) Loads persisted config. (4) Calls `Application.launch(ValVoiceApplication.class)`.
- **Depends on:** `ValVoiceApplication`
- **Pattern:** Entry Point

### `ValVoiceApplication.java`
- **Role:** JavaFX bootstrap.
- **Behavior:** Overrides `Application.start()` → loads FXML layout → applies CSS theme → loads fonts → creates and shows primary `Stage`.
- **Depends on:** `ValVoiceController` (via FXMLLoader reflection), `module-info.java`
- **Pattern:** Application Bootstrap

### `ValVoiceController.java`
- **Role:** Primary UI controller (largest file).
- **Behavior:** Wires all `@FXML` controls (buttons, sliders, dropdowns, checkboxes) to backend. Handles Start/Stop via `ValVoiceBackend` facade. Receives backend events and updates UI console via `Platform.runLater()`. Manages audio routing setup. Persists user config. Color-codes log output by `MessageType`.
- **Depends on:** `ValVoiceBackend` (GUI facade), `Chat`, `VoiceGenerator`, `MessageType`
- **Pattern:** MVC Controller, Event Handler

### `ValVoiceBackend.java` *(GUI package)*
- **Role:** Facade bridging UI controller to core backend.
- **Behavior:** Exposes start/stop commands, status queries, error propagation. Delegates to `com.someone.valvoicebackend` classes.
- **Depends on:** all `valvoicebackend` classes
- **Depended on by:** `ValVoiceController`
- **Pattern:** Facade

### `MessageType.java`
- **Role:** Enum for console log categories.
- **Values:** `INFO`, `ERROR`, `SUCCESS`, `DEBUG` — each maps to a CSS color in the UI console.
- **Depends on:** nothing
- **Depended on by:** `ValVoiceController`
- **Pattern:** Enum

---

## FILE REFERENCE — Root Files

### `pom.xml`
- **Role:** Maven build config.
- **Key details:** Java 17 target. Dependencies: JavaFX controls/media/fxml, Gson, JNA, logging. Shade Plugin produces fat JAR `ValVoice.jar`.

### `module-info.java`
- **Role:** JPMS module descriptor.
- **Exports:** `com.someone.valvoicebackend`, `com.someone.valvoicegui`
- **Requires:** `javafx.controls`, `javafx.media`, `javafx.fxml`, `java.logging`, `com.sun.jna`

### `SoundVolumeView.exe`
- **Role:** Windows CLI for per-process audio device routing.
- **Called by:** `InbuiltVoiceSynthesizer.java` via `ProcessBuilder`
- **Must be present** in project root at runtime.

---

## FILE REFERENCE — MITM Proxy (`mitm/`)

### `mitm/src/` → `valvoice-mitm.exe`
- **Role:** TLS MITM proxy (first pipeline stage).
- **Behavior:** Binds `127.0.0.1` → intercepts Riot Client ↔ Riot XMPP server traffic → TLS termination with local certs (`mitm/certs/`) → wraps raw XML in JSON envelope → writes to stdout. Intentionally thin — no XML parsing here.
- **Consumed by:** `ValVoiceBackend.java` via stdout `BufferedReader`.

### `mitm/package.json`
- **Role:** npm manifest. Defines `build:exe` script (TypeScript → `valvoice-mitm.exe`).

### `mitm/tsconfig.json`
- **Role:** TypeScript compiler config. Strict mode, CommonJS output, `dist/` output dir.

---

## DATA FLOW (Numbered Steps)

```
Step 1: Riot Client sends encrypted XMPP to Riot servers.
Step 2: valvoice-mitm.exe intercepts via local TLS proxy → outputs JSON to stdout.
Step 3: ValVoiceBackend.java reads stdout stream → extracts raw XML string.
Step 4: XmppStreamParser.java parses XML → produces ParsedMessage DTO.
Step 5: ChatDataHandler.java validates message → checks channel flags + game state → produces Message.
Step 6: VoiceGenerator.java dequeues Message → presses PTT key (JNA SendInput).
Step 7: InbuiltVoiceSynthesizer.java sends HTTP POST to local XTTS engine → receives audio file.
        (Fallback: SapiVoiceEngine.java generates .wav via PowerShell SAPI if engine is DEGRADED.)
Step 8: SoundVolumeView.exe routes JVM audio output → VB Cable virtual device.
Step 9: JavaFX MediaPlayer plays audio → heard in Valorant voice chat as microphone input.
```

---

## DEPENDENCY GRAPH (Simplified)

```
Main.java
  └─► ValVoiceApplication.java
        └─► ValVoiceController.java
              ├─► ValVoiceBackend.java (GUI facade)
              │     └─► ValVoiceBackend.java (backend orchestrator)
              │           ├─► valvoice-mitm.exe (child process)
              │           ├─► XmppStreamParser.java → ParsedMessage.java
              │           ├─► ChatDataHandler.java
              │           │     ├─► Chat.java (channel flags)
              │           │     ├─► GameStateManager.java
              │           │     ├─► ChatUtilityHandler.java
              │           │     └─► VoiceGenerator.java
              │           │           ├─► InbuiltVoiceSynthesizer.java
              │           │           │     ├─► SoundVolumeView.exe
              │           │           │     └─► JavaFX MediaPlayer
              │           │           └─► SapiVoiceEngine.java (SAPI fallback)
              │           ├─► APIHandler.java
              │           │     ├─► ConnectionHandler.java
              │           │     ├─► LockFileHandler.java → RiotClientDetails.java
              │           │     ├─► RiotUtilityHandler.java
              │           │     ├─► EntitlementsTokenResponse.java
              │           │     └─► PlayerAccount.java
              │           └─► Roster.java
              ├─► Chat.java
              ├─► VoiceGenerator.java
              └─► MessageType.java
```

---

## SECURITY MODEL

| Constraint                  | Implementation                                              |
|-----------------------------|-------------------------------------------------------------|
| Localhost-only binding      | MITM proxy binds exclusively to `127.0.0.1`                |
| No memory injection         | Zero interaction with Valorant process memory               |
| No DLL hooking              | No shared libraries injected into any game process          |
| No remote API keys          | All API calls target `localhost` only                       |
| Absolute executable paths   | `ProcessBuilder` uses verified absolute paths               |
| Safe XML parsing            | StAX parser; malformed packets silently discarded           |
| SSL bypass scope            | SSL validation bypass limited to `127.0.0.1` only          |
| Process cleanup             | Reaper kills orphaned processes on startup + shutdown hook  |
| No credential logging       | `APIHandler` suppresses token output in logs                |
| PTT via native API          | JNA `SendInput` (not `java.awt.Robot`) to avoid Vanguard   |

---

## REQUIREMENTS

| Requirement              | Version / Detail                          |
|--------------------------|-------------------------------------------|
| OS                       | Windows 10+ (64-bit)                      |
| Java                     | JDK 17+                                   |
| Node.js                  | 18+                                       |
| VB-Audio Virtual Cable   | Latest (https://vb-audio.com/Cable/)      |
| SoundVolumeView.exe      | Must be in project root at runtime        |
| Valorant + Riot Client   | Installed and accessible                  |
| JNA                      | Included via Maven dependency             |

---

## BUILD INSTRUCTIONS

### 1. Prerequisites

```bash
java -version    # Must be 17+
node -v          # Must be 18+
# Install VB-Audio Virtual Cable from https://vb-audio.com/Cable/
```

### 2. Clone

```bash
git clone https://github.com/your-username/ValVoice.git
cd ValVoice
```

### 3. Build MITM Proxy

```bash
cd mitm
npm install
npm run build:exe
cd ..
```

### 4. Build Java Application

```bash
mvn clean install
```

Output: `target/valvoice-1.0.0.jar`

---

## RUN INSTRUCTIONS

### Development

```bash
mvn javafx:run
```

### Production

```bash
java -jar ValVoice.jar
```

> `SoundVolumeView.exe` must be in the same directory as the JAR. VB-Audio Virtual Cable must be installed and active.

---

## BUILD STATUS

| Component        | Status         |
|------------------|----------------|
| MITM Proxy       | 🟢 Stable      |
| TTS Engine       | 🟢 Stable      |
| Java Backend     | 🟢 Stable      |
| JavaFX UI        | 🟢 Stable      |
| Security Audit   | 🟢 Verified    |
| Overall          | 🏆 Golden Build |
