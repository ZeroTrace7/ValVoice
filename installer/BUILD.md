# ValVoice Installer Build Instructions

## Prerequisites

1. **Inno Setup 6** — Download from https://jrsoftware.org/isdl.php
2. **Java 23+** — Required for running ValVoice
3. **Maven** — For building the JAR
4. **.NET 8 SDK** — For building the OCR sidecar

## Build Steps

### 1. Build the Java Application
```bash
cd ValVoice
mvn clean package -DskipTests
```

### 2. Build the MITM Executable
```bash
cd mitm
npm install
npm run build:exe
```

### 3. Build the OCR Sidecar
```bash
cd ocr-sidecar
dotnet publish -c Release -o ../ocr
```
Expected output in `ocr/` directory:
- `ValVoiceOCR.exe`
- `ValVoiceOCR.dll`
- `ValVoiceOCR.runtimeconfig.json`
- `ValVoiceOCR.deps.json`
- dependency dlls

### 4. Compile the Installer

Open Inno Setup and compile `installer/valvoice-installer.iss`

Or from command line:
```bash
"C:\Program Files (x86)\Inno Setup 6\ISCC.exe" installer\valvoice-installer.iss
```

### 5. Output

The installer will be created at:
```
target/ValVoice-1.0.0-Setup.exe
```

## Installer Contents

The installer packages these files into `%ProgramFiles%\ValVoice\`:

| File | Description |
|------|-------------|
| `valvoice-1.0.0.jar` | Main Java application |
| `valvoice-mitm.exe` | MITM proxy (bundled Node.js runtime) |
| `SoundVolumeView.exe` | Audio routing utility |
| `engine/` | XTTS voice engine (`valorantNarrator-agentVoices.exe` + agents) |
| `ocr/` | OCR chat parser sidecar and runtime libraries |
| `mitm/certs/` | TLS certificates for MITM proxy |
| `run-valvoice.bat` | Silent Windows launcher |
| `README.txt` | End-user documentation |
| `LICENSE` | License information |

## Start Menu Shortcut

The installer creates a Start Menu shortcut that runs:
```
javaw.exe -jar "C:\Program Files\ValVoice\valvoice-1.0.0.jar"
```

## Requirements for End Users

- Windows 10/11 (64-bit)
- Java 23+
- VB-Audio Virtual Cable (for voice chat integration)
- SoundVolumeView (bundled with release)
