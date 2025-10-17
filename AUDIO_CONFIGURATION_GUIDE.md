# 🔊 ValVoice Audio Setup Guide

Complete guide for setting up audio routing so teammates hear TTS in Valorant voice chat.

---

## 📦 Step 1: Install VB-Cable

1. **Download VB-Cable** from: https://vb-audio.com/Cable/
2. Extract the ZIP file to a temporary folder
3. Right-click `VBCABLE_Setup_x64.exe` → **Run as Administrator**
4. Click **Install Driver** and wait for completion
5. **Restart your computer** (required for driver installation)

**Verify Installation:**
- Open Windows Sound Settings:
  - **Windows 11**: Right-click speaker icon → Sound settings → More sound settings
  - **Windows 10**: Right-click speaker icon → Sounds → Playback tab
- Check **Playback** tab: You should see "CABLE Input (VB-Audio Virtual Cable)"
- Check **Recording** tab: You should see "CABLE Output (VB-Audio Virtual Cable)"

> 💡 **Tip:** If you don't see these devices, restart your PC again.

---

## ⚙️ Step 2: Configure Valorant Audio

Launch Valorant and configure these settings:

**Settings → Audio → Voice Chat:**
- **Voice Chat:** ENABLED ✅
- **Input Device:** **CABLE Output (VB-Audio Virtual Cable)** ⭐
- **Output Device:** Your normal headphones/speakers
- **Voice Activation:** **OPEN MIC** (NOT Push to Talk!) ⭐
- **Voice Activation Sensitivity:** LOW (slider far left ~10-20%)
- **Incoming Voice Volume:** 80-100%
- **Party Voice Volume:** 100%
- **Team Voice Volume:** 100%
- **Loopback Test:** Turn OFF (you don't want to hear yourself)

**❗ Critical Settings:**
- ✅ **Input Device = CABLE Output** (NOT "CABLE Input" - this is the most common mistake!)
- ✅ **Voice Activation = OPEN MIC** (Push to Talk won't work with automatic TTS)

---

## 🎮 Step 3: How It Works

```
1. You type message in Valorant chat → Press ENTER
   ↓
2. ValVoice detects message via XMPP bridge
   ↓
3. TTS engine speaks text → Audio routes to VB-CABLE Input
   ↓
4. VB-CABLE Output (acts as virtual microphone) → Valorant picks it up
   ↓
5. Teammates hear TTS through in-game voice chat! 🎤
```

**What ValVoice Does Automatically:**
- ✅ Detects VB-Cable installation on startup
- ✅ Routes TTS audio ONLY to VB-Cable (your normal audio stays normal)
- ✅ Saves your original audio device settings
- ✅ Restores original settings when you close the app
- ✅ You continue to hear game audio, music, Discord normally!

**What You Hear vs. What Teammates Hear:**
- **You:** Game sounds, music, Discord (normal)
- **Teammates:** TTS voice through Valorant voice chat
- **Note:** You won't hear your own TTS unless you enable loopback

---

## 🔧 Troubleshooting

### ❌ Issue: Teammates can't hear TTS

**Solution Checklist:**
1. Valorant Settings → Audio → Voice Chat
2. Verify: Input Device = **CABLE Output** (not "CABLE Input" or "Default")
3. Verify: Voice Activation = **OPEN MIC** (not Push to Talk)
4. Verify: Voice Chat = **ENABLED**
5. Check ValVoice status bar at bottom:
   - Should show: `✓ VB-Cable: Detected`
   - Should show: `✓ Audio Route: Active (built-in)`
6. Test by typing a message in Valorant all chat and pressing ENTER
7. Ask teammate if they can hear anything at all

**Still not working?**
- Restart Valorant (voice settings sometimes need game restart)
- Check Windows Sound → Recording → CABLE Output → Make sure it's not muted
- Make sure ValVoice is running BEFORE you start Valorant

---

### ❌ Issue: TTS cuts off mid-sentence

**Causes:** Voice Activation Sensitivity too high, or voice is too quiet

**Fix:**
1. In Valorant: Lower Voice Activation Sensitivity (move slider far left to ~10%)
2. In Windows Sound Settings:
   - Right-click speaker icon → Sound settings → Sound Control Panel
   - Recording tab → Right-click "CABLE Output" → Properties
   - Levels tab → Increase Microphone volume to 80-100%
3. In ValVoice: Increase TTS rate slider slightly (slower speech = more stable)

---

### ❌ Issue: TTS too quiet for teammates

**Fix Option 1 - Valorant:**
- Increase Incoming Voice Volume to 100%
- Increase Party/Team Voice Volume to 100%

**Fix Option 2 - Windows:**
- Sound Control Panel → Recording tab
- Right-click "CABLE Output" → Properties → Levels
- Set Microphone to 100%
- Set Microphone Boost to +20dB or +30dB

**Fix Option 3 - ValVoice:**
- Adjust speech rate slider (slower = louder perceived volume)

---

### ❌ Issue: Can't hear game audio / music

**This shouldn't happen!** ValVoice only routes TTS, not system audio.

**If it does happen:**
1. Right-click speaker icon → Sound settings
2. Check: Output device = Your headphones/speakers (NOT VB-Cable Input)
3. Open Volume Mixer (right-click speaker → Open Volume Mixer)
4. Check: All apps are routed to your normal speakers
5. Close ValVoice properly using the X button (it auto-restores settings)

**Emergency Manual Reset:**
1. Windows Settings → System → Sound
2. Output → Select your normal audio device
3. OR: Sound Control Panel → Playback → Right-click your device → Set as Default

---

### ❌ Issue: Audio not restored after closing ValVoice

**Automatic Restore Failed - Manual Fix:**
1. Right-click speaker icon → Sound settings
2. Output → Select your normal device (headphones/speakers)
3. Advanced → App volume and device preferences
4. Set all apps back to "Default"

**Or use Sound Control Panel:**
1. Right-click speaker icon → Sounds
2. Playback tab → Right-click your device → Set as Default Device

---

### ❌ Issue: ValVoice not detecting messages

**Check XMPP Bridge Status:**
- Look at ValVoice status bar (bottom of window)
- Should show: `✓ XMPP: Connected` or `✓ XMPP: External exe`
- If shows error, check `valvoice-xmpp.exe` is in the same folder

**Check Valorant Integration:**
- Status bar should show: `✓ Self: [your-player-id]`
- If shows "pending", make sure Valorant is running
- Restart ValVoice after Valorant is fully loaded

**Check Chat Filters:**
- Settings tab → Sources: Set to "SELF+PARTY+TEAM+ALL"
- Make sure Team Chat toggle is ON
- Try typing in different chat channels (Party, Team, All)

---

## 🎙️ Advanced: Using Real Mic + TTS Together

Want to use your **real voice AND TTS at the same time?** Use **Voicemeeter**!

### Setup with Voicemeeter:

1. **Download & Install:**
   - Get Voicemeeter from: https://vb-audio.com/Voicemeeter/
   - Install and restart PC if prompted

2. **Configure Voicemeeter:**
   - **Hardware Input 1:** Your physical microphone
   - **Hardware Input 2:** CABLE Output (VB-Cable)
   - **Hardware Out A1:** Your headphones/speakers
   - Enable both inputs (click the **A1** button for each)

3. **Configure Valorant:**
   - Input Device: **VoiceMeeter Output** (or "VoiceMeeter Aux Output")
   - Voice Activation: **OPEN MIC**
   - Output Device: Your normal headphones/speakers

4. **Test:**
   - Speak into your mic → Teammates should hear you
   - Type message in Valorant → TTS plays → Teammates hear it
   - **Both work together!** 🎉

**Troubleshooting Voicemeeter:**
- Make sure both inputs show green activity bars when speaking/TTS
- Adjust individual input volumes in Voicemeeter if one is too loud/quiet
- Keep Voicemeeter running in the background

---

## 📊 Performance Tips

**Optimize TTS Performance:**
- Close unnecessary background apps to free CPU
- Use shorter messages for faster TTS response
- Adjust speech rate: 40-60 = balanced, 70-100 = faster
- Select high-quality voices (Microsoft voices work best)

**Reduce Audio Latency:**
- Keep ValVoice running continuously (don't restart frequently)
- Ensure VB-Cable driver is up to date
- Use wired headphones instead of Bluetooth (lower latency)

**System Requirements:**
- Windows 10/11 (64-bit)
- 4GB+ RAM
- Java 17 or higher
- Active internet for Valorant API access

---

## ✅ Quick Setup Checklist

- [ ] VB-Cable installed and PC restarted
- [ ] ValVoice running and shows all green status indicators
- [ ] Valorant Input Device = **CABLE Output**
- [ ] Valorant Voice Activation = **OPEN MIC**
- [ ] Voice Activation Sensitivity = LOW (10-20%)
- [ ] Voice Chat = **ENABLED**
- [ ] ValVoice status shows: `✓ VB-Cable: Detected`
- [ ] ValVoice status shows: `✓ Audio Route: Active`
- [ ] ValVoice status shows: `✓ Self: [player-id]`
- [ ] Tested: Type message in Valorant → Press ENTER → TTS plays
- [ ] Confirmed with teammate they can hear TTS

---

## ❓ FAQ

**Q: Will this get me banned from Valorant?**
A: No. ValVoice uses Riot's official local API (same as the Valorant client). It doesn't modify game files or inject code. It's purely a chat-to-voice tool.

**Q: Can teammates mute my TTS?**
A: Yes, they can mute you just like any other player (ESC → Social → Mute).

**Q: Does this work in all game modes?**
A: Yes - works in Competitive, Unrated, Spike Rush, Deathmatch, Custom games.

**Q: Can I use this in other games?**
A: Currently ValVoice only supports Valorant. Other games would need similar API access.

**Q: Why do I need Open Mic instead of Push to Talk?**
A: TTS needs to speak automatically when you type. Push to Talk requires manual key press, which defeats the automation purpose.

**Q: Can I customize the TTS voice?**
A: Yes! Settings tab → Voice dropdown → Select any installed Windows TTS voice. You can also install additional voices from Microsoft Store.

**Q: How much bandwidth does this use?**
A: Minimal. ValVoice only monitors local Valorant chat (no external servers). TTS is generated locally on your PC.

---

## 🎉 You're All Set!

**To use ValVoice:**
1. Launch Valorant first
2. Start ValVoice (it will auto-detect Valorant)
3. Type your message in Valorant chat
4. Press **ENTER**
5. TTS speaks automatically!
6. Teammates hear you through voice chat! 🎤✨

**Need help?**
- Check ValVoice logs (they show detailed status messages)
- Verify all status indicators are green at the bottom
- Try the troubleshooting steps above
- Make sure both Valorant and ValVoice are running

**Pro Tips:**
- Keep messages concise for faster TTS
- Use punctuation for natural-sounding speech
- Adjust speech rate to find your preferred speed
- Remember: Teammates can mute you if TTS is too frequent

---

*Last updated: Performance improvements & troubleshooting expanded*
