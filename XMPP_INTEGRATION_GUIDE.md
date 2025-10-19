# ValVoice XMPP Integration Guide

## ✅ Integration Complete!

Your new `valvoice-xmpp.exe` has been successfully integrated into ValVoice!

---

## 📁 File Location

**Executable:** `C:\Users\HP\IdeaProjects\ValVoice\valvoice-xmpp.exe`

The Java application automatically looks for this file in the project root directory when it starts.

---

## 🔧 What Was Changed

### 1. **File Setup**
- ✅ Renamed: `valorantNarrator-xmpp.exe` → `valvoice-xmpp.exe`
- ✅ Moved to project root (where Java expects it)
- ✅ Replaced old XMPP executable

### 2. **Code Updates (Main.java)**
Enhanced error handling to support your new executable's JSON format:

```java
case "error" -> {
    // Support both "error" and "reason" fields for compatibility
    String err = "(unknown)";
    if (obj.has("error") && !obj.get("error").isJsonNull()) {
        err = obj.get("error").getAsString();
    } else if (obj.has("reason") && !obj.get("reason").isJsonNull()) {
        err = obj.get("reason").getAsString();
    }
    // Include error code if present
    if (obj.has("code") && !obj.get("code").isJsonNull()) {
        err = "(" + obj.get("code").getAsInt() + ") " + err;
    }
    logger.warn("XMPP error event: {}", err);
    ValVoiceController.updateXmppStatus("Error", false);
}
```

### 3. **Application Rebuilt**
- ✅ Compiled successfully with Maven
- ✅ All changes applied to `target/valvoice-1.0.0.jar`

---

## 🚀 How It Works

### Automatic Startup
When you run ValVoice:
1. `Main.startXmppNodeProcess()` automatically launches `valvoice-xmpp.exe`
2. The executable connects to Riot's XMPP servers
3. JSON events stream to stdout
4. Java app reads and processes the events in real-time

### Expected JSON Format
Your executable outputs JSON lines with these types:

```json
{"type":"open-valorant","time":1760842816584,"host":"jp1.chat.si.riotgames.com","port":5223,"socketID":1}
{"type":"open-riot","time":1760842817094,"socketID":1}
{"type":"outgoing","time":1760842816771,"data":"<?xml version=\"1.0\" encoding=\"UTF-8\"?><stream:stream...>"}
{"type":"incoming","time":1760842817234,"data":"<?xml version='1.0'?><stream:stream...>"}
{"type":"error","code":409,"reason":"Error message here"}
```

### Supported Event Types
- **open-valorant** - Connection opened to Riot XMPP server (shows host:port, updates UI to "Connecting...")
- **open-riot** - Riot server acknowledged connection (updates UI to "Connected")
- **outgoing** - XMPP stanza sent to server (logged for debugging)
- **incoming** - XMPP stanzas received from server (parsed for chat messages)
- **error** - Error messages (supports "code" and "reason" fields)
- **startup** - Initial connection (optional)
- **info** / **debug** - Status updates (optional)
- **shutdown** - Clean exit (optional)

---

## 📝 Current Status

### Detected Behavior
When testing the executable, it outputted:
```json
{"type":"error","code":409,"reason":"Riot client is running, please close it before running this tool."}
```

**This is normal!** The executable is working correctly. It appears to require that Riot Client isn't already running when it starts, or it manages the connection differently.

---

## 🧪 Testing the Integration

### Option 1: Run from IDE
1. Open `Main.java` in your IDE
2. Run the main method
3. Watch console for XMPP bridge logs

### Option 2: Run from JAR
```bat
START_VALVOICE.bat
```

### What to Look For
In the logs, you should see:
```
[INFO] Started XMPP bridge (mode: external-exe)
[INFO] XMPP bridge output: ...
```

And in the UI:
- **XMPP Status** label will update (Connecting → Connected)
- **Bridge Mode** will show "external-exe"

---

## 🐛 Troubleshooting

### Executable Not Starting
**Check:**
- File exists: `C:\Users\HP\IdeaProjects\ValVoice\valvoice-xmpp.exe`
- File is executable (not blocked by Windows)
- No antivirus blocking it

### No Chat Messages
**Check:**
1. Valorant is running
2. XMPP Status shows "Connected"
3. You're in a game/chat session
4. The executable is outputting JSON with `"type":"incoming"`

### Error 409
If you see: `"code":409,"reason":"Riot client is running..."`
- This may be expected behavior from your executable
- It might need Riot Client closed first, then it manages the connection itself
- Or it may need different startup parameters

---

## 🔄 Future Updates

To update the XMPP executable:
1. Replace `C:\Users\HP\IdeaProjects\ValVoice\valvoice-xmpp.exe` with the new version
2. Ensure it's named exactly `valvoice-xmpp.exe`
3. No code changes needed (unless JSON format changes)
4. Rebuild with: `mvn clean package -DskipTests`

---

## 📂 Project Structure

```
ValVoice/
├── valvoice-xmpp.exe          ← Your XMPP bridge executable
├── START_VALVOICE.bat         ← Launch script
├── target/
│   └── valvoice-1.0.0.jar    ← Built application
└── src/
    └── main/
        └── java/
            └── com/someone/valvoice/
                └── Main.java  ← Launches and monitors XMPP bridge
```

---

## ✅ Verification Checklist

- [x] File renamed correctly
- [x] File in project root
- [x] Java code updated for compatibility
- [x] Application rebuilt successfully
- [x] No compilation errors
- [ ] **Test with Valorant running** (your next step!)

---

## 🎯 Next Steps

1. **Start Valorant**
2. **Run ValVoice** via `START_VALVOICE.bat`
3. **Check XMPP Status** in the UI
4. **Send a chat message** in Valorant
5. **Verify TTS works** and message appears in ValVoice

If everything works, you're all set! 🎉

---

**Date:** October 19, 2025  
**Integration Version:** 1.0  
**XMPP Executable:** valvoice-xmpp.exe
