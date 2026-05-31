package com.someone.valvoicebackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent Windows SAPI wrapper backed by a long-lived PowerShell process.
 * Handles voice enumeration, native speech execution, and SoundVolumeView PID routing.
 */
public class InbuiltVoiceSynthesizer {
    private static final Logger logger = LoggerFactory.getLogger(InbuiltVoiceSynthesizer.class);

    private static final String VOICES_SENTINEL = "END_OF_VOICES";

    private final Object powershellLock = new Object();
    private final List<String> voices = new ArrayList<>();

    private Process powershellProcess;
    private PrintWriter powershellWriter;
    private BufferedReader powershellReader;
    private Path soundVolumeViewPath;
    private volatile boolean audioRoutingConfigured;

    public static class DependencyMissingException extends RuntimeException {
        private final String dependencyName;
        private final String installUrl;

        public DependencyMissingException(String dependencyName, String message, String installUrl) {
            super(message);
            this.dependencyName = dependencyName;
            this.installUrl = installUrl;
        }

        public String getDependencyName() {
            return dependencyName;
        }

        public String getInstallUrl() {
            return installUrl;
        }
    }

    public InbuiltVoiceSynthesizer() {
        this(false);
    }

    public InbuiltVoiceSynthesizer(boolean strictMode) {
        initializePowerShell();
        soundVolumeViewPath = SystemAudioRouter.resolveSoundVolumeViewPath();

        if (strictMode) {
            validateDependencies();
        }

        loadAvailableVoices();
        routePowerShellAudio();
    }

    private void initializePowerShell() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("powershell.exe", "-NoExit", "-Command", "-");
            processBuilder.redirectErrorStream(true);
            powershellProcess = processBuilder.start();
            powershellWriter = new PrintWriter(new OutputStreamWriter(powershellProcess.getOutputStream()), true);
            powershellReader = new BufferedReader(new InputStreamReader(powershellProcess.getInputStream()));
            logger.info("[SAPI] Started persistent PowerShell process (pid={})", powershellProcess.pid());
        } catch (IOException e) {
            logger.error("[SAPI] Failed to start PowerShell process", e);
        }
    }

    private void validateDependencies() {
        if (!isReady()) {
            throw new DependencyMissingException(
                "PowerShell",
                "powershell.exe could not be started for SAPI speech execution.",
                "https://learn.microsoft.com/powershell/"
            );
        }

        if (soundVolumeViewPath == null || !Files.exists(soundVolumeViewPath)) {
            throw new DependencyMissingException(
                "SoundVolumeView.exe",
                "SoundVolumeView.exe is required for routing the PowerShell audio process to CABLE Input.",
                "https://www.nirsoft.net/utils/sound_volume_view.html"
            );
        }
    }

    private void loadAvailableVoices() {
        if (!isReady()) {
            return;
        }

        synchronized (powershellLock) {
            voices.clear();
            try {
                powershellWriter.println(
                    "Add-Type -AssemblyName System.Speech; " +
                    "$voices = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                    "$voices.GetInstalledVoices() | ForEach-Object { $_.VoiceInfo.Name }; " +
                    "Write-Output '" + VOICES_SENTINEL + "'"
                );

                String line;
                while ((line = powershellReader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (VOICES_SENTINEL.equals(trimmed)) {
                        break;
                    }
                    if (!trimmed.isEmpty()) {
                        voices.add(trimmed);
                    }
                }

                logger.info("[SAPI] Loaded {} installed voices", voices.size());
            } catch (IOException e) {
                logger.warn("[SAPI] Failed to enumerate installed voices", e);
            }
        }
    }

    // Path resolution is now centralized in SystemAudioRouter.resolveSoundVolumeViewPath()
    // which searches: user.dir → JAR dir → %ProgramFiles%/ValVoice/ → %ProgramFiles%/ValorantNarrator/

    private void routePowerShellAudio() {
        if (!isReady()) {
            logger.warn("[AudioRouting] PowerShell process is not available; skipping audio hijack");
            audioRoutingConfigured = false;
            return;
        }

        if (soundVolumeViewPath == null || !Files.exists(soundVolumeViewPath)) {
            logger.warn("[AudioRouting] SoundVolumeView.exe not found at {}", soundVolumeViewPath);
            audioRoutingConfigured = false;
            return;
        }

        long pid = powershellProcess.pid();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                soundVolumeViewPath.toString(),
                "/SetAppDefault",
                "CABLE Input",
                "all",
                String.valueOf(pid)
            );
            pb.redirectErrorStream(true);
            Process routingProcess = pb.start();
            try (BufferedReader routingReader =
                     new BufferedReader(new InputStreamReader(routingProcess.getInputStream()))) {
                while (routingReader.readLine() != null) {
                    // Drain output so the process cannot block on stdout.
                }
            }
            int exitCode = routingProcess.waitFor();
            audioRoutingConfigured = exitCode == 0;

            if (audioRoutingConfigured) {
                logger.info("[AudioRouting] Routed PowerShell PID {} to CABLE Input", pid);
            } else {
                logger.warn("[AudioRouting] SoundVolumeView exited with code {} for PID {}", exitCode, pid);
            }
        } catch (IOException e) {
            audioRoutingConfigured = false;
            logger.warn("[AudioRouting] Failed to execute SoundVolumeView for PID {}", pid, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            audioRoutingConfigured = false;
            logger.warn("[AudioRouting] Audio hijack interrupted for PID {}", pid);
        }
    }

    public void speakInbuiltVoice(String voice, String text, short rate) {
        if (!isReady() || text == null || text.isBlank()) {
            return;
        }

        short sapiRate = (short) (rate / 10.0 - 10);
        String escapedVoice = escapePowerShellString(voice);
        String escapedText = escapePowerShellString(text);
        String sentinel = "END_OF_SPEAK_" + System.nanoTime();

        String command =
            "Add-Type -AssemblyName System.Speech; " +
            "$speaker = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
            "$speaker.SelectVoice('" + escapedVoice + "'); " +
            "$speaker.Rate = " + sapiRate + "; " +
            "$speaker.Speak('" + escapedText + "'); " +
            "Write-Output '" + sentinel + "'";

        synchronized (powershellLock) {
            try {
                powershellWriter.println(command);
                String line;
                while ((line = powershellReader.readLine()) != null) {
                    if (sentinel.equals(line.trim())) {
                        break;
                    }
                }
            } catch (IOException e) {
                logger.error("[SAPI] Failed to execute inbuilt voice speak command", e);
            }
        }
    }

    private String escapePowerShellString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    public List<String> getAvailableVoices() {
        return Collections.unmodifiableList(new ArrayList<>(voices));
    }

    public boolean isReady() {
        return powershellProcess != null
            && powershellProcess.isAlive()
            && powershellWriter != null
            && powershellReader != null;
    }

    public boolean isAudioRoutingConfigured() {
        return audioRoutingConfigured;
    }

    /**
     * Compatibility shim: PTT is owned elsewhere.
     */
    public void setPttEnabled(boolean enabled) {
        // Intentionally no-op.
    }

    public void shutdown() {
        synchronized (powershellLock) {
            try {
                if (powershellWriter != null) {
                    powershellWriter.println("exit");
                    powershellWriter.flush();
                    powershellWriter.close();
                }
                if (powershellReader != null) {
                    powershellReader.close();
                }
            } catch (IOException e) {
                logger.warn("[SAPI] Error while shutting down PowerShell streams", e);
            } finally {
                if (powershellProcess != null && powershellProcess.isAlive()) {
                    powershellProcess.destroy();
                }
            }
        }
    }
}
