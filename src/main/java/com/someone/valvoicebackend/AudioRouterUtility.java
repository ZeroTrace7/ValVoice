package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * AudioRouterUtility - VN-Parity Phase 5 Step 3
 *
 * Routes Java audio output to VB-Cable (Virtual Audio Cable) using SoundVolumeView.exe.
 * This enables JavaFX MediaPlayer audio to flow into Valorant's microphone input.
 *
 * Audio Flow:
 *   JavaFX MediaPlayer → CABLE Input (VB-Cable) → CABLE Output → Valorant Mic Input
 *
 * This utility is designed to be:
 * - Called once at application startup
 * - Fire-and-forget (no retries, no blocking)
 * - Gracefully degraded if SoundVolumeView.exe is missing
 * - Completely independent of other backend classes
 *
 * @author ValVoice Team
 * @since Phase 5 Step 3
 */
public final class AudioRouterUtility {

    private static final Logger logger = LoggerFactory.getLogger(AudioRouterUtility.class);

    /** SoundVolumeView executable name */
    private static final String SVV_EXECUTABLE = "SoundVolumeView.exe";

    /** VB-Cable device name as it appears in Windows */
    private static final String VB_CABLE_DEVICE = "CABLE Input";

    /** Maximum time to wait for SoundVolumeView command (seconds) */
    private static final int COMMAND_TIMEOUT_SECONDS = 2;

    /** Prevent instantiation - utility class */
    private AudioRouterUtility() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    /**
     * Route Java audio output to VB-Cable (Virtual Audio Cable).
     *
     * This method:
     * 1. Locates SoundVolumeView.exe in the working directory
     * 2. Executes commands to route java.exe and javaw.exe to CABLE Input
     * 3. Logs results
     * 4. Fails gracefully if executable is missing
     *
     * Should be called ONCE at application startup, before any TTS playback.
     *
     * VN-Parity: Matches ValorantNarrator's audio routing behavior.
     */
    public static void routeAudioToVirtualCable() {
        logger.info("[AudioRouter] Configuring Java audio routing to VB-Cable...");

        // Step 1: Locate SoundVolumeView.exe
        Path svvPath = resolveSoundVolumeViewPath();

        if (svvPath == null || !Files.exists(svvPath)) {
            logger.warn("[AudioRouter] SoundVolumeView.exe not found. Audio routing skipped. User must configure manually.");
            return;
        }

        logger.debug("[AudioRouter] Found SoundVolumeView.exe at: {}", svvPath);

        // Step 2: Execute routing commands for both Java runtimes
        boolean javaRouted = routeProcess(svvPath, "java.exe");
        boolean javawRouted = routeProcess(svvPath, "javaw.exe");

        // Step 3: Log final result
        if (javaRouted || javawRouted) {
            logger.info("[AudioRouter] Routed Java audio to VB-Cable successfully.");
        } else {
            logger.warn("[AudioRouter] Failed to route audio using SoundVolumeView. VB-Cable may not be installed.");
        }
    }

    /**
     * Resolve the path to SoundVolumeView.exe.
     *
     * Checks the working directory (project root).
     *
     * @return Path to SoundVolumeView.exe, or null if not found
     */
    private static Path resolveSoundVolumeViewPath() {
        // Primary location: working directory (project root)
        Path workingDir = Paths.get(System.getProperty("user.dir"), SVV_EXECUTABLE);
        if (Files.exists(workingDir)) {
            return workingDir;
        }

        // Alternative: same directory as the JAR
        try {
            Path jarDir = Paths.get(
                AudioRouterUtility.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
            ).getParent();

            Path jarDirPath = jarDir.resolve(SVV_EXECUTABLE);
            if (Files.exists(jarDirPath)) {
                return jarDirPath;
            }
        } catch (Exception e) {
            logger.debug("[AudioRouter] Could not resolve JAR directory: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Route a specific Java process to VB-Cable.
     *
     * Executes: SoundVolumeView.exe /SetAppDefault "{processName}" 0 "CABLE Input"
     *
     * @param svvPath Path to SoundVolumeView.exe
     * @param processName The Java process name (java.exe or javaw.exe)
     * @return true if command succeeded, false otherwise
     */
    private static boolean routeProcess(Path svvPath, String processName) {
        try {
            // Build command: SoundVolumeView.exe /SetAppDefault "java.exe" 0 "CABLE Input"
            ProcessBuilder builder = new ProcessBuilder(
                svvPath.toString(),
                "/SetAppDefault",
                processName,
                "0",  // 0 = default render device
                VB_CABLE_DEVICE
            );

            // Configure process
            builder.redirectErrorStream(true);
            // Do NOT use inheritIO() - keep process hidden

            // Execute
            Process process = builder.start();

            // Consume output to prevent blocking
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("[AudioRouter] SVV output: {}", line);
                }
            }

            // Wait with timeout
            boolean completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!completed) {
                // Timeout - destroy process
                process.destroyForcibly();
                logger.warn("[AudioRouter] SoundVolumeView timed out for {}", processName);
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                logger.debug("[AudioRouter] Successfully routed {} to {}", processName, VB_CABLE_DEVICE);
                return true;
            } else {
                // Non-zero exit may indicate VB-Cable not found or other issue
                logger.debug("[AudioRouter] SoundVolumeView returned exit code {} for {}", exitCode, processName);
                return false;
            }

        } catch (Exception e) {
            logger.warn("[AudioRouter] Error routing {}: {}", processName, e.getMessage());
            return false;
        }
    }

    /**
     * Route a specific process (by PID) to VB-Cable using SoundVolumeView.
     *
     * Security: Uses ProcessBuilder with separated arguments to prevent command injection (CWE-78).
     * Handles paths containing spaces safely.
     *
     * Command: SoundVolumeView.exe /SetAppDefault "CABLE Input" all {PID}
     *
     * @param soundVolumeViewPath Absolute path to SoundVolumeView.exe
     * @param pid Process ID to route
     * @return true if routing succeeded, false otherwise
     */
    public static boolean routeProcessToCable(String soundVolumeViewPath, long pid) {
        if (soundVolumeViewPath == null || soundVolumeViewPath.isEmpty()) {
            logger.warn("[AudioRouter] Cannot route PID {}: SoundVolumeView path is null", pid);
            return false;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                soundVolumeViewPath,
                "/SetAppDefault",
                VB_CABLE_DEVICE,
                "all",
                String.valueOf(pid)
            );
            pb.redirectErrorStream(true);

            Process routeProcess = pb.start();

            // Consume output to prevent process blocking
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(routeProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("[AudioRouter] {}", line);
                }
            }

            boolean completed = routeProcess.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!completed) {
                routeProcess.destroyForcibly();
                logger.warn("[AudioRouter] Timed out routing PID {}", pid);
                return false;
            }

            int exitCode = routeProcess.exitValue();
            if (exitCode == 0) {
                logger.debug("[AudioRouter] Routed PID {} to VB-Cable", pid);
                return true;
            } else {
                logger.debug("[AudioRouter] SoundVolumeView returned exit code {} for PID {}", exitCode, pid);
                return false;
            }

        } catch (Exception e) {
            logger.warn("[AudioRouter] Failed to route process {}", pid, e);
            return false;
        }
    }
}

