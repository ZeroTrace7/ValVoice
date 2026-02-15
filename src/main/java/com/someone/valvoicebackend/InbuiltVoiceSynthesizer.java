package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent PowerShell-based Windows voice synthesizer using System.Speech.
 * Routes TTS audio to VB-CABLE for Valorant voice chat integration.
 *
 * Phase 2: Voice Injection Core
 * - Validates SoundVolumeView.exe presence on startup
 * - Validates VB-Cable device availability
 * - Throws DependencyMissingException if critical dependencies are absent
 *
 * PHASE 2 SECURITY (VN-Parity):
 * - SoundVolumeView.exe executed via ABSOLUTE PATH only (no PATH lookup)
 * - Fixed path: %ProgramFiles%/ValVoice/SoundVolumeView.exe
 * - File existence check before execution (graceful degradation if missing)
 * - PowerShell executed via system binary name (OS resolves from System32)
 */
public class InbuiltVoiceSynthesizer {
    private static final Logger logger = LoggerFactory.getLogger(InbuiltVoiceSynthesizer.class);
    private Process powershellProcess;
    private PrintWriter powershellWriter;
    private BufferedReader powershellReader;
    private final List<String> voices = new ArrayList<>();
    private String soundVolumeViewPath;
    private boolean audioRoutingConfigured = false;

    /**
     * Exception thrown when a required dependency is missing.
     */
    public static class DependencyMissingException extends RuntimeException {
        private final String dependencyName;
        private final String installUrl;

        public DependencyMissingException(String dependencyName, String message, String installUrl) {
            super(message);
            this.dependencyName = dependencyName;
            this.installUrl = installUrl;
        }

        public String getDependencyName() { return dependencyName; }
        public String getInstallUrl() { return installUrl; }
    }

    /**
     * Initialize synthesizer with optional strict mode.
     * @param strictMode If true, throws DependencyMissingException when VB-Cable or SoundVolumeView.exe is missing
     */
    public InbuiltVoiceSynthesizer(boolean strictMode) {
        initializePowerShell();
        loadAvailableVoices();
        findSoundVolumeView();

        // Phase 2: Strict dependency validation
        if (strictMode) {
            validateDependencies();
        }

        routeAudioToVbCable();
    }

    public InbuiltVoiceSynthesizer() {
        this(false); // Non-strict mode for backward compatibility
    }

    /**
     * Phase 2: Validate critical dependencies for voice injection.
     * VN-parity: Only VB-Cable is a hard dependency. SoundVolumeView.exe is optional.
     * If SoundVolumeView.exe is missing, audio routing is disabled but TTS still works.
     */
    private void validateDependencies() {
        // VN-parity: SoundVolumeView.exe is NOT a hard dependency
        // Log warning but continue - TTS still works, just audio routing disabled
        if (soundVolumeViewPath == null) {
            logger.warn("[AudioRouting] Disabled: SoundVolumeView.exe not found at %ProgramFiles%/ValVoice/SoundVolumeView.exe");
            logger.warn("[AudioRouting] TTS will play through default speakers instead of VB-Cable");
            // Do NOT throw - graceful degradation per VN architecture
        }

        // Check VB-Cable device - this IS a hard dependency for voice injection
        if (!isVbCableInstalled()) {
            logger.error("FATAL: VB-Audio Virtual Cable not detected - cannot inject voice into game");
            throw new DependencyMissingException(
                "VB-Audio Virtual Cable",
                "VB-Audio Virtual Cable is required for voice injection.\n\n" +
                "Please install VB-Cable from: https://vb-audio.com/Cable/\n" +
                "After installation, restart your computer and try again.",
                "https://vb-audio.com/Cable/"
            );
        }
    }

    /**
     * Phase 2: Detect if VB-Cable is installed by querying Windows audio devices.
     */
    private boolean isVbCableInstalled() {
        if (powershellWriter == null || powershellProcess == null || !powershellProcess.isAlive()) {
            logger.warn("PowerShell not available for VB-Cable detection");
            return false;
        }

        try {
            String sentinel = "VBCABLE_CHECK_" + System.currentTimeMillis();
            String command = "Get-CimInstance Win32_SoundDevice | Select-Object -ExpandProperty Name; echo '" + sentinel + "'";
            powershellWriter.println(command);

            StringBuilder buffer = new StringBuilder();
            long startTime = System.currentTimeMillis();
            long timeoutMs = 10000;

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (powershellReader.ready()) {
                    int ch = powershellReader.read();
                    if (ch == -1) break;
                    if (ch == '\n' || ch == '\r') {
                        String line = buffer.toString().trim();
                        if (line.equals(sentinel)) {
                            return false; // Finished without finding VB-Cable
                        }
                        String lower = line.toLowerCase();
                        if (lower.contains("vb-audio") || lower.contains("cable input") || lower.contains("cable output")) {
                            // Consume remaining output until sentinel
                            consumeUntilSentinel(sentinel, timeoutMs - (System.currentTimeMillis() - startTime));
                            logger.info("VB-Cable detected: {}", line);
                            return true;
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
            logger.debug("VB-Cable detection error: {}", e.getMessage());
        }
        return false;
    }

    private void consumeUntilSentinel(String sentinel, long timeoutMs) {
        try {
            StringBuilder buffer = new StringBuilder();
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (powershellReader.ready()) {
                    int ch = powershellReader.read();
                    if (ch == -1) break;
                    if (ch == '\n' || ch == '\r') {
                        if (buffer.toString().trim().equals(sentinel)) return;
                        buffer.setLength(0);
                    } else {
                        buffer.append((char) ch);
                    }
                } else {
                    Thread.sleep(20);
                }
            }
        } catch (Exception ignored) {}
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

    /**
     * PHASE 2 SECURITY: Absolute path enforcement for SoundVolumeView.exe.
     * VN-parity: Uses %ProgramFiles%/ValVoice/SoundVolumeView.exe exclusively.
     * No PATH search, no dynamic discovery - matches ValorantNarrator exactly.
     *
     * Guardrail: Checks file existence before setting path.
     * If missing → soundVolumeViewPath remains null → graceful degradation.
     */
    private void findSoundVolumeView() {
        // PHASE 2 SECURITY: Fixed absolute path - no PATH lookup
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles == null) {
            logger.warn("[AudioRouting] ProgramFiles environment variable not set");
            soundVolumeViewPath = null;
            return;
        }

        String fileLocation = programFiles.replace("\\", "/") + "/ValVoice/SoundVolumeView.exe";
        File exeFile = new File(fileLocation);

        // PHASE 2 SECURITY: Existence check before storing path
        if (exeFile.exists() && exeFile.isFile()) {
            soundVolumeViewPath = fileLocation;
            logger.info("[AudioRouting] SoundVolumeView.exe found at absolute path: {}", fileLocation);
        } else {
            soundVolumeViewPath = null;
            logger.warn("[AudioRouting] SoundVolumeView.exe not found at {}", fileLocation);
            logger.warn("[AudioRouting] Audio routing disabled - TTS will play through default speakers");
        }
    }

    /**
     * PHASE 2 SECURITY: Route PowerShell TTS child process audio to VB-CABLE Input.
     * VN-parity: Uses absolute path from findSoundVolumeView() - never relies on PATH.
     * Command format: SoundVolumeView.exe /SetAppDefault "CABLE Input" all PID
     *
     * Guardrail: If soundVolumeViewPath is null (tool missing), logs warning and returns.
     * TTS continues to work but audio plays through default device instead of VB-Cable.
     */
    private void routeAudioToVbCable() {
        // PHASE 2 SECURITY: Graceful degradation if tool missing
        if (soundVolumeViewPath == null) {
            logger.warn("[AudioRouting] Skipping audio routing: SoundVolumeView.exe not available");
            audioRoutingConfigured = false;
            return;
        }

        if (powershellProcess == null || !powershellProcess.isAlive()) {
            logger.warn("[AudioRouting] Cannot route TTS audio: PowerShell process not running");
            audioRoutingConfigured = false;
            return;
        }

        try {
            long pid = powershellProcess.pid();

            // PHASE 2 SECURITY: Execute using absolute path (soundVolumeViewPath already validated)
            // Command format: SoundVolumeView.exe /SetAppDefault "CABLE Input" all PID
            String command = soundVolumeViewPath + " /SetAppDefault \"CABLE Input\" all " + pid;
            logger.debug("[AudioRouting] Executing (absolute path): {}", soundVolumeViewPath);

            Process routeProcess = Runtime.getRuntime().exec(command);
            int exitCode = routeProcess.waitFor();

            if (exitCode == 0) {
                logger.info("[AudioRouting] ✓ PowerShell TTS (PID {}) routed to CABLE Input", pid);
                audioRoutingConfigured = true;
            } else {
                logger.warn("[AudioRouting] SoundVolumeView returned exit code {} - routing may have failed", exitCode);
                audioRoutingConfigured = false;
            }

            // Configure VB-CABLE listen-through (so user can hear their own TTS)
            executeAndLog(soundVolumeViewPath, "/SetPlaybackThroughDevice", "CABLE Output", "Default Playback Device");
            executeAndLog(soundVolumeViewPath, "/SetListenToThisDevice", "CABLE Output", "1");
            executeAndLog(soundVolumeViewPath, "/unmute", "CABLE Output");

            logger.info("[AudioRouting] ✓ VB-CABLE listen-through configured");

        } catch (Exception e) {
            // PHASE 2 SECURITY: Graceful degradation - log and continue, do not crash
            logger.error("[AudioRouting] Failed to route TTS audio: {}", e.getMessage());
            audioRoutingConfigured = false;
        }
    }

    private void executeAndLog(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.start().waitFor();
        } catch (Exception e) {
            logger.debug("Command failed: {}", String.join(" ", command));
        }
    }

    /**
     * Check if audio routing was successfully configured.
     */
    public boolean isAudioRoutingConfigured() {
        return audioRoutingConfigured;
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
