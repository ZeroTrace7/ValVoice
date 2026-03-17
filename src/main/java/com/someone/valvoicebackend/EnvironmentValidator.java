package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * EnvironmentValidator - Phase 8 Step 1: Startup Environment Diagnostics.
 *
 * Performs read-only checks for required external dependencies:
 *   - SoundVolumeView.exe (audio routing tool)
 *   - PowerShell (SAPI fallback engine)
 *   - VB-Audio Virtual Cable (audio injection device)
 *
 * This is a pure diagnostic utility — it never modifies application state,
 * throws exceptions, creates threads, or blocks startup.
 *
 * Called once at startup before SystemAudioRouter and JavaFX launch.
 */
public final class EnvironmentValidator {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentValidator.class);

    /** PowerShell validation timeout (seconds) */
    private static final int POWERSHELL_TIMEOUT_SECONDS = 2;

    /** Prevent instantiation — utility class */
    private EnvironmentValidator() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    /**
     * Run all environment validation checks and print a diagnostic report.
     *
     * Checks:
     *   1. SoundVolumeView.exe presence
     *   2. PowerShell availability
     *   3. VB-Audio Virtual Cable detection
     *
     * This method is purely diagnostic — it never throws, never blocks indefinitely,
     * and never modifies any application state.
     */
    public static void runAllChecks() {
        logger.info("[Environment] Checking system dependencies...");

        String svvStatus = checkSoundVolumeView();
        String psStatus = checkPowerShell();
        String vbStatus = checkVbCable();

        // Print summary report
        logger.info("[Environment] ═══════════════════════════════════════");
        logger.info("[Environment] Validation Report");
        logger.info("[Environment]   SoundVolumeView: {}", svvStatus);
        logger.info("[Environment]   PowerShell:      {}", psStatus);
        logger.info("[Environment]   VB-Cable:        {}", vbStatus);
        logger.info("[Environment] ═══════════════════════════════════════");
    }

    /**
     * Run all environment checks and return results as a Map.
     * Phase 8 Step 3: Used by the Setup Wizard to display dependency status in the UI.
     *
     * Keys: "VBCable", "SoundVolumeView", "PowerShell"
     * Values: true if dependency exists, false if missing.
     *
     * This method also logs results via the existing check methods.
     *
     * @return Map of dependency name → availability boolean
     */
    public static Map<String, Boolean> runChecksWithResults() {
        logger.info("[Environment] Checking system dependencies (wizard mode)...");

        Map<String, Boolean> results = new LinkedHashMap<>();

        String svvStatus = checkSoundVolumeView();
        results.put("SoundVolumeView", "OK".equals(svvStatus));

        String psStatus = checkPowerShell();
        results.put("PowerShell", "OK".equals(psStatus));

        String vbStatus = checkVbCable();
        results.put("VBCable", "OK".equals(vbStatus));

        // Log summary
        logger.info("[Environment] Wizard validation: SoundVolumeView={}, PowerShell={}, VBCable={}",
                results.get("SoundVolumeView"), results.get("PowerShell"), results.get("VBCable"));

        return results;
    }

    /**
     * Check whether SoundVolumeView.exe is available.
     *
     * Mirrors the startup router path resolution.
     *
     * @return "OK" if found, "MISSING" if not found
     */
    private static String checkSoundVolumeView() {
        Path resolvedPath = SystemAudioRouter.resolveSoundVolumeViewPath();
        if (resolvedPath != null) {
            logger.info("[Environment] SoundVolumeView: OK ({})", resolvedPath);
            return "OK";
        }

        logger.warn("[Environment] SoundVolumeView.exe not found at {} — audio routing will be skipped.",
            SystemAudioRouter.getExpectedSoundVolumeViewLocation());
        return "MISSING";
    }

    /**
     * Check whether Windows PowerShell is available.
     *
     * Executes a simple echo command with a 2-second timeout.
     * PowerShell is required for the SAPI fallback engine.
     *
     * @return "OK" if available, "NOT AVAILABLE" if missing or timed out
     */
    private static String checkPowerShell() {
        try {
            ProcessBuilder builder = new ProcessBuilder("powershell", "-Command", "echo test");
            builder.redirectErrorStream(true);

            Process process = builder.start();

            // Consume output to prevent pipe blocking
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Drain output
                }
            }

            boolean completed = process.waitFor(POWERSHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                logger.warn("[Environment] PowerShell: NOT AVAILABLE (timed out)");
                return "NOT AVAILABLE";
            }

            if (process.exitValue() == 0) {
                logger.info("[Environment] PowerShell: OK");
                return "OK";
            } else {
                logger.warn("[Environment] PowerShell: NOT AVAILABLE (exit code {})", process.exitValue());
                return "NOT AVAILABLE";
            }

        } catch (Exception e) {
            logger.warn("[Environment] PowerShell: NOT AVAILABLE ({})", e.getMessage());
            return "NOT AVAILABLE";
        }
    }

    /**
     * Detect VB-Audio Virtual Cable using Java's native audio API.
     *
     * Scans all available audio mixer devices for names containing "CABLE".
     * This matches both "CABLE Input" and "CABLE Output" device names.
     *
     * @return "OK" if detected, "NOT DETECTED" if no VB-Cable device found
     */
    private static String checkVbCable() {
        try {
            Mixer.Info[] mixers = AudioSystem.getMixerInfo();

            for (Mixer.Info info : mixers) {
                String name = info.getName();
                String description = info.getDescription();

                if ((name != null && name.toUpperCase().contains("CABLE"))
                        || (description != null && description.toUpperCase().contains("CABLE"))) {
                    logger.info("[Environment] VB-Cable: OK (detected: {})", name);
                    return "OK";
                }
            }
        } catch (Exception e) {
            logger.debug("[Environment] Error scanning audio devices: {}", e.getMessage());
        }

        logger.warn("[Environment] VB-Cable not detected — install VB-Audio Virtual Cable for voice injection.");
        return "NOT DETECTED";
    }
}

