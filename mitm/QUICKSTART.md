# 🚀 QUICK START GUIDE - Integrating MITM into ValVoice

## ✅ What You Have Now

This `valvoice-mitm/` folder contains everything you need to replace the old Direct XMPP approach with MITM.

## 📂 Where to Put This Folder

**Copy this entire `valvoice-mitm/` folder into your ValVoice project:**

```
ValVoice/                          (your existing project)
├── src/main/java/...              (existing Java code)
├── pom.xml                        (existing)
├── target/                        (existing)
├── mitm/                          ← PASTE THE valvoice-mitm FOLDER HERE (rename to just "mitm")
│   ├── src/
│   │   ├── main.ts
│   │   ├── ConfigMITM.ts
│   │   ├── XmppMITM.ts
│   │   ├── riotClientUtils.ts
│   │   └── undici.d.ts
│   ├── certs/
│   │   ├── server.key
│   │   └── server.cert
│   ├── package.json
│   ├── tsconfig.json
│   └── README.md
```

## 🔧 Build the MITM Executable

1. **Open terminal in the `mitm/` folder**

2. **Install Node.js dependencies:**
   ```bash
   npm install
   ```

3. **Build TypeScript to JavaScript:**
   ```bash
   npm run build
   ```
   This creates `mitm/dist/main.js`

4. **Package as Windows executable (optional but recommended):**
   ```bash
   npm run package
   ```
   This creates `ValVoice/target/valvoice-xmpp.exe`

## ✂️ Delete Old Code from Java

### Files to DELETE (if they exist):
- ❌ `valvoice-xmpp.exe` (old direct client)
- ❌ `xmpp-bridge/` folder (old bridge source)

### Code to REMOVE from `Main.java`:

**REMOVE THIS:**
```java
// OLD: Direct XMPP client launch
logger.info("Starting XMPP bridge (direct client approach)...");
ProcessBuilder pb = new ProcessBuilder("valvoice-xmpp.exe");
Process xmppProcess = pb.start();
```

**REPLACE WITH THIS:**
```java
// NEW: MITM proxy launch
logger.info("Killing Riot if running...");
Runtime.getRuntime().exec("taskkill /F /IM RiotClientServices.exe");
Thread.sleep(2000);

logger.info("Starting MITM proxy...");
ProcessBuilder pb = new ProcessBuilder("target/valvoice-xmpp.exe");
// OR for development: new ProcessBuilder("node", "mitm/dist/main.js");
pb.redirectErrorStream(true);
Process mitmProcess = pb.start();
```

### KEEP THIS (STDOUT reading is the same):
```java
// This stays exactly the same
BufferedReader reader = new BufferedReader(
    new InputStreamReader(mitmProcess.getInputStream())
);

String line;
while ((line = reader.readLine()) != null) {
    handleIncomingStanza(line); // Your existing parser
}
```

## 🔄 Update Your STDOUT Parser

The MITM outputs **JSON** instead of raw XML. Update your parser:

**BEFORE (Direct Client - Raw XML):**
```java
String xml = reader.readLine(); // "<message>...</message>"
Message msg = Message.fromXml(xml);
```

**AFTER (MITM - JSON with XML inside):**
```java
String jsonLine = reader.readLine();
// {"type":"incoming","time":123,"data":"<message>...</message>"}

if (jsonLine.contains("\"type\":\"incoming\"")) {
    String xml = extractXmlFromJson(jsonLine);
    Message msg = Message.fromXml(xml);
}
```

**Helper method:**
```java
private static String extractXmlFromJson(String jsonLine) {
    int dataStart = jsonLine.indexOf("\"data\":\"") + 8;
    int dataEnd = jsonLine.lastIndexOf("\"");
    
    if (dataStart > 8 && dataEnd > dataStart) {
        String data = jsonLine.substring(dataStart, dataEnd);
        return data.replace("\\\"", "\"")
                  .replace("\\\\", "\\")
                  .replace("\\n", "\n");
    }
    return null;
}
```

## ✅ Test It Works

1. **Make sure Riot/Valorant is CLOSED**
2. **Run your Java app**
3. **Watch for these logs:**
   ```
   Killing Riot if running...
   Starting MITM proxy...
   MITM proxy started
   Riot client connected to MITM
   MITM connected to Riot server
   ```

4. **Launch Valorant** (MITM will do this automatically)
5. **Send a chat message in-game**
6. **Check if TTS reads it aloud** ✅

## 🐛 Troubleshooting

### "Riot client is running" error
- The MITM **must** launch Riot itself
- Make sure Java kills Riot before starting MITM
- Check: `taskkill /F /IM RiotClientServices.exe`

### No XMPP traffic
- Check if `valvoice-xmpp.exe` exists in `target/`
- Run `npm run package` in `mitm/` folder
- Verify certificates exist in `mitm/certs/`

### Certificate errors
- Make sure `chat.allow_bad_cert.enabled = true` in ConfigMITM
- Verify `certs/server.key` and `certs/server.cert` exist

### Port conflicts
- Ports 35478 and 35479 must be free
- Close any other proxies/servers using these ports

## 📞 Final Checklist

- [ ] `mitm/` folder copied into ValVoice project
- [ ] `npm install` completed in `mitm/`
- [ ] `npm run build` or `npm run package` completed
- [ ] Old `valvoice-xmpp.exe` (direct client) deleted
- [ ] `Main.java` updated to kill Riot and launch MITM
- [ ] STDOUT parser updated to handle JSON format
- [ ] Tested with Valorant closed first
- [ ] TTS works when chat messages arrive ✅

---

**You're now running ValVoice with MITM architecture - the same proven approach as ValorantNarrator!** 🎉
