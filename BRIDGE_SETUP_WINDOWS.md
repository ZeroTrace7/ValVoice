# Build and install valvoice-xmpp.exe on Windows

This guide fixes the "Node/npm not on PATH → bridge cannot be built" issue and gets ValVoice to use the external bridge executable.

What the app expects (verified from source):
- The bridge executable must be named exactly `valvoice-xmpp.exe` and live in the ValVoice working directory (same folder as `run_valvoice.bat` and the built JAR). See `src/main/java/com/someone/valvoice/Main.java`.
- If the exe is missing but Node/npm are on PATH, the app will auto-build it on first run (same result, slower). Otherwise it falls back to an embedded stub script and you won’t get real chat.
- The included `xmpp-bridge/index.js` is currently a stub. Packaging it produces a stub exe. For real Valorant chat, you must replace it with a real Riot XMPP client.

## 1) Install Node.js (adds node and npm to PATH)
1. Download Node.js 18 LTS for Windows: https://nodejs.org/
2. Run the installer and keep “Add to PATH” enabled.
3. Open a new Command Prompt and verify:

```bat
node -v
npm -v
```
Both commands should print versions (e.g., v18.x.x and 9.x.x). If not, reboot or re-run the installer.

## 2) Build the bridge executable
From a Command Prompt:

```bat
cd C:\Users\HP\IdeaProjects\ValVoice\xmpp-bridge
npm install
npm run build:exe
```

- This uses `pkg` (configured in `xmpp-bridge/package.json`) to produce `..\valvoice-xmpp.exe`.
- After it finishes, confirm the file exists:

```bat
dir C:\Users\HP\IdeaProjects\ValVoice\valvoice-xmpp.exe
```

## 3) Quick sanity check (optional)
Run the exe directly to see stub JSON output:

```bat
C:\Users\HP\IdeaProjects\ValVoice\valvoice-xmpp.exe
```
Press Ctrl+C to stop.

## 4) Run ValVoice and verify status
From the project root:

```bat
C:\Users\HP\IdeaProjects\ValVoice\run_valvoice.bat
```

In the app’s status bar you should see:
- XMPP: Ready
- Bridge Mode: external-exe

These indicate the exe was found and launched. If you still see “Stub (demo only)” or “Init…”, the exe was not detected.

## 5) Troubleshooting
- "'node' is not recognized": open a new Command Prompt or add `C:\Program Files\nodejs\` to PATH, then `node -v` should work.
- `npm install` fails behind a corporate proxy:
  - Configure npm proxy: `npm config set proxy http://user:pass@proxy:port` and `npm config set https-proxy http://user:pass@proxy:port`.
- Build completes but exe missing: check that `xmpp-bridge/package.json` has `"build:exe": "pkg -t node18-win-x64 --output ../valvoice-xmpp.exe ."` and re-run `npm run build:exe`.

## 6) About “real chat”
The included bridge code (`xmpp-bridge/index.js`) is a placeholder that emits fake messages. To receive real Valorant chat, you’ll need to implement a proper Riot XMPP client inside `xmpp-bridge/` and rebuild. Until then, both the embedded script and the packaged exe are demos only.

