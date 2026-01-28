# Repository Analysis: ValVoice Pipeline

## 1. Current Data Flow Map

The current application pipeline follows this flow:

1.  **Input Source**:
    *   **Simulation**: `ValVoiceController.startChatSimulation()` generates hardcoded XML messages.
    *   **Manual Trigger**: `ValVoiceController.selectVoice()` triggers a sample text ("Sample voice confirmation").
    *   *Note*: There is no user-facing text input field for arbitrary text.

2.  **Controller Layer**:
    *   Input triggers `VoiceGenerator.speakVoice(text, voice, rate)`.
    *   `VoiceGenerator` manages Push-to-Talk (PTT) logic using `java.awt.Robot` (simulating key presses).
    *   It delegates the actual speech generation to `InbuiltVoiceSynthesizer`.

3.  **TTS Engine**:
    *   `InbuiltVoiceSynthesizer.speakInbuiltVoice()` constructs a PowerShell script.
    *   The script uses the .NET `System.Speech.Synthesis.SpeechSynthesizer` library.
    *   The command is executed via a persistent `powershell.exe` process spawned by `ProcessBuilder`.

4.  **Audio Output**:
    *   The PowerShell process plays audio to the system's **Default Playback Device**.
    *   `InbuiltVoiceSynthesizer` attempts to route this audio to a specific virtual device ("CABLE Input") using an external utility, `SoundVolumeView.exe`.
    *   If `SoundVolumeView.exe` or the virtual device is missing, audio remains on the default device (with a warning logged).

## 2. Broken or Incomplete Components

*   **Platform Dependency (Critical Break)**:
    *   The core TTS engine relies exclusively on `powershell.exe` and Windows `System.Speech` libraries.
    *   **Status**: Broken on non-Windows environments (Linux/macOS). The application cannot generate speech on the current test environment.

*   **Audio Routing (Brittle)**:
    *   Routing depends on the presence of `SoundVolumeView.exe` and "VB-CABLE" drivers.
    *   **Status**: Incomplete/Brittle. It fails gracefully to the default device, but the requirement to "play on a *selected* output device" is not met programmatically; it's either "Force CABLE" or "Default".

*   **Input Mechanism (Incomplete)**:
    *   **Status**: Incomplete. The UI (`mainApplication.fxml`) lacks a text input field and button for the user to type and test TTS directly. Testing currently requires code modification (simulation flag) or changing settings (voice selection).

## 3. Missing or Incorrectly Connected Components

*   **Missing Abstraction Layer**:
    *   `VoiceGenerator` is tightly coupled to the concrete `InbuiltVoiceSynthesizer` class.
    *   **Correction Needed**: A `VoiceSynthesizer` interface should be introduced to allow swapping backend implementations (e.g., WindowsSpeech, LinuxSpeech, JavaInternalSpeech).

*   **Missing Cross-Platform Backend**:
    *   There is no fallback TTS implementation for non-Windows systems.
    *   **Status**: Verified that common Linux CLI tools (`espeak`, `festival`, `spd-say`) are not present in the environment. A pure Java solution or a portable bundled engine is required.

*   **Incorrect Audio Device Handling**:
    *   Audio device selection is handled by an external process (`SoundVolumeView`) rather than within the Java application's audio mixer.
    *   **Correction Needed**: Java's `javax.sound.sampled` API should ideally be used to select output lines if a Java-based TTS is implemented.
