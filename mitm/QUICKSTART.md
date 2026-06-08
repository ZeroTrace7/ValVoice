# 🚀 MITM Proxy — Quick Start Guide

## ✅ Overview

The `mitm/` directory contains the XMPP Man-in-the-Middle proxy that intercepts Valorant's chat traffic. This proxy is the first stage of the ValVoice pipeline.

## 📂 Directory Structure

```
ValVoice/
├── mitm/                          ← MITM Proxy subsystem
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

4. **Package as Windows executable:**
   ```bash
   npm run build:exe
   ```
   This creates `valvoice-mitm.exe`

## 🔄 How It Works

The MITM proxy outputs JSON to stdout, which `ValVoiceBackend.java` consumes via `BufferedReader`:

```json
{"type":"incoming","time":1234567890,"data":"<message>...</message>"}
{"type":"outgoing","time":1234567890,"data":"<iq>...</iq>"}
```

The Java backend extracts the raw XML from the `"data"` field and passes it to `XmppStreamParser.java` for StAX parsing.

## ✅ Verification

1. **Make sure Riot/Valorant is CLOSED**
2. **Run ValVoice** (which starts the MITM automatically)
3. **Watch for these logs:**
   ```
   Starting MITM proxy...
   MITM proxy started
   Riot client connected to MITM
   MITM connected to Riot server
   ```
4. **Valorant launches automatically**
5. **Send a chat message in-game**
6. **TTS reads it aloud** ✅

## 🐛 Troubleshooting

### "Riot client is running" error
- The MITM **must** launch Riot itself
- ValVoice kills Riot before starting the MITM
- Ensure Riot Client is closed before starting ValVoice

### No XMPP traffic
- Run `npm run build:exe` in `mitm/` folder
- Verify certificates exist in `mitm/certs/`

### Certificate errors
- Verify `certs/server.key` and `certs/server.cert` exist
- `chat.allow_bad_cert.enabled` is set to `true` in ConfigMITM

### Port conflicts
- Ports 35478 and 35479 must be free
- Close any other proxies/servers using these ports
