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

    /**
     * Speak text using Windows TTS. This method BLOCKS until speech is complete.
     * Uses a sentinel marker to know when PowerShell has finished speaking.
     */
    public void speakInbuiltVoice(String voice, String text, short rate) {
        speakInbuiltVoiceBlocking(voice, text, rate);
    }

    /**
     * Speak text and BLOCK until speech completes.
     * This is critical for proper Push-to-Talk key timing.
     */
    public void speakInbuiltVoiceBlocking(String voice, String text, short rate) {
        rate = (short) (rate / 10.0 - 10);

        try {
            // Escape single quotes for PowerShell
            String escapedText = text.replace("'", "''");

            // Generate unique sentinel to detect completion
            String sentinel = "TTS_DONE_" + System.currentTimeMillis();

            // Create speech command with error handling that outputs sentinel when done
            // The try-finally in PowerShell ensures sentinel is ALWAYS written
            // $speak.Speak() is SYNCHRONOUS in PowerShell - it blocks until speech completes
            String command = String.format(
                "try { " +
                "Add-Type -AssemblyName System.Speech; " +
                "$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                "$speak.SelectVoice('%s'); " +
                "$speak.Rate = %d; " +
                "$speak.Speak('%s') " +
                "} catch { Write-Error $_.Exception.Message } finally { Write-Output '%s' }",
                voice, rate, escapedText, sentinel);

            powershellWriter.println(command);

            logger.info("🔊 Speaking: voice='{}', rate={}, text='{}'", voice, rate,
                text.length() > 50 ? text.substring(0, 47) + "..." : text);

            // Wait for the sentinel to appear in output (speech completed)
            long startTime = System.currentTimeMillis();
            long maxWaitMs = Math.max(30000, text.length() * 200); // At least 30s, or 200ms per char

            StringBuilder lineBuffer = new StringBuilder();
            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                // Check if data is available (non-blocking)
                if (powershellReader.ready()) {
                    int ch = powershellReader.read();
                    if (ch == -1) break; // EOF
                    if (ch == '\n' || ch == '\r') {
                        String line = lineBuffer.toString();
                        lineBuffer.setLength(0);
                        if (line.contains(sentinel)) {
                            long elapsed = System.currentTimeMillis() - startTime;
                            logger.debug("✅ TTS completed in {}ms", elapsed);
                            return;
                        }
                    } else {
                        lineBuffer.append((char) ch);
                    }
                } else {
                    // No data available, sleep briefly to avoid busy-waiting
                    Thread.sleep(50);
                }
            }

            // Timeout reached
            logger.warn("⚠ TTS timeout after {}ms - continuing anyway", maxWaitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("TTS interrupted");
        } catch (Exception e) {
            logger.error("Failed to speak text", e);
        }
    }

    /**
     * Estimate speech duration based on text length and rate.
     * Used as fallback if blocking read fails.
     * @param text The text to speak
     * @param rate The speech rate (-10 to 10 scale)
     * @return Estimated duration in milliseconds
     */
    public long estimateSpeechDuration(String text, short rate) {
        if (text == null || text.isEmpty()) return 0;

        // Average speaking rate is ~150 words per minute at rate=0
        // Rate -10 is slowest, +10 is fastest
        // Base: ~100ms per character at rate 0
        double baseMs = 100.0;

        // Adjust for rate: each rate unit changes speed by ~10%
        // rate < 0 = slower, rate > 0 = faster
        double rateMultiplier = 1.0 - (rate * 0.1);
        rateMultiplier = Math.max(0.3, Math.min(2.0, rateMultiplier)); // Clamp to reasonable range

        long estimatedMs = (long) (text.length() * baseMs * rateMultiplier);

        // Add buffer for speech synthesis startup
        return estimatedMs + 500;
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
