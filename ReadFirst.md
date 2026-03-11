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
                              ┌─────────────────────────────────┐
                              │ STARTUP (Main.java)             │
                              │ EnvironmentValidator → diag     │
                              │ AudioRouterUtility → VB-Cable   │
                              │ ConfigManager.load() → config   │
                              └────────────┬────────────────────┘
                                           │
                                           ▼
Riot Client
    │
    │  [TLS / XMPP encrypted]
    ▼
valvoice-mitm.exe            ← Node.js MITM Proxy (TLS termination, XML capture)
    │
    │  [JSON via stdout pipe]
    ▼
ValVoiceBackend.java          ← Java process manager, state machine, packet reader
    │                            Also manages: engine/valorantNarrator-agentVoices.exe
    │  [raw XML string]          (EngineState: STOPPED→STARTING→READY→DEGRADED→STOPPING)
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
    │        │                            Queue(10), MediaPlayer playback, PTT injection
    │        │                            Config reads from ConfigManager.get()
    │        ├──► SoundVolumeView.exe  ← Routes JVM audio → VB Cable (via AudioRouterUtility)
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
├── README.txt                        # [Phase 8.2] End-user guide (bundled in release)
├── ReadFirst.md                      # THIS FILE — AI agent context
├── run-valvoice.bat                  # [Phase 8.2] Silent Windows launcher
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
│   ├── valorantNarrator-agentVoices.exe  # XTTS backend server (FastAPI on port 5005)
│   ├── agents/                       # Agent voice reference MP3s (e.g. jett.mp3)
│   └── ...                           # Python runtime, DLLs, libs
│
├── installer/                        # Inno Setup installer assets
├── PowerShell/                       # Helper PowerShell scripts (incl. SAPI fallback)
│
└── src/
    ├── assembly/
    │   └── distribution.xml              # [Phase 8.2] Maven assembly descriptor for release ZIP
    └── main/
    ├── java/
    │   ├── module-info.java          # JPMS module descriptor
    │   └── com/someone/
    │       ├── valvoicebackend/      # Core backend logic
    │       │   ├── APIHandler.java
    │       │   ├── AudioRouterUtility.java    # [Phase 5] SoundVolumeView audio routing
    │       │   ├── Chat.java
    │       │   ├── ChatDataHandler.java
    │       │   ├── ChatUtilityHandler.java
    │       │   ├── ConnectionHandler.java
    │       │   ├── EntitlementsTokenResponse.java
    │       │   ├── EnvironmentValidator.java   # [Phase 8] Startup dependency diagnostics
    │       │   ├── GameStateManager.java
    │       │   ├── HtmlEscape.java
    │       │   ├── InbuiltVoiceSynthesizer.java  # HTTP + Cache + Queue + Playback + PTT
    │       │   ├── LockFileHandler.java
    │       │   ├── Message.java
    │       │   ├── ParsedMessage.java
    │       │   ├── PlayerAccount.java
    │       │   ├── RiotClientDetails.java
    │       │   ├── RiotUtilityHandler.java
    │       │   ├── Roster.java
    │       │   ├── SapiVoiceEngine.java        # [Phase 6] Windows SAPI fallback engine
    │       │   ├── Source.java
    │       │   ├── VoiceGenerator.java
    │       │   ├── XmppStreamParser.java
    │       │   └── config/                      # [Phase 7] Persistent configuration
    │       │       ├── ConfigManager.java        # JSON config loader/saver (Gson)
    │       │       └── ValVoiceConfig.java        # Config POJO (pttKey, volume, etc.)
    │       └── valvoicegui/          # JavaFX GUI layer
    │           ├── Main.java
    │           ├── MessageType.java
    │           ├── SettingsController.java       # [Phase 7] Settings window controller
    │           ├── SetupWizardController.java    # [Phase 8] First-run setup wizard
    │           ├── ValVoiceApplication.java
    │           ├── ValVoiceBackend.java
    │           └── ValVoiceController.java
    └── resources/
        └── com/someone/valvoicegui/
            ├── mainApplication.fxml              # Primary UI layout
            ├── settings.fxml                     # [Phase 7] Settings window layout
            ├── setup-wizard.fxml                 # [Phase 8] First-run wizard layout
            ├── config.properties                 # Build-time metadata
            ├── style.css                         # CSS theme
            └── icons/                            # UI icons
```

---

## FILE REFERENCE — Backend (`com.someone.valvoicebackend`)

Each entry follows the format: `File` → Responsibility → Key behavior → Dependencies → Pattern.

---

### `ValVoiceBackend.java`
- **Role:** Central orchestrator, lifecycle manager, and XTTS engine process manager.
- **Behavior:** Launches `valvoice-mitm.exe` as a child process via `ProcessBuilder`. Reads JSON from stdout via `BufferedReader`. Implements MITM state machine: `STOPPED → READY → RUNNING → DEGRADED`. Runs process reaper on startup to kill orphaned mitm instances. Registers JVM shutdown hook for child process cleanup. *(Note: The XTTS engine lifecycle — EngineState, startEngine(), stopEngine() — is managed in the GUI-package ValVoiceBackend.java. See GUI section.)*
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
- **Role:** Local TTS HTTP client with MD5 audio cache, sequential queue, playback, and PTT injection.
- **Behavior:**
  - **HTTP Layer (Phase 4):** HTTP POST to local XTTS engine on `http://127.0.0.1:5005/speak` with 15s timeout. Sends JSON payload: `{agent, text, language}`. ConnectException triggers permanent `DEGRADED` state via `ValVoiceBackend.markDegraded()`.
  - **Cache Layer (Phase 4):** MD5 hash of `agent|text|language` → `.mp3` file in `%LOCALAPPDATA%\ValVoice\cache\`. Atomic `.tmp → .mp3` rename. 100MB eviction by `lastModified` (oldest first).
  - **Queue Layer (Phase 4):** `LinkedBlockingQueue(10)`, drop newest on full. Daemon consumer thread. DEGRADED state routes to SAPI fallback.
  - **Playback (Phase 5):** JavaFX `MediaPlayer` created inside `Platform.runLater()` with `CountDownLatch` sync. Fresh MediaPlayer per clip, always disposed. 60s latch timeout prevents permanent blocking.
  - **PTT Injection (Phase 5):** JNA `SendInput` via `user32.dll`. Key DOWN before playback (50ms pre-delay), key UP after playback (100ms tail delay) in `finally` block. `AtomicBoolean isPttPressed` prevents stuck keys.
  - **Recovery Probe (Phase 6):** Every 10s during DEGRADED, probes port 5005 via TCP socket (500ms timeout). On success, restores READY state.
  - **Config Integration (Phase 7):** Reads `ConfigManager.get()` for PTT key, playback volume, language, XTTS/SAPI toggles. No config writes.
- **Depends on:** `ValVoiceBackend` (engine state), `SapiVoiceEngine` (fallback), `ConfigManager` (settings), `SoundVolumeView.exe`, JavaFX `MediaPlayer`, JNA
- **Depended on by:** `VoiceGenerator`
- **Pattern:** Service Client, Cache-Aside, Producer-Consumer, JNA Bridge

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

### `SapiVoiceEngine.java` *(Phase 6)*
- **Role:** Windows SAPI fallback — generates `.wav` audio files when XTTS backend is unavailable.
- **Behavior:** Pure utility class (`final`, private constructor, all static methods). Generates speech via PowerShell command: `Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; $s.SetOutputToWaveFile('path'); $s.Speak('text'); $s.Dispose();`. Text sanitized for PowerShell injection (`'` → `''`). Text truncated to 300 chars. MD5-hashed filenames (`sapi_<hash>.wav`). Cache in `%LOCALAPPDATA%\ValVoice\cache\` (shared with XTTS cache). Rate limiter: 250ms minimum between PowerShell spawns (only after cache miss). 10s process timeout with `destroyForcibly()`.
- **Depends on:** PowerShell, filesystem
- **Depended on by:** `InbuiltVoiceSynthesizer` (queue consumer routes here when DEGRADED)
- **Pattern:** Utility, Fallback Strategy

### `AudioRouterUtility.java` *(Phase 5 Step 3)*
- **Role:** Routes Java audio output to VB-Cable using SoundVolumeView.exe.
- **Behavior:** Utility class. Locates `SoundVolumeView.exe` in working dir or JAR dir. Executes two commands: `/SetAppDefault "java.exe" 0 "CABLE Input"` and `/SetAppDefault "javaw.exe" 0 "CABLE Input"`. Each command has 2s timeout. `redirectErrorStream(true)`, no `inheritIO()`. Fails gracefully if executable missing. Called once at startup.
- **Depends on:** `SoundVolumeView.exe`
- **Depended on by:** `Main.java` (startup call)
- **Pattern:** Utility, Fire-and-Forget

### `EnvironmentValidator.java` *(Phase 8)*
- **Role:** Startup environment diagnostics — read-only dependency checker.
- **Behavior:** Final utility class (private constructor, all static methods). Three checks:
  - (1) `checkSoundVolumeView()` — searches working dir and JAR dir for `SoundVolumeView.exe`, mirrors `AudioRouterUtility` path logic. Returns `"OK"` or `"MISSING"`.
  - (2) `checkPowerShell()` — executes `powershell -Command "echo test"` with 2s timeout, drains output stream, checks exit code. Returns `"OK"` or `"NOT AVAILABLE"`.
  - (3) `checkVbCable()` — scans `AudioSystem.getMixerInfo()` for mixer names/descriptions containing `"CABLE"`. Returns `"OK"` or `"NOT DETECTED"`.
  - `runAllChecks()` — calls all three checks, logs formatted summary report to console. Called once at startup from `Main.java`.
  - **[Phase 8.3]** `runChecksWithResults()` — calls the same three check methods but returns `Map<String, Boolean>` with keys `"SoundVolumeView"`, `"PowerShell"`, `"VBCable"`. Used by `SetupWizardController` to display green/red status labels in the wizard UI. Also logs results.
- **Thread safety:** Fully stateless. Never throws. Never blocks indefinitely. Never modifies application state.
- **Depends on:** `javax.sound.sampled.AudioSystem`, PowerShell, filesystem
- **Depended on by:** `Main.java` (startup call), `SetupWizardController` (wizard UI)
- **Pattern:** Utility, Diagnostic

---

## FILE REFERENCE — Configuration (`com.someone.valvoicebackend.config`)

### `ValVoiceConfig.java` *(Phase 7)*
- **Role:** Plain configuration data model (POJO).
- **Fields:**
  - `pttKey` (String, default `"V"`) — Push-to-Talk key for JNA SendInput
  - `xttsEnabled` (boolean, default `true`) — whether XTTS backend engine is active
  - `sapiFallbackEnabled` (boolean, default `true`) — whether Windows SAPI fallback is active
  - `playbackVolume` (double, default `1.0`) — MediaPlayer volume (0.0–1.0)
  - `language` (String, default `"en"`) — TTS language code for XTTS payload
  - **[Phase 8.3]** `firstRunCompleted` (boolean, default `false`) — tracks whether the first-run Setup Wizard has been completed; once `true`, wizard never shows again
- **Behavior:** No methods, no logic, no imports. All fields are `public`. Default values defined inline. Serialized/deserialized by Gson.
- **Depends on:** nothing
- **Depended on by:** `ConfigManager`, `SetupWizardController` (reads `firstRunCompleted`)
- **Pattern:** POJO, DTO

### `ConfigManager.java` *(Phase 7)*
- **Role:** Persistent JSON configuration manager. Singleton access pattern.
- **Behavior:** Loads/saves `ValVoiceConfig` to `%LOCALAPPDATA%\ValVoice\config.json`. Uses Gson for serialization (pretty-printed). `load()` — reads from disk, creates default if missing, catches all exceptions. `save()` — atomic write pattern: writes to `config.json.tmp`, then `Files.move()` with `ATOMIC_MOVE` + `REPLACE_EXISTING`. `reload()` — synchronized re-read from disk (called when Settings window opens). `get()` — returns singleton instance (never null, double-checked locking with default fallback). `getConfigPath()` — resolves to `%LOCALAPPDATA%\ValVoice\config.json` with `user.home` fallback.
- **Depends on:** `ValVoiceConfig`, Gson
- **Depended on by:** `InbuiltVoiceSynthesizer`, `SettingsController`, `SetupWizardController`, `ValVoiceApplication`, `Main.java`
- **Pattern:** Singleton, Utility, Atomic Write

---

## FILE REFERENCE — GUI (`com.someone.valvoicegui`)

### `Main.java`
- **Role:** Application entry point and startup orchestrator.
- **Behavior:** (1) Checks for running Riot Client / Valorant processes — blocks if found. (2) Runs process reaper for orphaned `valvoice-mitm.exe`. (3) Registers shutdown reaper hook. (4) Bootstraps config directory. (5) Loads user config (`loadUserConfig()`). (6) Loads `config.properties`. (7) Acquires single-instance file lock. (8) **[Phase 8]** Runs `EnvironmentValidator.runAllChecks()` — diagnostic report. (9) **[Phase 5]** Runs `AudioRouterUtility.routeAudioToVirtualCable()` — routes JVM audio to VB-Cable. (10) **[Phase 7]** Runs `ConfigManager.load()` — loads persistent JSON config. (11) Calls `Application.launch(ValVoiceApplication.class)`.
- **Depends on:** `ValVoiceApplication`, `AudioRouterUtility`, `EnvironmentValidator`, `ConfigManager`, `Chat`, `Source`
- **Pattern:** Entry Point, Startup Orchestrator

### `ValVoiceApplication.java`
- **Role:** JavaFX bootstrap with first-run wizard routing.
- **Behavior:** Overrides `Application.start(Stage)`:
  - **[Phase 8.3] Wizard routing:** Checks `ConfigManager.get().firstRunCompleted`. If `false`, calls `launchSetupWizard(stage)` which loads `setup-wizard.fxml` into a transparent `StageStyle.TRANSPARENT` window and returns early. If `true`, calls `launchMainApp(stage)` for normal startup.
  - **`launchSetupWizard(Stage)`:** Loads `setup-wizard.fxml` via `FXMLLoader`, applies transparent scene fill, shows wizard window. Sets `Platform.setImplicitExit(false)`. The `SetupWizardController` handles launching the main app after wizard completion.
  - **`launchMainApp(Stage)`:** Loads `mainApplication.fxml`, applies Valorant theme CSS (`/css/valorant-theme.css`), configures `StageStyle.TRANSPARENT`, creates system tray icon via `createTrayIcon()`, sets `Platform.setImplicitExit(false)`, registers shutdown hook calling `controller.shutdownServices()`.
  - **`createTrayIcon(Stage)`:** Creates AWT `SystemTray` icon with popup menu (Show/Close). Loads `appIcon.png` from classpath. Hide-to-tray on close. Public method — also called by `SetupWizardController.launchMainApplication()`.
  - **`stop()`:** Calls `controller.shutdownServices()` for JavaFX lifecycle cleanup.
- **Depends on:** `ValVoiceController`, `ConfigManager`, `SetupWizardController` (via FXML), `module-info.java`
- **Pattern:** Application Bootstrap, Router

### `ValVoiceController.java`
- **Role:** Primary UI controller (largest file).
- **Behavior:** Wires all `@FXML` controls (buttons, sliders, dropdowns, checkboxes) to backend. Handles Start/Stop via `ValVoiceBackend` facade. Receives backend events and updates UI console via `Platform.runLater()`. Manages audio routing setup. Persists user config. Color-codes log output by `MessageType`.
- **Depends on:** `ValVoiceBackend` (GUI facade), `Chat`, `VoiceGenerator`, `MessageType`
- **Pattern:** MVC Controller, Event Handler

### `ValVoiceBackend.java` *(GUI package)*
- **Role:** Facade bridging UI controller to core backend + TTS engine lifecycle manager.
- **Behavior:** Exposes start/stop commands, status queries, error propagation. Delegates to `com.someone.valvoicebackend` classes.
  - **Engine Lifecycle (Phases 1–3):** Manages `EngineState` enum: `STOPPED → STARTING → READY → DEGRADED → STOPPING`. Launches `engine/valorantNarrator-agentVoices.exe` via hidden `ProcessBuilder` (no `inheritIO`, `redirectErrorStream(true)`). Polls TCP socket on `127.0.0.1:5005` every 500ms with 15s timeout. Log gobbler thread drains stdout. `stopEngine()` — escalation kill: `destroy()` → 500ms wait → `destroyForcibly()`. Idempotent. Shutdown hook calls `stopEngine()` and kills stale EXE via `taskkill /F /IM`. `markDegraded()` — called by HTTP layer on ConnectException. `setEngineReady()` — called by recovery probe on successful port 5005 reconnect.
  - **Guards:** `AtomicBoolean engineRunning` prevents double-start. `volatile EngineState` for cross-thread visibility.
- **Depends on:** all `valvoicebackend` classes, `engine/valorantNarrator-agentVoices.exe`
- **Depended on by:** `ValVoiceController`, `InbuiltVoiceSynthesizer` (engine state checks)
- **Pattern:** Facade, State Machine, Process Manager

### `MessageType.java`
- **Role:** Enum for console log categories.
- **Values:** `INFO`, `ERROR`, `SUCCESS`, `DEBUG` — each maps to a CSS color in the UI console.
- **Depends on:** nothing
- **Depended on by:** `ValVoiceController`
- **Pattern:** Enum

### `SettingsController.java` *(Phase 7 Step 2)*
- **Role:** JavaFX controller for the runtime Settings window.
- **Behavior:** Binds to `settings.fxml`. On open: calls `ConfigManager.reload()` to refresh from disk → populates UI from `ConfigManager.get()` with null protection (defaults: PTT=`"V"`, language=`"en"`, volume=`0.75`). Volume slider: 0.0–1.0, step 0.05, live `%` label. PTT field: auto-uppercase, single character only. `handleSave()`: validates PTT key (single letter/digit), writes all fields to config, calls `ConfigManager.save()` (atomic), shows success `Alert`, closes window. Changes apply immediately — backend reads `ConfigManager.get()` dynamically.
- **FXML fields:** `pttKeyField` (TextField), `xttsEnabledCheckBox` (CheckBox), `sapiFallbackCheckBox` (CheckBox), `volumeSlider` (Slider), `languageChoice` (ChoiceBox — 16 languages), `saveButton` (Button), `volumeValueLabel` (Label).
- **Depends on:** `ConfigManager`, `ValVoiceConfig`
- **Depended on by:** `settings.fxml` (via `fx:controller`)
- **Pattern:** MVC Controller

### `SetupWizardController.java` *(Phase 8 Step 3)*
- **Role:** JavaFX controller for the first-run Setup Wizard.
- **Behavior:** Controls a 4-page onboarding wizard displayed on first launch. Pages stacked inside a `StackPane`; only one visible at a time via `setVisible()/setManaged()`.
  - **Page navigation:** `showPage(int pageIndex)` — hides all pages, shows requested page, updates button visibility (Back hidden on page 0, Next hidden on last page, Finish only on last page).
  - **`handleNext()`:** Increments `currentPage`, calls `showPage()`. When entering page 1 (Environment), triggers `runEnvironmentChecks()`.
  - **`handleBack()`:** Decrements `currentPage`, calls `showPage()`.
  - **`runEnvironmentChecks()`:** Calls `EnvironmentValidator.runChecksWithResults()` which returns `Map<String, Boolean>`. Updates three status labels: `statusVbCable`, `statusSoundVolumeView`, `statusPowerShell` via `updateStatusLabel()`. Shows green `"✅ OK"` (style `#a6e3a1`) if `true`, red `"❌ Missing"` (style `#f38ba8`) if `false`. Shows `envWarningLabel` with warning if any check fails, or green success message if all pass. Catches all exceptions — never crashes.
  - **`handleFinish()`:** Sets `ConfigManager.get().firstRunCompleted = true`, calls `ConfigManager.save()`, then calls `launchMainApplication(wizardStage)`.
  - **`launchMainApplication(Stage wizardStage)`:** Creates new `Stage`, loads `mainApplication.fxml`, applies Valorant theme CSS, configures `StageStyle.TRANSPARENT`, creates `ValVoiceApplication` instance for `createTrayIcon()`, registers shutdown hook for `controller.shutdownServices()`, closes wizard stage, shows main stage.
- **FXML fields:** `wizardPages` (StackPane), `page1Welcome`/`page2Environment`/`page3Audio`/`page4Finish` (VBox), `backButton`/`nextButton`/`finishButton` (Button), `statusVbCable`/`statusSoundVolumeView`/`statusPowerShell` (Label), `envWarningLabel` (Label).
- **Thread safety:** Runs entirely on JavaFX Application Thread. No background threads. No polling. No engine logic.
- **Depends on:** `EnvironmentValidator` (dependency checks), `ConfigManager` (config read/write), `ValVoiceApplication` (tray icon), `ValVoiceController` (main UI)
- **Depended on by:** `setup-wizard.fxml` (via `fx:controller`), `ValVoiceApplication` (loads FXML on first run)
- **Pattern:** MVC Controller, Wizard Pattern

### `setup-wizard.fxml` *(Phase 8 Step 3)*
- **Role:** FXML layout for the first-run Setup Wizard.
- **Location:** `src/main/resources/com/someone/valvoicegui/setup-wizard.fxml`
- **Controller:** `com.someone.valvoicegui.SetupWizardController`
- **Layout:** `VBox` root (520×480) with header bar, `StackPane` page container, and bottom navigation bar.
  - **Header:** App icon + "ValVoice Setup" title + subtitle. Background `#181825`.
  - **Page 1 — Welcome (`page1Welcome`):** Explains what ValVoice does. Lists 3 wizard steps (verify dependencies, check audio, guide Valorant setup). "Click Next to begin."
  - **Page 2 — Environment Check (`page2Environment`):** Three dependency rows (VB-Cable, SoundVolumeView, PowerShell), each with name, description, and `fx:id` status label. Initial text `"⏳ Checking..."`. `envWarningLabel` for overall result.
  - **Page 3 — Audio Setup (`page3Audio`):** 4 numbered steps for Valorant config: Open Settings → Audio → Voice Chat → Set Input Device to `"CABLE Output (VB-Audio Virtual Cable)"` → Match PTT key.
  - **Page 4 — Finish (`page4Finish`):** "🎉 You're All Set!" with 3 reminder bullet points. "Click Finish to launch ValVoice."
  - **Navigation bar:** Back (←), Next (→), Finish (✓) buttons. Back hidden on first page, Finish only on last page.
- **Theme:** Catppuccin Mocha dark palette (`#1e1e2e` background, `#181825` header, `#89b4fa` accent, `#a6e3a1` success, `#f38ba8` error).

---

## FILE REFERENCE — Root Files

### `pom.xml`
- **Role:** Maven build config.
- **Key details:** Java 17 target. Dependencies: JavaFX controls/media/fxml, Gson, JNA, logging. Shade Plugin produces fat JAR `ValVoice.jar`.

### `module-info.java`
- **Role:** JPMS module descriptor.
- **Exports:** `com.someone.valvoicebackend`, `com.someone.valvoicegui`, `com.someone.valvoicebackend.config`
- **Requires:** `javafx.base`, `javafx.controls`, `javafx.fxml`, `javafx.graphics`, `javafx.media`, `com.jfoenix`, `org.slf4j`, `ch.qos.logback.classic`, `ch.qos.logback.core`, `com.google.gson`, `java.desktop`, `java.net.http`, `com.sun.jna`
- **Opens:** `com.someone.valvoicegui` → `javafx.fxml, javafx.graphics`; `com.someone.valvoicebackend` → `javafx.fxml, javafx.graphics, com.google.gson`; `com.someone.valvoicebackend.config` → `com.google.gson`

### `SoundVolumeView.exe`
- **Role:** Windows CLI for per-process audio device routing.
- **Called by:** `AudioRouterUtility.java` via `ProcessBuilder`
- **Must be present** in project root at runtime.

### `settings.fxml` *(Phase 7 Step 2)*
- **Role:** FXML layout for the Settings window.
- **Location:** `src/main/resources/com/someone/valvoicegui/settings.fxml`
- **Controller:** `com.someone.valvoicegui.SettingsController`
- **Contains:** PTT key TextField, XTTS/SAPI CheckBoxes, volume Slider with `%` label, language ChoiceBox, Save button. Dark theme inline styles matching Catppuccin palette.

### `config.json` *(Phase 7 — runtime file)*
- **Role:** Persistent user configuration. Created automatically on first launch.
- **Location:** `%LOCALAPPDATA%\ValVoice\config.json`
- **Managed by:** `ConfigManager.java`
- **Fields:** `pttKey`, `xttsEnabled`, `sapiFallbackEnabled`, `playbackVolume`, `language`, `firstRunCompleted`
- **[Phase 8.3]** `firstRunCompleted` defaults to `false`. Set to `true` by the Setup Wizard on completion. Controls whether the wizard or main UI is shown at startup.

### `run-valvoice.bat` *(Phase 8 Step 2)*
- **Role:** Silent Windows launcher for production use.
- **Behavior:** Checks if `javaw` is available on PATH. If missing, prints error and pauses. Otherwise, launches `javaw -Xms256m -Xmx1024m -jar valvoice-1.0.0.jar` via `start ""` for no console window. Users double-click this file to run ValVoice.
- **Location:** Project root (bundled in release ZIP).
- **Depends on:** `valvoice-1.0.0.jar`, Java 17+ on PATH

### `README.txt` *(Phase 8 Step 2)*
- **Role:** End-user documentation included in the release bundle.
- **Contents:** Installation steps (Java, VB-Cable, extract ZIP). Running instructions (start before Valorant). Valorant configuration (set mic to CABLE Output). Feature list (AI voices, SAPI fallback, recovery, settings, diagnostics). Troubleshooting guide. System requirements. Log/config/cache file locations.
- **Location:** Project root (bundled in release ZIP).

### `src/assembly/distribution.xml` *(Phase 8 Step 2)*
- **Role:** Maven Assembly Plugin descriptor for release packaging.
- **Behavior:** Generates `valvoice-1.0.0.zip` and `ValVoice/` directory during `mvn package`. Bundles: shaded JAR, `SoundVolumeView.exe`, `valvoice-mitm.exe` (at `/mitm/` to match `ValVoiceBackend` path resolution), MITM certs (at `/mitm/certs/`), `engine/` directory (recursive, excludes dev artifacts like `pyinstxtractor.py`, `_extracted/`, `Doc/`, `Tools/`, `__pycache__/`), `run-valvoice.bat`, `README.txt`, `LICENSE`. Base directory: `ValVoice/`. Formats: `zip` + `dir`.
- **Depends on:** Maven Assembly Plugin (configured in `pom.xml`)
- **Output:** `target/valvoice-1.0.0.zip`

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

### Startup Sequence
```
Step 0a: Main.java checks for running Riot Client / Valorant → blocks if found.
Step 0b: Main.java runs process reaper (kills orphaned mitm/engine processes).
Step 0c: Main.java acquires single-instance file lock.
Step 0d: EnvironmentValidator.runAllChecks() → logs SoundVolumeView, PowerShell, VB-Cable status.
Step 0e: AudioRouterUtility.routeAudioToVirtualCable() → routes java.exe + javaw.exe to CABLE Input.
Step 0f: ConfigManager.load() → reads/creates %LOCALAPPDATA%\ValVoice\config.json.
Step 0g: Application.launch() → ValVoiceApplication.start().
Step 0h: [Phase 8.3] If firstRunCompleted == false → launch Setup Wizard (4 pages).
         Wizard validates environment → shows Valorant audio instructions → marks complete.
         Then launches main application window.
Step 0i: [Normal] If firstRunCompleted == true → load mainApplication.fxml directly.
Step 0j: ValVoiceController starts ValVoiceBackend → launches MITM proxy + XTTS engine.
```

### Runtime TTS Pipeline
```
Step 1: Riot Client sends encrypted XMPP to Riot servers.
Step 2: valvoice-mitm.exe intercepts via local TLS proxy → outputs JSON to stdout.
Step 3: ValVoiceBackend.java reads stdout stream → extracts raw XML string.
Step 4: XmppStreamParser.java parses XML → produces ParsedMessage DTO.
Step 5: ChatDataHandler.java validates message → checks channel flags + game state → produces Message.
Step 6: VoiceGenerator.java dequeues Message → calls InbuiltVoiceSynthesizer.enqueueTts().
Step 7: InbuiltVoiceSynthesizer queue consumer:
        [if READY]  → HTTP POST to XTTS engine → receives MP3 → caches with MD5 hash.
        [if DEGRADED] → SapiVoiceEngine generates .wav via PowerShell SAPI.
        [if DEGRADED + probe cooldown] → probes port 5005, restores READY on success.
Step 8: playAudio() → pressPtt() (JNA SendInput KEY_DOWN, 50ms delay)
        → MediaPlayer.play() (Platform.runLater, CountDownLatch)
        → [wait for clip end] → sleep 100ms → releasePtt() (KEY_UP, in finally block).
Step 9: Audio flows: JavaFX MediaPlayer → CABLE Input → CABLE Output → Valorant Mic Input.
Step 10: Teammates hear TTS in Valorant voice chat.
```

---

## DEPENDENCY GRAPH (Simplified)

```
Main.java
  ├─► EnvironmentValidator.runAllChecks()     ← Phase 8 diagnostics
  ├─► AudioRouterUtility.routeAudioToVirtualCable()  ← Phase 5 audio routing
  ├─► ConfigManager.load()                    ← Phase 7 config
  └─► ValVoiceApplication.java
        │
        ├─► [firstRunCompleted == false] → SetupWizardController.java  ← Phase 8.3
        │     ├─► EnvironmentValidator.runChecksWithResults()
        │     ├─► ConfigManager.get() / .save()
        │     └─► launchMainApplication() → loads mainApplication.fxml
        │
        └─► [firstRunCompleted == true] → ValVoiceController.java
              ├─► SettingsController.java      ← Phase 7 settings window
              │     └─► ConfigManager.java → ValVoiceConfig.java
              ├─► ValVoiceBackend.java (GUI facade + engine lifecycle)
              │     ├─► engine/valorantNarrator-agentVoices.exe (XTTS backend)
              │     ├─► valvoice-mitm.exe (child process)
              │     ├─► XmppStreamParser.java → ParsedMessage.java
              │     ├─► ChatDataHandler.java
              │     │     ├─► Chat.java (channel flags)
              │     │     ├─► GameStateManager.java
              │     │     ├─► ChatUtilityHandler.java
              │     │     └─► VoiceGenerator.java
              │     │           └─► InbuiltVoiceSynthesizer.java
              │     │                 ├─► HTTP POST → 127.0.0.1:5005/speak
              │     │                 ├─► MD5 cache (%LOCALAPPDATA%\ValVoice\cache\)
              │     │                 ├─► SapiVoiceEngine.java (SAPI fallback)
              │     │                 ├─► JavaFX MediaPlayer (playback)
              │     │                 ├─► JNA SendInput (PTT injection)
              │     │                 ├─► ConfigManager.java (runtime config reads)
              │     │                 └─► SoundVolumeView.exe (via AudioRouterUtility)
              │     ├─► APIHandler.java
              │     │     ├─► ConnectionHandler.java
              │     │     ├─► LockFileHandler.java → RiotClientDetails.java
              │     │     ├─► RiotUtilityHandler.java
              │     │     ├─► EntitlementsTokenResponse.java
              │     │     └─► PlayerAccount.java
              │     └─► Roster.java
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
| XTTS Engine              | `engine/valorantNarrator-agentVoices.exe`  |
| Valorant + Riot Client   | Installed and accessible                  |
| JNA                      | Included via Maven dependency             |
| Gson                     | Included via Maven dependency             |
| Config storage           | `%LOCALAPPDATA%\ValVoice\config.json`      |
| Cache storage            | `%LOCALAPPDATA%\ValVoice\cache\`           |

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

| Component            | Status          | Phase |
|----------------------|-----------------|-------|
| MITM Proxy           | 🟢 Stable       | —     |
| TTS Engine Lifecycle | 🟢 Stable       | 1–3   |
| HTTP + Cache + Queue | 🟢 Stable       | 4     |
| MediaPlayer Playback | 🟢 Stable       | 5.1   |
| PTT Injection (JNA)  | 🟢 Stable       | 5.2   |
| VB-Cable Routing     | 🟢 Stable       | 5.3   |
| SAPI Fallback        | 🟢 Stable       | 6     |
| Auto Recovery        | 🟢 Stable       | 6.3   |
| Configuration System | 🟢 Stable       | 7     |
| Settings UI          | 🟢 Stable       | 7.2   |
| Runtime Config Sync  | 🟢 Stable       | 7.3   |
| Env Validation       | 🟢 Stable       | 8.1   |
| Release Packaging    | 🟢 Stable       | 8.2   |
| Setup Wizard         | 🟢 Stable       | 8.3   |
| Crash Logging        | 🟢 Stable       | —     |
| Java Backend         | 🟢 Stable       | —     |
| JavaFX UI            | 🟢 Stable       | —     |
| Security Audit       | 🟢 Verified     | —     |
| Overall              | 🏆 Production    | 1–8   |

---

## PHASE IMPLEMENTATION HISTORY

> Summary of all engineering phases completed. Each phase was independently verified via structured PASS/FAIL audit before proceeding.

| Phase | Name | Description | Key Files |
|-------|------|-------------|-----------|
| 1 | Engine State Machine | `EngineState` enum, `volatile` state field, `AtomicBoolean` guard, `markDegraded()`, `isEngineReady()` | `ValVoiceBackend.java` |
| 2 | Engine Process Launch | `startEngine()` with taskkill, hidden ProcessBuilder, 15s socket polling, log gobbler. `stopEngine()` with escalation kill | `ValVoiceBackend.java` |
| 3 | Shutdown Lifecycle | Shutdown hook, reaper integration for `valorantNarrator-agentVoices.exe`, hardened `stopEngine()` idempotency | `ValVoiceBackend.java` |
| 4.1 | HTTP Layer | `requestTts()` — Java 11 HttpClient, 5s connect / 15s request timeout, ConnectException → DEGRADED | `InbuiltVoiceSynthesizer.java` |
| 4.2 | Cache Layer | MD5 hash (`agent\|text\|language`), 100MB eviction, atomic `.tmp → .mp3` rename, `%LOCALAPPDATA%\ValVoice\cache\` | `InbuiltVoiceSynthesizer.java` |
| 4.3 | Queue Layer | `LinkedBlockingQueue(10)`, daemon consumer, drop newest, graceful degrade routing | `InbuiltVoiceSynthesizer.java` |
| 5.1 | MediaPlayer Playback | `Platform.runLater()` + `CountDownLatch`, fresh MediaPlayer per clip, 60s timeout, always dispose | `InbuiltVoiceSynthesizer.java` |
| 5.2 | PTT Injection | JNA `SendInput` via `user32.dll`, 50ms pre-delay, 100ms tail delay, `AtomicBoolean` stuck-key guard, shutdown failsafe | `InbuiltVoiceSynthesizer.java` |
| 5.3 | Audio Routing | `SoundVolumeView.exe /SetAppDefault` for `java.exe` + `javaw.exe`, fire-and-forget startup call | `AudioRouterUtility.java`, `Main.java` |
| 6.1 | SAPI Fallback | PowerShell WAV generation, MD5 cache, text sanitization, 10s timeout | `SapiVoiceEngine.java`, `InbuiltVoiceSynthesizer.java` |
| 6.2 | Fallback Hardening | Rate limiter (250ms), text truncation (300 chars), null-safety in queue, improved logging | `SapiVoiceEngine.java`, `InbuiltVoiceSynthesizer.java` |
| 6.3 | Auto Recovery | Port 5005 probe every 10s during DEGRADED, `setEngineReady()` on success | `InbuiltVoiceSynthesizer.java`, `ValVoiceBackend.java` |
| 7.1 | Config System | `ValVoiceConfig` POJO, `ConfigManager` with Gson, `%LOCALAPPDATA%\ValVoice\config.json`, config reads in synthesizer | `config/ConfigManager.java`, `config/ValVoiceConfig.java`, `Main.java` |
| 7.2 | Settings UI | `SettingsController.java`, `settings.fxml`, `handleOpenSettings()` in controller, Engine Config card in main FXML | `SettingsController.java`, `settings.fxml`, `ValVoiceController.java`, `mainApplication.fxml` |
| 7.3 | Runtime Config Sync | `reload()` method, atomic write (`.tmp → ATOMIC_MOVE`), null protection, save confirmation dialog, window sizing | `ConfigManager.java`, `SettingsController.java`, `ValVoiceController.java` |
| 8.1 | Environment Validation | Read-only startup checks: SoundVolumeView, PowerShell, VB-Cable. Diagnostic report in logs. Added `runChecksWithResults()` returning `Map<String, Boolean>` for wizard UI | `EnvironmentValidator.java`, `Main.java` |
| 8.2 | Production Packaging | Maven Assembly descriptor for release ZIP. Bundles JAR + all executables + engine/ dir + certs + launcher + docs. `run-valvoice.bat` silent launcher. `README.txt` end-user guide | `distribution.xml`, `run-valvoice.bat`, `README.txt`, `pom.xml` |
| 8.3 | First-Run Setup Wizard | 4-page onboarding wizard (Welcome → Environment Check → Audio Setup → Finish). Tracks `firstRunCompleted` in config. Shows dependency status with green/red labels. Launches main app after completion. Never appears again | `SetupWizardController.java`, `setup-wizard.fxml`, `ValVoiceApplication.java`, `ValVoiceConfig.java` |
| — | Global Crash Logging | Architecture-agnostic crash logging. `%LOCALAPPDATA%\ValVoice\logs\valvoice.log` with daily rotation (7 days) via logback `CRASH_LOG` appender. Dedicated `crash.log` file at same location captures full stack traces with timestamps via `Thread.setDefaultUncaughtExceptionHandler`. Logs directory created at startup. Crash entries appended with `StandardOpenOption.APPEND`. No engine/queue/playback changes | `Main.java`, `logback.xml` |
| — | Cache Temp Cleanup | Startup cleanup of stale `.tmp` files in `%LOCALAPPDATA%\ValVoice\cache\`. `cleanupTempCacheFiles()` runs once after config load, before JavaFX launch. Only deletes `.tmp` files — never touches `.mp3` or `.wav`. Fail-safe: never throws exceptions. Prevents leftover partial files from interrupted audio generation | `Main.java` |
| — | Restart Guard | Automatic TTS engine crash recovery. Daemon watcher thread detects unexpected process exit (non-zero exit code). `handleProcessCrash()` attempts `stopEngine()` + `startEngine()` once (`MAX_RESTART_ATTEMPTS=1`). If restart fails → permanent `DEGRADED` state. Intentional shutdowns (`STOPPING`/`STOPPED` state) are ignored by the watcher. `restartAttempts` resets to 0 inside `setEngineReady()` after successful recovery. No queue/playback/PTT changes | `ValVoiceBackend.java` |
| — | Stale Cache Cleanup | `cleanupStaleTempFiles()` in `InbuiltVoiceSynthesizer.java` removes orphaned `.tmp` files older than 3 minutes from `%LOCALAPPDATA%\ValVoice\cache\`. Runs asynchronously at startup via `CompletableFuture.runAsync()` — never blocks constructor or UI. Uses `getCacheDirectory()` for path resolution. Only deletes `.tmp` files; never touches `.mp3`/`.wav`. 3-minute age threshold prevents race conditions with active atomic writes. Best-effort: all exceptions caught | `InbuiltVoiceSynthesizer.java` |
| — | Voice Pipeline Self-Test | "Test Voice" button in Settings UI. `handleTestVoice()` calls `VoiceGenerator.getInstance().speak()` to route through the full pipeline: Queue → PTT press → Synthesizer → Playback → VB-Cable → PTT release. Checks `VoiceGenerator.isInitialized()` before calling. Green Catppuccin-styled button alongside Save. No VoiceGenerator/queue/PTT/playback logic modified — purely reuses existing pipeline | `SettingsController.java`, `settings.fxml` |
| — | Security Hardening (CWE-78) | Eliminated all `Runtime.getRuntime().exec(String)` usage (command injection vulnerability). Replaced with `ProcessBuilder` and separated argument lists. Centralized SoundVolumeView PID routing into `AudioRouterUtility.routeProcessToCable(path, pid)`. Both `InbuiltVoiceSynthesizer.routeAudioToVbCable()` and `ValVoiceController.routeMainProcessAudioToVbCable()` now delegate to this method. Handles paths with spaces. Output consumed to prevent blocking. 2-second timeout with `destroyForcibly()`. Zero `Runtime.exec()` calls remain in the codebase | `AudioRouterUtility.java`, `InbuiltVoiceSynthesizer.java`, `ValVoiceController.java` |
| — | Roster Pattern Optimization | Hoisted 3 `Pattern.compile()` calls out of the `parseRosterItemsFallback()` loop into `private static final` class-level constants: `ITEM_TAG_PATTERN`, `JID_PATTERN`, `NAME_ATTR_PATTERN`. Eliminates repeated regex compilation during 100–200 friend roster bursts, reducing CPU spikes and GC pressure. Zero `Pattern.compile()` calls remain inside any method body. Parsing behavior unchanged | `Roster.java` |
