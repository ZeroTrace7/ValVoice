# ValVoice XMPP Bridge

A Node.js-based XMPP client that connects to Riot Games' XMPP servers for Valorant chat integration.

## 🎯 Purpose

This bridge connects to Riot's XMPP chat infrastructure and streams chat messages to the ValVoice Java application via JSON stdout.

## ✅ Features

- ✅ Authenticates with Riot Games XMPP servers
- ✅ Reads Riot Client lockfile for credentials
- ✅ Fetches PAS tokens and entitlements automatically
- ✅ Auto-joins MUC rooms (party, pregame, in-game chat)
- ✅ Streams real-time chat messages as JSON
- ✅ Automatic reconnection with exponential backoff
- ✅ Robust error handling and retry logic
- ✅ TLS connection with certificate validation

## 📦 Installation

```bash
npm install
```

## 🔨 Build Executable

```bash
# Clean cache and build Windows executable
npm run build:exe

# Output: ../valvoice-xmpp.exe (37.6 MB)
```

## 🚀 Usage

### Development Mode
```bash
npm start
```

### Production Mode
The Java application automatically spawns `valvoice-xmpp.exe` as a subprocess.

## 📤 Output Format

The bridge outputs JSON lines to stdout:

### Startup Event
```json
{"type":"startup","pid":12345,"ts":1729591200000,"version":"2.3.0-muc-fixed"}
```

### Connection Events
```json
{"type":"info","message":"Connected to Riot XMPP server","ts":1729591200000}
{"type":"debug","message":"Joining MUC room: room@ares-parties.na1.pvp.net","ts":1729591200000}
```

### Incoming Messages
```json
{
  "type":"incoming",
  "time":1729591200000,
  "data":"<message from='player@ares-coregame.na1.pvp.net' type='groupchat'><body>Hello team!</body></message>"
}
```

### Error Events
```json
{"type":"error","error":"Connection failed","code":"ECONNREFUSED","ts":1729591200000}
```

### Heartbeat (every 2.5 minutes)
```json
{"type":"heartbeat","ts":1729591200000}
```

## 🔧 Technical Details

### Authentication Flow
1. Read Riot Client lockfile (`%LOCALAPPDATA%\Riot Games\Riot Client\Config\lockfile`)
2. Fetch entitlements token from local Riot Client API
3. Poll `/chat/v1/session` until chat is loaded
4. Fetch PAS token from `riot-geo.pas.si.riotgames.com`
5. Get Riot config for XMPP server affinity
6. Connect to XMPP server via TLS (port 5223)
7. Authenticate using X-Riot-RSO-PAS mechanism
8. Send presence and join MUC rooms

### XMPP Server Regions
- **NA:** `na.chat.si.riotgames.com`
- **EU:** `eu.chat.si.riotgames.com`
- **AP:** `ap.chat.si.riotgames.com`
- **KR:** `kr.chat.si.riotgames.com`
- **BR:** `br.chat.si.riotgames.com`
- **LATAM:** `latam.chat.si.riotgames.com`

### MUC Room Types
- `@ares-parties.*` - Party chat
- `@ares-pregame.*` - Agent select chat
- `@ares-coregame.*` - In-game team chat

## 🐛 Debugging

Set environment variable for detailed logs:
```bash
set DEBUG=*
npm start
```

Check stderr for connection status:
```
✅ Connected to Riot XMPP server
✅ Joined MUC room: room-id@ares-coregame.na1.pvp.net
📨 RAW MESSAGE STANZA: <message...>
```

## 📋 Requirements

- **Node.js:** 18+
- **OS:** Windows 10/11
- **Riot Client:** Must be running and authenticated
- **Valorant:** Optional (chat session loads when Valorant launches)

## 🔒 Security

- ✅ Reads credentials from local Riot Client only
- ✅ TLS encrypted XMPP connection
- ✅ No credential storage
- ✅ Local-only communication

## 📊 Dependencies

- `tls` (built-in) - TLS socket connection
- `https` (built-in) - HTTP requests to Riot APIs
- `fs` (built-in) - Lockfile reading

**Dev Dependencies:**
- `pkg@5.8.1` - Executable bundler

## 🛠️ Troubleshooting

### "Lockfile not found"
- **Cause:** Riot Client not running
- **Solution:** Launch Riot Client or Valorant

### "Chat session did not become ready"
- **Cause:** Valorant not launched
- **Solution:** Open Valorant to initialize chat session

### "PAS token fetch error"
- **Cause:** Rate limiting or network issues
- **Solution:** Automatic retry with exponential backoff (5 attempts)

### "XMPP socket error"
- **Cause:** Connection interrupted
- **Solution:** Automatic reconnection every 5-10 seconds

## 📝 Version History

### v2.3.0-muc-fixed (Current)
- ✅ Auto-join MUC rooms for team/party chat
- ✅ Enhanced retry logic with exponential backoff
- ✅ Improved error handling
- ✅ Better connection stability
- ✅ Detailed debug logging

## 📄 License

UNLICENSED - Private project

---

**Status:** ✅ Production Ready  
**Last Updated:** October 22, 2025

