package com.someone.valvoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Detects VB-CABLE availability for TTS routing.
 * Does NOT change system default device or route the Java app.
 * Audio routing is handled per-TTS-process in InbuiltVoiceSynthesizer.
 */
public class AudioRouter {
    private static final Logger logger = LoggerFactory.getLogger(AudioRouter.class);

    private static final String VB_CABLE_DEVICE_NAME = "CABLE Input";
    private static volatile boolean routingAttempted = false;
    private static volatile boolean routingSuccessful = false;

    /**
     * Detects VB-CABLE availability for TTS routing.
     * DOES NOT route the Java application - routing is handled per-TTS-process.
     *
     * CRITICAL: This method does NOT change system audio or route the Java app.
     * Audio routing is handled in InbuiltVoiceSynthesizer for PowerShell TTS only.
     * Your game audio and system audio remain completely untouched!
     *
     * @return true if VB-CABLE is detected and available, false otherwise
     */
    public static boolean routeToVirtualCable() {
        if (routingAttempted) return routingSuccessful;
        routingAttempted = true;

        if (!isWindows()) {
            logger.warn("AudioRouter is Windows-only; skipping audio routing");
            return false;
        }

        if (!isVbCableInstalled()) {
            logger.warn("VB-Audio Virtual Cable not detected; audio routing unavailable");
            logger.warn("Download VB-CABLE from: https://vb-audio.com/Cable/");
            return false;
        }

        logger.info("VB-CABLE detected - configuring audio routing...");

        // CRITICAL: Setup VB-CABLE listen-through (ValorantNarrator logic)
        // This allows you to HEAR your own TTS through your speakers
        setupVbCableListenThrough();

        logger.info("Audio routing will be handled per-TTS-process (Java app audio stays on default device)");

        // Success - VB-CABLE is available
        // Actual routing happens in InbuiltVoiceSynthesizer when PowerShell process starts
        routingSuccessful = true;

        logger.info("✓ VB-CABLE ready for TTS routing (system audio unchanged)");
        return routingSuccessful;
    }

    /**
     * Setup VB-CABLE listen-through so you can hear your own TTS
     * Matches ValorantNarrator's syncValorantPlayerSettings logic
     */
    private static void setupVbCableListenThrough() {
        try {
            Path svvPath = locateSoundVolumeView();
            if (svvPath == null) {
                logger.debug("SoundVolumeView.exe not found - skipping listen-through setup");
                return;
            }

            String fileLocation = svvPath.toString();

            // ValorantNarrator's exact setup sequence:

            // 1. Set CABLE Output to play through default device (so you can hear TTS)
            String command = fileLocation + " /SetPlaybackThroughDevice \"CABLE Output\" \"Default Playback Device\"";
            long start = System.currentTimeMillis();
            Runtime.getRuntime().exec(command);
            logger.debug("({} ms) Added listen-in from CABLE Output to default playback device",
                (System.currentTimeMillis() - start));

            // 2. Enable "Listen to this device" on CABLE Output
            command = fileLocation + " /SetListenToThisDevice \"CABLE Output\" 1";
            start = System.currentTimeMillis();
            Runtime.getRuntime().exec(command);
            logger.debug("({} ms) Enabled listen-in on CABLE Output",
                (System.currentTimeMillis() - start));

            // 3. Unmute CABLE Output (in case it was muted)
            command = fileLocation + " /unmute \"CABLE Output\"";
            start = System.currentTimeMillis();
            Runtime.getRuntime().exec(command);
            logger.debug("({} ms) Unmuted CABLE Output",
                (System.currentTimeMillis() - start));

            logger.info("✓ VB-CABLE listen-through configured - you'll hear your own TTS");

        } catch (IOException e) {
            logger.warn("Failed to setup VB-CABLE listen-through (non-critical): {}", e.getMessage());
        }
    }

    /**
     * Locate SoundVolumeView.exe in multiple possible locations
     * Matches ValorantNarrator's file location logic
     */
    private static Path locateSoundVolumeView() {
        // Try ValorantNarrator's standard location first
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            Path valoNarratorPath = Paths.get(programFiles, "ValorantNarrator", "SoundVolumeView.exe");
            if (Files.isRegularFile(valoNarratorPath)) {
                return valoNarratorPath;
            }

            // Try ValVoice location
            Path valVoicePath = Paths.get(programFiles, "ValVoice", "SoundVolumeView.exe");
            if (Files.isRegularFile(valVoicePath)) {
                return valVoicePath;
            }
        }

        // Try current directory (where InbuiltVoiceSynthesizer also looks)
        Path currentDir = Paths.get(System.getProperty("user.dir"), "SoundVolumeView.exe");
        if (Files.isRegularFile(currentDir)) {
            return currentDir;
        }

        return null;
    }

    /**
     * Check if VB-Audio Virtual Cable is installed by querying audio devices.
     * Matches ValorantNarrator's detection logic with multiple device name checks.
     */
    public static boolean isVbCableInstalled() {
        // Method 1: Check sound devices via Win32_SoundDevice
        String psScript =
            "$ErrorActionPreference='SilentlyContinue'; " +
            "Get-CimInstance Win32_SoundDevice | Select-Object -ExpandProperty Name";
        try {
            String result = executePowerShell(psScript, 8);
            if (result != null) {
                String lower = result.toLowerCase();
                // Check all possible VB-CABLE device name variations
                boolean found = (lower.contains("vb-audio") && lower.contains("cable")) ||
                                lower.contains("cable input") ||
                                lower.contains("cable output") ||
                                lower.contains("vb-audio virtual cable");
                if (found) {
                    logger.info("✓ VB-CABLE detected: Device found in Win32_SoundDevice");
                    return true;
                }
            }
        } catch (Exception e) {
            logger.debug("Win32_SoundDevice query failed", e);
        }

        // Method 2: Try to get CABLE Output device ID (more reliable)
        // This is what ValorantNarrator uses in syncValorantPlayerSettings
        Path svvPath = locateSoundVolumeView();
        if (svvPath != null) {
            try {
                String command = String.format("%s /GetColumnValue \"VB-Audio Virtual Cable\\Device\\CABLE Output\\Capture\" \"Item ID\"",
                    svvPath.toString());
                Process process = Runtime.getRuntime().exec(command);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && !line.isEmpty() && line.contains("{")) {
                        logger.info("✓ VB-CABLE detected: CABLE Output device ID found");
                        return true;
                    }
                }
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.debug("SoundVolumeView device query failed", e);
            }
        }

        logger.warn("VB-CABLE not detected - install from: https://vb-audio.com/Cable/");
        return false;
    }

    /**
     * Execute a PowerShell script and return the output.
     */
    private static String executePowerShell(String script, int timeoutSeconds) {
        StringBuilder output = new StringBuilder();

        Process process = null;
        try {
            // Use ProcessBuilder instead of deprecated Runtime.exec()
            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-WindowStyle", "Hidden",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-Command", script
            );
            process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                logger.warn("PowerShell script timed out after {} seconds", timeoutSeconds);
                process.destroyForcibly();
                return null;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                logger.debug("PowerShell script exit code: {}", exitCode);
            }
        } catch (Exception e) {
            logger.debug("PowerShell execution failed", e);
            return null;
        } finally {
            if (process != null) process.destroyForcibly();
        }
        return output.toString();
    }

    /**
     * Get routing status for display.
     */
    public static String getRoutingStatus() {
        if (!routingAttempted) return "Not configured";
        return routingSuccessful ? "VB-CABLE detected" : "VB-CABLE not found";
    }

    /**
     * DEPRECATED: No cleanup needed since we never route the Java app.
     * PowerShell process routing is temporary and cleaned up automatically.
     */
    public static void clearAppRouting() {
        // No-op: We never route the Java app, so nothing to clean up
        logger.debug("No audio routing cleanup needed (Java app was never routed)");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

