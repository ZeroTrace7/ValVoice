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
 * Routes ONLY PowerShell TTS to VB-CABLE Input using SoundVolumeView (per-app mapping).
 * Does NOT change system default device. Game audio stays on your normal output.
 * On shutdown, clears the per-app mapping and returns PowerShell to Default output.
 */
public class AudioRouter {
    private static final Logger logger = LoggerFactory.getLogger(AudioRouter.class);

    private static final String VB_CABLE_DEVICE_NAME = "CABLE Input";
    private static volatile boolean routingAttempted = false;
    private static volatile boolean routingSuccessful = false;

    /**
     * Attempts to route PowerShell TTS audio to VB-CABLE Input.
     * This is a best-effort operation - failure is logged but not fatal.
     *
     * CRITICAL: Only routes powershell.exe processes, does NOT change system default.
     * Your game audio will continue playing through your normal speakers/headphones!
     *
     * @return true if routing was successful or already configured, false otherwise
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

        logger.info("Attempting to route PowerShell TTS audio to VB-CABLE Input...");
        logger.info("Your game audio will remain on your normal speakers/headphones");

        boolean success = routeViaSoundVolumeView();
        routingSuccessful = success;

        if (success) {
            logger.info("\u2713 Audio successfully routed to VB-CABLE Input (TTS only)");
            // Register shutdown cleanup
            registerShutdownUnroute();
        } else {
            logger.warn("\u26A0 Audio routing failed - TTS may not reach Valorant. Manual setup required.");
            logger.warn("  Manual fix: Windows Sound Settings → App volume → PowerShell → Output: CABLE Input");
        }
        return success;
    }

    private static void registerShutdownUnroute() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                clearAppRouting();
            } catch (Exception e) {
                logger.debug("Failed to clear app-specific routing on shutdown", e);
            }
        }, "AudioRouter-Unroute"));
        logger.debug("Shutdown hook registered to clear app-specific routing");
    }

    /**
     * Clears app-specific routing for powershell.exe back to Default (if SVV is available).
     */
    public static void clearAppRouting() {
        try {
            Path svv = locateSoundVolumeView();
            if (svv == null) {
                logger.debug("SoundVolumeView.exe not found; cannot auto-clear routing");
                return;
            }
            String exe = '"' + svv.toAbsolutePath().toString() + '"';
            String cmd = exe + " /SetAppDefault \"Default\" all \"powershell.exe\"";
            int exit = run(cmd);
            if (exit == 0) {
                logger.info("\u2713 Cleared PowerShell app-specific routing (back to Default)");
            } else {
                logger.debug("Clear routing exit code: {}", exit);
            }
        } catch (Exception e) {
            logger.debug("Exception while clearing app-specific routing", e);
        }
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
        String command = String.format(
            "powershell.exe -WindowStyle Hidden -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"%s\"",
            script.replace("\"", "\\\"")
        );

        Process process = null;
        try {
            process = Runtime.getRuntime().exec(command);
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
        return routingSuccessful ? "Active (TTS → VB-CABLE)" : "Failed (manual setup required)";
    }

    /**
     * Route via SoundVolumeView - ONLY routes powershell.exe (best method!)
     * This leaves your game audio completely untouched.
     */
    private static boolean routeViaSoundVolumeView() {
        try {
            Path svv = locateSoundVolumeView();
            if (svv == null) {
                logger.debug("SoundVolumeView.exe not found");
                return false;
            }
            String exe = '"' + svv.toAbsolutePath().toString() + '"';
            String cmdPs = exe + " /SetAppDefault \"" + VB_CABLE_DEVICE_NAME + "\" all \"powershell.exe\"";
            int exitCode = run(cmdPs);
            boolean ok = (exitCode == 0);
            if (ok) {
                logger.info("PowerShell TTS routed to VB-CABLE via SoundVolumeView (game audio unchanged)");
            } else {
                logger.debug("SoundVolumeView returned exit code: {}", exitCode);
            }
            return ok;
        } catch (Exception e) {
            logger.debug("SoundVolumeView routing failed", e);
            return false;
        }
    }

    private static int run(String command) throws IOException, InterruptedException {
        Process p = Runtime.getRuntime().exec(command);
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    private static Path locateSoundVolumeView() {
        Path workingDir = Paths.get(System.getProperty("user.dir"));
        Path candidate = workingDir.resolve("SoundVolumeView.exe");
        if (Files.isRegularFile(candidate)) return candidate;
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            Path pf = Paths.get(programFiles, "ValVoice", "SoundVolumeView.exe");
            if (Files.isRegularFile(pf)) return pf;
            Path pfAlt = Paths.get(programFiles, "SoundVolumeView.exe");
            if (Files.isRegularFile(pfAlt)) return pfAlt;
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
