package com.someone.valvoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent PowerShell-based Windows voice synthesizer using System.Speech.
 *
 * Responsibilities:
 *  - Launch a reusable hidden PowerShell process (-NoExit) for lower latency voice playback.
 *  - Enumerate installed System.Speech voices (friendly names) on initialization.
 *  - Provide simple speak method with selectable voice + rate conversion (0..100 UI -> -10..10 SAPI range).
 *  - Route the PowerShell process audio to VB-CABLE using SoundVolumeView.
 *
 * Notes:
 *  - This is Windows-only. Guard usages with OS checks.
 *  - Routes PowerShell process PID to VB-Cable for TTS output.
 */
public class InbuiltVoiceSynthesizer {
    private static final Logger logger = LoggerFactory.getLogger(InbuiltVoiceSynthesizer.class);
    private static final String END_SENTINEL = "END_OF_VOICES";

    private Process powershellProcess;
    private PrintWriter powershellWriter;
    private BufferedReader powershellReader;
    private final List<String> voices = new ArrayList<>();
    private volatile boolean ready = false;

    public InbuiltVoiceSynthesizer() {
        if (!isWindows()) {
            logger.warn("InbuiltVoiceSynthesizer initialized on non-Windows OS; disabled");
            return;
        }

        try {
            // Start persistent PowerShell session
            powershellProcess = new ProcessBuilder("powershell.exe", "-NoExit", "-Command", "-")
                    .start();
            powershellWriter = new PrintWriter(new OutputStreamWriter(powershellProcess.getOutputStream(), StandardCharsets.UTF_8), true);
            powershellReader = new BufferedReader(new InputStreamReader(powershellProcess.getInputStream(), StandardCharsets.UTF_8));

            // Enumerate installed voices
            enumerateVoices();

            // Route PowerShell process audio to VB-Cable
            routePowerShellToVbCable();

            // Test voice if available
            if (!voices.isEmpty()) {
                speakInbuiltVoice(voices.get(0), "Inbuilt voice synthesizer initialized.", (short) 100);
            }

            logger.info("InbuiltVoiceSynthesizer initialized successfully with {} voices", voices.size());
            ready = true;
        } catch (IOException e) {
            logger.error("Failed to start persistent PowerShell for inbuilt synthesizer", e);
            cleanup();
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Check if the synthesizer is ready to use
     */
    public boolean isReady() {
        return ready && powershellProcess != null && powershellProcess.isAlive();
    }

    /**
     * Shutdown the synthesizer (alias for cleanup)
     */
    public void shutdown() {
        ready = false;
        cleanup();
    }

    private void enumerateVoices() {
        try {
            String command = "Add-Type -AssemblyName System.Speech;" +
                    "$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer;" +
                    "$speak.GetInstalledVoices() | Select-Object -ExpandProperty VoiceInfo | Select-Object -Property Name | ConvertTo-Csv -NoTypeInformation | Select-Object -Skip 1; " +
                    "echo '" + END_SENTINEL + "'";

            powershellWriter.println(command);

            String line;
            while ((line = powershellReader.readLine()) != null) {
                if (line.trim().equals(END_SENTINEL)) {
                    break;
                }
                if (!line.trim().isEmpty()) {
                    voices.add(line.replace("\"", "").trim());
                }
            }

            if (voices.isEmpty()) {
                logger.warn("No inbuilt voices found.");
            } else {
                logger.info("Found {} inbuilt voices: {}", voices.size(), voices);
            }
        } catch (Exception e) {
            logger.error("Failed to enumerate voices", e);
        }
    }

    /**
     * Route the PowerShell process to VB-Cable using SoundVolumeView.
     * This matches the ValNarrator implementation exactly.
     */
    private void routePowerShellToVbCable() {
        try {
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles == null) {
                logger.debug("ProgramFiles environment variable not found");
                return;
            }

            String fileLocation = String.format("%s/ValVoice/SoundVolumeView.exe", programFiles.replace("\\", "/"));
            File svvFile = new File(fileLocation);

            if (!svvFile.exists()) {
                // Try alternative location
                fileLocation = String.format("%s/SoundVolumeView.exe", programFiles.replace("\\", "/"));
                svvFile = new File(fileLocation);

                if (!svvFile.exists()) {
                    // Try project directory
                    fileLocation = new File(System.getProperty("user.dir"), "SoundVolumeView.exe").getAbsolutePath();
                    svvFile = new File(fileLocation);

                    if (!svvFile.exists()) {
                        logger.debug("SoundVolumeView.exe not found; skipping VB-CABLE routing for PowerShell");
                        return;
                    }
                }
            }

            // CRITICAL: Route PowerShell process by PID to VB-Cable
            long pid = powershellProcess.pid();
            String command = fileLocation + " /SetAppDefault \"CABLE Input\" all " + pid;
            Runtime.getRuntime().exec(command);
            logger.info("PowerShell process (PID {}) audio routed to VB-CABLE", pid);
        } catch (IOException e) {
            logger.error("SoundVolumeView.exe generated an error", e);
        }
    }

    public List<String> getAvailableVoices() {
        return new ArrayList<>(voices);
    }

    /**
     * Speak text using the specified voice and rate.
     *
     * @param voice The voice name to use
     * @param text  The text to speak
     * @param rate  Rate from 0-100 (UI scale), will be converted to -10 to +10 (SAPI scale)
     */
    public void speakInbuiltVoice(String voice, String text, short rate) {
        if (powershellWriter == null) {
            logger.warn("PowerShell process not initialized; cannot speak");
            return;
        }

        // Convert UI rate (0-100) to SAPI rate (-10 to +10)
        short sapiRate = (short) (rate / 10.0 - 10);

        // Escape single quotes in text
        String escapedText = text.replace("'", "''");

        try {
            String command = String.format(
                    "Add-Type -AssemblyName System.Speech;" +
                            "$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer;" +
                            "$speak.SelectVoice('%s');" +
                            "$speak.Rate=%d;" +
                            "$speak.Speak('%s');",
                    voice, sapiRate, escapedText);

            powershellWriter.println(command);
            logger.debug("Speaking with voice '{}' at rate {}: {}", voice, sapiRate, text.substring(0, Math.min(50, text.length())));
        } catch (Exception e) {
            logger.error("Failed to speak text", e);
        }
    }

    /**
     * Clean up PowerShell process on shutdown
     */
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
                powershellProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                if (powershellProcess.isAlive()) {
                    powershellProcess.destroyForcibly();
                }
            }
            logger.debug("InbuiltVoiceSynthesizer cleaned up");
        } catch (Exception e) {
            logger.debug("Error during cleanup", e);
        }
    }
}
