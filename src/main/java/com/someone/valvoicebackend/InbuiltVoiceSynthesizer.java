package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent PowerShell-based Windows voice synthesizer using System.Speech.
 * Routes TTS audio to VB-CABLE for Valorant voice chat integration.
 */
public class InbuiltVoiceSynthesizer {
    private static final Logger logger = LoggerFactory.getLogger(InbuiltVoiceSynthesizer.class);
    private Process powershellProcess;
    private PrintWriter powershellWriter;
    private BufferedReader powershellReader;
    private final List<String> voices = new ArrayList<>();
    private String soundVolumeViewPath;

    public InbuiltVoiceSynthesizer() {
        initializePowerShell();
        loadAvailableVoices();
        findSoundVolumeView();
        routeAudioToVbCable();
    }

    private void initializePowerShell() {
        try {
            powershellProcess = new ProcessBuilder("powershell.exe", "-NoExit", "-Command", "-").start();
            powershellWriter = new PrintWriter(new OutputStreamWriter(powershellProcess.getOutputStream()), true);
            powershellReader = new BufferedReader(new InputStreamReader(powershellProcess.getInputStream()));
        } catch (IOException e) {
            logger.error("Failed to start PowerShell process", e);
        }
    }

    private void loadAvailableVoices() {
        if (powershellWriter == null) return;

        try {
            String command = "Add-Type -AssemblyName System.Speech;" +
                "$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer;" +
                "$speak.GetInstalledVoices() | Select-Object -ExpandProperty VoiceInfo | " +
                "Select-Object -Property Name | ConvertTo-Csv -NoTypeInformation | " +
                "Select-Object -Skip 1; echo 'END_OF_VOICES'";
            powershellWriter.println(command);

            String line;
            while ((line = powershellReader.readLine()) != null) {
                if (line.trim().equals("END_OF_VOICES")) break;
                if (!line.trim().isEmpty()) {
                    voices.add(line.replace("\"", "").trim());
                }
            }

            if (voices.isEmpty()) {
                logger.warn("No TTS voices found");
            } else {
                logger.info("Found {} TTS voices", voices.size());
            }
        } catch (Exception e) {
            logger.error("Failed to load voices", e);
        }
    }

    private void findSoundVolumeView() {
        String[] paths = {
            System.getProperty("user.dir") + "/SoundVolumeView.exe",
            System.getenv("ProgramFiles") + "/ValorantNarrator/SoundVolumeView.exe",
            System.getenv("ProgramFiles") + "/ValVoice/SoundVolumeView.exe"
        };

        for (String path : paths) {
            if (path != null && new File(path).exists()) {
                soundVolumeViewPath = path;
                logger.debug("Found SoundVolumeView.exe at: {}", path);
                return;
            }
        }
        logger.warn("SoundVolumeView.exe not found - TTS will use default audio device");
    }

    private void routeAudioToVbCable() {
        if (soundVolumeViewPath == null || powershellProcess == null) return;

        try {
            long pid = powershellProcess.pid();

            // Route PowerShell to VB-CABLE Input
            Runtime.getRuntime().exec(soundVolumeViewPath + " /SetAppDefault \"CABLE Input\" all " + pid);

            // Configure VB-CABLE listen-through
            Runtime.getRuntime().exec(soundVolumeViewPath + " /SetPlaybackThroughDevice \"CABLE Output\" \"Default Playback Device\"");
            Runtime.getRuntime().exec(soundVolumeViewPath + " /SetListenToThisDevice \"CABLE Output\" 1");
            Runtime.getRuntime().exec(soundVolumeViewPath + " /unmute \"CABLE Output\"");

            logger.info("✓ Audio routed to VB-CABLE (PID {})", pid);
        } catch (IOException e) {
            logger.error("Failed to route audio: {}", e.getMessage());
        }
    }

    public List<String> getAvailableVoices() {
        return voices;
    }

    public boolean isReady() {
        return powershellProcess != null && powershellProcess.isAlive() && !voices.isEmpty();
    }

    /**
     * Speak text using Windows TTS. Blocks until speech completes.
     */
    public void speakInbuiltVoice(String voice, String text, short rate) {
        if (!isReady() || text == null || text.isEmpty()) return;

        // Convert rate from 0-100 UI scale to -10 to +10 SAPI scale
        short sapiRate = (short) (rate / 10.0 - 10);

        try {
            String escapedText = text.replace("'", "''");
            String sentinel = "TTS_DONE_" + System.currentTimeMillis();

            String command = String.format(
                "try { " +
                "Add-Type -AssemblyName System.Speech; " +
                "$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                "$speak.SelectVoice('%s'); " +
                "$speak.Rate = %d; " +
                "$speak.Speak('%s') " +
                "} catch { } finally { Write-Output '%s' }",
                voice, sapiRate, escapedText, sentinel);

            powershellWriter.println(command);
            logger.debug("Speaking: '{}' (voice={}, rate={})",
                text.length() > 50 ? text.substring(0, 47) + "..." : text, voice, sapiRate);

            // Wait for speech completion
            waitForSentinel(sentinel, text.length());
        } catch (Exception e) {
            logger.error("TTS failed: {}", e.getMessage());
        }
    }

    private void waitForSentinel(String sentinel, int textLength) {
        long maxWaitMs = Math.max(30000, textLength * 200);
        long startTime = System.currentTimeMillis();
        StringBuilder buffer = new StringBuilder();

        try {
            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                if (powershellReader.ready()) {
                    int ch = powershellReader.read();
                    if (ch == -1) break;
                    if (ch == '\n' || ch == '\r') {
                        if (buffer.toString().contains(sentinel)) {
                            logger.debug("TTS completed in {}ms", System.currentTimeMillis() - startTime);
                            return;
                        }
                        buffer.setLength(0);
                    } else {
                        buffer.append((char) ch);
                    }
                } else {
                    Thread.sleep(50);
                }
            }
        } catch (Exception e) {
            logger.debug("Error waiting for TTS: {}", e.getMessage());
        }
    }

    public void shutdown() {
        try {
            if (powershellWriter != null) {
                powershellWriter.println("exit");
                powershellWriter.close();
            }
            if (powershellReader != null) powershellReader.close();
            if (powershellProcess != null && powershellProcess.isAlive()) powershellProcess.destroy();
            logger.debug("TTS synthesizer shutdown complete");
        } catch (Exception e) {
            logger.debug("Error during shutdown", e);
        }
    }
}
