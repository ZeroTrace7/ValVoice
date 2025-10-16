# 🔊 ValVoice Audio Setup Guide

Complete guide for setting up audio routing so teammates hear TTS in Valorant voice chat.

---

## 📦 Step 1: Install VB-Cable

1. Download VB-Cable from: https://vb-audio.com/Cable/
2. Extract the ZIP file
3. Right-click `VBCABLE_Setup_x64.exe` → **Run as Administrator**
4. Click **Install Driver**
5. Restart your computer

**Verify Installation:**
- Right-click speaker icon → Sound settings → Sound Control Panel
- Check **Playback** tab: You should see "CABLE Input"
- Check **Recording** tab: You should see "CABLE Output"

---

## ⚙️ Step 2: Configure Valorant Audio

Launch Valorant and configure these settings:

**Settings → Audio → Voice Chat:**
- **Voice Chat:** ENABLED ✅
- **Input Device:** **CABLE Output (VB-Audio Virtual Cable)** ⭐
- **Output Device:** Your normal headphones/speakers
- **Voice Activation:** **OPEN MIC** (NOT Push to Talk!) ⭐
- **Voice Activation Sensitivity:** LOW (far left)
- **Incoming Voice Volume:** 80-100%
- **Party/Team Voice Volume:** 100%

**Why Open Mic?** So TTS speaks automatically without pressing V key!

---

## 🎮 Step 3: How It Works

```
1. Type message in Valorant chat → Press ENTER
   ↓
2. ValVoice TTS speaks → Routes to VB-CABLE Input
   ↓
3. VB-CABLE Output (virtual mic) → Valorant picks it up
   ↓
4. Teammates hear TTS automatically! 🎤
```

**What ValVoice Does Automatically:**
- ✅ Saves your original audio device on startup
- ✅ Routes TTS audio to VB-Cable (system audio stays normal)
- ✅ Restores original audio device when you close the app
- ✅ You hear game audio, music, everything normally!

---

## 🔧 Troubleshooting

### Issue: Teammates can't hear TTS

**Fix:**
1. Valorant Settings → Audio → Voice Chat
2. Input Device = **CABLE Output** (not "CABLE Input")
3. Voice Activation = **OPEN MIC**
4. Voice Chat = **ENABLED**
5. Check ValVoice logs for: `✓ Audio successfully routed to VB-CABLE`

### Issue: TTS cuts off mid-sentence

**Fix:** Lower Voice Activation Sensitivity in Valorant (move slider far left)

### Issue: TTS too quiet

**Fix:** Increase Incoming Voice Volume in Valorant to 100%

Or in Windows:
- Sound Control Panel → Recording tab
- Right-click "CABLE Output" → Properties → Levels
- Increase microphone boost to 80-100%

### Issue: Can't hear game audio

**This shouldn't happen!** ValVoice only routes TTS, not system audio.

If it does:
1. Right-click speaker icon → Sound settings
2. Output = Your headphones/speakers (NOT VB-Cable)
3. Close ValVoice properly (it auto-restores on exit)

### Issue: Audio not restored after closing ValVoice

**Emergency Reset:**
1. Right-click speaker icon → Sound settings
2. Output → Select your normal device
3. Or Sound Control Panel → Playback → Right-click device → Set as Default

---

## 🎙️ Advanced: Using Real Mic + TTS Together

Want to talk with your voice AND use TTS? Use **Voicemeeter**:

1. Download: https://vb-audio.com/Voicemeeter/
2. Configure:
   - Hardware Input 1: Your microphone
   - Hardware Input 2: CABLE Output
   - Enable both inputs (A1 button)
3. In Valorant:
   - Input Device: **VoiceMeeter Output**
   - Voice Activation: OPEN MIC

Now both your voice and TTS go to Valorant!

---

## ✅ Quick Checklist

- [ ] VB-Cable installed and restarted PC
- [ ] Valorant Input Device = **CABLE Output**
- [ ] Voice Activation = **OPEN MIC**
- [ ] Voice Chat = **ENABLED**
- [ ] ValVoice shows: `✓ Audio successfully routed to VB-CABLE`
- [ ] Tested: Type message → ENTER → TTS speaks!

---

## 🎉 Done!

**Type message → Press ENTER → Teammates hear TTS automatically!** 🎤✨

Need help? Check ValVoice logs for detailed status messages.
