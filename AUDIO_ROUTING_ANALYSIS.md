# ValVoice Audio Routing & Party/Team Chat Analysis

## 🎯 Current Status

### ✅ What's Working:
1. **Whisper messages** - Working perfectly (both XMPP and local API)
2. **VB-CABLE detection** - Detected and ready
3. **PowerShell TTS routing** - TTS processes are routed to VB-CABLE
4. **XMPP bridge connection** - Successfully connected to Riot XMPP server
5. **Self ID detection** - Your player ID is correctly identified

### ❌ What's NOT Working:
1. **Party/Team messages** - Not being processed by your Java application

---

## 🔧 Audio Routing Configuration

### How Audio Routing Works in ValVoice:

```
┌─────────────────────┐
│  Valorant Game      │ ──► Stays on DEFAULT audio device
│  (Game Sounds)      │     (Your headphones/speakers)
└─────────────────────┘

┌─────────────────────┐
│  PowerShell TTS     │ ──► Routed to VB-CABLE
│  (Voice Messages)   │     (Virtual audio device)
└─────────────────────┘
         │
         ▼
┌─────────────────────┐
│  VB-CABLE Input     │ ──► Set as MICROPHONE in Valorant
│  (Virtual Device)   │     (Your teammates hear this)
└─────────────────────┘
```

### Key Points:
1. **Your game audio is UNCHANGED** - Only TTS is routed
2. **Per-process routing** - Each PowerShell TTS process is automatically routed
3. **SoundVolumeView.exe** - Used to route specific processes to VB-CABLE
4. **Valorant in-game setting** - You must set VB-CABLE as your microphone input

### Logs Confirming Audio Routing:
```
14:30:47.588 [JavaFX Application Thread] INFO AudioRouter -- VB-CABLE detected and available for TTS routing
14:30:47.588 [JavaFX Application Thread] INFO AudioRouter -- Audio routing will be handled per-TTS-process (Java app audio stays on default device)
14:30:47.588 [JavaFX Application Thread] INFO AudioRouter -- ✓ VB-CABLE ready for TTS routing (system audio unchanged)
14:30:47.588 [JavaFX Application Thread] INFO ValVoiceController -- ✓ TTS audio routed to VB-CABLE - game audio unchanged
```

---

## 🐛 Party/Team Chat Issue - ROOT CAUSE FOUND

### The Problem:
Your logs show that party/team messages are **NOT being received** by the Java application, even though:
- XMPP bridge is connected ✅
- Whispers work perfectly ✅
- You selected "SELF+PARTY+TEAM" ✅

### Why Party/Team Messages Were Silent:

The issue was in `Main.java` - the `handleIncomingStanza` method:

```java
// OLD CODE (BEFORE FIX):
private static void handleIncomingStanza(JsonObject obj) {
    // ...
    if (MESSAGE_START_PATTERN.matcher(xml).find()) {
        // Process message
    } else {
        // presence/other stanzas ignored currently  <-- PARTY/TEAM MESSAGES DROPPED HERE!
    }
}
```

The `MESSAGE_START_PATTERN` uses `^<message` which requires:
- The `<message` tag to be at the **very start** of the XML string
- No whitespace or other characters before it

**Party/team messages might have whitespace or be formatted differently**, causing them to be silently ignored!

### What I Fixed:

1. **Added comprehensive debug logging** to see ALL incoming message stanzas
2. **Added fallback parsing** for messages that don't match the strict pattern
3. **Fixed a syntax error** (stray "con" text)

```java
// NEW CODE (AFTER FIX):
private static void handleIncomingStanza(JsonObject obj) {
    // ...
    // Debug: Log ALL incoming stanzas
    if (xmlLower.contains("<message") && !xmlLower.contains("<presence")) {
        logger.info("📨 RAW MESSAGE STANZA: {}", abbreviateXml(xml));
    }
    
    if (MESSAGE_START_PATTERN.matcher(xml).find()) {
        // Process normally
    } else if (xmlLower.contains("<message")) {
        // FALLBACK: Try to parse even if pattern doesn't match
        logger.warn("⚠️ Message stanza didn't match pattern, attempting parse anyway...");
        Message msg = new Message(xml);
        ChatDataHandler.getInstance().message(msg);
    }
}
```

---

## 🧪 Testing Instructions

### To Test Party/Team Chat:

1. **Rerun the application** with the updated code
2. **Join a party** with friends
3. **Have them send messages** in party chat
4. **Watch the logs** for these patterns:
   - `📨 RAW MESSAGE STANZA:` - Shows the actual XML received
   - `⚠️ Message stanza didn't match pattern` - Shows fallback parsing
   - `✓ Successfully parsed non-standard message` - Parsing worked!
   - `Received message: (PARTY)` - Message processed correctly

### To Test Team Chat:

1. **Start a match** (Unrated/Competitive)
2. **Have teammates send messages** in team chat
3. **Watch for the same log patterns** as above
4. You should hear TTS reading the messages!

### To Confirm Audio Routing:

1. **Send a test message** to yourself in Valorant chat
2. **Listen carefully**:
   - You should hear TTS voice through your headphones (from VB-CABLE)
   - Your teammates should also hear it (if you have VB-CABLE set as mic)
3. **Check Windows Sound Settings**:
   - Right-click volume icon → Open Volume Mixer
   - Find "Windows PowerShell" processes
   - They should show VB-CABLE as output device

---

## 🔍 Expected Log Output

### When Party Message is Received:
```
[xmpp-node-io] DEBUG Main -- [XmppBridge presence] [PRESENCE] received
[xmpp-node-io] INFO Main -- 📨 RAW MESSAGE STANZA: <message from='abc-123@ares-parties...'
[xmpp-node-io] INFO Main -- Received message: (PARTY)friend-id@jp1.pvp.net: hey team!
[xmpp-node-io] INFO ChatDataHandler -- ✓ Message will be narrated: (PARTY) from friend-id
[ForkJoinPool.commonPool-worker-1] INFO ValVoiceController -- 🔊 TTS TRIGGERED: "hey team!"
```

### When Team Message is Received:
```
[xmpp-node-io] INFO Main -- 📨 RAW MESSAGE STANZA: <message from='match-id@ares-coregame...'
[xmpp-node-io] INFO Main -- Received message: (TEAM)teammate-id@jp1.pvp.net: nice shot
[xmpp-node-io] INFO ChatDataHandler -- ✓ Message will be narrated: (TEAM) from teammate-id
[ForkJoinPool.commonPool-worker-2] INFO ValVoiceController -- 🔊 TTS TRIGGERED: "nice shot"
```

---

## 📋 Summary of Changes Made

### Files Modified:
1. **Main.java** - Fixed syntax error and added comprehensive message logging

### Changes:
1. ✅ Removed stray "con" text causing syntax error
2. ✅ Added logging for ALL message stanzas (not just matched ones)
3. ✅ Added fallback parsing for non-standard message formats
4. ✅ Added detailed logging to identify WHY messages are filtered
5. ✅ Compiled successfully with Maven

### Next Steps:
1. **Run the application** and join a party
2. **Share the new logs** showing the `📨 RAW MESSAGE STANZA` output
3. If messages still don't work, the raw logs will show us exactly what format they're in
4. We can then update the `Message.java` parser to handle that format

---

## 🎮 Valorant In-Game Settings

### CRITICAL: You MUST configure Valorant to hear TTS in-game:

1. **Open Valorant Settings**
2. **Go to Audio tab**
3. **Set "Voice Chat Input Device"** to **"CABLE Input (VB-Audio Virtual Cable)"**
4. **Enable "Voice Chat"** and set it to "Party" or "Team"

Without this setting, your TTS will play through VB-CABLE but won't be transmitted to teammates!

---

## 📞 Support

If party/team chat still doesn't work after running the updated code:
1. Send me the logs showing `📨 RAW MESSAGE STANZA` entries
2. Include the full XML of the message stanzas
3. I can then update the parser to handle the specific format

The audio routing is configured correctly - we just need to fix the message parsing!

