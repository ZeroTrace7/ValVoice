- [ ] Test: Run TEST_TTS_SPEAKERS.bat → You hear it
- [ ] Test: Type in Valorant chat → TTS speaks → Teammates hear it

---

## 🎮 Final Result

After completing this setup:

✅ **You hear:**
   - TTS voices on your speakers
   - Game audio on your speakers
   - Everything sounds normal!

✅ **Your teammates hear:**
   - TTS voices through Valorant voice chat
   - Your actual microphone (if you speak)

✅ **Game audio:**
   - Stays on your normal output device
   - No changes, no routing, works perfectly!

---

## 📞 Still Having Issues?

1. Run `SETUP_AUDIO_MONITORING.bat` for guided setup
2. Check ValVoice logs for audio routing status
3. Verify with `TEST_TTS_SPEAKERS.bat` and `TEST_TTS_VBCABLE.bat`
4. Restart Valorant after changing audio settings

**The most common mistake:** Forgetting to enable "Listen to this device" on CABLE Output!
# 🔊 ValVoice Complete Audio Setup Guide

## 🎯 Goal: Everyone Hears Everything!

**YOU should hear:**
- ✅ TTS voices (on your speakers/headphones)
- ✅ Game sounds (on your speakers/headphones)

**TEAMMATES should hear:**
- ✅ TTS voices (through Valorant voice chat)

---

## 📋 Prerequisites

### 1. Install VB-Audio Virtual Cable
- Download: https://vb-audio.com/Cable/
- Install and restart your computer
- You'll see new devices: "CABLE Input" and "CABLE Output"

---

## ⚙️ Complete Setup (One-Time)

### Step 1: Configure VB-CABLE Monitoring (CRITICAL!)

**This makes YOU hear the TTS on your speakers!**

1. **Open Windows Sound Settings**
   - Right-click speaker icon in taskbar → Open Sound settings
   - OR run: `SETUP_AUDIO_MONITORING.bat`

2. **Open Sound Control Panel**
   - Scroll down → Click "More sound settings" 
   - OR Click "Sound Control Panel"

3. **Go to RECORDING tab**

4. **Find: CABLE Output** (VB-Audio Virtual Cable)
   - Should show green bars when TTS plays
   - If you don't see it, right-click → Show Disabled Devices

5. **Right-click CABLE Output → Properties**

6. **Go to LISTEN tab**

7. **✅ CHECK: "Listen to this device"**

8. **In dropdown: Select your SPEAKERS/HEADPHONES**
   - This is where YOU will hear the TTS!
   - Choose your normal audio output device

9. **Click APPLY → OK**

**Result:** TTS audio now goes to BOTH VB-CABLE (for teammates) AND your speakers (for you)!

---

### Step 2: Configure Valorant Voice Chat

1. **Launch Valorant**

2. **Settings → Audio → Voice Chat**

3. **Set these options:**
   ```
   Input Device: CABLE Output (VB-Audio Virtual Cable)
   Output Device: [Your normal speakers/headphones]
   Voice Activation: OPEN MIC
   Input Sensitivity: Adjust so green bar shows when TTS plays
   ```

4. **IMPORTANT: Use OPEN MIC, not Push to Talk!**
   - PTT won't work with TTS automation

---

### Step 3: Configure ValVoice App

1. **Launch ValVoice** (run `START_VALVOICE.bat`)

2. **Go to Settings panel**

3. **Select voice:**
   - Choose any Windows TTS voice
   - Adjust speech rate slider

4. **Select message sources:**
   - SELF+PARTY+TEAM (recommended)
   - Or any combination you prefer

5. **The app automatically routes PowerShell TTS to VB-CABLE!**
   - You should see: `✓ PowerShell audio routed to VB-CABLE`
   - Your game audio stays on normal speakers

---

## 🧪 Testing Your Setup

### Test 1: Hear TTS on YOUR Speakers
```cmd
TEST_TTS_SPEAKERS.bat
```
- **Expected:** You hear voice through your speakers
- **If silent:** Check Windows volume mixer, unmute PowerShell

### Test 2: Verify VB-CABLE Routing
1. Open Windows Volume Mixer (right-click speaker icon)
2. You should see: `PowerShell` with audio going to "CABLE Input"
3. In Sound Control Panel → Recording tab → CABLE Output should show green bars

### Test 3: Full Integration Test
1. Launch Valorant
2. Join a party/game with friends
3. Have someone type in chat
4. **Expected results:**
   - ✅ You hear TTS on your speakers
   - ✅ You hear game audio normally
   - ✅ Your teammates hear TTS through voice chat

---

## 🔧 Audio Flow Diagram

```
┌─────────────────────────────────────────────────────────┐
│  ValVoice TTS Engine (PowerShell)                       │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
       ┌─────────────────────┐
       │  CABLE Input        │ ◄─── Routed by ValVoice
       │  (Playback Device)  │
       └─────┬───────────────┘
             │
             ├────────────────────────┐
             │                        │
             ▼                        ▼
    ┌─────────────────┐    ┌──────────────────────┐
    │  CABLE Output   │    │  Your Speakers       │
    │  (Recording)    │    │  (via "Listen to")   │
    └────┬────────────┘    └──────────────────────┘
         │                          │
         ▼                          ▼
┌─────────────────────┐    ┌──────────────────┐
│ Valorant            │    │  YOU hear TTS!   │
│ (Voice Input)       │    │  + Game audio    │
└────┬────────────────┘    └──────────────────┘
     │
     ▼
┌─────────────────────────┐
│ Your teammates hear TTS │
│ through voice chat      │
└─────────────────────────┘
```

---

## ❓ Troubleshooting

### Problem: "I can't hear TTS on my speakers"

**Solution:** Enable VB-CABLE monitoring (Step 1 above)
- Sound Control Panel → Recording tab
- CABLE Output → Properties → Listen tab
- ✅ Check "Listen to this device"
- Select your speakers in dropdown

### Problem: "Teammates can't hear TTS"

**Solutions:**
1. Check Valorant Input Device: Should be "CABLE Output"
2. Check Voice Activation: Must be "Open Mic" (not Push to Talk)
3. Adjust Input Sensitivity in Valorant
4. Test VB-CABLE: Recording tab → CABLE Output should show green bars

### Problem: "I hear echo/feedback"

**Solution:** This happens if Valorant Output is also set to VB-CABLE
- Valorant Settings → Audio → Voice Chat
- Output Device: Set to your NORMAL speakers (not VB-CABLE!)

### Problem: "TTS is very quiet"

**Solutions:**
1. Open Windows Volume Mixer
2. Find "PowerShell" → Increase volume to 100%
3. Sound Control Panel → Recording → CABLE Output → Properties → Levels → Boost

### Problem: "Game audio is missing"

**Solution:** Game audio should NEVER be affected!
- Game uses your normal speakers/headphones
- Only PowerShell TTS goes to VB-CABLE
- Check Windows Sound → App volume and device preferences
- Make sure game isn't routed to VB-CABLE

---

## ✅ Quick Verification Checklist

- [ ] VB-Audio Virtual Cable installed
- [ ] CABLE Output → Listen to this device → ENABLED
- [ ] CABLE Output → Playback through → Your Speakers/Headphones
- [ ] Valorant → Input Device → CABLE Output
- [ ] Valorant → Output Device → Your Speakers/Headphones  
- [ ] Valorant → Voice Activation → OPEN MIC
- [ ] ValVoice → Audio routing status → ✓ Active
@echo off
echo ========================================
echo ValVoice Audio Monitoring Setup
echo ========================================
echo.
echo This will configure VB-CABLE so YOU can hear TTS on your speakers!
echo.
echo What this does:
echo 1. TTS audio goes to VB-CABLE Input
echo 2. VB-CABLE Input mirrors to YOUR speakers (so you hear it)
echo 3. Valorant picks up audio from CABLE Output
echo 4. Your teammates hear it through voice chat
echo 5. Game audio stays on your normal speakers
echo.
echo Result: EVERYONE hears TTS, you hear game audio normally!
echo.
pause

echo.
echo Setting up audio monitoring via PowerShell...
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
"$ErrorActionPreference='Stop'; ^
try { ^
    Write-Host 'Looking for VB-CABLE Input device...' -ForegroundColor Cyan; ^
    $cableDevice = Get-PnpDevice -FriendlyName '*CABLE Input*' -Class 'MEDIA' -ErrorAction SilentlyContinue | Select-Object -First 1; ^
    if (-not $cableDevice) { ^
        Write-Host 'ERROR: VB-CABLE not found! Please install VB-Audio Virtual Cable.' -ForegroundColor Red; ^
        Write-Host 'Download from: https://vb-audio.com/Cable/' -ForegroundColor Yellow; ^
        exit 1; ^
    } ^
    Write-Host 'VB-CABLE found!' -ForegroundColor Green; ^
    Write-Host ''; ^
    Write-Host 'Opening Sound Settings to enable monitoring...' -ForegroundColor Cyan; ^
    Start-Process 'ms-settings:sound'; ^
    Start-Sleep -Seconds 2; ^
    Write-Host ''; ^
    Write-Host '========================================' -ForegroundColor Yellow; ^
    Write-Host 'MANUAL STEPS REQUIRED:' -ForegroundColor Yellow; ^
    Write-Host '========================================' -ForegroundColor Yellow; ^
    Write-Host ''; ^
    Write-Host '1. In Sound Settings, scroll down and click:' -ForegroundColor White; ^
    Write-Host '   [More sound settings] or [Sound Control Panel]' -ForegroundColor Cyan; ^
    Write-Host ''; ^
    Write-Host '2. Go to the RECORDING tab' -ForegroundColor White; ^
    Write-Host ''; ^
    Write-Host '3. Find: CABLE Output (VB-Audio Virtual Cable)' -ForegroundColor White; ^
    Write-Host ''; ^
    Write-Host '4. RIGHT-CLICK on it and select Properties' -ForegroundColor White; ^
    Write-Host ''; ^
    Write-Host '5. Go to the LISTEN tab' -ForegroundColor White; ^
    Write-Host ''; ^
    Write-Host '6. CHECK the box: [ ] Listen to this device' -ForegroundColor Green; ^
    Write-Host ''; ^
    Write-Host '7. In the dropdown below, select your SPEAKERS/HEADPHONES' -ForegroundColor White; ^
    Write-Host '   (Your normal audio output device)' -ForegroundColor Gray; ^
    Write-Host ''; ^
    Write-Host '8. Click APPLY, then OK' -ForegroundColor White; ^
    Write-Host ''; ^
    Write-Host '========================================' -ForegroundColor Yellow; ^
    Write-Host 'After this setup:' -ForegroundColor Green; ^
    Write-Host '- You WILL hear TTS on your speakers' -ForegroundColor Green; ^
    Write-Host '- Teammates WILL hear TTS in Valorant' -ForegroundColor Green; ^
    Write-Host '- You WILL hear game audio normally' -ForegroundColor Green; ^
    Write-Host '========================================' -ForegroundColor Yellow; ^
    Write-Host ''; ^
    Write-Host 'Press any key when setup is complete...' -ForegroundColor White; ^
} catch { ^
    Write-Host 'ERROR: ' $_.Exception.Message -ForegroundColor Red; ^
    exit 1; ^
}"

echo.
echo ========================================
echo Setup instructions displayed!
echo.
echo After completing the manual steps above,
echo run TEST_TTS_SPEAKERS.bat to verify!
echo ========================================
pause

