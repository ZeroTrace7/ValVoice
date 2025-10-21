# ValVoice

Turn Valorant chat into a clear voice you and your teammates can hear.

— Windows app with a simple interface. No complex setup.

## What it does
- Reads Valorant chat messages out loud in real time
- Works with Party, Team, All chat, and Whispers
- Uses Windows built‑in voices (no extra TTS install needed)
- Sends only the spoken voice to your mic (VB‑CABLE), so teammates hear it in game
- Keeps your normal game/system audio unchanged

## Highlights
- Clean, minimal UI with status badges
- Pick your favorite Windows voice and speed
- Choose which chats to narrate (SELF / PARTY / TEAM / ALL)
- Minimize to system tray and keep running in the background

## What you need
- Windows 10/11
- Java 17 (or run from an IDE like IntelliJ)
- VB‑CABLE (VB‑Audio Virtual Cable) installed
- Valorant (Riot Client) running

## Quick start
1) Install VB‑CABLE and restart if asked
2) In Valorant → Settings → Audio → Voice Chat
   - Set “Voice Chat Input Device” to “CABLE Input (VB‑Audio Virtual Cable)”
3) Open ValVoice
4) Pick a voice and select which chat sources to read
5) Send a chat message in Valorant — you’ll hear it spoken, and teammates will hear it through your mic

That’s it.

## Tips
- Your game and system audio are not changed — only the voice output is routed to VB‑CABLE
- If you don’t hear anything, make sure Valorant is open and Voice Chat is enabled
- Voices are managed by Windows: Settings → Time & Language → Speech → Manage voices

## Privacy & safety
- Uses Riot’s local client to access your own chat session while the game is running
- Connects only to Riot services; no analytics or third‑party data sharing

## Credits
- VB‑Audio (VB‑CABLE)
- JavaFX, SLF4J/Logback, Gson, JFoenix

© This is a community project and is not affiliated with or endorsed by Riot Games.
