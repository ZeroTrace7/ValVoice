# 🧪 ValVoice Complete Testing Guide

## ✅ Step-by-Step Testing Instructions

### Prerequisites Check
Before starting, ensure:
- [ ] **VB-Audio Virtual Cable** is installed and Windows is rebooted
- [ ] **Valorant** is installed (or you can test in demo mode)
- [ ] **Java 17+** is installed
- [ ] Project is built: `mvn clean package -DskipTests`

---

## 🚀 Test Scenario 1: Demo Mode (No Valorant Required)

This tests TTS without needing Valorant running.

### Steps:

1. **Enable Demo Mode**
   - Open: `src\main\java\com\someone\valvoice\ValVoiceController.java`
   - Find line: `private static final boolean SIMULATE_CHAT = false;`
   - Change to: `private static final boolean SIMULATE_CHAT = true;`
   - Rebuild: `mvn clean package -DskipTests`

2. **Configure VB-CABLE as Default Output**
   - Right-click **Sound icon** in taskbar → **Sound settings**
   - Set **Output device** to **CABLE Input (VB-Audio Virtual Cable)**
   - This allows you to hear the TTS directly

3. **Run ValVoice**
   ```cmd
   cd C:\Users\HP\IdeaProjects\ValVoice
   java -jar target\valvoice-1.0.0.jar
   ```

4. **Check Status Bar** (bottom of ValVoice window)
   - **XMPP**: Should be "Ready" or "Stub (demo only)" (green/yellow)
   - **VB-Cable**: Should be "Detected" (green)
   - **Audio Routing**: Should be "Active (built-in)" (green)
   - **Self**: Should show "Self: playerSelf" after a few seconds

5. **Select Voice & Source**
   - In **Settings** tab:
     - **Voice**: Select any SAPI voice (e.g., "Microsoft Zira Desktop")
     - **Rate**: Set to 50 (middle)
     - **Sources**: Select "SELF+PARTY+TEAM"
   - Enable **Team Chat** toggle
   - Enable **Private Messages** toggle

6. **Expected Result**
   - After ~2.5 seconds, you should hear: "Hello party!"
   - Every 2.5 seconds, another message plays:
     - "Team push B"
     - "GL HF"
     - "Ready?"
     - "Encoded Whisper"
   - Check **User** tab → **Messages Narrated** counter increases

7. **Troubleshooting Demo Mode**
   - **No sound?** 
     - Check Windows Volume Mixer → Java should be at 100%
     - Verify CABLE Input is selected as Windows default output
   - **No messages?**
     - Check logs in console for errors
     - Verify "Sources" includes PARTY+TEAM

---

## 🎮 Test Scenario 2: Real Valorant Integration

This tests the full integration with actual Valorant chat.

### Steps:

1. **Disable Demo Mode** (if enabled)
   - Set `SIMULATE_CHAT = false` in ValVoiceController.java
   - Rebuild if you changed it

2. **Start Valorant First**
   - Launch **Valorant** and log in
   - Get to main menu (don't join a game yet)
   - This creates the Riot lockfile that ValVoice needs

3. **Configure Valorant Audio Settings**
   - In Valorant → **Settings** → **Audio** → **Voice Chat**
   - **Input Device**: Set to **CABLE Output (VB-Audio Virtual Cable)**
   - **Output Device**: Keep as your normal headset
   - **Voice Chat**: Enable it
   - Test your mic to ensure Valorant can detect it

4. **Run ValVoice**
   ```cmd
   cd C:\Users\HP\IdeaProjects\ValVoice
   java -jar target\valvoice-1.0.0.jar
   ```

5. **Verify Status Bar**
   - **XMPP**: "Ready" (green) - means bridge is running
   - **Bridge Mode**: "external-exe" or "embedded-script"
   - **VB-Cable**: "Detected" (green)
   - **Audio Routing**: "Active (built-in)" (green)
   - **Self**: Should show your player ID/PUUID after 10-30 seconds

6. **Configure ValVoice**
   - **Voice**: Select a clear voice (e.g., "Microsoft Zira Desktop")
   - **Rate**: 50-60 (moderate speed)
   - **Sources**: Select "PARTY+TEAM" or "SELF+PARTY+TEAM"
   - Enable **Team Chat** toggle
   - Enable **Private Messages** toggle if you want DMs narrated

7. **Join a Valorant Match**
   - Start any game mode (Unrated, Spike Rush, Custom, etc.)
   - Wait for agent select / in-game chat to become active

8. **Test Chat Messages**
   
   **Option A: Type in Team Chat**
   - Press `Enter` to open chat
   - Type: "Hello team"
   - Press `Enter` to send
   - **Expected**: You should hear "Hello team" spoken through your mic
   - Your teammates will hear it too!

   **Option B: Have Teammates Type**
   - Ask a teammate to type in team chat
   - **Expected**: You should hear their message narrated
   - It will be spoken through your Valorant mic

9. **Verify It's Working**
   - Check **User** tab in ValVoice:
     - **Messages Narrated**: Should increment with each message
     - **Characters Narrated**: Should increase
   - Watch console/logs for "Received message:" entries
   - Your Valorant teammates should hear the TTS voice through your mic

---

## 🔍 Test Scenario 3: Audio Routing Verification

This specifically tests the audio routing to VB-CABLE.

### Steps:

1. **Open Windows Sound Settings**
   ```
   Settings → System → Sound → Advanced sound options → App volume and device preferences
   ```

2. **Run ValVoice**
   - Start ValVoice application
   - Wait for initialization (~5 seconds)

3. **Check App Volume Settings**
   - Look for **java.exe** or **OpenJDK Platform binary** in the list
   - **Output device** should be set to **CABLE Input**
   - If it shows "Default", the auto-routing may have failed

4. **Manual Override (if needed)**
   - Click the dropdown next to java.exe
   - Select **CABLE Input (VB-Audio Virtual Cable)**
   - Close settings

5. **Test TTS**
   - In ValVoice → **Settings** → Select a voice
   - It should speak "Sample voice confirmation"
   - This confirms TTS is working

6. **Verify Valorant Receives Audio**
   - With Valorant running and CABLE Output as mic input
   - Use Valorant's mic test feature
   - Speak or trigger TTS in ValVoice
   - You should see the mic indicator light up in Valorant

---

## 📊 Expected Behavior Summary

### When Everything Works Correctly:

| Component | Expected State | Visual Indicator |
|-----------|---------------|------------------|
| **VB-Cable** | Installed & detected | Status: "Detected" (green) |
| **Audio Routing** | Automatic | Status: "Active (built-in)" (green) |
| **XMPP Bridge** | Running | Status: "Ready" (green) |
| **Valorant Connection** | Active | Self ID shows in status bar |
| **Chat Messages** | Narrated | Counter increases, sound plays |
| **Teammates** | Hear narration | Through Valorant voice chat |

### Audio Flow (Visual):
```
ValVoice Java App
      ↓
  TTS Engine (PowerShell SAPI)
      ↓
  [Automatic Routing]
      ↓
  CABLE Input (Playback Device)
      ↓
  CABLE Output (Recording Device) ← Internal loopback
      ↓
  Valorant Mic Input
      ↓
  Teammates Hear Voice
```

---

## 🐛 Troubleshooting Guide

### Problem: No sound at all

**Diagnosis Steps:**
1. Check Windows Volume Mixer → Java volume at 100%?
2. Check Status Bar → Audio Routing is green?
3. Open Sound Settings → App volume → Is java.exe set to CABLE Input?

**Solutions:**
- Manual routing: Set java.exe output to CABLE Input in Windows
- Verify VB-CABLE installed: Check Sound Control Panel for devices
- Restart ValVoice after configuring audio

### Problem: Sound plays on speakers, not through Valorant mic

**Cause:** Audio not routed to VB-CABLE

**Solutions:**
1. Check Valorant mic input is set to **CABLE Output** (not CABLE Input!)
2. Manually set java.exe output to **CABLE Input** in Windows Sound Settings
3. Verify in Valorant: Settings → Audio → Voice Chat → Input Device

### Problem: XMPP shows "Stub (demo only)"

**Cause:** valvoice-xmpp.exe not built or not found

**Solutions:**
1. Build manually:
   ```cmd
   cd xmpp-bridge
   npm install
   npm run build:exe
   cd ..
   ```
2. Verify `valvoice-xmpp.exe` exists in project root
3. Ensure Node.js is installed for auto-build

### Problem: No messages from Valorant chat

**Diagnosis:**
1. Check Status Bar → Self ID showing? (means connection works)
2. Check Sources → Is "PARTY+TEAM" selected?
3. Check toggles → Team Chat and Private Messages enabled?
4. Try typing in chat yourself → Does your own message narrate?

**Solutions:**
- Ensure Valorant is running and you're in a match
- Check Sources includes the right channels
- Enable "SELF" in sources to hear your own messages (for testing)
- Check logs for "Received message:" entries

### Problem: Audio Routing shows "Manual setup needed"

**Cause:** Automatic routing failed

**Manual Fix:**
1. Open Windows Settings → System → Sound
2. Scroll to **Advanced sound options** → **App volume and device preferences**
3. Find **java.exe** or **powershell.exe**
4. Set **Output** to **CABLE Input (VB-Audio Virtual Cable)**
5. Restart ValVoice

### Problem: Teammates can't hear narration

**Diagnosis:**
1. Can YOU hear the narration? (Set Windows output to CABLE Input to test)
2. Is Valorant mic input set to CABLE Output?
3. Is your Valorant voice chat enabled and not muted?

**Solutions:**
- Verify Valorant voice chat is enabled (press V in-game)
- Check voice chat volume is not 0
- Test Valorant mic with normal speech first
- Ensure you're not muted in Valorant (team mute or party mute)

---

## ✅ Quick Test Checklist

Use this for rapid verification:

### Pre-Flight:
- [ ] VB-CABLE installed + rebooted
- [ ] Project built (`mvn clean package`)
- [ ] Valorant running (for real test) OR Demo mode enabled

### ValVoice Launch:
- [ ] Status: XMPP = Green
- [ ] Status: VB-Cable = Green  
- [ ] Status: Audio Routing = Green
- [ ] Status: Self ID appears (if Valorant running)

### Configuration:
- [ ] Voice selected in Settings
- [ ] Sources = "PARTY+TEAM" or "SELF+PARTY+TEAM"
- [ ] Team Chat toggle = ON
- [ ] Private Messages toggle = ON

### Valorant Settings:
- [ ] Voice Chat = Enabled
- [ ] Input Device = **CABLE Output** (not CABLE Input!)
- [ ] Output Device = Your headset

### Test:
- [ ] Type in Valorant team chat
- [ ] Hear TTS narration
- [ ] Messages Narrated counter increases
- [ ] (Optional) Teammate confirms they hear it

---

## 🎯 Success Criteria

**You'll know it's working when:**
1. ✅ ValVoice status bar is all green
2. ✅ You type in Valorant chat and hear it spoken
3. ✅ Teammates type and you hear their messages
4. ✅ Messages Narrated counter increases in ValVoice
5. ✅ (Optional) Your teammates report hearing the TTS in voice chat

---

## 📝 Testing Notes

- **First launch** may take 30-60 seconds as it auto-builds the XMPP bridge
- **Self ID detection** may take 10-30 seconds after Valorant starts
- **Audio routing** happens automatically on startup (check status bar)
- **Custom matches** work great for testing without affecting rank
- **Spike Rush** is fastest for quick testing

---

## 🔧 Advanced: Enable Debug Logging

If you need detailed logs for troubleshooting:

1. Create/edit `src/main/resources/logback.xml`:
```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="DEBUG">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```

2. Rebuild and run
3. Check console output for detailed message flow

---

**Need help?** Check the logs and status bar indicators first!

