package com.someone.valvoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent PowerShell-based Windows voice synthesizer using System.Speech.
 * Matches ValNarrator implementation exactly.
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

        // Initialize the SpeechSynthesizer ONCE and keep it in the PowerShell session
        try {
            String initCommand = "Add-Type -AssemblyName System.Speech;$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer;";
            powershellWriter.println(initCommand);

            String command = "$speak.GetInstalledVoices() | Select-Object -ExpandProperty VoiceInfo | Select-Object -Property Name | ConvertTo-Csv -NoTypeInformation | Select-Object -Skip 1; echo 'END_OF_VOICES'";
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

        // CRITICAL: Route ONLY PowerShell process to VB-CABLE (NOT Java app!)
        try {
            String fileLocation = null;
            File svvFile = null;

            // Try multiple locations for SoundVolumeView.exe
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles != null) {
                // Try Program Files\ValVoice\ first
                fileLocation = String.format("%s/ValVoice/SoundVolumeView.exe", programFiles.replace("\\", "/"));
                svvFile = new File(fileLocation);

                if (!svvFile.exists()) {
                    // Try Program Files\ directly
                    fileLocation = String.format("%s/SoundVolumeView.exe", programFiles.replace("\\", "/"));
                    svvFile = new File(fileLocation);
                }
            }

            // Try project directory (user.dir)
            if (svvFile == null || !svvFile.exists()) {
                fileLocation = new File(System.getProperty("user.dir"), "SoundVolumeView.exe").getAbsolutePath();
                svvFile = new File(fileLocation);
            }

            if (svvFile != null && svvFile.exists()) {
                long pid = powershellProcess.pid();
                // Use ProcessBuilder instead of deprecated Runtime.exec()
                ProcessBuilder pb = new ProcessBuilder(
                    fileLocation,
                    "/SetAppDefault",
                    "CABLE Input",
                    "all",
                    String.valueOf(pid)
                );
                pb.start();
                logger.info("✓ PowerShell process (PID {}) routed to VB-CABLE using: {}", pid, fileLocation);
            } else {
                logger.warn("⚠ SoundVolumeView.exe not found! PowerShell TTS will NOT be routed to VB-CABLE.");
                logger.warn("  Please place SoundVolumeView.exe in: {}", System.getProperty("user.dir"));
            }
        } catch (IOException e) {
            logger.error("SoundVolumeView.exe execution failed", e);
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
            // Escape single quotes in text for PowerShell string interpolation
            String escapedText = text.replace("'", "''");

            // Use the EXISTING $speak object - just set voice, rate, and speak
            // This is MUCH faster than creating a new SpeechSynthesizer every time!
            String command = String.format("$speak.SelectVoice('%s');$speak.Rate=%d;$speak.Speak('%s');", voice, rate, escapedText);
            powershellWriter.println(command);

            logger.debug("✓ Sent TTS command to PowerShell: voice={}, rate={}, textLen={}", voice, rate, text.length());
        } catch (Exception e) {
            logger.error("Failed to speak text: {}", text, e);
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
