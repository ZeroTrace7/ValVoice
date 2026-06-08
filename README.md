<div align="center">

# 🎮 ValVoice

<img src="https://img.shields.io/badge/Valorant-FA4454?style=for-the-badge&logo=valorant&logoColor=white" alt="Valorant">

### 💬 *Your Chat, Your Voice*

**Let your teammates hear what you type — in real-time**

<br>

[![Java](https://img.shields.io/badge/Java-23-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![JavaFX](https://img.shields.io/badge/JavaFX-21-007396?style=flat-square&logo=java&logoColor=white)](https://openjfx.io/)
[![Windows](https://img.shields.io/badge/Windows-10%2F11-0078D6?style=flat-square&logo=windows&logoColor=white)](https://www.microsoft.com/windows)

<br>

---

🔊 **Type in chat** → 🎤 **Hear it spoken** → 👥 **Teammates hear it too**

---

</div>

<br>

## 🌟 Why ValVoice?

Ever wished your chat messages could be **heard** instead of just read? ValVoice converts your Valorant chat into speech — and routes it directly to your microphone so your **entire team** can hear!

> 💡 Perfect for quick callouts, fun moments, or when you just don't feel like using your mic!

<br>

## ✨ What You Get

| | Feature | Description |
|:--:|---------|-------------|
| 🔊 | **Instant TTS** | Messages read aloud the moment they're sent |
| 💬 | **All Chats** | Works with Party, Team, All chat |
| 🎤 | **Team Audio** | Your squad hears TTS through voice chat |
| 🎧 | **Your Audio Safe** | Game sounds stay untouched |
| 🎨 | **Sleek UI** | Beautiful dark theme that fits right in |
| ⚡ | **Lightweight** | Runs quietly in your system tray |

<br>

## 🔧 How It Works

ValVoice uses a pipeline of components to convert your chat messages into voice:

```
Valorant Chat → MITM Proxy → XMPP Parser → ChatDataHandler → VoiceGenerator → XTTS Engine → HTTP Stream → JLayer → VB-Cable → Valorant Voice Chat
```

**Key Components:**

| Component | Role |
|-----------|------|
| `valvoice-mitm.exe` | Intercepts Valorant XMPP chat traffic |
| `valorantNarrator-agentVoices.exe` | AI voice engine (XTTS on port 5005) |
| `VoiceGenerator.java` | Streams XTTS audio via JLayer, owns Push-to-Talk |
| `SoundVolumeView.exe` | Routes Java audio output to VB-Cable |
| `java.awt.Robot` | Simulates PTT key press/release tied to JLayer playback |

**Push-to-Talk:** Robot key press fires on JLayer playback start, Robot key release fires on playback finish.

**Audio Routing:** Java process audio → SoundVolumeView routes by PID → VB-Cable → Valorant mic input.

<br>

## 📥 Get Started in 3 Steps

<table>
<tr>
<td align="center" width="33%">

### Step 1
**Install VB-CABLE**

[⬇️ Download Here](https://vb-audio.com/Cable/)

*Free virtual audio cable*

</td>
<td align="center" width="33%">

### Step 2
**Configure Valorant**

`Settings → Audio → Voice Chat`

Set Input to **CABLE Output**

</td>
<td align="center" width="33%">

### Step 3
**Launch & Play!**

Pick your voice, select chats

**You're ready to go! 🎉**

</td>
</tr>
</table>

<br>

## ⚙️ Requirements

| Requirement | Detail |
|-------------|--------|
| OS | Windows 10+ (64-bit) |
| Java | JDK 23+ |
| VB-Audio Virtual Cable | [Download](https://vb-audio.com/Cable/) |
| SoundVolumeView | Bundled with release |

> **Note:** The XTTS engine takes approximately 90–100 seconds to start on first launch.

<br>

## 🟡 Runtime Validation Pending

The following items have not yet been confirmed at runtime:

- VB-Cable meter confirmation (visual verification of audio flow through the virtual cable)
- Valorant loopback confirmation (verification that Valorant receives audio from VB-Cable)
- End-to-end teammate confirmation (verification that a teammate hears the TTS output)

These items require live runtime testing and should not be treated as completed facts.

<br>

## 🔐 Your Privacy Matters

| | |
|:--|:--|
| ✅ | **Local Only** — Credentials never leave your PC |
| ✅ | **Encrypted** — All connections use TLS |
| ✅ | **No Tracking** — Zero analytics or data collection |
| ✅ | **No Injection** — Doesn't touch game files |

<br>

## 🤝 Want to Contribute?

We'd love your help! Feel free to submit a Pull Request or open an Issue.

<br>

---

<div align="center">

### ⚠️ Disclaimer

*This is a community project and is not affiliated with, endorsed by, or connected to Riot Games.*

*Valorant and Riot Games are trademarks of Riot Games, Inc.*

<br>

---

<br>

**If ValVoice made your games more fun, consider giving it a ⭐**

<br>

Made with ❤️ for the Valorant community

</div>
