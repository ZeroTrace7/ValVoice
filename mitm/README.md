# ValVoice XMPP MITM Proxy

This is the MITM (Man-in-the-Middle) proxy layer for ValVoice. It intercepts Valorant XMPP chat traffic by:
1. Launching the Riot client with a custom config URL
2. Intercepting the config request and redirecting chat servers to localhost
3. Acting as a TLS proxy between Riot client and Riot servers
4. Logging all decrypted XMPP traffic to STDOUT

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
- Node.js 18+ installed
- npm installed

### Build Steps

1. **Install dependencies:**
   ```bash
   npm install
   ```

2. **Compile TypeScript:**
   ```bash
   npm run build
   ```
   This creates compiled JavaScript in `dist/` folder

3. **Package as executable (Optional):**
   ```bash
   npm run package
   ```
   This creates `valvoice-xmpp.exe` in `../target/` folder using `pkg`

4. **Build everything:**
   ```bash
   npm run build:all
   ```
   Compiles TypeScript AND packages the executable

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

## Integration with Java

The Java `Main.java` should:

1. **Kill Riot if running:**
   ```java
   Runtime.getRuntime().exec("taskkill /F /IM RiotClientServices.exe");
   Thread.sleep(2000);
   ```

2. **Launch the MITM proxy:**
   ```java
   ProcessBuilder pb = new ProcessBuilder("mitm/target/valvoice-xmpp.exe");
   // OR if using Node directly:
   // ProcessBuilder pb = new ProcessBuilder("node", "mitm/dist/main.js");
   Process mitmProcess = pb.start();
   ```

3. **Read STDOUT for XMPP traffic:**
   ```java
   BufferedReader reader = new BufferedReader(
       new InputStreamReader(mitmProcess.getInputStream())
   );
   
   String line;
   while ((line = reader.readLine()) != null) {
       // Parse JSON log entries
       // Extract XML from "incoming" and "outgoing" types
   }
   ```

## Output Format

The MITM proxy outputs JSON to STDOUT:

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
- `incoming` - Traffic from Riot server → Riot client (XMPP stanzas)
- `outgoing` - Traffic from Riot client → Riot server (XMPP stanzas)
- `error` - Errors (e.g., Riot already running, Valorant not installed)

## Critical Configuration

### Ports (hardcoded in main.ts)
- **HTTP Config Server:** `35479`
- **XMPP TLS Proxy:** `35478`

### Config Rewrites (ConfigMITM.ts)
When Riot requests config, we change:
```json
{
  "chat.host": "jp1.chat.si.riotgames.com",  // ← Real Riot server
  "chat.port": 5223,
  "chat.allow_bad_cert.enabled": false
}
```
To:
```json
{
  "chat.host": "127.0.0.1",                   // ← Localhost
  "chat.port": 35478,                          // ← Our MITM port
  "chat.allow_bad_cert.enabled": true          // ← CRITICAL for self-signed cert
}
```

## Troubleshooting

### Riot client doesn't connect
- Check if `chat.allow_bad_cert.enabled` is set to `true`
- Verify certificates exist in `certs/` folder
- Check if ports 35478 and 35479 are available

### No XMPP traffic logged
- Verify Riot client was launched by the MITM (not manually)
- Check if config was properly intercepted
- Look for error messages in STDOUT

### "Riot client is running" error
- The MITM must launch Riot itself
- Close Riot before starting the MITM
- Java should kill Riot before launching MITM
