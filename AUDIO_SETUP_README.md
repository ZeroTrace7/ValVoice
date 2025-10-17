git add .
git commit -m "update"
git push
# 🎮 ValVoice - Valorant Text-to-Speech Voice Chat

**Make your teammates hear Valorant chat messages through voice chat automatically!**

---

## ⚡ Quick Start (3 Steps)

### 1️⃣ Install VB-Audio Virtual Cable
- Download: **https://vb-audio.com/Cable/**
- Install and **restart your computer**

### 2️⃣ Enable Audio Monitoring (CRITICAL!)
```cmd
Run: AUDIO_TEST_SUITE.bat
Choose: Option 3 or 4
```

**This makes YOU hear TTS on your speakers!**

### 3️⃣ Launch ValVoice
```cmd
Double-click: START_VALVOICE.bat
```

**Done!** 🎉

---

## 🔊 Why You Need Audio Monitoring

**Without monitoring:** TTS goes to VB-CABLE only → You can't hear it ❌  
**With monitoring:** TTS goes to VB-CABLE AND your speakers → Everyone hears it ✅

### The Secret: "Listen to this device"

When you enable this feature on CABLE Output:
- ✅ TTS plays through VB-CABLE Input
- ✅ VB-CABLE Output mirrors to your speakers (you hear it!)
- ✅ Valorant picks up audio from CABLE Output (teammates hear it!)
- ✅ Game audio stays on your normal speakers (unchanged!)

**Result: Everyone hears TTS, you hear game audio normally!**

---

## 🎯 Complete Setup Guide

### Step 1: Install VB-CABLE
1. Download from: https://vb-audio.com/Cable/
2. Run installer (requires admin)
3. **Restart your computer** (required!)
4. Verify: You should see "CABLE Input" and "CABLE Output" in sound devices

### Step 2: Configure Audio Monitoring

**Option A: Automated (Recommended)**
```cmd
Run: AUTO_SETUP_MONITORING.bat
```

**Option B: Manual Setup**
1. Right-click speaker icon → Open Sound settings
2. Click "More sound settings" or "Sound Control Panel"
3. Go to **RECORDING** tab
4. Find: **CABLE Output** (VB-Audio Virtual Cable)
   - If not visible: right-click empty space → Show Disabled Devices
5. **Right-click CABLE Output** → Properties
6. Go to **LISTEN** tab
7. **✅ CHECK:** "Listen to this device"
8. **Select dropdown:** Your speakers/headphones (where you hear games)
9. Click **APPLY** → **OK**

### Step 3: Configure Valorant
1. Launch Valorant
2. Settings → Audio → Voice Chat
3. **Input Device:** CABLE Output (VB-Audio Virtual Cable)
4. **Output Device:** Your normal speakers/headphones
5. **Voice Activation:** OPEN MIC ⚠️ (PTT won't work!)
6. **Input Sensitivity:** Adjust so green bar shows when TTS plays

### Step 4: Launch ValVoice
```cmd
START_VALVOICE.bat
```

### Step 5: Configure ValVoice Settings
1. **Voice:** Choose any Windows TTS voice
2. **Rate:** Adjust speech speed
3. **Sources:** Select SELF+PARTY+TEAM (recommended)

---

## 🧪 Testing Your Setup

### Run Complete Test Suite
```cmd
AUDIO_TEST_SUITE.bat
```

**Test 1:** TTS on default speakers (verifies Windows TTS works)  
**Test 2:** TTS through VB-CABLE (verifies monitoring works)

### Quick Tests

**Test if YOU can hear TTS:**
```cmd
TEST_TTS_SPEAKERS.bat
```
Expected: You hear voice on your speakers ✅

**Test VB-CABLE routing:**
```cmd
TEST_TTS_VBCABLE.bat
```
Expected: You hear voice on your speakers (via monitoring) ✅

**Full integration test:**
1. Launch ValVoice
2. Launch Valorant
3. Join party/game with friends
4. Have someone type in chat
5. **Expected:**
   - ✅ You hear TTS on your speakers
   - ✅ You hear game audio normally  
   - ✅ Teammates hear TTS through voice chat

---

## 🔧 Audio Flow Explained

```
┌─────────────────────────────────────────┐
│  ValVoice TTS (PowerShell)              │
└──────────────┬──────────────────────────┘
               │ (Routed by ValVoice)
               ▼
    ┌──────────────────────┐
    │  CABLE Input         │ ◄─── Playback device
    │  (VB-Audio)          │
    └──────┬───────────────┘
           │
           │ (Virtual audio cable)
           │
           ▼
    ┌──────────────────────┐
    │  CABLE Output        │ ◄─── Recording device
    │  (VB-Audio)          │
    └──┬───────────────┬───┘
       │               │
       │               │ ("Listen to this device")
       │               │
       │               ▼
       │        ┌────────────────────┐
       │        │  Your Speakers     │ ◄─── YOU hear TTS!
       │        │  + Game Audio      │
       │        └────────────────────┘
       │
       │ (Valorant voice input)
       │
       ▼
┌──────────────────────┐
│  Valorant Voice Chat │
└──────┬───────────────┘
       │
       ▼
┌─────────────────────────┐
│  Teammates hear TTS!    │ ◄─── TEAMMATES hear TTS!
└─────────────────────────┘
```

**Key Points:**
- 🎮 Game audio: Always on your normal speakers (unchanged!)
- 🔊 TTS audio: Goes to BOTH VB-CABLE (teammates) AND speakers (you)
- 🎤 Your voice: Your real microphone still works normally

---

## ❓ Troubleshooting

### "I can't hear TTS on my speakers"

**Problem:** Audio monitoring not enabled

**Solution:**
```cmd
Run: AUDIO_TEST_SUITE.bat → Option 3
```
Then enable "Listen to this device" on CABLE Output

### "Teammates can't hear TTS"

**Solutions:**
1. ✅ Valorant → Input Device → CABLE Output (not your microphone!)
2. ✅ Valorant → Voice Activation → OPEN MIC (not Push to Talk!)
3. ✅ Valorant → Input Sensitivity → Adjust so green bar shows
4. ✅ Test: Recording tab → CABLE Output → Should show green bars when TTS plays

### "I hear echo/feedback"

**Problem:** Valorant output is also set to VB-CABLE

**Solution:**
- Valorant → Audio → Voice Chat
- Output Device → Your NORMAL speakers (NOT VB-CABLE!)

### "TTS is very quiet"

**Solutions:**
1. Windows Volume Mixer → PowerShell → Increase to 100%
2. Sound Control Panel → Recording → CABLE Output → Properties → Levels → Boost
3. Sound Control Panel → Recording → CABLE Output → Listen tab → Increase playback volume

### "Game audio is missing"

**Solution:** Game audio should NEVER be affected!
- Check: Windows Sound → App volume → Make sure game uses your normal speakers
- ValVoice only routes PowerShell TTS to VB-CABLE
- Your game should NEVER be routed to VB-CABLE

### "ValVoice says 'Audio routing failed'"

**This is OK!** The app tried to auto-route but:
- The important part is: "Listen to this device" on CABLE Output
- Manual setup (Option 3) is the most reliable method
- As long as you hear TTS in Test 2, everything works!

---

## 📋 Quick Verification Checklist

Before reporting issues, verify:

- [ ] VB-Audio Virtual Cable installed and PC restarted
- [ ] Sound Control Panel → Recording → CABLE Output exists
- [ ] CABLE Output → Properties → Listen tab → ✅ "Listen to this device"
- [ ] CABLE Output → Listen tab → Playback through: Your Speakers
- [ ] Valorant → Input Device: CABLE Output (VB-Audio Virtual Cable)
- [ ] Valorant → Output Device: Your Speakers/Headphones
- [ ] Valorant → Voice Activation: OPEN MIC ⚠️
- [ ] TEST_TTS_SPEAKERS.bat: You hear voice ✅
- [ ] TEST_TTS_VBCABLE.bat: You hear voice ✅
- [ ] Type in Valorant chat: TTS speaks and teammates hear it ✅

---

## 🎮 In-Game Usage

1. **Launch Valorant** (any mode: Unrated, Competitive, Custom, etc.)
2. **Launch ValVoice** (START_VALVOICE.bat)
3. **Join a party or game**
4. **Someone types in chat** (party, team, or whispers)
5. **ValVoice automatically:**
   - Reads the message
   - Plays TTS through VB-CABLE
   - You hear it on your speakers
   - Teammates hear it through voice chat
6. **No manual action required!** No pressing V, no clicking anything!

---

## 📁 Useful Files

| File | Purpose |
|------|---------|
| `START_VALVOICE.bat` | Launch the main application |
| `AUDIO_TEST_SUITE.bat` | Complete audio testing + setup menu |
| `AUTO_SETUP_MONITORING.bat` | Automated audio monitoring setup |
| `TEST_TTS_SPEAKERS.bat` | Quick test: Hear TTS on speakers |
| `TEST_TTS_VBCABLE.bat` | Quick test: Verify VB-CABLE routing |
| `COMPLETE_AUDIO_GUIDE.md` | Detailed audio setup documentation |

---

## 🔐 System Requirements

- **OS:** Windows 10/11 (64-bit)
- **Java:** JRE 17 or higher
- **Audio:** VB-Audio Virtual Cable installed
- **Valorant:** Running with Riot Vanguard active
- **Internet:** Required for first-time XMPP connection

---

## 🛠️ Technical Details

### What ValVoice Does Automatically:

1. ✅ Connects to Riot XMPP chat server
2. ✅ Monitors Valorant chat messages (party, team, whispers)
3. ✅ Filters messages based on your settings
4. ✅ Generates TTS using Windows Speech API
5. ✅ Routes PowerShell TTS to VB-CABLE Input
6. ✅ Updates statistics in real-time

### What YOU Need to Do Manually:

1. ⚠️ Install VB-CABLE (one-time)
2. ⚠️ Enable "Listen to this device" on CABLE Output (one-time)
3. ⚠️ Set Valorant voice input to CABLE Output (one-time)
4. ⚠️ Set Valorant to OPEN MIC mode (one-time)

**After initial setup: Everything is automatic!**

---

## 💡 Pro Tips

1. **Voice Selection:** Try different voices! Some sound better than others.
2. **Speech Rate:** Slower = more clear, Faster = less annoying
3. **Message Sources:** Start with PARTY+TEAM, add ALL if you want
4. **Volume Balance:** Keep TTS volume lower than game audio
5. **Test First:** Always test with friends in custom game before competitive!

---

## 🎯 Common Mistakes to Avoid

❌ **Forgetting to enable "Listen to this device"**  
   → Result: Teammates hear TTS, you don't

❌ **Using Push to Talk in Valorant**  
   → Result: TTS won't be transmitted (use Open Mic!)

❌ **Setting Valorant output to VB-CABLE**  
   → Result: Echo/feedback issues

❌ **Not restarting after VB-CABLE install**  
   → Result: Audio devices not properly initialized

❌ **Setting Valorant input to your real microphone**  
   → Result: TTS doesn't reach teammates

---

## 📞 Support

If you're stuck:

1. Run `AUDIO_TEST_SUITE.bat` → Check all tests
2. Read `COMPLETE_AUDIO_GUIDE.md` → Detailed troubleshooting
3. Check ValVoice logs → Look for "🔊 TTS TRIGGERED" messages
4. Verify Valorant is running with Riot Client active
5. Make sure VB-CABLE "Listen to this device" is enabled

**Most issues are solved by enabling "Listen to this device" on CABLE Output!**

---

## ⚡ TL;DR - Ultra Quick Setup

```cmd
1. Install VB-CABLE from https://vb-audio.com/Cable/
2. Restart PC
3. Run: AUDIO_TEST_SUITE.bat → Option 3 → Enable "Listen to this device"
4. Valorant: Input = CABLE Output, Voice = Open Mic
5. Run: START_VALVOICE.bat
6. Done! Type in chat to test.
```

**The secret sauce:** "Listen to this device" on CABLE Output ← Don't forget this!

---

**Enjoy automatic TTS in Valorant!** 🎮🔊
@echo off
title ValVoice - Complete Audio Test Suite
color 0A

:MENU
cls
echo ========================================
echo     ValVoice Audio Test Suite
echo ========================================
echo.
echo Choose a test:
echo.
echo 1. Test TTS on Default Speakers
echo    (Verifies Windows TTS is working)
echo.
echo 2. Test VB-CABLE Routing
echo    (Verifies ValVoice routing + monitoring)
echo.
echo 3. Setup Audio Monitoring (REQUIRED!)
echo    (Enable "Listen to this device")
echo.
echo 4. Auto-Setup Audio Monitoring
echo    (Automated setup attempt)
echo.
echo 5. Check Current Audio Status
echo.
echo 6. Open Sound Control Panel
echo.
echo 7. Exit
echo.
echo ========================================
set /p choice="Enter your choice (1-7): "

if "%choice%"=="1" goto TEST_SPEAKERS
if "%choice%"=="2" goto TEST_VBCABLE
if "%choice%"=="3" goto SETUP_MANUAL
if "%choice%"=="4" goto SETUP_AUTO
if "%choice%"=="5" goto CHECK_STATUS
if "%choice%"=="6" goto OPEN_SOUND
if "%choice%"=="7" goto EXIT
goto MENU

:TEST_SPEAKERS
cls
echo ========================================
echo Test 1: TTS on Default Speakers
echo ========================================
echo.
echo This plays TTS through your DEFAULT audio device
echo (Should work even without VB-CABLE)
echo.
pause
echo.
echo Playing test message...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$speak = New-Object -ComObject SAPI.SpVoice; $speak.Speak('Hello! This is test number one. If you can hear this, your Windows text to speech is working correctly!', 1)"
echo.
echo Did you hear the voice?
echo - YES = Windows TTS is working!
echo - NO  = Check your speaker volume and audio drivers
echo.
pause
goto MENU

:TEST_VBCABLE
cls
echo ========================================
echo Test 2: VB-CABLE Routing
echo ========================================
echo.
echo This tests if you can hear TTS routed to VB-CABLE
echo.
echo IMPORTANT: This only works if you completed
echo the audio monitoring setup (Option 3 or 4)!
echo.
pause
echo.
echo Playing test message through VB-CABLE...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$speak = New-Object -ComObject SAPI.SpVoice; $speak.Speak('This is test number two. If you hear this on your speakers, your audio monitoring is configured correctly. Your Valorant teammates would also hear this!', 1)"
echo.
echo Did you hear the voice on your SPEAKERS?
echo.
echo - YES = Perfect! Audio monitoring works!
echo         You and teammates will hear TTS.
echo.
echo - NO  = Audio monitoring not enabled yet.
echo         Choose Option 3 or 4 to set it up.
echo.
pause
goto MENU

:SETUP_MANUAL
cls
echo ========================================
echo Setup Audio Monitoring (Manual)
echo ========================================
echo.
echo Opening Sound Control Panel...
start control mmsys.cpl
timeout /t 2 /nobreak >nul
echo.
echo ========================================
echo FOLLOW THESE STEPS:
echo ========================================
echo.
echo 1. Click the RECORDING tab
echo.
echo 2. Find: CABLE Output (VB-Audio Virtual Cable)
echo    (If not visible: right-click and Show Disabled Devices)
echo.
echo 3. RIGHT-CLICK on "CABLE Output"
echo.
echo 4. Select "Properties"
echo.
echo 5. Go to the "LISTEN" tab
echo.
echo 6. CHECK the box: [X] Listen to this device
echo.
echo 7. In "Playback through" dropdown:
echo    SELECT YOUR SPEAKERS/HEADPHONES
echo    (Where you normally hear game audio)
echo.
echo 8. Click APPLY, then OK
echo.
echo ========================================
echo.
echo After completing these steps, run Test 2
echo to verify everything is working!
echo.
pause
goto MENU

:SETUP_AUTO
cls
call AUTO_SETUP_MONITORING.bat
goto MENU

:CHECK_STATUS
cls
echo ========================================
echo Current Audio Configuration
echo ========================================
echo.
echo Checking VB-CABLE installation...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
"$devices = Get-PnpDevice -Class 'MEDIA' -Status OK | Where-Object { $_.FriendlyName -like '*CABLE*' }; ^
if ($devices) { ^
    Write-Host '[OK] VB-CABLE is installed' -ForegroundColor Green; ^
    Write-Host ''; ^
    Write-Host 'Checking audio devices...' -ForegroundColor Cyan; ^
    Write-Host ''; ^
    Get-CimInstance Win32_SoundDevice | Where-Object { $_.Name -like '*CABLE*' } | ForEach-Object { ^
        Write-Host '  - ' $_.Name -ForegroundColor White; ^
    }; ^
} else { ^
    Write-Host '[ERROR] VB-CABLE not found!' -ForegroundColor Red; ^
    Write-Host 'Install from: https://vb-audio.com/Cable/' -ForegroundColor Yellow; ^
}"
echo.
echo ========================================
echo.
echo To check if "Listen to this device" is enabled:
echo - Open Sound Control Panel (Option 6)
echo - Recording tab - CABLE Output - Properties - Listen tab
echo.
pause
goto MENU

:OPEN_SOUND
cls
echo Opening Sound Control Panel...
start control mmsys.cpl
timeout /t 2 /nobreak >nul
echo.
echo Sound Control Panel opened!
echo.
echo Useful tabs:
echo - Playback: Your speakers/headphones
echo - Recording: CABLE Output (enable monitoring here!)
echo.
pause
goto MENU

:EXIT
cls
echo ========================================
echo Thank you for using ValVoice!
echo ========================================
echo.
echo For more help, see: COMPLETE_AUDIO_GUIDE.md
echo.
timeout /t 2 /nobreak >nul
exit

