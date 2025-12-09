# ValVoice Build & Deployment Guide

## Prerequisites

1. **Java Development Kit (JDK) 23** - Required for building
2. **Maven 3.8+** - For building the project
3. **Node.js 18+** - For the XMPP bridge (runtime requirement)
4. **npm** - Comes with Node.js
5. **jpackage** - Comes with JDK for creating the `.exe`

## Build Steps

### Step 1: Install XMPP Bridge Dependencies

```bash
cd C:\Users\HP\IdeaProjects\ValVoice\xmpp-bridge
npm install
```

### Step 2: (Optional) Build valvoice-xmpp.exe

If you want a standalone `.exe` for the XMPP bridge:

```bash
npm run build:exe
```

This creates `valvoice-xmpp.exe` in the project root.

### Step 3: Build the Java Application

```bash
cd C:\Users\HP\IdeaProjects\ValVoice
mvn clean package
```

This creates:
- `target/valvoice-1.0.0.jar` - Shaded JAR with all dependencies
- `target/valvoice-1.0.0.zip` - Distribution package with xmpp-bridge

### Step 4: Test the JAR

Before creating the `.exe`, test that everything works:

```bash
java -jar target/valvoice-1.0.0.jar
```

**Expected behavior:**
1. Application starts
2. XMPP bridge starts (either `valvoice-xmpp.exe` or Node.js fallback)
3. Logs show: "Started XMPP bridge (mode: external-exe)" or "Started XMPP bridge (mode: node-script)"
4. Connect to Valorant XMPP server
5. Team chat messages trigger TTS

### Step 5: Create Windows Executable with jpackage

#### Option A: Bundle JRE (Recommended)

```bash
jpackage ^
  --input target ^
  --name ValVoice ^
  --main-jar valvoice-1.0.0.jar ^
  --main-class com.someone.valvoicegui.Main ^
  --type exe ^
  --win-console ^
  --win-dir-chooser ^
  --win-menu ^
  --win-shortcut ^
  --app-version 1.0.0 ^
  --vendor "ValVoice Team" ^
  --description "Valorant Voice Chat TTS Application" ^
  --icon src/main/resources/com/someone/valvoicegui/icon.ico ^
  --resource-dir . ^
  --add-modules javafx.controls,javafx.fxml,javafx.graphics
```

#### Option B: Use System JRE (Smaller package)

```bash
jpackage ^
  --input target ^
  --name ValVoice ^
  --main-jar valvoice-1.0.0.jar ^
  --main-class com.someone.valvoicegui.Main ^
  --type exe ^
  --runtime-image "C:\Program Files\Java\jdk-23" ^
  --win-console ^
  --app-version 1.0.0
```

### Step 6: Manual Package Structure

If jpackage doesn't work, manually create this structure:

```
ValVoice-1.0.0/
├── ValVoice.bat              (Launcher script)
├── valvoice-1.0.0.jar        (From target/)
├── SoundVolumeView.exe       (Audio routing tool)
├── xmpp-bridge/              (Copy entire folder)
│   ├── index.js
│   ├── package.json
│   └── node_modules/         (After npm install)
│       └── ...
└── valvoice-xmpp.exe         (Optional - standalone bridge)
```

Create `ValVoice.bat`:
```batch
@echo off
java -jar valvoice-1.0.0.jar
pause
```

## Deployment Package Contents

Your final distribution should include:

### Required Files:
1. **valvoice-1.0.0.jar** - Main application
2. **xmpp-bridge/** - Node.js bridge (with node_modules installed)
3. **SoundVolumeView.exe** - Audio routing utility
4. **README.md** - User documentation
5. **TEAM_CHAT_FIX_SUMMARY.md** - Technical documentation

### Optional Files:
1. **valvoice-xmpp.exe** - Standalone XMPP bridge (recommended)
2. **JRE/** - Bundled Java Runtime (if using jpackage with --runtime-image)

## Runtime Requirements

### For End Users:

**If using standalone .exe (jpackage with bundled JRE):**
- Node.js 18+ (for XMPP bridge fallback)
- VB-Audio Virtual Cable
- Windows 10/11

**If using JAR:**
- Java Runtime Environment (JRE) 23+
- Node.js 18+
- VB-Audio Virtual Cable
- Windows 10/11

## Installation Instructions (For End Users)

1. **Install Node.js**
   - Download from: https://nodejs.org/
   - Use LTS version (18.x or 20.x)

2. **Install VB-Audio Virtual Cable**
   - Download from: https://vb-audio.com/Cable/
   - Run `VBCABLE_Setup_x64.exe` as Administrator

3. **Extract ValVoice Package**
   - Unzip `ValVoice-1.0.0.zip` to `C:\Program Files\ValVoice\`

4. **Install XMPP Bridge Dependencies** (if not using .exe)
   ```bash
   cd "C:\Program Files\ValVoice\xmpp-bridge"
   npm install
   ```

5. **Run ValVoice**
   - Double-click `ValVoice.exe` or `ValVoice.bat`
   - Or run: `java -jar valvoice-1.0.0.jar`

## Troubleshooting

### "Node.js not found"
- Install Node.js from https://nodejs.org/
- Ensure `node` is in system PATH
- Restart terminal/cmd after installation

### "XMPP bridge directory not found"
- Ensure `xmpp-bridge/` folder exists next to the JAR
- Verify `xmpp-bridge/index.js` exists
- Check `node_modules/` is installed (`npm install` in xmpp-bridge/)

### "XMPP bridge not receiving messages"
- Check logs for XMPP connection status
- Ensure Valorant is running
- Verify Riot Client is logged in
- Check firewall isn't blocking connections

### "Team chat not narrating"
- Check logs for: `📨 RAW MESSAGE STANZA`
- Verify `teamState=true` in logs
- Check `Chat initialized: enabledChannels=[PARTY, TEAM]`
- Ensure "SELF+PARTY+TEAM" is selected in UI

## Build Artifacts

After `mvn clean package`, you'll find:

```
target/
├── valvoice-1.0.0.jar          # Shaded JAR (runnable)
├── valvoice-1.0.0.zip          # Distribution package
└── classes/                     # Compiled classes
```

After `jpackage`:

```
ValVoice/                        # Installation directory
├── ValVoice.exe                 # Launcher
├── app/
│   ├── valvoice-1.0.0.jar      # Application JAR
│   └── ...
└── runtime/                     # Bundled JRE (if included)
```

## Continuous Integration

For automated builds, add this to your CI/CD pipeline:

```yaml
# GitHub Actions example
- name: Build XMPP Bridge
  run: |
    cd xmpp-bridge
    npm install
    npm run build:exe

- name: Build Java Application
  run: mvn clean package

- name: Create Installer
  run: |
    jpackage --input target --name ValVoice ...
```

## Version Management

Update version in three places:
1. `pom.xml` - `<version>1.0.0</version>`
2. `xmpp-bridge/package.json` - `"version": "1.0.0"`
3. `src/main/resources/com/someone/valvoicegui/config.properties` - `version=1.0`

## Distribution Checklist

Before releasing:

- [ ] Update version numbers
- [ ] Build and test JAR
- [ ] Build XMPP bridge .exe
- [ ] Create jpackage installer
- [ ] Test on clean Windows machine
- [ ] Verify Node.js fallback works
- [ ] Test team chat TTS in live game
- [ ] Update README.md
- [ ] Create GitHub release
- [ ] Upload distribution artifacts

## Support

For issues or questions:
- Check `TEAM_CHAT_FIX_SUMMARY.md` for technical details
- Review application logs
- Enable debug mode: Run with `-debug` flag

