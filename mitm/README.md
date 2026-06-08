# ValVoice XMPP MITM Proxy

The MITM (Man-in-the-Middle) proxy layer for ValVoice. Compiled as `valvoice-mitm.exe`, it intercepts Valorant XMPP chat traffic by:

1. Launching the Riot Client with a custom config URL
2. Intercepting the config request and redirecting chat servers to localhost
3. Acting as a TLS proxy between Riot Client and Riot XMPP servers
4. Outputting decrypted XMPP traffic as JSON to stdout

## Architecture

```
Riot Client
   ↓ (requests config from localhost:35479)
ConfigMITM (HTTP Server)
   ↓ (redirects chat to localhost:35478)
XmppMITM (TLS Proxy on port 35478)
   ↓ (forwards to real Riot XMPP server)
Real Riot XMPP Server
```

## Build Instructions

### Prerequisites
- Node.js 18+
- npm

### Build Steps

1. **Install dependencies:**
   ```bash
   npm install
   ```

2. **Compile TypeScript:**
   ```bash
   npm run build
   ```
   This creates compiled JavaScript in `dist/` folder.

3. **Package as executable:**
   ```bash
   npm run build:exe
   ```
   This creates `valvoice-mitm.exe`.

4. **Build everything:**
   ```bash
   npm run build:all
   ```
   Compiles TypeScript AND packages the executable.

## File Structure

```
mitm/
├── src/
│   ├── main.ts              - Entry point, starts servers and launches Riot
│   ├── ConfigMITM.ts        - HTTP server that intercepts config requests
│   ├── XmppMITM.ts          - TLS proxy for XMPP traffic
│   ├── riotClientUtils.ts   - Utilities for finding Riot client
│   └── undici.d.ts          - Type definitions for fetch API
├── certs/
│   ├── server.key           - Self-signed certificate key
│   └── server.cert          - Self-signed certificate
├── dist/                    - Compiled JavaScript (generated)
├── package.json
└── tsconfig.json
```

## Integration with Java Backend

`ValVoiceBackend.java` manages the MITM proxy as a child process:

1. Launches `valvoice-mitm.exe` via `ProcessBuilder`
2. Reads JSON lines from stdout via `BufferedReader`
3. Extracts raw XML from the `"data"` field
4. Passes XML to `XmppStreamParser.java` for StAX parsing

## Output Format

The MITM proxy outputs JSON to stdout:

```json
{"type":"incoming","time":1234567890,"data":"<message>...</message>"}
{"type":"outgoing","time":1234567890,"data":"<iq>...</iq>"}
{"type":"open-valorant","time":1234567890,"host":"jp1.chat.si.riotgames.com","port":5223,"socketID":1}
{"type":"open-riot","time":1234567890,"socketID":1}
{"type":"close-valorant","time":1234567890,"socketID":1}
{"type":"close-riot","time":1234567890,"socketID":1}
{"type":"error","code":409,"reason":"Riot client is running..."}
```

Key types:
- `incoming` — Traffic from Riot server → Riot Client (XMPP stanzas)
- `outgoing` — Traffic from Riot Client → Riot server (XMPP stanzas)
- `error` — Errors (e.g., Riot already running, Valorant not installed)

## Configuration

### Ports (hardcoded in main.ts)
- **HTTP Config Server:** `35479`
- **XMPP TLS Proxy:** `35478`

### Config Rewrites (ConfigMITM.ts)

When Riot requests its config, the proxy rewrites chat server details:

| Field | Original | Rewritten |
|-------|----------|-----------|
| `chat.host` | `jp1.chat.si.riotgames.com` | `127.0.0.1` |
| `chat.port` | `5223` | `35478` |
| `chat.allow_bad_cert.enabled` | `false` | `true` |

This forces the Riot Client to connect to the local TLS proxy instead of the real server.

## Troubleshooting

### Riot client doesn't connect
- Check if `chat.allow_bad_cert.enabled` is set to `true`
- Verify certificates exist in `certs/` folder
- Check if ports 35478 and 35479 are available

### No XMPP traffic logged
- Verify Riot Client was launched by ValVoice (not manually)
- Check if config was properly intercepted
- Look for error messages in stdout

### "Riot client is running" error
- ValVoice must start **before** Riot Client
- Close Riot before starting ValVoice
