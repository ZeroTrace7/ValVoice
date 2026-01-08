<div align="center">

# 🎮 ValVoice

### *Text-to-Speech for Valorant Chat*

**Turn in-game chat into crystal-clear voice — heard by you AND your teammates**

[![Java](https://img.shields.io/badge/Java-23-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![JavaFX](https://img.shields.io/badge/JavaFX-21-007396?style=for-the-badge&logo=java&logoColor=white)](https://openjfx.io/)
[![Node.js](https://img.shields.io/badge/Node.js-18+-339933?style=for-the-badge&logo=nodedotjs&logoColor=white)](https://nodejs.org/)
[![Windows](https://img.shields.io/badge/Windows-10%2F11-0078D6?style=for-the-badge&logo=windows&logoColor=white)](https://www.microsoft.com/windows)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

<br>

<img src="https://raw.githubusercontent.com/wiki/placeholder/demo.gif" alt="ValVoice Demo" width="600">

*A sleek, modern interface that puts voice control at your fingertips*

</div>

---

## ✨ Features

<table>
<tr>
<td width="50%">

### 🎯 Core Functionality
- 🔊 **Real-time TTS** — Chat messages read aloud instantly
- 🎮 **All Chat Types** — Party, Team, All, and Whispers
- 🎤 **Team Integration** — Teammates hear TTS through your mic
- 🔇 **Audio Isolation** — Game/system audio remains unchanged

</td>
<td width="50%">

### 💎 User Experience
- 🖥️ **Clean Modern UI** — Material Design with dark theme
- ⚙️ **Fully Customizable** — Voice, speed, and chat filters
- 📊 **Live Status Badges** — Connection health at a glance
- 🔽 **System Tray** — Minimize and run in background

</td>
</tr>
</table>

---

## 🏗️ Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Valorant Chat  │────▶│   XMPP Bridge    │────▶│   ValVoice UI   │
│   (Riot XMPP)   │◀────│   (Node.js)      │◀────│   (JavaFX)      │
└─────────────────┘     └──────────────────┘     └────────┬────────┘
                                                          │
                        ┌──────────────────┐              │
                        │  Windows TTS     │◀─────────────┘
                        │  (System.Speech) │
                        └────────┬─────────┘
                                 │
                        ┌────────▼─────────┐     ┌─────────────────┐
                        │    VB-CABLE      │────▶│   Teammates     │
                        │  (Virtual Audio) │     │   (Voice Chat)  │
                        └──────────────────┘     └─────────────────┘
```

| Component | Technology | Purpose |
|-----------|------------|---------|
| **Frontend** | JavaFX + JFoenix | Modern Material Design UI |
| **XMPP Bridge** | Node.js | Real-time chat message capture |
| **TTS Engine** | Windows System.Speech | Native voice synthesis |
| **Audio Routing** | SoundVolumeView + VB-CABLE | Route TTS to voice chat |

---

## 📋 Requirements

| Requirement | Details |
|-------------|---------|
| **OS** | Windows 10 / 11 |
| **Java** | JDK 17+ (23 recommended) |
| **Virtual Audio** | [VB-CABLE](https://vb-audio.com/Cable/) (free) |
| **Game** | Valorant with Riot Client running |

---

## 🚀 Quick Start

### 1️⃣ Install VB-CABLE

Download and install [VB-CABLE](https://vb-audio.com/Cable/) (restart if prompted)

### 2️⃣ Configure Valorant

Navigate to **Settings** → **Audio** → **Voice Chat**:

```
Voice Chat Input Device  →  CABLE Input (VB-Audio Virtual Cable)
```

### 3️⃣ Launch ValVoice

```bash
# Option A: Run the JAR
java -jar valvoice-1.0.0.jar

# Option B: Build from source
mvn clean package
java -jar target/valvoice-1.0.0.jar
```

### 4️⃣ Configure & Play!

1. Select your preferred **Windows voice**
2. Choose which **chat sources** to narrate (Self/Party/Team/All)
3. Adjust **speech rate** to your liking
4. Send a chat message — hear it spoken, teammates hear it too! 🎉

---

## 🎛️ Settings Overview

| Setting | Description |
|---------|-------------|
| **Voice** | Select from installed Windows TTS voices |
| **Rate** | Adjust speech speed (0.5x - 2.0x) |
| **Chat Sources** | Toggle Party / Team / All / Whisper chat |
| **Ignored Players** | Mute specific players from TTS |
| **Mic Button** | Enable/disable TTS output to voice chat |

---

## 🔧 Building from Source

### Prerequisites

- Java JDK 23+
- Maven 3.8+
- Node.js 18+ (for XMPP bridge)

### Required Tools Download

| Tool | Download | Purpose |
|------|----------|---------|
| **SoundVolumeView** | [Download from NirSoft](https://www.nirsoft.net/utils/sound_volume_view.html) | Audio routing (place in project root) |

### Build Steps

```bash
# Clone the repository
git clone https://github.com/yourusername/ValVoice.git
cd ValVoice

# Download SoundVolumeView.exe from NirSoft and place in project root

# Build the Java application
mvn clean package

# The executable JAR will be at:
# target/valvoice-1.0.0.jar

# Build XMPP bridge executable (REQUIRED)
cd xmpp-bridge
npm install
npm run build:exe
# Output: ../valvoice-xmpp.exe

# Run the application
cd ..
java -jar target/valvoice-1.0.0.jar
```

### Project Structure

```
ValVoice/
├── 📁 src/main/java/com/someone/
│   ├── 📁 valvoicebackend/     # Core logic & Riot API integration
│   │   ├── APIHandler.java
│   │   ├── ChatDataHandler.java
│   │   ├── InbuiltVoiceSynthesizer.java
│   │   ├── RiotClientUtils.java
│   │   └── ...
│   └── 📁 valvoicegui/         # JavaFX UI components
│       ├── ValVoiceApplication.java
│       ├── ValVoiceController.java
│       └── ...
├── 📁 src/main/resources/
│   ├── 📄 mainApplication.fxml  # UI layout
│   ├── 📄 style.css             # Dark theme styling
│   └── 📁 icons/                # UI icons
├── 📁 xmpp-bridge/              # Node.js XMPP client
│   ├── 📄 index.js
│   └── 📄 package.json
├── 📄 valvoice-xmpp.exe         # Pre-built XMPP bridge
├── 📄 SoundVolumeView.exe       # Audio routing utility
└── 📄 pom.xml                   # Maven configuration
```

---

## 🐛 Troubleshooting

<details>
<summary><b>❌ "Lockfile not found"</b></summary>

**Cause:** Riot Client is not running  
**Solution:** Launch Valorant or Riot Client before starting ValVoice
</details>

<details>
<summary><b>❌ "Chat session did not become ready"</b></summary>

**Cause:** Valorant not fully launched  
**Solution:** Wait for Valorant to fully load to the main menu
</details>

<details>
<summary><b>❌ No audio / Teammates can't hear TTS</b></summary>

**Check the following:**
1. VB-CABLE is installed and visible in Sound settings
2. Valorant Voice Chat Input is set to "CABLE Input"
3. The mic toggle in ValVoice is enabled
4. Voice Chat is enabled in Valorant settings
</details>

<details>
<summary><b>❌ "XMPP connection failed"</b></summary>

**Cause:** Network issues or Riot server maintenance  
**Solution:** ValVoice auto-reconnects; check your internet connection
</details>

---

## 🔒 Privacy & Security

| Aspect | Details |
|--------|---------|
| **Credentials** | Reads from local Riot Client only — never stored |
| **Connections** | TLS encrypted to Riot XMPP servers only |
| **Analytics** | ❌ None — no data collection whatsoever |
| **Third Parties** | ❌ No external services or APIs |

> ⚠️ **Note:** This app accesses your own chat session through Riot's local client API while the game is running. It does not modify game files or inject into the game process.

---

## 📦 Dependencies

### Java (Maven)
| Package | Version | Purpose |
|---------|---------|---------|
| JavaFX | 21.0.1 | Modern UI framework |
| JFoenix | 9.0.10 | Material Design components |
| Gson | 2.10.1 | JSON processing |
| SLF4J + Logback | 2.0.9 | Logging |

### Node.js (XMPP Bridge)
| Package | Purpose |
|---------|---------|
| Built-in TLS | Secure XMPP connection |
| Built-in HTTPS | Riot API requests |
| pkg | Executable bundler |

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## ⚠️ Disclaimer

<div align="center">

**This is a community project and is not affiliated with, endorsed by, or connected to Riot Games in any way.**

*Valorant and Riot Games are trademarks of Riot Games, Inc.*

</div>

---

<div align="center">

### ⭐ Star this repo if you find it useful!

Made with ❤️ for the Valorant community

</div>
