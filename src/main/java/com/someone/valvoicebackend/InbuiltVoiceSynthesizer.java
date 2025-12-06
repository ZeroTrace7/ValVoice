package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent PowerShell-based Windows voice synthesizer using System.Speech.
 * Matches ValorantNarrator implementation exactly - simplified and reliable.
 *
 * CRITICAL: Routes ONLY the PowerShell process to VB-CABLE, NOT the Java app!
 */
public class InbuiltVoiceSynthesizer {
    private static final Logger logger = LoggerFactory.getLogger(InbuiltVoiceSynthesizer.class);
    private Process powershellProcess;
    private PrintWriter powershellWriter;
    private BufferedReader powershellReader;
    private final List<String> voices = new ArrayList<>();

    public InbuiltVoiceSynthesizer() {
        try {
            powershellProcess = new ProcessBuilder("powershell.exe", "-NoExit", "-Command", "-").start();
            powershellWriter = new PrintWriter(new OutputStreamWriter(powershellProcess.getOutputStream()), true);
            powershellReader = new BufferedReader(new InputStreamReader(powershellProcess.getInputStream()));
        } catch (IOException e) {
            logger.error("Failed to start PowerShell process", e);
        }

        try {
            String command = "Add-Type -AssemblyName System.Speech;$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer;$speak.GetInstalledVoices() | Select-Object -ExpandProperty VoiceInfo | Select-Object -Property Name | ConvertTo-Csv -NoTypeInformation | Select-Object -Skip 1; echo 'END_OF_VOICES'";
            powershellWriter.println(command);

            String line;
            while ((line = powershellReader.readLine()) != null) {
                if (line.trim().equals("END_OF_VOICES")) {
                    break;
                }
                if (!line.trim().isEmpty()) {
                    voices.add(line.replace("\"", "").trim());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to initialize voices", e);
        }

        if (voices.isEmpty()) {
            logger.warn("No inbuilt voices found.");
        } else {
            logger.info(String.format("Found %d inbuilt voices.", voices.size()));
            speakInbuiltVoice(voices.get(0), "Inbuilt voice synthesizer initialized.", (short) 100);
        }

        // Route PowerShell process to VB-CABLE using SoundVolumeView.exe
        try {
            String fileLocation = String.format("%s/ValorantNarrator/SoundVolumeView.exe", System.getenv("ProgramFiles").replace("\\", "/"));
            File svvFile = new File(fileLocation);

            // Try alternate location if not found
            if (!svvFile.exists()) {
                fileLocation = new File(System.getProperty("user.dir"), "SoundVolumeView.exe").getAbsolutePath();
                svvFile = new File(fileLocation);
            }

            if (svvFile.exists()) {
                long pid = powershellProcess.pid();
                String command = fileLocation + " /SetAppDefault \"CABLE Input\" all " + pid;
                Runtime.getRuntime().exec(command);
                logger.info("✓ PowerShell process (PID {}) routed to VB-CABLE", pid);
            } else {
                logger.warn("⚠ SoundVolumeView.exe not found - TTS will use default audio device");
            }
        } catch (IOException e) {
            logger.error(String.format("SoundVolumeView.exe generated an error: %s", (Object) e.getStackTrace()));
        }

        setupVbCableListenThrough();
    }

    private void setupVbCableListenThrough() {
        try {
            String fileLocation = String.format("%s/ValorantNarrator/SoundVolumeView.exe", System.getenv("ProgramFiles").replace("\\", "/"));
            File svvFile = new File(fileLocation);

            // Try alternate location if not found
            if (!svvFile.exists()) {
                fileLocation = new File(System.getProperty("user.dir"), "SoundVolumeView.exe").getAbsolutePath();
                svvFile = new File(fileLocation);
            }

            if (svvFile.exists()) {
                // 1. Set CABLE Output to play through default device (so you can hear TTS)
                String command = fileLocation + " /SetPlaybackThroughDevice \"CABLE Output\" \"Default Playback Device\"";
                Runtime.getRuntime().exec(command);

                // 2. Enable "Listen to this device" on CABLE Output
                command = fileLocation + " /SetListenToThisDevice \"CABLE Output\" 1";
                Runtime.getRuntime().exec(command);

                // 3. Unmute CABLE Output (in case it was muted)
                command = fileLocation + " /unmute \"CABLE Output\"";
                Runtime.getRuntime().exec(command);

                logger.info("✓ VB-CABLE listen-through configured successfully");
            } else {
                logger.warn("⚠ SoundVolumeView.exe not found - TTS listen-through not configured");
            }
        } catch (IOException e) {
            logger.error("Failed to configure VB-CABLE listen-through: {}", e.getMessage());
        }
    }

    public List<String> getAvailableVoices() {
        return voices;
    }

    public boolean isReady() {
        return powershellProcess != null && powershellProcess.isAlive() && !voices.isEmpty();
    }

    public void speakInbuiltVoice(String voice, String text, short rate) {
        rate = (short) (rate / 10.0 - 10);

        try {
            // Escape single quotes for PowerShell
            String escapedText = text.replace("'", "''");

            // Create new SpeechSynthesizer each time (ValorantNarrator approach)
            String command = String.format("Add-Type -AssemblyName System.Speech;$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer;$speak.SelectVoice('%s');$speak.Rate=%d;$speak.Speak('%s');", voice, rate, escapedText);
            powershellWriter.println(command);

            logger.info("🔊 Speaking: voice='{}', rate={}, text='{}'", voice, rate, text.length() > 50 ? text.substring(0, 47) + "..." : text);
        } catch (Exception e) {
            logger.error("Failed to speak text", e);
        }
    }

    public void shutdown() {
        cleanup();
    }

    public void cleanup() {
        try {
            if (powershellWriter != null) {
                powershellWriter.println("exit");
                powershellWriter.close();
            }
            if (powershellReader != null) {
                powershellReader.close();
            }
            if (powershellProcess != null && powershellProcess.isAlive()) {
                powershellProcess.destroy();
            }
            logger.debug("InbuiltVoiceSynthesizer cleaned up");
        } catch (Exception e) {
            logger.debug("Error during cleanup", e);
        }
    }
}
