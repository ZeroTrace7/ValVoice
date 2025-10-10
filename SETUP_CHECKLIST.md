# ValVoice Setup Checklist

## Pre-Launch Verification

### ✅ 1. VB-Audio Virtual Cable
- [ ] Downloaded from https://vb-audio.com/Cable/
- [ ] Installed successfully
- [ ] **Rebooted Windows** (MANDATORY)
- [ ] Verify in Windows Sound Settings:
  - Playback device: `CABLE Input (VB-Audio Virtual Cable)` exists
  - Recording device: `CABLE Output (VB-Audio Virtual Cable)` exists

### ✅ 2. Audio Routing (AUTOMATIC - No Action Required!)
**Good news:** ValVoice now has built-in audio routing! You don't need to download any external tools.

- ✨ Audio routing happens automatically when you start the app
- 🔧 The app will detect VB-CABLE and configure everything for you
- ⚡ Just install VB-CABLE (step 1 above) and you're done!

**Old Method (No Longer Needed):**
~~SoundVolumeView.exe~~ - This external tool is **no longer required**. The app now handles audio routing internally using PowerShell and Windows APIs.

### ✅ 3. XMPP Bridge Executable
// ...existing code...

### ✅ 6. Verify Status Bar (in ValVoice UI)
- [ ] **XMPP**: Should show "Ready" (green)
- [ ] **Bridge Mode**: Should show "external-exe"
- [ ] **VB-Cable**: Should show "Detected"
- [ ] **Audio Routing**: Should show "Active (built-in)" or "Active (SoundVolumeView)" (green)
- [ ] **Self**: Should show your player ID (after joining a game)

// ...existing code...

#### No Sound in Valorant
- **Cause**: Audio not routed to VB-CABLE
- **Fix**: 
  - The app should route audio automatically on startup
  - Check status bar: "Audio Routing" should be green
  - If still not working, manually route in Windows: 
    - Sound Settings → App volume → PowerShell → Output: CABLE Input
    - OR Sound Settings → App volume → Java → Output: CABLE Input

// ...existing code...

