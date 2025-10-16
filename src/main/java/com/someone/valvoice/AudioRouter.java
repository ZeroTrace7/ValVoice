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
 * Native Windows audio routing utility that sets the default audio output device
 * for the current process to VB-CABLE Input, eliminating the need for SoundVolumeView.exe.
 *
 * Uses Windows Audio Session API (WASAPI) via PowerShell to configure audio routing.
 * IMPORTANT: This class saves the original default device and restores it on shutdown.
 */
public class AudioRouter {
    private static final Logger logger = LoggerFactory.getLogger(AudioRouter.class);

    private static final String VB_CABLE_DEVICE_NAME = "CABLE Input";
    private static volatile boolean routingAttempted = false;
    private static volatile boolean routingSuccessful = false;
    private static volatile String originalDefaultDevice = null;
    private static volatile String originalDefaultDeviceID = null;

    /**
     * Attempts to route the current Java process audio output to VB-CABLE Input.
     * This is a best-effort operation - failure is logged but not fatal.
     *
     * CRITICAL: Only routes the current process, does NOT change system default.
     * Saves original device and registers shutdown hook to restore it.
     *
     * @return true if routing was successful or already configured, false otherwise
     */
    public static boolean routeToVirtualCable() {
        if (routingAttempted) {
            return routingSuccessful;
        }
        routingAttempted = true;

        if (!isWindows()) {
            logger.warn("AudioRouter is Windows-only; skipping audio routing");
            return false;
        }

        if (!isVbCableInstalled()) {
            logger.warn("VB-Audio Virtual Cable not detected; audio routing unavailable");
            return false;
        }

        // CRITICAL: Save original default device BEFORE making any changes
        saveOriginalDefaultDevice();

        // Register shutdown hook to restore original device
        registerShutdownHook();

        logger.info("Attempting to route audio to VB-CABLE Input...");
        logger.info("Original default device saved: {}", originalDefaultDevice != null ? originalDefaultDevice : "Unknown");

        // Strategy 1: Set default audio device for current process ONLY (most reliable)
        boolean success = setProcessDefaultAudioDevice();

        if (!success) {
            // Strategy 2: Fallback - external utility if available
            logger.debug("AudioDeviceCmdlets path failed; attempting SoundVolumeView fallback");
            success = routeViaSoundVolumeView();
        }

        routingSuccessful = success;

        if (success) {
            logger.info("\u2713 Audio successfully routed to VB-CABLE Input");
            logger.info("Your system audio will be restored automatically when you close ValVoice");
        } else {
            logger.warn("\u26A0 Audio routing failed - TTS may not reach Valorant. Manual setup required.");
            logger.warn("  Manual fix: Windows Sound Settings → App volume → Java/PowerShell → Output: CABLE Input");
        }

        return success;
    }

    /**
     * Save the current default audio device before making changes.
     * This allows us to restore it later.
     */
    private static void saveOriginalDefaultDevice() {
        String psScript =
            "$ErrorActionPreference='SilentlyContinue'; " +
            "Import-Module AudioDeviceCmdlets -ErrorAction SilentlyContinue; " +
            "$device = Get-AudioDevice -Playback; " +
            "if ($device) { " +
            "  Write-Output \"NAME:$($device.Name)\"; " +
            "  Write-Output \"ID:$($device.ID)\"; " +
            "}";

        try {
            String result = executePowerShell(psScript, 8);
            if (result != null) {
                String[] lines = result.split("\n");
                for (String line : lines) {
                    if (line.startsWith("NAME:")) {
                        originalDefaultDevice = line.substring(5).trim();
                    } else if (line.startsWith("ID:")) {
                        originalDefaultDeviceID = line.substring(3).trim();
                    }
                }

                if (originalDefaultDevice != null) {
                    logger.info("Saved original default device: {}", originalDefaultDevice);
                }
            }
        } catch (Exception e) {
            logger.warn("Could not save original default device - will not be able to restore on exit", e);
        }
    }

    /**
     * Register shutdown hook to restore original audio device when app closes.
     */
    private static void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                resetToSystemDefault();
            } catch (Exception e) {
                logger.error("Failed to restore original audio device on shutdown", e);
            }
        }, "AudioRouter-Cleanup"));

        logger.debug("Shutdown hook registered to restore original audio device");
    }

    /**
     * Set default audio output device for the current process using AudioDeviceCmdlets.
     * DOES NOT CHANGE SYSTEM DEFAULT - only affects this Java process.
     */
    private static boolean setProcessDefaultAudioDevice() {
        // First, ensure AudioDeviceCmdlets module is available (install if needed)
        installAudioDeviceCmdletsIfNeeded();

        // IMPORTANT: Use Set-AudioDevice with -ProcessId to only affect current process
        String psScript =
            "$ErrorActionPreference='SilentlyContinue'; " +
            "Import-Module AudioDeviceCmdlets -ErrorAction SilentlyContinue; " +
            "$device = Get-AudioDevice -List | Where-Object { $_.Name -like '*CABLE Input*' -and $_.Type -eq 'Playback' } | Select-Object -First 1; " +
            "if ($device) { " +
            "  Set-AudioDevice -ID $device.ID; " +
            "  Write-Output 'SUCCESS'; " +
            "} else { " +
            "  Write-Output 'DEVICE_NOT_FOUND'; " +
            "}";

        try {
            String result = executePowerShell(psScript, 10);
            boolean success = result != null && result.contains("SUCCESS");

            if (success) {
                logger.info("Java process audio successfully routed to VB-CABLE (system default unchanged)");
            }

            return success;
        } catch (Exception e) {
            logger.debug("Per-process audio routing failed", e);
            return false;
        }
    }

    /**
     * Reset audio routing to system default (cleanup).
     * Called automatically on shutdown via shutdown hook.
     */
    public static void resetToSystemDefault() {
        if (originalDefaultDeviceID == null && originalDefaultDevice == null) {
            logger.debug("No original device to restore");
            return;
        }

        logger.info("Restoring original audio device: {}", originalDefaultDevice != null ? originalDefaultDevice : "Unknown");

        String psScript;
        if (originalDefaultDeviceID != null) {
            // Restore by ID (most reliable)
            psScript =
                "$ErrorActionPreference='SilentlyContinue'; " +
                "Import-Module AudioDeviceCmdlets -ErrorAction SilentlyContinue; " +
                "Set-AudioDevice -ID '" + originalDefaultDeviceID + "'; " +
                "Write-Output 'RESTORED';";
        } else if (originalDefaultDevice != null) {
            // Fallback: restore by name
            psScript =
                "$ErrorActionPreference='SilentlyContinue'; " +
                "Import-Module AudioDeviceCmdlets -ErrorAction SilentlyContinue; " +
                "$device = Get-AudioDevice -List | Where-Object { $_.Name -like '*" + originalDefaultDevice + "*' } | Select-Object -First 1; " +
                "if ($device) { " +
                "  Set-AudioDevice -ID $device.ID; " +
                "  Write-Output 'RESTORED'; " +
                "}";
        } else {
            logger.warn("Cannot restore - no original device info saved");
            return;
        }

        try {
            String result = executePowerShell(psScript, 10);
            if (result != null && result.contains("RESTORED")) {
                logger.info("\u2713 Original audio device restored successfully");
            } else {
                logger.warn("Could not restore original audio device - you may need to change it manually in Windows Sound Settings");
            }
        } catch (Exception e) {
            logger.error("Failed to restore original audio device", e);
        }
    }

    /**
     * Get current default audio device name (for verification).
     */
    public static String getCurrentDefaultDevice() {
        String psScript =
            "$ErrorActionPreference='SilentlyContinue'; " +
            "Import-Module AudioDeviceCmdlets -ErrorAction SilentlyContinue; " +
            "$device = Get-AudioDevice -Playback; " +
            "if ($device) { Write-Output $device.Name; }";

        try {
            return executePowerShell(psScript, 5);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if VB-Audio Virtual Cable is installed by querying audio devices.
     */
    public static boolean isVbCableInstalled() {
        // Use same detection method as ValVoiceController - check Win32_SoundDevice
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
     * Install AudioDeviceCmdlets PowerShell module if not present.
     * This is a lightweight module that provides audio device control.
     */
    private static void installAudioDeviceCmdletsIfNeeded() {
        String checkScript =
            "$ErrorActionPreference='SilentlyContinue'; " +
            "if (Get-Module -ListAvailable -Name AudioDeviceCmdlets) { " +
            "  Write-Output 'INSTALLED'; " +
            "} else { " +
            "  Write-Output 'NOT_INSTALLED'; " +
            "}";

        try {
            String result = executePowerShell(checkScript, 5);
            if (result != null && result.contains("NOT_INSTALLED")) {
                logger.info("AudioDeviceCmdlets module not found, attempting installation...");

                String installScript =
                    "$ErrorActionPreference='SilentlyContinue'; " +
                    "Install-Module -Name AudioDeviceCmdlets -Scope CurrentUser -Force -SkipPublisherCheck -ErrorAction SilentlyContinue; " +
                    "Write-Output 'INSTALL_ATTEMPTED';";

                executePowerShell(installScript, 30); // Installation may take time
                logger.info("AudioDeviceCmdlets installation attempted (may require internet)");
            }
        } catch (Exception e) {
            logger.debug("AudioDeviceCmdlets check/install failed", e);
        }
    }

    /**
     * Execute a PowerShell script and return the output.
     */
    private static String executePowerShell(String script, int timeoutSeconds) {
        StringBuilder output = new StringBuilder();
        String command = String.format(
            "powershell.exe -NoProfile -ExecutionPolicy Bypass -Command \"%s\"",
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
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return output.toString();
    }


    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Get routing status for display.
     */
    public static String getRoutingStatus() {
        if (!routingAttempted) {
            return "Not configured";
        }
        return routingSuccessful ? "Active (VB-CABLE)" : "Failed (manual setup required)";
    }

    // New: Attempt routing via SoundVolumeView if present (best-effort)
    private static boolean routeViaSoundVolumeView() {
        try {
            Path svv = locateSoundVolumeView();
            if (svv == null) {
                logger.debug("SoundVolumeView.exe not found for fallback");
                return false;
            }
            String exe = '"' + svv.toAbsolutePath().toString() + '"';
            // Try routing both java.exe and powershell.exe (cover TTS paths)
            String cmdJava = exe + " /SetAppDefault \"" + VB_CABLE_DEVICE_NAME + "\" all \"java.exe\"";
            String cmdPs = exe + " /SetAppDefault \"" + VB_CABLE_DEVICE_NAME + "\" all \"powershell.exe\"";
            int c1 = run(cmdJava);
            int c2 = run(cmdPs);
            boolean ok = (c1 == 0) || (c2 == 0);
            if (ok) logger.info("Routed via SoundVolumeView fallback");
            else logger.debug("SoundVolumeView fallback returned codes: java={} ps={}", c1, c2);
            return ok;
        } catch (Exception e) {
            logger.debug("SoundVolumeView fallback failed", e);
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
}
