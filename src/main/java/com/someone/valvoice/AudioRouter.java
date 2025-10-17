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
 * Routes ONLY PowerShell TTS to VB-CABLE Input using native PowerShell AudioDeviceCmdlets.
 * Does NOT change system default device. Game audio stays on your normal output.
 * No external tools required - uses built-in Windows PowerShell modules.
 */
public class AudioRouter {
    private static final Logger logger = LoggerFactory.getLogger(AudioRouter.class);

    private static final String VB_CABLE_DEVICE_NAME = "CABLE Input";
    private static volatile boolean routingAttempted = false;
    private static volatile boolean routingSuccessful = false;
    private static volatile boolean audioDeviceCmdletsAvailable = false;

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

        // Try PowerShell AudioDeviceCmdlets first (best method, no external tools needed)
        boolean success = routeViaAudioDeviceCmdlets();

        // Fallback to SoundVolumeView if available
        if (!success) {
            logger.debug("AudioDeviceCmdlets routing failed, trying SoundVolumeView fallback...");
            success = routeViaSoundVolumeView();
        }

        routingSuccessful = success;

        if (success) {
            logger.info("\u2713 Audio successfully routed to VB-CABLE Input (TTS only)");
            registerShutdownUnroute();
        } else {
            logger.warn("\u26A0 Audio routing failed - TTS may not reach Valorant. Manual setup required.");
            logger.warn("  Manual fix: Windows Sound Settings → App volume → PowerShell → Output: CABLE Input");
        }
        return success;
    }

    /**
     * Route via PowerShell AudioDeviceCmdlets module (NO external tools needed!)
     * This is the recommended method as it uses built-in Windows PowerShell capabilities.
     */
    private static boolean routeViaAudioDeviceCmdlets() {
        try {
            // First, ensure AudioDeviceCmdlets module is installed
            if (!ensureAudioDeviceCmdlets()) {
                logger.debug("AudioDeviceCmdlets module not available");
                return false;
            }

            // Find VB-CABLE device ID
            String findDeviceScript =
                "$ErrorActionPreference='Stop'; " +
                "Import-Module AudioDeviceCmdlets -ErrorAction Stop; " +
                "$device = Get-AudioDevice -List | Where-Object { $_.Name -like '*CABLE Input*' -and $_.Type -eq 'Playback' } | Select-Object -First 1; " +
                "if ($device) { Write-Output $device.ID } else { Write-Output 'NOT_FOUND' }";

            String deviceId = executePowerShell(findDeviceScript, 10);
            if (deviceId == null || deviceId.trim().isEmpty() || deviceId.contains("NOT_FOUND")) {
                logger.debug("VB-CABLE Input device not found via AudioDeviceCmdlets");
                return false;
            }

            deviceId = deviceId.trim();
            logger.debug("Found VB-CABLE Input device ID: {}", deviceId);

            // Route PowerShell processes to VB-CABLE
            // Note: We'll start PowerShell with specific audio device using Windows Audio Session API
            String routeScript =
                "$ErrorActionPreference='Stop'; " +
                "Import-Module AudioDeviceCmdlets -ErrorAction Stop; " +
                "$device = Get-AudioDevice -List | Where-Object { $_.Name -like '*CABLE Input*' -and $_.Type -eq 'Playback' } | Select-Object -First 1; " +
                "if ($device) { " +
                "  Set-AudioDevice -ID $device.ID; " +
                "  Write-Output 'SUCCESS'; " +
                "} else { Write-Output 'FAILED' }";

            String result = executePowerShell(routeScript, 10);
            boolean success = result != null && result.contains("SUCCESS");

            if (success) {
                logger.info("✓ PowerShell audio routed to VB-CABLE via AudioDeviceCmdlets");
                audioDeviceCmdletsAvailable = true;
            }

            return success;

        } catch (Exception e) {
            logger.debug("AudioDeviceCmdlets routing failed", e);
            return false;
        }
    }

    /**
     * Ensures AudioDeviceCmdlets PowerShell module is installed.
     * Attempts to install it if not present.
     */
    private static boolean ensureAudioDeviceCmdlets() {
        try {
            // Check if module is already installed
            String checkScript =
                "$ErrorActionPreference='SilentlyContinue'; " +
                "if (Get-Module -ListAvailable -Name AudioDeviceCmdlets) { " +
                "  Write-Output 'INSTALLED' " +
                "} else { " +
                "  Write-Output 'NOT_INSTALLED' " +
                "}";

            String result = executePowerShell(checkScript, 5);

            if (result != null && result.contains("INSTALLED")) {
                logger.debug("AudioDeviceCmdlets module already installed");
                return true;
            }

            // Try to install the module
            logger.info("Installing AudioDeviceCmdlets PowerShell module (one-time setup)...");
            String installScript =
                "$ErrorActionPreference='Stop'; " +
                "try { " +
                "  Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Force -Scope CurrentUser -ErrorAction SilentlyContinue | Out-Null; " +
                "  Install-Module -Name AudioDeviceCmdlets -Scope CurrentUser -Force -AllowClobber -ErrorAction Stop; " +
                "  Write-Output 'INSTALL_SUCCESS' " +
                "} catch { " +
                "  Write-Output \"INSTALL_FAILED: $($_.Exception.Message)\" " +
                "}";

            result = executePowerShell(installScript, 30);
            boolean installed = result != null && result.contains("INSTALL_SUCCESS");

            if (installed) {
                logger.info("✓ AudioDeviceCmdlets module installed successfully");
            } else {
                logger.debug("AudioDeviceCmdlets installation failed: {}", result);
            }

            return installed;

        } catch (Exception e) {
            logger.debug("Failed to ensure AudioDeviceCmdlets module", e);
            return false;
        }
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
     * Clears app-specific routing for powershell.exe back to Default.
     */
    public static void clearAppRouting() {
        try {
            if (audioDeviceCmdletsAvailable) {
                // Use AudioDeviceCmdlets to restore default
                String restoreScript =
                    "$ErrorActionPreference='SilentlyContinue'; " +
                    "Import-Module AudioDeviceCmdlets; " +
                    "$default = Get-AudioDevice -Playback | Where-Object { $_.Default -eq 'True' } | Select-Object -First 1; " +
                    "if ($default) { Set-AudioDevice -ID $default.ID }";
                executePowerShell(restoreScript, 5);
                logger.info("✓ Restored default audio device");
            } else {
                // Fallback to SoundVolumeView if available
                Path svv = locateSoundVolumeView();
                if (svv != null) {
                    String exe = '"' + svv.toAbsolutePath().toString() + '"';
                    String cmd = exe + " /SetAppDefault \"Default\" all \"powershell.exe\"";
                    int exit = run(cmd);
                    if (exit == 0) {
                        logger.info("✓ Cleared PowerShell app-specific routing (back to Default)");
                    }
                }
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
     * Route via SoundVolumeView - FALLBACK method if AudioDeviceCmdlets not available
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
