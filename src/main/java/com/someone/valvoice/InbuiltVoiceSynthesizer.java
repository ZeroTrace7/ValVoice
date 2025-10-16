package com.someone.valvoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Persistent PowerShell-based Windows voice synthesizer using System.Speech.
 *
 * Responsibilities:
 *  - Launch a reusable hidden PowerShell process (-NoExit) for lower latency voice playback.
 *  - Enumerate installed System.Speech voices (friendly names) on initialization.
 *  - Provide simple speak method with selectable voice + rate conversion (0..100 UI -> -10..10 SAPI range).
 *  - (Best effort) route audio through VB-CABLE if SoundVolumeView.exe is present (mirrors original logic).
 *
 * Notes:
 *  - This is Windows-only. Guard usages with OS checks.
 *  - Output from speak commands isn't consumed (they produce none on success). We only read during enumeration.
 *  - If the persistent process dies, further speaks will be ignored; could be enhanced with auto-restart logic.
 */
public class InbuiltVoiceSynthesizer {
    private static final Logger logger = LoggerFactory.getLogger(InbuiltVoiceSynthesizer.class);
    private static final String END_SENTINEL = "END_OF_VOICES";

    private Process powershellProcess;
    private PrintWriter psWriter;
    private BufferedReader psReader;
    private BufferedReader psErrorReader;
    private final List<String> voices = new ArrayList<>();
    private final AtomicBoolean ready = new AtomicBoolean(false);

    // Performance: Queue for async speaking to prevent blocking
    private final BlockingQueue<SpeakCommand> speakQueue = new LinkedBlockingQueue<>(50);
    private Thread speakWorker;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);

    public InbuiltVoiceSynthesizer() {
        if (!isWindows()) {
            logger.warn("InbuiltVoiceSynthesizer initialized on non-Windows OS; disabled");
            return;
        }
        try {
            // Start persistent PowerShell session. Using "-" trick with -Command to keep session open.
            powershellProcess = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-NoExit", "-Command", "-")
                    .redirectErrorStream(false)
                    .start();
            psWriter = new PrintWriter(new OutputStreamWriter(powershellProcess.getOutputStream(), StandardCharsets.UTF_8), true);
            psReader = new BufferedReader(new InputStreamReader(powershellProcess.getInputStream(), StandardCharsets.UTF_8));
            psErrorReader = new BufferedReader(new InputStreamReader(powershellProcess.getErrorStream(), StandardCharsets.UTF_8));

            // Start error stream consumer to prevent buffer blocking
            startErrorStreamConsumer();

            enumerateVoices();
            configureAppAudioRoute();

            // Start worker thread for async speaking
            startSpeakWorker();

            if (!voices.isEmpty()) {
                speakInbuiltVoice(voices.get(0), "Inbuilt voice synthesizer initialized.", 50);
            }
            ready.set(true);
            logger.info("InbuiltVoiceSynthesizer initialized successfully");
        } catch (IOException e) {
            logger.error("Failed to start persistent PowerShell for inbuilt synthesizer", e);
            cleanup();
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private void startErrorStreamConsumer() {
        Thread errorConsumer = new Thread(() -> {
            try {
                String line;
                while ((line = psErrorReader.readLine()) != null) {
                    if (!line.isBlank()) {
                        logger.trace("PS stderr: {}", line);
                    }
                }
            } catch (IOException e) {
                logger.trace("Error stream consumer stopped", e);
            }
        }, "PS-ErrorConsumer");
        errorConsumer.setDaemon(true);
        errorConsumer.start();
    }

    private void startSpeakWorker() {
        workerRunning.set(true);
        speakWorker = new Thread(() -> {
            while (workerRunning.get()) {
                try {
                    SpeakCommand cmd = speakQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (cmd != null) {
                        executeSpeakCommand(cmd);
                    }
                } catch (InterruptedException e) {
                    if (!workerRunning.get()) break;
                } catch (Exception e) {
                    logger.debug("Speak worker error", e);
                }
            }
            logger.debug("Speak worker stopped");
        }, "InbuiltSynth-Worker");
        speakWorker.setDaemon(true);
        speakWorker.start();
    }

    private void enumerateVoices() {
        try {
            // Optimized command to reduce output parsing
            String command = String.join(";",
                    "$ErrorActionPreference='SilentlyContinue'",
                    "Add-Type -AssemblyName System.Speech",
                    "$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer",
                    // Convert to CSV to get clean lines; skip header
                    "$speak.GetInstalledVoices() | Select-Object -ExpandProperty VoiceInfo | Select-Object -Property Name | ConvertTo-Csv -NoTypeInformation | Select-Object -Skip 1",
                    "echo '" + END_SENTINEL + "'"
            );
            send(command);

            String line;
            int timeout = 0;
            while (timeout < 100) { // 10 second timeout
                if (psReader.ready()) {
                    line = psReader.readLine();
                    if (line == null) break;
                    String trimmed = line.trim();
                    if (trimmed.equals(END_SENTINEL)) break;
                    if (!trimmed.isEmpty()) {
                        // CSV line => "VoiceName"; remove quotes if present
                        voices.add(trimmed.replace("\"", "").trim());
                    }
                } else {
                    Thread.sleep(100);
                    timeout++;
                }
            }

            if (voices.isEmpty()) {
                logger.warn("No inbuilt voices found via System.Speech enumeration");
            } else {
                logger.info("InbuiltVoiceSynthesizer detected {} voices", voices.size());
            }
        } catch (Exception e) {
            logger.error("Error enumerating inbuilt voices", e);
        }
    }

    private void configureAppAudioRoute() {
        try {
            // Check working directory first (same folder as JAR)
            File svv = new File(System.getProperty("user.dir"), "SoundVolumeView.exe");

            // If not in working dir, check Program Files
            if (!svv.exists()) {
                String programFiles = System.getenv("ProgramFiles");
                if (programFiles != null) {
                    svv = new File(programFiles, "ValVoice/SoundVolumeView.exe");
                    if (!svv.exists()) {
                        svv = new File(programFiles, "SoundVolumeView.exe");
                    }
                }
            }

            if (!svv.exists()) {
                logger.debug("SoundVolumeView.exe not found; skipping VB-CABLE routing");
                return;
            }
            if (powershellProcess == null) return;
            long pid = powershellProcess.pid();
            // Route to CABLE Input if installed; swallow failures
            String cmd = '"' + svv.getAbsolutePath() + '"' + " /SetAppDefault \"CABLE Input\" all " + pid;
            Runtime.getRuntime().exec(cmd);
            logger.info("Attempted audio routing of PowerShell PID {} via SoundVolumeView", pid);
        } catch (Exception e) {
            logger.warn("Audio routing (SoundVolumeView) failed", e);
        }
    }

    private synchronized void send(String psCommand) {
        if (psWriter == null || !ready.get()) return;
        psWriter.println(psCommand);
        psWriter.flush();
    }

    public boolean isReady() { return ready.get(); }

    /**
     * Return immutable list of discovered voices.
     */
    public List<String> getAvailableVoices() {
        return Collections.unmodifiableList(voices);
    }

    /**
     * Speak text using the persistent System.Speech synthesizer session.
     * @param voice exact Name as returned by getAvailableVoices(); null => default voice
     * @param text phrase to speak
     * @param uiRate 0..100 UI slider value (converted to -10..10 SAPI rate)
     */
    public void speakInbuiltVoice(String voice, String text, int uiRate) {
        if (text == null || text.isBlank()) return;
        if (!ready.get()) {
            logger.debug("speakInbuiltVoice called but synthesizer not ready");
            return;
        }

        // Convert UI 0..100 => -10..10 (integer)
        int sapiRate = (int) Math.round((uiRate / 100.0) * 20.0 - 10.0);
        sapiRate = Math.max(-10, Math.min(10, sapiRate));

        // Queue the command for async execution
        boolean offered = speakQueue.offer(new SpeakCommand(voice, text, sapiRate));
        if (!offered) {
            logger.warn("Inbuilt synthesizer queue full, dropping speak request");
        }
    }

    private void executeSpeakCommand(SpeakCommand cmd) {
        if (psWriter == null || !ready.get()) {
            return;
        }

        String safeText = escape(cmd.text);
        String select = (cmd.voice != null && !cmd.voice.isBlank())
                ? String.format("$speak.SelectVoice('%s');", escape(cmd.voice))
                : "";

        // Optimized: Reuse synthesizer object instead of creating new one each time
        String psCommand = String.format(
                "$ErrorActionPreference='SilentlyContinue';" +
                "if(-not $global:synth){ $global:synth = New-Object System.Speech.Synthesis.SpeechSynthesizer };" +
                "%s$global:synth.Rate=%d;$global:synth.Speak('%s');",
                select, cmd.sapiRate, safeText);

        try {
            send(psCommand);
        } catch (Exception e) {
            logger.warn("Failed to speak text via inbuilt synthesizer", e);
        }
    }

    private String escape(String s) {
        return s.replace("'", "''").replace("\n", " ").replace("\r", " ");
    }

    /**
     * Stop the persistent PowerShell process.
     */
    public void shutdown() {
        cleanup();
    }

    private void cleanup() {
        ready.set(false);
        workerRunning.set(false);

        if (speakWorker != null) {
            speakWorker.interrupt();
            try {
                speakWorker.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (psWriter != null) {
            try {
                psWriter.println("exit");
                psWriter.flush();
            } catch (Exception ignored) {}
        }
        closeQuiet(psReader);
        closeQuiet(psErrorReader);
        if (powershellProcess != null) {
            try {
                powershellProcess.destroy();
                if (!powershellProcess.waitFor(2, TimeUnit.SECONDS)) {
                    powershellProcess.destroyForcibly();
                }
            } catch (Exception ignored) {}
        }
        psWriter = null;
        psReader = null;
        psErrorReader = null;
        powershellProcess = null;
        logger.debug("InbuiltVoiceSynthesizer cleanup complete");
    }

    private void closeQuiet(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) {}
    }

    private record SpeakCommand(String voice, String text, int sapiRate) {}
}
