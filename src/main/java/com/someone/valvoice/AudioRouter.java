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
            return false;
        }

        logger.info("VB-CABLE detected and available for TTS routing");
        logger.info("Audio routing will be handled per-TTS-process (Java app audio stays on default device)");

        // Success - VB-CABLE is available
        // Actual routing happens in InbuiltVoiceSynthesizer when PowerShell process starts
        routingSuccessful = true;

        logger.info("\u2713 VB-CABLE ready for TTS routing (system audio unchanged)");
        return routingSuccessful;
    }

    /**
     * Check if VB-Audio Virtual Cable is installed by querying audio devices.
     */
    public static boolean isVbCableInstalled() {
        String psScript =
            "$ErrorActionPreference='SilentlyContinue'; " +
            "Get-CimInstance Win32_SoundDevice | Select-Object -ExpandProperty Name";
        try {
            String result = executePowerShell(psScript, 8);
            if (result != null) {
                String lower = result.toLowerCase();
                boolean found = (lower.contains("vb-audio") && lower.contains("cable")) ||
                                lower.contains("cable input") ||
                                lower.contains("cable output");
                if (found) {
                    logger.debug("VB-CABLE detected via Win32_SoundDevice");
                    return true;
                }
            }
            logger.debug("VB-CABLE not found in sound devices");
            return false;
        } catch (Exception e) {
            logger.debug("VB-CABLE detection failed", e);
            return false;
        }
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

