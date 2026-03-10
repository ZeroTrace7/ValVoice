╔═══════════════════════════════════════════════════════════════╗
║                    ValVoice v1.0.0                            ║
║         AI Voice Engine for Valorant In-Game Chat             ║
╚═══════════════════════════════════════════════════════════════╝

ValVoice reads your Valorant in-game chat messages aloud using
AI-powered text-to-speech, then plays them through Valorant's
voice chat so your teammates can hear callouts instantly.


═══════════════════════════════════════════════════════════════
  INSTALLATION
═══════════════════════════════════════════════════════════════

Step 1 — Install Java 17+
  Download from: https://adoptium.net/
  Verify: Open a terminal and run "java -version"

Step 2 — Install VB-Audio Virtual Cable
  Download from: https://vb-audio.com/Cable/
  This creates a virtual audio device that routes
  ValVoice audio into Valorant's microphone input.

Step 3 — Extract ValVoice
  Extract the ValVoice ZIP to any folder.
  Keep all files together — do not move individual files.

Step 4 — Verify Contents
  Your ValVoice folder should contain:
    valvoice-1.0.0.jar        — Main application
    run-valvoice.bat           — Launcher (double-click this)
    SoundVolumeView.exe        — Audio routing utility
    valvoice-mitm.exe          — Network proxy
    engine/                    — AI voice engine
    mitm/certs/                — TLS certificates
    README.txt                 — This file
    LICENSE                    — License information


═══════════════════════════════════════════════════════════════
  RUNNING VALVOICE
═══════════════════════════════════════════════════════════════

  1. Close Valorant and Riot Client if they are running.
     ValVoice MUST start BEFORE Valorant.

  2. Double-click: run-valvoice.bat

  3. Wait for the ValVoice window to appear.

  4. Click "Start" in ValVoice.

  5. Now launch Valorant through the Riot Client.

  6. In-game chat messages will be read aloud automatically.

  IMPORTANT: ValVoice must always start BEFORE Valorant.


═══════════════════════════════════════════════════════════════
  VALORANT CONFIGURATION
═══════════════════════════════════════════════════════════════

  1. Open Valorant Settings → Audio → Voice Chat

  2. Set "Input Device" (Microphone) to:
       CABLE Output (VB-Audio Virtual Cable)

  3. Set Push-to-Talk key to match your ValVoice config.
     Default PTT key: V

  4. Your teammates will now hear TTS callouts through
     voice chat when chat messages arrive.


═══════════════════════════════════════════════════════════════
  FEATURES
═══════════════════════════════════════════════════════════════

  • AI Agent Voices (XTTS)
    High-quality AI-generated speech that sounds like
    Valorant agent voices. Powered by a local XTTS engine.

  • Windows SAPI Fallback
    If the AI engine is unavailable, ValVoice automatically
    falls back to Windows built-in speech synthesis.

  • Automatic Recovery
    If the AI engine crashes, ValVoice switches to SAPI
    fallback instantly and periodically checks if the
    engine has recovered.

  • Settings UI
    Configure PTT key, volume, language, and engine
    preferences through the in-app Settings window.

  • Environment Diagnostics
    On startup, ValVoice checks for required dependencies
    (VB-Cable, SoundVolumeView, PowerShell) and reports
    any missing components in the logs.

  • Channel Filtering
    Choose which chat channels to read: Team, Party,
    All Chat, or Self messages.

  • Smart Mute
    Automatically mutes TTS during certain game states.

  • Audio Caching
    Previously generated speech is cached locally for
    instant playback. Cache limit: 100MB.


═══════════════════════════════════════════════════════════════
  TROUBLESHOOTING
═══════════════════════════════════════════════════════════════

  Problem: "Java not found" error
  Solution: Install Java 17+ from https://adoptium.net/

  Problem: No sound in Valorant voice chat
  Solution: Set Valorant microphone to "CABLE Output"

  Problem: ValVoice cannot start
  Solution: Close Valorant and Riot Client first,
            then start ValVoice before launching them.

  Problem: AI voices not working
  Solution: Check that the engine/ folder is present
            and contains valorantNarrator-agentVoices.exe

  Problem: SoundVolumeView warning in logs
  Solution: Ensure SoundVolumeView.exe is in the same
            folder as ValVoice.jar

  Logs are saved to:
    %APPDATA%\ValVoice\debug.log

  Configuration is saved to:
    %LOCALAPPDATA%\ValVoice\config.json

  Audio cache is stored in:
    %LOCALAPPDATA%\ValVoice\cache\


═══════════════════════════════════════════════════════════════
  REQUIREMENTS
═══════════════════════════════════════════════════════════════

  • Windows 10 or later (64-bit)
  • Java 17 or later
  • VB-Audio Virtual Cable
  • Valorant + Riot Client installed


═══════════════════════════════════════════════════════════════
  LICENSE
═══════════════════════════════════════════════════════════════

  ValVoice is released under the MIT License.
  See LICENSE file for details.


