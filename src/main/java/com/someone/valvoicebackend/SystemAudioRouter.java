package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Startup-only Windows audio routing helper.
 *
 * Executes the SoundVolumeView hijack sequence against the current Java PID so
 * the JVM's playback is routed into VB-Cable before the rest of the application boots.
 */
public final class SystemAudioRouter {

    private static final Logger logger = LoggerFactory.getLogger(SystemAudioRouter.class);

    private static final String SVV_FILENAME = "SoundVolumeView.exe";
    private static final String VB_CABLE_CAPTURE_DEVICE = "VB-Audio Virtual Cable\\Device\\CABLE Output\\Capture";
    private static final String RIOT_INPUT_DEVICE_KEY = "EAresStringSettingName::VoiceDeviceCaptureHandle=";
    private static final int COMMAND_TIMEOUT_SECONDS = 2;
    private static final AtomicBoolean ROUTING_ATTEMPTED = new AtomicBoolean(false);

    private SystemAudioRouter() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    public static void routeApplicationAudio() {
        if (!ROUTING_ATTEMPTED.compareAndSet(false, true)) {
            logger.debug("[AudioRouting] Startup audio hijack already attempted");
            return;
        }

        try {
            Path soundVolumeViewPath = resolveSoundVolumeViewPath();
            if (soundVolumeViewPath == null) {
                logger.warn("[AudioRouting] Startup audio hijack skipped: SoundVolumeView.exe not found");
                logCandidatePathsChecked();
                return;
            }

            String fileLocation = soundVolumeViewPath.toString();
            long pid = ProcessHandle.current().pid();

            logger.info("[AudioRouting] Applying SoundVolumeView startup hijack for Java PID {}", pid);

            executeCommand(fileLocation, "/SetAppDefault", "CABLE Input", "all", String.valueOf(pid));
            executeCommand(fileLocation, "/SetPlaybackThroughDevice", "CABLE Output", "Default Playback Device");
            executeCommand(fileLocation, "/SetListenToThisDevice", "CABLE Output", "1");
            executeCommand(fileLocation, "/unmute", "CABLE Output");

            logger.info("[AudioRouting] Startup audio hijack sequence completed for PID {}", pid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[AudioRouting] Startup audio hijack interrupted");
        } catch (Exception e) {
            logger.warn("[AudioRouting] Startup audio hijack failed: {}", e.getMessage());
        }
    }

    /**
     * Resolve SoundVolumeView.exe using a deterministic 4-location search.
     *
     * Search order (first match wins):
     *   1. Working directory (user.dir) — development / portable use
     *   2. Application/JAR directory — packaged distribution
     *   3. %ProgramFiles%/ValVoice/ — ValVoice installer location
     *   4. %ProgramFiles%/ValorantNarrator/ — legacy VN backward compat
     *
     * @return Resolved path to SoundVolumeView.exe, or null if not found
     */
    public static Path resolveSoundVolumeViewPath() {
        for (Path candidate : getCandidatePaths()) {
            if (Files.isRegularFile(candidate)) {
                logger.debug("[AudioRouting] SoundVolumeView.exe resolved: {}", candidate);
                return candidate;
            }
        }
        return null;
    }

    /**
     * Build the ordered list of candidate paths to check for SoundVolumeView.exe.
     *
     * Duplicate paths are suppressed (e.g. if JAR dir == working dir).
     *
     * @return Unmodifiable list of candidate paths (may be empty if no env vars are set)
     */
    public static List<Path> getCandidatePaths() {
        List<Path> candidates = new ArrayList<>();

        // 1. Working directory (user.dir)
        Path workingDir = Paths.get(System.getProperty("user.dir", ".")).resolve(SVV_FILENAME).toAbsolutePath().normalize();
        candidates.add(workingDir);

        // 2. Application/JAR directory (if different from working dir)
        try {
            URI codeSourceUri = SystemAudioRouter.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path codeSourcePath = Paths.get(codeSourceUri);
            Path jarDir = Files.isDirectory(codeSourcePath) ? codeSourcePath : codeSourcePath.getParent();
            if (jarDir != null) {
                Path jarCandidate = jarDir.resolve(SVV_FILENAME).toAbsolutePath().normalize();
                if (!jarCandidate.equals(workingDir)) {
                    candidates.add(jarCandidate);
                }
            }
        } catch (Exception e) {
            // ProtectionDomain may be unavailable in some classloader configs — skip this candidate
            logger.debug("[AudioRouting] Could not resolve JAR directory for SVV lookup: {}", e.getMessage());
        }

        // 3. %ProgramFiles%/ValVoice/
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null && !programFiles.isBlank()) {
            candidates.add(Paths.get(programFiles, "ValVoice", SVV_FILENAME));
            // 4. %ProgramFiles%/ValorantNarrator/ (legacy backward compat)
            candidates.add(Paths.get(programFiles, "ValorantNarrator", SVV_FILENAME));
        }

        return Collections.unmodifiableList(candidates);
    }

    /**
     * Log all candidate paths that were checked when SoundVolumeView.exe was not found.
     * Provides honest diagnostic output instead of reporting only the legacy VN path.
     */
    private static void logCandidatePathsChecked() {
        List<Path> candidates = getCandidatePaths();
        logger.warn("[AudioRouting] SoundVolumeView.exe not found. Checked {} locations:", candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            logger.warn("[AudioRouting]   {}. {}", i + 1, candidates.get(i));
        }
    }

    public static void injectValorantInputDevice(String subjectId, String deployment) {
        try {
            if (subjectId == null || subjectId.isBlank() || deployment == null || deployment.isBlank()) {
                logger.warn("[AudioRouting] Skipping RiotUserSettings.ini injection: subjectId/deployment missing");
                return;
            }

            Path soundVolumeViewPath = resolveSoundVolumeViewPath();
            if (soundVolumeViewPath == null) {
                logger.warn("[AudioRouting] Skipping RiotUserSettings.ini injection: SoundVolumeView.exe not found");
                logCandidatePathsChecked();
                return;
            }

            String localAppData = System.getenv("LocalAppData");
            if (localAppData == null || localAppData.isBlank()) {
                localAppData = System.getenv("LOCALAPPDATA");
            }
            if (localAppData == null || localAppData.isBlank()) {
                logger.warn("[AudioRouting] Skipping RiotUserSettings.ini injection: LocalAppData is not set");
                return;
            }

            Path settingsPath = Paths.get(
                localAppData,
                "VALORANT",
                "Saved",
                "Config",
                subjectId + "-" + deployment,
                "Windows",
                "RiotUserSettings.ini"
            );

            if (!Files.isRegularFile(settingsPath)) {
                logger.warn("[AudioRouting] RiotUserSettings.ini not found at {}", settingsPath);
                return;
            }

            ProcessBuilder processBuilder = new ProcessBuilder(
                soundVolumeViewPath.toString(),
                "/GetColumnValue",
                VB_CABLE_CAPTURE_DEVICE,
                "Item ID"
            );
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            final String id;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                id = reader.readLine().split("\\}\\.\\{")[1].replace("}", "");
                while (reader.readLine() != null) {
                    // Drain remaining output to avoid blocking if SoundVolumeView emits extra lines.
                }
            }

            boolean completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                logger.warn("[AudioRouting] SoundVolumeView GUID extraction timed out");
                return;
            }

            if (process.exitValue() != 0) {
                logger.warn("[AudioRouting] SoundVolumeView GUID extraction failed with exit code {}", process.exitValue());
                return;
            }

            List<String> lines;
            try (BufferedReader fileReader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                lines = fileReader.lines().collect(Collectors.toCollection(ArrayList::new));
            }
            String replacement = RIOT_INPUT_DEVICE_KEY + "\"{" + id + "}\"";
            boolean replaced = false;

            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith(RIOT_INPUT_DEVICE_KEY)) {
                    lines.set(i, replacement);
                    replaced = true;
                    break;
                }
            }

            if (!replaced) {
                lines.add(replacement);
            }

            try (BufferedWriter fileWriter = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                for (int i = 0; i < lines.size(); i++) {
                    fileWriter.write(lines.get(i));
                    if (i < lines.size() - 1) {
                        fileWriter.newLine();
                    }
                }
            }
            logger.info("[AudioRouting] Injected Valorant input device GUID into {}", settingsPath);
        } catch (Exception e) {
            logger.warn("[AudioRouting] RiotUserSettings.ini injection failed: {}", e.getMessage());
        }
    }

    private static void executeCommand(String... command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while (reader.readLine() != null) {
                // Drain output to avoid blocking on full buffers.
            }
        }

        boolean completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            logger.warn("[AudioRouting] SoundVolumeView command timed out: {}", String.join(" ", command));
            return;
        }

        if (process.exitValue() != 0) {
            logger.warn("[AudioRouting] SoundVolumeView command failed with exit code {}: {}",
                process.exitValue(), String.join(" ", command));
        } else {
            logger.debug("[AudioRouting] SoundVolumeView command completed: {}", String.join(" ", command));
        }
    }
}
