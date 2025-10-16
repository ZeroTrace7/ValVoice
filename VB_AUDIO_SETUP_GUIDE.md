# VB-Audio Virtual Cable Setup Guide

## What is VB-Audio Virtual Cable?

VB-Audio Virtual Cable is a virtual audio device that allows audio to be routed from one application to another. ValVoice uses it to route the Text-to-Speech (TTS) audio to your Valorant microphone input.

## Why You Need It

Without VB-Audio Virtual Cable:
- ✅ ValVoice can still read chat messages via XMPP
- ✅ TTS will play on your default speakers
- ❌ **Your teammates WON'T hear the TTS in Valorant**

With VB-Audio Virtual Cable:
- ✅ TTS audio is routed to a virtual microphone
- ✅ Valorant can use this virtual microphone
- ✅ **Your teammates CAN hear the TTS**

## Installation Steps

### 1. Download VB-Audio Virtual Cable

**Official Download:** https://vb-audio.com/Cable/

- Click "Download" under "VB-CABLE Virtual Audio Device"
- Save the ZIP file to your Downloads folder

### 2. Install VB-Audio Virtual Cable

1. Extract the downloaded ZIP file
2. Right-click `VBCABLE_Setup_x64.exe` (or `VBCABLE_Setup.exe` for 32-bit)
3. Select "Run as administrator"
4. Click "Install Driver"
5. Click "OK" when installation completes
6. **Restart your computer** (important!)

### 3. Verify Installation

After restarting:

1. Right-click the speaker icon in Windows system tray
2. Select "Open Sound settings"
3. Scroll down and click "Sound Control Panel"
4. Check the **Playback** tab - you should see:
   - `CABLE Input (VB-Audio Virtual Cable)`
5. Check the **Recording** tab - you should see:
   - `CABLE Output (VB-Audio Virtual Cable)`

### 4. Configure Valorant to Use VB-Cable

1. Launch Valorant
2. Go to Settings → Audio
3. Under "Voice Chat" section:
   - Set **Input Device** to: `CABLE Output (VB-Audio Virtual Cable)`
4. Test by using ValVoice - teammates should now hear the TTS

### 5. Optional: Mix Your Real Microphone + VB-Cable

If you want to speak AND have TTS in Valorant:

**Option A: Use Voicemeeter (Recommended)**
- Download Voicemeeter from: https://vb-audio.com/Voicemeeter/
- Route your real microphone AND VB-Cable through Voicemeeter
- Set Valorant to use Voicemeeter output

**Option B: Use Windows Listen Feature**
- Open Sound Control Panel
- Recording tab → Select your real microphone → Properties
- Listen tab → Check "Listen to this device"
- Playback device: CABLE Input
- This has slight latency but works

## Testing ValVoice with VB-Cable

1. Restart ValVoice after installing VB-Cable
2. Check the status bar - it should show:
   - VB-Cable: ✅ Detected
3. Go to Settings in ValVoice
4. Select a voice and test narration
5. Audio should route to VB-Cable (not your speakers)

## Troubleshooting

### "VB-Cable devices missing or incomplete"

**Cause:** VB-Cable not installed or Windows hasn't recognized it yet

**Solutions:**
1. Verify VB-Cable is installed (see Step 3 above)
2. Restart your computer
3. Reinstall VB-Cable as administrator

### "I hear TTS on my speakers, not in Valorant"

**Cause:** ValVoice isn't routing to VB-Cable properly

**Solutions:**
1. Make sure VB-Cable appears in Sound Control Panel
2. Restart ValVoice
3. Check ValVoice status bar shows "VB-Cable: Detected"

### "Teammates can't hear TTS in Valorant"

**Cause:** Valorant microphone not set to VB-Cable

**Solutions:**
1. In Valorant Settings → Audio
2. Set Input Device to "CABLE Output (VB-Audio Virtual Cable)"
3. Enable voice chat (V key by default)

### "My real microphone doesn't work anymore"

**Cause:** Valorant is only listening to VB-Cable

**Solutions:**
- Use Voicemeeter to mix both inputs (see Optional section above)
- Or manually switch Valorant's input device when not using ValVoice

## Without VB-Cable (Testing/Debugging Only)

You can test ValVoice without VB-Cable:

1. XMPP will still connect and receive messages
2. TTS will play on your default audio device
3. Only you will hear it (not teammates)
4. Good for testing chat connectivity

## Current Status in Your Logs

```
23:45:49.697 [JavaFX Application Thread] WARN ... VB-Audio Virtual Cable devices missing or incomplete: Input=false Output=false
```

This means VB-Cable is **NOT** installed on your system. Follow the installation steps above.

## Summary

| Feature | Without VB-Cable | With VB-Cable |
|---------|------------------|---------------|
| XMPP Chat Connection | ✅ Works | ✅ Works |
| Read teammate messages | ✅ Works | ✅ Works |
| TTS plays on speakers | ✅ Yes | ❌ No |
| Teammates hear TTS | ❌ No | ✅ Yes |

**Bottom line:** Install VB-Cable if you want teammates to hear the TTS narration.

