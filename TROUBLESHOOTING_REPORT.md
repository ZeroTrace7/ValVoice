# Troubleshooting Analysis: TTS Audio in Valorant Voice Chat

## 1. Execution Path Trace (Text → Audio)

The code executes the following path when a chat message is received:

1.  **Input (Text Detection)**:
    *   **Component**: `ChatDataHandler.java`
    *   **Action**: `message(Message)` method receives an incoming chat message.
    *   **Filtering**: It checks message type (Whisper/Party/Team). If the message passes filters (is enabled), it calls `ValVoiceController.narrateMessage()`.
    *   **Note**: Since the user reports "audio is audible" for Whisper, this confirms the Input and Filtering stages are **working correctly**.

2.  **Controller Layer**:
    *   **Component**: `ValVoiceController.narrateMessage()`
    *   **Action**: Calls `VoiceGenerator.getInstance().speakVoice(text, voice, rate)`.

3.  **Push-to-Talk (PTT) Activation**:
    *   **Component**: `VoiceGenerator.java`
    *   **Action**:
        1.  `robot.keyPress(keyEvent)`: Simulates pressing the configured PTT key (Default: `V`).
        2.  `Thread.sleep(150)`: Waits for the game to register the key press.
    *   **Mechanism**: Uses `java.awt.Robot`, which generates standard OS-level input events.

4.  **Audio Generation (TTS)**:
    *   **Component**: `InbuiltVoiceSynthesizer.java`
    *   **Action**: `speakInbuiltVoice()` sends a script to a persistent PowerShell process.
    *   **Output**: PowerShell uses `System.Speech` to play audio to the default playback device.
    *   **Routing**: The audio is ostensibly routed to "CABLE Input" (VB-CABLE) via `SoundVolumeView` configuration performed at startup.

5.  **PTT Deactivation**:
    *   **Component**: `VoiceGenerator.java`
    *   **Action**: `robot.keyRelease(keyEvent)` releases the PTT key after speech completes.

## 2. Analysis of Whisper vs. Team/Party Discrepancy

The reported issue is that audio works in Whisper but fails in Team/Party. Based on the code, here is the technical explanation:

### A. The "Robot" Blocking Issue (Primary Suspect)
*   **The Issue**: The code uses `java.awt.Robot` to simulate PTT key presses.
*   **The Reality**: Valorant's anti-cheat (Vanguard) and the game client itself aggressively block synthetic input from non-driver sources like Java's `Robot` class to prevent botting.
*   **Why Whisper Works**: "Whisper" implies a context where PTT might not be required or the user is testing via a mechanism (like "Loopback Test" or "Open Mic" in a private party) where the key press is irrelevant. If the PTT key is **not** successfully pressed by the code, the audio plays to the microphone input (CABLE Output), but the game **does not transmit it** to the Team/Party channel because the voice gate is closed.
*   **Why Team/Party Fails**: These channels strictly require the PTT key to be held. Since `Robot` key presses are likely ignored by the game, the microphone remains muted, and teammates hear nothing.

### B. Single PTT Key Limitation
*   **The Code**: `VoiceGenerator` maintains a **single** `keyEvent` (Default `V`).
*   **The Game**: Valorant uses **different keys** for Team Voice (Default `V`) and Party Voice (Default `U`).
*   **The Mismatch**: If the user is typing in **Party Chat**, the app generates TTS and presses `V` (Team Key).
    *   Audio is generated.
    *   Team Key is pressed.
    *   Result: Audio is transmitted to the **Team** channel, not the Party channel.
    *   User Perception: "It's not audible in Party chat" (because it went to the wrong channel).

### C. "Audible" Confusion (Local vs. Remote)
*   **Local Audio**: The `InbuiltVoiceSynthesizer` configures "Listen to this device" on the VB-CABLE output. This means the user **always** hears the TTS locally through their speakers, regardless of whether it is transmitted in-game.
*   **Conclusion**: If the user says "Audible in Whisper", they likely mean they verified transmission with a friend or in a specific context. If they simply "hear it", they might be hearing the local loopback, which works 100% of the time because it bypasses the game entirely.

## 3. Role of valvoice-xmpp.exe

*   **Role**: This executable is solely responsible for connecting to Riot's XMPP chat servers to **receive text messages**.
*   **Verification**: Since the TTS generation is triggering (audio is being produced), `valvoice-xmpp.exe` is **working correctly**. It successfully detects the text and passes it to the Java backend.
*   **Conclusion**: It is **unrelated** to the voice pipeline failure. The breakdown happens *after* text is received, specifically during audio transmission/routing.

## 4. Identified Assumptions & Risks

1.  **Assumption: `java.awt.Robot` works in-game.**
    *   **Risk**: High. Most competitive games block this API. This is the most likely cause of PTT failure.
2.  **Assumption: One PTT key fits all.**
    *   **Risk**: High. The code blindly presses the configured key (e.g., `V`) for *all* messages. Party messages need a different key (e.g., `U`).
3.  **Assumption: Audio Routing is successful.**
    *   **Risk**: Medium. `InbuiltVoiceSynthesizer` relies on `SoundVolumeView.exe` successfully finding "CABLE Input". If this fails (e.g. device named differently), audio plays to default speakers (user hears it, game mic hears nothing).
